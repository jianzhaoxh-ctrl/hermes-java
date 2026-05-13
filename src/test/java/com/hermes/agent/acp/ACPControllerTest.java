package com.hermes.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.agent.acp.model.*;
import com.hermes.agent.acp.server.ACPController;
import com.hermes.agent.acp.server.HermesACPAgent;
import com.hermes.agent.acp.session.ACPSession;
import com.hermes.agent.acp.session.ACPSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ACPController.
 *
 * <p>Tests the controller by calling its handler methods directly,
 * bypassing the HTTP pipeline to avoid serialization issues.
 */
class ACPControllerTest {

    @TempDir
    Path tempDir;

    // ---- Mocks ---------------------------------------------------------------
    private HermesACPAgent mockAgent;
    private ACPSessionManager mockSessionManager;

    // ---- Subject under test --------------------------------------------------
    private ACPController controller;
    private ObjectMapper mapper = new ObjectMapper();

    // ---- Test fixtures -------------------------------------------------------
    private ACPSession testSession;

    @BeforeEach
    void setUp() {
        mockAgent = mock(HermesACPAgent.class);
        mockSessionManager = mock(ACPSessionManager.class);
        controller = new ACPController(mockAgent, mockSessionManager);

        testSession = new ACPSession("test-session-123", tempDir.toString(), "dashscope/qwen-plus");
    }

    // ========================================================================
    //  POST /acp — JSON-RPC endpoint
    // ========================================================================

    @Test
    void handleJsonRpc_initializeRequest_returnsResponse() {
        // Stub agent to return InitializeResponse
        ACPModels.InitializeResponse initializeResponse = new ACPModels.InitializeResponse(
                2,
                new ACPModels.Implementation("hermes-agent", "1.0.0"),
                new ACPModels.AgentCapabilities(Optional.of(true), Optional.empty()),
                List.of(new ACPModels.AuthMethod("none", "No Auth", Optional.empty()))
        );
        when(mockAgent.dispatch(any(ACPMessage.class))).thenReturn(initializeResponse);

        // Build JSON-RPC request
        Map<String, Object> request = Map.of(
                "jsonrpc", "2.0",
                "id", "1",
                "method", "initialize",
                "params", Map.of("protocolVersion", 2)
        );

        ResponseEntity<Map<String, Object>> response = controller.handleJsonRpc(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("2.0", response.getBody().get("jsonrpc"));
        assertEquals("1", response.getBody().get("id"));

        Object result = response.getBody().get("result");
        assertNotNull(result, "result should not be null");
        assertTrue(result instanceof ACPModels.InitializeResponse,
                "result should be InitializeResponse");
        ACPModels.InitializeResponse initResult = (ACPModels.InitializeResponse) result;
        assertEquals(2, initResult.protocolVersion());
        assertEquals("hermes-agent", initResult.agentInfo().name());
    }

    @Test
    void handleJsonRpc_newSessionRequest_returnsSessionId() {
        ACPModels.NewSessionResponse newSessionResponse = new ACPModels.NewSessionResponse(
                "new-session-123",
                Optional.of(new ACPModels.SessionModelState(
                        List.of(new ACPModels.ModelInfo(
                                "dashscope/qwen-plus",
                                "DashScope / qwen-plus",
                                Optional.of("current")
                        )),
                        "dashscope/qwen-plus"
                ))
        );
        when(mockAgent.dispatch(any(ACPMessage.class))).thenReturn(newSessionResponse);

        Map<String, Object> request = Map.of(
                "jsonrpc", "2.0",
                "id", "2",
                "method", "newSession",
                "params", Map.of("cwd", tempDir.toString())
        );

        ResponseEntity<Map<String, Object>> response = controller.handleJsonRpc(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        Object result = response.getBody().get("result");
        assertTrue(result instanceof ACPModels.NewSessionResponse,
                "result should be NewSessionResponse, got: " + (result != null ? result.getClass().getName() : "null"));
        ACPModels.NewSessionResponse nsr = (ACPModels.NewSessionResponse) result;
        assertEquals("new-session-123", nsr.sessionId());
        assertTrue(nsr.models().isPresent());
    }

    @Test
    void handleJsonRpc_promptRequest_returnsPromptResponse() {
        ACPModels.PromptResponse promptResponse = new ACPModels.PromptResponse(
                ACPProtocol.STOP_END_TURN,
                Optional.of(new ACPModels.Usage(100, 50, 150, Optional.empty(), Optional.empty())),
                Optional.empty()
        );
        when(mockAgent.dispatch(any(ACPMessage.class))).thenReturn(promptResponse);

        Map<String, Object> request = Map.of(
                "jsonrpc", "2.0",
                "id", "3",
                "method", "prompt",
                "params", Map.of(
                        "sessionId", "test-session",
                        "prompt", List.of(Map.of("type", "text", "text", "Hello"))
                )
        );

        ResponseEntity<Map<String, Object>> response = controller.handleJsonRpc(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Object result = response.getBody().get("result");
        assertTrue(result instanceof ACPModels.PromptResponse);
        ACPModels.PromptResponse pr = (ACPModels.PromptResponse) result;
        assertEquals(ACPProtocol.STOP_END_TURN, pr.stopReason());
    }

    @Test
    void handleJsonRpc_missingMethod_returnsBadRequest() {
        Map<String, Object> request = Map.of(
                "jsonrpc", "2.0",
                "id", "4"
                // missing "method" field
        );

        ResponseEntity<Map<String, Object>> response = controller.handleJsonRpc(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("2.0", body.get("jsonrpc"));
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertEquals(-32600, error.get("code"));
    }

    @Test
    void handleJsonRpc_invalidJsonrpcVersion_returnsBadRequest() {
        Map<String, Object> request = Map.of(
                "jsonrpc", "1.0",
                "id", "5",
                "method", "initialize"
        );

        ResponseEntity<Map<String, Object>> response = controller.handleJsonRpc(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertEquals(-32600, error.get("code"));
    }

    @Test
    void handleJsonRpc_unknownMethod_returnsOkWithNullResult() {
        when(mockAgent.dispatch(any(ACPMessage.class))).thenReturn(null);

        Map<String, Object> request = Map.of(
                "jsonrpc", "2.0",
                "id", "6",
                "method", "unknownMethod",
                "params", Collections.emptyMap()
        );

        ResponseEntity<Map<String, Object>> response = controller.handleJsonRpc(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNull(response.getBody().get("result"));
    }

    // ========================================================================
    //  GET /acp/health — Health check
    // ========================================================================

    @Test
    void health_returnsStatusAndProtocolVersion() {
        ResponseEntity<Map<String, Object>> response = controller.health();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("ok", body.get("status"));
        assertEquals(2, body.get("protocolVersion"));
        assertNotNull(body.get("sseConnections"));
    }

    // ========================================================================
    //  GET /acp/sessions — List sessions
    // ========================================================================

    @Test
    void listSessions_returnsAllSessions() {
        List<Map<String, Object>> sessions = new ArrayList<>();
        sessions.add(new LinkedHashMap<>(Map.of(
                "session_id", "s1",
                "cwd", tempDir.toString(),
                "title", "Test Session",
                "updated_at", "2026-05-09T01:00:00Z"
        )));
        sessions.add(new LinkedHashMap<>(Map.of(
                "session_id", "s2",
                "cwd", tempDir.toString(),
                "title", "",
                "updated_at", "2026-05-09T00:30:00Z"
        )));

        when(mockSessionManager.listSessions(null)).thenReturn(sessions);

        ResponseEntity<Map<String, Object>> response = controller.listSessions();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessionList = (List<Map<String, Object>>) body.get("sessions");
        assertEquals(2, sessionList.size());
        assertEquals("s1", sessionList.get(0).get("sessionId"));
        assertEquals("s2", sessionList.get(1).get("sessionId"));
        assertEquals(2, body.get("total"));
    }

    @Test
    void listSessions_empty_returnsEmptyArray() {
        when(mockSessionManager.listSessions(null)).thenReturn(Collections.emptyList());

        ResponseEntity<Map<String, Object>> response = controller.listSessions();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) response.getBody().get("sessions");
        assertEquals(0, sessions.size());
        assertEquals(0, response.getBody().get("total"));
    }

    @Test
    void listSessions_sortedByUpdatedAtDescending() {
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            LinkedHashMap<String, Object> session = new LinkedHashMap<>();
            session.put("session_id", "s" + i);
            session.put("cwd", tempDir.toString());
            session.put("title", "Session " + i);
            session.put("updated_at", "2026-05-0" + (9 - i) + "T00:00:00Z");
            sessions.add(session);
        }
        when(mockSessionManager.listSessions(null)).thenReturn(sessions);

        ResponseEntity<Map<String, Object>> response = controller.listSessions();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) response.getBody().get("sessions");
        assertEquals(3, result.size());
        // First session should be most recent (s0: 2026-05-09)
        assertEquals("s0", result.get(0).get("sessionId"));
    }

    // ========================================================================
    //  GET /acp/sessions/{id} — Get session details
    // ========================================================================

    @Test
    void getSession_existingSession_returnsSessionDetails() {
        // Add some history to testSession so we can verify history is returned
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", "Hello");
        testSession.addMessage(msg);

        when(mockSessionManager.getSession("test-session-123")).thenReturn(testSession);

        ResponseEntity<Map<String, Object>> response = controller.getSession("test-session-123");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("test-session-123", response.getBody().get("sessionId"));
        assertEquals(tempDir.toString(), response.getBody().get("cwd"));
        assertEquals("dashscope/qwen-plus", response.getBody().get("model"));
        // Verify history is included
        assertEquals(1, response.getBody().get("historySize"));
        assertNotNull(response.getBody().get("history"));
        // Verify config options are included
        assertNotNull(response.getBody().get("configOptions"));
    }

    @Test
    void getSession_unknownSession_returns404() {
        when(mockSessionManager.getSession("nonexistent")).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.getSession("nonexistent");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ========================================================================
    //  POST /acp/sessions/{id}/cancel — Cancel session
    // ========================================================================

    @Test
    void cancelSession_existingSession_returnsCancelled() {
        when(mockSessionManager.getSession("test-session-123")).thenReturn(testSession);
        doNothing().when(mockAgent).cancel("test-session-123");

        ResponseEntity<Map<String, Object>> response = controller.cancelSession("test-session-123");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("cancelled", response.getBody().get("status"));
        assertEquals("test-session-123", response.getBody().get("sessionId"));
        verify(mockAgent).cancel("test-session-123");
    }

    @Test
    void health_includesSseConnectionCount() {
        ResponseEntity<Map<String, Object>> response = controller.health();
        assertNotNull(response.getBody().get("sseConnections"));
    }
}
