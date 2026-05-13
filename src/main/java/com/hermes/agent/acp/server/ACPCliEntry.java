package com.hermes.agent.acp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hermes.agent.acp.auth.ACPAuthProvider;
import com.hermes.agent.acp.auth.ACPPermissionBridge;
import com.hermes.agent.acp.model.*;
import com.hermes.agent.acp.session.ACPSession;
import com.hermes.agent.acp.session.ACPSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * ACP CLI Entry Point - JSON-RPC message loop over stdio.
 *
 * <p>Reads JSON-RPC 2.0 requests from stdin, delegates to
 * {@link HermesACPAgent} for actual protocol handling, and writes
 * responses/notifications back to stdout.
 *
 * <p>This is the main entry point for the ACP server when launched as an
 * editor subprocess (e.g. by Claude Code, Cursor, or any ACP-compatible IDE).
 * It replaces the transport layer with direct stdio I/O.
 *
 * <p>Reference: Python acp_adapter/entry.py
 */
public class ACPCliEntry {

    private static final Logger log = LoggerFactory.getLogger(ACPCliEntry.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HermesACPAgent agent;
    private final ACPSessionManager sessionManager;
    private final ACPPermissionBridge permissionBridge;
    private final SlashCommandHandler slashCommandHandler;

    /** Shutdown flag */
    private volatile boolean shuttingDown = false;

    public ACPCliEntry(HermesACPAgent agent,
                       ACPSessionManager sessionManager,
                       SlashCommandHandler slashCommandHandler) {
        this.agent = agent;
        this.sessionManager = sessionManager;
        this.slashCommandHandler = slashCommandHandler;
        this.permissionBridge = new ACPPermissionBridge();

        // Install a stdout-based transport so HermesACPAgent sends updates here
        agent.setTransport(new StdioTransport());
    }

    // ---- Main loop -----------------------------------------------------------

    /**
     * Run the JSON-RPC message loop. Blocks until stdin is closed or
     * a shutdown signal is received.
     */
    public void run() {
        log.info("ACP CLI entry starting (protocol version {})", ACPProtocol.PROTOCOL_VERSION);

        sendNotification("notifications/startup", mapper.createObjectNode()
                .put("protocolVersion", ACPProtocol.PROTOCOL_VERSION));

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            String line;
            while (!shuttingDown && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    JsonNode request = mapper.readTree(line);
                    handleRequest(request);
                } catch (Exception e) {
                    log.error("Failed to parse/handle request: {}", line, e);
                    sendError(null, -32700, "Parse error", null);
                }
            }
        } catch (Exception e) {
            if (!shuttingDown) {
                log.error("ACP CLI entry loop error", e);
            }
        } finally {
            log.info("ACP CLI entry shutting down");
            cleanup();
        }
    }

    /**
     * Signal the message loop to stop.
     */
    public void shutdown() {
        shuttingDown = true;
    }

    // ---- Request dispatching (delegates to HermesACPAgent) -------------------

    private void handleRequest(JsonNode request) {
        if (!request.has("jsonrpc") || !"2.0".equals(request.get("jsonrpc").asText())) {
            sendError(getId(request), -32600, ACPProtocol.JSONRPC_INVALID_REQUEST, null);
            return;
        }

        String method = request.has("method") ? request.get("method").asText() : null;
        JsonNode id = getId(request);

        log.debug("ACP request: method={} id={}", method, id);

        // Convert JSON-RPC request into an ACPMessage and delegate to agent
        ACPMessage message = ACPMessage.fromJson(request);

        try {
            Object result = agent.dispatch(message);

            // For cancel, send acknowledgment separately
            if (ACPProtocol.METHOD_CANCEL.equals(method)) {
                if (id != null) {
                    sendResult(id, mapper.createObjectNode().put("status", "cancelled"));
                }
                return;
            }

            if (id != null && result != null) {
                sendResult(id, result);
            }
        } catch (IllegalArgumentException e) {
            log.warn("Invalid params for {}: {}", method, e.getMessage());
            sendError(id, -32602, ACPProtocol.JSONRPC_INVALID_PARAMS, e.getMessage());
        } catch (Exception e) {
            log.error("Error handling {}: {}", method, e.getMessage(), e);
            sendError(id, -32603, ACPProtocol.JSONRPC_INTERNAL_ERROR, e.getMessage());
        }
    }

    // ---- JSON-RPC I/O --------------------------------------------------------

    private void sendResult(JsonNode id, Object result) {
        try {
            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", ACPProtocol.JSONRPC_VERSION);
            response.set("id", id);
            response.set("result", mapper.valueToTree(result));
            writeLine(response.toString());
        } catch (Exception e) {
            log.error("Failed to send result", e);
        }
    }

    private void sendError(JsonNode id, int code, String message, String data) {
        try {
            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", ACPProtocol.JSONRPC_VERSION);
            if (id != null) response.set("id", id);
            else response.putNull("id");

            ObjectNode error = mapper.createObjectNode();
            error.put("code", code);
            error.put("message", message);
            if (data != null) error.put("data", data);
            response.set("error", error);

            writeLine(response.toString());
        } catch (Exception e) {
            log.error("Failed to send error", e);
        }
    }

    private void sendNotification(String method, Object params) {
        try {
            ObjectNode notification = mapper.createObjectNode();
            notification.put("jsonrpc", ACPProtocol.JSONRPC_VERSION);
            notification.put("method", method);
            notification.set("params", mapper.valueToTree(params));
            writeLine(notification.toString());
        } catch (Exception e) {
            log.error("Failed to send notification", e);
        }
    }

    private void writeLine(String line) {
        System.out.println(line);
        System.out.flush();
    }

    private JsonNode getId(JsonNode request) {
        return request.has("id") ? request.get("id") : null;
    }

    private void cleanup() {
        agent.shutdown();
    }

    // ---- Main entry point ----------------------------------------------------

    /**
     * Static main method for JVM entry point.
     *
     * <p>Launches a minimal Spring context to wire dependencies,
     * then starts the stdio JSON-RPC message loop.
     *
     * <p>Usage: java -jar hermes-agent.jar --acp
     */
    public static void main(String[] args) {
        try {
            // Boot a Spring context to get all beans wired up
            var context = new SpringApplicationBuilder(
                    com.hermes.agent.HermesAgentApplication.class)
                    .web(WebApplicationType.NONE)   // no HTTP server for stdio mode
                    .logStartupInfo(false)
                    .run(args);

            HermesACPAgent acpAgent = context.getBean(HermesACPAgent.class);
            ACPCliEntry entry = new ACPCliEntry(
                    acpAgent,
                    acpAgent.getSessionManager(),
                    acpAgent.getSlashCommandHandler());

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                entry.shutdown();
                context.close();
            }));

            entry.run();
        } catch (Exception e) {
            System.err.println("Fatal error starting ACP CLI entry: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    // ---- Inner class: Stdio-based transport ----------------------------------

    /**
     * ACPTransport implementation that writes session updates to stdout
     * as JSON-RPC notifications.
     */
    private class StdioTransport implements ACPTransport {

        @Override
        public void sendSessionUpdate(String sessionId, Map<String, Object> update) {
            ObjectNode params = mapper.createObjectNode();
            params.put("sessionId", sessionId);
            params.set("update", mapper.valueToTree(update));
            sendNotification("session/update", params);
        }

        @Override
        public void sendResponse(Object id, Object response) {
            ObjectNode msg = mapper.createObjectNode();
            msg.put("jsonrpc", ACPProtocol.JSONRPC_VERSION);
            msg.putPOJO("id", id);
            msg.set("result", mapper.valueToTree(response));
            writeLine(msg.toString());
        }

        @Override
        public void sendError(Object id, int code, String message) {
            ObjectNode msg = mapper.createObjectNode();
            msg.put("jsonrpc", ACPProtocol.JSONRPC_VERSION);
            msg.putPOJO("id", id);
            ObjectNode err = mapper.createObjectNode();
            err.put("code", code);
            err.put("message", message);
            msg.set("error", err);
            writeLine(msg.toString());
        }

        @Override
        public boolean isConnected() {
            return !shuttingDown;
        }

        @Override
        public void close() {
            // No-op: stdout lifecycle managed by ACPCliEntry
        }
    }
}
