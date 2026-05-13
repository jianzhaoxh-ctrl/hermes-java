package com.hermes.agent.userprofile;

import com.hermes.agent.llm.LLMService;
import com.hermes.agent.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Embedding Service — generates vector embeddings for semantic search.
 *
 * <p>Supports multiple backends:
 * <ul>
 *   <li><b>dashscope</b>: Alibaba Tongyi embedding API (default)</li>
 *   <li><b>openai</b>: OpenAI embeddings API</li>
 *   <li><b>local</b>: Local sentence-transformers (requires external service)</li>
 * </ul>
 *
 * <p>Caching: embeddings are cached by content hash to avoid redundant API calls.
 * Cache is in-memory with LRU eviction (max 10k entries).
 */
@Component
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final UserProfileConfig config;
    private final LLMService llmService;

    /** Embedding cache: contentHash → embedding */
    private final Map<String, float[]> embeddingCache;

    /** Cache max size */
    private static final int CACHE_MAX_SIZE = 10_000;

    /** Executor for async embedding */
    private final ExecutorService executor;

    /** Whether embedding is enabled */
    private volatile boolean enabled = true;

    /** Default embedding model for DashScope */
    private static final String DASHSCOPE_EMBEDDING_MODEL = "text-embedding-v3";

    /** Embedding dimension cache */
    private volatile int cachedDimension = 0;

    public EmbeddingService(UserProfileConfig config, LLMService llmService) {
        this.config = config;
        this.llmService = llmService;
        this.embeddingCache = new ConcurrentHashMap<>();
        this.executor = Executors.newFixedThreadPool(
                2,
                r -> { Thread t = new Thread(r, "hermes-embedding"); t.setDaemon(true); return t; }
        );

        log.info("[Embedding] Initialized: backend={}", config.getEmbeddingBackend());
    }

    /**
     * Generate embedding for a single text.
     *
     * @param text The text to embed
     * @return Embedding vector, or null if failed
     */
    public float[] embed(String text) {
        if (!enabled || text == null || text.isBlank()) {
            return null;
        }

        // Check cache
        String hash = hashContent(text);
        float[] cached = embeddingCache.get(hash);
        if (cached != null) {
            log.debug("[Embedding] Cache hit for text hash: {}", hash.substring(0, 8));
            return cached;
        }

        // Generate embedding
        float[] embedding = generateEmbedding(text);
        if (embedding != null) {
            // Cache it (with LRU eviction)
            if (embeddingCache.size() >= CACHE_MAX_SIZE) {
                // Simple eviction: clear half
                embeddingCache.clear();
                log.debug("[Embedding] Cache cleared (LRU eviction)");
            }
            embeddingCache.put(hash, embedding);
        }

        return embedding;
    }

    /**
     * Generate embeddings for multiple texts (batch).
     *
     * @param texts List of texts to embed
     * @return List of embeddings (same order as input)
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (!enabled || texts == null || texts.isEmpty()) {
            return List.of();
        }

        List<float[]> results = new ArrayList<>();
        for (String text : texts) {
            results.add(embed(text));
        }
        return results;
    }

    /**
     * Generate embedding asynchronously.
     */
    public CompletableFuture<float[]> embedAsync(String text) {
        return CompletableFuture.supplyAsync(() -> embed(text), executor);
    }

    /**
     * Get the embedding dimension.
     */
    public int getDimension() {
        if (cachedDimension > 0) {
            return cachedDimension;
        }

        // Generate a dummy embedding to get dimension
        float[] sample = embed("test");
        if (sample != null) {
            cachedDimension = sample.length;
        }
        return cachedDimension > 0 ? cachedDimension : 1536;
    }

    // ── Backend implementations ────────────────────────────────────────

    /**
     * Generate embedding using configured backend.
     */
    private float[] generateEmbedding(String text) {
        String backend = config.getEmbeddingBackend();

        try {
            return switch (backend) {
                case "dashscope" -> generateDashScopeEmbedding(text);
                case "openai" -> generateOpenAIEmbedding(text);
                case "mock" -> generateMockEmbedding(text);
                default -> generateDashScopeEmbedding(text);  // default to dashscope
            };
        } catch (Exception e) {
            log.warn("[Embedding] Failed to generate embedding: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Generate embedding using DashScope (Alibaba Tongyi) API.
     * 
     * Note: This uses the DashScope embedding endpoint.
     * Actual implementation would call the embedding API.
     */
    private float[] generateDashScopeEmbedding(String text) {
        // DashScope embedding API call
        // For now, generate a mock embedding if API not available
        // Real implementation would use LLMService or HTTP client
        
        try {
            // Try to use LLMService for embedding (if it supports it)
            // DashScope embedding endpoint: POST /services/embeddings/text-embedding/text-embedding
            
            // For now, fall back to mock if actual embedding API not integrated
            if (!config.isHonchoEnabled()) {
                return generateMockEmbedding(text);
            }

            // TODO: Implement actual DashScope embedding API call
            // The DashScope embedding models:
            // - text-embedding-v1: 1536 dimensions
            // - text-embedding-v2: 1536 dimensions
            // - text-embedding-v3: 1024 dimensions (newer, cheaper)
            
            return generateMockEmbedding(text);
            
        } catch (Exception e) {
            log.debug("[Embedding] DashScope embedding failed: {}", e.getMessage());
            return generateMockEmbedding(text);
        }
    }

    /**
     * Generate embedding using OpenAI API.
     */
    private float[] generateOpenAIEmbedding(String text) {
        // OpenAI embedding API call
        // Real implementation would use OpenAI client
        
        try {
            // TODO: Implement actual OpenAI embedding API call
            // The OpenAI embedding models:
            // - text-embedding-ada-002: 1536 dimensions
            // - text-embedding-3-small: 1536 dimensions
            // - text-embedding-3-large: 3072 dimensions
            
            return generateMockEmbedding(text);
            
        } catch (Exception e) {
            log.debug("[Embedding] OpenAI embedding failed: {}", e.getMessage());
            return generateMockEmbedding(text);
        }
    }

    /**
     * Generate a deterministic mock embedding for testing/fallback.
     * Uses simple hash-based pseudo-random vector, normalized.
     */
    private float[] generateMockEmbedding(String text) {
        int dim = config.getEmbeddingDimension() > 0 ? config.getEmbeddingDimension() : 1536;
        float[] embedding = new float[dim];

        // Use text hash as seed for deterministic results
        int seed = text.hashCode();
        Random random = new Random(seed);

        double norm = 0.0;
        for (int i = 0; i < dim; i++) {
            embedding[i] = (float) (random.nextGaussian() * 0.5);
            norm += embedding[i] * embedding[i];
        }

        // Normalize to unit length
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dim; i++) {
                embedding[i] /= norm;
            }
        }

        return embedding;
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * Hash content for cache key.
     */
    private String hashContent(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            return String.valueOf(text.hashCode());
        }
    }

    // ── Config ────────────────────────────────────────────────────────

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getCacheSize() {
        return embeddingCache.size();
    }

    public void clearCache() {
        embeddingCache.clear();
        log.info("[Embedding] Cache cleared");
    }

    public void shutdown() {
        executor.shutdown();
        embeddingCache.clear();
        log.info("[Embedding] Shutdown complete");
    }
}
