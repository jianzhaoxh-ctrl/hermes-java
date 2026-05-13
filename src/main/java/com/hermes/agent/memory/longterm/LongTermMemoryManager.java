package com.hermes.agent.memory.longterm;

import com.hermes.agent.config.AgentConfig;
import com.hermes.agent.memory.longterm.LongTermMemory.Category;
import com.hermes.agent.model.Message;
import com.hermes.agent.llm.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 长期记忆管理器 — 跨会话持久化记忆的核心服务
 *
 * <p>职责：
 * <ul>
 *   <li>记忆 CRUD 操作</li>
 *   <li>LLM 重要性评分</li>
 *   <li>FTS5 + 向量语义搜索</li>
 *   <li>智能预取（Agent 上下文注入）</li>
 *   <li>遗忘机制定时执行</li>
 * </ul>
 *
 * <p>数据流：
 * <pre>
 * Agent 每轮 → evaluateAndStore() → LLM 评分 → Dao 写入
 *                          ↓
 *              prefetchContext() → Agent 上下文注入
 * </pre>
 */
@Component
public class LongTermMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryManager.class);

    @Autowired
    private LongTermMemoryDao dao;

    @Autowired
    private AgentConfig config;

    @Autowired(required = false)
    private LLMService llmService;

    // 内存缓存：避免频繁查数据库
    private final Map<String, List<LongTermMemory>> userMemoryCache = new ConcurrentHashMap<>();
    private static final int CACHE_SIZE = 100;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;  // 5 分钟

    private final Map<String, Long> cacheTimestamp = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════════
    // 核心操作
    // ═══════════════════════════════════════════════════════════════

    /**
     * 添加记忆（自动 LLM 评分）
     *
     * @param content 记忆内容
     * @param category 分类
     * @param userId 用户 ID
     * @param sessionId 来源会话
     * @param sourceContext 来源上下文
     * @return 写入的记忆（含 ID）
     */
    public LongTermMemory add(String content, Category category, String userId,
                             String sessionId, String sourceContext) {
        return add(content, category, -1, userId, sessionId, sourceContext);
    }

    /**
     * 添加记忆（指定重要性，跳过 LLM 评分）
     */
    public LongTermMemory add(String content, Category category, double importance,
                             String userId, String sessionId, String sourceContext) {
        // LLM 评分（如果未指定）
        if (importance < 0) {
            importance = scoreImportance(content, sourceContext);
        }

        // 创建记忆
        LongTermMemory memory = LongTermMemory.create(
                userId != null ? userId : "global",
                category != null ? category : Category.CONTEXT,
                content,
                importance,
                sessionId,
                sourceContext
        );

        // 写入数据库
        dao.insert(memory);

        // 更新缓存
        invalidateCache(userId);

        log.info("[LongTermMemory] 添加记忆: id={}, category={}, importance={:.2f}",
                memory.getId(), category.getValue(), importance);

        return memory;
    }

    /**
     * 批量添加
     */
    public List<LongTermMemory> batchAdd(List<LongTermMemory> memories) {
        dao.batchInsert(memories);
        if (!memories.isEmpty()) {
            invalidateCache(memories.get(0).getUserId());
        }
        return memories;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 搜索
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 全文搜索
     */
    public List<LongTermMemory> search(String query, String userId, int limit) {
        return dao.searchFts(query, userId, limit);
    }

    /**
     * 按分类搜索
     */
    public List<LongTermMemory> searchByCategory(String userId, Category category, int limit) {
        return dao.findByCategory(userId, category, limit);
    }

    /**
     * 按重要性搜索
     */
    public List<LongTermMemory> searchByImportance(String userId, double minImportance, int limit) {
        return dao.findByImportance(userId, minImportance, limit);
    }

    /**
     * 获取会话的所有记忆
     */
    public List<LongTermMemory> getBySession(String sessionId) {
        return dao.findBySessionId(sessionId);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 预取（Agent 上下文注入）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 构建预取上下文（注入 Agent）
     *
     * @param query 当前查询
     * @param sessionId 当前会话
     * @param userId 用户 ID
     * @return 格式化的记忆上下文
     */
    public String buildPrefetchContext(String query, String sessionId, String userId) {
        return buildPrefetchContext(query, sessionId, userId, 
                config.getVectorMemoryTopK());
    }

    public String buildPrefetchContext(String query, String sessionId, String userId, int limit) {
        StringBuilder sb = new StringBuilder();
        
        // 1. 语义搜索相关记忆
        List<LongTermMemory> searchResults = dao.searchFts(query, userId, limit);
        if (!searchResults.isEmpty()) {
            sb.append("## 相关记忆\n\n");
            for (LongTermMemory mem : searchResults) {
                sb.append(formatMemoryItem(mem));
                dao.markAccessed(mem.getId());  // 标记访问
            }
        }

        // 2. 用户偏好（高优先级）
        List<LongTermMemory> preferences = dao.findByCategory(
                userId, Category.PREFERENCE, 5);
        if (!preferences.isEmpty()) {
            sb.append("\n## 用户偏好\n\n");
            for (LongTermMemory mem : preferences) {
                sb.append(formatMemoryItem(mem));
            }
        }

        // 3. 最近决策
        List<LongTermMemory> decisions = dao.findByCategory(
                userId, Category.DECISION, 3);
        if (!decisions.isEmpty()) {
            sb.append("\n## 重要决策\n\n");
            for (LongTermMemory mem : decisions) {
                sb.append(formatMemoryItem(mem));
            }
        }

        return sb.toString();
    }

    private String formatMemoryItem(LongTermMemory mem) {
        return String.format("- [%s] %s（重要性: %.1f）\n",
                mem.getCategory().getValue(),
                mem.getContent(),
                mem.getImportance());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 删除
    // ═══════════════════════════════════════════════════════════════

    /**
     * 删除记忆
     */
    public void delete(String memoryId) {
        LongTermMemory mem = dao.findById(memoryId).orElse(null);
        if (mem != null) {
            dao.deleteById(memoryId);
            invalidateCache(mem.getUserId());
            log.info("[LongTermMemory] 删除记忆: {}", memoryId);
        }
    }

    /**
     * 删除用户的所有记忆
     */
    public void deleteByUserId(String userId) {
        dao.deleteByUserId(userId);
        invalidateCache(userId);
        log.info("[LongTermMemory] 删除用户所有记忆: {}", userId);
    }

    // ═══════════════════════════════════════════════════════════════
    // LLM 重要性评分
    // ═══════════════════════════════════════════════════════════════

    /**
     * 使用 LLM 评分重要性
     *
     * <p>评分标准：
     * <ul>
     *   <li>1.0：关键事实、用户偏好、重要决策、长期目标</li>
     *   <li>0.8：技术实现细节、代码规范、项目背景</li>
     *   <li>0.6：中等重要的工作状态、已完成的任务</li>
     *   <li>0.4：临时状态、测试输出、中间结果</li>
     *   <li>0.2：噪音、日志、不重要的事件</li>
     *   <li>0.1：应立即遗忘的信息</li>
     * </ul>
     */
    private double scoreImportance(String content, String context) {
        // 如果没有 LLM 服务，返回默认重要性
        if (llmService == null) {
            return 0.5;
        }

        String prompt = buildImportancePrompt(content, context);
        
        try {
            // 调用 LLM 评分
            Message response = llmService.chat(
                    List.of(new Message("user", prompt, java.time.Instant.now())),
                    "importance-scoring"
            ).block();
            
            if (response == null) return 0.5;
            // 解析 JSON 响应
            return parseImportanceResponse(response.getContent());
        } catch (Exception e) {
            log.warn("[LongTermMemory] LLM 评分失败: {}", e.getMessage());
            return 0.5;
        }
    }

    private String buildImportancePrompt(String content, String context) {
        return String.format("""
            你是一个记忆评分专家。请评估以下信息的重要性（0.0 ~ 1.0）。

            评分标准：
            - 1.0：关键事实、用户偏好、重要决策、长期目标
            - 0.8：技术实现细节、代码规范、项目背景
            - 0.6：中等重要的工作状态、已完成的任务
            - 0.4：临时状态、测试输出、中间结果
            - 0.2：噪音、日志、不重要的事件
            - 0.1：应立即遗忘的信息

            同时判断：
            - auto_forget: 是否应该在未来自动遗忘（true/false）
            - category: fact | preference | context | lesson | decision

            信息内容：%s
            上下文：%s

            输出 JSON（只需一行）：
            {"importance": 0.0~1.0, "auto_forget": true/false, "category": "..."}
            """, content, context != null ? context : "无");
    }

    private double parseImportanceResponse(String response) {
        try {
            // 简单 JSON 解析
            String json = response.trim();
            if (json.startsWith("```json")) {
                json = json.substring(7);
            }
            if (json.startsWith("```")) {
                json = json.substring(3);
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3);
            }
            json = json.trim();
            
            // 提取 importance
            double importance = 0.5;
            int impStart = json.indexOf("\"importance\"");
            if (impStart >= 0) {
                int colon = json.indexOf(":", impStart);
                int comma = json.indexOf(",", colon);
                if (comma < 0) comma = json.indexOf("}", colon);
                if (colon > 0 && comma > colon) {
                    String impStr = json.substring(colon + 1, comma).trim();
                    importance = Double.parseDouble(impStr);
                }
            }
            
            return Math.max(0.0, Math.min(1.0, importance));
        } catch (Exception e) {
            log.debug("[LongTermMemory] 解析评分失败: {}", e.getMessage());
            return 0.5;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 遗忘机制
    // ═══════════════════════════════════════════════════════════════

    /**
     * 执行遗忘周期（定时任务）
     */
    @Scheduled(fixedRate = 86400000)  // 每天执行一次
    public void runForgettingCycle() {
        if (!config.getMemory().isFtsEnabled()) {
            return;
        }

        log.info("[LongTermMemory] 开始遗忘周期...");
        
        try {
            // 获取所有用户
            Set<String> userIds = new HashSet<>();
            // 简化：只处理默认用户
            userIds.add("global");
            
            int totalForgotten = 0;
            for (String userId : userIds) {
                int count = forgetUser(userId);
                totalForgotten += count;
            }
            
            log.info("[LongTermMemory] 遗忘周期完成: 共删除 {} 条", totalForgotten);
        } catch (Exception e) {
            log.error("[LongTermMemory] 遗忘周期失败: {}", e.getMessage());
        }
    }

    /**
     * 执行用户的遗忘
     */
    private int forgetUser(String userId) {
        // 获取遗忘候选
        List<LongTermMemory> candidates = dao.getForgettingCandidates(userId, 100);
        if (candidates.isEmpty()) {
            return 0;
        }

        // 删除
        List<String> ids = candidates.stream()
                .map(LongTermMemory::getId)
                .collect(Collectors.toList());
        
        dao.batchDelete(ids);
        invalidateCache(userId);
        
        return ids.size();
    }

    // ═══════════════════════════════════════════════════════════════
    // 统计
    // ═══════════════════════════════════════════════════════════════

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStats(String userId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        
        stats.put("total", dao.countByUserId(userId));
        stats.put("byCategory", dao.countByCategory(userId));
        
        // 重要性分布
        List<LongTermMemory> high = dao.findByImportance(userId, 0.7, 100);
        List<LongTermMemory> medium = dao.findByImportance(userId, 0.4, 100);
        stats.put("highImportance", high.size());
        stats.put("mediumImportance", medium.size());
        
        // 遗忘候选
        List<LongTermMemory> candidates = dao.getForgettingCandidates(userId, 10);
        stats.put("forgettingCandidates", candidates.size());
        
        return stats;
    }

    // ═══════════════════════════════════════════════════════════════
    // 缓存管理
    // ═══════════════════════════════════════════════════════════════

    private void invalidateCache(String userId) {
        userMemoryCache.remove(userId);
        cacheTimestamp.remove(userId);
    }

    private List<LongTermMemory> getCached(String userId) {
        if (!userMemoryCache.containsKey(userId)) {
            return null;
        }
        
        Long ts = cacheTimestamp.get(userId);
        if (ts == null || System.currentTimeMillis() - ts > CACHE_TTL_MS) {
            invalidateCache(userId);
            return null;
        }
        
        return userMemoryCache.get(userId);
    }

    private void cache(String userId, List<LongTermMemory> memories) {
        if (memories.size() > CACHE_SIZE) {
            memories = memories.subList(0, CACHE_SIZE);
        }
        userMemoryCache.put(userId, memories);
        cacheTimestamp.put(userId, System.currentTimeMillis());
    }

    // ═══════════════════════════════════════════════════════════════════
    // Agent 集成
    // ═══════════════════════════════════════════════════════════════

    /**
     * Agent 每轮评估写入（由 Agent.java 调用）
     *
     * <p>在每轮对话结束后，评估是否值得写入长期记忆
     */
    public void evaluateAndStore(String sessionId, String userContent, 
                                  String assistantContent) {
        // 提取值得记住的信息
        String keyInfo = extractKeyInfo(userContent, assistantContent);
        if (keyInfo == null || keyInfo.isBlank()) {
            return;
        }

        // 用户偏好检测
        String preference = detectPreference(userContent, assistantContent);
        if (preference != null) {
            add(preference, Category.PREFERENCE, "global", sessionId, 
                    "用户偏好检测");
        }

        // 重要决策检测
        String decision = detectDecision(userContent, assistantContent);
        if (decision != null) {
            add(decision, Category.DECISION, "global", sessionId,
                    "决策检测");
        }

        // 用户身份/名字检测
        String identity = detectIdentity(userContent);
        if (identity != null) {
            add(identity, Category.PREFERENCE, "global", sessionId,
                    "身份信息检测");
        }

        // 用户目标/意图检测
        String goal = detectGoal(userContent);
        if (goal != null) {
            add(goal, Category.PREFERENCE, "global", sessionId,
                    "目标检测");
        }
    }

    private String extractKeyInfo(String userContent, String assistantContent) {
        // 降低阈值，允许写入更短的内容
        if (assistantContent != null && assistantContent.length() > 20) {
            return assistantContent.substring(0, Math.min(500, assistantContent.length()));
        }
        return assistantContent;
    }

    private String detectPreference(String userContent, String assistantContent) {
        // 检测用户偏好相关关键词（扩大范围）
        String lower = userContent.toLowerCase();
        if (lower.contains("喜欢") || lower.contains("偏好") ||
                lower.contains("爱好") || lower.contains("最爱") ||
                lower.contains("兴趣") || lower.contains("我爱") ||
                lower.contains("我喜欢") || lower.contains("我的爱好") ||
                lower.contains("interest") || lower.contains("hobby") ||
                lower.contains("love") || lower.contains("prefer") ||
                lower.contains("favorite") || lower.contains("enjoy") ||
                lower.contains("讨厌") || lower.contains("hate") ||
                lower.contains("不爱") || lower.contains("dont like") ||
                lower.contains("想要") || lower.contains("不想要")) {
            return userContent;
        }
        return null;
    }

    private String detectDecision(String userContent, String assistantContent) {
        // 检测决策类内容（从用户消息和助手回复中检测）
        String combined = userContent + " " + (assistantContent != null ? assistantContent : "");
        if (combined.contains("决定") || combined.contains("选择") ||
                combined.contains("decided") || combined.contains("choice") ||
                combined.contains("选了") || combined.contains("决定是") ||
                combined.contains("我决定") || combined.contains("我选择")) {
            return combined.substring(0, Math.min(200, combined.length()));
        }
        return null;
    }

    /**
     * 检测用户身份信息（名字、角色、职业等）
     */
    private String detectIdentity(String userContent) {
        String lower = userContent.toLowerCase();
        // 名字检测
        if (lower.contains("我叫") || lower.contains("我的名字") ||
                lower.contains("name is") || lower.contains("i'm ") ||
                lower.contains("i am ") || lower.contains("本人") ||
                lower.contains("本人在") || lower.contains("姓名")) {
            return userContent;
        }
        // 职业/角色检测
        if (lower.contains("我是") || lower.contains("职业是") ||
                lower.contains("做") || lower.contains("工作") ||
                lower.contains("i work") || lower.contains("i'm a") ||
                lower.contains("i am a") || lower.contains("职业")) {
            return userContent;
        }
        return null;
    }

    /**
     * 检测用户目标/意图
     */
    private String detectGoal(String userContent) {
        String lower = userContent.toLowerCase();
        if (lower.contains("想要") || lower.contains("希望") ||
                lower.contains("需要") || lower.contains("目标") ||
                lower.contains("目的是") || lower.contains("为了") ||
                lower.contains("want to") || lower.contains("need to") ||
                lower.contains("goal") || lower.contains("purpose") ||
                lower.contains("帮我") || lower.contains("请帮我") ||
                lower.contains("能不能") || lower.contains("是否可以")) {
            return userContent;
        }
        return null;
    }
}