package com.hermes.agent.userprofile;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Honcho-style user profile configuration.
 * Maps to honcho.json configuration fields.
 */
@Component
@ConfigurationProperties(prefix = "hermes.profile")
public class UserProfileConfig {

    // ── Identity ───────────────────────────────────────────────────────────

    /** User peer name (e.g., "Alice") */
    private String peerName = "";

    /** AI peer name (e.g., "hermes") */
    private String aiPeer = "hermes";

    /** Workspace ID for session grouping */
    private String workspace = "hermes";

    // ── Honcho server (optional — for cloud sync) ──────────────────────────

    /** Enable Honcho cloud integration */
    private boolean honchoEnabled = false;

    /** Honcho API base URL */
    private String honchoBaseUrl = "";

    /** Honcho API key */
    private String honchoApiKey = "";

    /** Honcho workspace ID */
    private String honchoWorkspace = "";

    // ── Observation ────────────────────────────────────────────────────────

    /**
     * Observation mode:
     *   directional — each peer builds its own view (default)
     *   unified     — user observes self, AI observes user only
     */
    private String observationMode = "directional";

    // ── Write frequency ─────────────────────────────────────────────────────

    /**
     * How often to write observations:
     *   async     — background thread, no blocking (recommended)
     *   turn      — sync write after every turn
     *   session   — batch write at session end only
     *   N (int)   — write every N turns
     */
    private String writeFrequency = "async";

    // ── Recall mode (B1) ────────────────────────────────────────────────────

    /**
     * How the agent accesses user profile:
     *   hybrid   — auto-injected context + tools available (default)
     *   context  — auto-injected context only
     *   tools    — tools only, no auto-injected context
     */
    private String recallMode = "hybrid";

    // ── Context budget ─────────────────────────────────────────────────────

    /** Max chars for context injection per turn (0 = uncapped) */
    private int contextTokens = 0;

    // ── Dialectic reasoning (B5) ───────────────────────────────────────────

    /** How often to run dialectic LLM reasoning (every N turns, default 2) */
    private int dialecticCadence = 2;

    /** How often to refresh base context (every N turns, default 1) */
    private int contextCadence = 1;

    /** Injection frequency: "every-turn" or "first-turn" */
    private String injectionFrequency = "every-turn";

    /** Dialectic reasoning level: minimal / low / medium / high / max */
    private String dialecticReasoningLevel = "low";

    /** Dialectic depth: 1-3 passes of LLM reasoning per cycle */
    private int dialecticDepth = 1;

    /** Per-pass reasoning levels (optional, overrides proportional) */
    private List<String> dialecticDepthLevels;

    /** Cap for auto-scaled reasoning level */
    private String reasoningLevelCap = "high";

    /** Enable query-length-based reasoning heuristic */
    private boolean reasoningHeuristic = true;

    /** Max chars for dialectic output */
    private int dialecticMaxChars = 600;

    /** Max chars per message for sync */
    private int messageMaxChars = 25000;

    /** Max chars for dialectic input (truncate before LLM call) */
    private int dialecticMaxInputChars = 10000;

    // ── Session strategy ───────────────────────────────────────────────────

    /**
     * Session strategy:
     *   per-session   — each run starts clean (default)
     *   per-directory — reuses session per working directory
     *   per-repo      — reuses session per git repository (crosses subdirs)
     *   global        — single session across all directories
     */
    private String sessionStrategy = "per-session";

    /** Save messages to profile store */
    private boolean saveMessages = true;

    /** Token budget for context injection */
    private int contextCharBudget = 2000;

    // ── Semantic search (vector embeddings) ─────────────────────────────────

    /** Enable semantic search with embeddings */
    private boolean semanticSearchEnabled = true;

    /** Embedding backend: dashscope / openai / mock */
    private String embeddingBackend = "dashscope";

    /** Embedding model dimension (1536 for ada-002, 1024 for text-embedding-v3) */
    private int embeddingDimension = 1536;

    /** Max results for semantic search */
    private int semanticSearchTopK = 10;

    /** Min similarity score threshold (0-1) */
    private double semanticSearchMinScore = 0.5;

    /** Embedding batch size */
    private int embeddingBatchSize = 20;

    /** Max text length for embedding (chars) */
    private int embeddingMaxTextLength = 8000;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public String getPeerName() { return peerName; }
    public void setPeerName(String v) { this.peerName = v; }

    public String getAiPeer() { return aiPeer; }
    public void setAiPeer(String v) { this.aiPeer = v; }

    public String getWorkspace() { return workspace; }
    public void setWorkspace(String v) { this.workspace = v; }

    public boolean isHonchoEnabled() { return honchoEnabled; }
    public void setHonchoEnabled(boolean v) { this.honchoEnabled = v; }

    public String getHonchoBaseUrl() { return honchoBaseUrl; }
    public void setHonchoBaseUrl(String v) { this.honchoBaseUrl = v; }

    public String getHonchoApiKey() { return honchoApiKey; }
    public void setHonchoApiKey(String v) { this.honchoApiKey = v; }

    public String getHonchoWorkspace() { return honchoWorkspace; }
    public void setHonchoWorkspace(String v) { this.honchoWorkspace = v; }

    public String getObservationMode() { return observationMode; }
    public void setObservationMode(String v) { this.observationMode = v; }

    public String getWriteFrequency() { return writeFrequency; }
    public void setWriteFrequency(String v) { this.writeFrequency = v; }

    public String getRecallMode() { return recallMode; }
    public void setRecallMode(String v) { this.recallMode = v; }

    public int getContextTokens() { return contextTokens; }
    public void setContextTokens(int v) { this.contextTokens = v; }

    public int getDialecticCadence() { return dialecticCadence; }
    public void setDialecticCadence(int v) { this.dialecticCadence = v; }

    public int getContextCadence() { return contextCadence; }
    public void setContextCadence(int v) { this.contextCadence = v; }

    public String getInjectionFrequency() { return injectionFrequency; }
    public void setInjectionFrequency(String v) { this.injectionFrequency = v; }

    public List<String> getDialecticDepthLevels() { return dialecticDepthLevels; }
    public void setDialecticDepthLevels(List<String> v) { this.dialecticDepthLevels = v; }

    public int getDialecticMaxInputChars() { return dialecticMaxInputChars; }
    public void setDialecticMaxInputChars(int v) { this.dialecticMaxInputChars = v; }

    public String getDialecticReasoningLevel() { return dialecticReasoningLevel; }
    public void setDialecticReasoningLevel(String v) { this.dialecticReasoningLevel = v; }

    public int getDialecticDepth() { return dialecticDepth; }
    public void setDialecticDepth(int v) { this.dialecticDepth = Math.max(1, Math.min(v, 3)); }

    public String getReasoningLevelCap() { return reasoningLevelCap; }
    public void setReasoningLevelCap(String v) { this.reasoningLevelCap = v; }

    public boolean isReasoningHeuristic() { return reasoningHeuristic; }
    public void setReasoningHeuristic(boolean v) { this.reasoningHeuristic = v; }

    public int getDialecticMaxChars() { return dialecticMaxChars; }
    public void setDialecticMaxChars(int v) { this.dialecticMaxChars = v; }

    public int getMessageMaxChars() { return messageMaxChars; }
    public void setMessageMaxChars(int v) { this.messageMaxChars = v; }

    public String getSessionStrategy() { return sessionStrategy; }
    public void setSessionStrategy(String v) { this.sessionStrategy = v; }

    public boolean isSaveMessages() { return saveMessages; }
    public void setSaveMessages(boolean v) { this.saveMessages = v; }

    public int getContextCharBudget() { return contextCharBudget; }
    public void setContextCharBudget(int v) { this.contextCharBudget = v; }

    public boolean isSemanticSearchEnabled() { return semanticSearchEnabled; }
    public void setSemanticSearchEnabled(boolean v) { this.semanticSearchEnabled = v; }

    public String getEmbeddingBackend() { return embeddingBackend; }
    public void setEmbeddingBackend(String v) { this.embeddingBackend = v; }

    public int getEmbeddingDimension() { return embeddingDimension; }
    public void setEmbeddingDimension(int v) { this.embeddingDimension = v; }

    public int getSemanticSearchTopK() { return semanticSearchTopK; }
    public void setSemanticSearchTopK(int v) { this.semanticSearchTopK = v; }

    public double getSemanticSearchMinScore() { return semanticSearchMinScore; }
    public void setSemanticSearchMinScore(double v) { this.semanticSearchMinScore = v; }

    public int getEmbeddingBatchSize() { return embeddingBatchSize; }
    public void setEmbeddingBatchSize(int v) { this.embeddingBatchSize = v; }

    public int getEmbeddingMaxTextLength() { return embeddingMaxTextLength; }
    public void setEmbeddingMaxTextLength(int v) { this.embeddingMaxTextLength = v; }

    // ── Convenience helpers ───────────────────────────────────────────────

    public boolean isContextInjectionEnabled() {
        return "hybrid".equals(recallMode) || "context".equals(recallMode);
    }

    public boolean isToolsEnabled() {
        return "hybrid".equals(recallMode) || "tools".equals(recallMode);
    }

    public boolean isUnifiedObservation() {
        return "unified".equals(observationMode);
    }

    public boolean isAsyncWrite() {
        return "async".equals(writeFrequency);
    }

    public int getEffectiveDialecticCadence(int emptyStreak) {
        int widened = dialecticCadence + Math.max(0, emptyStreak);
        int ceiling = dialecticCadence * 8;
        return Math.min(widened, ceiling);
    }

    /**
     * Check if session strategy is per-directory or per-repo.
     */
    public boolean isSessionStrategyScoped() {
        return "per-directory".equals(sessionStrategy) || "per-repo".equals(sessionStrategy);
    }

    /**
     * Check if session strategy is per-repo.
     */
    public boolean isPerRepoStrategy() {
        return "per-repo".equals(sessionStrategy);
    }
}
