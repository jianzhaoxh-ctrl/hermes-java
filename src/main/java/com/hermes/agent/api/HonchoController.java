package com.hermes.agent.api;

import com.hermes.agent.userprofile.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Honcho REST API — manage the user profiling system.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET  /honcho/status          — system status and config</li>
 *   <li>POST /honcho/session/{sessionId}/init — initialize a session with peers</li>
 *   <li>GET  /honcho/profile/{sessionId}  — peer card + representation</li>
 *   <li>GET  /honcho/profile/{sessionId}/context — full context string</li>
 *   <li>GET  /honcho/profile/{sessionId}/conclusions — all conclusions</li>
 *   <li>POST /honcho/profile/{sessionId}/conclude — add a conclusion</li>
 *   <li>DELETE /honcho/profile/{sessionId}/conclusions/{id} — delete a conclusion</li>
 *   <li>POST /honcho/profile/{sessionId}/seed  — seed identity from file</li>
 *   <li>POST /honcho/profile/{sessionId}/extract — trigger on-demand extraction</li>
 *   <li>GET  /honcho/tools            — list available Honcho tools</li>
 *   <li>POST /honcho/tools/call       — call a tool directly</li>
 *   <li>GET  /honcho/config           — get current config</li>
 *   <li>PATCH /honcho/config          — update config (runtime)</li>
 *   <li>GET  /honcho/liveness/{sessionId} — dialectic engine state</li>
 *   <li>GET  /honcho/strategy         — session strategy info</li>
 *   <li>GET  /honcho/vectors/{sessionId} — vector store stats</li>
 *   <li>POST /honcho/search           — semantic search endpoint</li>
 * </ul>
 */
@RestController
@RequestMapping("/honcho")
public class HonchoController {

    private static final Logger log = LoggerFactory.getLogger(HonchoController.class);

    private final HonchoService honchoService;
    private final HonchoMemoryProvider provider;
    private final UserProfileConfig config;
    private final ProfileExtractor extractor;

    public HonchoController(HonchoService honchoService,
                             HonchoMemoryProvider provider,
                             UserProfileConfig config,
                             ProfileExtractor extractor) {
        this.honchoService = honchoService;
        this.provider = provider;
        this.config = config;
        this.extractor = extractor;
    }

    // ── Status ─────────────────────────────────────────────────────────

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", provider.isActive());
        result.put("recall_mode", config.getRecallMode());
        result.put("observation_mode", config.getObservationMode());
        result.put("dialectic_depth", config.getDialecticDepth());
        result.put("dialectic_cadence", config.getDialecticCadence());
        result.put("dialectic_reasoning_level", config.getDialecticReasoningLevel());
        result.put("extraction_enabled", extractor.isEnabled());
        result.put("tools_count", honchoService.getToolSchemas().size());
        result.put("tools_available", config.isToolsEnabled());
        result.put("session_strategy", config.getSessionStrategy());
        result.put("semantic_search", config.isSemanticSearchEnabled());
        result.put("embedding_backend", config.getEmbeddingBackend());
        return result;
    }

    // ── Session Init ─────────────────────────────────────────────────────

    @PostMapping("/session/{sessionId}/init")
    public Map<String, Object> initSession(@PathVariable String sessionId,
                                           @RequestBody(required = false) Map<String, String> body) {
        String sessionTitle = body != null ? body.getOrDefault("title", sessionId) : sessionId;
        String userPeerName = body != null ? body.get("peerName") : null;
        honchoService.initializeSession(sessionId, sessionTitle, null, userPeerName);
        return Map.of("success", true, "session", sessionId,
                "user_peer", honchoService.getUserProfile(sessionId)
                        .map(com.hermes.agent.userprofile.PeerProfile::getPeerId).orElse("unknown"));
    }

    // ── Profile ────────────────────────────────────────────────────────

    @GetMapping("/profile/{sessionId}")
    public Map<String, Object> getProfile(@PathVariable String sessionId) {
        return honchoService.getUserProfile(sessionId)
                .map(p -> Map.<String, Object>of(
                        "peer_id", p.getPeerId(),
                        "peer_type", p.getPeerType(),
                        "observation_mode", p.getObservationMode(),
                        "card", p.getCard().facts(),
                        "card_version", p.getCard().version(),
                        "representation", p.getRepresentation(),
                        "observation_count", p.getObservationCount(),
                        "conclusion_count", p.getConclusionCount(),
                        "created_at", p.getCreatedAt(),
                        "last_updated", p.getLastRepresentationUpdate()
                ))
                .orElse(Map.of("error", "Profile not found for session: " + sessionId));
    }

    @GetMapping("/profile/{sessionId}/context")
    public Map<String, Object> getContext(@PathVariable String sessionId,
                                           @RequestParam(defaultValue = "2000") int maxChars) {
        String ctx = honchoService.buildContextString(sessionId, maxChars);
        return Map.of("context", ctx, "length", ctx.length());
    }

    @GetMapping("/profile/{sessionId}/conclusions")
    public Map<String, Object> getConclusions(@PathVariable String sessionId) {
        return honchoService.getUserProfile(sessionId)
                .map(p -> {
                    List<Map<String, Object>> cs = p.getConclusions().stream()
                            .map(c -> Map.<String, Object>of(
                                    "id", c.id(),
                                    "fact", c.fact(),
                                    "source", c.source(),
                                    "timestamp", c.timestamp()
                            ))
                            .toList();
                    return Map.<String, Object>of("conclusions", cs, "total", cs.size());
                })
                .orElse(Map.of("error", "Profile not found"));
    }

    @PostMapping("/profile/{sessionId}/conclude")
    public Map<String, Object> addConclusion(@PathVariable String sessionId,
                                              @RequestBody Map<String, String> body) {
        String fact = body.get("fact");
        if (fact == null || fact.isBlank()) {
            return Map.of("error", "Missing 'fact' field");
        }
        Conclusion c = honchoService.addUserConclusion(sessionId, fact.trim());
        if (c != null) {
            return Map.of("success", true, "id", c.id(), "fact", c.fact());
        }
        return Map.of("error", "Failed to add conclusion");
    }

    @DeleteMapping("/profile/{sessionId}/conclusions/{conclusionId}")
    public Map<String, Object> deleteConclusion(@PathVariable String sessionId,
                                                 @PathVariable String conclusionId) {
        String peerId = honchoService.getUserProfile(sessionId)
                .map(com.hermes.agent.userprofile.PeerProfile::getPeerId)
                .orElse(null);
        if (peerId == null) {
            return Map.of("error", "Profile not found");
        }
        honchoService.deleteConclusion(sessionId, peerId, conclusionId);
        return Map.of("success", true, "deleted_id", conclusionId);
    }

    // ── Seed identity ─────────────────────────────────────────────────

    @PostMapping("/profile/{sessionId}/seed")
    public Map<String, Object> seedIdentity(@PathVariable String sessionId,
                                             @RequestBody Map<String, String> body) {
        String type = body.getOrDefault("type", "user"); // "user" or "ai"
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            return Map.of("error", "Missing 'content' field");
        }

        if ("ai".equals(type)) {
            honchoService.seedAIIdentity(sessionId, content);
        } else {
            honchoService.seedUserIdentity(sessionId, content);
        }

        return Map.of("success", true, "type", type, "content_length", content.length());
    }

    // ── Extraction ────────────────────────────────────────────────────

    @PostMapping("/profile/{sessionId}/extract")
    public Map<String, Object> triggerExtraction(@PathVariable String sessionId,
                                                 @RequestParam(defaultValue = "false") boolean deep) {
        extractor.extractNow(sessionId);
        return Map.of("success", true, "session", sessionId, "deep", deep);
    }

    // ── Tools ────────────────────────────────────────────────────────

    @GetMapping("/tools")
    public Map<String, Object> listTools() {
        return Map.of(
            "tools", honchoService.getToolSchemas(),
            "tools_enabled", config.isToolsEnabled()
        );
    }

    @PostMapping("/tools/call")
    public Map<String, Object> callTool(@RequestBody Map<String, Object> body) {
        String toolName = (String) body.get("tool");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) body.getOrDefault("args", Map.of());
        String sessionId = (String) body.getOrDefault("sessionId", "default");

        if (toolName == null || toolName.isBlank()) {
            return Map.of("error", "Missing 'tool' field");
        }

        try {
            String result = honchoService.handleToolCall(toolName, args, sessionId);
            return Map.of("success", true, "tool", toolName, "result", result);
        } catch (Exception e) {
            log.error("[Honcho] Tool call failed: {} -> {}", toolName, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    // ── Config ────────────────────────────────────────────────────────

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        return Map.ofEntries(
                Map.entry("peer_name", config.getPeerName()),
                Map.entry("ai_peer", config.getAiPeer()),
                Map.entry("observation_mode", config.getObservationMode()),
                Map.entry("write_frequency", config.getWriteFrequency()),
                Map.entry("recall_mode", config.getRecallMode()),
                Map.entry("context_char_budget", config.getContextCharBudget()),
                Map.entry("dialectic_cadence", config.getDialecticCadence()),
                Map.entry("dialectic_depth", config.getDialecticDepth()),
                Map.entry("dialectic_reasoning_level", config.getDialecticReasoningLevel()),
                Map.entry("reasoning_level_cap", config.getReasoningLevelCap()),
                Map.entry("reasoning_heuristic", config.isReasoningHeuristic()),
                Map.entry("dialectic_max_chars", config.getDialecticMaxChars()),
                Map.entry("session_strategy", config.getSessionStrategy()),
                Map.entry("save_messages", config.isSaveMessages()),
                Map.entry("honcho_enabled", config.isHonchoEnabled())
        );
    }

    @PatchMapping("/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> patch) {
        // Runtime config updates (non-persistent)
        if (patch.containsKey("peer_name")) config.setPeerName((String) patch.get("peer_name"));
        if (patch.containsKey("ai_peer")) config.setAiPeer((String) patch.get("ai_peer"));
        if (patch.containsKey("observation_mode")) config.setObservationMode((String) patch.get("observation_mode"));
        if (patch.containsKey("write_frequency")) config.setWriteFrequency((String) patch.get("write_frequency"));
        if (patch.containsKey("recall_mode")) config.setRecallMode((String) patch.get("recall_mode"));
        if (patch.containsKey("context_char_budget")) config.setContextCharBudget((Integer) patch.get("context_char_budget"));
        if (patch.containsKey("dialectic_cadence")) config.setDialecticCadence((Integer) patch.get("dialectic_cadence"));
        if (patch.containsKey("dialectic_depth")) config.setDialecticDepth((Integer) patch.get("dialectic_depth"));
        if (patch.containsKey("dialectic_reasoning_level")) config.setDialecticReasoningLevel((String) patch.get("dialectic_reasoning_level"));
        if (patch.containsKey("reasoning_level_cap")) config.setReasoningLevelCap((String) patch.get("reasoning_level_cap"));
        if (patch.containsKey("reasoning_heuristic")) config.setReasoningHeuristic((Boolean) patch.get("reasoning_heuristic"));
        if (patch.containsKey("dialectic_max_chars")) config.setDialecticMaxChars((Integer) patch.get("dialectic_max_chars"));
        if (patch.containsKey("session_strategy")) config.setSessionStrategy((String) patch.get("session_strategy"));
        if (patch.containsKey("save_messages")) config.setSaveMessages((Boolean) patch.get("save_messages"));

        log.info("[Honcho] Config updated via API: {} keys changed", patch.size());
        return Map.of("success", true, "updated_keys", patch.keySet());
    }

    // ── Liveness / Diagnostics ───────────────────────────────────────

    @GetMapping("/liveness/{sessionId}")
    public Map<String, Object> getLiveness(@PathVariable String sessionId) {
        return honchoService.getLivenessSnapshot(sessionId);
    }

    @GetMapping("/stats/{sessionId}")
    public Map<String, Object> getStats(@PathVariable String sessionId) {
        return provider.getSessionStats(sessionId);
    }

    // ── Session Strategy ───────────────────────────────────────────────

    @GetMapping("/strategy")
    public Map<String, Object> getStrategyInfo() {
        return honchoService.getStrategyInfo();
    }

    // ── Vector Store / Semantic Search ──────────────────────────────────

    @GetMapping("/vectors/{sessionId}")
    public Map<String, Object> getVectorStats(@PathVariable String sessionId) {
        return honchoService.getVectorStats(sessionId);
    }

    @PostMapping("/search")
    public Map<String, Object> semanticSearch(@RequestBody Map<String, Object> body) {
        String query = (String) body.get("query");
        String sessionId = (String) body.getOrDefault("sessionId", "default");
        String peer = (String) body.getOrDefault("peer", "user");
        int maxTokens = body.containsKey("max_tokens")
                ? (Integer) body.get("max_tokens") : 800;

        if (query == null || query.isBlank()) {
            return Map.of("error", "Missing 'query' field");
        }

        try {
            String result = honchoService.handleToolCall(
                    "honcho_search",
                    Map.of("query", query, "peer", peer, "max_tokens", maxTokens),
                    sessionId);
            return Map.of("success", true, "query", query, "result", result);
        } catch (Exception e) {
            log.error("[Honcho] Search failed: {}", e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }
}
