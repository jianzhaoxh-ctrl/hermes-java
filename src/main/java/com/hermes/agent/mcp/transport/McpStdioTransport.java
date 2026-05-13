package com.hermes.agent.mcp.transport;

import com.hermes.agent.mcp.McpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP stdio transport — reads JSON-RPC from stdin, writes responses to stdout.
 *
 * Used when the agent runs hermes-java as a subprocess with pipe-based communication.
 * Protocol: one JSON-RPC message per line, responses written to stdout.
 */
public class McpStdioTransport {
    private static final Logger log = LoggerFactory.getLogger(McpStdioTransport.class);

    private final McpServer server;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public McpStdioTransport(McpServer server) {
        this.server = server;
    }

    /**
     * Start the stdio message loop. Blocks until stdin is closed or "exit" is received.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("MCP stdio transport already running");
            return;
        }

        log.info("MCP stdio transport started");
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter writer = new PrintWriter(
                new java.io.BufferedWriter(
                        new java.io.OutputStreamWriter(System.out, StandardCharsets.UTF_8)),
                true /* autoFlush */);

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if ("exit".equalsIgnoreCase(line) || "quit".equalsIgnoreCase(line)) {
                    log.info("MCP stdio transport: exit requested");
                    break;
                }

                String response = server.handle(line);
                if (response != null && !response.isEmpty()) {
                    writer.println(response);
                    writer.flush();
                }
            }
        } catch (Exception e) {
            log.error("MCP stdio transport error", e);
        } finally {
            running.set(false);
            writer.flush();
            log.info("MCP stdio transport stopped");
        }
    }

    public void stop() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }
}
