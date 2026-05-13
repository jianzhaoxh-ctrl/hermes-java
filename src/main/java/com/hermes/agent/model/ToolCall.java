package com.hermes.agent.model;

import java.time.Instant;

public class ToolCall {
    private String id;
    private String name;
    private String arguments;
    private String result;
    private Instant createdAt;

    public ToolCall() {}

    public ToolCall(String id, String name, String arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArguments() { return arguments; }
    public void setArguments(String arguments) { this.arguments = arguments; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
