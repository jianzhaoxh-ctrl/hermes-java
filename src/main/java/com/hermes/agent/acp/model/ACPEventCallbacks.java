package com.hermes.agent.acp.model;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * ACP Event Callbacks
 * 
 * Interface for bridging agent events to ACP notifications.
 * Callbacks are invoked during agent execution to send updates
 * to the ACP client (editor/IDE).
 * 
 * Reference: Python acp_adapter/events.py callback factories
 */
public class ACPEventCallbacks {

    /** Thinking callback - invoked as agent generates thoughts */
    private Consumer<String> thinkingCallback;
    
    /** Message callback - invoked as agent streams response text */
    private Consumer<String> messageCallback;
    
    /** Step callback - invoked after each agent step with tool results */
    private BiConsumer<Integer, java.util.List<Map<String, Object>>> stepCallback;
    
    /** Tool progress callback - invoked for tool lifecycle events */
    private TriConsumer<String, String, Map<String, Object>> toolProgressCallback;
    
    /** Approval callback - invoked for permission requests */
    private BiConsumer<String, String> approvalCallback;

    public ACPEventCallbacks() {}

    // ---- Setters ----

    public void setThinkingCallback(Consumer<String> thinkingCallback) {
        this.thinkingCallback = thinkingCallback;
    }

    public void setMessageCallback(Consumer<String> messageCallback) {
        this.messageCallback = messageCallback;
    }

    public void setStepCallback(BiConsumer<Integer, java.util.List<Map<String, Object>>> stepCallback) {
        this.stepCallback = stepCallback;
    }

    public void setToolProgressCallback(TriConsumer<String, String, Map<String, Object>> toolProgressCallback) {
        this.toolProgressCallback = toolProgressCallback;
    }

    public void setApprovalCallback(BiConsumer<String, String> approvalCallback) {
        this.approvalCallback = approvalCallback;
    }

    // ---- Invocation methods ----

    public void onThinking(String text) {
        if (thinkingCallback != null && text != null && !text.isEmpty()) {
            thinkingCallback.accept(text);
        }
    }

    public void onMessage(String text) {
        if (messageCallback != null && text != null && !text.isEmpty()) {
            messageCallback.accept(text);
        }
    }

    public void onStep(int apiCallCount, java.util.List<Map<String, Object>> prevTools) {
        if (stepCallback != null) {
            stepCallback.accept(apiCallCount, prevTools);
        }
    }

    public void onToolStart(String eventType, String name, Map<String, Object> args) {
        if (toolProgressCallback != null) {
            toolProgressCallback.accept(eventType, name, args);
        }
    }

    public boolean requestApproval(String toolName, String details) {
        if (approvalCallback != null) {
            approvalCallback.accept(toolName, details);
            return true; // Assume approved for now
        }
        return false;
    }

    // ---- Convenience ----

    public boolean hasCallbacks() {
        return thinkingCallback != null || messageCallback != null 
                || stepCallback != null || toolProgressCallback != null;
    }

    @Override
    public String toString() {
        return String.format("ACPEventCallbacks{thinking=%s, message=%s, step=%s, toolProgress=%s}",
                thinkingCallback != null, messageCallback != null,
                stepCallback != null, toolProgressCallback != null);
    }
    
    /**
     * Simple tri-consumer for tool progress events
     */
    @FunctionalInterface
    public interface TriConsumer<T1, T2, T3> {
        void accept(T1 t1, T2 t2, T3 t3);
    }
}