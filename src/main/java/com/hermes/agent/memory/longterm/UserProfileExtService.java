package com.hermes.agent.memory.longterm;

import com.hermes.agent.memory.longterm.LongTermMemory.Category;
import com.hermes.agent.userprofile.UserProfileManager;
import com.hermes.agent.userprofile.UserProfileManager.UserProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 用户画像扩展服务 — 长期记忆 ↔ 用户画像双向同步
 *
 * <p>Phase 5 核心组件，负责：
 * <ul>
 *   <li>用户画像 ↔ 长期记忆双向同步</li>
 *   <li>主题专业度追踪（topic_expertise）</li>
 *   <li>学习模式识别（learned_patterns）</li>
 *   <li>会话统计持久化</li>
 *   <li>画像与记忆关联查询</li>
 * </ul>
 *
 * <p>与 UserProfileManager 的关系：
 * <ul>
 *   <li>UserProfileManager — 负责画像提取和上下文注入（原有逻辑）</li>
 *   <li>UserProfileExtService — 负责画像持久化扩展和长期记忆同步</li>
 * </ul>
 *
 * <p>数据流：
 * <pre>
 * UserProfileManager.extractAndUpdateProfile()
 *        ↓
 * UserProfileExtService.syncProfileToLongTermMemory()
 *        ↓
 * LongTermMemoryManager.add(preference / fact)
 *        ↓
 * UserProfileExtService.syncLongTermMemoryToProfile()
 *        ↓
 * UserProfileManager.getContextPrompt()
 * </pre>
 */
@Component
public class UserProfileExtService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileExtService.class);

    private final JdbcTemplate jdbcTemplate;
    private final LongTermMemoryManager memoryManager;
    private final LongTermMemoryDao memoryDao;
    private final UserProfileManager profileManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UserProfileExtService(JdbcTemplate jdbcTemplate,
                                  LongTermMemoryManager memoryManager,
                                  LongTermMemoryDao memoryDao,
                                  UserProfileManager profileManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.memoryManager = memoryManager;
        this.memoryDao = memoryDao;
        this.profileManager = profileManager;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Row Mapper
    // ═══════════════════════════════════════════════════════════════════

    private final RowMapper<UserProfileExt> extRowMapper = (rs, rowNum) -> {
        UserProfileExt ext = new UserProfileExt();
        ext.setUserId(rs.getString("user_id"));

        // JSON 字段
        ext.setProperties(parseJsonMap(rs.getString("properties")));
        ext.setPreferences(parseJsonMap(rs.getString("preferences")));
        ext.setKnownTopics(parseJsonList(rs.getString("known_topics")));
        ext.setPersistentMemoryIds(parseJsonList(rs.getString("persistent_memory_ids")));
        ext.setTopicExpertise(parseJsonDoubleMap(rs.getString("topic_expertise")));
        ext.setLearnedPatterns(parseJsonList(rs.getString("learned_patterns")));

        ext.setConversationCount(rs.getInt("conversation_count"));
        ext.setFirstSeen(rs.getLong("first_seen"));
        ext.setLastSeen(rs.getLong("last_seen"));

        return ext;
    };

    // ═══════════════════════════════════════════════════════════════════
    // 画像 ↔ 长期记忆双向同步
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 同步用户画像到长期记忆
     *
     * <p>将用户画像中的偏好、事实等提取为长期记忆，
     * 确保跨会话时这些信息可以通过长期记忆检索到。
     *
     * @param userId 用户 ID
     */
    @Async("taskExecutor")
    public void syncProfileToLongTermMemory(String userId) {
        UserProfile profile = profileManager.getProfile(userId);
        if (profile == null) return;

        int synced = 0;

        // 1. 同步偏好
        Map<String, Object> preferences = profile.getPreferences();
        for (Map.Entry<String, Object> entry : preferences.entrySet()) {
            String content = String.format("用户偏好：%s = %s", entry.getKey(), entry.getValue());

            // 检查是否已存在相同偏好的记忆（避免重复）
            List<LongTermMemory> existing = memoryDao.searchFts(
                    "用户偏好 " + entry.getKey(), userId, 1);
            if (existing.isEmpty()) {
                memoryManager.add(content, Category.PREFERENCE, 0.8, userId, null,
                        "从用户画像同步");
                synced++;
            }
        }

        // 2. 同步关键属性
        Map<String, Object> properties = profile.getProperties();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String content = String.format("用户信息：%s = %s", entry.getKey(), entry.getValue());

            List<LongTermMemory> existing = memoryDao.searchFts(
                    "用户信息 " + entry.getKey(), userId, 1);
            if (existing.isEmpty()) {
                memoryManager.add(content, Category.FACT, 0.7, userId, null,
                        "从用户画像同步");
                synced++;
            }
        }

        // 3. 同步已知主题（作为事实）
        for (String topic : profile.getKnownTopics()) {
            String content = String.format("用户熟悉的技术领域：%s", topic);

            List<LongTermMemory> existing = memoryDao.searchFts(
                    "用户熟悉 " + topic, userId, 1);
            if (existing.isEmpty()) {
                memoryManager.add(content, Category.FACT, 0.6, userId, null,
                        "从用户画像主题同步");
                synced++;
            }
        }

        if (synced > 0) {
            log.info("[ProfileExt] 画像→长期记忆同步: userId={}, synced={}", userId, synced);
        }
    }

    /**
     * 同步长期记忆到用户画像
     *
     * <p>从长期记忆中提取偏好和事实，反向更新用户画像，
     * 确保画像始终包含最新的跨会话信息。
     *
     * @param userId 用户 ID
     */
    @Async("taskExecutor")
    public void syncLongTermMemoryToProfile(String userId) {
        UserProfile profile = profileManager.getProfile(userId);
        if (profile == null) return;

        int synced = 0;

        // 1. 从长期记忆中提取偏好
        List<LongTermMemory> preferences = memoryDao.findByCategory(
                userId, Category.PREFERENCE, 20);
        for (LongTermMemory mem : preferences) {
            // 解析 "用户偏好：key = value" 格式
            String content = mem.getContent();
            if (content != null && content.startsWith("用户偏好：")) {
                String kv = content.substring("用户偏好：".length());
                String[] parts = kv.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim();
                    profile.getPreferences().put(key, value);
                    synced++;
                }
            }
        }

        // 2. 从长期记忆中提取事实
        List<LongTermMemory> facts = memoryDao.findByCategory(
                userId, Category.FACT, 20);
        for (LongTermMemory mem : facts) {
            String content = mem.getContent();
            if (content != null) {
                // 提取技术领域
                if (content.contains("熟悉的技术领域")) {
                    int colonIdx = content.indexOf("：");
                    if (colonIdx > 0) {
                        String topic = content.substring(colonIdx + 1).trim();
                        profile.addTopic(topic);
                        synced++;
                    }
                }

                // 提取用户属性
                if (content.startsWith("用户信息：")) {
                    String kv = content.substring("用户信息：".length());
                    String[] parts = kv.split("=", 2);
                    if (parts.length == 2) {
                        profile.setProperty(parts[0].trim(), parts[1].trim());
                        synced++;
                    }
                }
            }
        }

        if (synced > 0) {
            log.info("[ProfileExt] 长期记忆→画像同步: userId={}, synced={}", userId, synced);
            // 触发画像→扩展表持久化
            saveProfileExt(userId);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 主题专业度追踪
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 更新主题专业度
     *
     * <p>基于用户在某个主题下的：
     * <ul>
     *   <li>对话次数（conversation_count）</li>
     *   <li>长期记忆数量（memory_count）</li>
     *   <li>决策数量（decision_count）</li>
     *   <li>问题解决数量（lesson_count）</li>
     * </ul>
     *
     * <p>计算专业度评分（0.0 ~ 1.0）：
     * <pre>
     * expertise = min(1.0,
     *     (conversations * 0.2 + memories * 0.3 + decisions * 0.3 + lessons * 0.2) / 10.0)
     * </pre>
     *
     * @param userId 用户 ID
     * @param topic 主题名称
     */
    public void updateTopicExpertise(String userId, String topic) {
        UserProfileExt ext = getOrCreateProfileExt(userId);

        // 统计各指标
        int conversations = ext.getConversationCount();
        int memories = (int) memoryDao.searchFts(topic, userId, 100).stream()
                .filter(m -> m.getCategory() == Category.FACT || m.getCategory() == Category.CONTEXT)
                .count();
        int decisions = (int) memoryDao.findByCategory(userId, Category.DECISION, 100).stream()
                .filter(m -> m.getContent() != null && m.getContent().contains(topic))
                .count();
        int lessons = (int) memoryDao.findByCategory(userId, Category.LESSON, 100).stream()
                .filter(m -> m.getContent() != null && m.getContent().contains(topic))
                .count();

        // 计算专业度
        double expertise = Math.min(1.0,
                (conversations * 0.2 + memories * 0.3 + decisions * 0.3 + lessons * 0.2) / 10.0);

        // 更新
        ext.getTopicExpertise().put(topic, expertise);
        if (!ext.getKnownTopics().contains(topic)) {
            ext.getKnownTopics().add(topic);
        }

        saveProfileExt(ext);

        log.debug("[ProfileExt] 主题专业度更新: userId={}, topic={}, expertise={:.2f}",
                userId, topic, expertise);
    }

    /**
     * 获取用户的专业主题列表（按专业度排序）
     */
    public List<Map.Entry<String, Double>> getExpertTopics(String userId, int limit) {
        UserProfileExt ext = getOrCreateProfileExt(userId);
        return ext.getTopicExpertise().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════
    // 学习模式识别
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 记录学习模式
     *
     * <p>从对话中识别用户的学习行为模式：
     * <ul>
     *   <li>example_driven — 偏好示例驱动学习</li>
     *   <li>concept_first — 偏好先理解概念</li>
     *   <li>hands_on — 偏好动手实践</li>
     *   <li>documentation — 偏好阅读文档</li>
     *   <li>trial_and_error — 偏好试错</li>
     * </ul>
     *
     * @param userId 用户 ID
     * @param pattern 学习模式
     * @param evidence 证据（用户消息片段）
     */
    public void recordLearnedPattern(String userId, String pattern, String evidence) {
        UserProfileExt ext = getOrCreateProfileExt(userId);

        String patternEntry = String.format("%s|%s", pattern, evidence);
        ext.getLearnedPatterns().add(patternEntry);

        // 同时写入长期记忆
        memoryManager.add(
                String.format("用户学习偏好：%s（证据：%s）", pattern, evidence),
                Category.PREFERENCE,
                0.7,
                userId,
                null,
                "学习模式识别"
        );

        saveProfileExt(ext);
        log.debug("[ProfileExt] 学习模式记录: userId={}, pattern={}", userId, pattern);
    }

    /**
     * 从对话中自动识别学习模式
     *
     * @param userId 用户 ID
     * @param userMessage 用户消息
     * @param assistantResponse 助手回复
     */
    public void detectLearnedPattern(String userId, String userMessage, String assistantResponse) {
        if (userMessage == null) return;

        String lower = userMessage.toLowerCase();

        // 基于关键词的学习模式识别
        if (lower.contains("举个例子") || lower.contains("show me an example") ||
                lower.contains("示例") || lower.contains("demo")) {
            recordLearnedPattern(userId, "example_driven", truncate(userMessage, 100));
        }
        if (lower.contains("原理") || lower.contains("为什么") || lower.contains("how does it work") ||
                lower.contains("原理是什么")) {
            recordLearnedPattern(userId, "concept_first", truncate(userMessage, 100));
        }
        if (lower.contains("试试") || lower.contains("run this") || lower.contains("帮我执行") ||
                lower.contains("动手")) {
            recordLearnedPattern(userId, "hands_on", truncate(userMessage, 100));
        }
        if (lower.contains("文档") || lower.contains("官方文档") || lower.contains("documentation") ||
                lower.contains("reference")) {
            recordLearnedPattern(userId, "documentation", truncate(userMessage, 100));
        }
        if (lower.contains("换个方式") || lower.contains("还是不行") || lower.contains("再试一次") ||
                lower.contains("试试别")) {
            recordLearnedPattern(userId, "trial_and_error", truncate(userMessage, 100));
        }
    }

    /**
     * 获取用户的学习模式摘要
     */
    public Map<String, Integer> getLearnedPatternSummary(String userId) {
        UserProfileExt ext = getOrCreateProfileExt(userId);
        Map<String, Integer> summary = new ConcurrentHashMap<>();

        for (String pattern : ext.getLearnedPatterns()) {
            String[] parts = pattern.split("\\|", 2);
            if (parts.length > 0) {
                String mode = parts[0];
                summary.merge(mode, 1, Integer::sum);
            }
        }

        return summary;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 持久化操作（user_profiles_ext 表）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 获取或创建用户画像扩展
     */
    public UserProfileExt getOrCreateProfileExt(String userId) {
        UserProfileExt ext = findProfileExt(userId);
        if (ext == null) {
            ext = new UserProfileExt(userId);
            // 从 UserProfileManager 同步基础信息
            UserProfile profile = profileManager.getProfile(userId);
            if (profile != null) {
                ext.setProperties(new ConcurrentHashMap<>(profile.getProperties()));
                ext.setPreferences(new ConcurrentHashMap<>(profile.getPreferences()));
                ext.setKnownTopics(new ArrayList<>(profile.getKnownTopics()));
                ext.setConversationCount(profile.getConversationCount());
                ext.setFirstSeen(profile.getFirstSeen());
                ext.setLastSeen(profile.getLastSeen());
            }
            saveProfileExt(ext);
        }
        return ext;
    }

    /**
     * 查找用户画像扩展
     */
    public UserProfileExt findProfileExt(String userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM user_profiles_ext WHERE user_id = ?",
                    extRowMapper, userId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 保存用户画像扩展
     */
    public void saveProfileExt(UserProfileExt ext) {
        if (ext == null || ext.getUserId() == null) return;

        String sql = """
            INSERT INTO user_profiles_ext (
                user_id, properties, preferences, known_topics,
                persistent_memory_ids, topic_expertise, learned_patterns,
                conversation_count, first_seen, last_seen
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET
                properties = excluded.properties,
                preferences = excluded.preferences,
                known_topics = excluded.known_topics,
                persistent_memory_ids = excluded.persistent_memory_ids,
                topic_expertise = excluded.topic_expertise,
                learned_patterns = excluded.learned_patterns,
                conversation_count = excluded.conversation_count,
                first_seen = excluded.first_seen,
                last_seen = excluded.last_seen
            """;

        try {
            jdbcTemplate.update(sql,
                    ext.getUserId(),
                    toJson(ext.getProperties()),
                    toJson(ext.getPreferences()),
                    toJson(ext.getKnownTopics()),
                    toJson(ext.getPersistentMemoryIds()),
                    toJson(ext.getTopicExpertise()),
                    toJson(ext.getLearnedPatterns()),
                    ext.getConversationCount(),
                    ext.getFirstSeen(),
                    ext.getLastSeen()
            );
        } catch (Exception e) {
            log.warn("[ProfileExt] 保存失败: userId={}, error={}", ext.getUserId(), e.getMessage());
        }
    }

    /**
     * 保存用户画像扩展（按 userId 查找后保存）
     */
    private void saveProfileExt(String userId) {
        UserProfileExt ext = getOrCreateProfileExt(userId);
        saveProfileExt(ext);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 增强的上下文构建
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 构建增强版用户画像上下文（含专业度、学习模式）
     *
     * <p>与 UserProfileManager.getContextPrompt() 不同的是，
     * 此方法额外包含从长期记忆和扩展画像中获取的信息。
     *
     * @param userId 用户 ID
     * @return 增强版上下文文本
     */
    public String buildEnhancedContext(String userId) {
        StringBuilder sb = new StringBuilder();

        // 1. 基础画像
        String baseContext = profileManager.getContextPrompt(userId);
        if (!baseContext.isEmpty()) {
            sb.append(baseContext).append("\n\n");
        }

        // 2. 专业领域
        List<Map.Entry<String, Double>> expertTopics = getExpertTopics(userId, 5);
        if (!expertTopics.isEmpty()) {
            sb.append("## 专业领域\n");
            for (Map.Entry<String, Double> entry : expertTopics) {
                sb.append(String.format("- %s（专业度: %.0f%%）\n",
                        entry.getKey(), entry.getValue() * 100));
            }
            sb.append("\n");
        }

        // 3. 学习偏好
        Map<String, Integer> patterns = getLearnedPatternSummary(userId);
        if (!patterns.isEmpty()) {
            sb.append("## 学习偏好\n");
            String topPattern = patterns.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .findFirst()
                    .map(Map.Entry::getKey)
                    .orElse("");
            sb.append(String.format("- 主要模式：%s\n", topPattern));
            sb.append(String.format("- 模式分布：%s\n", patterns));
            sb.append("\n");
        }

        // 4. 关键长期记忆
        List<LongTermMemory> keyMemories = memoryDao.findByImportance(userId, 0.8, 5);
        if (!keyMemories.isEmpty()) {
            sb.append("## 关键记忆\n");
            for (LongTermMemory mem : keyMemories) {
                sb.append(String.format("- [%s] %s\n",
                        mem.getCategory().getValue(), mem.getContent()));
            }
        }

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 会话统计
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 增加对话计数
     */
    public void incrementConversationCount(String userId) {
        UserProfileExt ext = getOrCreateProfileExt(userId);
        ext.setConversationCount(ext.getConversationCount() + 1);
        ext.setLastSeen(System.currentTimeMillis());
        saveProfileExt(ext);
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStats(String userId) {
        UserProfileExt ext = getOrCreateProfileExt(userId);
        Map<String, Object> stats = new LinkedHashMap<>();

        stats.put("userId", userId);
        stats.put("conversationCount", ext.getConversationCount());
        stats.put("knownTopics", ext.getKnownTopics());
        stats.put("topicExpertise", ext.getTopicExpertise());
        stats.put("learnedPatterns", getLearnedPatternSummary(userId));
        stats.put("memoryCount", memoryDao.countByUserId(userId));
        stats.put("persistentMemoryIds", ext.getPersistentMemoryIds());

        return stats;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════════════

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        try {
            if (json == null || json.isBlank()) return new ConcurrentHashMap<>();
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return new ConcurrentHashMap<>();
        }
    }

    private Map<String, Double> parseJsonDoubleMap(String json) {
        try {
            if (json == null || json.isBlank()) return new ConcurrentHashMap<>();
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            Map<String, Double> result = new ConcurrentHashMap<>();
            raw.forEach((k, v) -> {
                if (v instanceof Number) result.put(k, ((Number) v).doubleValue());
            });
            return result;
        } catch (Exception e) {
            return new ConcurrentHashMap<>();
        }
    }

    private List<String> parseJsonList(String json) {
        try {
            if (json == null || json.isBlank()) return new ArrayList<>();
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 数据类
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 用户画像扩展数据
     */
    public static class UserProfileExt {
        private String userId;
        private Map<String, Object> properties = new ConcurrentHashMap<>();
        private Map<String, Object> preferences = new ConcurrentHashMap<>();
        private List<String> knownTopics = new ArrayList<>();
        private List<String> persistentMemoryIds = new ArrayList<>();
        private Map<String, Double> topicExpertise = new ConcurrentHashMap<>();
        private List<String> learnedPatterns = new ArrayList<>();
        private int conversationCount;
        private long firstSeen;
        private long lastSeen;

        public UserProfileExt() {
            long now = System.currentTimeMillis();
            this.firstSeen = now;
            this.lastSeen = now;
        }

        public UserProfileExt(String userId) {
            this();
            this.userId = userId;
        }

        // Getters & Setters
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) { this.properties = properties; }
        public Map<String, Object> getPreferences() { return preferences; }
        public void setPreferences(Map<String, Object> preferences) { this.preferences = preferences; }
        public List<String> getKnownTopics() { return knownTopics; }
        public void setKnownTopics(List<String> knownTopics) { this.knownTopics = knownTopics; }
        public List<String> getPersistentMemoryIds() { return persistentMemoryIds; }
        public void setPersistentMemoryIds(List<String> persistentMemoryIds) { this.persistentMemoryIds = persistentMemoryIds; }
        public Map<String, Double> getTopicExpertise() { return topicExpertise; }
        public void setTopicExpertise(Map<String, Double> topicExpertise) { this.topicExpertise = topicExpertise; }
        public List<String> getLearnedPatterns() { return learnedPatterns; }
        public void setLearnedPatterns(List<String> learnedPatterns) { this.learnedPatterns = learnedPatterns; }
        public int getConversationCount() { return conversationCount; }
        public void setConversationCount(int conversationCount) { this.conversationCount = conversationCount; }
        public long getFirstSeen() { return firstSeen; }
        public void setFirstSeen(long firstSeen) { this.firstSeen = firstSeen; }
        public long getLastSeen() { return lastSeen; }
        public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }
    }
}