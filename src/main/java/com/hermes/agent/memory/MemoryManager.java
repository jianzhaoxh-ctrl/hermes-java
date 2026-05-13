package com.hermes.agent.memory;

import com.hermes.agent.model.Message;
import com.hermes.agent.persistence.PersistenceBackend;
import com.hermes.agent.persistence.PersistenceBackendFactory;
import com.hermes.agent.persistence.PersistenceBackend.SearchResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 记忆管理器 — 统一管理会话消息、用户画像、技能记忆。
 *
 * <p>升级版特性：
 * <ul>
 *   <li>支持多种持久化后端（SQLite + FTS5、JSON 文件、Redis）</li>
 *   <li>FTS5 全文搜索能力</li>
 *   <li>异步写入不阻塞主流程</li>
 *   <li>向后兼容 JSON 文件格式</li>
 * </ul>
 */
@Component
public class MemoryManager {
    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);

    private final PersistenceBackendFactory backendFactory;
    private PersistenceBackend backend;

    /** 内存缓存：sessionId → 消息列表 */
    private final Map<String, List<Message>> sessionMemories = new ConcurrentHashMap<>();
    /** 用户画像缓存 */
    private final Map<String, Map<String, Object>> userProfiles = new ConcurrentHashMap<>();
    /** 技能记忆缓存 */
    private final Map<String, List<String>> skillMemory = new ConcurrentHashMap<>();

    public MemoryManager(PersistenceBackendFactory backendFactory) {
        this.backendFactory = backendFactory;
    }

    @PostConstruct
    public void loadFromBackend() {
        this.backend = backendFactory.getDefaultBackend();
        log.info("[Memory] 使用后端: {}", backend.name());

        loadSessionMessages();
        loadUserProfiles();
        loadSkillMemories();
    }

    private void loadSessionMessages() {
        try {
            for (String sessionId : backend.getAllSessionIds()) {
                List<Map<String, Object>> messages = backend.getSessionMessages(sessionId);
                List<Message> msgs = messages.stream()
                        .map(this::mapToMessage)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                if (!msgs.isEmpty()) {
                    sessionMemories.put(sessionId, msgs);
                }
            }
            log.info("[Memory] 加载会话 {} 个，消息共 {} 条", 
                    sessionMemories.size(), 
                    sessionMemories.values().stream().mapToInt(List::size).sum());
        } catch (Exception e) {
            log.warn("[Memory] 加载会话消息失败: {}", e.getMessage());
        }
    }

    private void loadUserProfiles() {
        try {
            userProfiles.putAll(backend.getAllUserProfiles());
            log.info("[Memory] 加载用户画像 {} 个", userProfiles.size());
        } catch (Exception e) {
            log.warn("[Memory] 加载用户画像失败: {}", e.getMessage());
        }
    }

    private void loadSkillMemories() {
        try {
            skillMemory.putAll(backend.getAllSkillMemories());
            log.info("[Memory] 加载技能记忆 {} 个", skillMemory.size());
        } catch (Exception e) {
            log.warn("[Memory] 加载技能记忆失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[Memory] 收到 shutdown 信号");
        // 各 Backend 有自己的 shutdown 钩子
    }

    // ── 会话消息 ─────────────────────────────────────────────────────

    public void saveMessage(String sessionId, Message message) {
        sessionMemories.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(message);
        backend.saveMessageAsync(sessionId, messageToMap(message));
        log.debug("[Memory] 保存消息: session={}, role={}, 总消息数={}", 
                sessionId, message.getRole(), getSessionSize(sessionId));
    }

    public void saveMessageSync(String sessionId, Message message) {
        sessionMemories.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(message);
        backend.saveMessage(sessionId, messageToMap(message));
    }

    public List<Message> getSessionHistory(String sessionId) {
        return new ArrayList<>(sessionMemories.getOrDefault(sessionId, Collections.emptyList()));
    }

    public List<Message> getSessionHistory(String sessionId, int maxMessages) {
        List<Message> messages = sessionMemories.getOrDefault(sessionId, Collections.emptyList());
        if (messages.size() <= maxMessages) return new ArrayList<>(messages);
        return new ArrayList<>(messages.subList(messages.size() - maxMessages, messages.size()));
    }

    /**
     * 使用 FTS5 全文搜索
     */
    public List<SearchResult> searchMessages(String query, String sessionId, int limit) {
        return backend.searchMessages(query, sessionId, limit);
    }

    /**
     * 在当前会话中搜索（回退到内存过滤）
     */
    public List<Message> searchSessionHistory(String sessionId, String query) {
        List<Message> messages = sessionMemories.getOrDefault(sessionId, Collections.emptyList());
        String lowerQuery = query.toLowerCase();
        return messages.stream()
                .filter(m -> m.getContent() != null && m.getContent().toLowerCase().contains(lowerQuery))
                .collect(Collectors.toList());
    }

    public int getSessionSize(String sessionId) {
        return sessionMemories.getOrDefault(sessionId, Collections.emptyList()).size();
    }

    public void clearSession(String sessionId) {
        sessionMemories.remove(sessionId);
        try {
            backend.clearSession(sessionId);
        } catch (Exception e) {
            log.error("[Memory] Backend clearSession failed for {}: {}",
                    sessionId, e.getMessage());
            // 内存已清除，标记不一致状态
        }
        log.info("[Memory] 清除会话: {}", sessionId);
    }

    public Set<String> getAllSessionIds() {
        return new HashSet<>(sessionMemories.keySet());
    }

    // ── 用户画像 ─────────────────────────────────────────────────────

    public void updateUserProfile(String userId, Map<String, Object> profile) {
        userProfiles.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).putAll(profile);
        backend.saveUserProfile(userId, profile);
        log.debug("[Memory] 更新用户画像: {}", userId);
    }

    public Map<String, Object> getUserProfile(String userId) {
        return userProfiles.getOrDefault(userId, new HashMap<>());
    }

    public Optional<Map<String, Object>> findUserProfile(String userId) {
        return Optional.ofNullable(userProfiles.get(userId));
    }

    // ── 技能记忆 ─────────────────────────────────────────────────────

    public void saveSkill(String skillName, String skillContent) {
        skillMemory.computeIfAbsent(skillName, k -> new ArrayList<>()).add(skillContent);
        backend.saveSkillMemory(skillName, skillContent);
        log.debug("[Memory] 保存技能: {}", skillName);
    }

    public List<String> getSkillHistory(String skillName) {
        return new ArrayList<>(skillMemory.getOrDefault(skillName, Collections.emptyList()));
    }

    public List<String> searchSkills(String query) {
        String lowerQuery = query.toLowerCase();
        return skillMemory.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().contains(lowerQuery) ||
                        e.getValue().stream().anyMatch(v -> v.toLowerCase().contains(lowerQuery)))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    // ── Memory Nudge ─────────────────────────────────────────────────

    public void saveMemoryNudge(String sessionId, String nudgeContent, String reason) {
        Map<String, Object> nudge = new LinkedHashMap<>();
        nudge.put("content", nudgeContent);
        nudge.put("reason", reason);
        nudge.put("timestamp", Instant.now().toString());

        sessionMemories.computeIfAbsent(sessionId + ":nudges", k -> new ArrayList<>())
                .add(new Message("system", nudgeContent, Instant.now()));
        log.info("[Memory] 保存 Nudge: session={}, reason={}", sessionId, reason);
    }

    public List<Map<String, Object>> getNudges(String sessionId) {
        List<Message> msgs = sessionMemories.get(sessionId + ":nudges");
        if (msgs == null) return Collections.emptyList();
        return msgs.stream()
                .map(m -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("content", m.getContent());
                    map.put("timestamp", m.getTimestamp() != null ? m.getTimestamp().toString() : Instant.now().toString());
                    if (m.getMetadata() != null) map.putAll(m.getMetadata());
                    return map;
                })
                .collect(Collectors.toList());
    }

    // ── 辅助方法 ─────────────────────────────────────────────────────

    private Map<String, Object> messageToMap(Message m) {
        if (m == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", m.getRole());
        map.put("content", m.getContent());
        map.put("timestamp", m.getTimestamp() != null ? m.getTimestamp().toEpochMilli() : System.currentTimeMillis());
        map.put("toolCallId", m.getToolCallId());
        if (m.getMetadata() != null) map.put("metadata", m.getMetadata());
        return map;
    }

    @SuppressWarnings("unchecked")
    private Message mapToMessage(Map<String, Object> map) {
        try {
            Message m = new Message();
            m.setRole((String) map.get("role"));
            m.setContent((String) map.get("content"));
            Object tsRaw = map.get("timestamp");
            if (tsRaw instanceof Long) {
                m.setTimestamp(Instant.ofEpochMilli((Long) tsRaw));
            } else if (tsRaw instanceof Integer) {
                m.setTimestamp(Instant.ofEpochMilli(((Integer) tsRaw).longValue()));
            } else if (tsRaw instanceof String) {
                try { m.setTimestamp(Instant.parse((String) tsRaw)); } catch (Exception e) { m.setTimestamp(Instant.now()); }
            } else {
                m.setTimestamp(Instant.now());
            }
            m.setToolCallId((String) map.get("toolCallId"));
            Object meta = map.get("metadata");
            if (meta instanceof Map) m.setMetadata((Map<String, Object>) meta);
            return m;
        } catch (Exception e) {
            log.debug("[Memory] 消息解析失败: {}", e.getMessage());
            return null;
        }
    }

    // ── 统计信息 ─────────────────────────────────────────────────────

    public Map<String, Object> getStats() {
        return Map.of(
                "backend", backend.name(),
                "sessions", sessionMemories.size(),
                "totalMessages", sessionMemories.values().stream().mapToInt(List::size).sum(),
                "userProfiles", userProfiles.size(),
                "skillMemories", skillMemory.size(),
                "availableBackends", backendFactory.getAvailableBackends().keySet()
        );
    }
}
