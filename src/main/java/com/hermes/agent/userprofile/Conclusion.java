package com.hermes.agent.userprofile;

import java.time.Instant;

/**
 * A persistent conclusion (fact) about a peer.
 * Conclusions are the atomic unit of persistent user knowledge.
 */
public record Conclusion(
    String id,
    String peerId,
    String fact,
    String source,    // "inference", "seed", "conclusion_api", "user"
    long timestamp
) {
    public static Conclusion fromFact(String peerId, String fact) {
        return new Conclusion(
            java.util.UUID.randomUUID().toString(),
            peerId,
            fact,
            "inference",
            Instant.now().toEpochMilli()
        );
    }

    public boolean isFromInference() {
        return "inference".equals(source);
    }

    public boolean isFromSeed() {
        return "seed".equals(source);
    }

    // ── Serialization ──────────────────────────────────────────────────

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", id);
        m.put("peerId", peerId);
        m.put("fact", fact);
        m.put("source", source);
        m.put("timestamp", timestamp);
        return m;
    }

    public static Conclusion fromMap(java.util.Map<String, Object> map) {
        return new Conclusion(
            (String) map.get("id"),
            (String) map.get("peerId"),
            (String) map.get("fact"),
            (String) map.get("source"),
            ((Number) map.get("timestamp")).longValue()
        );
    }
}
