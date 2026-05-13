package com.hermes.agent.userprofile;

import java.time.Instant;

/**
 * An observation — a single unit of dialectic reasoning.
 * Represents a message or fact extracted from the conversation.
 */
public record Observation(
    String id,
    String peerId,
    String content,
    String source,           // "user", "assistant", "conclusion", "seed"
    long timestamp,
    String sessionId,
    String metadata          // JSON metadata (role, messageId, etc.)
) {
    public static Observation userMessage(String peerId, String content, String sessionId) {
        return new Observation(
            java.util.UUID.randomUUID().toString(),
            peerId,
            content,
            "user",
            Instant.now().toEpochMilli(),
            sessionId,
            null
        );
    }

    public static Observation assistantMessage(String peerId, String content, String sessionId) {
        return new Observation(
            java.util.UUID.randomUUID().toString(),
            peerId,
            content,
            "assistant",
            Instant.now().toEpochMilli(),
            sessionId,
            null
        );
    }

    public static Observation conclusion(String peerId, String fact) {
        return new Observation(
            java.util.UUID.randomUUID().toString(),
            peerId,
            fact,
            "conclusion",
            Instant.now().toEpochMilli(),
            null,
            null
        );
    }

    public static Observation seed(String peerId, String content, String source) {
        return new Observation(
            java.util.UUID.randomUUID().toString(),
            peerId,
            content,
            "seed:" + source,
            Instant.now().toEpochMilli(),
            null,
            null
        );
    }

    public boolean isFromUser() {
        return "user".equals(source);
    }

    public boolean isFromAssistant() {
        return "assistant".equals(source);
    }

    public boolean isConclusion() {
        return "conclusion".equals(source);
    }

    public boolean isSeed() {
        return source != null && source.startsWith("seed:");
    }

    // ── Serialization ──────────────────────────────────────────────────

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", id);
        m.put("peerId", peerId);
        m.put("content", content);
        m.put("source", source);
        m.put("timestamp", timestamp);
        m.put("sessionId", sessionId);
        m.put("metadata", metadata);
        return m;
    }

    public static Observation fromMap(java.util.Map<String, Object> map) {
        return new Observation(
            (String) map.get("id"),
            (String) map.get("peerId"),
            (String) map.get("content"),
            (String) map.get("source"),
            ((Number) map.get("timestamp")).longValue(),
            (String) map.get("sessionId"),
            (String) map.get("metadata")
        );
    }
}
