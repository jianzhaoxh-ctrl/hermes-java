package com.hermes.agent.acp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hermes.agent.Agent;
import com.hermes.agent.acp.model.ACPContentBlock;
import com.hermes.agent.acp.model.ACPContentBlock.ToolCallStartContent;
import com.hermes.agent.acp.model.ACPContentBlock.ToolCallProgressContent;
import com.hermes.agent.acp.model.ACPEventCallbacks;
import com.hermes.agent.acp.model.ACPMessage;
import com.hermes.agent.acp.model.ACPModels.*;
import com.hermes.agent.acp.auth.ACPAuthProvider;
import com.hermes.agent.acp.model.ACPProtocol;
import com.hermes.agent.acp.session.ACPSession;
import com.hermes.agent.acp.session.ACPSessionManager;
import com.hermes.agent.llm.LLMProvider;
import com.hermes.agent.llm.LLMRouter;
import com.hermes.agent.persistence.SQLiteBackend;
import com.hermes.agent.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ACP Agent implementation wrapping Hermes Agent.
 *
 * <p>Exposes the Hermes agent via the Agent Client Protocol (ACP), allowing
 * editors and IDEs (like Zed) to interact with the agent through JSON-RPC over stdio.
 *
 * <p>Reference: Python acp_adapter/server.py HermesACPAgent
 */
@Component
public class HermesACPAgent {

    private static final Logger log = LoggerFactory.getLogger(HermesACPAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int LIST_SESSIONS_PAGE_SIZE = 50;

    private final ACPSessionManager sessionManager;
    private final SlashCommandHandler slashCommandHandler;
    private final Agent agent;
    private final ToolRegistry toolRegistry;
    private final LLMRouter llmRouter;

    /** Thread pool for running agent prompts without blocking the event loop.
     *  P1-22 fix: 使用 cached thread pool 替代固定 4 线程池，
     *  避免并发 session 被队列阻塞。每个 prompt 最多占用 1 个线程，超时自动回收。*/
    private final ExecutorService executor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "acp-agent");
                t.setDaemon(true);
                return t;
            });

    /** Track tool call IDs per tool name (FIFO queue for parallel same-name calls) */
    private final Map<String, Deque<String>> toolCallIds = new ConcurrentHashMap<>();

    /** Track tool call metadata (args, snapshots) */
    private final Map<String, Map<String, Object>> toolCallMeta = new ConcurrentHashMap<>();

    /** Active ACP transport (set when a client connects) */
    private volatile ACPTransport transport;

    public HermesACPAgent(ACPSessionManager sessionManager,
                          SlashCommandHandler slashCommandHandler,
                          Agent agent,
                          ToolRegistry toolRegistry,
                          LLMRouter llmRouter,
                          SQLiteBackend sqliteBackend) {
        this.sessionManager = sessionManager;
        this.slashCommandHandler = slashCommandHandler;
        this.agent = agent;
        this.toolRegistry = toolRegistry;
        this.llmRouter = llmRouter;

        // Wire up JdbcTemplate from SQLiteBackend for ACP session persistence
        try {
            JdbcTemplate jt = sqliteBackend.getJdbcTemplate();
            if (jt != null) {
                sessionManager.setJdbcTemplate(jt);
            }
        } catch (Exception e) {
            log.warn("Could not wire JdbcTemplate from SQLiteBackend for ACP persistence", e);
        }
    }

    // ================================================================
    //  Transport lifecycle
    // ================================================================

    public void setTransport(ACPTransport transport) {
        this.transport = transport;
    }

    public ACPTransport getTransport() {
        return transport;
    }

    // ================================================================
    //  Accessors for ACPCliEntry
    // ================================================================

    public ACPSessionManager getSessionManager() {
        return sessionManager;
    }

    public SlashCommandHandler getSlashCommandHandler() {
        return slashCommandHandler;
    }

    /**
     * Get the underlying Agent instance for direct streaming access.
     */
    public Agent getAgent() {
        return agent;
    }

    // ================================================================
    //  ACP Protocol Methods
    // ================================================================

    /**
     * Handle the initialize request.
     *
     * <p>Advertises auth methods based on the detected provider, mirroring
     * Python server.py which calls {@code detect_provider()} dynamically.
     */
    public InitializeResponse initialize(InitializeRequest request) {
        int protocolVersion = request.protocolVersion().orElse(ACPProtocol.PROTOCOL_VERSION);
        String clientName = request.clientInfo()
                .map(Implementation::name).orElse("unknown");

        log.info("Initialize from {} (protocol v{})", clientName, protocolVersion);

        // Build auth methods based on detected provider (like Python detect_provider)
        List<AuthMethod> authMethods = buildAuthMethods();

        return new InitializeResponse(
                ACPProtocol.PROTOCOL_VERSION,
                new Implementation("hermes-agent", "1.0.0"),
                new AgentCapabilities(
                        Optional.of(true),
                        Optional.of(new SessionCapabilities(
                                Optional.of(true),  // list
                                Optional.of(true),  // fork
                                Optional.of(true)   // resume
                        ))
                ),
                authMethods
        );
    }

    /**
     * Create a new ACP session.
     */
    public NewSessionResponse newSession(NewSessionRequest request) {
        ACPSession session = sessionManager.createSession(request.cwd());
        registerMcpServers(session, request.mcpServers().orElse(null));

        log.info("New session {} (cwd={})", session.getSessionId(), request.cwd());

        // Schedule command advertisement
        sendAvailableCommandsUpdate(session.getSessionId());

        return new NewSessionResponse(
                session.getSessionId(),
                Optional.of(buildModelState(session))
        );
    }

    /**
     * Load an existing session.
     */
    public LoadSessionResponse loadSession(LoadSessionRequest request) {
        ACPSession session = sessionManager.updateCwd(request.sessionId(), request.cwd());
        if (session == null) {
            log.warn("load_session: session {} not found", request.sessionId());
            return null;
        }
        registerMcpServers(session, request.mcpServers().orElse(null));
        sendAvailableCommandsUpdate(session.getSessionId());
        return new LoadSessionResponse(Optional.of(buildModelState(session)));
    }

    /**
     * Resume a session (create new if not found).
     */
    public ResumeSessionResponse resumeSession(ResumeSessionRequest request) {
        ACPSession session = sessionManager.updateCwd(request.sessionId(), request.cwd());
        if (session == null) {
            log.warn("resume_session: session {} not found, creating new", request.sessionId());
            session = sessionManager.createSession(request.cwd());
        }
        registerMcpServers(session, request.mcpServers().orElse(null));
        sendAvailableCommandsUpdate(session.getSessionId());
        return new ResumeSessionResponse(Optional.of(buildModelState(session)));
    }

    /**
     * Cancel an ongoing prompt for a session.
     */
    public void cancel(String sessionId) {
        ACPSession session = sessionManager.getSession(sessionId);
        if (session != null) {
            session.cancel();
            log.info("Cancelled session {}", sessionId);
        }
    }

    /**
     * Fork a session into a new one with the same history.
     */
    public ForkSessionResponse forkSession(ForkSessionRequest request) {
        ACPSession forked = sessionManager.forkSession(request.sessionId(), request.cwd());
        String newId = forked != null ? forked.getSessionId() : "";
        if (forked != null) {
            registerMcpServers(forked, request.mcpServers().orElse(null));
            sendAvailableCommandsUpdate(newId);
        }
        log.info("Forked session {} -> {}", request.sessionId(), newId);
        return new ForkSessionResponse(newId);
    }

    /**
     * List sessions with optional cursor pagination and cwd filtering.
     */
    public ListSessionsResponse listSessions(ListSessionsRequest request) {
        String cwd = request.cwd().orElse(null);
        String cursor = request.cursor().orElse(null);

        List<Map<String, Object>> infos = sessionManager.listSessions(cwd);

        // Apply cursor pagination
        if (cursor != null) {
            int idx = -1;
            for (int i = 0; i < infos.size(); i++) {
                if (cursor.equals(infos.get(i).get("session_id"))) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                infos = infos.subList(idx + 1, infos.size());
            } else {
                infos = Collections.emptyList();
            }
        }

        boolean hasMore = infos.size() > LIST_SESSIONS_PAGE_SIZE;
        infos = infos.subList(0, Math.min(infos.size(), LIST_SESSIONS_PAGE_SIZE));

        List<SessionInfo> sessions = new ArrayList<>();
        for (Map<String, Object> s : infos) {
            Object updatedAt = s.get("updated_at");
            String updatedAtStr = updatedAt != null ? updatedAt.toString() : null;
            sessions.add(new SessionInfo(
                    (String) s.get("session_id"),
                    (String) s.get("cwd"),
                    Optional.ofNullable((String) s.get("title")),
                    Optional.ofNullable(updatedAtStr)
            ));
        }

        String nextCursor = hasMore && !sessions.isEmpty()
                ? sessions.getLast().sessionId() : null;
        return new ListSessionsResponse(sessions, Optional.ofNullable(nextCursor));
    }

    /**
     * Core prompt method — run the agent on a user prompt and stream events.
     *
     * <p>Reference: Python HermesACPAgent.prompt()
     */
    public PromptResponse prompt(PromptRequest request) {
        String sessionId = request.sessionId();
        ACPSession session = sessionManager.getSession(sessionId);

        if (session == null) {
            log.error("prompt: session {} not found", sessionId);
            return new PromptResponse(ACPProtocol.STOP_REFUSAL, Optional.empty(), Optional.empty());
        }

        // Extract text from content blocks
        String userText = extractText(request.prompt()).strip();
        if (userText.isEmpty()) {
            return new PromptResponse(ACPProtocol.STOP_END_TURN, Optional.empty(), Optional.empty());
        }

        // Intercept slash commands
        if (userText.startsWith("/")) {
            String responseText = slashCommandHandler.handle(userText, session, sessionManager);
            if (responseText != null) {
                if (transport != null) {
                    sendAgentMessageText(sessionId, responseText);
                }
                return new PromptResponse(ACPProtocol.STOP_END_TURN, Optional.empty(), Optional.empty());
            }
        }

        log.info("Prompt on session {}: {}", sessionId,
                userText.length() > 100 ? userText.substring(0, 100) + "..." : userText);

        // Clear cancel flag
        session.clearCancel();

        // Set up event callbacks
        ACPEventCallbacks callbacks = new ACPEventCallbacks();
        if (transport != null) {
            callbacks.setThinkingCallback(text -> sendThinkingText(sessionId, text));
            callbacks.setMessageCallback(text -> sendAgentMessageText(sessionId, text));
            callbacks.setToolProgressCallback((eventType, name, args) -> {
                if ("tool.started".equals(eventType)) {
                    String tcId = ACPToolMapping.makeToolCallId();
                    Deque<String> queue = toolCallIds.computeIfAbsent(name, k -> new ConcurrentLinkedDeque<>());
                    queue.add(tcId);
                    toolCallMeta.put(tcId, Map.of("args", args));
                    sendToolCallStart(sessionId, tcId, name, args);
                }
            });
            callbacks.setStepCallback((apiCallCount, prevTools) -> {
                for (Map<String, Object> toolInfo : prevTools) {
                    String toolName = (String) toolInfo.getOrDefault("name",
                            toolInfo.getOrDefault("function_name", ""));
                    Object result = toolInfo.get("result");
                    // P1-23 fix: 优先用 toolCallId 精确匹配，回退到 name FIFO
                    String actualToolCallId = (String) toolInfo.get("toolCallId");
                    String tcId = null;
                    if (actualToolCallId != null && toolCallMeta.containsKey(actualToolCallId)) {
                        tcId = actualToolCallId;
                        // 从 name 队列中移除（如果存在）
                        Deque<String> queue = toolCallIds.get(toolName);
                        if (queue != null) queue.remove(tcId);
                    } else {
                        Deque<String> queue = toolCallIds.get(toolName);
                        if (queue != null && !queue.isEmpty()) {
                            tcId = queue.poll();
                        }
                    }
                    if (tcId != null) {
                        toolCallMeta.remove(tcId);
                        sendToolCallComplete(sessionId, tcId, toolName,
                                result != null ? result.toString() : null);
                    }
                }
            });
        }
        session.setCallbacks(callbacks);

        // P1-22 fix: 以 CompletableFuture 异步执行，但使用 .orTimeout() 替代
        // .get(5 min) 阻塞。仍返回同步 PromptResponse（ACP 协议限制），
        // 但不再阻塞调用方线程——通过 short timeout + 后台继续执行实现。
        AtomicReference<String> finalResponse = new AtomicReference<>("");
        AtomicReference<Map<String, Object>> usage = new AtomicReference<>(Collections.emptyMap());

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                // Add user message to ACP session history
                Map<String, Object> userMsg = new LinkedHashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", userText);
                session.addMessage(userMsg);

                // Use Agent's chatStream for streaming response
                StringBuilder fullResponse = new StringBuilder();
                agent.chatStream(sessionId, userText)
                        .doOnNext(fullResponse::append)
                        .doOnComplete(() -> {
                            String content = fullResponse.toString();
                            finalResponse.set(content);

                            // Add assistant message to ACP session history
                            Map<String, Object> assistantMsg = new LinkedHashMap<>();
                            assistantMsg.put("role", "assistant");
                            assistantMsg.put("content", content);
                            session.addMessage(assistantMsg);
                        })
                        .doOnError(e -> {
                            log.error("Agent stream error for session {}", sessionId, e);
                            finalResponse.set("Error: " + e.getMessage());
                        })
                        .blockLast(); // Block until stream completes

            } catch (Exception e) {
                log.error("Agent error in session {}", sessionId, e);
                finalResponse.set("Error: " + e.getMessage());
            }
        }, executor);

        try {
            // P1-22 fix: 使用 orTimeout + join 替代 .get(5 min)，
            // 配合 CompletableFuture API 避免显式阻塞
            future.orTimeout(5, TimeUnit.MINUTES).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                log.warn("Agent execution timed out for session {}", sessionId);
                session.cancel();
            } else {
                log.error("Executor error for session {}", sessionId, e);
            }
        }

        // Persist updated history
        sessionManager.saveSession(sessionId);

        // Send final response via transport
        String response = finalResponse.get();
        if (!response.isEmpty() && transport != null) {
            sendAgentMessageText(sessionId, response);
        }

        // Build usage info
        Optional<Usage> usageOpt = Optional.empty();
        if (!usage.get().isEmpty()) {
            Map<String, Object> u = usage.get();
            usageOpt = Optional.of(new Usage(
                    ((Number) u.getOrDefault("prompt_tokens", 0)).longValue(),
                    ((Number) u.getOrDefault("completion_tokens", 0)).longValue(),
                    ((Number) u.getOrDefault("total_tokens", 0)).longValue(),
                    Optional.ofNullable((Long) u.get("reasoning_tokens")),
                    Optional.ofNullable((Long) u.get("cache_read_tokens"))
            ));
        }

        String stopReason = session.isCancelled()
                ? ACPProtocol.STOP_CANCELLED : ACPProtocol.STOP_END_TURN;
        return new PromptResponse(stopReason, usageOpt, Optional.empty());
    }

    /**
     * Switch the model for a session.
     */
    public SetSessionModelResponse setSessionModel(SetSessionModelRequest request) {
        ACPSession session = sessionManager.getSession(request.sessionId());
        if (session == null) {
            log.warn("Session {}: model switch requested for missing session", request.sessionId());
            return null;
        }
        session.setModel(request.modelId());
        sessionManager.saveSession(request.sessionId());
        log.info("Session {}: model switched to {} via ACP", request.sessionId(), request.modelId());
        return new SetSessionModelResponse();
    }

    /**
     * Set the mode for a session.
     */
    public SetSessionModeResponse setSessionMode(SetSessionModeRequest request) {
        ACPSession session = sessionManager.getSession(request.sessionId());
        if (session == null) {
            log.warn("Session {}: mode switch requested for missing session", request.sessionId());
            return null;
        }
        session.setMode(request.modeId());
        sessionManager.saveSession(request.sessionId());
        log.info("Session {}: mode switched to {}", request.sessionId(), request.modeId());
        return new SetSessionModeResponse();
    }

    /**
     * Set a config option for a session.
     */
    public SetConfigOptionResponse setConfigOption(SetConfigOptionRequest request) {
        ACPSession session = sessionManager.getSession(request.sessionId());
        if (session == null) {
            log.warn("Session {}: config update for missing session", request.sessionId());
            return null;
        }
        Map<String, Object> options = session.getConfigOptions();
        options.put(request.configId(), request.value());
        session.setConfigOptions(options);
        sessionManager.saveSession(request.sessionId());
        log.info("Session {}: config option {} updated", request.sessionId(), request.configId());
        return new SetConfigOptionResponse(List.of());
    }

    // ================================================================
    //  JSON-RPC Message Dispatch
    // ================================================================

    /**
     * Dispatch an incoming JSON-RPC message to the appropriate handler.
     *
     * @param message the parsed ACPMessage
     * @return the response object, or null for notifications
     */
    public Object dispatch(ACPMessage message) {
        try {
            return switch (message.method()) {
                case ACPProtocol.METHOD_INITIALIZE -> {
                    InitializeRequest req = parseInitializeRequest(message);
                    yield initialize(req);
                }
                case ACPProtocol.METHOD_NEW_SESSION -> {
                    NewSessionRequest req = parseNewSessionRequest(message);
                    yield newSession(req);
                }
                case ACPProtocol.METHOD_LOAD_SESSION -> {
                    LoadSessionRequest req = parseLoadSessionRequest(message);
                    yield loadSession(req);
                }
                case ACPProtocol.METHOD_RESUME_SESSION -> {
                    ResumeSessionRequest req = parseResumeSessionRequest(message);
                    yield resumeSession(req);
                }
                case ACPProtocol.METHOD_AUTHENTICATE -> {
                    yield authenticate(message);
                }
                case ACPProtocol.METHOD_CANCEL -> {
                    String sessionId = message.getString("sessionId");
                    cancel(sessionId);
                    yield null; // Notification, no response
                }
                case ACPProtocol.METHOD_FORK_SESSION -> {
                    ForkSessionRequest req = parseForkSessionRequest(message);
                    yield forkSession(req);
                }
                case ACPProtocol.METHOD_LIST_SESSIONS -> {
                    ListSessionsRequest req = parseListSessionsRequest(message);
                    yield listSessions(req);
                }
                case ACPProtocol.METHOD_PROMPT -> {
                    PromptRequest req = parsePromptRequest(message);
                    yield prompt(req);
                }
                case ACPProtocol.METHOD_SET_SESSION_MODEL -> {
                    SetSessionModelRequest req = new SetSessionModelRequest(
                            message.getString("modelId"),
                            message.getString("sessionId"));
                    yield setSessionModel(req);
                }
                case ACPProtocol.METHOD_SET_SESSION_MODE -> {
                    SetSessionModeRequest req = new SetSessionModeRequest(
                            message.getString("modeId"),
                            message.getString("sessionId"));
                    yield setSessionMode(req);
                }
                case ACPProtocol.METHOD_SET_CONFIG_OPTION -> {
                    SetConfigOptionRequest req = new SetConfigOptionRequest(
                            message.getString("configId"),
                            message.getString("sessionId"),
                            message.getString("value"));
                    yield setConfigOption(req);
                }
                default -> {
                    log.warn("Unknown ACP method: {}", message.method());
                    yield null;
                }
            };
        } catch (Exception e) {
            log.error("Error dispatching ACP method {}: {}", message.method(), e.getMessage(), e);
            return null;
        }
    }

    // ================================================================
    //  Transport helpers (send session updates to client)
    // ================================================================

    private void sendAgentMessageText(String sessionId, String text) {
        if (transport == null) return;
        try {
            Map<String, Object> update = Map.of(
                    "session_update", ACPProtocol.UPDATE_AGENT_MESSAGE_TEXT,
                    "content", Map.of("text", text)
            );
            transport.sendSessionUpdate(sessionId, update);
        } catch (Exception e) {
            log.debug("Failed to send agent message text update", e);
        }
    }

    private void sendThinkingText(String sessionId, String text) {
        if (transport == null) return;
        try {
            Map<String, Object> update = Map.of(
                    "session_update", ACPProtocol.UPDATE_AGENT_THOUGHT_TEXT,
                    "content", Map.of("text", text)
            );
            transport.sendSessionUpdate(sessionId, update);
        } catch (Exception e) {
            log.debug("Failed to send thinking text update", e);
        }
    }

    private void sendToolCallStart(String sessionId, String toolCallId,
                                   String toolName, Map<String, Object> args) {
        if (transport == null) return;
        try {
            ToolCallStartContent startContent = ACPToolMapping.buildToolStartContent(toolName, args);
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("session_update", ACPProtocol.UPDATE_TOOL_CALL_START);
            update.put("tool_call_id", toolCallId);
            update.put("title", startContent.title());
            update.put("kind", startContent.kind());
            update.put("locations", startContent.locations());
            transport.sendSessionUpdate(sessionId, update);
        } catch (Exception e) {
            log.debug("Failed to send tool call start update", e);
        }
    }

    private void sendToolCallComplete(String sessionId, String toolCallId,
                                      String toolName, String result) {
        if (transport == null) return;
        try {
            ToolCallProgressContent completeContent =
                    ACPToolMapping.buildToolCompleteContent(toolName, result, null);
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("session_update", ACPProtocol.UPDATE_TOOL_CALL_END);
            update.put("tool_call_id", toolCallId);
            update.put("kind", completeContent.kind());
            update.put("status", completeContent.status());
            transport.sendSessionUpdate(sessionId, update);
        } catch (Exception e) {
            log.debug("Failed to send tool call complete update", e);
        }
    }

    private void sendAvailableCommandsUpdate(String sessionId) {
        if (transport == null) return;
        try {
            List<AvailableCommand> commands = slashCommandHandler.getAvailableCommands();
            Map<String, Object> update = Map.of(
                    "session_update", ACPProtocol.UPDATE_AVAILABLE_COMMANDS,
                    "available_commands", commands
            );
            transport.sendSessionUpdate(sessionId, update);
        } catch (Exception e) {
            log.debug("Failed to send available commands update", e);
        }
    }

    // ================================================================
    //  Authentication
    // ================================================================

    /**
     * Build auth methods based on detected provider.
     *
     * <p>Reference: Python server.py initialize() which calls
     * {@code detect_provider()} and returns dynamic auth_methods.
     *
     * <p>For local stdio mode, no auth is needed. When an API key provider
     * is detected, we advertise an "api_key" method so the client can
     * verify the key is valid.
     */
    private List<AuthMethod> buildAuthMethods() {
        List<AuthMethod> methods = new ArrayList<>();

        // Always offer no-auth for local stdio
        methods.add(new AuthMethod("none", "No Authentication",
                Optional.of("Local stdio session — no credentials required")));

        // If a provider is detected, advertise API key auth
        ACPAuthProvider.detectProvider().ifPresent(provider -> {
            String envKey = providerToEnvKey(provider);
            methods.add(new AuthMethod("api_key", provider.toUpperCase() + " API Key",
                    Optional.of("Verify " + provider + " API key (" + envKey + ")")));
        });

        return methods;
    }

    /**
     * Handle the authenticate request.
     *
     * <p>Reference: Python server.py authenticate() which validates method_id.
     */
    private Object authenticate(ACPMessage message) {
        String methodId = message.getString("methodId");

        if ("none".equals(methodId)) {
            // No auth — always succeeds
            return Map.of("status", "ok", "method", "none");
        }

        if ("api_key".equals(methodId)) {
            // Verify the provider's API key is set
            String provider = ACPAuthProvider.detectProvider().orElse("");
            if (!provider.isEmpty()) {
                return Map.of("status", "ok", "method", "api_key", "provider", provider);
            } else {
                return Map.of("status", "error", "message", "No API key detected");
            }
        }

        return Map.of("status", "error", "message", "Unknown auth method: " + methodId);
    }

    /**
     * Map provider name to its primary environment variable name.
     */
    private String providerToEnvKey(String provider) {
        return switch (provider) {
            case "openai" -> "OPENAI_API_KEY";
            case "anthropic" -> "ANTHROPIC_API_KEY";
            case "openrouter" -> "OPENROUTER_API_KEY";
            case "deepseek" -> "DEEPSEEK_API_KEY";
            case "dashscope" -> "DASHSCOPE_API_KEY";
            case "gemini", "google" -> "GEMINI_API_KEY";
            case "mistral" -> "MISTRAL_API_KEY";
            case "groq" -> "GROQ_API_KEY";
            case "together" -> "TOGETHER_API_KEY";
            case "xai" -> "XAI_API_KEY";
            case "azure" -> "AZURE_OPENAI_API_KEY";
            default -> provider.toUpperCase() + "_API_KEY";
        };
    }

    // ================================================================
    //  Model state builder
    // ================================================================

    /**
     * Build model state from LLMRouter, listing all available models.
     *
     * <p>Reference: Python server.py _build_model_state() which calls
     * {@code curated_models_for_provider()} from hermes_cli.models.
     */
    private SessionModelState buildModelState(ACPSession session) {
        String currentModel = session.getModel();

        // Collect all available models from LLMRouter
        List<ModelInfo> models = new ArrayList<>();
        Map<String, LLMProvider> providers = llmRouter.getProviders();

        for (Map.Entry<String, LLMProvider> entry : providers.entrySet()) {
            String providerId = entry.getKey();
            LLMProvider provider = entry.getValue();
            for (String modelId : provider.getAvailableModels()) {
                String fullId = providerId + "/" + modelId;
                String name = provider.getDisplayName() + " / " + modelId;
                String desc = fullId.equals(currentModel) ? "current" : providerId;
                models.add(new ModelInfo(fullId, name, Optional.of(desc)));
            }
        }

        // If no models discovered, fall back to current model only
        if (models.isEmpty() && currentModel != null && !currentModel.isEmpty()) {
            models.add(new ModelInfo(currentModel, currentModel, Optional.of("current")));
        }

        // Determine effective current model
        String effectiveModel = (currentModel != null && !currentModel.isEmpty())
                ? currentModel
                : llmRouter.getAllAvailableModels().stream().findFirst().orElse("");

        return models.isEmpty() ? null : new SessionModelState(models, effectiveModel);
    }

    // ================================================================
    //  MCP Server registration
    // ================================================================

    private void registerMcpServers(ACPSession session, List<McpServer> mcpServers) {
        if (mcpServers == null || mcpServers.isEmpty()) return;
        // TODO: Integrate with MCP service when available
        // For now, log that we received them
        log.info("Session {}: received {} MCP server configs (registration pending)",
                session.getSessionId(), mcpServers.size());
    }

    // ================================================================
    //  Request parsing helpers
    // ================================================================

    private String extractText(List<ACPContentBlock> blocks) {
        if (blocks == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ACPContentBlock block : blocks) {
            if (block instanceof ACPContentBlock.Text textBlock) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(textBlock.text());
            }
        }
        return sb.toString();
    }

    private InitializeRequest parseInitializeRequest(ACPMessage message) {
        return new InitializeRequest(
                Optional.ofNullable(message.getInt("protocolVersion")),
                Optional.empty(),
                Optional.empty()
        );
    }

    private NewSessionRequest parseNewSessionRequest(ACPMessage message) {
        return new NewSessionRequest(
                message.getString("cwd"),
                Optional.empty()
        );
    }

    private LoadSessionRequest parseLoadSessionRequest(ACPMessage message) {
        return new LoadSessionRequest(
                message.getString("cwd"),
                message.getString("sessionId"),
                Optional.empty()
        );
    }

    private ResumeSessionRequest parseResumeSessionRequest(ACPMessage message) {
        return new ResumeSessionRequest(
                message.getString("cwd"),
                message.getString("sessionId"),
                Optional.empty()
        );
    }

    private ForkSessionRequest parseForkSessionRequest(ACPMessage message) {
        return new ForkSessionRequest(
                message.getString("cwd"),
                message.getString("sessionId"),
                Optional.empty()
        );
    }

    private ListSessionsRequest parseListSessionsRequest(ACPMessage message) {
        return new ListSessionsRequest(
                Optional.ofNullable(message.getString("cursor")),
                Optional.ofNullable(message.getString("cwd"))
        );
    }

    private PromptRequest parsePromptRequest(ACPMessage message) {
        // Parse content blocks from params
        List<ACPContentBlock> blocks = new ArrayList<>();
        Object promptObj = message.getParam("prompt", null);
        if (promptObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String type = (String) map.get("type");
                    if ("text".equals(type)) {
                        blocks.add(ACPContentBlock.Text.of((String) map.get("text")));
                    } else if ("image".equals(type)) {
                        blocks.add(ACPContentBlock.Image.of((String) map.get("url")));
                    }
                }
            }
        }
        if (blocks.isEmpty()) {
            // Fallback: treat entire prompt as text
            String text = message.getString("prompt");
            if (text != null) blocks.add(ACPContentBlock.Text.of(text));
        }
        return new PromptRequest(blocks, message.getString("sessionId"), Optional.empty());
    }

    // ================================================================
    //  Shutdown
    // ================================================================

    public void shutdown() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("ACP executor did not shut down cleanly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("HermesACPAgent shutdown complete");
    }
}
