package com.hermes.agent.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQLite + FTS5 持久化后端（增强版）
 *
 * <p>新增特性：
 * <ul>
 *   <li>Schema 迁移系统（v1 → v6）</li>
 *   <li>计费字段追踪</li>
 *   <li>压缩链支持</li>
 *   <li>会话标题管理</li>
 *   <li>来源过滤增强</li>
 *   <li>搜索上下文窗口</li>
 * </ul>
 */
@Component("sqliteBackend")
public class SQLiteBackend implements PersistenceBackend {

    // 使用接口中的 SearchResult 类型

    private static final Logger log = LoggerFactory.getLogger(SQLiteBackend.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private HikariDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SchemaMigrator migrator;

    private final String dbPath;
    private final int poolSize;

    // ═══════════════════════════════════════════════════════════════════
    // Row Mappers
    // ═══════════════════════════════════════════════════════════════════

    private final RowMapper<Map<String, Object>> messageRowMapper = (rs, rowNum) -> {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", rs.getLong("id"));
        msg.put("sessionId", rs.getString("session_id"));
        msg.put("role", rs.getString("role"));
        msg.put("content", rs.getString("content"));
        msg.put("toolCallId", rs.getString("tool_call_id"));
        msg.put("toolName", rs.getString("tool_name"));
        msg.put("timestamp", rs.getLong("timestamp"));

        // 解析 JSON 字段
        parseJsonField(rs, "tool_calls", msg, "toolCalls", List.class);
        parseJsonField(rs, "reasoning_details", msg, "reasoningDetails", Map.class);
        parseJsonField(rs, "codex_reasoning_items", msg, "codexReasoningItems", List.class);

        // 推理文本字段
        String reasoning = rs.getString("reasoning");
        if (reasoning != null && !reasoning.isBlank()) {
            msg.put("reasoning", reasoning);
        }

        return msg;
    };

    private final RowMapper<SearchResult> searchResultRowMapper = (rs, rowNum) ->
            new SearchResult(
                    String.valueOf(rs.getLong("id")),
                    rs.getString("session_id"),
                    rs.getString("role"),
                    rs.getString("snippet"),
                    rs.getDouble("score"),
                    rs.getLong("timestamp")
            );

    private final RowMapper<Map<String, Object>> sessionRichRowMapper = (rs, rowNum) -> {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", rs.getString("id"));
        session.put("source", rs.getString("source"));
        session.put("userId", rs.getString("user_id"));
        session.put("model", rs.getString("model"));
        session.put("title", rs.getString("title"));
        session.put("startedAt", rs.getLong("started_at"));
        session.put("endedAt", rs.getObject("ended_at") != null ? rs.getLong("ended_at") : null);
        session.put("endReason", rs.getString("end_reason"));
        session.put("parentSessionId", rs.getString("parent_session_id"));
        session.put("messageCount", rs.getInt("message_count"));
        session.put("toolCallCount", rs.getInt("tool_call_count"));
        session.put("preview", rs.getString("preview"));
        session.put("lastActive", rs.getObject("last_active") != null ? rs.getLong("last_active") : null);
        return session;
    };

    private final RowMapper<Map<String, Object>> sessionRowMapper = (rs, rowNum) -> {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", rs.getString("id"));
        session.put("source", rs.getString("source"));
        session.put("userId", rs.getString("user_id"));
        session.put("model", rs.getString("model"));
        session.put("title", rs.getString("title"));
        session.put("startedAt", rs.getLong("started_at"));
        session.put("endedAt", rs.getObject("ended_at") != null ? rs.getLong("ended_at") : null);
        session.put("endReason", rs.getString("end_reason"));
        session.put("parentSessionId", rs.getString("parent_session_id"));

        // Token 计数
        session.put("messageCount", rs.getInt("message_count"));
        session.put("toolCallCount", rs.getInt("tool_call_count"));
        session.put("inputTokens", rs.getInt("input_tokens"));
        session.put("outputTokens", rs.getInt("output_tokens"));
        session.put("cacheReadTokens", rs.getInt("cache_read_tokens"));
        session.put("cacheWriteTokens", rs.getInt("cache_write_tokens"));
        session.put("reasoningTokens", rs.getInt("reasoning_tokens"));

        // 成本
        session.put("estimatedCostUsd", rs.getObject("estimated_cost_usd") != null ? rs.getDouble("estimated_cost_usd") : null);
        session.put("actualCostUsd", rs.getObject("actual_cost_usd") != null ? rs.getDouble("actual_cost_usd") : null);
        session.put("costStatus", rs.getString("cost_status"));

        return session;
    };

    // ═══════════════════════════════════════════════════════════════════
    // 初始化与关闭
    // ═══════════════════════════════════════════════════════════════════

    public SQLiteBackend() {
        this.dbPath = System.getProperty("user.home") + "/.hermes/hermes.db";
        this.poolSize = 5;
    }

    public SQLiteBackend(String dbPath, int poolSize) {
        this.dbPath = dbPath;
        this.poolSize = poolSize;
    }

    @PostConstruct
    @Override
    public void initialize() {
        try {
            Path dbFile = Paths.get(dbPath);
            Files.createDirectories(dbFile.getParent());

            // 配置 HikariCP
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:" + dbPath);
            config.setDriverClassName("org.sqlite.JDBC");
            config.setMaximumPoolSize(poolSize);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(30000);
            config.setIdleTimeout(600000);
            config.setMaxLifetime(1800000);
            config.setPoolName("Hermes-SQLite-Pool");

            // SQLite 优化配置
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("synchronous", "NORMAL");
            config.addDataSourceProperty("foreign_keys", "ON");
            config.addDataSourceProperty("busy_timeout", "30000");

            this.dataSource = new HikariDataSource(config);
            this.jdbcTemplate = new JdbcTemplate(dataSource);
            this.migrator = new SchemaMigrator(jdbcTemplate);

            // 执行迁移
            migrator.migrate();

            // 确保 FTS5 表存在
            migrator.ensureFtsTable();

            log.info("[SQLiteBackend] 初始化完成: {} (v{}, poolSize={})",
                    dbPath, migrator.getCurrentVersion(), poolSize);
        } catch (Exception e) {
            log.error("[SQLiteBackend] 初始化失败: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize SQLite backend", e);
        }
    }

    @PreDestroy
    @Override
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            log.info("[SQLiteBackend] 关闭连接池...");
            dataSource.close();
        }
    }

    private void parseJsonField(ResultSet rs, String column, Map<String, Object> target,
                                 String targetKey, Class<?> type) throws SQLException {
        String json = rs.getString(column);
        if (json != null && !json.isBlank()) {
            try {
                target.put(targetKey, mapper.readValue(json, type));
            } catch (JsonProcessingException e) {
                log.debug("Failed to parse {}: {}", column, e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 会话生命周期
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 创建会话（增强版）
     */
    public void createSession(String sessionId, String source, String userId, String model,
                              String modelConfig, String systemPrompt, String parentSessionId) {
        String sql = """
            INSERT OR IGNORE INTO sessions
            (id, source, user_id, model, model_config, system_prompt, parent_session_id, started_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        jdbcTemplate.update(sql, sessionId, source, userId, model,
                modelConfig, systemPrompt, parentSessionId, Instant.now().getEpochSecond());
        log.debug("[SQLiteBackend] 创建会话: {} (source={})", sessionId, source);
    }

    /**
     * 结束会话
     */
    public void endSession(String sessionId, String endReason) {
        String sql = """
            UPDATE sessions SET ended_at = ?, end_reason = ?
            WHERE id = ? AND ended_at IS NULL
            """;
        jdbcTemplate.update(sql, Instant.now().getEpochSecond(), endReason, sessionId);
        log.debug("[SQLiteBackend] 结束会话: {} (reason={})", sessionId, endReason);
    }

    /**
     * 重新打开会话
     */
    public void reopenSession(String sessionId) {
        String sql = "UPDATE sessions SET ended_at = NULL, end_reason = NULL WHERE id = ?";
        jdbcTemplate.update(sql, sessionId);
    }

    /**
     * 获取会话详情
     */
    public Optional<Map<String, Object>> getSession(String sessionId) {
        String sql = "SELECT * FROM sessions WHERE id = ?";
        try {
            return Optional.of(jdbcTemplate.queryForObject(sql, sessionRowMapper, sessionId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 获取所有会话 ID
     */
    @Override
    public List<String> getAllSessionIds() {
        String sql = "SELECT id FROM sessions ORDER BY started_at DESC";
        return jdbcTemplate.queryForList(sql, String.class);
    }

    /**
     * 获取压缩链末端（最新续接会话）
     */
    public String getCompressionTip(String sessionId) {
        String current = sessionId;
        for (int i = 0; i < 100; i++) {  // 防御性上限
            String sql = """
                SELECT id FROM sessions
                WHERE parent_session_id = ?
                  AND started_at >= (
                      SELECT ended_at FROM sessions
                      WHERE id = ? AND end_reason = 'compression'
                  )
                ORDER BY started_at DESC LIMIT 1
                """;
            try {
                String next = jdbcTemplate.queryForObject(sql, String.class, current, current);
                if (next == null) {
                    return current;
                }
                current = next;
            } catch (EmptyResultDataAccessException e) {
                return current;
            }
        }
        return current;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 会话标题管理
    // ═══════════════════════════════════════════════════════════════════

    private static final int MAX_TITLE_LENGTH = 100;

    /**
     * 设置会话标题
     */
    public boolean setSessionTitle(String sessionId, String title) {
        title = sanitizeTitle(title);
        if (title == null) {
            // 清空标题
            String sql = "UPDATE sessions SET title = NULL WHERE id = ?";
            return jdbcTemplate.update(sql, sessionId) > 0;
        }

        // 检查唯一性
        String checkSql = "SELECT id FROM sessions WHERE title = ? AND id != ?";
        try {
            String conflict = jdbcTemplate.queryForObject(checkSql, String.class, title, sessionId);
            throw new IllegalArgumentException("Title '" + title + "' is already in use by session " + conflict);
        } catch (EmptyResultDataAccessException e) {
            // 无冲突，继续
        }

        String sql = "UPDATE sessions SET title = ? WHERE id = ?";
        return jdbcTemplate.update(sql, title, sessionId) > 0;
    }

    /**
     * 获取会话标题
     */
    public String getSessionTitle(String sessionId) {
        String sql = "SELECT title FROM sessions WHERE id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, String.class, sessionId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 按标题查找会话
     */
    public Optional<Map<String, Object>> getSessionByTitle(String title) {
        String sql = "SELECT * FROM sessions WHERE title = ?";
        try {
            return Optional.of(jdbcTemplate.queryForObject(sql, sessionRowMapper, title));
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 生成下一个标题（自动编号）
     */
    public String getNextTitleInLineage(String baseTitle) {
        // 去除现有编号后缀
        String base = baseTitle.replaceFirst(" #\\d+$", "");

        // 查找所有匹配的标题
        String sql = "SELECT title FROM sessions WHERE title = ? OR title LIKE ? ESCAPE '\\'";
        String pattern = base.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + " #%";
        List<String> existing = jdbcTemplate.queryForList(sql, String.class, base, pattern);

        if (existing.isEmpty()) {
            return base;
        }

        // 找最大编号
        int maxNum = 1;
        for (String t : existing) {
            if (t.equals(base)) {
                maxNum = Math.max(maxNum, 1);
            } else if (t.matches(".* #\\d+$")) {
                int num = Integer.parseInt(t.replaceFirst(".* #(\\d+)$", "$1"));
                maxNum = Math.max(maxNum, num);
            }
        }

        return base + " #" + (maxNum + 1);
    }

    /**
     * 标题清理
     */
    private String sanitizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }

        // 移除控制字符
        String cleaned = title.replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]", "");
        // 移除零宽字符
        cleaned = cleaned.replaceAll("[\\u200b-\\u200f\\u2028-\\u202e\\u2060-\\u2069\\ufeff]", "");
        // 合并空白
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        if (cleaned.isEmpty()) {
            return null;
        }

        if (cleaned.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("Title too long (" + cleaned.length() + " chars, max " + MAX_TITLE_LENGTH + ")");
        }

        return cleaned;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 消息存储
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void saveMessage(String sessionId, Map<String, Object> message) {
        ensureSessionExists(sessionId);

        String sql = """
            INSERT INTO messages
            (session_id, role, content, tool_call_id, tool_calls, tool_name,
             timestamp, token_count, finish_reason, reasoning, reasoning_details, codex_reasoning_items)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        String role = (String) message.getOrDefault("role", "user");
        String content = (String) message.get("content");
        String toolCallId = (String) message.get("toolCallId");
        String toolName = (String) message.get("toolName");
        Long timestamp = (Long) message.getOrDefault("timestamp", Instant.now().getEpochSecond());
        Integer tokenCount = (Integer) message.get("tokenCount");
        String finishReason = (String) message.get("finishReason");
        String reasoning = (String) message.get("reasoning");

        String toolCallsJson = toJson(message.get("toolCalls"));
        String reasoningDetailsJson = toJson(message.get("reasoningDetails"));
        String codexItemsJson = toJson(message.get("codexReasoningItems"));

        jdbcTemplate.update(sql, sessionId, role, content, toolCallId, toolCallsJson, toolName,
                timestamp, tokenCount, finishReason, reasoning, reasoningDetailsJson, codexItemsJson);

        updateSessionStats(sessionId, role, message.get("toolCalls"));
    }

    @Async("taskExecutor")
    @Override
    public void saveMessageAsync(String sessionId, Map<String, Object> message) {
        saveMessage(sessionId, message);
    }

    /**
     * 更新会话统计
     */
    private void updateSessionStats(String sessionId, String role, Object toolCalls) {
        int toolCallCount = 0;
        if (toolCalls instanceof List<?> list) {
            toolCallCount = list.size();
        }

        if (toolCallCount > 0) {
            String sql = """
                UPDATE sessions SET
                    message_count = message_count + 1,
                    tool_call_count = tool_call_count + ?
                WHERE id = ?
                """;
            jdbcTemplate.update(sql, toolCallCount, sessionId);
        } else {
            String sql = "UPDATE sessions SET message_count = message_count + 1 WHERE id = ?";
            jdbcTemplate.update(sql, sessionId);
        }
    }

    /**
     * 更新 Token 计数
     */
    public void updateTokenCounts(String sessionId, int inputTokens, int outputTokens,
                                   int cacheReadTokens, int cacheWriteTokens, int reasoningTokens,
                                   Double estimatedCost, Double actualCost, String billingProvider) {
        String sql = """
            UPDATE sessions SET
                input_tokens = input_tokens + ?,
                output_tokens = output_tokens + ?,
                cache_read_tokens = cache_read_tokens + ?,
                cache_write_tokens = cache_write_tokens + ?,
                reasoning_tokens = reasoning_tokens + ?,
                estimated_cost_usd = COALESCE(estimated_cost_usd, 0) + COALESCE(?, 0),
                actual_cost_usd = CASE WHEN ? IS NULL THEN actual_cost_usd ELSE COALESCE(actual_cost_usd, 0) + ? END,
                billing_provider = COALESCE(billing_provider, ?)
            WHERE id = ?
            """;
        jdbcTemplate.update(sql, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens,
                reasoningTokens, estimatedCost, actualCost, actualCost, billingProvider, sessionId);
    }

    @Override
    public List<Map<String, Object>> getSessionMessages(String sessionId) {
        String sql = "SELECT * FROM messages WHERE session_id = ? ORDER BY timestamp, id";
        return jdbcTemplate.query(sql, messageRowMapper, sessionId);
    }

    @Override
    public List<Map<String, Object>> getSessionMessages(String sessionId, int limit) {
        String sql = """
            SELECT * FROM messages
            WHERE session_id = ?
            ORDER BY timestamp DESC, id DESC
            LIMIT ?
            """;
        List<Map<String, Object>> messages = jdbcTemplate.query(sql, messageRowMapper, sessionId, limit);
        Collections.reverse(messages);
        return messages;
    }

    @Override
    public void clearSession(String sessionId) {
        jdbcTemplate.update("DELETE FROM messages WHERE session_id = ?", sessionId);
        jdbcTemplate.update(
                "UPDATE sessions SET message_count = 0, tool_call_count = 0 WHERE id = ?",
                sessionId);
        log.info("[SQLiteBackend] 清空会话消息: {}", sessionId);
    }

    private void ensureSessionExists(String sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sessions WHERE id = ?", Integer.class, sessionId);
        if (count == null || count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO sessions (id, source, started_at) VALUES (?, ?, ?)",
                    sessionId, "unknown", Instant.now().getEpochSecond());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 增强搜索功能
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 全文搜索（增强版）
     *
     * @param query 搜索关键词
     * @param sourceFilter 来源过滤（如 "cli", "telegram", "discord"）
     * @param roleFilter 角色过滤（如 "user", "assistant", "tool"）
     * @param sessionId 可选的会话限定
     * @param limit 返回结果上限
     * @return 搜索结果列表
     */
    public List<SearchResult> searchMessagesEnhanced(String query, List<String> sourceFilter,
                                                      List<String> roleFilter, String sessionId, int limit) {
        return searchMessagesEnhanced(query, sourceFilter, null, roleFilter, sessionId, limit);
    }

    /**
     * 增强版搜索 — 支持 excludeSources（参考 Python 版）
     *
     * @param query           搜索关键词
     * @param sourceFilter    包含的来源列表（可选）
     * @param excludeSources  排除的来源列表（可选）
     * @param roleFilter      角色过滤（可选）
     * @param sessionId       会话 ID 限定（可选）
     * @param limit           结果上限
     * @return 搜索结果列表
     */
    public List<SearchResult> searchMessagesEnhanced(String query, List<String> sourceFilter,
                                                      List<String> excludeSources,
                                                      List<String> roleFilter, String sessionId, int limit) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        String originalQuery = query.trim();
        String ftsQuery = sanitizeFts5Query(originalQuery);
        if (ftsQuery.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建动态 WHERE 子句
        List<String> whereClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        whereClauses.add("messages_fts MATCH ?");
        params.add(ftsQuery);

        if (sourceFilter != null && !sourceFilter.isEmpty()) {
            whereClauses.add("s.source IN (" + String.join(",", Collections.nCopies(sourceFilter.size(), "?")) + ")");
            params.addAll(sourceFilter);
        }

        if (excludeSources != null && !excludeSources.isEmpty()) {
            whereClauses.add("s.source NOT IN (" + String.join(",", Collections.nCopies(excludeSources.size(), "?")) + ")");
            params.addAll(excludeSources);
        }

        if (roleFilter != null && !roleFilter.isEmpty()) {
            whereClauses.add("m.role IN (" + String.join(",", Collections.nCopies(roleFilter.size(), "?")) + ")");
            params.addAll(roleFilter);
        }

        if (sessionId != null && !sessionId.isBlank()) {
            whereClauses.add("m.session_id = ?");
            params.add(sessionId);
        }

        params.add(limit);

        String whereSql = String.join(" AND ", whereClauses);

        String sql = String.format("""
            SELECT m.id, m.session_id, m.role,
                   snippet(messages_fts, 0, '>>>', '<<<', '...', 40) AS snippet,
                   bm25(messages_fts) AS score,
                   m.timestamp
            FROM messages_fts
            JOIN messages m ON m.id = messages_fts.rowid
            JOIN sessions s ON s.id = m.session_id
            WHERE %s
            ORDER BY score
            LIMIT ?
            """, whereSql);

        List<SearchResult> results;
        try {
            results = jdbcTemplate.query(sql, searchResultRowMapper, params.toArray());
        } catch (Exception e) {
            // FTS5 查询语法错误（尽管已清理）→ 尝试 CJK 回退或直接 LIKE
            log.warn("[SQLiteBackend] FTS5 搜索失败: {}", e.getMessage());
            results = Collections.emptyList();
        }

        // CJK LIKE 回退：FTS5 默认分词器将 CJK 字符逐字拆分，多字查询会失败。
        // Java 版使用 unicode61 分词器对中文更友好，但仍可能存在边界情况。
        // 当 FTS5 返回空结果且查询包含 CJK 字符时，回退到 LIKE 搜索。
        if (results.isEmpty() && containsCjk(originalQuery)) {
            log.debug("[SQLiteBackend] FTS5 无结果且查询含 CJK，回退到 LIKE 搜索: {}", originalQuery);
            return cjkFallbackSearch(originalQuery, sourceFilter, excludeSources, roleFilter, sessionId, limit);
        }

        return results;
    }

    /**
     * 原始搜索方法（兼容接口）
     */
    @Override
    public List<SearchResult> searchMessages(String query, String sessionId, int limit) {
        return searchMessagesEnhanced(query, null, null, sessionId, limit);
    }

    /**
     * 获取消息上下文窗口（公开方法，供测试和外部调用）
     */
    public List<Map<String, String>> getContextMessages(long messageId) {
        String sql = """
            WITH target AS (
                SELECT session_id, timestamp, id
                FROM messages WHERE id = ?
            )
            SELECT role, substr(content, 1, 200) AS content
            FROM (
                SELECT m.id, m.timestamp, m.role, m.content
                FROM messages m
                JOIN target t ON t.session_id = m.session_id
                WHERE m.timestamp < t.timestamp
                   OR (m.timestamp = t.timestamp AND m.id < t.id)
                ORDER BY m.timestamp DESC, m.id DESC
                LIMIT 1
            )
            UNION ALL
            SELECT role, substr(content, 1, 200) FROM messages WHERE id = ?
            UNION ALL
            SELECT role, substr(content, 1, 200)
            FROM (
                SELECT m.id, m.timestamp, m.role, m.content
                FROM messages m
                JOIN target t ON t.session_id = m.session_id
                WHERE m.timestamp > t.timestamp
                   OR (m.timestamp = t.timestamp AND m.id > t.id)
                ORDER BY m.timestamp ASC, m.id ASC
                LIMIT 1
            )
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> Map.of(
                "role", rs.getString("role"),
                "content", rs.getString("content") != null ? rs.getString("content") : ""
        ), messageId, messageId);
    }

    /**
     * LIKE 回退搜索（增强版）
     */
    private List<SearchResult> fallbackSearchEnhanced(String query, List<String> sourceFilter,
                                                       List<String> roleFilter, String sessionId, int limit) {
        List<String> whereClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        whereClauses.add("m.content LIKE ?");
        params.add("%" + query + "%");

        if (sourceFilter != null && !sourceFilter.isEmpty()) {
            whereClauses.add("s.source IN (" + String.join(",", Collections.nCopies(sourceFilter.size(), "?")) + ")");
            params.addAll(sourceFilter);
        }

        if (roleFilter != null && !roleFilter.isEmpty()) {
            whereClauses.add("m.role IN (" + String.join(",", Collections.nCopies(roleFilter.size(), "?")) + ")");
            params.addAll(roleFilter);
        }

        if (sessionId != null && !sessionId.isBlank()) {
            whereClauses.add("m.session_id = ?");
            params.add(sessionId);
        }

        params.add(limit);

        String whereSql = String.join(" AND ", whereClauses);

        String sql = String.format("""
            SELECT m.id, m.session_id, m.role,
                   substr(m.content, max(1, instr(m.content, ?) - 40), 120) AS snippet,
                   0.5 AS score,
                   m.timestamp
            FROM messages m
            JOIN sessions s ON s.id = m.session_id
            WHERE %s
            ORDER BY m.timestamp DESC
            LIMIT ?
            """, whereSql);

        // instr 参数在最前面
        params.add(0, query);

        return jdbcTemplate.query(sql, searchResultRowMapper, params.toArray());
    }

    /**
     * CJK LIKE 回退搜索
     *
     * <p>参考 Python 版 search_messages() 中的 CJK 回退逻辑。
     * 当 FTS5 对中文查询返回空结果时，使用 LIKE 搜索作为后备。
     *
     * <p>与通用 fallbackSearchEnhanced 的区别：
     * <ul>
     *   <li>清理查询中的引号（FTS5 语法残留）</li>
     *   <li>使用 instr() 截取摘要片段</li>
     * </ul>
     */
    private List<SearchResult> cjkFallbackSearch(String query, List<String> sourceFilter,
                                                  List<String> excludeSources,
                                                  List<String> roleFilter, String sessionId, int limit) {
        // 清理 FTS5 语法残留
        String rawQuery = query.replaceAll("\"", "").trim();

        List<String> whereClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        whereClauses.add("m.content LIKE ?");
        params.add("%" + rawQuery + "%");

        if (sourceFilter != null && !sourceFilter.isEmpty()) {
            whereClauses.add("s.source IN (" + String.join(",", Collections.nCopies(sourceFilter.size(), "?")) + ")");
            params.addAll(sourceFilter);
        }

        if (excludeSources != null && !excludeSources.isEmpty()) {
            whereClauses.add("s.source NOT IN (" + String.join(",", Collections.nCopies(excludeSources.size(), "?")) + ")");
            params.addAll(excludeSources);
        }

        if (roleFilter != null && !roleFilter.isEmpty()) {
            whereClauses.add("m.role IN (" + String.join(",", Collections.nCopies(roleFilter.size(), "?")) + ")");
            params.addAll(roleFilter);
        }

        if (sessionId != null && !sessionId.isBlank()) {
            whereClauses.add("m.session_id = ?");
            params.add(sessionId);
        }

        String whereSql = String.join(" AND ", whereClauses);

        String sql = String.format("""
            SELECT m.id, m.session_id, m.role,
                   substr(m.content, max(1, instr(m.content, ?) - 40), 120) AS snippet,
                   0.5 AS score,
                   m.timestamp
            FROM messages m
            JOIN sessions s ON s.id = m.session_id
            WHERE %s
            ORDER BY m.timestamp DESC
            LIMIT ?
            """, whereSql);

        // 参数顺序: instr的查询词 + LIKE的查询词 + 其他过滤 + limit
        List<Object> finalParams = new ArrayList<>();
        finalParams.add(rawQuery);       // instr() 参数
        finalParams.add("%" + rawQuery + "%");  // LIKE 参数
        // 跳过第一个 LIKE 参数（已在上面添加）
        for (int i = 1; i < params.size(); i++) {
            finalParams.add(params.get(i));
        }
        finalParams.add(limit);

        try {
            return jdbcTemplate.query(sql, searchResultRowMapper, finalParams.toArray());
        } catch (Exception e) {
            log.warn("[SQLiteBackend] CJK LIKE 回退搜索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // FTS5 查询清理（参考 Python _sanitize_fts5_query）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * FTS5 查询清理 — 6 步精细处理
     *
     * <p>参考 Python 版 _sanitize_fts5_query() 实现，防止用户输入中的特殊字符
     * 导致 FTS5 MATCH 语法错误。
     *
     * <p>步骤：
     * <ol>
     *   <li>提取并保留平衡的双引号短语</li>
     *   <li>剥离未匹配的 FTS5 特殊字符</li>
     *   <li>合并重复 * 并移除前导 *</li>
     *   <li>移除开头/结尾的悬空布尔操作符</li>
     *   <li>将未引用的连字符/点号术语包裹在引号中</li>
     *   <li>恢复保留的引号短语</li>
     * </ol>
     *
     * @param query 原始用户查询
     * @return 清理后的安全 FTS5 查询字符串
     */
    static String sanitizeFts5Query(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        query = query.trim();
        if (query.isEmpty()) {
            return "";
        }

        // Step 1: 提取并保留平衡的双引号短语
        List<String> quotedParts = new ArrayList<>();
        // 使用 XML 风格占位符保护引号内容，避免 null 字符问题
        Pattern quotedPattern = Pattern.compile("\"[^\"]*\"");
        Matcher m = quotedPattern.matcher(query);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            quotedParts.add(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement("\u00ABQ" + (quotedParts.size() - 1) + "\u00BB"));
        }
        m.appendTail(sb);
        String sanitized = sb.toString();

        // Step 2: 剥离未匹配的 FTS5 特殊字符: + { } ( ) " ^
        sanitized = sanitized.replaceAll("[+{}\\(\\)\"\\^]", " ");

        // Step 3: 合并重复 * 并移除前导 *
        sanitized = sanitized.replaceAll("\\*+", "*");
        sanitized = sanitized.replaceAll("(^|\\s)\\*", "$1");

        // Step 4: 移除开头/结尾的悬空布尔操作符 (AND, OR, NOT)
        sanitized = sanitized.trim();
        sanitized = sanitized.replaceAll("(?i)^(AND|OR|NOT)\\b\\s*", "");
        sanitized = sanitized.replaceAll("(?i)\\s+(AND|OR|NOT)\\b\\s*$", "");

        // Step 5: 将未引用的连字符/点号术语包裹在双引号中
        // FTS5 分词器会在 . 和 - 处拆分，"chat-send" → "chat AND send"
        // 引号包裹保留短语语义
        sanitized = sanitized.replaceAll("\\b(\\w+(?:[.-]\\w+)+)\\b", "\"$1\"");

        // Step 6: 恢复保留的引号短语
        for (int i = 0; i < quotedParts.size(); i++) {
            sanitized = sanitized.replace("\u00ABQ" + i + "\u00BB", quotedParts.get(i));
        }

        return sanitized.trim();
    }

    /**
     * 检测文本是否包含 CJK（中日韩）字符
     *
     * <p>参考 Python 版 _contains_cjk() 实现。
     * CJK 字符在 FTS5 默认分词器下会被逐字拆分，
     * 导致多字查询失败，需要回退到 LIKE 搜索。
     * （注：Java 版 FTS5 使用 unicode61 分词器，对 CJK 支持更好，
     * 但仍可能在某些边界场景下需要 LIKE 回退）
     *
     * @param text 待检测文本
     * @return true 如果包含 CJK 字符
     */
    static boolean containsCjk(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            int cp = text.codePointAt(i);
            if (Character.isSupplementaryCodePoint(cp)) {
                i++; // 跳过代理对的低半部分
            }
            if (isCjkCodePoint(cp)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断单个 Unicode 码点是否为 CJK 字符
     *
     * <p>覆盖范围：
     * <ul>
     *   <li>CJK Unified Ideographs (0x4E00-0x9FFF)</li>
     *   <li>CJK Extension A (0x3400-0x4DBF)</li>
     *   <li>CJK Extension B (0x20000-0x2A6DF)</li>
     *   <li>CJK Symbols and Punctuation (0x3000-0x303F)</li>
     *   <li>Hiragana (0x3040-0x309F)</li>
     *   <li>Katakana (0x30A0-0x30FF)</li>
     *   <li>Hangul Syllables (0xAC00-0xD7AF)</li>
     * </ul>
     *
     * @param cp Unicode 码点
     * @return true 如果为 CJK 字符
     */
    static boolean isCjkCodePoint(int cp) {
        return (0x4E00 <= cp && cp <= 0x9FFF) ||   // CJK Unified Ideographs
               (0x3400 <= cp && cp <= 0x4DBF) ||   // CJK Extension A
               (0x20000 <= cp && cp <= 0x2A6DF) || // CJK Extension B
               (0x3000 <= cp && cp <= 0x303F) ||   // CJK Symbols
               (0x3040 <= cp && cp <= 0x309F) ||   // Hiragana
               (0x30A0 <= cp && cp <= 0x30FF) ||   // Katakana
               (0xAC00 <= cp && cp <= 0xD7AF);     // Hangul Syllables
    }

    /**
     * 补齐性能索引
     *
     * <p>Python 版已有的索引，Java 版之前缺失：
     * <ul>
     *   <li>idx_sessions_source — 按来源过滤会话</li>
     *   <li>idx_sessions_parent — 压缩链查询</li>
     *   <li>idx_sessions_started — 按时间排序会话</li>
     *   <li>idx_messages_session_ts — 消息按会话+时间复合查询</li>
     * </ul>
     */
    private void ensurePerformanceIndexes() {
        String[] indexSqls = {
            "CREATE INDEX IF NOT EXISTS idx_sessions_source ON sessions(source)",
            "CREATE INDEX IF NOT EXISTS idx_sessions_parent ON sessions(parent_session_id)",
            "CREATE INDEX IF NOT EXISTS idx_sessions_started ON sessions(started_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_messages_session_ts ON messages(session_id, timestamp)",
        };
        for (String sql : indexSqls) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.debug("[SQLiteBackend] 索引创建跳过（可能已存在）: {}", e.getMessage());
            }
        }
        log.info("[SQLiteBackend] 性能索引检查完成");
    }

    // ═══════════════════════════════════════════════════════════════════
    // 用户画像
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void saveUserProfile(String userId, Map<String, Object> profile) {
        try {
            String profileJson = mapper.writeValueAsString(profile);
            String sql = """
                INSERT INTO user_profiles (user_id, profile, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                    profile = excluded.profile,
                    updated_at = excluded.updated_at
                """;
            jdbcTemplate.update(sql, userId, profileJson, Instant.now().getEpochSecond());
        } catch (JsonProcessingException e) {
            log.error("[SQLiteBackend] 序列化用户画像失败: {}", e.getMessage());
        }
    }

    @Override
    public Optional<Map<String, Object>> getUserProfile(String userId) {
        String sql = "SELECT profile FROM user_profiles WHERE user_id = ?";
        try {
            String profileJson = jdbcTemplate.queryForObject(sql, String.class, userId);
            if (profileJson != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> profile = mapper.readValue(profileJson, Map.class);
                return Optional.of(profile);
            }
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        } catch (JsonProcessingException e) {
            log.warn("[SQLiteBackend] 解析用户画像失败: {}", e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public Map<String, Map<String, Object>> getAllUserProfiles() {
        String sql = "SELECT user_id, profile FROM user_profiles";
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            try {
                String userId = rs.getString("user_id");
                String profileJson = rs.getString("profile");
                @SuppressWarnings("unchecked")
                Map<String, Object> profile = mapper.readValue(profileJson, Map.class);
                result.put(userId, profile);
            } catch (JsonProcessingException e) {
                log.warn("[SQLiteBackend] 解析用户画像失败: {}", e.getMessage());
            }
        });
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 技能记忆
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void saveSkillMemory(String skillName, String content) {
        String sql = "INSERT INTO skill_memories (skill_name, content, created_at) VALUES (?, ?, ?)";
        jdbcTemplate.update(sql, skillName, content, Instant.now().getEpochSecond());
    }

    @Override
    public List<String> getSkillMemory(String skillName) {
        String sql = "SELECT content FROM skill_memories WHERE skill_name = ? ORDER BY created_at DESC";
        return jdbcTemplate.queryForList(sql, String.class, skillName);
    }

    @Override
    public Map<String, List<String>> getAllSkillMemories() {
        String sql = "SELECT skill_name, content FROM skill_memories ORDER BY created_at DESC";
        Map<String, List<String>> result = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String skillName = rs.getString("skill_name");
            String content = rs.getString("content");
            result.computeIfAbsent(skillName, k -> new ArrayList<>()).add(content);
        });
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 数据迁移
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 从 JSON 文件迁移数据到 SQLite
     */
    public void migrateFromJson(Map<String, List<Map<String, Object>>> sessionMessages,
                                 Map<String, Map<String, Object>> userProfiles,
                                 Map<String, List<String>> skillMemories) {
        log.info("[SQLiteBackend] 开始数据迁移...");

        int msgCount = 0;
        for (Map.Entry<String, List<Map<String, Object>>> entry : sessionMessages.entrySet()) {
            String sessionId = entry.getKey();
            for (Map<String, Object> msg : entry.getValue()) {
                saveMessage(sessionId, msg);
                msgCount++;
            }
        }
        log.info("[SQLiteBackend] 迁移会话消息: {} 条", msgCount);

        int profileCount = 0;
        for (Map.Entry<String, Map<String, Object>> entry : userProfiles.entrySet()) {
            saveUserProfile(entry.getKey(), entry.getValue());
            profileCount++;
        }
        log.info("[SQLiteBackend] 迁移用户画像: {} 个", profileCount);

        int skillCount = 0;
        for (Map.Entry<String, List<String>> entry : skillMemories.entrySet()) {
            for (String content : entry.getValue()) {
                saveSkillMemory(entry.getKey(), content);
                skillCount++;
            }
        }
        log.info("[SQLiteBackend] 迁移技能记忆: {} 条", skillCount);

        log.info("[SQLiteBackend] 数据迁移完成");
    }

    // ═══════════════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Expose JdbcTemplate for ACP session persistence.
     */
    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }

    /**
     * 获取当前 schema 版本
     */
    public int getSchemaVersion() {
        return migrator.getCurrentVersion();
    }

    @Override
    public String name() {
        return "sqlite";
    }

    @Override
    public boolean isAvailable() {
        return dataSource != null && !dataSource.isClosed();
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("[SQLiteBackend] JSON 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 更新会话的 started_at 时间戳（测试用）
     */
    void updateSessionTimestamp(String sessionId, long startedAt) {
        jdbcTemplate.update("UPDATE sessions SET started_at = ? WHERE id = ?", startedAt, sessionId);
    }

    // ═══════════════════════════════════════════════════════════════════
    // P1: 会话列表增强（预览 + 最后活跃 + 压缩链投影）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 增强版会话列表
     *
     * <p>参考 Python 版 list_sessions_rich() 实现，包含：
     * <ul>
     *   <li>preview: 第一条用户消息前 60 字符</li>
     *   <li>lastActive: 最后一条消息的时间戳</li>
     *   <li>压缩链投影: 根会话自动投影到最新续接</li>
     * </ul>
     *
     * @param source                   来源过滤（可选，null 表示所有来源）
     * @param limit                    返回条数上限
     * @param offset                   偏移量
     * @param projectCompressionTips   是否投影压缩链到最新续接
     * @return 会话列表
     */
    @Override
    public List<Map<String, Object>> listSessionsRich(String source, int limit, int offset,
                                                        boolean projectCompressionTips) {
        // 1. 只查根会话（parent_session_id IS NULL），压缩链投影后子会话自然可见
        List<String> whereClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        whereClauses.add("s.parent_session_id IS NULL");

        if (source != null && !source.isBlank()) {
            whereClauses.add("s.source = ?");
            params.add(source);
        }

        String whereSql = String.join(" AND ", whereClauses);
        params.add(limit);
        params.add(offset);

        String sql = String.format("""
            SELECT s.*,
                COALESCE(
                    (SELECT SUBSTR(REPLACE(REPLACE(m.content, X'0A', ' '), X'0D', ' '), 1, 63)
                     FROM messages m
                     WHERE m.session_id = s.id AND m.role = 'user' AND m.content IS NOT NULL
                     ORDER BY m.timestamp, m.id LIMIT 1),
                    ''
                ) AS _preview_raw,
                COALESCE(
                    (SELECT MAX(m2.timestamp) FROM messages m2 WHERE m2.session_id = s.id),
                    s.started_at
                ) AS last_active
            FROM sessions s
            WHERE %s
            ORDER BY s.started_at DESC
            LIMIT ? OFFSET ?
            """, whereSql);

        List<Map<String, Object>> sessions = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("id", rs.getString("id"));
            session.put("source", rs.getString("source"));
            session.put("userId", rs.getString("user_id"));
            session.put("model", rs.getString("model"));
            session.put("title", rs.getString("title"));
            session.put("startedAt", rs.getLong("started_at"));
            session.put("endedAt", rs.getObject("ended_at") != null ? rs.getLong("ended_at") : null);
            session.put("endReason", rs.getString("end_reason"));
            session.put("parentSessionId", rs.getString("parent_session_id"));
            session.put("messageCount", rs.getInt("message_count"));
            session.put("toolCallCount", rs.getInt("tool_call_count"));

            // 预览：前 60 字符
            String rawPreview = rs.getString("_preview_raw");
            if (rawPreview != null && !rawPreview.isBlank()) {
                String preview = rawPreview.length() > 60
                        ? rawPreview.substring(0, 60) + "..."
                        : rawPreview;
                session.put("preview", preview);
            } else {
                session.put("preview", "");
            }

            // 最后活跃时间
            session.put("lastActive", rs.getObject("last_active") != null ? rs.getLong("last_active") : null);

            return session;
        }, params.toArray());

        // 2. 压缩链投影：根会话 end_reason='compression' → 投影到最新续接
        if (projectCompressionTips) {
            List<Map<String, Object>> projected = new ArrayList<>();
            for (Map<String, Object> s : sessions) {
                String endReason = (String) s.get("endReason");
                if (!"compression".equals(endReason)) {
                    projected.add(s);
                    continue;
                }

                String sessionId = (String) s.get("id");
                String tipId = getCompressionTip(sessionId);
                if (tipId.equals(sessionId)) {
                    // 没有续接，保留原数据
                    projected.add(s);
                    continue;
                }

                // 获取续接会话的富数据
                Map<String, Object> tipRow = getSessionRichRow(tipId);
                if (tipRow == null) {
                    projected.add(s);
                    continue;
                }

                // 保留根的 startedAt 排序，但用 tip 的数据覆盖
                Map<String, Object> merged = new LinkedHashMap<>(s);
                for (String key : List.of("id", "endedAt", "endReason", "messageCount",
                        "toolCallCount", "title", "lastActive", "preview", "model")) {
                    if (tipRow.containsKey(key)) {
                        merged.put(key, tipRow.get(key));
                    }
                }
                merged.put("_lineageRootId", sessionId);  // 标记原始根 ID
                projected.add(merged);
            }
            sessions = projected;
        }

        return sessions;
    }

    /**
     * 获取单个会话的富数据（preview + lastActive），供压缩链投影使用
     */
    private Map<String, Object> getSessionRichRow(String sessionId) {
        String sql = """
            SELECT s.*,
                COALESCE(
                    (SELECT SUBSTR(REPLACE(REPLACE(m.content, X'0A', ' '), X'0D', ' '), 1, 63)
                     FROM messages m
                     WHERE m.session_id = s.id AND m.role = 'user' AND m.content IS NOT NULL
                     ORDER BY m.timestamp, m.id LIMIT 1),
                    ''
                ) AS _preview_raw,
                COALESCE(
                    (SELECT MAX(m2.timestamp) FROM messages m2 WHERE m2.session_id = s.id),
                    s.started_at
                ) AS last_active
            FROM sessions s
            WHERE s.id = ?
            """;
        try {
            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                Map<String, Object> session = new LinkedHashMap<>();
                session.put("id", rs.getString("id"));
                session.put("source", rs.getString("source"));
                session.put("model", rs.getString("model"));
                session.put("title", rs.getString("title"));
                session.put("startedAt", rs.getLong("started_at"));
                session.put("endedAt", rs.getObject("ended_at") != null ? rs.getLong("ended_at") : null);
                session.put("endReason", rs.getString("end_reason"));
                session.put("messageCount", rs.getInt("message_count"));
                session.put("toolCallCount", rs.getInt("tool_call_count"));

                String rawPreview = rs.getString("_preview_raw");
                if (rawPreview != null && !rawPreview.isBlank()) {
                    session.put("preview", rawPreview.length() > 60
                            ? rawPreview.substring(0, 60) + "..."
                            : rawPreview);
                } else {
                    session.put("preview", "");
                }

                session.put("lastActive", rs.getObject("last_active") != null ? rs.getLong("last_active") : null);
                return session;
            }, sessionId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // P1: 搜索结果上下文内嵌
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 增强搜索 — 返回带上下文的搜索结果
     *
     * <p>参考 Python 版 search_messages()，每个结果包含：
     * <ul>
     *   <li>snippet: FTS5 高亮摘要</li>
     *   <li>context: 匹配消息前后各 1 条消息</li>
     *   <li>不返回 content 完整内容（节省 token）</li>
     * </ul>
     */
    public List<SearchResult> searchMessagesWithContext(String query, List<String> sourceFilter,
                                                         List<String> roleFilter, String sessionId, int limit) {
        List<SearchResult> results = searchMessagesEnhanced(query, sourceFilter, roleFilter, sessionId, limit);

        // 为每个结果添加上下文窗口
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            try {
                List<Map<String, String>> context = getContextMessages(Long.parseLong(r.messageId()));
                results.set(i, new SearchResult(
                        r.messageId(), r.sessionId(), r.role(),
                        r.snippet(), r.score(), r.timestamp(), context
                ));
            } catch (Exception e) {
                log.debug("[SQLiteBackend] 获取搜索上下文失败 id={}: {}", r.messageId(), e.getMessage());
            }
        }

        return results;
    }

    // ═══════════════════════════════════════════════════════════════════
    // P1: 删除会话 + 按时间清理
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 删除会话及其消息 — 子会话孤儿化处理
     *
     * <p>参考 Python 版 delete_session()：删除前先将子会话的 parent_session_id 设为 NULL，
     * 避免外键约束冲突，同时保留子会话可独立访问。
     *
     * @param sessionId 会话 ID
     * @return true 如果会话存在并已删除
     */
    @Override
    public boolean deleteSession(String sessionId) {
        // 检查会话是否存在
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sessions WHERE id = ?", Integer.class, sessionId);
        if (count == null || count == 0) {
            return false;
        }

        // 孤儿化子会话（parent_session_id → NULL）
        jdbcTemplate.update(
                "UPDATE sessions SET parent_session_id = NULL WHERE parent_session_id = ?",
                sessionId);

        // 删除消息
        jdbcTemplate.update("DELETE FROM messages WHERE session_id = ?", sessionId);

        // 删除会话
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);

        log.info("[SQLiteBackend] 删除会话: {}（子会话已孤儿化）", sessionId);
        return true;
    }

    /**
     * 按时间清理已结束的旧会话
     *
     * <p>参考 Python 版 prune_sessions()：
     * <ul>
     *   <li>只清理 ended_at IS NOT NULL 的会话（不清理活跃会话）</li>
     *   <li>子会话孤儿化处理（不级联删除）</li>
     *   <li>返回被清理的会话数量</li>
     * </ul>
     *
     * @param olderThanDays 清理多少天前的会话
     * @param source        来源过滤（可选，null 表示所有来源）
     * @return 被清理的会话数量
     */
    @Override
    public int pruneSessions(int olderThanDays, String source) {
        long cutoff = Instant.now().getEpochSecond() - ((long) olderThanDays * 86400L);

        // 查找满足条件的会话 ID
        List<String> sessionIds;
        if (source != null && !source.isBlank()) {
            sessionIds = jdbcTemplate.queryForList(
                    "SELECT id FROM sessions WHERE started_at < ? AND ended_at IS NOT NULL AND source = ?",
                    String.class, cutoff, source);
        } else {
            sessionIds = jdbcTemplate.queryForList(
                    "SELECT id FROM sessions WHERE started_at < ? AND ended_at IS NOT NULL",
                    String.class, cutoff);
        }

        if (sessionIds.isEmpty()) {
            return 0;
        }

        // 孤儿化子会话
        String placeholders = String.join(",", Collections.nCopies(sessionIds.size(), "?"));
        jdbcTemplate.update(
                "UPDATE sessions SET parent_session_id = NULL WHERE parent_session_id IN (" + placeholders + ")",
                sessionIds.toArray());

        // 逐个删除（消息 + 会话）
        for (String sid : sessionIds) {
            jdbcTemplate.update("DELETE FROM messages WHERE session_id = ?", sid);
            jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sid);
        }

        log.info("[SQLiteBackend] 清理旧会话: {} 个（> {} 天，source={})",
                sessionIds.size(), olderThanDays, source != null ? source : "*");
        return sessionIds.size();
    }

    // SearchResult 在 PersistenceBackend 接口中定义
}
