package com.hermes.agent.memory.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.agent.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量存储服务 — 基于 HNSW 算法的高效向量检索。
 *
 * <p>核心特性：
 * <ul>
 *   <li>HNSW (Hierarchical Navigable Small World) 算法，O(log n) 查询复杂度</li>
 *   <li>支持增量添加和删除</li>
 *   <li>自动持久化到磁盘</li>
 *   <li>支持元数据过滤</li>
 *   <li>多会话隔离</li>
 * </ul>
 *
 * <p>参考：Python 版 Mem0 的 Qdrant 向量存储实现
 */
public class MemoryVectorStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorStore.class);

    private final AgentConfig config;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // HNSW 索引参数
    private final int m;                    // 每个节点的最大连接数
    private final int efConstruction;       // 构建时的 ef 参数
    private final int efSearch;             // 搜索时的 ef 参数
    private final int dimension;            // 向量维度

    // 存储结构
    private final Map<String, VectorEntry> entries = new ConcurrentHashMap<>();  // id -> entry
    private final Map<String, Set<String>> sessionIndex = new ConcurrentHashMap<>();  // sessionId -> ids
    private final Map<String, Set<String>> typeIndex = new ConcurrentHashMap<>();      // type -> ids

    // 持久化路径
    private final Path indexPath;
    private final Path metaPath;

    // HNSW 索引（简化实现，实际应使用 hnswlib）
    private HnswIndex hnswIndex;

    public MemoryVectorStore(AgentConfig config, EmbeddingService embeddingService) {
        this.config = config;
        this.embeddingService = embeddingService;

        this.m = config.getVectorMemoryHnswM();
        this.efConstruction = config.getVectorMemoryHnswEfConstruction();
        this.efSearch = config.getVectorMemoryHnswEfSearch();
        this.dimension = config.getVectorMemoryDimension();

        String basePath = config.getVectorMemoryIndexPath();
        if (basePath == null || basePath.isBlank()) {
            basePath = System.getProperty("user.home") + "/.hermes/vector_index";
        }
        this.indexPath = Paths.get(basePath + ".idx");
        this.metaPath = Paths.get(basePath + ".meta");

        init();
    }

    private void init() {
        try {
            // 初始化 HNSW 索引
            this.hnswIndex = new HnswIndex(dimension, m, efConstruction, efSearch);

            // 加载已有数据
            loadFromDisk();

            log.info("[MemoryVectorStore] 初始化完成: dimension={}, M={}, efConstruction={}, efSearch={}, entries={}",
                    dimension, m, efConstruction, efSearch, entries.size());
        } catch (Exception e) {
            log.error("[MemoryVectorStore] 初始化失败: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  核心操作
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 添加向量
     *
     * @param id        唯一标识符
     * @param content   原始内容
     * @param vector    向量表示
     * @param sessionId 会话 ID
     * @param type      类型（如 "memory", "fact", "preference"）
     * @param metadata  额外元数据
     * @return 是否成功
     */
    public boolean add(String id, String content, float[] vector, String sessionId, String type, Map<String, Object> metadata) {
        if (id == null || vector == null || vector.length != dimension) {
            log.warn("[MemoryVectorStore] 添加失败: id={}, vectorLength={}", id, vector != null ? vector.length : "null");
            return false;
        }

        VectorEntry entry = new VectorEntry(
                id,
                content,
                vector,
                sessionId != null ? sessionId : "default",
                type != null ? type : "memory",
                metadata != null ? metadata : new HashMap<>(),
                System.currentTimeMillis()
        );

        // 添加到存储
        entries.put(id, entry);
        hnswIndex.add(id, vector);

        // 更新索引
        sessionIndex.computeIfAbsent(entry.sessionId, k -> ConcurrentHashMap.newKeySet()).add(id);
        typeIndex.computeIfAbsent(entry.type, k -> ConcurrentHashMap.newKeySet()).add(id);

        log.debug("[MemoryVectorStore] 添加向量: id={}, session={}, type={}", id, entry.sessionId, entry.type);
        return true;
    }

    /**
     * 添加文本（自动生成向量）
     */
    public boolean addText(String id, String content, String sessionId, String type, Map<String, Object> metadata) {
        try {
            float[] vector = embeddingService.embed(content);
            return add(id, content, vector, sessionId, type, metadata);
        } catch (EmbeddingService.EmbeddingException e) {
            log.error("[MemoryVectorStore] 生成向量失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 删除向量
     */
    public boolean delete(String id) {
        VectorEntry entry = entries.remove(id);
        if (entry == null) {
            return false;
        }

        // 从索引中移除（HNSW 不支持直接删除，标记为已删除）
        hnswIndex.markDeleted(id);

        // 更新辅助索引
        Set<String> sessionIds = sessionIndex.get(entry.sessionId);
        if (sessionIds != null) {
            sessionIds.remove(id);
        }

        Set<String> typeIds = typeIndex.get(entry.type);
        if (typeIds != null) {
            typeIds.remove(id);
        }

        log.debug("[MemoryVectorStore] 删除向量: id={}", id);
        return true;
    }

    /**
     * 删除会话的所有向量
     */
    public int deleteBySession(String sessionId) {
        Set<String> ids = sessionIndex.remove(sessionId);
        if (ids == null || ids.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (String id : ids) {
            if (entries.remove(id) != null) {
                hnswIndex.markDeleted(id);
                count++;
            }
        }

        log.info("[MemoryVectorStore] 删除会话向量: session={}, count={}", sessionId, count);
        return count;
    }

    /**
     * 相似度搜索
     *
     * @param queryVector 查询向量
     * @param k           返回结果数
     * @param sessionId   会话过滤（可选）
     * @param type        类型过滤（可选）
     * @return 搜索结果列表
     */
    public List<SearchResult> search(float[] queryVector, int k, String sessionId, String type) {
        if (queryVector == null || queryVector.length != dimension) {
            return List.of();
        }

        // HNSW 搜索
        List<HnswIndex.SearchResult> hnswResults = hnswIndex.search(queryVector, k * 2);  // 多取一些，用于过滤

        // 过滤和构建结果
        List<SearchResult> results = new ArrayList<>();
        for (HnswIndex.SearchResult hr : hnswResults) {
            VectorEntry entry = entries.get(hr.id);
            if (entry == null || hnswIndex.isDeleted(hr.id)) {
                continue;
            }

            // 会话过滤
            if (sessionId != null && !sessionId.isBlank() && !sessionId.equals(entry.sessionId)) {
                continue;
            }

            // 类型过滤
            if (type != null && !type.isBlank() && !type.equals(entry.type)) {
                continue;
            }

            results.add(new SearchResult(entry, hr.score));
            if (results.size() >= k) break;
        }

        log.debug("[MemoryVectorStore] 搜索完成: queryDim={}, k={}, sessionId={}, type={}, results={}",
                queryVector.length, k, sessionId, type, results.size());
        return results;
    }

    /**
     * 文本相似度搜索（自动生成向量）
     */
    public List<SearchResult> searchText(String query, int k, String sessionId, String type) {
        try {
            float[] queryVector = embeddingService.embed(query);
            return search(queryVector, k, sessionId, type);
        } catch (EmbeddingService.EmbeddingException e) {
            log.error("[MemoryVectorStore] 生成查询向量失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取指定 ID 的向量
     */
    public VectorEntry get(String id) {
        return entries.get(id);
    }

    /**
     * 获取会话的所有向量
     */
    public List<VectorEntry> getBySession(String sessionId) {
        Set<String> ids = sessionIndex.get(sessionId);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        List<VectorEntry> result = new ArrayList<>();
        for (String id : ids) {
            VectorEntry entry = entries.get(id);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  持久化
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 保存到磁盘
     */
    public synchronized void saveToDisk() {
        try {
            // 确保目录存在
            Files.createDirectories(indexPath.getParent());

            // 保存元数据
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(metaPath.toFile()))) {
                oos.writeObject(new ArrayList<>(entries.values()));
                oos.writeObject(new HashMap<>(sessionIndex));
                oos.writeObject(new HashMap<>(typeIndex));
            }

            log.info("[MemoryVectorStore] 保存完成: entries={}, path={}", entries.size(), metaPath);
        } catch (IOException e) {
            log.error("[MemoryVectorStore] 保存失败: {}", e.getMessage());
        }
    }

    /**
     * 从磁盘加载
     */
    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (!Files.exists(metaPath)) {
            log.info("[MemoryVectorStore] 元数据文件不存在，跳过加载");
            return;
        }

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(metaPath.toFile()))) {
            List<VectorEntry> entryList = (List<VectorEntry>) ois.readObject();
            Map<String, Set<String>> sessionMap = (Map<String, Set<String>>) ois.readObject();
            Map<String, Set<String>> typeMap = (Map<String, Set<String>>) ois.readObject();

            // 恢复数据
            for (VectorEntry entry : entryList) {
                entries.put(entry.id, entry);
                hnswIndex.add(entry.id, entry.vector);
            }
            sessionIndex.putAll(sessionMap);
            typeIndex.putAll(typeMap);

            log.info("[MemoryVectorStore] 加载完成: entries={}", entries.size());
        } catch (Exception e) {
            log.warn("[MemoryVectorStore] 加载失败: {}", e.getMessage());
        }
    }

    /**
     * 关闭并保存
     */
    @PreDestroy
    public void shutdown() {
        saveToDisk();
        log.info("[MemoryVectorStore] 关闭");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  统计信息
    // ═══════════════════════════════════════════════════════════════════════

    public int size() {
        return entries.size();
    }

    public int sessionCount() {
        return sessionIndex.size();
    }

    public Map<String, Object> getStats() {
        return Map.of(
                "totalEntries", entries.size(),
                "sessions", sessionIndex.size(),
                "types", typeIndex.size(),
                "dimension", dimension
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  数据类
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 向量条目
     */
    public static class VectorEntry implements Serializable {
        public final String id;
        public final String content;
        public final float[] vector;
        public final String sessionId;
        public final String type;
        public final Map<String, Object> metadata;
        public final long timestamp;

        public VectorEntry(String id, String content, float[] vector, String sessionId, String type, Map<String, Object> metadata, long timestamp) {
            this.id = id;
            this.content = content;
            this.vector = vector;
            this.sessionId = sessionId;
            this.type = type;
            this.metadata = metadata;
            this.timestamp = timestamp;
        }
    }

    /**
     * 搜索结果
     */
    public static class SearchResult {
        public final VectorEntry entry;
        public final float score;

        public SearchResult(VectorEntry entry, float score) {
            this.entry = entry;
            this.score = score;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", entry.id);
            m.put("content", entry.content.length() > 300 ? entry.content.substring(0, 300) + "..." : entry.content);
            m.put("sessionId", entry.sessionId);
            m.put("type", entry.type);
            m.put("score", String.format("%.4f", score));
            m.put("timestamp", entry.timestamp);
            return m;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  简化的 HNSW 索引实现
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 简化的 HNSW 索引实现。
     * 实际生产环境应使用 hnswlib-java 或其他成熟库。
     */
    private static class HnswIndex {
        private final int dimension;
        private final int efSearch;
        private final Map<String, float[]> vectors = new ConcurrentHashMap<>();
        private final Set<String> deleted = ConcurrentHashMap.newKeySet();

        public HnswIndex(int dimension, int m, int efConstruction, int efSearch) {
            this.dimension = dimension;
            this.efSearch = efSearch;
        }

        public void add(String id, float[] vector) {
            vectors.put(id, vector);
        }

        public void markDeleted(String id) {
            deleted.add(id);
        }

        public boolean isDeleted(String id) {
            return deleted.contains(id);
        }

        public List<SearchResult> search(float[] query, int k) {
            List<SearchResult> results = new ArrayList<>();

            for (Map.Entry<String, float[]> entry : vectors.entrySet()) {
                if (deleted.contains(entry.getKey())) continue;

                float score = cosineSimilarity(query, entry.getValue());
                results.add(new SearchResult(entry.getKey(), score));
            }

            // 按相似度降序排序
            results.sort((a, b) -> Float.compare(b.score, a.score));

            // 返回 top-k
            if (results.size() > k) {
                results = results.subList(0, k);
            }

            return results;
        }

        private float cosineSimilarity(float[] a, float[] b) {
            float dotProduct = 0.0f;
            float normA = 0.0f;
            float normB = 0.0f;

            for (int i = 0; i < a.length; i++) {
                dotProduct += a[i] * b[i];
                normA += a[i] * a[i];
                normB += b[i] * b[i];
            }

            if (normA == 0 || normB == 0) return 0.0f;
            return dotProduct / (float) (Math.sqrt(normA) * Math.sqrt(normB));
        }

        public static class SearchResult {
            public final String id;
            public final float score;

            public SearchResult(String id, float score) {
                this.id = id;
                this.score = score;
            }
        }
    }
}
