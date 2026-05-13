package com.hermes.agent.acp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * STDIO transport for ACP — reads JSON-RPC from stdin, writes to stdout.
 *
 * <p>ACP reserves stdout for JSON-RPC frames. Any incidental CLI/status
 * output is routed to stderr.
 *
 * <p>Reference: Python acp library stdio transport
 */
public class ACPStdioTransport implements ACPTransport {

    private static final Logger log = LoggerFactory.getLogger(ACPStdioTransport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PrintStream out;
    private final AtomicBoolean connected = new AtomicBoolean(true);

    public ACPStdioTransport() {
        this.out = System.out;
    }

    public ACPStdioTransport(PrintStream out) {
        this.out = out;
    }

    /**
     * Read a single JSON-RPC line from stdin.
     * Blocks until a line is available.
     *
     * @return the raw JSON string, or null on EOF
     */
    public String readLine() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            return reader.readLine();
        } catch (Exception e) {
            log.error("Error reading from stdin", e);
            connected.set(false);
            return null;
        }
    }

    @Override
    public void sendSessionUpdate(String sessionId, Map<String, Object> update) {
        if (!connected.get()) return;
        try {
            Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("jsonrpc", "2.0");
            envelope.put("method", "sessionUpdate");
            envelope.put("params", Map.of("session_id", sessionId, "update", update));
            writeJson(envelope);
        } catch (Exception e) {
            log.debug("Failed to send session update", e);
        }
    }

    @Override
    public void sendResponse(Object id, Object response) {
        if (!connected.get()) return;
        try {
            Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("jsonrpc", "2.0");
            if (id != null) envelope.put("id", id);
            envelope.put("result", response);
            writeJson(envelope);
        } catch (Exception e) {
            log.error("Failed to send response", e);
        }
    }

    @Override
    public void sendError(Object id, int code, String message) {
        if (!connected.get()) return;
        try {
            Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("jsonrpc", "2.0");
            if (id != null) envelope.put("id", id);
            envelope.put("error", Map.of("code", code, "message", message));
            writeJson(envelope);
        } catch (Exception e) {
            log.error("Failed to send error", e);
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void close() {
        connected.set(false);
    }

    private synchronized void writeJson(Object obj) {
        try {
            out.println(MAPPER.writeValueAsString(obj));
            out.flush();
        } catch (Exception e) {
            log.error("Failed to write JSON to stdout", e);
            connected.set(false);
        }
    }
}
