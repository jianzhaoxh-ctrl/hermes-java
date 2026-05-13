package com.hermes.agent.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话 Transcript 管理器
 *
 * 管理每个会话的消息历史（transcript），参考 Python 版 session.py 实现。
 *
 * 功能：
 * - 加载/追加/重写会话消息历史
 * - 基于 SQLite 嵌入式数据库持久化（无需外部依赖）
 * - 自动清理过期会话
 * - 内存缓存 + 磁盘持久化双层架构
 *
 * 与 Python 版的差异：
 * - 追加粒度：每条消息独立存储，支持重写
 */
public class TranscriptManager {
    private static final Logger log = LoggerFactory.getLogger(TranscriptManager.class);

    private static final String CREATE_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS transcript_entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_key TEXT NOT NULL,
            session_id TEXT NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at TEXT NOT NULL,
            metadata TEXT
        )
    """;

    private static final String CREATE_INDEXES_SQL = """
        CREATE INDEX IF NOT EXISTS idx_transcript_session_key ON transcript_entries (session_key);
        CREATE INDEX IF NOT EXISTS idx_transcript_session_id ON transcript_entries (session_id)
    """;

    private static final String INSERT_SQL = """
        INSERT INTO transcript_entries (session_key, session_id, role, content, created_at, metadata)
        VALUES (?, ?, ?, ?, ?, ?)
    """;

    private static final String LOAD_SQL = """
        SELECT role, content, created_at, metadata FROM transcript_entries
        WHERE session_id = ? ORDER BY id ASC
    """;

    private static final String DELETE_SESSION_SQL = """
        DELETE FROM transcript_entries WHERE session_id = ?
    """;

    private static final String DELETE_KEY_SQL = """
        DELETE FROM transcript_entries WHERE session_key = ?
    """;

    private static final String PRUNE_SQL = """
        DELETE FROM transcript_entries WHERE session_key IN (
            SELECT DISTINCT session_key FROM transcript_entries
            GROUP BY session_key HAVING MAX(created_at) < ?
        )
    """;

    private static final String COUNT_SQL = """
        SELECT COUNT(*) FROM transcript_entries WHERE session_id = ?
    """;

    // 内存缓存
    private final Map<String, List<TranscriptEntry>> cache = new ConcurrentHashMap<>();

    // H2 数据库连接
    private Connection dbConnection;
    private final String dbPath;
    private final int maxCachedSessions;

    public TranscriptManager(String dbPath, int maxCachedSessions) {
        this.dbPath = dbPath;
        this.maxCachedSessions = maxCachedSessions;
    }

    public TranscriptManager() {
        this(System.getProperty("user.home") + "/.hermes/data/transcript", 100);
    }

    /**
     * 初始化数据库连接
     */
    public synchronized void init() throws SQLException {
        String jdbcUrl = "jdbc:sqlite:" + dbPath;
        dbConnection = DriverManager.getConnection(jdbcUrl, "sa", "");

        try (Statement stmt = dbConnection.createStatement()) {
            stmt.execute(CREATE_TABLE_SQL);
            for (String ddl : CREATE_INDEXES_SQL.split(";")) {
                if (!ddl.trim().isEmpty()) {
                    stmt.execute(ddl.trim());
                }
            }
        }

        log.info("TranscriptManager initialized with DB: {}", dbPath);
    }

    /**
     * 关闭数据库连接
     */
    public synchronized void shutdown() {
        if (dbConnection != null) {
            try {
                dbConnection.close();
            } catch (SQLException e) {
                log.warn("Error closing transcript DB: {}", e.getMessage());
            }
        }
        cache.clear();
    }

    /**
     * 加载会话 transcript
     *
     * @param sessionId 会话 ID
     * @return 消息列表
     */
    public List<TranscriptEntry> loadTranscript(String sessionId) {
        // 先查缓存
        List<TranscriptEntry> cached = cache.get(sessionId);
        if (cached != null) {
            return new ArrayList<>(cached);
        }

        // 从数据库加载
        List<TranscriptEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = dbConnection.prepareStatement(LOAD_SQL)) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                entries.add(new TranscriptEntry(
                    rs.getString("role"),
                    rs.getString("content"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("metadata")
                ));
            }
        } catch (SQLException e) {
            log.error("Error loading transcript for session {}: {}", sessionId, e.getMessage());
        }

        // 写入缓存
        if (!entries.isEmpty()) {
            evictCacheIfNeeded();
            cache.put(sessionId, entries);
        }

        return entries;
    }

    /**
     * 追加消息到 transcript
     *
     * @param sessionKey 会话键
     * @param sessionId 会话 ID
     * @param role 角色 (user/assistant/system/tool)
     * @param content 消息内容
     */
    public void appendToTranscript(String sessionKey, String sessionId, String role, String content) {
        appendToTranscript(sessionKey, sessionId, role, content, null);
    }

    /**
     * 追加消息到 transcript（带元数据）
     */
    public void appendToTranscript(String sessionKey, String sessionId, String role, String content, String metadata) {
        Instant now = Instant.now();

        // 写入数据库
        try (PreparedStatement ps = dbConnection.prepareStatement(INSERT_SQL)) {
            ps.setString(1, sessionKey);
            ps.setString(2, sessionId);
            ps.setString(3, role);
            ps.setString(4, content);
            ps.setTimestamp(5, Timestamp.from(now));
            ps.setString(6, metadata);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Error appending to transcript: {}", e.getMessage());
        }

        // 更新缓存
        TranscriptEntry entry = new TranscriptEntry(role, content, now, metadata);
        cache.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(entry);
    }

    /**
     * 重写整个 transcript（替换所有消息）
     *
     * 用于会话压缩/摘要后替换历史记录。
     */
    public void rewriteTranscript(String sessionKey, String sessionId, List<TranscriptEntry> newEntries) {
        // 删除旧记录
        try (PreparedStatement ps = dbConnection.prepareStatement(DELETE_SESSION_SQL)) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Error deleting old transcript: {}", e.getMessage());
            return;
        }

        // 插入新记录
        Instant now = Instant.now();
        try (PreparedStatement ps = dbConnection.prepareStatement(INSERT_SQL)) {
            for (TranscriptEntry entry : newEntries) {
                ps.setString(1, sessionKey);
                ps.setString(2, sessionId);
                ps.setString(3, entry.role());
                ps.setString(4, entry.content());
                ps.setTimestamp(5, Timestamp.from(entry.timestamp() != null ? entry.timestamp() : now));
                ps.setString(6, entry.metadata());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.error("Error rewriting transcript: {}", e.getMessage());
        }

        // 更新缓存
        cache.put(sessionId, new ArrayList<>(newEntries));
    }

    /**
     * 获取会话消息数量
     */
    public int getMessageCount(String sessionId) {
        try (PreparedStatement ps = dbConnection.prepareStatement(COUNT_SQL)) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.error("Error counting transcript messages: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 删除会话的所有 transcript
     */
    public void deleteTranscript(String sessionId) {
        cache.remove(sessionId);
        try (PreparedStatement ps = dbConnection.prepareStatement(DELETE_SESSION_SQL)) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Error deleting transcript: {}", e.getMessage());
        }
    }

    /**
     * 删除会话键下的所有 transcript
     */
    public void deleteTranscriptByKey(String sessionKey) {
        // 清除相关缓存
        cache.entrySet().removeIf(e -> {
            List<TranscriptEntry> entries = e.getValue();
            return !entries.isEmpty();
        });

        try (PreparedStatement ps = dbConnection.prepareStatement(DELETE_KEY_SQL)) {
            ps.setString(1, sessionKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Error deleting transcript by key: {}", e.getMessage());
        }
    }

    /**
     * 清理过期会话
     *
     * @param maxAgeDays 最大保留天数
     * @return 清理的会话数
     */
    public int pruneOldEntries(int maxAgeDays) {
        Instant cutoff = Instant.now().minus(maxAgeDays, ChronoUnit.DAYS);
        try (PreparedStatement ps = dbConnection.prepareStatement(PRUNE_SQL)) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.info("Pruned {} old transcript entries (older than {} days)", deleted, maxAgeDays);
            }
            return deleted;
        } catch (SQLException e) {
            log.error("Error pruning old transcripts: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 缓存驱逐
     */
    private void evictCacheIfNeeded() {
        if (cache.size() >= maxCachedSessions) {
            // 移除最早添加的一半缓存
            int toRemove = maxCachedSessions / 2;
            Iterator<String> it = cache.keySet().iterator();
            int removed = 0;
            while (it.hasNext() && removed < toRemove) {
                it.next();
                it.remove();
                removed++;
            }
            log.debug("Evicted {} cached transcripts", removed);
        }
    }

    /**
     * Transcript 条目记录
     */
    public record TranscriptEntry(
        String role,
        String content,
        Instant timestamp,
        String metadata
    ) {}
}
