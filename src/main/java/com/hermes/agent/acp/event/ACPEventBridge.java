package com.hermes.agent.acp.event;

import com.hermes.agent.acp.model.ACPEventCallbacks;
import com.hermes.agent.acp.server.ACPTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Bridge between Agent events and ACP client notifications.
 *
 * <p>Creates callback functions that push ACP session updates to the client
 * via the transport layer. Uses a dedicated executor to avoid blocking the
 * agent's worker threads.
 *
 * <p>Reference: Python acp_adapter/events.py callback factories
 */
public class ACPEventBridge {

    private static final Logger log = LoggerFactory.getLogger(ACPEventBridge.class);

    private final ACPTransport transport;
    private final String sessionId;
    private final ExecutorService sendExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "acp-event-bridge");
                t.setDaemon(true);
                return t;
            });

    public ACPEventBridge(ACPTransport transport, String sessionId) {
        this.transport = transport;
        this.sessionId = sessionId;
    }

    /**
     * Create ACPEventCallbacks wired to the transport.
     */
    public ACPEventCallbacks createCallbacks() {
        ACPEventCallbacks callbacks = new ACPEventCallbacks();

        callbacks.setThinkingCallback(text -> sendUpdate(Map.of(
                "session_update", "agent.thought.text",
                "content", Map.of("text", text)
        )));

        callbacks.setMessageCallback(text -> sendUpdate(Map.of(
                "session_update", "agent.message.text",
                "content", Map.of("text", text)
        )));

        callbacks.setToolProgressCallback((eventType, name, args) -> sendUpdate(Map.of(
                "session_update", "tool.call.start",
                "tool_name", name,
                "event_type", eventType,
                "args", args != null ? args : Map.of()
        )));

        callbacks.setStepCallback((apiCallCount, prevTools) -> sendUpdate(Map.of(
                "session_update", "step.complete",
                "api_call_count", apiCallCount
        )));

        callbacks.setApprovalCallback((toolName, details) -> {
            log.info("Approval requested for tool {} in session {}", toolName, sessionId);
            // Approval is handled by the transport's request_permission mechanism
        });

        return callbacks;
    }

    /**
     * Fire-and-forget an ACP session update.
     */
    private void sendUpdate(Map<String, Object> update) {
        sendExecutor.submit(() -> {
            try {
                transport.sendSessionUpdate(sessionId, update);
            } catch (Exception e) {
                log.debug("Failed to send ACP event update", e);
            }
        });
    }

    /**
     * Shutdown the event bridge executor.
     */
    public void shutdown() {
        sendExecutor.shutdownNow();
    }
}
