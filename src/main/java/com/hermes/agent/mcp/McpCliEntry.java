package com.hermes.agent.mcp;

import com.hermes.agent.mcp.transport.McpStdioTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * MCP CLI entry point — activates when the app is launched with
 * `--transport=stdio` or `--mcp-stdio` argument.
 *
 * Starts the MCP server in stdio mode, reading JSON-RPC from stdin
 * and writing responses to stdout.
 *
 * Usage:
 *   java -jar hermes-agent.jar --mcp-stdio
 *   java -jar hermes-agent.jar --transport=stdio
 */
@Component
@Order(1)
public class McpCliEntry implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(McpCliEntry.class);

    private final McpServer server;

    public McpCliEntry(McpServer server) {
        this.server = server;
    }

    @Override
    public void run(String... args) {
        boolean stdioMode = false;
        for (String arg : args) {
            if ("--mcp-stdio".equals(arg) || "--transport=stdio".equals(arg)) {
                stdioMode = true;
                break;
            }
        }

        if (!stdioMode) {
            return; // Not in stdio mode — let Spring Boot start web server normally
        }

        log.info("Starting MCP server in stdio mode");
        System.out.println("{\"jsonrpc\":\"2.0\",\"method\":\"log\",\"params\":{\"level\":\"info\",\"data\":\"MCP stdio server ready\"}}");

        McpStdioTransport transport = new McpStdioTransport(server);
        transport.start();

        log.info("MCP stdio transport finished, exiting");
        System.exit(0);
    }
}
