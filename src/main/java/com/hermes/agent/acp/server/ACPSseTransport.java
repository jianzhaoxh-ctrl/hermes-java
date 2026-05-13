package com.hermes.agent.acp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server-Sent Events (SSE) transport for ACP — pushes session updates
 * to connected HTTP clients via SSE, receives requests via REST POST.
 *
 * <p>Each client connection gets its own SseEmitter. The transport
 * multiplexes updates for all sessions to all connected clients
 * (editors can filter by sessionId client-side).
 *
 * <p>Reference: Python acp library SSE transport
 */
public class ACPSseTransport implements ACPTransport {

    private static final Logger log = LoggerFactory.getLogger(ACPSseTransport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000; // 30 min

    /** Active SSE connections keyed by connection ID */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    private final AtomicBoolean connected = new AtomicBoolean(true);

    /**
     * Register a new SSE client connection.
     *
     * @return the SseEmitter for the client
     */
    public SseEmitter createEmitter() {
        String connId = "sse-" + System.currentTimeMillis() + "-" + (int) (Math.random() * 10000);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        emitter.onCompletion(() -> {
            log.info("SSE connection completed: {}", connId);
            emitters.remove(connId);
        });
        emitter.onTimeout(() -> {
            log.info("SSE connection timed out: {}", connId);
            emitters.remove(connId);
        });
        emitter.onError(e -> {
            log.debug("SSE connection error: {} - {}", connId, e.getMessage());
            emitters.remove(connId);
        });

        emitters.put(connId, emitter);
        log.info("SSE client connected: {} (total: {})", connId, emitters.size());
        return emitter;
    }

    /**
     * Get the number of active SSE connections.
     */
    public int getConnectionCount() {
        return emitters.size();
    }

    @Override
    public void sendSessionUpdate(String sessionId, Map<String, Object> update) {
        if (!connected.get()) return;

        Map<String, Object> event = Map.of(
                "sessionId", sessionId,
                "update", update
        );

        broadcastSse("session_update", event);
    }

    @Override
    public void sendResponse(Object id, Object response) {
        if (!connected.get()) return;

        Map<String, Object> event = new java.util.LinkedHashMap<>();
        if (id != null) event.put("id", id.toString());
        event.put("result", response);

        broadcastSse("response", event);
    }

    @Override
    public void sendError(Object id, int code, String message) {
        if (!connected.get()) return;

        Map<String, Object> event = new java.util.LinkedHashMap<>();
        if (id != null) event.put("id", id.toString());
        event.put("error", Map.of("code", code, "message", message));

        broadcastSse("error", event);
    }

    @Override
    public boolean isConnected() {
        return connected.get() && !emitters.isEmpty();
    }

    @Override
    public void close() {
        connected.set(false);
        for (Map.Entry<String, SseEmitter> entry : emitters.entrySet()) {
            try {
                entry.getValue().complete();
            } catch (Exception e) {
                log.debug("Error closing SSE emitter {}", entry.getKey(), e);
            }
        }
        emitters.clear();
    }

    // ---- Internal helpers ----

    private void broadcastSse(String eventName, Object data) {
        if (emitters.isEmpty()) return;

        String jsonData;
        try {
            jsonData = MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            log.error("Failed to serialize SSE data", e);
            return;
        }

        // Send to all connected clients
        var iterator = emitters.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, SseEmitter> entry = iterator.next();
            try {
                entry.getValue().send(SseEmitter.event()
                        .name(eventName)
                        .data(jsonData, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                log.debug("Failed to send SSE to {}, removing", entry.getKey());
                iterator.remove();
            }
        }
    }
}
