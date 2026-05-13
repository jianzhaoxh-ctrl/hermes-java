package com.hermes.agent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JSON 文件持久化后端 — 保持与旧版本的向后兼容。
 *
 * <p>数据文件：
 * <ul>
 *   <li>session_messages.json → 会话历史</li>
 *   <li>user_profiles.json → 用户画像</li>
 *   <li>skill_memories.json → 技能记录</li>
 * </ul>
 *
 * <p>策略：
 * <ul>
 *   <li>启动时同步加载</li>
 *   <li>运行时异步保存（不阻塞主流程）</li>
 *   <li>Shutdown 时同步保存（最后一次保险）</li>
 * </ul>
 */
@Component("jsonFileBackend")
public class JsonFileBackend implements PersistenceBackend {

    private static final Logger log = LoggerFactory.getLogger(JsonFileBackend.class);

    private static final String F_SESSION_MESSAGES = "session_messages.json";
    private static final String F_USER_PROFILES = "user_profiles.json";
    private static final String F_SKILL_MEMORIES = "skill_memories.json";

    private final ObjectMapper mapper;
    private final Path dataDir;
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    // 内存缓存
    private final Map<String, List<Map<String, Object>>> sessionMessages = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> userProfiles = new ConcurrentHashMap<>();
    private final Map<String, List<String>> skillMemories = new ConcurrentHashMap<>();

    public JsonFileBackend(String dataDir) {
        this.dataDir = Paths.get(dataDir);
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public JsonFileBackend() {
        this(System.getProperty("user.home") + "/.hermes/data");
    }

    @PostConstruct
    @Override
    public void initialize() {
        try {
            Files.createDirectories(dataDir);
            loadAll();
            log.info("[JsonFileBackend] 初始化完成: {}", dataDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("[JsonFileBackend] 初始化失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    @Override
    public void shutdown() {
        saveAllSync();
        log.info("[JsonFileBackend] 数据已持久化到磁盘");
    }

    @Override
    public String name() {
        return "jsonfile";
    }

    @Override
    public boolean isAvailable() {
        return Files.isDirectory(dataDir);
    }

    // ── 加载数据 ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void loadAll() {
        // 加载会话消息
        readJson(F_SESSION_MESSAGES, Map.class).ifPresent(data -> {
            sessionMessages.putAll((Map<String, List<Map<String, Object>>>) data);
        });

        // 加载用户画像
        readJson(F_USER_PROFILES, Map.class).ifPresent(data -> {
            userProfiles.putAll((Map<String, Map<String, Object>>) data);
        });

        // 加载技能记忆
        readJson(F_SKILL_MEMORIES, Map.class).ifPresent(data -> {
            skillMemories.putAll((Map<String, List<String>>) data);
        });

        log.info("[JsonFileBackend] 加载数据: sessions={}, profiles={}, skills={}",
                sessionMessages.size(), userProfiles.size(), skillMemories.size());
    }

    // ── 会话消息 ─────────────────────────────────────────────────────

    @Override
    public void saveMessage(String sessionId, Map<String, Object> message) {
        sessionMessages.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(message);
        dirty.set(true);
        saveAllAsync();
    }

    @Async("taskExecutor")
    @Override
    public void saveMessageAsync(String sessionId, Map<String, Object> message) {
        saveMessage(sessionId, message);
    }

    @Override
    public List<Map<String, Object>> getSessionMessages(String sessionId) {
        return new ArrayList<>(sessionMessages.getOrDefault(sessionId, Collections.emptyList()));
    }

    @Override
    public List<Map<String, Object>> getSessionMessages(String sessionId, int limit) {
        List<Map<String, Object>> messages = sessionMessages.getOrDefault(sessionId, Collections.emptyList());
        if (messages.size() <= limit) return new ArrayList<>(messages);
        return new ArrayList<>(messages.subList(messages.size() - limit, messages.size()));
    }

    @Override
    public List<String> getAllSessionIds() {
        return new ArrayList<>(sessionMessages.keySet());
    }

    @Override
    public void clearSession(String sessionId) {
        sessionMessages.remove(sessionId);
        dirty.set(true);
        saveAllAsync();
    }

    // ── 用户画像 ─────────────────────────────────────────────────────

    @Override
    public void saveUserProfile(String userId, Map<String, Object> profile) {
        userProfiles.put(userId, profile);
        dirty.set(true);
        saveAllAsync();
    }

    @Override
    public Optional<Map<String, Object>> getUserProfile(String userId) {
        return Optional.ofNullable(userProfiles.get(userId));
    }

    @Override
    public Map<String, Map<String, Object>> getAllUserProfiles() {
        return new HashMap<>(userProfiles);
    }

    // ── 技能记忆 ─────────────────────────────────────────────────────

    @Override
    public void saveSkillMemory(String skillName, String content) {
        skillMemories.computeIfAbsent(skillName, k -> new ArrayList<>()).add(content);
        dirty.set(true);
        saveAllAsync();
    }

    @Override
    public List<String> getSkillMemory(String skillName) {
        return new ArrayList<>(skillMemories.getOrDefault(skillName, Collections.emptyList()));
    }

    @Override
    public Map<String, List<String>> getAllSkillMemories() {
        return new HashMap<>(skillMemories);
    }

    // ── 全文搜索（回退到内存搜索）─────────────────────────────────────

    @Override
    public List<SearchResult> searchMessages(String query, String sessionId, int limit) {
        String lowerQuery = query.toLowerCase();
        List<SearchResult> results = new ArrayList<>();

        for (Map.Entry<String, List<Map<String, Object>>> entry : sessionMessages.entrySet()) {
            if (sessionId != null && !sessionId.isBlank() && !entry.getKey().equals(sessionId)) {
                continue;
            }

            for (Map<String, Object> msg : entry.getValue()) {
                String content = (String) msg.get("content");
                if (content != null && content.toLowerCase().contains(lowerQuery)) {
                    int hitCount = countOccurrences(content.toLowerCase(), lowerQuery);
                    results.add(new SearchResult(
                            String.valueOf(msg.get("id")),
                            entry.getKey(),
                            (String) msg.get("role"),
                            content,
                            hitCount / (double) content.length(),
                            System.currentTimeMillis()
                    ));
                }
            }
        }

        results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return results.size() > limit ? results.subList(0, limit) : results;
    }

    private int countOccurrences(String text, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }

    // ── 持久化 ────────────────────────────────────────────────────────

    @Async("taskExecutor")
    public void saveAllAsync() {
        saveAllSync();
    }

    public synchronized void saveAllSync() {
        if (!dirty.get()) return;

        writeJson(F_SESSION_MESSAGES, sessionMessages);
        writeJson(F_USER_PROFILES, userProfiles);
        writeJson(F_SKILL_MEMORIES, skillMemories);

        dirty.set(false);
        log.debug("[JsonFileBackend] 数据已保存");
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────

    private <T> Optional<T> readJson(String filename, Class<T> type) {
        Path file = dataDir.resolve(filename);
        if (!Files.exists(file)) return Optional.empty();
        try {
            T data = mapper.readValue(file.toFile(), type);
            return Optional.of(data);
        } catch (IOException e) {
            log.warn("[JsonFileBackend] 读取 {} 失败: {}", filename, e.getMessage());
            return Optional.empty();
        }
    }

    private void writeJson(String filename, Object data) {
        Path file = dataDir.resolve(filename);
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        } catch (IOException e) {
            log.warn("[JsonFileBackend] 写入 {} 失败: {}", filename, e.getMessage());
        }
    }

    // ── Getter ────────────────────────────────────────────────────────

    public Map<String, List<Map<String, Object>>> getSessionMessages() {
        return sessionMessages;
    }

    public Map<String, Map<String, Object>> getUserProfiles() {
        return userProfiles;
    }

    public Map<String, List<String>> getSkillMemories() {
        return skillMemories;
    }

    // ── P1 增强方法（JSON 后端无 SQLite 特性，返回空/默认值）────────────

    @Override
    public List<Map<String, Object>> listSessionsRich(String source, int limit, int offset,
                                                        boolean projectCompressionTips) {
        // JSON 后端不支持富会话列表，返回空列表
        return Collections.emptyList();
    }

    @Override
    public boolean deleteSession(String sessionId) {
        boolean removed = sessionMessages.remove(sessionId) != null;
        if (removed) {
            dirty.set(true);
        }
        return removed;
    }

    @Override
    public int pruneSessions(int olderThanDays, String source) {
        // JSON 后端不支持按时间清理，返回 0
        return 0;
    }
}
