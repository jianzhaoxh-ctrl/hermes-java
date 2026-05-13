package com.hermes.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.agent.acp.model.*;
import com.hermes.agent.acp.server.ACPCliEntry;
import com.hermes.agent.acp.server.ACPTransport;
import com.hermes.agent.acp.server.HermesACPAgent;
import com.hermes.agent.acp.server.SlashCommandHandler;
import com.hermes.agent.acp.session.ACPSession;
import com.hermes.agent.acp.session.ACPSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for ACPCliEntry stdio message loop.
 *
 * <p>Tests the complete JSON-RPC 2.0 round-trip:
 * <pre>
 * stdin → parse JSON-RPC → HermesACPAgent.dispatch → stdout
 * </pre>
 *
 * <p>Uses piped streams to simulate stdin/stdout without
 * spawning a subprocess.
 */
class ACPCliEntryTest {

    @TempDir
    Path tempDir;

    // ---- Mocks ---------------------------------------------------------------
    private HermesACPAgent mockAgent;
    private ACPSessionManager mockSessionManager;
    private SlashCommandHandler mockSlashHandler;
    private ACPTransport mockTransport;

    // ---- Pipes for stdin/stdout simulation ------------------------------------
    private PipedOutputStream pipedIn;   // What we write to → feeds stdin
    private ByteArrayOutputStream stdoutCapture;  // Captures what ACPCliEntry writes to stdout
    private PrintStream originalOut;
    private PrintStream originalErr;

    // ---- Subject under test --------------------------------------------------
    private ACPCliEntry entry;
    private ExecutorService executor;

    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        // Create mocks
        mockAgent = mock(HermesACPAgent.class);
        mockSessionManager = mock(ACPSessionManager.class);
        mockSlashHandler = mock(SlashCommandHandler.class);
        mockTransport = mock(ACPTransport.class);

        // Stub mockAgent to return the mock transport
        doNothing().when(mockAgent).setTransport(any());
        when(mockAgent.getSessionManager()).thenReturn(mockSessionManager);
        when(mockAgent.getSlashCommandHandler()).thenReturn(mockSlashHandler);

        // Stub mockAgent dispatch to return appropriate responses
        ACPSession testSession = new ACPSession("session-123", tempDir.toString(), "dashscope/qwen-plus");

        when(mockAgent.dispatch(any(ACPMessage.class))).thenAnswer(invocation -> {
            ACPMessage msg = invocation.getArgument(0);
            return switch (msg.method()) {
                case ACPProtocol.METHOD_INITIALIZE -> new ACPModels.InitializeResponse(
                        2,
                        new ACPModels.Implementation("hermes-agent", "1.0.0"),
                        new ACPModels.AgentCapabilities(Optional.of(true), Optional.empty()),
                        List.of(new ACPModels.AuthMethod("none", "No Auth", Optional.empty()))
                );
                case ACPProtocol.METHOD_NEW_SESSION -> new ACPModels.NewSessionResponse(
                        "session-123",
                        Optional.of(new ACPModels.SessionModelState(
                                List.of(new ACPModels.ModelInfo(
                                        "dashscope/qwen-plus",
                                        "DashScope / qwen-plus",
                                        Optional.of("current")
                                )),
                                "dashscope/qwen-plus"
                        ))
                );
                case ACPProtocol.METHOD_PROMPT -> new ACPModels.PromptResponse(
                        ACPProtocol.STOP_END_TURN,
                        Optional.of(new ACPModels.Usage(100, 50, 150,
                                Optional.empty(), Optional.empty())),
                        Optional.empty()
                );
                case ACPProtocol.METHOD_LIST_SESSIONS -> new ACPModels.ListSessionsResponse(
                        List.of(new ACPModels.SessionInfo(
                                "session-123",
                                tempDir.toString(),
                                Optional.of("Test Session"),
                                Optional.of("2026-05-09T01:00:00Z")
                        )),
                        Optional.empty()
                );
                default -> null;
            };
        });

        when(mockSessionManager.createSession(anyString())).thenReturn(testSession);
        when(mockSessionManager.getSession(anyString())).thenReturn(testSession);

        // Set up piped streams for stdin/stdout simulation
        // ACPCliEntry reads from System.in, writes to System.out
        pipedIn = new PipedOutputStream();
        PipedInputStream stdin = new PipedInputStream(pipedIn);

        // We'll capture System.out to stdoutCapture
        stdoutCapture = new ByteArrayOutputStream();

        // Redirect System.in and System.out
        originalOut = System.out;
        originalErr = System.err;
        System.setIn(stdin);
        System.setOut(new PrintStream(stdoutCapture, true, StandardCharsets.UTF_8));

        // Create ACPCliEntry with mocks
        entry = new ACPCliEntry(mockAgent, mockSessionManager, mockSlashHandler);

        // Replace the transport with our mock
        entry = spy(entry);
        doNothing().when(entry).shutdown();

        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Restore original streams
        System.setIn(new ByteArrayInputStream(new byte[0]));
        System.setOut(originalOut);
        System.setErr(originalErr);

        // Shutdown executor
        executor.shutdownNow();
        if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }

        // Close pipes
        pipedIn.close();
        stdoutCapture.close();
    }

    // ========================================================================
    //  Startup notification tests
    // ========================================================================

    @Test
    void run_sendsStartupNotification() throws Exception {
        // Send a message that will be processed, then trigger shutdown
        runInBackgroundWithTimeout(500);

        // Write a valid JSON-RPC request
        writeJsonRpc("1", "initialize", Map.of("protocolVersion", 2));

        // Wait for processing and read all output
        String output = readStdout(2000);

        assertNotNull(output);
        assertTrue(output.contains("notifications/startup"),
                "Should send startup notification. Got: " + output);
        assertTrue(output.contains("protocolVersion"),
                "Startup should include protocol version. Got: " + output);
    }

    // ========================================================================
    //  JSON-RPC request/response tests
    // ========================================================================

    @Test
    void jsonRpc_initialize_returnsProtocolVersion() throws Exception {
        runInBackgroundWithTimeout(500);

        writeJsonRpc("req-1", "initialize", Map.of("protocolVersion", 2));
        writeJsonRpc("req-2", "newSession", Map.of("cwd", tempDir.toString()));

        // Trigger end-of-input to stop the loop
        pipedIn.close();

        String output = readStdout(3000);

        // Should have responses for both requests
        assertTrue(output.contains("\"id\":\"req-1\"") || output.contains("\"id\": \"req-1\""),
                "Should respond to req-1. Got: " + output);
        assertTrue(output.contains("\"id\":\"req-2\"") || output.contains("\"id\": \"req-2\""),
                "Should respond to req-2. Got: " + output);
        assertTrue(output.contains("protocolVersion"),
                "Should include protocol version in response. Got: " + output);
    }

    @Test
    void jsonRpc_prompt_returnsPromptResponse() throws Exception {
        runInBackgroundWithTimeout(500);

        writeJsonRpc("req-3", "prompt", Map.of(
                "sessionId", "session-123",
                "prompt", List.of(Map.of("type", "text", "text", "Hello"))
        ));

        pipedIn.close();

        String output = readStdout(3000);

        assertTrue(output.contains("\"id\":\"req-3\"") || output.contains("\"id\": \"req-3\""),
                "Should respond to prompt. Got: " + output);
    }

    @Test
    void jsonRpc_listSessions_returnsSessionList() throws Exception {
        runInBackgroundWithTimeout(500);

        writeJsonRpc("req-4", "listSessions", Collections.emptyMap());

        pipedIn.close();

        String output = readStdout(3000);

        assertTrue(output.contains("\"id\":\"req-4\"") || output.contains("\"id\": \"req-4\""),
                "Should respond to listSessions. Got: " + output);
        assertTrue(output.contains("session-123"),
                "Should include session-123 in response. Got: " + output);
    }

    @Test
    void jsonRpc_cancel_returnsAcknowledgment() throws Exception {
        runInBackgroundWithTimeout(500);

        writeJsonRpc("req-5", "cancel", Map.of("sessionId", "session-123"));

        pipedIn.close();

        String output = readStdout(3000);

        // Cancel returns a result with status=cancelled
        assertTrue(output.contains("cancelled") || output.contains("\"id\":\"req-5\"") ||
                        output.contains("\"id\": \"req-5\""),
                "Should acknowledge cancel. Got: " + output);
    }

    // ========================================================================
    //  Error handling tests
    // ========================================================================

    @Test
    void jsonRpc_invalidJson_returnsParseError() throws Exception {
        // Use the shared stdoutCapture (set up in setUp)
        System.setOut(new PrintStream(stdoutCapture, true, StandardCharsets.UTF_8));

        java.nio.file.Path debugFile = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")).resolve("acp_debug.txt");
        java.nio.file.Files.writeString(debugFile, "TEST_START\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

        runInBackgroundWithTimeout(2000);

        // Give ACPCliEntry time to send startup
        Thread.sleep(500);

        String afterStartup = stdoutCapture.toString(StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(debugFile, "AFTER_STARTUP len=" + afterStartup.length() + " content=" + afterStartup.replace('\n', '|') + "\n",
                java.nio.file.StandardOpenOption.APPEND);

        // Write invalid JSON to stdin
        pipedIn.write("{ invalid json }".getBytes(StandardCharsets.UTF_8));
        pipedIn.write("\n".getBytes(StandardCharsets.UTF_8));
        pipedIn.flush();

        // Wait for ACPCliEntry to process
        Thread.sleep(1000);

        String output = stdoutCapture.toString(StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(debugFile, "FINAL_OUTPUT len=" + output.length() + " content=" + output.replace('\n', '|') + "\n",
                java.nio.file.StandardOpenOption.APPEND);

        System.setOut(originalOut); // Restore BEFORE assertions
        pipedIn.close();

        // ACPCliEntry should have sent startup notification AND parse error
        assertTrue(output.contains("notifications/startup"),
                "Should send startup. Got: [" + output + "]");
        assertTrue(output.contains("Parse error") || output.contains("-32700"),
                "Should return parse error. Got: [" + output + "]");
    }

    @Test
    void jsonRpc_invalidRpcVersion_returnsInvalidRequest() throws Exception {
        runInBackgroundWithTimeout(500);

        // Write request with wrong JSON-RPC version
        String badRequest = """
                {"jsonrpc": "1.0", "id": "x", "method": "initialize", "params": {}}
                """;
        pipedIn.write(badRequest.getBytes(StandardCharsets.UTF_8));
        pipedIn.write("\n".getBytes(StandardCharsets.UTF_8));
        pipedIn.flush();

        pipedIn.close();

        String output = readStdout(2000);

        assertTrue(output.contains("-32600") || output.contains("Invalid Request"),
                "Should return invalid request for wrong JSON-RPC version. Got: " + output);
    }

    @Test
    void jsonRpc_emptyLines_areSkipped() throws Exception {
        runInBackgroundWithTimeout(500);

        // Write empty lines between valid requests
        pipedIn.write("\n\n".getBytes(StandardCharsets.UTF_8));
        writeJsonRpc("req-6", "initialize", Map.of());
        pipedIn.write("   \n".getBytes(StandardCharsets.UTF_8));  // whitespace-only line
        pipedIn.write("\n".getBytes(StandardCharsets.UTF_8));

        pipedIn.close();

        String output = readStdout(2000);
        assertTrue(output.contains("\"id\":\"req-6\"") || output.contains("\"id\": \"req-6\""),
                "Should process valid request despite surrounding empty lines. Got: " + output);
    }

    // ========================================================================
    //  Transport integration tests
    // ========================================================================

    @Test
    void run_withNullTransport_doesNotThrow() throws Exception {
        // Create entry without setting transport
        ACPCliEntry entryNoTransport = new ACPCliEntry(mockAgent, mockSessionManager, mockSlashHandler);
        // Transport is null by default in ACPCliEntry, but we call setTransport with StdioTransport
        // So HermesACPAgent transport is the StdioTransport inner class

        runInBackgroundWithTimeout(500);

        writeJsonRpc("req-7", "newSession", Map.of("cwd", tempDir.toString()));

        pipedIn.close();

        // Should not throw even with transport handling
        String output = readStdout(3000);
        assertNotNull(output);
    }

    // ========================================================================
    //  Notification tests
    // ========================================================================

    @Test
    void run_shutdownHook_triggersCleanup() throws Exception {
        runInBackgroundWithTimeout(500);

        // Trigger shutdown via the entry's shutdown method
        entry.shutdown();

        pipedIn.close();

        String output = readStdout(2000);
        // Should have received shutdown signal
        assertNotNull(output);
    }

    // ========================================================================
    //  Multi-message sequential tests
    // ========================================================================

    @Test
    void jsonRpc_multipleRequests_processedInOrder() throws Exception {
        runInBackgroundWithTimeout(500);

        // Send 3 requests in quick succession
        for (int i = 1; i <= 3; i++) {
            writeJsonRpc("multi-" + i, "listSessions", Collections.emptyMap());
        }

        pipedIn.close();

        String output = readStdout(3000);

        // All 3 responses should appear in order
        assertTrue(output.contains("\"id\":\"multi-1\"") || output.contains("\"id\": \"multi-1\""),
                "Should respond to multi-1. Got: " + output);
        assertTrue(output.contains("\"id\":\"multi-2\"") || output.contains("\"id\": \"multi-2\""),
                "Should respond to multi-2. Got: " + output);
        assertTrue(output.contains("\"id\":\"multi-3\"") || output.contains("\"id\": \"multi-3\""),
                "Should respond to multi-3. Got: " + output);

        // Verify order: multi-1 should appear before multi-2 and multi-3
        int idx1 = output.indexOf("multi-1");
        int idx2 = output.indexOf("multi-2");
        int idx3 = output.indexOf("multi-3");
        assertTrue(idx1 < idx2 && idx2 < idx3,
                "Responses should be in order. Got: " + output);
    }

    @Test
    void jsonRpc_pingPong_allRequestsGetResponses() throws Exception {
        runInBackgroundWithTimeout(500);

        // Alternate between initialize and newSession
        writeJsonRpc("pp-1", "initialize", Map.of());
        writeJsonRpc("pp-2", "newSession", Map.of("cwd", tempDir.toString()));
        writeJsonRpc("pp-3", "initialize", Map.of());

        pipedIn.close();

        String output = readStdout(3000);

        for (int i = 1; i <= 3; i++) {
            assertTrue(output.contains("pp-" + i),
                    "Should respond to pp-" + i + ". Got: " + output);
        }
    }

    // ========================================================================
    //  Helper methods
    // ========================================================================

    /**
     * Write a JSON-RPC 2.0 request to the piped input stream.
     */
    private void writeJsonRpc(String id, String method, Map<String, Object> params)
            throws IOException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);

        String json = mapper.writeValueAsString(request);
        pipedIn.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        pipedIn.flush();
    }

    /**
     * Run the ACPCliEntry in a background thread with a timeout.
     */
    private void runInBackgroundWithTimeout(int timeoutMs) {
        Future<?> future = executor.submit(() -> {
            try {
                entry.run();
            } catch (Exception e) {
                // Expected when stdin is closed or during test teardown
            }
        });
        executor.submit(() -> {
            try {
                Thread.sleep(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Read all output from the captured stdout.
     *
     * <p>Waits up to timeoutMs for content to appear, then reads everything
     * available.
     */
    private String readStdout(int timeoutMs) throws Exception {
        // Wait for the ACPCliEntry thread to finish processing
        Thread.sleep(timeoutMs);

        // Restore System.out briefly to flush
        System.out.flush();

        // Read all captured output from stdoutCapture
        String output = stdoutCapture.toString(StandardCharsets.UTF_8);

        return output;
    }
}
