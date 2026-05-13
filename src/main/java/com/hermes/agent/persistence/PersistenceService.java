package com.hermes.agent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hermes.agent.config.AgentConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一持久化服务：负责 JSON 文件读写，支持：
 *   - session_messages.json   → 会话历史
 *   - user_profiles.json      → 用户画像
 *   - skill_memories.json     → 技能记录
 *   - auto_skills.json       → 自动生成的技能列表
 *   - session_summaries.json  → 会话摘要状态
 *
 * 策略：
 *   - 启动时同步加载（确保重启后立即可用）
 *   - 运行中数据变更由各 Manager 触发异步保存（不阻塞主流程）
 *   - Shutdown 时同步保存（最后一次保险）
 */
@Service
public class PersistenceService {

    private static final Logger log = LoggerFactory.getLogger(PersistenceService.class);

    private static final String F_SESSION_MESSAGES = "session_messages.json";
    private static final String F_USER_PROFILES    = "user_profiles.json";
    private static final String F_SKILL_MEMORIES   = "skill_memories.json";
    private static final String F_AUTO_SKILLS     = "auto_skills.json";
    private static final String F_SESSION_SUMMARIES = "session_summaries.json";

    private final AgentConfig config;
    private final ObjectMapper mapper;
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private Path dataDir;

    public PersistenceService(AgentConfig config) {
        this.config = config;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @PostConstruct
    public void init() {
        String dir = config.resolveDataDir();
        this.dataDir = Paths.get(dir);
        try {
            Files.createDirectories(dataDir);
            log.info("[Persistence] 数据目录: {}", dataDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("[Persistence] 无法创建数据目录 {}: {}", dir, e.getMessage());
            // fallback to temp dir
            this.dataDir = Paths.get(System.getProperty("java.io.tmpdir"), "hermes-data");
            try { Files.createDirectories(dataDir); } catch (IOException ignored) {}
        }
    }

    // ── Public API (各 Manager 调用) ───────────────────────────────

    /** 异步保存会话消息列表（Map<sessionId, List<Message>>） */
    @Async("taskExecutor")
    public void saveSessionMessagesAsync(Map<String, List<Map<String, Object>>> data) {
        writeJson(F_SESSION_MESSAGES, data);
    }

    /** 同步保存（shutdown 时用） */
    public void saveSessionMessagesSync(Map<String, List<Map<String, Object>>> data) {
        writeJson(F_SESSION_MESSAGES, data);
    }

    /** 加载会话消息 */
    @SuppressWarnings("unchecked")
    public Map<String, List<Map<String, Object>>> loadSessionMessages() {
        return readJson(F_SESSION_MESSAGES, Map.class)
                .map(m -> (Map<String, List<Map<String, Object>>>) m)
                .orElseGet(ConcurrentHashMap::new);
    }

    /** 异步保存用户画像 */
    @Async("taskExecutor")
    public void saveUserProfilesAsync(Map<String, Map<String, Object>> data) {
        writeJson(F_USER_PROFILES, data);
    }

    public void saveUserProfilesSync(Map<String, Map<String, Object>> data) {
        writeJson(F_USER_PROFILES, data);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> loadUserProfiles() {
        return readJson(F_USER_PROFILES, Map.class)
                .map(m -> (Map<String, Map<String, Object>>) m)
                .orElseGet(ConcurrentHashMap::new);
    }

    /** 异步保存技能记忆（Map<skillName, List<content>>） */
    @Async("taskExecutor")
    public void saveSkillMemoriesAsync(Map<String, List<String>> data) {
        writeJson(F_SKILL_MEMORIES, data);
    }

    public void saveSkillMemoriesSync(Map<String, List<String>> data) {
        writeJson(F_SKILL_MEMORIES, data);
    }

    @SuppressWarnings("unchecked")
    public Map<String, List<String>> loadSkillMemories() {
        return readJson(F_SKILL_MEMORIES, Map.class)
                .map(m -> (Map<String, List<String>>) m)
                .orElseGet(ConcurrentHashMap::new);
    }

    /** 异步保存自动生成技能名称集合 */
    @Async("taskExecutor")
    public void saveAutoSkillNamesAsync(Set<String> names) {
        writeJson(F_AUTO_SKILLS, names);
    }

    public void saveAutoSkillNamesSync(Set<String> names) {
        writeJson(F_AUTO_SKILLS, names);
    }

    @SuppressWarnings("unchecked")
    public Set<String> loadAutoSkillNames() {
        return readJson(F_AUTO_SKILLS, Set.class)
                .map(s -> (Set<String>) s)
                .orElseGet(ConcurrentHashMap::newKeySet);
    }

    /** 异步保存会话摘要状态（Map<sessionId, SummaryState>） */
    @Async("taskExecutor")
    public void saveSessionSummariesAsync(Map<String, Map<String, Object>> data) {
        writeJson(F_SESSION_SUMMARIES, data);
    }

    public void saveSessionSummariesSync(Map<String, Map<String, Object>> data) {
        writeJson(F_SESSION_SUMMARIES, data);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> loadSessionSummaries() {
        return readJson(F_SESSION_SUMMARIES, Map.class)
                .map(m -> (Map<String, Map<String, Object>>) m)
                .orElseGet(ConcurrentHashMap::new);
    }

    // ── Lifecycle ──────────────────────────────────────────────────

    /** Shutdown 时同步保存全部数据 */
    @PreDestroy
    public void shutdown() {
        log.info("[Persistence] 收到 shutdown 信号，等待保存...");
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        log.info("[Persistence] 数据已同步到磁盘（各 Manager 已在 shutdown 前触发过保存）");
    }

    // ── Private helpers ────────────────────────────────────────────

    private <T> Optional<T> readJson(String filename, Class<T> type) {
        Path file = dataDir.resolve(filename);
        if (!Files.exists(file)) {
            log.debug("[Persistence] 文件不存在（将首次创建）: {}", file);
            return Optional.empty();
        }
        try {
            T data = mapper.readValue(file.toFile(), type);
            log.info("[Persistence] 加载: {} ({} bytes)", filename, Files.size(file));
            return Optional.of(data);
        } catch (IOException e) {
            log.warn("[Persistence] 加载 {} 失败: {}", filename, e.getMessage());
            return Optional.empty();
        }
    }

    private void writeJson(String filename, Object data) {
        Path file = dataDir.resolve(filename);
        try {
            Files.createDirectories(dataDir);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
            log.debug("[Persistence] 保存: {} ({} bytes)", filename, Files.size(file));
        } catch (IOException e) {
            log.warn("[Persistence] 保存 {} 失败: {}", filename, e.getMessage());
        }
    }

    public Path getDataDir() {
        return dataDir;
    }
}
