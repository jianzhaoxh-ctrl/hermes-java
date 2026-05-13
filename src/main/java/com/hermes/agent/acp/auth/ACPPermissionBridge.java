package com.hermes.agent.acp.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ACP Permission Bridge.
 *
 * <p>Bridges ACP session permissions with Hermes agent tool allow/deny lists.
 * When an ACP session is created, the client can specify which tools the agent
 * is allowed to use. This class translates those permissions into the format
 * expected by the Hermes agent runtime.
 *
 * <p>Reference: Python acp_adapter/permissions.py
 */
public class ACPPermissionBridge {

    private static final Logger log = LoggerFactory.getLogger(ACPPermissionBridge.class);

    /** Well-known tool categories */
    public static final String CAT_FILE_READ = "file_read";
    public static final String CAT_FILE_WRITE = "file_write";
    public static final String CAT_FILE_SEARCH = "file_search";
    public static final String CAT_SHELL_EXECUTE = "shell_execute";
    public static final String CAT_WEB_FETCH = "web_fetch";
    public static final String CAT_BROWSER = "browser";

    /** Default allowed categories for a new session */
    private static final Set<String> DEFAULT_ALLOWED = Set.of(
            CAT_FILE_READ, CAT_FILE_SEARCH, CAT_WEB_FETCH
    );

    /** Maps tool names to their permission categories */
    private static final Map<String, String> TOOL_CATEGORY_MAP = new ConcurrentHashMap<>();

    static {
        // File read tools
        TOOL_CATEGORY_MAP.put("read_file", CAT_FILE_READ);
        TOOL_CATEGORY_MAP.put("read", CAT_FILE_READ);
        TOOL_CATEGORY_MAP.put("cat", CAT_FILE_READ);
        TOOL_CATEGORY_MAP.put("head", CAT_FILE_READ);
        TOOL_CATEGORY_MAP.put("tail", CAT_FILE_READ);

        // File write tools
        TOOL_CATEGORY_MAP.put("write_file", CAT_FILE_WRITE);
        TOOL_CATEGORY_MAP.put("write", CAT_FILE_WRITE);
        TOOL_CATEGORY_MAP.put("edit_file", CAT_FILE_WRITE);
        TOOL_CATEGORY_MAP.put("edit", CAT_FILE_WRITE);
        TOOL_CATEGORY_MAP.put("create_file", CAT_FILE_WRITE);
        TOOL_CATEGORY_MAP.put("delete_file", CAT_FILE_WRITE);
        TOOL_CATEGORY_MAP.put("move_file", CAT_FILE_WRITE);

        // File search tools
        TOOL_CATEGORY_MAP.put("search", CAT_FILE_SEARCH);
        TOOL_CATEGORY_MAP.put("grep", CAT_FILE_SEARCH);
        TOOL_CATEGORY_MAP.put("find", CAT_FILE_SEARCH);
        TOOL_CATEGORY_MAP.put("glob", CAT_FILE_SEARCH);
        TOOL_CATEGORY_MAP.put("ripgrep", CAT_FILE_SEARCH);

        // Shell execute tools
        TOOL_CATEGORY_MAP.put("execute", CAT_SHELL_EXECUTE);
        TOOL_CATEGORY_MAP.put("shell", CAT_SHELL_EXECUTE);
        TOOL_CATEGORY_MAP.put("bash", CAT_SHELL_EXECUTE);
        TOOL_CATEGORY_MAP.put("terminal", CAT_SHELL_EXECUTE);
        TOOL_CATEGORY_MAP.put("run", CAT_SHELL_EXECUTE);

        // Web fetch tools
        TOOL_CATEGORY_MAP.put("fetch", CAT_WEB_FETCH);
        TOOL_CATEGORY_MAP.put("web_fetch", CAT_WEB_FETCH);
        TOOL_CATEGORY_MAP.put("http_get", CAT_WEB_FETCH);
        TOOL_CATEGORY_MAP.put("http_post", CAT_WEB_FETCH);
        TOOL_CATEGORY_MAP.put("curl", CAT_WEB_FETCH);

        // Browser tools
        TOOL_CATEGORY_MAP.put("browser", CAT_BROWSER);
        TOOL_CATEGORY_MAP.put("screenshot", CAT_BROWSER);
        TOOL_CATEGORY_MAP.put("navigate", CAT_BROWSER);
        TOOL_CATEGORY_MAP.put("click", CAT_BROWSER);
        TOOL_CATEGORY_MAP.put("type_text", CAT_BROWSER);
    }

    /** Per-session permission state */
    private final Map<String, SessionPermissions> sessionPermissions = new ConcurrentHashMap<>();

    /**
     * Create default permissions for a session.
     */
    public SessionPermissions createDefault(String sessionId) {
        SessionPermissions perms = new SessionPermissions(sessionId, new HashSet<>(DEFAULT_ALLOWED));
        sessionPermissions.put(sessionId, perms);
        log.debug("Created default permissions for session {}: allowed={}", sessionId, DEFAULT_ALLOWED);
        return perms;
    }

    /**
     * Create permissions for a session from an explicit allow list.
     */
    public SessionPermissions createFromAllow(String sessionId, Set<String> allowedCategories) {
        SessionPermissions perms = new SessionPermissions(sessionId, new HashSet<>(allowedCategories));
        sessionPermissions.put(sessionId, perms);
        log.debug("Created permissions for session {}: allowed={}", sessionId, allowedCategories);
        return perms;
    }

    /**
     * Get permissions for a session.
     */
    public Optional<SessionPermissions> getPermissions(String sessionId) {
        return Optional.ofNullable(sessionPermissions.get(sessionId));
    }

    /**
     * Check if a tool is allowed for a session.
     */
    public boolean isToolAllowed(String sessionId, String toolName) {
        SessionPermissions perms = sessionPermissions.get(sessionId);
        if (perms == null) {
            // No explicit permissions → default allow (read-only by default)
            String category = TOOL_CATEGORY_MAP.getOrDefault(toolName, CAT_FILE_READ);
            return DEFAULT_ALLOWED.contains(category);
        }
        return perms.isToolAllowed(toolName);
    }

    /**
     * Grant additional tool categories to a session.
     */
    public void grant(String sessionId, String... categories) {
        SessionPermissions perms = sessionPermissions.get(sessionId);
        if (perms != null) {
            perms.addAllowed(Set.of(categories));
            log.debug("Granted categories {} to session {}", Arrays.toString(categories), sessionId);
        }
    }

    /**
     * Revoke tool categories from a session.
     */
    public void revoke(String sessionId, String... categories) {
        SessionPermissions perms = sessionPermissions.get(sessionId);
        if (perms != null) {
            perms.removeAllowed(Set.of(categories));
            log.debug("Revoked categories {} from session {}", Arrays.toString(categories), sessionId);
        }
    }

    /**
     * Remove all permissions for a session (cleanup on session end).
     */
    public void remove(String sessionId) {
        sessionPermissions.remove(sessionId);
    }

    /**
     * Get the permission category for a tool name.
     */
    public static String getCategoryForTool(String toolName) {
        return TOOL_CATEGORY_MAP.getOrDefault(toolName, CAT_FILE_READ);
    }

    /**
     * Get all known tool categories.
     */
    public static Set<String> allCategories() {
        return Set.of(CAT_FILE_READ, CAT_FILE_WRITE, CAT_FILE_SEARCH,
                CAT_SHELL_EXECUTE, CAT_WEB_FETCH, CAT_BROWSER);
    }

    // ---- Inner class ---------------------------------------------------------

    /**
     * Per-session permission state.
     */
    public static class SessionPermissions {
        private final String sessionId;
        private final Set<String> allowedCategories;

        public SessionPermissions(String sessionId, Set<String> allowedCategories) {
            this.sessionId = sessionId;
            this.allowedCategories = allowedCategories;
        }

        public boolean isToolAllowed(String toolName) {
            String category = TOOL_CATEGORY_MAP.getOrDefault(toolName, CAT_FILE_READ);
            return allowedCategories.contains(category);
        }

        public void addAllowed(Set<String> categories) {
            allowedCategories.addAll(categories);
        }

        public void removeAllowed(Set<String> categories) {
            allowedCategories.removeAll(categories);
        }

        public Set<String> getAllowedCategories() {
            return Collections.unmodifiableSet(allowedCategories);
        }

        public String getSessionId() {
            return sessionId;
        }
    }
}
