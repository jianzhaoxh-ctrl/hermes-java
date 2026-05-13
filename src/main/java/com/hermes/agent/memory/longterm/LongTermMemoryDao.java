package com.hermes.agent.memory.longterm;

import com.hermes.agent.memory.longterm.LongTermMemory.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长期记忆数据库访问层（DAO）
 *
 * <p>职责：
 * <ul>
 *   <li>CRUD 操作</li>
 *   <li>FTS5 全文搜索</li>
 *   <li>向量相似度搜索（可选）</li>
 *   <li>跨会话检索</li>
 *   <li>遗忘候选查询</li>
 * </ul>
 */
@Component
public class LongTermMemoryDao {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryDao.class);

    private final JdbcTemplate jdbcTemplate;

    public LongTermMemoryDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Row Mapper
    // ═══════════════════════════════════════════════════════════

    private final RowMapper<LongTermMemory> rowMapper = (rs, rowNum) -> mapRow(rs);

    private LongTermMemory mapRow(ResultSet rs) throws SQLException {
        LongTermMemory mem = new LongTermMemory();
        mem.setId(rs.getString("id"));
        mem.setUserId(rs.getString("user_id"));
        mem.setCategory(Category.fromValue(rs.getString("category")));
        mem.setContent(rs.getString("content"));
        mem.setImportance(rs.getDouble("importance"));
        mem.setAutoForget(rs.getBoolean("auto_forget"));
        
        // 向量
        byte[] embeddingBytes = rs.getBytes("embedding");
        if (embeddingBytes != null && embeddingBytes.length >= 4) {
            float[] embedding = new float[embeddingBytes.length / 4];
            ByteBuffer bb = ByteBuffer.wrap(embeddingBytes);
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] = bb.getFloat();
            }
            mem.setEmbedding(embedding);
        }
        
        mem.setSourceSessionId(rs.getString("source_session_id"));
        mem.setSourceContext(rs.getString("source_context"));
        mem.setCreatedAt(rs.getLong("created_at"));
        
        long lastAccessed = rs.getLong("last_accessed_at");
        if (rs.wasNull()) {
            mem.setLastAccessedAt(mem.getCreatedAt());
        } else {
            mem.setLastAccessedAt(lastAccessed);
        }
        
        mem.setAccessCount(rs.getInt("access_count"));
        
        long predictedForget = rs.getLong("predicted_forget_at");
        if (!rs.wasNull()) {
            mem.setPredictedForgetAt(predictedForget);
        }
        
        // 元数据 JSON
        String metadataJson = rs.getString("metadata");
        if (metadataJson != null && !metadataJson.isBlank()) {
            try {
                Map<String, Object> metadata = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(metadataJson, Map.class);
                mem.setMetadata(metadata);
            } catch (Exception e) {
                log.debug("解析 metadata 失败: {}", e.getMessage());
            }
        }
        
        return mem;
    }

    // ═════════════════════════════════════════════���═════════════════════
    // 插入操作
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 插入新记忆
     */
    public void insert(LongTermMemory memory) {
        String sql = """
            INSERT INTO long_term_memories (
                id, user_id, category, content, importance, auto_forget,
                embedding, source_session_id, source_context,
                created_at, last_accessed_at, access_count, predicted_forget_at, metadata
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        
        // 向量转 bytes
        byte[] embeddingBytes = null;
        if (memory.getEmbedding() != null) {
            ByteBuffer bb = ByteBuffer.allocate(memory.getEmbedding().length * 4);
            for (float f : memory.getEmbedding()) {
                bb.putFloat(f);
            }
            embeddingBytes = bb.array();
        }
        
        // 元数据转 JSON
        String metadataJson = null;
        if (memory.getMetadata() != null && !memory.getMetadata().isEmpty()) {
            try {
                metadataJson = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(memory.getMetadata());
            } catch (Exception e) {
                log.debug("序列化 metadata 失败: {}", e.getMessage());
            }
        }
        
        jdbcTemplate.update(sql,
                memory.getId(),
                memory.getUserId(),
                memory.getCategory() != null ? memory.getCategory().getValue() : null,
                memory.getContent(),
                memory.getImportance(),
                memory.isAutoForget(),
                embeddingBytes,
                memory.getSourceSessionId(),
                memory.getSourceContext(),
                memory.getCreatedAt(),
                memory.getLastAccessedAt(),
                memory.getAccessCount(),
                memory.getPredictedForgetAt(),
                metadataJson
        );
        
        log.debug("[LongTermMemoryDao] 插入记忆: {}", memory.getId());
    }

    /**
     * 批量插入
     */
    public void batchInsert(List<LongTermMemory> memories) {
        for (LongTermMemory mem : memories) {
            insert(mem);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 查询操作
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 根据 ID 查询
     */
    public Optional<LongTermMemory> findById(String id) {
        try {
            LongTermMemory mem = jdbcTemplate.queryForObject(
                    "SELECT * FROM long_term_memories WHERE id = ?",
                    rowMapper, id);
            return Optional.ofNullable(mem);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 查询用户的所有记忆
     */
    public List<LongTermMemory> findByUserId(String userId) {
        return jdbcTemplate.query(
                "SELECT * FROM long_term_memories WHERE user_id = ? ORDER BY created_at DESC",
                rowMapper, userId);
    }

    /**
     * 按分类查询
     */
    public List<LongTermMemory> findByCategory(String userId, Category category, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM long_term_memories WHERE user_id = ? AND category = ? ORDER BY importance DESC, created_at DESC LIMIT ?",
                rowMapper, userId, category.getValue(), limit);
    }

    /**
     * 按重要性阈值查询
     */
    public List<LongTermMemory> findByImportance(String userId, double minImportance, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM long_term_memories WHERE user_id = ? AND importance >= ? ORDER BY importance DESC, created_at DESC LIMIT ?",
                rowMapper, userId, minImportance, limit);
    }

    /**
     * 查询会话的所有记忆
     */
    public List<LongTermMemory> findBySessionId(String sessionId) {
        return jdbcTemplate.query(
                "SELECT * FROM long_term_memories WHERE source_session_id = ? ORDER BY created_at DESC",
                rowMapper, sessionId);
    }

    // ═══════════════════════════════════════════════════════════════════
    // FTS5 全文搜索
    // ═══════════════════════════════════════════════════════════════════

    /**
     * FTS5 全文搜索
     */
    public List<LongTermMemory> searchFts(String query, String userId, int limit) {
        String sql = """
            SELECT m.* FROM long_term_memories m
            JOIN long_term_memories_fts fts ON m.rowid = fts.rowid
            WHERE long_term_memories_fts MATCH ?
            AND m.user_id = ?
            ORDER BY m.importance DESC, m.created_at DESC
            LIMIT ?
            """;
        
        try {
            return jdbcTemplate.query(sql, rowMapper, query, userId, limit);
        } catch (Exception e) {
            log.warn("[LongTermMemoryDao] FTS5 搜索失败，回退到 LIKE: {}", e.getMessage());
            return searchLike(query, userId, limit);
        }
    }

    /**
     * LIKE 模糊搜索（回退方案）
     */
    private List<LongTermMemory> searchLike(String query, String userId, int limit) {
        String likePattern = "%" + query + "%";
        return jdbcTemplate.query(
                "SELECT * FROM long_term_memories WHERE user_id = ? AND (content LIKE ? OR source_context LIKE ?) ORDER BY importance DESC, created_at DESC LIMIT ?",
                rowMapper, userId, likePattern, likePattern, limit);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 更新操作
    // ═══════════════════════════════════════════════════════════════

    /**
     * 标记访问（更新访问计数和时间）
     */
    public void markAccessed(String id) {
        String sql = """
            UPDATE long_term_memories 
            SET last_accessed_at = ?, access_count = access_count + 1
            WHERE id = ?
            """;
        jdbcTemplate.update(sql, System.currentTimeMillis(), id);
    }

    /**
     * 更新重要性评分
     */
    public void updateImportance(String id, double importance, boolean autoForget) {
        String sql = """
            UPDATE long_term_memories 
            SET importance = ?, auto_forget = ?
            WHERE id = ?
            """;
        jdbcTemplate.update(sql, importance, autoForget, id);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 删除操作
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 根据 ID 删除
     */
    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM long_term_memories WHERE id = ?", id);
        log.debug("[LongTermMemoryDao] 删除记忆: {}", id);
    }

    /**
     * 批量删除
     */
    public void batchDelete(List<String> ids) {
        for (String id : ids) {
            deleteById(id);
        }
    }

    /**
     * 删除用户的所有记忆
     */
    public void deleteByUserId(String userId) {
        jdbcTemplate.update("DELETE FROM long_term_memories WHERE user_id = ?", userId);
    }

    // ═══════════════════════════════════════════════════════════
    // 遗忘相关查询
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 获取遗忘候选记忆（按遗忘优先级排序）
     */
    public List<LongTermMemory> getForgettingCandidates(String userId, int limit) {
        // 基于重要性、访问频率、时间计算遗忘优先级
        String sql = """
            SELECT * FROM long_term_memories
            WHERE user_id = ?
            AND (
                (importance < 0.3 AND access_count < 3)
                OR (importance < 0.5 AND (julianday('now') - julianday(last_accessed_at/1000, 'unixepoch')) > 30)
                OR (auto_forget = 1 AND (julianday('now') - julianday(last_accessed_at/1000, 'unixepoch')) > 7)
            )
            ORDER BY 
                (1.0 - importance) * 0.5 +
                (1.0 / (1.0 + access_count)) * 0.3 +
                MIN((last_accessed_at - created_at) / (1000.0 * 60 * 60 * 24 * 30), 1.0) * 0.2
            LIMIT ?
            """;
        
        try {
            return jdbcTemplate.query(sql, rowMapper, userId, limit);
        } catch (Exception e) {
            // SQLite 版本不支持 julianday，回退到简单查询
            log.warn("[LongTermMemoryDao] 遗忘查询失败，回退到简单方案: {}", e.getMessage());
            return findByImportance(userId, 0.0, limit);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 统计
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 统计用户的记忆数量
     */
    public int countByUserId(String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM long_term_memories WHERE user_id = ?",
                Integer.class, userId);
        return count != null ? count : 0;
    }

    /**
     * 统计各分类的记忆数量
     */
    public Map<Category, Integer> countByCategory(String userId) {
        String sql = """
            SELECT category, COUNT(*) as cnt 
            FROM long_term_memories 
            WHERE user_id = ?
            GROUP BY category
            """;
        
        Map<Category, Integer> result = new ConcurrentHashMap<>();
        jdbcTemplate.query(sql, (rs) -> {
            String cat = rs.getString("category");
            int cnt = rs.getInt("cnt");
            result.put(Category.fromValue(cat), cnt);
        }, userId);
        
        return result;
    }

    /**
     * 统计记忆总数
     */
    public int totalCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM long_term_memories",
                Integer.class);
        return count != null ? count : 0;
    }
}