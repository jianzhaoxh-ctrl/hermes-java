package com.hermes.agent.acp.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ACP Protocol Message
 * 
 * Core message format for the Agent Client Protocol.
 * Used for JSON-RPC communication between ACP clients and agents.
 * 
 * Reference: Python acp package schema definitions
 */
public record ACPMessage(
    String id,
    String method,
    Map<String, Object> params
) {
    
    /**
     * Create a new ACP message
     */
    public static ACPMessage create(String id, String method, Map<String, Object> params) {
        return new ACPMessage(id, method, params);
    }
    
    /**
     * Get typed parameter
     */
    @SuppressWarnings("unchecked")
    public <T> T getParam(String key, T defaultValue) {
        if (params == null || !params.containsKey(key)) {
            return defaultValue;
        }
        Object value = params.get(key);
        if (defaultValue != null && value != null && !value.getClass().isInstance(defaultValue)) {
            return defaultValue;
        }
        return (T) value;
    }
    
    /**
     * Get string parameter
     */
    public String getString(String key) {
        return getParam(key, null);
    }
    
    /**
     * Get integer parameter
     */
    public Integer getInt(String key) {
        Object value = params != null ? params.get(key) : null;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        return null;
    }
    
    /**
     * Get boolean parameter
     */
    public Boolean getBool(String key) {
        Object value = params != null ? params.get(key) : null;
        if (value instanceof Boolean) return (Boolean) value;
        return null;
    }
    
    /**
     * Get list parameter
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> getList(String key) {
        Object value = params != null ? params.get(key) : null;
        if (value instanceof List) return (List<T>) value;
        return null;
    }
    
    /**
     * Get map parameter
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String key) {
        Object value = params != null ? params.get(key) : null;
        if (value instanceof Map) return (Map<String, Object>) value;
        return null;
    }

    /**
     * Create an ACPMessage from a Jackson JsonNode (JSON-RPC request).
     *
     * <p>Extracts the id, method, and flattens params into a Map.
     *
     * @param node the JSON-RPC request node
     * @return the parsed ACPMessage
     */
    public static ACPMessage fromJson(JsonNode node) {
        String id = node.has("id") ? node.get("id").asText() : null;
        String method = node.has("method") ? node.get("method").asText() : null;

        Map<String, Object> params = new LinkedHashMap<>();
        JsonNode paramsNode = node.get("params");
        if (paramsNode != null && paramsNode.isObject()) {
            params = flattenJsonNode(paramsNode);
        }

        return new ACPMessage(id, method, params);
    }

    /**
     * Recursively flatten a JsonNode into a Map<String, Object>.
     * Preserves nested structures as Maps and Lists.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> flattenJsonNode(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (!node.isObject()) return map;

        var iter = node.fields();
        while (iter.hasNext()) {
            var entry = iter.next();
            map.put(entry.getKey(), convertJsonValue(entry.getValue()));
        }
        return map;
    }

    private static Object convertJsonValue(JsonNode node) {
        if (node.isNull()) return null;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isDouble()) return node.asDouble();
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            List<Object> list = new java.util.ArrayList<>();
            for (JsonNode item : node) {
                list.add(convertJsonValue(item));
            }
            return list;
        }
        if (node.isObject()) {
            return flattenJsonNode(node);
        }
        return node.toString();
    }
}