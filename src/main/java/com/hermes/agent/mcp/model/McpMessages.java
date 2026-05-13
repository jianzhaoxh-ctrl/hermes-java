package com.hermes.agent.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * JSON-RPC 2.0 / MCP protocol message types.
 *
 * MCP spec: https://spec.modelcontextprotocol.io/specification/2024-11-05/
 */
public class McpMessages {

    // ── JSON-RPC Request ───────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JsonRpcRequest {
        public String jsonrpc = "2.0";
        public String id;          // null for notifications
        public String method;
        public Map<String, Object> params;

        public JsonRpcRequest() {}

        public JsonRpcRequest(String id, String method, Map<String, Object> params) {
            this.id = id;
            this.method = method;
            this.params = params;
        }

        public static JsonRpcRequest notification(String method, Map<String, Object> params) {
            return new JsonRpcRequest(null, method, params);
        }
    }

    // ── JSON-RPC Response ──────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JsonRpcResponse {
        public String jsonrpc = "2.0";
        public String id;
        public Object result;
        public JsonRpcError error;

        public JsonRpcResponse() {}

        public JsonRpcResponse(String id, Object result) {
            this.id = id;
            this.result = result;
        }

        public static JsonRpcResponse error(String id, JsonRpcError error) {
            JsonRpcResponse r = new JsonRpcResponse();
            r.id = id;
            r.error = error;
            return r;
        }
    }

    // ── JSON-RPC Error ─────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JsonRpcError {
        public int code;
        public String message;
        public Object data;

        public JsonRpcError() {}

        public JsonRpcError(int code, String message) {
            this.code = code;
            this.message = message;
        }

        public JsonRpcError(int code, String message, Object data) {
            this.code = code;
            this.message = message;
            this.data = data;
        }

        // Standard JSON-RPC error codes
        public static JsonRpcError parseError(String message) {
            return new JsonRpcError(-32700, message);
        }

        public static JsonRpcError invalidRequest(String message) {
            return new JsonRpcError(-32600, message);
        }

        public static JsonRpcError methodNotFound(String message) {
            return new JsonRpcError(-32601, message);
        }

        public static JsonRpcError invalidParams(String message) {
            return new JsonRpcError(-32602, message);
        }

        public static JsonRpcError internalError(String message) {
            return new JsonRpcError(-32603, message);
        }
    }

    // ── MCP initialize result ──────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InitializeResult {
        public String protocolVersion = "2024-11-05";
        public ServerCapabilities capabilities;
        public ServerInfo serverInfo;
        public String instructions;

        public InitializeResult() {}

        public InitializeResult(String serverName, String serverVersion, String instructions) {
            this.serverInfo = new ServerInfo(serverName, serverVersion);
            this.capabilities = new ServerCapabilities();
            this.instructions = instructions;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ServerCapabilities {
        public Map<String, Object> tools;
        public Map<String, Object> resources;
        public Map<String, Object> prompts;
        public Map<String, Object> logging;

        public ServerCapabilities() {
            this.tools = Map.of("listChanged", true);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ServerInfo {
        public String name;
        public String version;

        public ServerInfo() {}

        public ServerInfo(String name, String version) {
            this.name = name;
            this.version = version;
        }
    }

    // ── MCP Tool types ─────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Tool {
        public String name;
        public String description;
        public Map<String, Object> inputSchema;

        public Tool() {}

        public Tool(String name, String description, Map<String, Object> inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolListResult {
        public List<Tool> tools;
        public Object nextCursor;

        public ToolListResult() {}

        public ToolListResult(List<Tool> tools) {
            this.tools = tools;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCallResult {
        public List<ContentBlock> content;
        public boolean isError;

        public ToolCallResult() {}

        public ToolCallResult(List<ContentBlock> content) {
            this.content = content;
        }

        public static ToolCallResult textResult(String text) {
            return new ToolCallResult(List.of(new ContentBlock("text", text)));
        }

        public static ToolCallResult errorResult(String text) {
            ToolCallResult r = new ToolCallResult(List.of(new ContentBlock("text", text)));
            r.isError = true;
            return r;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContentBlock {
        public String type;  // "text", "image", "resource"
        public String text;
        public String mimeType;
        public String data;

        public ContentBlock() {}

        public ContentBlock(String type, String text) {
            this.type = type;
            this.text = text;
        }

        public static ContentBlock image(String mimeType, String base64Data) {
            ContentBlock b = new ContentBlock();
            b.type = "image";
            b.mimeType = mimeType;
            b.data = base64Data;
            return b;
        }
    }
}
