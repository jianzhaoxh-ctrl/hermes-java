package com.hermes.agent.acp.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.agent.acp.model.ACPEventCallbacks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe manager for ACP sessions backed by the existing SQLite persistence.
 *
 * <p>Sessions are held in-memory for fast access AND persisted to the database
 * so they survive process restarts and appear in session_search.
 *
 * <p>Reference: Python acp_adapter/session.py SessionManager
 */
@Component
public class ACPSessionManager {

    private static final Logger log = LoggerFactory.getLogger(ACPSessionManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** In-memory session store */
    private final Map<String, ACPSession> sessions = new ConcurrentHashMap<>();

    /** Lock for operations that need atomicity across multiple sessions */
    private final ReentrantLock lock = new ReentrantLock();

    /** JdbcTemplate from existing SQLiteBackend — injected lazily */
    private volatile JdbcTemplate jdbcTemplate;

    /** Page size for list_sessions */
    private static final int LIST_PAGE_SIZE = 50;

    // ================================================================
    //  Public API
    // ================================================================

    /**
     * Create a new session with a unique ID.
     *
     * @param cwd working directory for the session
     * @return the newly created session state
     */
    public ACPSession createSession(String cwd) {
        String sessionId = UUID.randomUUID().toString();
        String effectiveCwd = cwd != null ? cwd : ".";
        ACPSession session = new ACPSession(sessionId, effectiveCwd, "");

        sessions.put(sessionId, session);
        persist(session);
        log.info("Created ACP session {} (cwd={})", sessionId, effectiveCwd);
        return session;
    }

    /**
     * Get a session by ID. If not in memory but exists in the database,
     * transparently restores it.
     *
     * @param sessionId the session ID
     * @return session state, or null if not found
     */
    public ACPSession getSession(String sessionId) {
        ACPSession session = sessions.get(sessionId);
        if (session != null) {
            return session;
        }
        // Attempt to restore from database
        return restore(sessionId);
    }

    /**
     * Remove a session from memory and database.
     *
     * @return true if the session existed
     */
    public boolean removeSession(String sessionId) {
        ACPSession removed = sessions.remove(sessionId);
        boolean dbExisted = deletePersisted(sessionId);
        return removed != null || dbExisted;
    }

    /**
     * Deep-copy a session's history into a new session.
     *
     * @param sessionId source session ID
     * @param cwd       working directory for the forked session
     * @return new forked session, or null if source not found
     */
    public ACPSession forkSession(String sessionId, String cwd) {
        ACPSession original = getSession(sessionId);
        if (original == null) {
            return null;
        }

        String newId = UUID.randomUUID().toString();
        String effectiveCwd = cwd != null ? cwd : ".";
        ACPSession forked = new ACPSession(newId, effectiveCwd, original.getModel());

        // Deep-copy history
        synchronized (original) {
            List<Map<String, Object>> copiedHistory = new ArrayList<>();
            for (Map<String, Object> msg : original.getHistory()) {
                copiedHistory.add(new HashMap<>(msg));
            }
            forked.setHistory(copiedHistory);
        }

        forked.setConfigOptions(new HashMap<>(original.getConfigOptions()));

        sessions.put(newId, forked);
        persist(forked);
        log.info("Forked ACP session {} -> {}", sessionId, newId);
        return forked;
    }

    /**
     * List all sessions, optionally filtered by cwd.
     *
     * @param cwd optional working directory filter
     * @return list of session info maps
     */
    public List<Map<String, Object>> listSessions(String cwd) {
        String normalizedCwd = cwd != null ? normalizeCwd(cwd) : null;

        Set<String> seenIds = new HashSet<>();
        List<Map<String, Object>> results = new ArrayList<>();

        // Collect in-memory sessions
        for (ACPSession s : sessions.values()) {
            if (s.getHistorySize() <= 0) continue;
            if (normalizedCwd != null && !normalizeCwd(s.getCwd()).equals(normalizedCwd)) continue;
            seenIds.add(s.getSessionId());

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("session_id", s.getSessionId());
            info.put("cwd", s.getCwd());
            info.put("model", s.getModel());
            info.put("history_len", s.getHistorySize());
            info.put("title", s.getTitle());
            info.put("updated_at", formatInstant(s.getUpdatedAt()));
            results.add(info);
        }

        // Merge persisted sessions not currently in memory
        List<Map<String, Object>> persistedRows = loadPersistedSessions();
        for (Map<String, Object> row : persistedRows) {
            String sid = (String) row.get("id");
            if (sid == null || seenIds.contains(sid)) continue;

            int messageCount = ((Number) row.getOrDefault("message_count", 0)).intValue();
            if (messageCount <= 0) continue;

            String sessionCwd = extractCwdFromConfig(row.get("model_config"));
            if (normalizedCwd != null && !normalizeCwd(sessionCwd).equals(normalizedCwd)) continue;

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("session_id", sid);
            info.put("cwd", sessionCwd);
            info.put("model", row.getOrDefault("model", ""));
            info.put("history_len", messageCount);
            info.put("title", buildSessionTitle(
                    row.get("title"), row.get("preview"), sessionCwd));
            info.put("updated_at", formatUpdatedAt(row.get("last_active")));
            results.add(info);
        }

        // Sort by updated_at descending
        results.sort((a, b) -> {
            double ta = parseTimestamp(a.get("updated_at"));
            double tb = parseTimestamp(b.get("updated_at"));
            return Double.compare(tb, ta);
        });

        return results;
    }

    /**
     * Update the working directory for a session.
     */
    public ACPSession updateCwd(String sessionId, String cwd) {
        ACPSession session = getSession(sessionId);
        if (session == null) return null;
        session.setCwd(cwd);
        persist(session);
        return session;
    }

    /**
     * Save the current state of a session to the database.
     * Called after prompt completion, slash commands, and model switches.
     */
    public void saveSession(String sessionId) {
        ACPSession session = sessions.get(sessionId);
        if (session != null) {
            persist(session);
        }
    }

    /**
     * Remove all sessions (memory and database).
     */
    public void cleanup() {
        lock.lock();
        try {
            Set<String> ids = new HashSet<>(sessions.keySet());
            sessions.clear();
            for (String id : ids) {
                deletePersisted(id);
            }
            // Also remove any DB-only ACP sessions
            deleteAllPersistedAcpSessions();
        } finally {
            lock.unlock();
        }
    }

    // ================================================================
    //  JdbcTemplate injection (set by HermesACPAgent on init)
    // ================================================================

    public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ================================================================
    //  Persistence via existing SQLiteBackend
    // ================================================================

    private void persist(ACPSession session) {
        JdbcTemplate jt = jdbcTemplate;
        if (jt == null) {
            log.debug("JdbcTemplate not available, skipping ACP session persistence");
            return;
        }

        try {
            String modelStr = session.getModel() != null ? session.getModel() : null;
            String configJson = MAPPER.writeValueAsString(Map.of("cwd", session.getCwd()));

            // Ensure session record exists
            List<Map<String, Object>> existing = jt.queryForList(
                    "SELECT id FROM sessions WHERE id = ?", session.getSessionId());

            if (existing.isEmpty()) {
                jt.update(
                        "INSERT INTO sessions (id, source, model, model_config, started_at) " +
                                "VALUES (?, 'acp', ?, ?, ?)",
                        session.getSessionId(), modelStr, configJson,
                        System.currentTimeMillis());
            } else {
                jt.update(
                        "UPDATE sessions SET model_config = ?, model = COALESCE(?, model) WHERE id = ?",
                        configJson, modelStr, session.getSessionId());
            }

            // Replace stored messages with current history
            jt.update("DELETE FROM messages WHERE session_id = ?", session.getSessionId());
            for (Map<String, Object> msg : session.getHistory()) {
                String role = (String) msg.getOrDefault("role", "user");
                Object content = msg.get("content");
                String contentStr = content != null ? content.toString() : null;
                String toolName = (String) msg.getOrDefault("tool_name",
                        msg.getOrDefault("name", null));

                jt.update(
                        "INSERT INTO messages (session_id, role, content, tool_name, created_at) " +
                                "VALUES (?, ?, ?, ?, ?)",
                        session.getSessionId(), role, contentStr, toolName,
                        System.currentTimeMillis());
            }

        } catch (Exception e) {
            log.warn("Failed to persist ACP session {}", session.getSessionId(), e);
        }
    }

    private ACPSession restore(String sessionId) {
        JdbcTemplate jt = jdbcTemplate;
        if (jt == null) return null;

        try {
            List<Map<String, Object>> rows = jt.queryForList(
                    "SELECT * FROM sessions WHERE id = ? AND source = 'acp'", sessionId);
            if (rows.isEmpty()) return null;

            Map<String, Object> row = rows.get(0);
            String cwd = extractCwdFromConfig(row.get("model_config"));
            String model = (String) row.get("model");

            // Load conversation history
            List<Map<String, Object>> history = jt.queryForList(
                    "SELECT role, content, tool_name FROM messages WHERE session_id = ? ORDER BY created_at",
                    sessionId);

            // Convert to the format ACPSession expects
            List<Map<String, Object>> convertedHistory = new ArrayList<>();
            for (Map<String, Object> msgRow : history) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", msgRow.get("role"));
                msg.put("content", msgRow.get("content"));
                if (msgRow.get("tool_name") != null) {
                    msg.put("tool_name", msgRow.get("tool_name"));
                }
                convertedHistory.add(msg);
            }

            ACPSession session = new ACPSession(sessionId, cwd, model != null ? model : "");
            session.setHistory(convertedHistory);

            sessions.put(sessionId, session);
            log.info("Restored ACP session {} from DB ({} messages)", sessionId, convertedHistory.size());
            return session;

        } catch (Exception e) {
            log.debug("Failed to restore ACP session {}", sessionId, e);
            return null;
        }
    }

    private boolean deletePersisted(String sessionId) {
        JdbcTemplate jt = jdbcTemplate;
        if (jt == null) return false;
        try {
            jt.update("DELETE FROM messages WHERE session_id = ?", sessionId);
            int deleted = jt.update("DELETE FROM sessions WHERE id = ? AND source = 'acp'", sessionId);
            return deleted > 0;
        } catch (Exception e) {
            log.debug("Failed to delete ACP session {} from DB", sessionId, e);
            return false;
        }
    }

    private void deleteAllPersistedAcpSessions() {
        JdbcTemplate jt = jdbcTemplate;
        if (jt == null) return;
        try {
            List<String> ids = jt.queryForList(
                    "SELECT id FROM sessions WHERE source = 'acp'", String.class);
            for (String id : ids) {
                jt.update("DELETE FROM messages WHERE session_id = ?", id);
            }
            jt.update("DELETE FROM sessions WHERE source = 'acp'");
        } catch (Exception e) {
            log.debug("Failed to cleanup ACP sessions from DB", e);
        }
    }

    private List<Map<String, Object>> loadPersistedSessions() {
        JdbcTemplate jt = jdbcTemplate;
        if (jt == null) return Collections.emptyList();
        try {
            return jt.queryForList(
                    "SELECT id, model, model_config, started_at, last_active, " +
                            "(SELECT COUNT(*) FROM messages m WHERE m.session_id = s.id) as message_count " +
                            "FROM sessions s WHERE source = 'acp' ORDER BY last_active DESC LIMIT 1000");
        } catch (Exception e) {
            log.debug("Failed to load persisted ACP sessions", e);
            return Collections.emptyList();
        }
    }

    // ================================================================
    //  Utility methods (mirrors Python session.py helpers)
    // ================================================================

    /**
     * Normalize a path for comparison (handles Windows drive letters).
     * Mirrors Python _normalize_cwd_for_compare.
     */
    static String normalizeCwd(String cwd) {
        if (cwd == null || cwd.isBlank()) return ".";
        String raw = cwd.strip();
        // Normalize backslashes to forward slashes
        raw = raw.replace('\\', '/');
        // Windows drive letter → WSL mount form
        if (raw.length() >= 2 && Character.isLetter(raw.charAt(0)) && raw.charAt(1) == ':') {
            char drive = Character.toLowerCase(raw.charAt(0));
            String tail = raw.length() > 2 ? raw.substring(2) : "/";
            raw = "/mnt/" + drive + tail;
        }
        return raw;
    }

    /**
     * Build a session title from available metadata.
     * Mirrors Python _build_session_title.
     */
    static String buildSessionTitle(Object title, Object preview, String cwd) {
        String explicit = title != null ? title.toString().strip() : "";
        if (!explicit.isEmpty()) return explicit;

        String previewText = preview != null ? preview.toString().strip() : "";
        if (!previewText.isEmpty()) {
            return previewText.length() > 80 ? previewText.substring(0, 77) + "..." : previewText;
        }

        String leaf = cwd != null ? cwd.substring(Math.max(cwd.lastIndexOf('/'), cwd.lastIndexOf('\\')) + 1) : "";
        return leaf.isEmpty() ? "New thread" : leaf;
    }

    private String extractCwdFromConfig(Object modelConfig) {
        if (modelConfig == null) return ".";
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> config = MAPPER.readValue(modelConfig.toString(), Map.class);
            Object cwd = config.get("cwd");
            return cwd != null ? cwd.toString() : ".";
        } catch (Exception e) {
            return ".";
        }
    }

    private String formatInstant(Instant instant) {
        if (instant == null) return null;
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    private String formatUpdatedAt(Object value) {
        if (value == null) return null;
        if (value instanceof String s && !s.isBlank()) return s;
        try {
            double ts = Double.parseDouble(value.toString());
            return DateTimeFormatter.ISO_INSTANT.format(
                    Instant.ofEpochMilli((long) ts));
        } catch (Exception e) {
            return null;
        }
    }

    private double parseTimestamp(Object value) {
        if (value == null) return Double.NEGATIVE_INFINITY;
        if (value instanceof Number n) return n.doubleValue();
        String raw = value.toString().strip();
        if (raw.isEmpty()) return Double.NEGATIVE_INFINITY;
        try {
            return Instant.parse(raw).toEpochMilli();
        } catch (Exception e) {
            try {
                return Double.parseDouble(raw);
            } catch (Exception e2) {
                return Double.NEGATIVE_INFINITY;
            }
        }
    }
}
