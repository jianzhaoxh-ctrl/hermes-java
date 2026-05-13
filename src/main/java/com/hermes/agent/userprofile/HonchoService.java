package com.hermes.agent.userprofile;

import com.hermes.agent.llm.LLMService;
import com.hermes.agent.model.Message;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Honcho Service — main facade for the Honcho-style user profiling system.
 *
 * <p>Provides:
 * <ul>
 *   <li>Multi-peer management (user peer + AI peer per session)</li>
 *   <li>Observation recording (messages, seeds, conclusions)</li>
 *   <li>Dialectic reasoning (multi-pass LLM inference)</li>
 *   <li>Context injection (buildContextString for system prompt)</li>
 *   <li>Search and reasoning tools (for agent function calling)</li>
 * </ul>
 *
 * <p>Design goals (from Honcho Python):
 * <ul>
 *   <li><b>B1 Recall modes</b>: context-injection, tools-only, hybrid</li>
 *   <li><b>B3 Session naming</b>: flexible session key resolution</li>
 *   <li><b>B5 Cost awareness</b>: cadence, depth, reasoning heuristic</li>
 *   <li><b>B6 Memory file migration</b>: seed from USER.md, SOUL.md, etc.</li>
 *   <li><b>B7 Pre-warming</b>: warm up dialectic at session start</li>
 * </ul>
 */
@Service
public class HonchoService {

    private static final Logger log = LoggerFactory.getLogger(HonchoService.class);

    private final UserProfileConfig config;
    private final DialecticEngine dialecticEngine;
    private final LLMService llmService;
    private final HonchoSessionStore sessionStore;  // Persistence layer
    private final SessionStrategyResolver strategyResolver;  // Session strategy
    private final VectorStore vectorStore;  // Vector search
    private final EmbeddingService embeddingService;  // Embedding generation

    /** All peer profiles: sessionId → Map(peerId → PeerProfile) */
    private final Map<String, Map<String, PeerProfile>> sessionProfiles = new ConcurrentHashMap<>();

    /** Peer card cache: peerId → peer card facts */
    private final Map<String, PeerCard> peerCards = new ConcurrentHashMap<>();

    /** Session keys: sessionId → resolved session name */
    private final Map<String, String> sessionKeys = new ConcurrentHashMap<>();

    /** Sync threads per session (to avoid concurrent writes) */
    private final Map<String, Thread> syncThreads = new ConcurrentHashMap<>();

    /** Async executor for background work */
    private final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            r -> { Thread t = new Thread(r, "hermes-honcho"); t.setDaemon(true); return t; }
    );

    /** Tool schemas for function calling */
    private final List<Map<String, Object>> toolSchemas;

    public HonchoService(UserProfileConfig config,
                         DialecticEngine dialecticEngine,
                         LLMService llmService,
                         HonchoSessionStore sessionStore,
                         SessionStrategyResolver strategyResolver,
                         VectorStore vectorStore,
                         EmbeddingService embeddingService) {
        this.config = config;
        this.dialecticEngine = dialecticEngine;
        this.llmService = llmService;
        this.sessionStore = sessionStore;
        this.strategyResolver = strategyResolver;
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        this.toolSchemas = buildToolSchemas();
    }

    @PostConstruct
    public void init() {
        log.info("[Honcho] Initialized: peerName={}, aiPeer={}, recallMode={}, " +
                 "dialecticDepth={}, dialecticCadence={}, observationMode={}, " +
                 "sessionStrategy={}, semanticSearch={}",
                config.getPeerName(), config.getAiPeer(), config.getRecallMode(),
                config.getDialecticDepth(), config.getDialecticCadence(),
                config.getObservationMode(), config.getSessionStrategy(),
                config.isSemanticSearchEnabled());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Session management
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Initialize a session with user and AI peers.
     * Uses SessionStrategyResolver to determine the effective session key.
     */
    public void initializeSession(String sessionId, String sessionTitle,
                                   String gatewaySessionKey, String userPeerName) {
        // Use strategy resolver to determine session key
        String resolvedKey = strategyResolver.resolveSessionKey(
                sessionId, sessionTitle, gatewaySessionKey, null);
        sessionKeys.put(sessionId, resolvedKey);

        log.debug("[Honcho] Session key resolved: strategy={}, sessionId={}, resolvedKey={}",
                config.getSessionStrategy(), sessionId, resolvedKey);

        Map<String, PeerProfile> profiles = sessionProfiles.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());

        // User peer
        String userPeerId = userPeerName != null ? userPeerName : (config.getPeerName().isBlank() ? "user" : config.getPeerName());
        profiles.put(userPeerId, new PeerProfile(userPeerId, "user"));

        // AI peer
        String aiPeerId = config.getAiPeer();
        profiles.put(aiPeerId, new PeerProfile(aiPeerId, "ai"));

        // Set observation mode for both peers
        for (PeerProfile p : profiles.values()) {
            p.setObservationMode(config.getObservationMode());
        }

        log.debug("[Honcho] Session initialized: id={}, key={}, user={}, ai={}",
                sessionId, resolvedKey, userPeerId, aiPeerId);

        // Pre-warm at init (B7)
        if (config.isContextInjectionEnabled()) {
            prewarmAtInit(sessionId);
        }
    }

    /**
     * Resolve session name from title / session ID / gateway key.
     * Delegates to SessionStrategyResolver for strategy-aware resolution.
     */
    private String resolveSessionName(String sessionTitle, String sessionId, String gatewaySessionKey) {
        return strategyResolver.resolveSessionKey(sessionId, sessionTitle, gatewaySessionKey, null);
    }

    /**
     * Get the strategy resolver for external access.
     */
    public SessionStrategyResolver getStrategyResolver() {
        return strategyResolver;
    }

    /**
     * Get or create a peer profile.
     */
    public Optional<PeerProfile> getPeerProfile(String sessionId, String peerId) {
        Map<String, PeerProfile> profiles = sessionProfiles.get(sessionId);
        if (profiles == null) return Optional.empty();
        return Optional.ofNullable(profiles.get(peerId));
    }

    /**
     * Get the user peer profile for a session.
     */
    public Optional<PeerProfile> getUserProfile(String sessionId) {
        Map<String, PeerProfile> profiles = sessionProfiles.get(sessionId);
        if (profiles == null) return Optional.empty();
        // Find the peer with type "user"
        return profiles.values().stream()
                .filter(p -> "user".equals(p.getPeerType()))
                .findFirst();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Observation recording
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Record a user message observation (async, non-blocking).
     */
    public void recordUserMessage(String sessionId, String content) {
        if (!config.isSaveMessages()) return;
        String peerId = resolveUserPeerId(sessionId);
        if (peerId == null) return;

        // Chunk messages that exceed the limit
        for (String chunk : chunkMessage(content, config.getMessageMaxChars())) {
            getPeerProfile(sessionId, peerId)
                    .ifPresent(p -> p.observeUserMessage(chunk, sessionId));
            // Index for semantic search
            indexObservation(sessionId, peerId, chunk, "user_message");
        }
    }

    /**
     * Record an assistant message observation (async, non-blocking).
     */
    public void recordAssistantMessage(String sessionId, String content) {
        if (!config.isSaveMessages()) return;
        String aiPeerId = config.getAiPeer();
        if (aiPeerId == null) return;

        for (String chunk : chunkMessage(content, config.getMessageMaxChars())) {
            getPeerProfile(sessionId, aiPeerId)
                    .ifPresent(p -> p.observeAssistantMessage(chunk, sessionId));
            // Index for semantic search
            indexObservation(sessionId, aiPeerId, chunk, "assistant_message");
        }
    }

    /**
     * Record both user and assistant messages in one call (for sync_turn).
     */
    public void syncTurn(String sessionId, String userContent, String assistantContent) {
        // Ensure session exists
        if (!sessionProfiles.containsKey(sessionId)) {
            initializeSession(sessionId, null, null, null);
        }

        recordUserMessage(sessionId, userContent);
        recordAssistantMessage(sessionId, assistantContent);

        // Advance dialectic turn
        dialecticEngine.advanceTurn(sessionId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Conclusions
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Add a conclusion (persistent fact) about a peer.
     */
    public Conclusion addConclusion(String sessionId, String peerId, String fact, String source) {
        return getPeerProfile(sessionId, peerId)
                .map(p -> p.addConclusion(fact, source))
                .orElse(null);
    }

    /**
     * Add a conclusion about the user peer.
     */
    public Conclusion addUserConclusion(String sessionId, String fact) {
        String peerId = resolveUserPeerId(sessionId);
        if (peerId == null) return null;
        return addConclusion(sessionId, peerId, fact, "conclusion_api");
    }

    /**
     * Delete a conclusion.
     */
    public void deleteConclusion(String sessionId, String peerId, String conclusionId) {
        getPeerProfile(sessionId, peerId)
                .ifPresent(p -> p.removeConclusion(conclusionId));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Seed from identity files
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Seed a peer's identity from content (e.g., SOUL.md, USER.md).
     */
    public void seedIdentity(String sessionId, String peerId, String content, String source) {
        getPeerProfile(sessionId, peerId)
                .ifPresent(p -> p.addSeed(content, source));
    }

    /**
     * Seed AI peer identity from SOUL.md / IDENTITY.md content.
     */
    public void seedAIIdentity(String sessionId, String soulContent) {
        String aiPeerId = config.getAiPeer();
        seedIdentity(sessionId, aiPeerId, soulContent, "SOUL.md");
        log.info("[Honcho] AI peer identity seeded for session {}", sessionId);
    }

    /**
     * Seed user peer identity from USER.md / MEMORY.md content.
     */
    public void seedUserIdentity(String sessionId, String userContent) {
        String userPeerId = resolveUserPeerId(sessionId);
        if (userPeerId == null) return;
        seedIdentity(sessionId, userPeerId, userContent, "USER.md");
        log.info("[Honcho] User peer identity seeded for session {}", sessionId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Context building (for system prompt injection)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Build the full context string for system prompt injection.
     * Combines representation, peer card, and recent observations.
     * Respects context token budget.
     */
    public String buildContextString(String sessionId) {
        return buildContextString(sessionId, config.getContextCharBudget());
    }

    /**
     * Build context string with a custom char budget.
     */
    public String buildContextString(String sessionId, int maxChars) {
        Optional<PeerProfile> userProfile = getUserProfile(sessionId);
        if (userProfile.isEmpty()) return "";

        PeerProfile p = userProfile.get();
        String ctx = p.buildContextString();

        // Append dialectic supplement
        String dialecticResult = dialecticEngine.consumeDialecticResult(sessionId);
        if (!dialecticResult.isBlank()) {
            ctx = ctx + "\n\n## Dialectic Insight\n" + dialecticResult;
        }

        // Truncate to budget
        if (ctx.length() > maxChars && maxChars > 0) {
            ctx = ctx.substring(0, maxChars) + " …";
        }

        return ctx;
    }

    /**
     * Get the system prompt block (mode header + tool instructions).
     * Static text that doesn't change between turns (prompt-cache friendly).
     */
    public String systemPromptBlock() {
        String mode = config.getRecallMode();
        if ("context".equals(mode)) {
            return "# User Profile\nActive (context-injection mode). " +
                   "Relevant user context is automatically injected before each turn.";
        } else if ("tools".equals(mode)) {
            return "# User Profile\nActive (tools-only mode). Use honcho_profile, " +
                   "honcho_search, honcho_reasoning, honcho_context, and honcho_conclude " +
                   "to access user memory.";
        } else { // hybrid
            return "# User Profile\nActive (hybrid mode). Relevant context is auto-injected " +
                   "AND memory tools are available. Use honcho_profile for quick facts, " +
                   "honcho_search for semantic search, honcho_reasoning for synthesized answers " +
                   "(use reasoning_level: minimal/low/medium/high/max), " +
                   "honcho_context for raw context, honcho_conclude to save facts.";
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tools (for function calling)
    // ─────────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> getToolSchemas() {
        if (!config.isToolsEnabled()) return List.of();
        return toolSchemas;
    }

    public String handleToolCall(String toolName, Map<String, Object> args, String sessionId) {
        String peer = (String) args.getOrDefault("peer", resolveUserPeerId(sessionId));

        try {
            String result = switch (toolName) {
                case "honcho_profile" -> handleProfile(sessionId, peer, args);
                case "honcho_search" -> handleSearch(sessionId, peer, args);
                case "honcho_reasoning" -> handleReasoningSync(sessionId, peer, args);
                case "honcho_context" -> handleContext(sessionId, peer, args);
                case "honcho_conclude" -> handleConclude(sessionId, peer, args);
                default -> "{\"error\": \"Unknown tool: " + toolName + "\"}";
            };
            return result;
        } catch (Exception e) {
            log.error("[Honcho] Tool {} failed: {}", toolName, e.getMessage());
            return "{\"error\": \"Honcho " + toolName + " failed: " + e.getMessage() + "\"}";
        }
    }

    private String handleProfile(String sessionId, String peerId, Map<String, Object> args) {
        Optional<PeerProfile> profile = getPeerProfile(sessionId, peerId);
        if (profile.isEmpty()) {
            return "{\"result\": \"Profile not found for peer: " + peerId + "\"}";
        }

        PeerProfile p = profile.get();
        @SuppressWarnings("unchecked")
        List<String> cardUpdate = (List<String>) args.get("card");

        if (cardUpdate != null) {
            p.updateCard(cardUpdate);
            return "{\"result\": \"Peer card updated (" + cardUpdate.size() + " facts)\", \"card\": " +
                   toJson(p.getCard().facts()) + "}";
        }

        List<String> facts = p.getCard().facts();
        if (facts.isEmpty()) {
            return "{\"result\": \"No profile facts available yet.\"}";
        }
        return "{\"result\": " + toJson(facts) + "}";
    }

    private String handleSearch(String sessionId, String peerId, Map<String, Object> args) {
        String query = (String) args.getOrDefault("query", "");
        if (query.isBlank()) {
            return "{\"error\": \"Missing required parameter: query\"}";
        }

        int maxTokens = args.containsKey("max_tokens")
                ? Math.min((Integer) args.get("max_tokens"), 2000) : 800;
        int maxChars = maxTokens * 4;

        // Use semantic search if enabled
        if (config.isSemanticSearchEnabled() && embeddingService != null && vectorStore != null) {
            return handleSemanticSearch(sessionId, peerId, query, maxChars);
        }

        // Fallback to keyword search
        return handleKeywordSearch(sessionId, peerId, query, maxChars);
    }

    /**
     * Semantic search using vector embeddings.
     */
    private String handleSemanticSearch(String sessionId, String peerId, String query, int maxChars) {
        try {
            // Generate query embedding
            float[] queryEmbedding = embeddingService.embed(query);
            if (queryEmbedding == null) {
                log.debug("[Honcho] Semantic search: failed to generate embedding, falling back to keyword");
                return handleKeywordSearch(sessionId, peerId, query, maxChars);
            }

            // Search vector store
            int topK = config.getSemanticSearchTopK();
            double minScore = config.getSemanticSearchMinScore();
            List<VectorStore.SearchResult> results = vectorStore.search(
                    queryEmbedding, sessionId, peerId, topK, minScore);

            if (results.isEmpty()) {
                return "{\"result\": \"No relevant context found (semantic search).\"}";
            }

            // Format results
            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(results.size()).append(" relevant items:\n\n");

            for (VectorStore.SearchResult sr : results) {
                String source = sr.getSource();
                String content = sr.getContent();
                double score = sr.getScore();

                sb.append("- [").append(source).append("] (score: ")
                  .append(String.format("%.2f", score)).append(") ")
                  .append(truncate(content, 200)).append("\n");
            }

            String result = sb.toString();
            if (result.length() > maxChars) {
                result = result.substring(0, maxChars) + "\n…(truncated)";
            }

            return "{\"result\": " + toJson(result) + ", \"semantic\": true}";

        } catch (Exception e) {
            log.debug("[Honcho] Semantic search failed: {}, falling back to keyword", e.getMessage());
            return handleKeywordSearch(sessionId, peerId, query, maxChars);
        }
    }

    /**
     * Keyword-based search (fallback). Searches over observations and conclusions.
     */
    private String handleKeywordSearch(String sessionId, String peerId, String query, int maxChars) {
        StringBuilder result = new StringBuilder();
        Optional<PeerProfile> profile = getPeerProfile(sessionId, peerId);
        if (profile.isEmpty()) {
            return "{\"result\": \"No profile found.\"}";
        }

        PeerProfile p = profile.get();
        String lowerQuery = query.toLowerCase();

        // Search conclusions
        for (Conclusion c : p.getConclusions()) {
            if (c.fact().toLowerCase().contains(lowerQuery)) {
                result.append("- [conclusion] ").append(c.fact()).append("\n");
            }
        }

        // Search observations
        for (Observation obs : p.getObservations()) {
            if (obs.content().toLowerCase().contains(lowerQuery)) {
                result.append("- [").append(obs.source()).append("] ")
                      .append(truncate(obs.content(), 200))
                      .append("\n");
            }
        }

        String found = result.toString();
        if (found.isBlank()) {
            return "{\"result\": \"No relevant context found.\"}";
        }

        if (found.length() > maxChars) {
            found = found.substring(0, maxChars) + "\n…(truncated)";
        }

        return "{\"result\": " + toJson(found) + ", \"semantic\": false}";
    }

    private String handleReasoningSync(String sessionId, String peerId, Map<String, Object> args) {
        String query = (String) args.getOrDefault("query", "");
        if (query.isBlank()) {
            return "{\"error\": \"Missing required parameter: query\"}";
        }
        String reasoningLevel = (String) args.getOrDefault("reasoning_level", null);
        try {
            String result = dialecticEngine.runDialecticOnDemand(sessionId, query, reasoningLevel)
                    .timeout(Duration.ofSeconds(30))
                    .block();
            return "{\"result\": " + toJson(result != null ? result : "") + "}";
        } catch (Exception e) {
            return "{\"result\": \"Dialectic reasoning failed: " + e.getMessage() + "\"}";
        }
    }

    private String handleContext(String sessionId, String peerId, Map<String, Object> args) {
        Optional<PeerProfile> profile = getPeerProfile(sessionId, peerId);
        if (profile.isEmpty()) {
            return "{\"result\": \"No context available.\"}";
        }

        PeerProfile p = profile.get();
        StringBuilder sb = new StringBuilder();

        if (!p.getRepresentation().isBlank()) {
            sb.append("## Representation\n").append(p.getRepresentation()).append("\n\n");
        }

        List<String> facts = p.getCard().facts();
        if (!facts.isEmpty()) {
            sb.append("## Peer Card\n");
            for (String fact : facts) {
                sb.append("- ").append(fact).append("\n");
            }
            sb.append("\n");
        }

        // Recent messages
        List<Observation> observations = p.getObservations();
        if (!observations.isEmpty()) {
            sb.append("## Recent messages\n");
            int start = Math.max(0, observations.size() - 5);
            for (Observation obs : observations.subList(start, observations.size())) {
                sb.append("- [").append(obs.source()).append("] ")
                  .append(obs.content().substring(0, Math.min(200, obs.content().length())))
                  .append("\n");
            }
        }

        String result = sb.toString();
        return "{\"result\": " + toJson(result.isBlank() ? "No context available." : result) + "}";
    }

    private String handleConclude(String sessionId, String peerId, Map<String, Object> args) {
        String deleteId = ((String) args.getOrDefault("delete_id", "")).trim();
        String conclusion = ((String) args.getOrDefault("conclusion", "")).trim();

        boolean hasDelete = !deleteId.isBlank();
        boolean hasConclusion = !conclusion.isBlank();

        if (hasDelete == hasConclusion) {
            return "{\"error\": \"Exactly one of conclusion or delete_id must be provided.\"}";
        }

        if (hasDelete) {
            deleteConclusion(sessionId, peerId, deleteId);
            return "{\"result\": \"Conclusion " + deleteId + " deleted.\"}";
        }

        Conclusion c = addConclusion(sessionId, peerId, conclusion, "conclusion_api");
        if (c != null) {
            return "{\"result\": \"Conclusion saved: " + conclusion + "\", \"id\": \"" + c.id() + "\"}";
        }
        return "{\"error\": \"Failed to save conclusion.\"}";
    }

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle hooks
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Called at turn start — advances turn counter and queues prefetch.
     */
    public void onTurnStart(String sessionId, String userMessage) {
        dialecticEngine.onTurnStart(sessionId);
        dialecticEngine.queueDialecticPrefetch(sessionId, userMessage);
    }

    /**
     * Called at session end — flush all pending writes.
     */
    public void onSessionEnd(String sessionId) {
        // Wait for any pending sync thread
        Thread syncThread = syncThreads.get(sessionId);
        if (syncThread != null && syncThread.isAlive()) {
            try { syncThread.join(10_000); } catch (InterruptedException ignored) {}
        }
        log.info("[Honcho] Session ended: {}, observations={}, conclusions={}",
                sessionId, getObservationCount(sessionId), getConclusionCount(sessionId));
    }

    /**
     * Clear a session.
     */
    public void clearSession(String sessionId) {
        sessionProfiles.remove(sessionId);
        sessionKeys.remove(sessionId);
        log.debug("[Honcho] Session cleared: {}", sessionId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Diagnostics
    // ─────────────────────────────────────────────────────────────────────

    public Map<String, Object> getLivenessSnapshot(String sessionId) {
        return dialecticEngine.getLivenessSnapshot(sessionId);
    }

    public int getObservationCount(String sessionId) {
        return getUserProfile(sessionId).map(PeerProfile::getObservationCount).orElse(0);
    }

    public int getConclusionCount(String sessionId) {
        return getUserProfile(sessionId).map(PeerProfile::getConclusionCount).orElse(0);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private String resolveUserPeerId(String sessionId) {
        return getUserProfile(sessionId).map(PeerProfile::getPeerId).orElse(null);
    }

    /**
     * Pre-warm dialectic at session start (B7).
     * Fires both base context fetch and dialectic prewarm in background threads.
     */
    private void prewarmAtInit(String sessionId) {
        executor.submit(() -> {
            try {
                // First turn dialectic prewarm
                String prewarmQuery = "Summarize what you know about this user. " +
                                      "Focus on preferences, current projects, and working style.";
                dialecticEngine.queueDialecticPrefetch(sessionId, prewarmQuery);
                log.debug("[Honcho] Prewarm started for session: {}", sessionId);
            } catch (Exception e) {
                log.debug("[Honcho] Prewarm failed: {}", e.getMessage());
            }
        });
    }

    /**
     * Chunk a message into parts that fit within the limit.
     * Continuation chunks are prefixed with "[continued] ".
     */
    private List<String> chunkMessage(String content, int limit) {
        if (content == null || content.length() <= limit) {
            return List.of(content != null ? content : "");
        }

        List<String> chunks = new ArrayList<>();
        String prefix = "[continued] ";
        String remaining = content;
        boolean first = true;

        while (!remaining.isEmpty()) {
            int effective = first ? limit : limit - prefix.length();
            if (remaining.length() <= effective) {
                chunks.add(remaining);
                break;
            }

            String segment = remaining.substring(0, effective);

            // Try paragraph break first, then sentence, then word
            int cut = segment.lastIndexOf("\n\n");
            if (cut < effective * 0.3) cut = segment.lastIndexOf(". ");
            if (cut < effective * 0.3) cut = segment.lastIndexOf(" ");
            if (cut < effective * 0.3) cut = effective;

            String chunk = segment.substring(0, cut).trim();
            remaining = remaining.substring(cut).trim();

            if (!first) chunk = prefix + chunk;
            if (!chunk.isEmpty()) chunks.add(chunk);
            first = false;
        }

        return chunks;
    }

    /**
     * Build tool schemas for function calling.
     */
    private List<Map<String, Object>> buildToolSchemas() {
        return List.of(
            // honcho_profile
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "peer", Map.of("type", "string", "description",
                            "Peer to query. 'user' (default) or 'ai'. Or pass any peer ID."),
                    "card", Map.of("type", "array", "description",
                            "New peer card facts to set. Omit to read.")
                ),
                "name", "honcho_profile",
                "description", "Get or update the peer card — key facts about a person (name, preferences, communication style, patterns). Pass card to update; omit to read."
            ),
            // honcho_search
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "query", Map.of("type", "string", "description", "What to search for in user memory."),
                    "max_tokens", Map.of("type", "integer", "description",
                            "Token budget for returned context (default 800, max 2000)."),
                    "peer", Map.of("type", "string", "description", "Peer to query. 'user' (default) or 'ai'.")
                ),
                "required", List.of("query"),
                "name", "honcho_search",
                "description", "Semantic search over stored context about a peer. Returns raw excerpts ranked by relevance. Good when you want to find specific past facts."
            ),
            // honcho_reasoning
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "query", Map.of("type", "string", "description", "A natural language question about the user."),
                    "reasoning_level", Map.of("type", "string", "description",
                            "Reasoning depth: minimal/low/medium/high/max. Omit for default (low).",
                            "enum", List.of("minimal", "low", "medium", "high", "max")),
                    "peer", Map.of("type", "string", "description", "Peer to query. 'user' (default) or 'ai'.")
                ),
                "required", List.of("query"),
                "name", "honcho_reasoning",
                "description", "Ask a synthesized question about the user. Uses LLM reasoning. Higher cost than profile/search. Good for nuanced questions about user goals and preferences."
            ),
            // honcho_context
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "peer", Map.of("type", "string", "description", "Peer to query. 'user' (default) or 'ai'.")
                ),
                "name", "honcho_context",
                "description", "Get full session context from user profile: summary, representation, peer card, and recent messages. No LLM synthesis. Cheaper than honcho_reasoning."
            ),
            // honcho_conclude
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "conclusion", Map.of("type", "string", "description",
                            "A factual statement to persist. Provide this when creating a conclusion."),
                    "delete_id", Map.of("type", "string", "description",
                            "Conclusion ID to delete. Provide this when deleting."),
                    "peer", Map.of("type", "string", "description", "Peer to query. 'user' (default) or 'ai'.")
                ),
                "name", "honcho_conclude",
                "description", "Write or delete a persistent conclusion (fact) about a peer. Facts build the peer card over time. Pass exactly one of conclusion or delete_id."
            )
        );
    }

    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .writeValueAsString(obj);
        } catch (Exception e) {
            return "\"" + obj.toString().replace("\"", "\\\"") + "\"";
        }
    }

    /**
     * Truncate a string to max length.
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen);
    }

    /**
     * Store an observation to the vector index (for semantic search).
     */
    public void indexObservation(String sessionId, String peerId, String content, String source) {
        if (!config.isSemanticSearchEnabled() || embeddingService == null || vectorStore == null) {
            return;
        }

        // Truncate long content
        int maxLen = config.getEmbeddingMaxTextLength();
        String truncated = truncate(content, maxLen);

        // Generate embedding asynchronously
        embeddingService.embedAsync(truncated).thenAccept(embedding -> {
            if (embedding != null) {
                vectorStore.store(sessionId, peerId, truncated, embedding, source, null);
                log.debug("[Honcho] Indexed observation: session={}, source={}, len={}",
                        sessionId, source, truncated.length());
            }
        }).exceptionally(e -> {
            log.debug("[Honcho] Failed to index observation: {}", e.getMessage());
            return null;
        });
    }

    /**
     * Get vector store stats.
     */
    public Map<String, Object> getVectorStats(String sessionId) {
        if (vectorStore == null) return Map.of("enabled", false);
        return Map.of(
            "enabled", config.isSemanticSearchEnabled(),
            "vector_count", vectorStore.getVectorCount(sessionId),
            "dimension", vectorStore.getDimension()
        );
    }

    /**
     * Get strategy info for diagnostics.
     */
    public Map<String, Object> getStrategyInfo() {
        return strategyResolver.getStrategyInfo();
    }

    @PreDestroy
    public void shutdown() {
        log.info("[Honcho] Shutting down...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
        // Shutdown vector store and embedding service
        if (vectorStore != null) vectorStore.shutdown();
        if (embeddingService != null) embeddingService.shutdown();
    }
}
