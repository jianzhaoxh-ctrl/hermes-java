package com.hermes.agent.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class Message {
    private String role;
    private String content;
    private Instant timestamp;
    private List<ToolCall> toolCalls;
    private String toolCallId;  // for tool role messages
    private Map<String, Object> metadata;

    public Message() {}

    public Message(String role, String content, Instant timestamp) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}