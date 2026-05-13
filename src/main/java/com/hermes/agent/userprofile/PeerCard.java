package com.hermes.agent.userprofile;

import java.util.List;
import java.util.Map;

/**
 * Represents a peer card — a curated list of key facts about a peer.
 * Used for both user and AI peers in the dialectic reasoning system.
 */
public record PeerCard(
    String peerId,
    List<String> facts,
    long updatedAt,
    int version
) {
    public static PeerCard empty(String peerId) {
        return new PeerCard(peerId, List.of(), System.currentTimeMillis(), 0);
    }

    public PeerCard withFacts(List<String> newFacts) {
        return new PeerCard(peerId, newFacts, System.currentTimeMillis(), version + 1);
    }

    public PeerCard merge(List<String> additionalFacts) {
        List<String> merged = new java.util.ArrayList<>(facts);
        for (String fact : additionalFacts) {
            if (!merged.contains(fact)) {
                merged.add(fact);
            }
        }
        return withFacts(merged);
    }
}
