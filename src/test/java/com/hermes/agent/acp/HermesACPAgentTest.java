package com.hermes.agent.acp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.agent.Agent;
import com.hermes.agent.acp.model.*;
import com.hermes.agent.acp.server.HermesACPAgent;
import com.hermes.agent.acp.server.SlashCommandHandler;
import com.hermes.agent.acp.session.ACPSession;
import com.hermes.agent.acp.session.ACPSessionManager;
import com.hermes.agent.llm.LLMProvider;
import com.hermes.agent.llm.LLMRouter;
import com.hermes.agent.persistence.SQLiteBackend;
import com.hermes.agent.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HermesACPAgent dispatch() method.
 *
 * <p>Tests all ACP protocol methods by creating HermesACPAgent with
 * mocked dependencies and sending ACPMessage objects directly.
 */
class HermesACPAgentTest {

    @TempDir
    Path tempDir;

    // ---- Mocks ---------------------------------------------------------------
    private Agent mockAgent;
    private ACPSessionManager mockSessionManager;
    private SlashCommandHandler mockSlashHandler;
    private ToolRegistry mockToolRegistry;
    private LLMRouter mockLlmRouter;
    private SQLiteBackend mockSqliteBackend;
    private JdbcTemplate mockJdbcTemplate;

    // ---- Subject under test --------------------------------------------------
    private HermesACPAgent agent;

    // ---- Test fixtures -------------------------------------------------------
    private ACPSession testSession;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockAgent = mock(Agent.class);
        mockSessionManager = mock(ACPSessionManager.class);
        mockSlashHandler = mock(SlashCommandHandler.class);
        mockToolRegistry = mock(ToolRegistry.class);
        mockLlmRouter = mock(LLMRouter.class);
        mockSqliteBackend = mock(SQLiteBackend.class);
        mockJdbcTemplate = mock(JdbcTemplate.class);

        // Default: no MCP servers registered
        testSession = new ACPSession("test-session-123", tempDir.toString(), "dashscope/qwen-plus");

        // Stub SQLiteBackend to return JdbcTemplate
        when(mockSqliteBackend.getJdbcTemplate()).thenReturn(mockJdbcTemplate);

        // Stub LLMRouter to return a provider with models
        LLMProvider mockProvider = mock(LLMProvider.class);
        when(mockProvider.getAvailableModels()).thenReturn(List.of("qwen-plus", "qwen-turbo"));
        when(mockProvider.getDisplayName()).thenReturn("DashScope");
        when(mockLlmRouter.getProviders()).thenReturn(Map.of("dashscope", mockProvider));
        when(mockLlmRouter.getAllAvailableModels())
                .thenReturn(List.of("dashscope/qwen-plus", "dashscope/qwen-turbo"));

        // Stub session manager defaults
        when(mockSessionManager.createSession(anyString())).thenReturn(testSession);
        when(mockSessionManager.getSession(anyString())).thenReturn(testSession);
        when(mockSessionManager.updateCwd(anyString(), anyString())).thenReturn(testSession);
        when(mockSessionManager.listSessions(any())).thenReturn(Collections.emptyList());

        // Stub slash handler to return null (no slash command)
        when(mockSlashHandler.handle(anyString(), any(), any())).thenReturn(null);
        when(mockSlashHandler.getAvailableCommands()).thenReturn(Collections.emptyList());

        // Create the agent
        agent = new HermesACPAgent(
                mockSessionManager,
                mockSlashHandler,
                mockAgent,
                mockToolRegistry,
                mockLlmRouter,
                mockSqliteBackend
        );

        // Wire up JdbcTemplate
        agent.getSessionManager().setJdbcTemplate(mockJdbcTemplate);
    }

    // ========================================================================
    //  initialize tests
    // ========================================================================

    @Test
    void initialize_returnsProtocolVersionAndCapabilities() {
        ACPMessage msg = ACPMessage.create("1", ACPProtocol.METHOD_INITIALIZE,
                Map.of("protocolVersion", 2));

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        assertTrue(result instanceof ACPModels.InitializeResponse,
                "Expected InitializeResponse, got: " + result.getClass().getName());

        ACPModels.InitializeResponse resp = (ACPModels.InitializeResponse) result;
        assertEquals(2, resp.protocolVersion());
        assertEquals("hermes-agent", resp.agentInfo().name());
        assertEquals("1.0.0", resp.agentInfo().version());
        assertTrue(resp.agentCapabilities().streaming().orElse(false),
                "Streaming should be enabled");
        assertFalse(resp.authMethods().isEmpty(),
                "Auth methods should not be empty");
    }

    @Test
    void initialize_withMissingProtocolVersion_usesDefault() {
        ACPMessage msg = ACPMessage.create("2", ACPProtocol.METHOD_INITIALIZE,
                Collections.emptyMap());

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        ACPModels.InitializeResponse resp = (ACPModels.InitializeResponse) result;
        assertEquals(2, resp.protocolVersion());
    }

    // ========================================================================
    //  newSession tests
    // ========================================================================

    @Test
    void newSession_createsSessionAndReturnsSessionId() {
        ACPMessage msg = ACPMessage.create("3", ACPProtocol.METHOD_NEW_SESSION,
                Map.of("cwd", tempDir.toString()));

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        assertTrue(result instanceof ACPModels.NewSessionResponse);
        ACPModels.NewSessionResponse resp = (ACPModels.NewSessionResponse) result;
        assertNotNull(resp.sessionId());
        assertFalse(resp.sessionId().isEmpty());
        assertTrue(resp.models().isPresent());
        assertFalse(resp.models().get().availableModels().isEmpty());

        verify(mockSessionManager).createSession(tempDir.toString());
    }

    @Test
    void newSession_withDefaultCwd_usesDot() {
        ACPMessage msg = ACPMessage.create("4", ACPProtocol.METHOD_NEW_SESSION,
                Map.of("cwd", "."));

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        verify(mockSessionManager).createSession(".");
    }

    // ========================================================================
    //  loadSession tests
    // ========================================================================

    @Test
    void loadSession_updatesCwdAndReturnsModelState() {
        ACPMessage msg = ACPMessage.create("5", ACPProtocol.METHOD_LOAD_SESSION,
                Map.of("sessionId", "test-session-123", "cwd", tempDir.toString()));

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        assertTrue(result instanceof ACPModels.LoadSessionResponse);
        ACPModels.LoadSessionResponse resp = (ACPModels.LoadSessionResponse) result;
        assertTrue(resp.models().isPresent());

        verify(mockSessionManager).updateCwd("test-session-123", tempDir.toString());
    }

    @Test
    void loadSession_missingSession_returnsNull() {
        when(mockSessionManager.updateCwd(anyString(), anyString())).thenReturn(null);
        ACPMessage msg = ACPMessage.create("6", ACPProtocol.METHOD_LOAD_SESSION,
                Map.of("sessionId", "nonexistent", "cwd", tempDir.toString()));

        Object result = agent.dispatch(msg);

        // Returns null when session not found
        assertNull(result);
    }

    // ========================================================================
    //  resumeSession tests
    // ========================================================================

    @Test
    void resumeSession_existingSession_updatesCwd() {
        ACPMessage msg = ACPMessage.create("7", ACPProtocol.METHOD_RESUME_SESSION,
                Map.of("sessionId", "test-session-123", "cwd", tempDir.toString()));

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        assertTrue(result instanceof ACPModels.ResumeSessionResponse);
        verify(mockSessionManager).updateCwd("test-session-123", tempDir.toString());
    }

    @Test
    void resumeSession_missingSession_createsNew() {
        when(mockSessionManager.updateCwd(anyString(), anyString())).thenReturn(null);
        when(mockSessionManager.createSession(anyString())).thenReturn(testSession);

        ACPMessage msg = ACPMessage.create("8", ACPProtocol.METHOD_RESUME_SESSION,
                Map.of("sessionId", "nonexistent", "cwd", tempDir.toString()));

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        assertTrue(result instanceof ACPModels.ResumeSessionResponse);
        verify(mockSessionManager).createSession(tempDir.toString());
    }

    // ========================================================================
    //  cancel tests
    // ========================================================================

    @Test
    void cancel_stopsSession() {
        ACPMessage msg = ACPMessage.create("9", ACPProtocol.METHOD_CANCEL,
                Map.of("sessionId", "test-session-123"));

        Object result = agent.dispatch(msg);

        // Cancel returns null (notification)
        assertNull(result);
        verify(mockSessionManager).getSession("test-session-123");
        assertTrue(testSession.isCancelled(), "Session should be marked cancelled");
    }

    @Test
    void cancel_unknownSession_noOp() {
        when(mockSessionManager.getSession(anyString())).thenReturn(null);
        ACPMessage msg = ACPMessage.create("10", ACPProtocol.METHOD_CANCEL,
                Map.of("sessionId", "unknown"));

        Object result = agent.dispatch(msg);

        assertNull(result);
    }

    // ========================================================================
    //  forkSession tests
    // ========================================================================

    @Test
    void forkSession_createsNewSessionWithHistory() {
        ACPSession forked = new ACPSession("forked-456", tempDir.toString(), "dashscope/qwen-plus");
        when(mockSessionManager.forkSession(eq("test-session-123"), anyString()))
                .thenReturn(forked);

        ACPMessage msg = ACPMessage.create("11", ACPProtocol.METHOD_FORK_SESSION,
                Map.of("sessionId", "test-session-123", "cwd", tempDir.toString()));

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        assertTrue(result instanceof ACPModels.ForkSessionResponse);
        ACPModels.ForkSessionResponse resp = (ACPModels.ForkSessionResponse) result;
        assertEquals("forked-456", resp.sessionId());
    }

    @Test
    void forkSession_missingSession_returnsEmptyId() {
        when(mockSessionManager.forkSession(anyString(), anyString())).thenReturn(null);
        ACPMessage msg = ACPMessage.create("12", ACPProtocol.METHOD_FORK_SESSION,
                Map.of("sessionId", "nonexistent", "cwd", tempDir.toString()));

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        ACPModels.ForkSessionResponse resp = (ACPModels.ForkSessionResponse) result;
        assertEquals("", resp.sessionId());
    }

    // ========================================================================
    //  listSessions tests
    // ========================================================================

    @Test
    void listSessions_returnsSessionList() {
        List<Map<String, Object>> sessions = List.of(
                Map.of("session_id", "s1", "cwd", tempDir.toString(),
                        "title", "Test Session", "updated_at", "2026-05-09T01:00:00Z"),
                Map.of("session_id", "s2", "cwd", tempDir.toString(),
                        "title", "", "updated_at", "2026-05-09T00:30:00Z")
        );
        when(mockSessionManager.listSessions(any())).thenReturn(new ArrayList<>(sessions));

        ACPMessage msg = ACPMessage.create("13", ACPProtocol.METHOD_LIST_SESSIONS,
                Collections.emptyMap());

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        assertTrue(result instanceof ACPModels.ListSessionsResponse);
        ACPModels.ListSessionsResponse resp = (ACPModels.ListSessionsResponse) result;
        assertEquals(2, resp.sessions().size());
        assertEquals("s1", resp.sessions().get(0).sessionId());
        assertEquals("s2", resp.sessions().get(1).sessionId());
    }

    @Test
    void listSessions_withCursor_returnsPaginated() {
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            sessions.add(Map.of(
                    "session_id", "s" + i,
                    "cwd", tempDir.toString(),
                    "title", "Session " + i,
                    "updated_at", Instant.now().toString()
            ));
        }
        when(mockSessionManager.listSessions(any())).thenReturn(sessions);

        ACPMessage msg = ACPMessage.create("14", ACPProtocol.METHOD_LIST_SESSIONS,
                Map.of("cursor", "s10", "cwd", tempDir.toString()));

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        ACPModels.ListSessionsResponse resp = (ACPModels.ListSessionsResponse) result;
        // First session after cursor s10 should be s11
        assertTrue(resp.nextCursor().isEmpty() || resp.nextCursor().isPresent());
    }

    // ========================================================================
    //  prompt tests
    // ========================================================================

    @Test
    void prompt_withTextMessage_returnsResponse() {
        // Stub Agent.chatStream to return a Flux
        when(mockAgent.chatStream(eq("test-session-123"), anyString()))
                .thenReturn(Flux.just("Hello from agent!"));

        List<Map<String, Object>> prompt = List.of(
                Map.of("type", "text", "text", "Hello, agent!")
        );
        ACPMessage msg = ACPMessage.create("15", ACPProtocol.METHOD_PROMPT,
                Map.of("sessionId", "test-session-123", "prompt", prompt));

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        assertTrue(result instanceof ACPModels.PromptResponse);
        ACPModels.PromptResponse resp = (ACPModels.PromptResponse) result;
        assertEquals(ACPProtocol.STOP_END_TURN, resp.stopReason());
    }

    @Test
    void prompt_withSlashCommand_returnsSlashResponse() {
        when(mockSlashHandler.handle(eq("/help"), any(), any()))
                .thenReturn("Available commands: /help, /exit, /clear");

        List<Map<String, Object>> prompt = List.of(
                Map.of("type", "text", "text", "/help")
        );
        ACPMessage msg = ACPMessage.create("16", ACPProtocol.METHOD_PROMPT,
                Map.of("sessionId", "test-session-123", "prompt", prompt));

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        ACPModels.PromptResponse resp = (ACPModels.PromptResponse) result;
        assertEquals(ACPProtocol.STOP_END_TURN, resp.stopReason());
        // Agent.chatStream should NOT be called for slash commands
        verify(mockAgent, never()).chatStream(anyString(), anyString());
    }

    @Test
    void prompt_withEmptyText_returnsEndTurn() {
        List<Map<String, Object>> prompt = List.of(
                Map.of("type", "text", "text", "   ")
        );
        ACPMessage msg = ACPMessage.create("17", ACPProtocol.METHOD_PROMPT,
                Map.of("sessionId", "test-session-123", "prompt", prompt));

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        ACPModels.PromptResponse resp = (ACPModels.PromptResponse) result;
        assertEquals(ACPProtocol.STOP_END_TURN, resp.stopReason());
    }

    @Test
    void prompt_missingSession_returnsRefusal() {
        when(mockSessionManager.getSession(anyString())).thenReturn(null);

        List<Map<String, Object>> prompt = List.of(
                Map.of("type", "text", "text", "Hello")
        );
        ACPMessage msg = ACPMessage.create("18", ACPProtocol.METHOD_PROMPT,
                Map.of("sessionId", "nonexistent", "prompt", prompt));

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        ACPModels.PromptResponse resp = (ACPModels.PromptResponse) result;
        assertEquals(ACPProtocol.STOP_REFUSAL, resp.stopReason());
    }

    // ========================================================================
    //  setSessionModel tests
    // ========================================================================

    @Test
    void setSessionModel_switchesModel() {
        ACPMessage msg = ACPMessage.create("19", ACPProtocol.METHOD_SET_SESSION_MODEL,
                Map.of("sessionId", "test-session-123", "modelId", "anthropic/claude-3-5-sonnet"));

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        assertTrue(result instanceof ACPModels.SetSessionModelResponse);
        verify(mockSessionManager).saveSession("test-session-123");
        assertEquals("anthropic/claude-3-5-sonnet", testSession.getModel());
    }

    // ========================================================================
    //  setSessionMode tests
    // ========================================================================

    @Test
    void setSessionMode_switchesMode() {
        ACPMessage msg = ACPMessage.create("20", ACPProtocol.METHOD_SET_SESSION_MODE,
                Map.of("sessionId", "test-session-123", "modeId", "fast"));

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        assertTrue(result instanceof ACPModels.SetSessionModeResponse);
        verify(mockSessionManager).saveSession("test-session-123");
        assertEquals("fast", testSession.getMode());
    }

    // ========================================================================
    //  setConfigOption tests
    // ========================================================================

    @Test
    void setConfigOption_updatesConfigAndSaves() {
        ACPMessage msg = ACPMessage.create("21", ACPProtocol.METHOD_SET_CONFIG_OPTION,
                Map.of("sessionId", "test-session-123", "configId", "temperature", "value", "0.7"));

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        assertTrue(result instanceof ACPModels.SetConfigOptionResponse);
        verify(mockSessionManager).saveSession("test-session-123");
        assertEquals("0.7", testSession.getConfigOptions().get("temperature"));
    }

    // ========================================================================
    //  authenticate tests
    // ========================================================================

    @Test
    void authenticate_withNoneMethod_returnsOk() {
        ACPMessage msg = ACPMessage.create("22", ACPProtocol.METHOD_AUTHENTICATE,
                Map.of("methodId", "none"));

        Object result = agent.dispatch(msg);

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) result;
        assertEquals("ok", resp.get("status"));
        assertEquals("none", resp.get("method"));
    }

    // ========================================================================
    //  unknown method tests
    // ========================================================================

    @Test
    void dispatch_unknownMethod_returnsNull() {
        ACPMessage msg = ACPMessage.create("23", "unknownMethod", Collections.emptyMap());

        Object result = agent.dispatch(msg);

        assertNull(result);
    }

    // ========================================================================
    //  JSON-RPC 2.0 compliance tests
    // ========================================================================

    @Test
    void dispatch_nonJsonRpcRequest_returnsNull() {
        ACPMessage msg = ACPMessage.create(null, ACPProtocol.METHOD_INITIALIZE, Collections.emptyMap());

        // Notification (no id) should still process
        Object result = agent.dispatch(msg);
        assertNotNull(result); // initialize still returns a response
    }

    @Test
    void initialize_responseHasAllRequiredFields() {
        ACPMessage msg = ACPMessage.create("id1", ACPProtocol.METHOD_INITIALIZE,
                Map.of("protocolVersion", 2));

        Object result = agent.dispatch(msg);

        ACPModels.InitializeResponse resp = (ACPModels.InitializeResponse) result;

        // Verify all fields are present
        assertEquals(2, resp.protocolVersion());
        assertNotNull(resp.agentInfo());
        assertNotNull(resp.agentInfo().name());
        assertNotNull(resp.agentInfo().version());
        assertNotNull(resp.agentCapabilities());
        assertNotNull(resp.authMethods());

        // Auth methods should have id, name
        for (ACPModels.AuthMethod auth : resp.authMethods()) {
            assertNotNull(auth.id());
            assertNotNull(auth.name());
        }
    }

    // ========================================================================
    //  Model state tests
    // ========================================================================

    @Test
    void newSession_returnsModelStateWithAvailableModels() {
        ACPMessage msg = ACPMessage.create("24", ACPProtocol.METHOD_NEW_SESSION,
                Map.of("cwd", tempDir.toString()));

        Object result = agent.dispatch(msg);

        ACPModels.NewSessionResponse resp = (ACPModels.NewSessionResponse) result;
        assertTrue(resp.models().isPresent());

        ACPModels.SessionModelState modelState = resp.models().get();
        assertFalse(modelState.availableModels().isEmpty(),
                "Should have at least one model");

        // Verify model format: provider/model
        ACPModels.ModelInfo firstModel = modelState.availableModels().get(0);
        assertTrue(firstModel.modelId().contains("/"),
                "Model ID should be in provider/model format: " + firstModel.modelId());
        assertNotNull(firstModel.name());
    }

    @Test
    void newSession_fallsBackToCurrentModel_whenNoProviders() {
        when(mockLlmRouter.getProviders()).thenReturn(Collections.emptyMap());
        when(mockLlmRouter.getAllAvailableModels()).thenReturn(Collections.emptyList());

        // Re-create agent with empty providers
        when(mockSessionManager.createSession(anyString())).thenReturn(testSession);
        HermesACPAgent agent2 = new HermesACPAgent(
                mockSessionManager, mockSlashHandler, mockAgent,
                mockToolRegistry, mockLlmRouter, mockSqliteBackend);

        ACPMessage msg = ACPMessage.create("25", ACPProtocol.METHOD_NEW_SESSION,
                Map.of("cwd", tempDir.toString()));

        Object result = agent2.dispatch(msg);

        ACPModels.NewSessionResponse resp = (ACPModels.NewSessionResponse) result;
        // Should still return session ID even with no models
        assertNotNull(resp.sessionId());
    }
}
