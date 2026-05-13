package com.hermes.agent.userprofile;

import com.hermes.agent.memory.MemoryProvider;
import com.hermes.agent.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HonchoMemoryProvider — MemoryProvider implementation backed by HonchoService.
 *
 * <p>This provider bridges the generic MemoryProvider interface with the
 * Honcho-style deep user profiling system (peer cards, dialectic reasoning,
 * observation recording, semantic search, conclusions).
 *
 * <p>Exposes 5 tools to the LLM:
 * <ul>
 *   <li><b>honcho_profile</b> — get/update peer card (key facts)</li>
 *   <li><b>honcho_search</b> — semantic search over stored context</li>
 *   <li><b>honcho_reasoning</b> — LLM-synthesized Q&A about the user</li>
 *   <li><b>honcho_context</b> — raw session context (representation + card + messages)</li>
 *   <li><b>honcho_conclude</b> — write/delete persistent conclusions (facts)</li>
 * </ul>
 *
 * <p>Also provides context injection (honcho_context auto-injected into system prompt
 * when recallMode is "hybrid" or "context").
 *
 * <p>Architecture notes:
 * <ul>
 *   <li>honcho_profile maps to honcho_peer_card CRUD</li>
 *   <li>honcho_search uses keyword + observation search (Honcho cloud not required)</li>
 *   <li>honcho_reasoning uses DialecticEngine for multi-pass LLM reasoning</li>
 *   <li>honcho_context = PeerProfile.buildContextString()</li>
 *   <li>honcho_conclude = PeerProfile.addConclusion / removeConclusion</li>
 * </ul>
 */
@Component
public class HonchoMemoryProvider implements MemoryProvider {

    private static final Logger log = LoggerFactory.getLogger(HonchoMemoryProvider.class);

    public static final String NAME = "honcho";
    private static final String TOOL_PREFIX = "honcho_";

    private final HonchoService honchoService;
    private final UserProfileConfig config;

    /** Whether this provider is active (honchoEnabled=true) */
    private volatile boolean active = false;

    /** Cached session context for this turn */
    private final Map<String, String> prefetchCache = new ConcurrentHashMap<>();

    public HonchoMemoryProvider(HonchoService honchoService, UserProfileConfig config) {
        this.honchoService = honchoService;
        this.config = config;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return config != null;
    }

    @Override
    public void initialize(String sessionId, Map<String, Object> kwargs) {
        String peerName = getString(kwargs, "peerName", config.getPeerName());
        String sessionTitle = getString(kwargs, "sessionTitle", null);
        String gatewayKey = getString(kwargs, "gatewaySessionKey", null);

        honchoService.initializeSession(sessionId, sessionTitle, gatewayKey, peerName);
        active = true;
        log.info("[Honcho] Provider initialized for session: {}", sessionId);
    }

    @Override
    public String systemPromptBlock() {
        if (!active || !config.isContextInjectionEnabled()) return "";
        return honchoService.systemPromptBlock();
    }

    @Override
    public String prefetch(String query, String sessionId) {
        if (!active || !config.isContextInjectionEnabled()) return "";
        try {
            // Build context string (includes dialectic supplement if ready)
            String ctx = honchoService.buildContextString(sessionId);

            // Cache it for this turn
            prefetchCache.put(sessionId, ctx);
            return ctx;
        } catch (Exception e) {
            log.debug("[Honcho] Prefetch failed: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public void queuePrefetch(String query, String sessionId) {
        if (!active || !config.isContextInjectionEnabled()) return;
        // HonchoService handles its own background prefetch via DialecticEngine
        honchoService.onTurnStart(sessionId, query);
    }

    @Override
    public void syncTurn(String userContent, String assistantContent, String sessionId) {
        if (!active) return;
        try {
            honchoService.syncTurn(sessionId, userContent, assistantContent);
        } catch (Exception e) {
            log.debug("[Honcho] syncTurn failed: {}", e.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> getToolSchemas() {
        if (!active || !config.isToolsEnabled()) return List.of();
        return honchoService.getToolSchemas();
    }

    @Override
    public String handleToolCall(String toolName, Map<String, Object> args) {
        if (!active) {
            return "{\"error\":\"Honcho provider not active.\"}";
        }

        // Strip prefix for tools that come without it (tool router strips prefix)
        String actualName = toolName.startsWith(TOOL_PREFIX) ? toolName : TOOL_PREFIX + toolName;

        if (!honchoService.getToolSchemas().stream()
                .anyMatch(s -> {
                    Map<String, Object> fn = (Map<String, Object>) s.get("function");
                    return fn != null && (
                            fn.get("name").equals(toolName) || fn.get("name").equals(actualName));
                })) {
            return "{\"error\":\"Unknown Honcho tool: " + toolName + "\"}";
        }

        return honchoService.handleToolCall(toolName, args, getSessionIdFromArgs(args));
    }

    @Override
    public void onTurnStart(int turnNumber, String message, Map<String, Object> kwargs) {
        if (!active) return;
        String sessionId = getSessionIdFromKwargs(kwargs, "unknown-turn");
        honchoService.onTurnStart(sessionId, message);
    }

    @Override
    public void onSessionEnd(List<Message> messages) {
        if (!active) return;
        // Session cleanup is handled at Agent level via UserProfileManager.onSessionEnd()
        // This hook is reserved for any session-end cleanup specific to Honcho
        log.debug("[Honcho] onSessionEnd called");
    }

    @Override
    public void onMemoryWrite(String action, String target, String content) {
        if (!active) return;
        log.debug("[Honcho] onMemoryWrite: action={}, target={}", action, target);
        // Mirror built-in memory writes as Honcho conclusions
        if ("add".equals(action) || "update".equals(action)) {
            String sessionId = "builtin-mirror";
            String aiPeerId = config.getAiPeer();
            honchoService.addConclusion(sessionId, aiPeerId,
                    "Built-in memory updated: " + target, "builtin-memory");
        }
    }

    @Override
    public void shutdown() {
        active = false;
        prefetchCache.clear();
        log.info("[Honcho] Provider shutdown");
    }

    // ── Accessors for diagnostics ──────────────────────────────────────

    public boolean isActive() {
        return active;
    }

    public Map<String, Object> getSessionStats(String sessionId) {
        return Map.of(
            "observation_count", honchoService.getObservationCount(sessionId),
            "conclusion_count", honchoService.getConclusionCount(sessionId),
            "liveness", honchoService.getLivenessSnapshot(sessionId)
        );
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private String getSessionIdFromArgs(Map<String, Object> args) {
        Object sid = args.get("sessionId");
        return sid != null ? sid.toString() : "default";
    }

    private String getSessionIdFromKwargs(Map<String, Object> kwargs, String fallback) {
        Object sid = kwargs != null ? kwargs.get("sessionId") : null;
        return sid != null ? sid.toString() : fallback;
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object v = map != null ? map.get(key) : null;
        return v != null ? v.toString() : defaultValue;
    }
}
