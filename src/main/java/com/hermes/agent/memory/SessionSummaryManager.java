package com.hermes.agent.memory;

import com.hermes.agent.config.AgentConfig;
import com.hermes.agent.llm.LLMService;
import com.hermes.agent.model.Message;
import com.hermes.agent.persistence.PersistenceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话摘要管理器 - 重构版
 * 
 * 使用 ContextCompressor 执行高级压缩（Token 预算、结构化摘要、工具输出修剪）
 * 保留原有的持久化功能
 */
@Component
public class SessionSummaryManager {

    private static final Logger log = LoggerFactory.getLogger(SessionSummaryManager.class);

    private final LLMService llmService;
    private final AgentConfig config;
    private final PersistenceService persistence;
    private final ContextCompressor compressor;

    /** sessionId → 摘要次数 */
    private final Map<String, Integer> summaryCount = new ConcurrentHashMap<>();
    /** sessionId → 最后一个摘要文本 */
    private final Map<String, String> lastSummary = new ConcurrentHashMap<>();
    /** sessionId → ContextCompressor 实例（每个会话独立） */
    private final Map<String, ContextCompressor> sessionCompressors = new ConcurrentHashMap<>();

    public SessionSummaryManager(LLMService llmService, AgentConfig config,
                                  PersistenceService persistence,
                                  ContextCompressor defaultCompressor) {
        this.llmService = llmService;
        this.config = config;
        this.persistence = persistence;
        this.compressor = defaultCompressor;
    }

    @PostConstruct
    public void loadFromDisk() {
        try {
            Map<String, Map<String, Object>> saved = persistence.loadSessionSummaries();
            for (Map.Entry<String, Map<String, Object>> e : saved.entrySet()) {
                String sid = e.getKey();
                Object count = e.getValue().get("summaryCount");
                Object summary = e.getValue().get("lastSummary");
                if (count instanceof Number) summaryCount.put(sid, ((Number) count).intValue());
                if (summary instanceof String) lastSummary.put(sid, (String) summary);
            }
            log.info("[Summary] 加载摘要状态 {} 个会话", summaryCount.size());
        } catch (Exception e) {
            log.warn("[Summary] 加载失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void saveToDisk() {
        try {
            Map<String, Map<String, Object>> data = new HashMap<>();
            for (String sid : summaryCount.keySet()) {
                Map<String, Object> state = new HashMap<>();
                state.put("summaryCount", summaryCount.getOrDefault(sid, 0));
                state.put("lastSummary", lastSummary.getOrDefault(sid, ""));
                data.put(sid, state);
            }
            persistence.saveSessionSummariesSync(data);
            log.info("[Summary] 摘要状态已保存到磁盘");
        } catch (Exception e) {
            log.error("[Summary] 保存失败: {}", e.getMessage());
        }
    }

    /**
     * 检查并执行高级压缩（使用 ContextCompressor）
     * 
     * @param sessionId 会话 ID
     * @param allMessages 所有消息列表（会被直接修改）
     * @param currentTokens 当前 token 数（可选，传 0 则自动估算）
     * @param focusTopic 焦点主题（可选，用于定向压缩）
     * @return 是否执行了压缩
     */
    public boolean checkAndSummarizeAdvanced(String sessionId, List<Message> allMessages,
                                               int currentTokens, String focusTopic) {
        // 获取或创建会话专用的压缩器
        ContextCompressor sessionCompressor = getOrCreateCompressor(sessionId);
        
        // 估算 token 数
        int tokens = currentTokens > 0 ? currentTokens : sessionCompressor.estimateTokens(allMessages);
        
        // 检查是否需要压缩
        if (!sessionCompressor.shouldCompress(allMessages, tokens)) {
            return false;
        }
        
        // 执行压缩
        List<Message> compressed = sessionCompressor.compress(allMessages, tokens, sessionId, focusTopic);
        
        // 更新原列表
        allMessages.clear();
        allMessages.addAll(compressed);
        
        // 更新计数和持久化
        int count = summaryCount.getOrDefault(sessionId, 0);
        summaryCount.put(sessionId, count + 1);
        
        // 保存最新摘要
        sessionCompressor.getPreviousSummary().ifPresent(s -> lastSummary.put(sessionId, s));
        
        persistence.saveSessionSummariesAsync(toStateMap());
        
        log.info("[Summary] 高级压缩完成: session={}, 消息数={}, 摘要次数={}", 
            sessionId, allMessages.size(), count + 1);
        
        return true;
    }
    
    /**
     * 简化版接口（向后兼容）
     */
    public boolean checkAndSummarize(String sessionId, List<Message> allMessages,
                                      int maxBeforeSummary, int keepLastN) {
        // 使用新的高级压缩逻辑
        ContextCompressor sessionCompressor = getOrCreateCompressor(sessionId);
        int tokens = sessionCompressor.estimateTokens(allMessages);
        
        // 消息数阈值检查（保留原有逻辑作为兜底）
        if (allMessages.size() < maxBeforeSummary && tokens < sessionCompressor.getThresholdTokens()) {
            return false;
        }
        
        return checkAndSummarizeAdvanced(sessionId, allMessages, tokens, null);
    }

    /**
     * 默认参数版本
     */
    public boolean checkAndSummarize(String sessionId, List<Message> allMessages) {
        return checkAndSummarize(sessionId, allMessages, config.getMaxHistory(), 10);
    }

    public Optional<String> getLastSummary(String sessionId) {
        return Optional.ofNullable(lastSummary.get(sessionId));
    }

    public int getSummaryCount(String sessionId) {
        return summaryCount.getOrDefault(sessionId, 0);
    }

    public void reset(String sessionId) {
        summaryCount.remove(sessionId);
        lastSummary.remove(sessionId);
        
        // 重置压缩器状态
        ContextCompressor sc = sessionCompressors.get(sessionId);
        if (sc != null) {
            sc.reset();
        }
        sessionCompressors.remove(sessionId);
        
        persistence.saveSessionSummariesAsync(toStateMap());
    }

    // ── private ────────────────────────────────────────────────────

    /**
     * 获取或创建会话专用的压缩器
     */
    private ContextCompressor getOrCreateCompressor(String sessionId) {
        return sessionCompressors.computeIfAbsent(sessionId, 
            k -> new ContextCompressor(llmService, config));
    }

    private Map<String, Map<String, Object>> toStateMap() {
        Map<String, Map<String, Object>> m = new HashMap<>();
        for (String sid : summaryCount.keySet()) {
            Map<String, Object> state = new HashMap<>();
            state.put("summaryCount", summaryCount.getOrDefault(sid, 0));
            state.put("lastSummary", lastSummary.getOrDefault(sid, ""));
            m.put(sid, state);
        }
        return m;
    }
}
