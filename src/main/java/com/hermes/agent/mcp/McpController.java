package com.hermes.agent.mcp;

import com.hermes.agent.mcp.model.McpMessages.JsonRpcRequest;
import com.hermes.agent.mcp.model.McpMessages.JsonRpcResponse;
import com.hermes.agent.mcp.model.McpMessages.JsonRpcError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MCP HTTP transport — Spring REST controller.
 *
 * Provides two endpoints:
 * - POST /mcp  — JSON-RPC request → JSON-RPC response
 * - GET  /mcp/sse  — SSE event stream for server-initiated notifications
 *
 * This follows the MCP HTTP+SSE transport pattern where:
 * - Client POSTs JSON-RPC requests and gets immediate responses
 * - Server can push notifications via the SSE stream
 */
@RestController
@RequestMapping("/mcp")
public class McpController {
    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final McpServer server;
    private final CopyOnWriteArrayList<SseEmitter> sseClients = new CopyOnWriteArrayList<>();

    public McpController(McpServer server) {
        this.server = server;
    }

    /**
     * Main JSON-RPC endpoint.
     * Accepts a JSON-RPC request body, dispatches via McpServer, returns the response.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> handleJsonRpc(@RequestBody Map<String, Object> rawRequest) {
        try {
            String rawJson = server.getMapper().writeValueAsString(rawRequest);
            String responseJson = server.handle(rawJson);

            if (responseJson == null) {
                // Notification — no response body
                return ResponseEntity.ok().build();
            }

            @SuppressWarnings("unchecked")
            Object result = server.getMapper().readValue(responseJson, Map.class);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("MCP HTTP error", e);
            return ResponseEntity.status(500).body(Map.of(
                    "jsonrpc", "2.0",
                    "id", rawRequest.get("id"),
                    "error", Map.of("code", -32603, "message", e.getMessage())
            ));
        }
    }

    /**
     * SSE event stream for server-initiated notifications.
     * Clients connect here to receive tool update notifications, logs, etc.
     */
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sseStream() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        sseClients.add(emitter);

        emitter.onCompletion(() -> sseClients.remove(emitter));
        emitter.onTimeout(() -> sseClients.remove(emitter));
        emitter.onError((e) -> sseClients.remove(emitter));

        log.info("MCP SSE client connected (total: {})", sseClients.size());
        return emitter;
    }

    /**
     * Health / status endpoint.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "protocol", "2024-11-05",
                "server", "hermes-java",
                "version", "1.0.0",
                "initialized", server.isInitialized(),
                "sseClients", sseClients.size(),
                "toolsCount", server.getMapper() != null ?
                        server.getMapper().convertValue(
                                Map.of("count", 0), Map.class) : Map.of()
        ));
    }

    /**
     * Broadcast a notification to all SSE clients.
     */
    public void broadcast(String event, Object data) {
        try {
            String json = server.getMapper().writeValueAsString(data);
            for (SseEmitter emitter : sseClients) {
                try {
                    emitter.send(SseEmitter.event().name(event).data(json));
                } catch (IOException e) {
                    sseClients.remove(emitter);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to broadcast MCP notification", e);
        }
    }
}
