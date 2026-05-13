package com.hermes.agent.acp.server;

import java.util.Map;

/**
 * Transport interface for sending ACP session updates to clients.
 *
 * <p>Implementations handle the actual wire protocol (stdio JSON-RPC,
 * WebSocket, HTTP SSE, etc.).
 */
public interface ACPTransport {

    /**
     * Send a session update to the connected client.
     *
     * @param sessionId the session ID
     * @param update    the update payload (will be serialized to JSON)
     */
    void sendSessionUpdate(String sessionId, Map<String, Object> update);

    /**
     * Send a JSON-RPC response.
     *
     * @param id       the request ID
     * @param response the response object (will be serialized to JSON)
     */
    void sendResponse(Object id, Object response);

    /**
     * Send a JSON-RPC error response.
     */
    void sendError(Object id, int code, String message);

    /**
     * Check if the transport is connected.
     */
    boolean isConnected();

    /**
     * Close the transport.
     */
    void close();
}
