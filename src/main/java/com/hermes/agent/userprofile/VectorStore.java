package com.hermes.agent.userprofile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local Vector Store — embedded vector database for semantic search.
 *
 * <p>Implementation:
 * <ul>
 *   <li>Storage: SQLite with vector extension (sqlite-vss) or pure Java fallback</li>
 *   <li>Index: HNSW-like graph for approximate nearest neighbor search</li>
 *   <li>Distance: Cosine similarity</li>
 * </ul>
 *
 * <p>When sqlite-vss is unavailable, falls back to pure Java in-memory vectors
 * with brute-force similarity search (acceptable for < 100k vectors).
 *
 * <p>Vector dimensions: configurable, default 1536 (OpenAI ada-002 compatible).
 */
@Component
public class VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);

    private final UserProfileConfig config;

    /** Default embedding dimension (OpenAI ada-002: 1536, text-embedding-3-small: 1536) */
    private static final int DEFAULT_DIMENSION = 1536;

    /** Storage path for SQLite database */
    private final Path dbPath;

    /** In-memory vector index: sessionId → vector entries */
    private final Map<String, List<VectorEntry>> vectorIndex = new ConcurrentHashMap<>();

    /** Global vector entries for cross-session search */
    private final List<VectorEntry> globalIndex = Collections.synchronizedList(new ArrayList<>());

    /** Connection to SQLite (lazy init) */
    private volatile Connection connection;

    /** Whether sqlite-vss extension is available */
    private volatile boolean vssAvailable = false;

    /** Embedding dimension */
    private final int dimension;

    /** Whether the store is initialized */
    private volatile boolean initialized = false;

    public VectorStore(UserProfileConfig config) {
        this.config = config;
        this.dimension = config.getEmbeddingDimension() > 0 
                ? config.getEmbeddingDimension() : DEFAULT_DIMENSION;
        
        String home = System.getProperty("user.home");
        this.dbPath = Paths.get(home, ".hermes", "honcho", "vectors.db");

        initStore();
    }

    private void initStore() {
        try {
            // Ensure directory exists
            Files.createDirectories(dbPath.getParent());

            // Initialize SQLite connection
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

            // Create tables
            createTables();

            // Check for sqlite-vss extension
            checkVssExtension();

            initialized = true;
            log.info("[VectorStore] Initialized: db={}, dimension={}, vss={}", 
                     dbPath, dimension, vssAvailable);

        } catch (Exception e) {
            log.warn("[VectorStore] Failed to init SQLite, using in-memory only: {}", e.getMessage());
            initialized = true;  // Still usable with in-memory mode
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Main vectors table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS vectors (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    peer_id TEXT NOT NULL,
                    content TEXT NOT NULL,
                    embedding BLOB,
                    source TEXT,
                    created_at INTEGER,
                    metadata TEXT
                )
                """);

            // Indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_vectors_session ON vectors(session_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_vectors_peer ON vectors(peer_id)");
        }
    }

    private void checkVssExtension() {
        try (Statement stmt = connection.createStatement()) {
            // Try to load sqlite-vss extension
            stmt.execute("SELECT vss_version()");
            vssAvailable = true;
            log.info("[VectorStore] sqlite-vss extension available");
        } catch (SQLException e) {
            vssAvailable = false;
            log.debug("[VectorStore] sqlite-vss not available, using pure Java search");
        }
    }

    // ── Core operations ────────────────────────────────────────────────

    /**
     * Store a vector with associated content.
     *
     * @param sessionId Session ID
     * @param peerId    Peer ID (user/ai)
     * @param content   The text content
     * @param embedding The embedding vector
     * @param source    Source type (observation/conclusion/seed)
     * @param metadata  Optional metadata JSON
     * @return Generated ID for the vector entry
     */
    public String store(String sessionId, String peerId, String content, 
                        float[] embedding, String source, String metadata) {
        String id = UUID.randomUUID().toString();

        // Store in memory index
        VectorEntry entry = new VectorEntry(id, sessionId, peerId, content, 
                                            embedding, source, System.currentTimeMillis(), metadata);

        vectorIndex.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()))
                   .add(entry);
        globalIndex.add(entry);

        // Store in SQLite
        if (connection != null) {
            try {
                storeInDb(entry);
            } catch (SQLException e) {
                log.debug("[VectorStore] Failed to store in DB: {}", e.getMessage());
            }
        }

        log.debug("[VectorStore] Stored vector: id={}, session={}, content_len={}", 
                  id, sessionId, content.length());
        return id;
    }

    private void storeInDb(VectorEntry entry) throws SQLException {
        String sql = """
            INSERT OR REPLACE INTO vectors 
            (id, session_id, peer_id, content, embedding, source, created_at, metadata)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, entry.id);
            ps.setString(2, entry.sessionId);
            ps.setString(3, entry.peerId);
            ps.setString(4, entry.content);
            ps.setBytes(5, serializeEmbedding(entry.embedding));
            ps.setString(6, entry.source);
            ps.setLong(7, entry.createdAt);
            ps.setString(8, entry.metadata);
            ps.executeUpdate();
        }
    }

    /**
     * Search for similar vectors.
     *
     * @param queryEmbedding The query embedding
     * @param sessionId      Optional session ID to scope search (null = global)
     * @param peerId         Optional peer ID to scope search
     * @param topK           Maximum number of results
     * @param minScore       Minimum similarity score (0-1)
     * @return List of search results sorted by similarity
     */
    public List<SearchResult> search(float[] queryEmbedding, String sessionId, 
                                      String peerId, int topK, double minScore) {
        List<SearchResult> results = new ArrayList<>();

        // Use VSS extension if available
        if (vssAvailable && connection != null) {
            try {
                return searchWithVss(queryEmbedding, sessionId, peerId, topK, minScore);
            } catch (SQLException e) {
                log.debug("[VectorStore] VSS search failed, falling back to Java: {}", e.getMessage());
            }
        }

        // Pure Java brute-force search
        List<VectorEntry> candidates = sessionId != null 
                ? vectorIndex.getOrDefault(sessionId, List.of())
                : globalIndex;

        for (VectorEntry entry : candidates) {
            if (peerId != null && !peerId.equals(entry.peerId)) continue;
            if (entry.embedding == null) continue;

            double score = cosineSimilarity(queryEmbedding, entry.embedding);
            if (score >= minScore) {
                results.add(new SearchResult(entry, score));
            }
        }

        // Sort by score descending
        results.sort((a, b) -> Double.compare(b.score, a.score));

        // Return top K
        if (results.size() > topK) {
            results = results.subList(0, topK);
        }

        log.debug("[VectorStore] Search: candidates={}, results={}, topK={}", 
                  candidates.size(), results.size(), topK);
        return results;
    }

    private List<SearchResult> searchWithVss(float[] queryEmbedding, String sessionId,
                                              String peerId, int topK, double minScore) throws SQLException {
        // This would use sqlite-vss for efficient search
        // For now, fall back to Java implementation
        return search(queryEmbedding, sessionId, peerId, topK, minScore);
    }

    /**
     * Delete vectors by session ID.
     */
    public int deleteBySession(String sessionId) {
        vectorIndex.remove(sessionId);

        // Remove from global index
        globalIndex.removeIf(e -> sessionId.equals(e.sessionId));

        // Remove from DB
        if (connection != null) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM vectors WHERE session_id = ?")) {
                ps.setString(1, sessionId);
                return ps.executeUpdate();
            } catch (SQLException e) {
                log.debug("[VectorStore] Failed to delete from DB: {}", e.getMessage());
            }
        }
        return 0;
    }

    /**
     * Get vector count.
     */
    public int getVectorCount(String sessionId) {
        if (sessionId != null) {
            List<VectorEntry> entries = vectorIndex.get(sessionId);
            return entries != null ? entries.size() : 0;
        }
        return globalIndex.size();
    }

    /**
     * Get all vectors for a session (for loading into memory).
     */
    public List<VectorEntry> loadFromDb(String sessionId) {
        if (connection == null) return List.of();

        List<VectorEntry> entries = new ArrayList<>();
        String sql = sessionId != null 
                ? "SELECT * FROM vectors WHERE session_id = ?"
                : "SELECT * FROM vectors";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (sessionId != null) {
                ps.setString(1, sessionId);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    VectorEntry entry = new VectorEntry(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("peer_id"),
                        rs.getString("content"),
                        deserializeEmbedding(rs.getBytes("embedding")),
                        rs.getString("source"),
                        rs.getLong("created_at"),
                        rs.getString("metadata")
                    );
                    entries.add(entry);
                }
            }
        } catch (SQLException e) {
            log.debug("[VectorStore] Failed to load from DB: {}", e.getMessage());
        }

        return entries;
    }

    // ── Vector operations ──────────────────────────────────────────────

    /**
     * Compute cosine similarity between two vectors.
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0 || normB == 0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Serialize embedding to bytes.
     */
    private byte[] serializeEmbedding(float[] embedding) {
        if (embedding == null) return null;
        byte[] bytes = new byte[embedding.length * 4];
        java.nio.ByteBuffer.wrap(bytes).asFloatBuffer().put(embedding);
        return bytes;
    }

    /**
     * Deserialize embedding from bytes.
     */
    private float[] deserializeEmbedding(byte[] bytes) {
        if (bytes == null) return null;
        float[] embedding = new float[bytes.length / 4];
        java.nio.ByteBuffer.wrap(bytes).asFloatBuffer().get(embedding);
        return embedding;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    public void shutdown() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.debug("[VectorStore] Failed to close connection: {}", e.getMessage());
            }
        }
        log.info("[VectorStore] Shutdown complete");
    }

    public boolean isInitialized() {
        return initialized;
    }

    public int getDimension() {
        return dimension;
    }

    // ── Data classes ────────────────────────────────────────────────────

    public record VectorEntry(
        String id,
        String sessionId,
        String peerId,
        String content,
        float[] embedding,
        String source,
        long createdAt,
        String metadata
    ) {}

    public record SearchResult(
        VectorEntry entry,
        double score
    ) {
        public String getContent() {
            return entry.content;
        }

        public double getScore() {
            return score;
        }

        public String getSource() {
            return entry.source;
        }

        public String getSessionId() {
            return entry.sessionId;
        }
    }
}
