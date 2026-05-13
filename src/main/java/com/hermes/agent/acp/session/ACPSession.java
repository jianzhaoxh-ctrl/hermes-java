package com.hermes.agent.acp.session;

import com.hermes.agent.acp.model.ACPEventCallbacks;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ACP Session State
 * 
 * Tracks per-session state for an ACP-managed Hermes agent.
 * Sessions are held in-memory for fast access AND persisted to the database
 * so they survive process restarts.
 * 
 * Reference: Python acp_adapter/session.py SessionState
 */
public class ACPSession {

    private final String sessionId;
    private final AtomicBoolean cancelEvent;
    private String cwd;
    private String model;
    private List<Map<String, Object>> history;
    private Map<String, Object> configOptions;
    private String mode;
    private Instant createdAt;
    private Instant updatedAt;
    
    // Agent callbacks (set during prompt execution)
    private ACPEventCallbacks callbacks;

    public ACPSession(String sessionId, String cwd, String model) {
        this.sessionId = sessionId;
        this.cwd = cwd != null ? cwd : ".";
        this.model = model != null ? model : "";
        this.history = new java.util.ArrayList<>();
        this.cancelEvent = new AtomicBoolean(false);
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.configOptions = new java.util.HashMap<>();
    }

    // ---- Session lifecycle ----

    public String getSessionId() { return sessionId; }
    
    public String getCwd() { return cwd; }
    public void setCwd(String cwd) { 
        this.cwd = cwd != null ? cwd : "."; 
        this.updatedAt = Instant.now();
    }
    
    public String getModel() { return model; }
    public void setModel(String model) { 
        this.model = model; 
        this.updatedAt = Instant.now();
    }
    
    public List<Map<String, Object>> getHistory() { return history; }
    public void setHistory(List<Map<String, Object>> history) { 
        this.history = history != null ? history : new java.util.ArrayList<>(); 
        this.updatedAt = Instant.now();
    }
    
    public Map<String, Object> getConfigOptions() { return configOptions; }
    public void setConfigOptions(Map<String, Object> configOptions) { this.configOptions = configOptions; }
    
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = Instant.now(); }

    public ACPEventCallbacks getCallbacks() { return callbacks; }
    public void setCallbacks(ACPEventCallbacks callbacks) { this.callbacks = callbacks; }

    // ---- History management ----

    public synchronized void addMessage(Map<String, Object> message) {
        history.add(message);
        this.updatedAt = Instant.now();
    }

    public synchronized void clearHistory() {
        history.clear();
        this.updatedAt = Instant.now();
    }

    public synchronized int getHistorySize() {
        return history.size();
    }

    // ---- Cancel management ----

    public void cancel() {
        cancelEvent.set(true);
    }

    public void clearCancel() {
        cancelEvent.set(false);
    }

    public boolean isCancelled() {
        return cancelEvent.get();
    }

    // ---- Convenience methods ----

    public String getPreview() {
        for (Map<String, Object> msg : history) {
            if ("user".equals(msg.get("role"))) {
                Object content = msg.get("content");
                if (content instanceof String) {
                    String text = ((String) content).trim();
                    if (!text.isEmpty()) return text;
                }
            }
        }
        return null;
    }

    public String getTitle() {
        Object title = configOptions.get("title");
        if (title instanceof String && !((String) title).isEmpty()) {
            return (String) title;
        }
        String preview = getPreview();
        if (preview != null && !preview.isEmpty()) {
            return preview.length() > 80 ? preview.substring(0, 77) + "..." : preview;
        }
        return cwd != null && !cwd.equals(".") ? cwd.substring(cwd.lastIndexOf('/') + 1) : "New thread";
    }

    @Override
    public String toString() {
        return String.format("ACPSession{id=%s, cwd=%s, model=%s, history=%d}", 
                sessionId, cwd, model, history.size());
    }
}