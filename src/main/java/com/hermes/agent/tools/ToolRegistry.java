package com.hermes.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, ToolSpec> toolSpecs = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @FunctionalInterface
    public interface Tool {
        Mono<String> execute(Map<String, Object> args);
    }

    /** Holds metadata about a registered tool for the LLM function-calling spec */
    public static class ToolSpec {
        private final String name;
        private final String description;
        private final Map<String, Object> parameters; // JSON Schema

        public ToolSpec(String name, String description, Map<String, Object> parameters) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public Map<String, Object> getParameters() { return parameters; }
    }

    public void register(String name, Tool tool) {
        tools.put(name, tool);
        log.info("Registered tool: {}", name);
    }

    public void register(String name, String description, Tool tool) {
        tools.put(name, tool);
        log.info("Registered tool: {} - {}", name, description);
    }

    /**
     * Register a tool with full spec for LLM function-calling.
     * The parameters should be a JSON Schema describing the tool's input.
     */
    public void register(String name, String description, Map<String, Object> parameters, Tool tool) {
        tools.put(name, tool);
        toolSpecs.put(name, new ToolSpec(name, description, parameters));
        log.info("Registered tool: {} - {}", name, description);
    }

    public Mono<String> execute(String toolName, Map<String, Object> args) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            log.warn("Tool not found: {}", toolName);
            return Mono.just("Tool not found: " + toolName);
        }
        try {
            return tool.execute(args);
        } catch (Exception e) {
            log.error("Tool execution failed: {}", e.getMessage());
            return Mono.just("Error: " + e.getMessage());
        }
    }

    public Map<String, Tool> getAllTools() {
        return new ConcurrentHashMap<>(tools);
    }

    /** Returns the full tool spec (description + parameters) for a tool */
    public ToolSpec getToolSpec(String name) {
        return toolSpecs.get(name);
    }

    /** Returns all tool specs */
    public Map<String, ToolSpec> getAllToolSpecs() {
        return new ConcurrentHashMap<>(toolSpecs);
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    public void unregister(String name) {
        tools.remove(name);
        toolSpecs.remove(name);
        log.info("Unregistered tool: {}", name);
    }
}
