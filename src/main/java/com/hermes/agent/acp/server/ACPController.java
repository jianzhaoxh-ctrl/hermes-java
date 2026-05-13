package com.hermes.agent.acp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hermes.agent.acp.model.*;
import com.hermes.agent.acp.session.ACPSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * REST controller exposing the ACP protocol over HTTP+SSE.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /acp — JSON-RPC 2.0 request/response</li>
 *   <li>GET /acp/events — SSE stream for session updates</li>
 *   <li>GET /acp/health — Health check</li>
 *   <li>GET /acp/sessions — List active sessions</li>
 * </ul>
 *
 * <p>Reference: Python acp library HTTP transport
 */
@RestController
@RequestMapping("/acp")
public class ACPController {

    private static final Logger log = LoggerFactory.getLogger(ACPController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HermesACPAgent agent;
    private final ACPSessionManager sessionManager;
    private final ACPSseTransport sseTransport;
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool();

    public ACPController(HermesACPAgent agent,
                         ACPSessionManager sessionManager) {
        this.agent = agent;
        this.sessionManager = sessionManager;
        this.sseTransport = new ACPSseTransport();

        // Wire SSE transport into agent so updates are pushed to HTTP clients
        agent.setTransport(sseTransport);
    }

    // ---- JSON-RPC endpoint ---------------------------------------------------

    /**
     * Handle a JSON-RPC 2.0 request via HTTP POST.
     *
     * <p>For long-running operations (like "prompt"), the method returns
     * immediately with a session ID, and the actual content is streamed
     * via the SSE endpoint.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> handleJsonRpc(
            @RequestBody Map<String, Object> request) {

        String method = (String) request.get("method");
        Object id = request.get("id");

        log.debug("ACP HTTP request: method={} id={}", method, id);

        // Validate JSON-RPC structure
        if (!"2.0".equals(request.get("jsonrpc"))) {
            return ResponseEntity.badRequest().body(errorResponse(id, -32600,
                    ACPProtocol.JSONRPC_INVALID_REQUEST));
        }

        if (method == null) {
            return ResponseEntity.badRequest().body(errorResponse(id, -32600,
                    "Missing method"));
        }

        try {
            // Convert to ACPMessage and dispatch
            JsonNode requestNode = MAPPER.valueToTree(request);
            ACPMessage message = ACPMessage.fromJson(requestNode);

            Object result = agent.dispatch(message);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("result", result);
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid params for {}: {}", method, e.getMessage());
            return ResponseEntity.badRequest().body(
                    errorResponse(id, -32602, ACPProtocol.JSONRPC_INVALID_PARAMS));
        } catch (Exception e) {
            log.error("Error handling {}: {}", method, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    errorResponse(id, -32603, ACPProtocol.JSONRPC_INTERNAL_ERROR));
        }
    }

    // ---- SSE endpoint --------------------------------------------------------

    /**
     * Open an SSE connection to receive real-time session updates.
     *
     * <p>Clients connect here and receive events:
     * <ul>
     *   <li>session_update — Agent text/thought output, tool calls</li>
     *   <li>response — JSON-RPC response for async operations</li>
     *   <li>error — Error notifications</li>
     * </ul>
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        log.info("New SSE client connected");
        return sseTransport.createEmitter();
    }

    // ---- Streaming chat endpoint (Python-compatible /chat/stream) -------------

    /**
     * Streaming chat endpoint — POST a prompt and receive real-time SSE events.
     *
     * <p>Request body:
     * <pre>{
     *   "sessionId": "...",
     *   "prompt": "hello world",
     *   "model": "optional-model-id"
     * }</pre>
     *
     * <p>SSE events streamed:
     * <ul>
     *   <li>text — incremental text chunks from agent stream</li>
     *   <li>tool_call_start — tool invocation begins</li>
     *   <li>tool_call_complete — tool invocation finished</li>
     *   <li>thinking — thinking/reasoning content</li>
     *   <li>done — final completion with stop reason</li>
     *   <li>error — error notification</li>
     * </ul>
     *
     * <p>Reference: Python hermes-agent-main /api/acp/chat/stream
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody Map<String, Object> request) {
        String sessionId = (String) request.get("sessionId");
        String prompt = (String) request.get("prompt");
        String model = (String) request.get("model");

        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (prompt == null || prompt.isEmpty()) {
            throw new IllegalArgumentException("prompt is required");
        }

        log.info("Streaming chat for session {} (model={})", sessionId, model);

        SseEmitter emitter = new SseEmitter(0L); // no timeout

        asyncExecutor.submit(() -> {
            try {
                com.hermes.agent.acp.session.ACPSession session = sessionManager.getSession(sessionId);
                if (session == null) {
                    emitter.send(SseEmitter.event().name("error").data(Map.of(
                            "message", "Session not found: " + sessionId
                    )));
                    emitter.complete();
                    return;
                }

                // Switch model if requested
                if (model != null && !model.isEmpty()) {
                    session.setModel(model);
                }

                // Clear cancel flag
                session.clearCancel();

                // Subscribe to agent stream and forward to SSE
                agent.getAgent().chatStream(sessionId, prompt)
                        .doOnNext(chunk -> {
                            try {
                                emitter.send(SseEmitter.event().name("text").data(Map.of(
                                        "sessionId", sessionId,
                                        "chunk", chunk
                                )));
                            } catch (IOException e) {
                                log.warn("SSE send failed during stream", e);
                            }
                        })
                        .doOnError(e -> {
                            try {
                                emitter.send(SseEmitter.event().name("error").data(Map.of(
                                        "sessionId", sessionId,
                                        "message", e.getMessage()
                                )));
                            } catch (IOException ex) {
                                log.warn("SSE send failed for error", ex);
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data(Map.of(
                                        "sessionId", sessionId,
                                        "stopReason", "end_turn"
                                )));
                                emitter.complete();
                            } catch (IOException e) {
                                log.warn("SSE send failed on complete", e);
                                emitter.completeWithError(e);
                            }
                        })
                        .blockLast(); // Block until stream completes

            } catch (Exception e) {
                log.error("Streaming chat error for session {}", sessionId, e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(Map.of(
                            "message", e.getMessage()
                    )));
                } catch (IOException ex) {
                    log.warn("SSE send failed for error", ex);
                }
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(() -> log.info("Chat stream timed out for session {}", sessionId));
        emitter.onCompletion(() -> log.debug("Chat stream completed for session {}", sessionId));
        emitter.onError(e -> log.warn("Chat stream error for session {}", sessionId, e));

        return emitter;
    }

    // ---- Management endpoints ------------------------------------------------

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "ok");
        status.put("protocolVersion", ACPProtocol.PROTOCOL_VERSION);
        status.put("sseConnections", sseTransport.getConnectionCount());
        return ResponseEntity.ok(status);
    }

    /**
     * List active sessions.
     */
    @GetMapping("/sessions")
    public ResponseEntity<Map<String, Object>> listSessions() {
        List<Map<String, Object>> sessionInfos = sessionManager.listSessions(null);

        // Transform to response format
        List<Map<String, Object>> sessionList = new ArrayList<>();
        for (Map<String, Object> s : sessionInfos) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("sessionId", s.get("session_id"));
            info.put("cwd", s.get("cwd"));
            info.put("title", s.getOrDefault("title", ""));
            info.put("updatedAt", s.getOrDefault("updated_at", ""));
            sessionList.add(info);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessions", sessionList);
        result.put("total", sessionInfos.size());
        return ResponseEntity.ok(result);
    }

    /**
     * Get session details — returns full session state including
     * conversation history, config options, model, timestamps, and metadata.
     */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String sessionId) {
        com.hermes.agent.acp.session.ACPSession session = sessionManager.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        // Core identity
        result.put("sessionId", session.getSessionId());
        result.put("cwd", session.getCwd());
        result.put("model", session.getModel());
        result.put("mode", session.getMode());
        result.put("title", session.getTitle());

        // Timestamps
        result.put("createdAt", session.getCreatedAt().toString());
        result.put("updatedAt", session.getUpdatedAt().toString());

        // Conversation history
        result.put("history", session.getHistory());
        result.put("historySize", session.getHistorySize());

        // Configuration
        result.put("configOptions", session.getConfigOptions());

        // State
        result.put("cancelled", session.isCancelled());

        return ResponseEntity.ok(result);
    }

    /**
     * Cancel an in-progress prompt.
     */
    @PostMapping("/sessions/{sessionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelSession(@PathVariable String sessionId) {
        // Delegate cancel through agent
        agent.cancel(sessionId);
        return ResponseEntity.ok(Map.of("status", "cancelled", "sessionId", sessionId));
    }

    // ---- Helper methods ------------------------------------------------------

    private Map<String, Object> errorResponse(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }
}
