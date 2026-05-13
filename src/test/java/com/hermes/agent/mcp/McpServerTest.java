package com.hermes.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.agent.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for McpServer — JSON-RPC 2.0 dispatch over MCP.
 *
 * Tests cover: initialize, tools/list, tools/call, ping, error handling,
 * notification handling, and edge cases.
 */
class McpServerTest {

    private McpServer server;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        ToolRegistry toolRegistry = new ToolRegistry();
        // Register a test tool
        toolRegistry.register("test_echo",
                "Echo back the input message",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "message", Map.of("type", "string", "description", "Message to echo")
                        ),
                        "required", java.util.List.of("message")
                ),
                args -> {
                    String msg = (String) args.getOrDefault("message", "");
                    return reactor.core.publisher.Mono.just("Echo: " + msg);
                });

        // Register a tool that returns an error
        toolRegistry.register("test_error",
                "A tool that always fails",
                Map.of("type", "object", "properties", Map.of()),
                args -> reactor.core.publisher.Mono.just("Error: something went wrong"));

        server = new McpServer(toolRegistry);
        mapper = server.getMapper();
    }

    // ── Helper to build JSON-RPC request strings ──────────────────────

    private String jsonRpcRequest(String id, String method, Map<String, Object> params) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "method", method,
                    "params", params != null ? params : Map.of()
            ));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String jsonRpcNotification(String method, Map<String, Object> params) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "method", method,
                    "params", params != null ? params : Map.of()
            ));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResponse(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ================================================================
    //  initialize
    // ================================================================

    @Nested
    @DisplayName("initialize")
    class InitializeTests {

        @Test
        @DisplayName("returns protocol version and server info")
        void returnsProtocolVersionAndServerInfo() {
            String request = jsonRpcRequest("1", "initialize", Map.of(
                    "protocolVersion", "2024-11-05",
                    "clientInfo", Map.of("name", "test-client", "version", "0.1.0")
            ));

            String responseJson = server.handle(request);
            assertNotNull(responseJson);

            Map<String, Object> resp = parseResponse(responseJson);
            assertEquals("2.0", resp.get("jsonrpc"));
            assertEquals("1", resp.get("id"));

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) resp.get("result");
            assertNotNull(result);
            assertEquals("2024-11-05", result.get("protocolVersion"));

            @SuppressWarnings("unchecked")
            Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");
            assertNotNull(serverInfo);
            assertEquals("hermes-java", serverInfo.get("name"));
            assertEquals("1.0.0", serverInfo.get("version"));
        }

        @Test
        @DisplayName("marks server as initialized after initialized notification")
        void marksInitializedAfterNotification() {
            assertFalse(server.isInitialized());

            // Send initialized notification
            server.handle(jsonRpcNotification("initialized", Map.of()));

            assertTrue(server.isInitialized());
        }

        @Test
        @DisplayName("records client name from initialize request")
        void recordsClientName() {
            server.handle(jsonRpcRequest("1", "initialize", Map.of(
                    "clientInfo", Map.of("name", "my-mcp-client", "version", "1.0.0")
            )));

            assertEquals("my-mcp-client", server.getClientName());
        }
    }

    // ================================================================
    //  tools/list
    // ================================================================

    @Nested
    @DisplayName("tools/list")
    class ToolsListTests {

        @Test
        @DisplayName("returns registered tools with schemas")
        void returnsRegisteredTools() {
            String request = jsonRpcRequest("2", "tools/list", null);
            String responseJson = server.handle(request);

            Map<String, Object> resp = parseResponse(responseJson);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) resp.get("result");
            assertNotNull(result);

            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> tools = (java.util.List<Map<String, Object>>) result.get("tools");
            assertNotNull(tools);
            assertTrue(tools.size() >= 2, "Should have at least 2 registered tools");

            // Find our test_echo tool
            Map<String, Object> echoTool = tools.stream()
                    .filter(t -> "test_echo".equals(t.get("name")))
                    .findFirst()
                    .orElse(null);
            assertNotNull(echoTool);
            assertEquals("Echo back the input message", echoTool.get("description"));

            @SuppressWarnings("unchecked")
            Map<String, Object> inputSchema = (Map<String, Object>) echoTool.get("inputSchema");
            assertNotNull(inputSchema);
        }

        @Test
        @DisplayName("returns empty list when no tools registered")
        void returnsEmptyListWhenNoTools() {
            ToolRegistry emptyRegistry = new ToolRegistry();
            McpServer emptyServer = new McpServer(emptyRegistry);

            String responseJson = emptyServer.handle(jsonRpcRequest("3", "tools/list", null));
            Map<String, Object> resp = parseResponse(responseJson);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) resp.get("result");

            @SuppressWarnings("unchecked")
            java.util.List<?> tools = (java.util.List<?>) result.get("tools");
            assertNotNull(tools);
            assertTrue(tools.isEmpty());
        }
    }

    // ================================================================
    //  tools/call
    // ================================================================

    @Nested
    @DisplayName("tools/call")
    class ToolsCallTests {

        @Test
        @DisplayName("executes tool and returns result")
        void executesToolAndReturnsResult() {
            String request = jsonRpcRequest("10", "tools/call", Map.of(
                    "name", "test_echo",
                    "arguments", Map.of("message", "hello world")
            ));

            String responseJson = server.handle(request);
            Map<String, Object> resp = parseResponse(responseJson);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) resp.get("result");
            assertNotNull(result);

            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> content = (java.util.List<Map<String, Object>>) result.get("content");
            assertNotNull(content);
            assertFalse(content.isEmpty());
            assertEquals("text", content.get(0).get("type"));
            assertTrue(content.get(0).get("text").toString().contains("hello world"));
            assertFalse((Boolean) result.get("isError"));
        }

        @Test
        @DisplayName("marks error results when tool returns error")
        void marksErrorResults() {
            String request = jsonRpcRequest("11", "tools/call", Map.of(
                    "name", "test_error",
                    "arguments", Map.of()
            ));

            String responseJson = server.handle(request);
            Map<String, Object> resp = parseResponse(responseJson);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) resp.get("result");
            assertNotNull(result);
            assertTrue((Boolean) result.get("isError"));
        }

        @Test
        @DisplayName("returns error for unknown tool")
        void returnsErrorForUnknownTool() {
            String request = jsonRpcRequest("12", "tools/call", Map.of(
                    "name", "nonexistent_tool",
                    "arguments", Map.of()
            ));

            String responseJson = server.handle(request);
            Map<String, Object> resp = parseResponse(responseJson);
            assertNotNull(resp.get("error"));

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) resp.get("error");
            assertEquals(-32602, error.get("code"));
        }

        @Test
        @DisplayName("returns error when tool name is missing")
        void returnsErrorWhenToolNameMissing() {
            String request = jsonRpcRequest("13", "tools/call", Map.of(
                    "arguments", Map.of("key", "value")
            ));

            String responseJson = server.handle(request);
            Map<String, Object> resp = parseResponse(responseJson);
            assertNotNull(resp.get("error"));
        }

        @Test
        @DisplayName("returns error when params is null")
        void returnsErrorWhenParamsNull() {
            String request = jsonRpcRequest("14", "tools/call", null);

            String responseJson = server.handle(request);
            Map<String, Object> resp = parseResponse(responseJson);
            assertNotNull(resp.get("error"));
        }

        @Test
        @DisplayName("uses empty arguments when not provided")
        void usesEmptyArgumentsWhenNotProvided() {
            // test_error tool doesn't need arguments
            String request = jsonRpcRequest("15", "tools/call", Map.of(
                    "name", "test_error"
            ));

            String responseJson = server.handle(request);
            Map<String, Object> resp = parseResponse(responseJson);
            // Should still execute (and fail), not return a params error
            assertNotNull(resp.get("result"));
        }
    }

    // ================================================================
    //  ping
    // ================================================================

    @Nested
    @DisplayName("ping")
    class PingTests {

        @Test
        @DisplayName("returns empty result")
        void returnsEmptyResult() {
            String request = jsonRpcRequest("20", "ping", null);
            String responseJson = server.handle(request);

            Map<String, Object> resp = parseResponse(responseJson);
            assertEquals("20", resp.get("id"));
            assertNotNull(resp.get("result"));
        }
    }

    // ================================================================
    //  Error handling
    // ================================================================

    @Nested
    @DisplayName("error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("returns parse error for malformed JSON")
        void returnsParseErrorForMalformedJson() {
            String responseJson = server.handle("{invalid json!!!}");
            Map<String, Object> resp = parseResponse(responseJson);
            assertNotNull(resp.get("error"));

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) resp.get("error");
            assertEquals(-32700, error.get("code"));
        }

        @Test
        @DisplayName("returns method not found for unknown methods")
        void returnsMethodNotFoundForUnknownMethods() {
            String request = jsonRpcRequest("30", "unknown_method", null);
            String responseJson = server.handle(request);

            Map<String, Object> resp = parseResponse(responseJson);
            assertNotNull(resp.get("error"));

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) resp.get("error");
            assertEquals(-32601, error.get("code"));
        }

        @Test
        @DisplayName("handles null tool name gracefully without crashing")
        void handlesNullToolNameGracefully() {
            // Build params with null name using HashMap (Map.of doesn't accept null)
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("name", null);
            params.put("arguments", Map.of());
            String request = jsonRpcRequest("31", "tools/call", params);
            
            // Server should handle this gracefully
            String responseJson = server.handle(request);
            assertNotNull(responseJson, "Should return a response, not crash");
        }

        @Test
        @DisplayName("handles empty input gracefully")
        void handlesEmptyInput() {
            // Empty string should cause a parse error
            String responseJson = server.handle("");
            assertNotNull(responseJson);
        }
    }

    // ================================================================
    //  Notifications
    // ================================================================

    @Nested
    @DisplayName("notifications")
    class NotificationTests {

        @Test
        @DisplayName("initialized notification sets initialized flag")
        void initializedNotificationSetsFlag() {
            assertFalse(server.isInitialized());
            server.handle(jsonRpcNotification("initialized", Map.of()));
            assertTrue(server.isInitialized());
        }

        @Test
        @DisplayName("unknown notification is silently ignored")
        void unknownNotificationIsIgnored() {
            // Should not throw
            assertDoesNotThrow(() -> {
                server.handle(jsonRpcNotification("unknown_notification", Map.of("foo", "bar")));
            });
        }

        @Test
        @DisplayName("returns null for notifications (no response body)")
        void returnsNullForNotifications() {
            String response = server.handle(jsonRpcNotification("initialized", Map.of()));
            assertNull(response);
        }
    }
}
