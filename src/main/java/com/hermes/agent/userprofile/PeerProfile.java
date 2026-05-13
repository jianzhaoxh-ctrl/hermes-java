package com.hermes.agent.userprofile;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Complete profile for a peer (user or AI).
 * Holds the peer card (facts), representation (LLM-generated narrative),
 * observations (all extracted facts/messages), and conclusions (persistent facts).
 */
public class PeerProfile {

    private final String peerId;
    private final String peerType;  // "user" or "ai"

    /** Peer card — curated list of key facts */
    private PeerCard card;

    /** LLM-generated representation narrative */
    private String representation;

    /** All observations (messages, facts, seeds) */
    private final List<Observation> observations = Collections.synchronizedList(new ArrayList<>());

    /** Persistent conclusions drawn from conversation */
    private final List<Conclusion> conclusions = new ArrayList<>();

    /** Observation mode: "directional" or "unified" */
    private String observationMode = "directional";

    /** When this profile was first created */
    private final long createdAt;

    /** Last time representation was regenerated */
    private long lastRepresentationUpdate;

    /** Version counter for cache invalidation */
    private int version = 0;

    /** Cache for built context strings */
    private volatile String cachedContext;
    private volatile long cachedContextAt;
    private static final long CONTEXT_CACHE_TTL_MS = 30_000; // 30 seconds

    public PeerProfile(String peerId, String peerType) {
        this.peerId = peerId;
        this.peerType = peerType;
        this.card = PeerCard.empty(peerId);
        this.representation = "";
        this.createdAt = Instant.now().toEpochMilli();
        this.lastRepresentationUpdate = createdAt;
    }

    // ── Observation tracking ────────────────────────────────────────────────

    /**
     * Record a user message observation.
     * In directional mode: only user observes their own messages.
     * In unified mode: user also observes AI messages about them.
     */
    public void observeUserMessage(String content, String sessionId) {
        if (!shouldObserve("user")) return;
        observations.add(Observation.userMessage(peerId, content, sessionId));
        invalidateCache();
    }

    /**
     * Record an assistant message observation.
     * In directional mode: AI observes its own messages.
     * In unified mode: AI observes user messages about it.
     */
    public void observeAssistantMessage(String content, String sessionId) {
        if (!shouldObserve("assistant")) return;
        observations.add(Observation.assistantMessage(peerId, content, sessionId));
        invalidateCache();
    }

    /**
     * Add a seed observation (from SOUL.md, USER.md, etc.)
     */
    public void addSeed(String content, String source) {
        observations.add(Observation.seed(peerId, content, source));
        invalidateCache();
    }

    // ── Conclusions ────────────────────────────────────────────────────────

    /**
     * Add a persistent conclusion (fact) about this peer.
     */
    public Conclusion addConclusion(String fact, String source) {
        Conclusion c = new Conclusion(
            UUID.randomUUID().toString(),
            peerId,
            fact,
            source != null ? source : "inference",
            Instant.now().toEpochMilli()
        );
        synchronized (conclusions) {
            // Avoid duplicates
            if (!conclusions.stream().anyMatch(x -> x.fact().equals(fact))) {
                conclusions.add(c);
                // Also update peer card
                card = card.merge(List.of(fact));
            }
        }
        invalidateCache();
        return c;
    }

    public void removeConclusion(String conclusionId) {
        synchronized (conclusions) {
            conclusions.removeIf(c -> c.id().equals(conclusionId));
        }
        invalidateCache();
    }

    // ── Peer card ──────────────────────────────────────────────────────────

    public void updateCard(List<String> facts) {
        this.card = card.withFacts(facts);
        invalidateCache();
    }

    public void mergeCardFacts(List<String> newFacts) {
        this.card = card.merge(newFacts);
        invalidateCache();
    }

    // ── Representation ────────────────────────────────────────────────────

    public void updateRepresentation(String text) {
        this.representation = text;
        this.lastRepresentationUpdate = Instant.now().toEpochMilli();
        this.version++;
        invalidateCache();
    }

    // ── Observation mode ───────────────────────────────────────────────────

    public void setObservationMode(String mode) {
        this.observationMode = mode;
        invalidateCache();
    }

    /**
     * In directional mode:
     *   - user peer observes: user messages (me=true, others=false)
     *   - ai peer observes: assistant messages (me=true, others=false)
     * In unified mode:
     *   - user peer observes: user messages (me=true, others=false)
     *   - ai peer observes: user messages (me=false, others=true) — "AI observes user"
     */
    private boolean shouldObserve(String source) {
        if ("user".equals(source)) {
            return "directional".equals(observationMode) ? "user".equals(peerType) : false;
        } else if ("assistant".equals(source)) {
            return "directional".equals(observationMode) ? "ai".equals(peerType) : false;
        }
        return true; // conclusions and seeds always observed
    }

    // ── Cache management ────────────────────────────────────────────────────

    private void invalidateCache() {
        this.cachedContext = null;
        this.version++;
    }

    /**
     * Build a display-friendly context string for system prompt injection.
     * Cached for CONTEXT_CACHE_TTL_MS to avoid repeated string building.
     */
    public String buildContextString() {
        long now = Instant.now().toEpochMilli();
        if (cachedContext != null && (now - cachedContextAt) < CONTEXT_CACHE_TTL_MS) {
            return cachedContext;
        }

        StringBuilder sb = new StringBuilder();

        // Representation (LLM-generated narrative about the peer)
        if (representation != null && !representation.isBlank()) {
            sb.append("## Representation\n").append(representation).append("\n\n");
        }

        // Peer card (key facts)
        if (!card.facts().isEmpty()) {
            sb.append("## Peer Card\n");
            for (String fact : card.facts()) {
                sb.append("- ").append(fact).append("\n");
            }
            sb.append("\n");
        }

        // Recent observations (last 5)
        synchronized (observations) {
            int count = observations.size();
            if (count > 0) {
                int start = Math.max(0, count - 5);
                List<Observation> recent = observations.subList(start, count);
                sb.append("## Recent Context\n");
                for (Observation obs : recent) {
                    String label = obs.isFromUser() ? "user" : obs.isFromAssistant() ? "assistant" : obs.source();
                    sb.append("- [").append(label).append("] ").append(obs.content().substring(0, Math.min(200, obs.content().length()))).append("\n");
                }
                sb.append("\n");
            }
        }

        String result = sb.toString();
        this.cachedContext = result;
        this.cachedContextAt = now;
        return result;
    }

    // ── Getters ────────────────────────────────────────────────────────────

    public String getPeerId() { return peerId; }
    public String getPeerType() { return peerType; }
    public PeerCard getCard() { return card; }
    public String getRepresentation() { return representation; }
    public List<Observation> getObservations() { return List.copyOf(observations); }
    public List<Conclusion> getConclusions() { synchronized (conclusions) { return List.copyOf(conclusions); } }
    public String getObservationMode() { return observationMode; }
    public long getCreatedAt() { return createdAt; }
    public long getLastRepresentationUpdate() { return lastRepresentationUpdate; }
    public int getVersion() { return version; }
    public int getObservationCount() { return observations.size(); }
    public int getConclusionCount() { synchronized (conclusions) { return conclusions.size(); } }

    /**
     * Get context with token budget enforcement.
     */
    public String buildContextString(int maxChars) {
        String full = buildContextString();
        if (full.length() <= maxChars) return full;
        return full.substring(0, maxChars) + " …";
    }

    /**
     * Export this profile as a JSON-friendly map.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put("peerId", peerId);
        result.put("peerType", peerType);
        result.put("cardFacts", card.facts());
        result.put("representation", representation);
        result.put("observationMode", observationMode);
        result.put("createdAt", createdAt);
        result.put("lastUpdated", lastRepresentationUpdate);
        result.put("version", version);
        result.put("observationCount", observations.size());
        result.put("conclusionCount", conclusions.size());
        return result;
    }

    /**
     * Create a PeerProfile from a map (deserialization).
     */
    public static PeerProfile fromMap(String sessionId, Map<String, Object> map) {
        String peerId = (String) map.getOrDefault("peerId", sessionId);
        String peerType = (String) map.getOrDefault("peerType", "user");
        PeerProfile p = new PeerProfile(peerId, peerType);
        
        // Card facts
        Object cardFacts = map.get("cardFacts");
        if (cardFacts instanceof List) {
            List<String> facts = ((List<?>) cardFacts).stream()
                    .map(Object::toString).toList();
            p.updateCard(facts);
        }
        
        // Representation
        String repr = (String) map.get("representation");
        if (repr != null && !repr.isEmpty()) {
            p.updateRepresentation(repr);
        }
        
        // Observation mode
        String obsMode = (String) map.get("observationMode");
        if (obsMode != null) p.setObservationMode(obsMode);
        
        return p;
    }
}
