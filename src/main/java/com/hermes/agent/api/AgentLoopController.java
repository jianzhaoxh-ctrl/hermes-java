package com.hermes.agent.api;

import com.hermes.agent.loop.AgentLoop;
import com.hermes.agent.loop.AgentLoopService;
import com.hermes.agent.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for AgentLoop - multi-turn tool calling agent.
 * 
 * Endpoints:
 * - POST /agent/chat - Simple chat with multi-turn tool support
 * - POST /agent/chat/stream - Streaming chat
 * - POST /agent/run - Advanced run with full result
 * - POST /agent/loop - Create a new loop instance
 * - POST /agent/loop/{loopId}/interrupt - Interrupt a running loop
 */
@RestController
@RequestMapping("/agent")
public class AgentLoopController {
    
    private static final Logger log = LoggerFactory.getLogger(AgentLoopController.class);
    
    private final AgentLoopService agentLoopService;
    private final Map<String, AgentLoop> activeLoops = new HashMap<>();
    
    public AgentLoopController(AgentLoopService agentLoopService) {
        this.agentLoopService = agentLoopService;
    }
    
    // ─────────────────────────── Simple Chat API ───────────────────────────
    
    /**
     * Simple chat - returns final response after all tool calls.
     * 
     * POST /agent/chat
     * Body: { "message": "...", "sessionId": "..." }
     */
    @PostMapping("/chat")
    public Mono<ResponseEntity<Map<String, Object>>> chat(
            @RequestBody Map<String, Object> body,
            @RequestParam(required = false) String sessionId
    ) {
        String userMessage = (String) body.get("message");
        String sid = sessionId != null ? sessionId : generateSessionId();
        
        if (userMessage == null || userMessage.isBlank()) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", "message is required")));
        }
        
        log.info("[AgentLoop:{}] Chat request: {}", sid, truncate(userMessage, 100));
        
        return agentLoopService.chat(sid, userMessage)
                .map(response -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("response", response);
                    result.put("sessionId", sid);
                    return ResponseEntity.ok(result);
                })
                .onErrorResume(e -> {
                    log.error("[AgentLoop:{}] Chat error: {}", sid, e.getMessage());
                    return Mono.just(ResponseEntity.internalServerError()
                            .body(Map.of("error", e.getMessage())));
                });
    }
    
    /**
     * Streaming chat - returns SSE stream of response chunks.
     * 
     * POST /agent/chat/stream
     * Body: { "message": "..." }
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(
            @RequestBody Map<String, Object> body,
            @RequestParam(required = false) String sessionId
    ) {
        String userMessage = (String) body.get("message");
        String sid = sessionId != null ? sessionId : generateSessionId();
        
        if (userMessage == null || userMessage.isBlank()) {
            return Flux.just("event: error\ndata: message is required\n\n");
        }
        
        log.info("[AgentLoop:{}] Stream request: {}", sid, truncate(userMessage, 100));
        
        return agentLoopService.chatStream(sid, userMessage)
                .map(chunk -> "data: " + escapeSSE(chunk) + "\n\n")
                .concatWith(Flux.just("data: [DONE]\n\n"))
                .onErrorResume(e -> {
                    log.error("[AgentLoop:{}] Stream error: {}", sid, e.getMessage());
                    return Flux.just("event: error\ndata: " + escapeSSE(e.getMessage()) + "\n\n");
                });
    }
    
    // ─────────────────────────── Advanced Run API ───────────────────────────
    
    /**
     * Advanced run - returns full AgentLoopResult with metadata.
     * 
     * POST /agent/run
     * Body: { 
     *   "messages": [{"role": "user", "content": "..."}],
     *   "sessionId": "...",
     *   "maxIterations": 90
     * }
     */
    @PostMapping("/run")
    public Mono<ResponseEntity<Map<String, Object>>> run(
            @RequestBody Map<String, Object> body
    ) {
        String sid = (String) body.getOrDefault("sessionId", generateSessionId());
        Integer maxIterations = (Integer) body.getOrDefault("maxIterations", 90);
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messageList = (List<Map<String, Object>>) body.get("messages");
        
        if (messageList == null || messageList.isEmpty()) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", "messages array is required")));
        }
        
        // Convert to Message objects
        List<Message> messages = new ArrayList<>();
        for (Map<String, Object> m : messageList) {
            String role = (String) m.getOrDefault("role", "user");
            String content = (String) m.getOrDefault("content", "");
            messages.add(new Message(role, content, Instant.now()));
        }
        
        log.info("[AgentLoop:{}] Run request: {} messages, maxIterations={}", 
                sid, messages.size(), maxIterations);
        
        AgentLoop loop = agentLoopService.createLoop(maxIterations, false);
        activeLoops.put(sid, loop);
        
        return loop.run(messages, sid)
                .doFinally(signal -> activeLoops.remove(sid))
                .map(result -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("finalResponse", result.getFinalResponse());
                    response.put("iterations", result.getIterations());
                    response.put("completed", result.isCompleted());
                    response.put("interrupted", result.isInterrupted());
                    response.put("exitReason", result.getExitReason());
                    response.put("sessionId", sid);
                    
                    // Include message count
                    response.put("messageCount", result.getMessages().size());
                    
                    return ResponseEntity.ok(response);
                })
                .onErrorResume(e -> {
                    log.error("[AgentLoop:{}] Run error: {}", sid, e.getMessage());
                    return Mono.just(ResponseEntity.internalServerError()
                            .body(Map.of("error", e.getMessage(), "sessionId", sid)));
                });
    }
    
    // ─────────────────────────── Loop Control API ───────────────────────────
    
    /**
     * Interrupt a running loop.
     * 
     * POST /agent/loop/{sessionId}/interrupt
     */
    @PostMapping("/loop/{sessionId}/interrupt")
    public ResponseEntity<Map<String, Object>> interrupt(@PathVariable String sessionId) {
        AgentLoop loop = activeLoops.get(sessionId);
        
        if (loop == null) {
            return ResponseEntity.notFound().build();
        }
        
        loop.interrupt();
        log.info("[AgentLoop:{}] Interrupt requested", sessionId);
        
        return ResponseEntity.ok(Map.of(
                "status", "interrupted",
                "sessionId", sessionId
        ));
    }
    
    /**
     * Steer a running loop with guidance.
     * 
     * POST /agent/loop/{sessionId}/steer
     * Body: { "guidance": "..." }
     */
    @PostMapping("/loop/{sessionId}/steer")
    public ResponseEntity<Map<String, Object>> steer(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body
    ) {
        AgentLoop loop = activeLoops.get(sessionId);
        
        if (loop == null) {
            return ResponseEntity.notFound().build();
        }
        
        String guidance = (String) body.get("guidance");
        if (guidance == null || guidance.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "guidance is required"));
        }
        
        loop.steer(guidance);
        log.info("[AgentLoop:{}] Steer queued: {}", sessionId, truncate(guidance, 100));
        
        return ResponseEntity.ok(Map.of(
                "status", "steered",
                "sessionId", sessionId
        ));
    }
    
    /**
     * Get active loops.
     * 
     * GET /agent/loops
     */
    @GetMapping("/loops")
    public ResponseEntity<Map<String, Object>> getActiveLoops() {
        return ResponseEntity.ok(Map.of(
                "activeLoops", activeLoops.keySet(),
                "count", activeLoops.size()
        ));
    }
    
    // ─────────────────────────── Status API ───────────────────────────
    
    /**
     * Health check.
     * 
     * GET /agent/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean healthy = agentLoopService.isHealthy();
        
        return ResponseEntity.ok(Map.of(
                "status", healthy ? "healthy" : "unhealthy",
                "maxIterations", agentLoopService.getMaxIterations()
        ));
    }
    
    // ─────────────────────────── Utility Methods ───────────────────────────
    
    private String generateSessionId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
    
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
    
    private String escapeSSE(String s) {
        if (s == null) return "";
        return s.replace("\n", "\\n").replace("\r", "\\r");
    }
}
