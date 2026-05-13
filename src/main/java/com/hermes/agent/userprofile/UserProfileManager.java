package com.hermes.agent.userprofile;

import com.hermes.agent.llm.LLMService;
import com.hermes.agent.model.Message;
import com.hermes.agent.persistence.PersistenceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户画像管理器（增强版 — 基于 Honcho 架构）。
 *
 * <p>当 hermes.profile.enabled=true 时，内部委托给 HonchoService 提供：
 * <ul>
 *   <li>Dialectic reasoning（多轮 LLM 推理）</li>
 *   <li>Peer card（伙伴卡片）</li>
 *   <li>Observation recording（观察记录）</li>
 *   <li>Semantic search（语义搜索）</li>
 *   <li>Conclusions（持久化结论）</li>
 * </ul>
 *
 * <p>当 hermes.profile.enabled=false 时，降级为原有逻辑（关键词 + LLM 提取）。
 *
 * <p>向后兼容：保留原有 getContextPrompt() 和 extractAndUpdateProfile() 接口，
 * Agent.java 无需修改即可获得深度用户画像能力。
 */
@Component
public class UserProfileManager {

    private static final Logger log = LoggerFactory.getLogger(UserProfileManager.class);

    private final LLMService llmService;
    private final PersistenceService persistence;
    private final UserProfileConfig profileConfig;

    /** 当 Honcho 禁用时的原有内存（降级模式） */
    private final Map<String, UserProfile> profiles = new ConcurrentHashMap<>();

    /** HonchoService（启用时非空） */
    private final HonchoService honchoService;

    /** ProfileExtractor（启用时非空） */
    private final ProfileExtractor profileExtractor;

    /** 当前是否使用 Honcho 模式 */
    private volatile boolean honchoMode = false;

    private static final Map<String, String> TOPIC_KEYWORDS = Map.ofEntries(
            Map.entry("java", "Java"), Map.entry("python", "Python"),
            Map.entry("javascript", "JavaScript/Frontend"), Map.entry("typescript", "TypeScript"),
            Map.entry("vue", "Vue.js"), Map.entry("react", "React"),
            Map.entry("spring", "Spring Boot"), Map.entry("docker", "Docker"),
            Map.entry("kubernetes", "Kubernetes"), Map.entry("mysql", "MySQL"),
            Map.entry("redis", "Redis"), Map.entry("mongodb", "MongoDB"),
            Map.entry("git", "Git"), Map.entry("linux", "Linux"),
            Map.entry("api", "API Design"), Map.entry("rest", "REST API"),
            Map.entry("microservice", "Microservices"), Map.entry("ai", "AI/ML"),
            Map.entry("llm", "LLM"), Map.entry("performance", "Performance"),
            Map.entry("security", "Security"), Map.entry("test", "Testing"),
            Map.entry("deploy", "DevOps"), Map.entry("cloud", "Cloud Computing")
    );

    public UserProfileManager(LLMService llmService,
                              PersistenceService persistence,
                              UserProfileConfig profileConfig,
                              HonchoService honchoService,
                              ProfileExtractor profileExtractor) {
        this.llmService = llmService;
        this.persistence = persistence;
        this.profileConfig = profileConfig;
        this.honchoService = honchoService;
        this.profileExtractor = profileExtractor;
    }

    @PostConstruct
    public void loadFromDisk() {
        try {
            Map<String, Map<String, Object>> saved = persistence.loadUserProfiles();
            for (Map.Entry<String, Map<String, Object>> e : saved.entrySet()) {
                profiles.put(e.getKey(), mapToProfile(e.getKey(), e.getValue()));
            }
            log.info("[Profile] 从磁盘加载 {} 个用户画像", profiles.size());
        } catch (Exception e) {
            log.warn("[Profile] 加载失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void saveToDisk() {
        try {
            Map<String, Map<String, Object>> data = new HashMap<>();
            profiles.forEach((k, v) -> data.put(k, profileToMap(v)));
            persistence.saveUserProfilesSync(data);
            log.info("[Profile] 保存 {} 个画像到磁盘", profiles.size());
        } catch (Exception e) {
            log.error("[Profile] 保存失败: {}", e.getMessage());
        }
    }

    /**
     * 提取并更新用户画像（每轮对话后调用）。
     * 启用 Honcho 时：委托给 ProfileExtractor + HonchoService。
     * 禁用时：使用原有逻辑（关键词 + LLM）。
     */
    @Async("taskExecutor")
    public void extractAndUpdateProfile(String sessionId, String userMessage, String assistantResponse) {
        if (honchoMode) {
            // Honcho 模式：通过 HonchoService 记录对话
            honchoService.syncTurn(sessionId, userMessage, assistantResponse);
            // 同时触发 LLM 提取
            profileExtractor.onTurn(sessionId, userMessage, assistantResponse);
        } else {
            // 降级模式：使用原有逻辑
            extractAndUpdateProfileLegacy(sessionId, userMessage, assistantResponse);
        }
    }

    /**
     * 获取上下文提示文本（注入系统提示）。
     * 启用 Honcho 时：委托给 HonchoService.buildContextString()。
     * 禁用时：使用原有逻辑。
     */
    public String getContextPrompt(String sessionId) {
        if (honchoMode) {
            String ctx = honchoService.buildContextString(sessionId);
            if (!ctx.isBlank()) {
                return "\n[用户画像]\n" + ctx;
            }
            return "";
        } else {
            return getContextPromptLegacy(sessionId);
        }
    }

    // ── Honcho 模式入口 ─────────────────────────────────────────────

    /**
     * 初始化 Honcho 会话（由 Agent 调用）。
     * 在 getOrCreateSession 时调用。
     */
    public void initializeSession(String sessionId, String sessionTitle, String gatewayKey) {
        if (honchoMode) {
            honchoService.initializeSession(sessionId, sessionTitle, gatewayKey,
                    profileConfig.getPeerName());
        }
    }

    /**
     * 会话结束时清理（由 Agent 调用）。
     */
    public void onSessionEnd(String sessionId) {
        if (honchoMode) {
            honchoService.onSessionEnd(sessionId);
        }
    }

    /**
     * 切换到 Honcho 模式（需要 hermes.profile.enabled=true）。
     */
    public void enableHonchoMode() {
        this.honchoMode = true;
        log.info("[Profile] Honcho 模式已启用 — dialectic_depth={}, recall_mode={}, observation={}",
                profileConfig.getDialecticDepth(),
                profileConfig.getRecallMode(),
                profileConfig.getObservationMode());
    }

    public boolean isHonchoMode() {
        return honchoMode;
    }

    /**
     * 获取 Honcho 状态（供调试和 API 使用）。
     */
    public Map<String, Object> getHonchoStatus(String sessionId) {
        if (!honchoMode) return Map.of("honcho_mode", false);
        return Map.of(
                "honcho_mode", true,
                "observation_count", honchoService.getObservationCount(sessionId),
                "conclusion_count", honchoService.getConclusionCount(sessionId),
                "liveness", honchoService.getLivenessSnapshot(sessionId),
                "recall_mode", profileConfig.getRecallMode(),
                "dialectic_depth", profileConfig.getDialecticDepth(),
                "observation_mode", profileConfig.getObservationMode()
        );
    }

    // ── 原有 API（向后兼容） ────────────────────────────────────────

    public void updateProfile(String userId, String key, Object value) {
        UserProfile profile = profiles.computeIfAbsent(userId, UserProfile::new);
        profile.setProperty(key, value);
        persistence.saveUserProfilesAsync(toProfileMap());
    }

    public void updatePreferences(String userId, Map<String, Object> preferences) {
        UserProfile profile = profiles.computeIfAbsent(userId, UserProfile::new);
        profile.getPreferences().putAll(preferences);
        persistence.saveUserProfilesAsync(toProfileMap());
    }

    public UserProfile getProfile(String userId) {
        if (honchoMode) {
            // Return a synthetic profile from Honcho data
            return honchoService.getUserProfile(userId)
                    .map(hp -> {
                        UserProfile p = new UserProfile(hp.getPeerId());
                        hp.getCard().facts().forEach(p::addTopic);
                        if (!hp.getRepresentation().isBlank()) {
                            p.setProperty("representation", hp.getRepresentation());
                        }
                        p.setConversationCount(hp.getObservationCount());
                        return p;
                    })
                    .orElse(new UserProfile(userId));
        }
        return profiles.getOrDefault(userId, new UserProfile(userId));
    }

    public Map<String, Object> getSummary(String userId) {
        if (honchoMode) {
            return honchoService.getUserProfile(userId)
                    .map(hp -> {
                        Map<String, Object> summary = new HashMap<>();
                        summary.put("userId", userId);
                        summary.put("honcho_mode", true);
                        summary.put("peerId", hp.getPeerId());
                        summary.put("peerType", hp.getPeerType());
                        summary.put("observationMode", hp.getObservationMode());
                        summary.put("cardFacts", hp.getCard().facts());
                        summary.put("representation", hp.getRepresentation());
                        summary.put("observationCount", hp.getObservationCount());
                        summary.put("conclusionCount", hp.getConclusionCount());
                        summary.put("lastUpdated", hp.getLastRepresentationUpdate());
                        return summary;
                    })
                    .orElse(Map.of("userId", userId, "honcho_mode", true, "found", false));
        }

        UserProfile profile = profiles.get(userId);
        if (profile == null) return Map.of("userId", userId, "known", false);
        Map<String, Object> summary = new HashMap<>();
        summary.put("userId", userId);
        summary.put("known", true);
        summary.put("conversationCount", profile.getConversationCount());
        summary.put("preferences", profile.getPreferences());
        summary.put("topics", profile.getKnownTopics());
        summary.put("summary", buildProfileSummary(profile));
        summary.put("lastUpdated", profile.getLastSeen());
        return summary;
    }

    // ── 原有降级逻辑 ───────────────────────────────────────────────

    private void extractAndUpdateProfileLegacy(String sessionId, String userMessage, String assistantResponse) {
        try {
            UserProfile profile = profiles.computeIfAbsent(sessionId, UserProfile::new);
            profile.incrementConversation();
            extractTopicsByKeywords(userMessage, profile);
            extractProfileViaLLM(sessionId, userMessage, assistantResponse, profile);
            persistence.saveUserProfilesAsync(toProfileMap());
            log.debug("[Profile] Updated for session={}, topics={}", sessionId, profile.getKnownTopics());
        } catch (Exception e) {
            log.warn("[Profile] Extraction failed for session={}: {}", sessionId, e.getMessage());
        }
    }

    private String getContextPromptLegacy(String sessionId) {
        UserProfile profile = profiles.get(sessionId);
        if (profile == null || profile.getConversationCount() == 0) return "";
        return buildProfileSummary(profile);
    }

    private void extractTopicsByKeywords(String message, UserProfile profile) {
        if (message == null) return;
        String lower = message.toLowerCase();
        for (Map.Entry<String, String> entry : TOPIC_KEYWORDS.entrySet()) {
            if (lower.contains(entry.getKey())) profile.addTopic(entry.getValue());
        }
    }

    private void extractProfileViaLLM(String sessionId, String userMessage,
                                        String assistantResponse, UserProfile profile) {
        try {
            String prompt = buildExtractionPrompt(userMessage, assistantResponse);
            List<Message> history = List.of(
                    new Message("system",
                            "You are a user profile extractor. Output ONLY a JSON object, nothing else.",
                            Instant.now()),
                    new Message("user", prompt, Instant.now())
            );
            Message result = llmService.chat(history, sessionId).block();
            if (result == null || result.getContent() == null) return;
            parseAndMerge(result.getContent().trim(), profile);
        } catch (Exception e) {
            log.debug("[Profile] LLM extraction failed (non-critical): {}", e.getMessage());
        }
    }

    private String buildExtractionPrompt(String userMessage, String assistantResponse) {
        return String.format(
            "Extract user profile info from this conversation. Output a JSON object with these fields (omit if not found):\n" +
            "- \"domain\": user's work domain (e.g. Java backend engineer, data engineer)\n" +
            "- \"techStack\": user's tech stack (array as string)\n" +
            "- \"preferences\": user's communication preferences (object as string)\n" +
            "- \"background\": user's background info\n\n" +
            "User: %s\nAssistant: %s\n\nOutput JSON only.",
            truncate(userMessage, 500), truncate(assistantResponse, 500));
    }

    private void parseAndMerge(String json, UserProfile profile) {
        try {
            if (!json.startsWith("{")) return;
            String domain = extractJsonField(json, "domain");
            if (!domain.isEmpty()) {
                profile.setProperty("domain", domain);
                profile.addTopic(domain);
            }
            String techStack = extractJsonField(json, "techStack");
            if (!techStack.isEmpty()) profile.setProperty("techStack", techStack);
            String prefs = extractJsonField(json, "preferences");
            if (!prefs.isEmpty()) profile.setProperty("inferredPreferences", prefs);
            String background = extractJsonField(json, "background");
            if (!background.isEmpty()) profile.setProperty("background", background);
            profile.touch();
        } catch (Exception e) {
            log.debug("[Profile] JSON parse failed: {}", e.getMessage());
        }
    }

    private String extractJsonField(String json, String field) {
        String strPattern = "\"" + field + "\"\\s*:\\s*\"";
        int start = json.indexOf(strPattern);
        if (start >= 0) {
            int valueStart = start + strPattern.length();
            int end = valueStart;
            while (end < json.length()) {
                char c = json.charAt(end);
                if (c == '"' && end > 0 && json.charAt(end - 1) != '\\') break;
                end++;
            }
            return json.substring(valueStart, end);
        }
        String arrPattern = "\"" + field + "\"\\s*:\\s*\\[";
        int arrStart = json.indexOf(arrPattern);
        if (arrStart >= 0) {
            int bs = json.indexOf('[', arrStart);
            int be = json.indexOf(']', bs);
            if (be > bs) return json.substring(bs + 1, be).trim();
        }
        return "";
    }

    private String buildProfileSummary(UserProfile profile) {
        StringBuilder sb = new StringBuilder("[User Profile] ");
        String domain = profile.getProperty("domain");
        if (domain != null && !domain.isEmpty()) sb.append("Domain: ").append(domain).append(". ");
        String techStack = profile.getProperty("techStack");
        if (techStack != null && !techStack.isEmpty()) sb.append("Tech stack: ").append(techStack).append(". ");
        if (!profile.getKnownTopics().isEmpty())
            sb.append("Known topics: ").append(String.join(", ", profile.getKnownTopics())).append(".");
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private Map<String, Map<String, Object>> toProfileMap() {
        Map<String, Map<String, Object>> m = new HashMap<>();
        profiles.forEach((k, v) -> m.put(k, profileToMap(v)));
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> profileToMap(UserProfile p) {
        Map<String, Object> m = new HashMap<>();
        m.put("userId", p.getUserId());
        m.put("properties", p.getProperties());
        m.put("preferences", p.getPreferences());
        m.put("knownTopics", new ArrayList<>(p.getKnownTopics()));
        m.put("conversationCount", p.getConversationCount());
        m.put("firstSeen", p.getFirstSeen());
        m.put("lastSeen", p.getLastSeen());
        return m;
    }

    @SuppressWarnings("unchecked")
    private UserProfile mapToProfile(String userId, Map<String, Object> m) {
        UserProfile p = new UserProfile(userId);
        Object props = m.get("properties");
        if (props instanceof Map) p.getProperties().putAll((Map<String, Object>) props);
        Object prefs = m.get("preferences");
        if (prefs instanceof Map) p.getPreferences().putAll((Map<String, Object>) prefs);
        Object topics = m.get("knownTopics");
        if (topics instanceof List) ((List<String>) topics).forEach(p::addTopic);
        Object cc = m.get("conversationCount");
        if (cc instanceof Integer) p.setConversationCount((Integer) cc);
        Object fs = m.get("firstSeen");
        if (fs instanceof Long) p.setFirstSeen((Long) fs);
        Object ls = m.get("lastSeen");
        if (ls instanceof Long) p.setLastSeen((Long) ls);
        return p;
    }

    // ── UserProfile ────────────────────────────────────────────────

    public static class UserProfile {
        private final String userId;
        private final Map<String, Object> properties = new ConcurrentHashMap<>();
        private final Map<String, Object> preferences = new ConcurrentHashMap<>();
        private final Set<String> knownTopics = ConcurrentHashMap.newKeySet();
        private int conversationCount;
        private long firstSeen;
        private long lastSeen;

        public UserProfile(String userId) {
            this.userId = userId;
            this.firstSeen = System.currentTimeMillis();
            this.lastSeen = System.currentTimeMillis();
        }

        public void incrementConversation() { conversationCount++; lastSeen = System.currentTimeMillis(); }
        public void touch() { lastSeen = System.currentTimeMillis(); }
        public void addTopic(String topic) { if (topic != null && !topic.isBlank()) knownTopics.add(topic.trim()); }
        public String getUserId() { return userId; }
        public Map<String, Object> getProperties() { return properties; }
        public Map<String, Object> getPreferences() { return preferences; }
        public Set<String> getKnownTopics() { return knownTopics; }
        public int getConversationCount() { return conversationCount; }
        public long getFirstSeen() { return firstSeen; }
        public long getLastSeen() { return lastSeen; }
        public String getProperty(String key) { Object v = properties.get(key); return v != null ? v.toString() : null; }
        public void setProperty(String key, Object value) { properties.put(key, value); }
        public void setConversationCount(int v) { this.conversationCount = v; }
        public void setFirstSeen(long v) { this.firstSeen = v; }
        public void setLastSeen(long v) { this.lastSeen = v; }
    }
}
