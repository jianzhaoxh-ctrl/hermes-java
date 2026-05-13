package com.hermes.agent.memory.longterm;

import com.hermes.agent.memory.SessionSearchService;
import com.hermes.agent.persistence.PersistenceBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 跨会话搜索服务 — Phase 3 核心组件
 *
 * <p>职责：
 * <ul>
 *   <li>FTS5 跨会话全文搜索</li>
 *   <li>会话链追踪（parent_session_id）</li>
 *   <li>长期记忆 ↔ 会话关联</li>
 *   <li>会话摘要检索</li>
 *   <li>语义搜索（向量 + FTS 混合）</li>
 * </ul>
 *
 * <p>与 SessionSearchService 的区别：
 * <ul>
 *   <li>SessionSearchService — 单会话内搜索</li>
 *   <li>CrossSessionSearchService — 跨所有会话搜索</li>
 * </ul>
 */
@Component
public class CrossSessionSearchService {

    private static final Logger log = LoggerFactory.getLogger(CrossSessionSearchService.class);

    private final JdbcTemplate jdbcTemplate;
    private final LongTermMemoryDao memoryDao;

    public CrossSessionSearchService(JdbcTemplate jdbcTemplate, LongTermMemoryDao memoryDao) {
        this.jdbcTemplate = jdbcTemplate;
        this.memoryDao = memoryDao;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Row Mappers
    // ═══════════════════════════════════════════════════════════════════

    private final RowMapper<EnhancedSessionSummary> summaryRowMapper = (rs, rowNum) -> {
        EnhancedSessionSummary s = new EnhancedSessionSummary();
        s.setSessionId(rs.getString("session_id"));
        s.setParentSessionId(rs.getString("parent_session_id"));
        s.setTitle(rs.getString("title"));
        s.setCreatedAt(rs.getLong("created_at"));
        s.setLastActiveAt(rs.getLong("last_active_at"));

        // 13 字段
        s.setActiveTask(rs.getString("active_task"));
        s.setGoal(rs.getString("goal"));
        s.setConstraints(rs.getString("constraints"));
        s.setCompletedActions(rs.getString("completed_actions"));
        s.setActiveState(rs.getString("active_state"));
        s.setInProgress(rs.getString("in_progress"));
        s.setBlocked(rs.getString("blocked"));
        s.setKeyDecisions(rs.getString("key_decisions"));
        s.setResolvedQuestions(rs.getString("resolved_questions"));
        s.setPendingUserAsks(rs.getString("pending_user_asks"));
        s.setRelevantFiles(rs.getString("relevant_files"));
        s.setRemainingWork(rs.getString("remaining_work"));
        s.setCriticalContext(rs.getString("critical_context"));

        // 跨时空字段
        s.setImportance(rs.getDouble("importance"));
        String linkedMemIds = rs.getString("linked_memory_ids");
        if (linkedMemIds != null && !linkedMemIds.isEmpty()) {
            s.setLinkedMemoryIds(Arrays.asList(linkedMemIds.split(",")));
        }
        String linkedSessIds = rs.getString("linked_session_ids");
        if (linkedSessIds != null && !linkedSessIds.isEmpty()) {
            s.setLinkedSessionIds(Arrays.asList(linkedSessIds.split(",")));
        }
        s.setExtractedFromLongTerm(rs.getBoolean("extracted_from_long_term"));

        return s;
    };

    private final RowMapper<CrossSessionSearchResult> searchResultRowMapper = (rs, rowNum) -> {
        CrossSessionSearchResult result = new CrossSessionSearchResult();
        result.setSessionId(rs.getString("session_id"));
        result.setRole(rs.getString("role"));
        result.setSnippet(rs.getString("snippet"));
        result.setScore(rs.getDouble("score"));
        result.setTimestamp(rs.getLong("timestamp"));
        return result;
    };

    // ═══════════════════════════════════════════════════════════════════
    // 跨会话全文搜索（消息）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 跨所有会话的 FTS5 全文搜索
     *
     * @param query 搜索关键词
     * @param limit 返回结果上限
     * @return 搜索结果列表
     */
    public List<CrossSessionSearchResult> searchMessages(String query, int limit) {
        return searchMessages(query, null, limit);
    }

    /**
     * 跨会话 FTS5 全文搜索（可选来源过滤）
     *
     * @param query 搜索关键词
     * @param sourceFilter 来源过滤（可选，如 "webchat"）
     * @param limit 返回结果上限
     */
    public List<CrossSessionSearchResult> searchMessages(String query, String sourceFilter, int limit) {
        String sql;
        Object[] params;

        if (sourceFilter != null && !sourceFilter.isBlank()) {
            sql = """
                SELECT m.session_id, m.role, 
                       snippet(messages_fts, 0, '«', '»', '...', 20) as snippet,
                       bm25(messages_fts) as score,
                       m.timestamp
                FROM messages_fts fts
                JOIN messages m ON m.id = fts.rowid
                JOIN sessions s ON s.id = m.session_id
                WHERE messages_fts MATCH ?
                AND s.source = ?
                ORDER BY score
                LIMIT ?
                """;
            params = new Object[]{query, sourceFilter, limit};
        } else {
            sql = """
                SELECT m.session_id, m.role,
                       snippet(messages_fts, 0, '«', '»', '...', 20) as snippet,
                       bm25(messages_fts) as score,
                       m.timestamp
                FROM messages_fts fts
                JOIN messages m ON m.id = fts.rowid
                WHERE messages_fts MATCH ?
                ORDER BY score
                LIMIT ?
                """;
            params = new Object[]{query, limit};
        }

        try {
            return jdbcTemplate.query(sql, searchResultRowMapper, params);
        } catch (Exception e) {
            log.warn("[CrossSessionSearch] FTS5 搜索失败，回退到 LIKE: {}", e.getMessage());
            return searchMessagesLike(query, sourceFilter, limit);
        }
    }

    /**
     * LIKE 回退搜索
     */
    private List<CrossSessionSearchResult> searchMessagesLike(String query, String sourceFilter, int limit) {
        String likePattern = "%" + query + "%";
        String sql;
        Object[] params;

        if (sourceFilter != null && !sourceFilter.isBlank()) {
            sql = """
                SELECT m.session_id, m.role, 
                       substr(m.content, 1, 200) as snippet,
                       0.0 as score,
                       m.timestamp
                FROM messages m
                JOIN sessions s ON s.id = m.session_id
                WHERE m.content LIKE ?
                AND s.source = ?
                ORDER BY m.timestamp DESC
                LIMIT ?
                """;
            params = new Object[]{likePattern, sourceFilter, limit};
        } else {
            sql = """
                SELECT m.session_id, m.role,
                       substr(m.content, 1, 200) as snippet,
                       0.0 as score,
                       m.timestamp
                FROM messages m
                WHERE m.content LIKE ?
                ORDER BY m.timestamp DESC
                LIMIT ?
                """;
            params = new Object[]{likePattern, limit};
        }

        return jdbcTemplate.query(sql, searchResultRowMapper, params);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 会话链追踪
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 获取会话链（从当前会话向上追溯所有父会话）
     *
     * @param sessionId 起始会话 ID
     * @return 会话链（从最早到最近）
     */
    public List<EnhancedSessionSummary> getSessionChain(String sessionId) {
        List<EnhancedSessionSummary> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();  // 防止循环引用
        String currentId = sessionId;

        while (currentId != null && !visited.contains(currentId)) {
            visited.add(currentId);
            EnhancedSessionSummary summary = findSummaryById(currentId);
            if (summary != null) {
                chain.add(summary);
                currentId = summary.getParentSessionId();
            } else {
                break;
            }
        }

        // 反转：从最早到最近
        Collections.reverse(chain);
        return chain;
    }

    /**
     * 获取会话链的完整上下文（合并所有摘要）
     *
     * @param sessionId 起始会话 ID
     * @return 合并后的上下文文本
     */
    public String getChainContext(String sessionId) {
        List<EnhancedSessionSummary> chain = getSessionChain(sessionId);
        if (chain.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## 会话链上下文\n\n");

        for (int i = 0; i < chain.size(); i++) {
            EnhancedSessionSummary s = chain.get(i);
            sb.append(String.format("### 会话 %d: %s\n", i + 1,
                    s.getTitle() != null ? s.getTitle() : s.getSessionId()));
            sb.append(s.toFormattedText());
        }

        return sb.toString();
    }

    /**
     * 查找子会话（由当前会话派生的会话）
     *
     * @param parentId 父会话 ID
     * @return 子会话列表
     */
    public List<EnhancedSessionSummary> findChildSessions(String parentId) {
        String sql = """
            SELECT * FROM session_summaries
            WHERE parent_session_id = ?
            ORDER BY created_at DESC
            """;

        try {
            return jdbcTemplate.query(sql, summaryRowMapper, parentId);
        } catch (Exception e) {
            log.warn("[CrossSessionSearch] 查找子会话失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 会话摘要 CRUD
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 保存或更新会话摘要
     */
    public void saveSummary(EnhancedSessionSummary summary) {
        // 先尝试更新
        String updateSql = """
            UPDATE session_summaries SET
                parent_session_id = ?, title = ?, last_active_at = ?,
                active_task = ?, goal = ?, constraints = ?,
                completed_actions = ?, active_state = ?, in_progress = ?,
                blocked = ?, key_decisions = ?, resolved_questions = ?,
                pending_user_asks = ?, relevant_files = ?, remaining_work = ?,
                critical_context = ?, importance = ?,
                linked_memory_ids = ?, linked_session_ids = ?,
                extracted_from_long_term = ?
            WHERE session_id = ?
            """;

        int updated = jdbcTemplate.update(updateSql,
                summary.getParentSessionId(),
                summary.getTitle(),
                summary.getLastActiveAt(),
                summary.getActiveTask(),
                summary.getGoal(),
                summary.getConstraints(),
                summary.getCompletedActions(),
                summary.getActiveState(),
                summary.getInProgress(),
                summary.getBlocked(),
                summary.getKeyDecisions(),
                summary.getResolvedQuestions(),
                summary.getPendingUserAsks(),
                summary.getRelevantFiles(),
                summary.getRemainingWork(),
                summary.getCriticalContext(),
                summary.getImportance(),
                summary.getLinkedMemoryIds() != null ?
                        String.join(",", summary.getLinkedMemoryIds()) : null,
                summary.getLinkedSessionIds() != null ?
                        String.join(",", summary.getLinkedSessionIds()) : null,
                summary.isExtractedFromLongTerm(),
                summary.getSessionId()
        );

        // 如果没有更新到，则插入
        if (updated == 0) {
            String insertSql = """
                INSERT INTO session_summaries (
                    session_id, parent_session_id, title, created_at, last_active_at,
                    active_task, goal, constraints, completed_actions, active_state,
                    in_progress, blocked, key_decisions, resolved_questions,
                    pending_user_asks, relevant_files, remaining_work, critical_context,
                    importance, linked_memory_ids, linked_session_ids, extracted_from_long_term
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

            jdbcTemplate.update(insertSql,
                    summary.getSessionId(),
                    summary.getParentSessionId(),
                    summary.getTitle(),
                    summary.getCreatedAt(),
                    summary.getLastActiveAt(),
                    summary.getActiveTask(),
                    summary.getGoal(),
                    summary.getConstraints(),
                    summary.getCompletedActions(),
                    summary.getActiveState(),
                    summary.getInProgress(),
                    summary.getBlocked(),
                    summary.getKeyDecisions(),
                    summary.getResolvedQuestions(),
                    summary.getPendingUserAsks(),
                    summary.getRelevantFiles(),
                    summary.getRemainingWork(),
                    summary.getCriticalContext(),
                    summary.getImportance(),
                    summary.getLinkedMemoryIds() != null ?
                            String.join(",", summary.getLinkedMemoryIds()) : null,
                    summary.getLinkedSessionIds() != null ?
                            String.join(",", summary.getLinkedSessionIds()) : null,
                    summary.isExtractedFromLongTerm()
            );
        }

        log.debug("[CrossSessionSearch] 保存摘要: {}", summary.getSessionId());
    }

    /**
     * 根据 ID 查询摘要
     */
    public EnhancedSessionSummary findSummaryById(String sessionId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM session_summaries WHERE session_id = ?",
                    summaryRowMapper, sessionId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 查找所有摘要（按重要性排序）
     */
    public List<EnhancedSessionSummary> findAllSummaries(int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM session_summaries ORDER BY importance DESC, created_at DESC LIMIT ?",
                summaryRowMapper, limit);
    }

    /**
     * 按关键词搜索摘要
     */
    public List<EnhancedSessionSummary> searchSummaries(String query, int limit) {
        String likePattern = "%" + query + "%";
        String sql = """
            SELECT * FROM session_summaries
            WHERE active_task LIKE ?
               OR goal LIKE ?
               OR key_decisions LIKE ?
               OR critical_context LIKE ?
               OR title LIKE ?
            ORDER BY importance DESC, created_at DESC
            LIMIT ?
            """;

        try {
            return jdbcTemplate.query(sql, summaryRowMapper,
                    likePattern, likePattern, likePattern, likePattern, likePattern, limit);
        } catch (Exception e) {
            log.warn("[CrossSessionSearch] 搜索摘要失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 记忆 ↔ 会话关联
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 查找与指定会话关联的长期记忆
     */
    public List<LongTermMemory> findLinkedMemories(String sessionId) {
        // 方式1：通过 source_session_id 查找
        List<LongTermMemory> directMemories = memoryDao.findBySessionId(sessionId);

        // 方式2：通过摘要中的 linked_memory_ids 查找
        EnhancedSessionSummary summary = findSummaryById(sessionId);
        if (summary != null && summary.getLinkedMemoryIds() != null) {
            for (String memId : summary.getLinkedMemoryIds()) {
                memoryDao.findById(memId).ifPresent(mem -> {
                    if (directMemories.stream().noneMatch(m -> m.getId().equals(memId))) {
                        directMemories.add(mem);
                    }
                });
            }
        }

        return directMemories;
    }

    /**
     * 查找包含指定记忆的所有会话
     */
    public List<EnhancedSessionSummary> findSessionsByMemory(String memoryId) {
        String sql = """
            SELECT * FROM session_summaries
            WHERE linked_memory_ids LIKE ?
               OR ? IN (SELECT id FROM long_term_memories WHERE source_session_id = session_summaries.session_id)
            ORDER BY created_at DESC
            """;

        try {
            return jdbcTemplate.query(sql, summaryRowMapper,
                    "%" + memoryId + "%", memoryId);
        } catch (Exception e) {
            log.warn("[CrossSessionSearch] 查找记忆关联会话失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 混合搜索（FTS5 + 记忆 + 摘要）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 混合搜索：同时搜索消息、长期记忆、会话摘要
     *
     * @param query 搜索关键词
     * @param userId 用户 ID
     * @param limit 每类结果上限
     * @return 混合搜索结果
     */
    public HybridSearchResult hybridSearch(String query, String userId, int limit) {
        HybridSearchResult result = new HybridSearchResult();

        // 1. 消息搜索
        result.setMessages(searchMessages(query, limit));

        // 2. 长期记忆搜索
        result.setMemories(memoryDao.searchFts(query, userId, limit));

        // 3. 会话摘要搜索
        result.setSummaries(searchSummaries(query, limit));

        return result;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 数据类
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 跨会话搜索结果
     */
    public static class CrossSessionSearchResult {
        private String sessionId;
        private String role;
        private String snippet;
        private double score;
        private long timestamp;

        // Getters & Setters
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getSnippet() { return snippet; }
        public void setSnippet(String snippet) { this.snippet = snippet; }
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sessionId", sessionId);
            map.put("role", role);
            map.put("snippet", snippet);
            map.put("score", String.format("%.4f", score));
            map.put("timestamp", timestamp);
            return map;
        }
    }

    /**
     * 混合搜索结果
     */
    public static class HybridSearchResult {
        private List<CrossSessionSearchResult> messages = new ArrayList<>();
        private List<LongTermMemory> memories = new ArrayList<>();
        private List<EnhancedSessionSummary> summaries = new ArrayList<>();

        // Getters & Setters
        public List<CrossSessionSearchResult> getMessages() { return messages; }
        public void setMessages(List<CrossSessionSearchResult> messages) { this.messages = messages; }
        public List<LongTermMemory> getMemories() { return memories; }
        public void setMemories(List<LongTermMemory> memories) { this.memories = memories; }
        public List<EnhancedSessionSummary> getSummaries() { return summaries; }
        public void setSummaries(List<EnhancedSessionSummary> summaries) { this.summaries = summaries; }

        public boolean isEmpty() {
            return messages.isEmpty() && memories.isEmpty() && summaries.isEmpty();
        }

        public int totalCount() {
            return messages.size() + memories.size() + summaries.size();
        }
    }
}