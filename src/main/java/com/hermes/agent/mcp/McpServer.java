package com.hermes.agent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.hermes.agent.mcp.model.McpMessages;
import com.hermes.agent.mcp.model.McpMessages.*;
import com.hermes.agent.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Core MCP (Model Context Protocol) server.
 *
 * Handles JSON-RPC 2.0 message dispatch: initialize, tools/list, tools/call, ping.
 * Bridges to the existing ToolRegistry so all built-in tools are exposed via MCP.
 *
 * Transport-agnostic: both stdio and HTTP use this same handler.
 */
@Component
public class McpServer {
    private static final Logger log = LoggerFactory.getLogger(McpServer.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "hermes-java";
    private static final String SERVER_VERSION = "1.0.0";

    private final ToolRegistry toolRegistry;
    private final ObjectMapper mapper;
    private volatile boolean initialized = false;
    private volatile String clientName;

    public McpServer(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        this.mapper = new ObjectMapper()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    }

    /**
     * Process a raw JSON-RPC request line and return the JSON-RPC response string.
     */
    public String handle(String rawJson) {
        try {
            JsonRpcRequest request = mapper.readValue(rawJson, JsonRpcRequest.class);

            // Notification (no id) — fire and forget, no response
            if (request.id == null || request.id.isEmpty()) {
                handleNotification(request);
                return null;
            }

            JsonRpcResponse response = dispatch(request);
            return mapper.writeValueAsString(response);

        } catch (JsonProcessingException e) {
            // Malformed JSON — we can't reliably parse the id
            JsonRpcResponse resp = JsonRpcResponse.error(null, JsonRpcError.parseError("Parse error: " + e.getMessage()));
            try {
                return mapper.writeValueAsString(resp);
            } catch (JsonProcessingException ex) {
                return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}";
            }
        }
    }

    private JsonRpcResponse dispatch(JsonRpcRequest req) {
        try {
            return switch (req.method) {
                case "initialize" -> handleInitialize(req);
                case "initialized" -> handleInitialized(req);
                case "tools/list" -> handleToolsList(req);
                case "tools/call" -> handleToolsCall(req);
                case "ping" -> handlePing(req);
                default -> JsonRpcResponse.error(req.id,
                        JsonRpcError.methodNotFound("Unknown method: " + req.method));
            };
        } catch (Exception e) {
            log.error("MCP dispatch error for method={}", req.method, e);
            return JsonRpcResponse.error(req.id,
                    JsonRpcError.internalError("Internal error: " + e.getMessage()));
        }
    }

    // ── initialize ─────────────────────────────────────────────────────

    private JsonRpcResponse handleInitialize(JsonRpcRequest req) {
        Map<String, Object> params = req.params != null ? req.params : Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> clientInfo = (Map<String, Object>) params.get("clientInfo");
        if (clientInfo != null) {
            clientName = (String) clientInfo.get("name");
        }

        InitializeResult result = new InitializeResult(SERVER_NAME, SERVER_VERSION,
                "Hermes Java MCP Server — exposes built-in tools via the Model Context Protocol.");

        return new JsonRpcResponse(req.id, result);
    }

    // ── initialized (notification, no response) ────────────────────────

    private void handleNotification(JsonRpcRequest req) {
        if ("initialized".equals(req.method)) {
            initialized = true;
            log.info("MCP client initialized{}", clientName != null ? " (" + clientName + ")" : "");
        }
        // Other notifications are silently ignored
    }

    private JsonRpcResponse handleInitialized(JsonRpcRequest req) {
        // Should arrive as notification, but handle as request too
        initialized = true;
        return new JsonRpcResponse(req.id, Map.of());
    }

    // ── tools/list ─────────────────────────────────────────────────────

    private JsonRpcResponse handleToolsList(JsonRpcRequest req) {
        Map<String, ToolRegistry.ToolSpec> specs = toolRegistry.getAllToolSpecs();
        List<McpMessages.Tool> tools = new ArrayList<>();

        for (ToolRegistry.ToolSpec spec : specs.values()) {
            // Build JSON Schema inputSchema from the ToolSpec parameters
            Map<String, Object> schema = buildInputSchema(spec.getParameters());
            tools.add(new Tool(spec.getName(), spec.getDescription(), schema));
        }

        return new JsonRpcResponse(req.id, new ToolListResult(tools));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildInputSchema(Map<String, Object> params) {
        // ToolSpec parameters are already in JSON Schema format with
        // type/properties/required — wrap them in an "object" schema
        if (params == null || params.isEmpty()) {
            return Map.of("type", "object", "properties", Map.of());
        }

        // If params already has "type" key, it's a complete schema
        if (params.containsKey("type")) {
            return params;
        }

        // Otherwise treat as flat properties → wrap in object schema
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", params);
        return schema;
    }

    // ── tools/call ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private JsonRpcResponse handleToolsCall(JsonRpcRequest req) {
        Map<String, Object> params = req.params;
        if (params == null) {
            return JsonRpcResponse.error(req.id,
                    JsonRpcError.invalidParams("Missing params: name"));
        }

        String toolName = (String) params.get("name");
        if (toolName == null || toolName.isEmpty()) {
            return JsonRpcResponse.error(req.id,
                    JsonRpcError.invalidParams("Missing tool name"));
        }

        Map<String, Object> args = (Map<String, Object>) params.getOrDefault("arguments", Map.of());

        // Check tool exists
        if (!toolRegistry.hasTool(toolName)) {
            return JsonRpcResponse.error(req.id,
                    JsonRpcError.invalidParams("Tool not found: " + toolName));
        }

        // Execute tool (returns Mono<String>)
        String result = toolRegistry.execute(toolName, args)
                .block(java.time.Duration.ofSeconds(60));

        if (result == null || result.isEmpty()) {
            result = "(no output)";
        }

        // Check if result starts with "Error:" or "Tool not found"
        boolean isError = result.startsWith("Error:") || result.startsWith("Tool not found");

        ToolCallResult callResult = new ToolCallResult(List.of(new ContentBlock("text", result)));
        callResult.isError = isError;

        return new JsonRpcResponse(req.id, callResult);
    }

    // ── ping ───────────────────────────────────────────────────────────

    private JsonRpcResponse handlePing(JsonRpcRequest req) {
        return new JsonRpcResponse(req.id, Map.of());
    }

    // ── Accessors ──────────────────────────────────────────────────────

    public boolean isInitialized() { return initialized; }
    public String getClientName() { return clientName; }
    public ObjectMapper getMapper() { return mapper; }
}
