package com.hermes.agent;

import com.hermes.agent.autonomous.SkillGenerator;
import com.hermes.agent.config.AgentConfig;
import com.hermes.agent.llm.LLMService;
import com.hermes.agent.memory.*;
import com.hermes.agent.memory.longterm.LongTermMemoryManager;
import com.hermes.agent.memory.longterm.UserProfileExtService;
import com.hermes.agent.model.Message;
import com.hermes.agent.skills.SkillSystem;
import com.hermes.agent.subagent.SubAgentService;
import com.hermes.agent.tools.ToolRegistry;
import com.hermes.agent.userprofile.UserProfileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    /** 摘要触发阈值（超过此数量条消息时触发摘要） */
    private static final int SUMMARY_THRESHOLD = 50;

    /** 摘要后保留最近消息数 */
    private static final int SUMMARY_KEEP_LAST = 10;

    private final LLMService llmService;
    private final MemoryManager memoryManager;
    private final SessionSummaryManager summaryManager;
    private final UserProfileManager userProfileManager;
    private final SkillSystem skillSystem;
    private final ToolRegistry toolRegistry;
    private final SubAgentService subAgentService;
    private final SkillGenerator skillGenerator;
    private final AgentConfig config;
    private final MemoryOrchestrator memoryOrchestrator;
    private final SessionSearchService searchService;
    private final LongTermMemoryManager longTermMemoryManager;
    private final UserProfileExtService userProfileExtService;
    private final Map<String, ConversationContext> sessions = new ConcurrentHashMap<>();

    /** 会话空闲超时时间（默认 30 分钟无活动则淘汰） */
    private static final Duration SESSION_IDLE_TIMEOUT = Duration.ofMinutes(30);

    /** 定期清理过期会话的调度器 */
    private final ScheduledExecutorService evictionScheduler;

    public Agent(LLMService llmService,
                 MemoryManager memoryManager,
                 SessionSummaryManager summaryManager,
                 UserProfileManager userProfileManager,
                 SkillSystem skillSystem,
                 ToolRegistry toolRegistry,
                 SubAgentService subAgentService,
                 SkillGenerator skillGenerator,
                 AgentConfig config,
                 MemoryOrchestrator memoryOrchestrator,
                 SessionSearchService searchService,
                 LongTermMemoryManager longTermMemoryManager,
                 UserProfileExtService userProfileExtService) {
        this.llmService = llmService;
        this.memoryManager = memoryManager;
        this.summaryManager = summaryManager;
        this.userProfileManager = userProfileManager;
        this.skillSystem = skillSystem;
        this.toolRegistry = toolRegistry;
        this.subAgentService = subAgentService;
        this.skillGenerator = skillGenerator;
        this.config = config;
        this.memoryOrchestrator = memoryOrchestrator;
        this.searchService = searchService;
        this.longTermMemoryManager = longTermMemoryManager;
        this.userProfileExtService = userProfileExtService;
        this.evictionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-session-eviction");
            t.setDaemon(true);
            return t;
        });
        // 每 5 分钟检查一次过期会话
        this.evictionScheduler.scheduleAtFixedRate(
                this::evictExpiredSessions, 5, 5, TimeUnit.MINUTES);
    }

    public Mono<String> chat(String sessionId, String userMessage) {
        return getOrCreateSession(sessionId)
                .flatMap(ctx -> processMessage(ctx, userMessage));
    }

    public Flux<String> chatStream(String sessionId, String userMessage) {
        return getOrCreateSession(sessionId)
                .flatMapMany(ctx -> processMessageStream(ctx, userMessage));
    }

    private Mono<ConversationContext> getOrCreateSession(String sessionId) {
        return Mono.defer(() -> {
            ConversationContext ctx = sessions.computeIfAbsent(sessionId,
                    k -> {
                        ConversationContext newCtx = new ConversationContext(sessionId);
                        // 初始化记忆 Provider
                        memoryOrchestrator.initializeAll(sessionId, Map.of());
                        
                        // 加载历史消息（会话恢复功能）
                        loadSessionHistory(newCtx);
                        
                        return newCtx;
                    });
            return Mono.just(ctx);
        });
    }
    
    /**
     * 加载会话历史消息并预取相关长期记忆
     * 实现会话恢复功能
     */
    private void loadSessionHistory(ConversationContext ctx) {
        String sessionId = ctx.getSessionId();
        
        // 1. 从内存缓存获取历史消息
        List<Message> history = memoryManager.getSessionHistory(sessionId);
        
        // 2. 如果缓存为空，尝试从数据库加载
        if (history.isEmpty()) {
            try {
                // 直接从后端加载
                var backend = memoryManager.getClass().getDeclaredField("backend");
                backend.setAccessible(true);
                Object be = backend.get(memoryManager);
                if (be != null) {
                    var method = be.getClass().getMethod("getSessionMessages", String.class);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rawMessages = (List<Map<String, Object>>) method.invoke(be, sessionId);
                    if (rawMessages != null && !rawMessages.isEmpty()) {
                        history = rawMessages.stream()
                                .map(this::mapToMessage)
                                .filter(m -> m != null)
                                .collect(java.util.stream.Collectors.toList());
                    }
                }
            } catch (Exception e) {
                log.debug("[Agent] 从数据库加载会话消息失败: {}", e.getMessage());
            }
        }
        
        // 3. 将历史消息添加到会话上下文
        if (!history.isEmpty()) {
            for (Message msg : history) {
                ctx.addMessage(msg);
            }
            log.info("[Agent] 会话恢复: {} 加载了 {} 条历史消息", sessionId, history.size());
            
            // 4. 基于历史消息预取相关长期记忆
            prefetchLongTermMemory(ctx);
        }
    }
    
    /**
     * 基于会话历史预取相关长期记忆
     */
    private void prefetchLongTermMemory(ConversationContext ctx) {
        try {
            // 获取最近几条消息用于匹配
            List<Message> recentMessages = ctx.getHistory(5);
            StringBuilder content = new StringBuilder();
            for (Message msg : recentMessages) {
                content.append(msg.getContent()).append(" ");
            }
            
            // 触发预取
            String context = longTermMemoryManager.buildPrefetchContext(
                    content.toString(),
                    ctx.getSessionId(),
                    ctx.getUserId(),
                    3
            );
            
            if (context != null && !context.isBlank()) {
                ctx.injectLongTermMemoryContext(context);
                log.info("[Agent] 会话 {} 预取了长期记忆", ctx.getSessionId());
            }
        } catch (Exception e) {
            log.warn("[Agent] 预取长期记忆失败: {}", e.getMessage());
        }
    }
    
    /**
     * 将 Map 转换为 Message 对象
     */
    private Message mapToMessage(Map<String, Object> map) {
        if (map == null) return null;
        try {
            String role = (String) map.get("role");
            String content = (String) map.get("content");
            Object tsObj = map.get("timestamp");
            Instant timestamp;
            if (tsObj instanceof Instant) {
                timestamp = (Instant) tsObj;
            } else if (tsObj instanceof String) {
                timestamp = Instant.parse((String) tsObj);
            } else {
                timestamp = Instant.now();
            }
            return new Message(role, content, timestamp);
        } catch (Exception e) {
            return null;
        }
    }

    private Mono<String> processMessage(ConversationContext ctx, String userMessage) {
        // 1. 保存用户消息
        ctx.addMessage(new Message("user", userMessage, Instant.now()));
        memoryManager.saveMessage(ctx.getSessionId(), new Message("user", userMessage, Instant.now()));

        // 2. 索引到全文搜索
        searchService.indexDocument(ctx.getSessionId(), "user", userMessage);

        // 3. 获取历史（从 ConversationContext 中取，包含摘要后的压缩链）
        List<Message> history = ctx.getHistory(config.getMaxHistory());

        // 4. 注入用户画像上下文（如果有的话）
        String profilePrompt = userProfileManager.getContextPrompt(ctx.getSessionId());
        if (!profilePrompt.isEmpty()) {
            if (!ctx.hasInjectedProfile()) {
                ctx.injectProfilePrompt(profilePrompt);
            }
        }

        // 4.1 注入增强版用户画像（含专业度、学习模式、关键记忆）
        String enhancedProfile = userProfileExtService.buildEnhancedContext(ctx.getUserId());
        if (!enhancedProfile.isEmpty() && !enhancedProfile.equals(profilePrompt)) {
            ctx.injectProfilePrompt(enhancedProfile);
        }

        // 5. 注入记忆上下文（MEMORY.md / USER.md 快照 + prefetch）
        String memoryContext = memoryOrchestrator.prefetchAll(userMessage, ctx.getSessionId());
        if (!memoryContext.isEmpty() && !ctx.hasInjectedMemory()) {
            ctx.injectMemoryContext(memoryContext);
        }

        // 5.1 注入长期记忆上下文（跨会话记忆）
        String longTermContext = longTermMemoryManager.buildPrefetchContext(
                userMessage, ctx.getSessionId(), ctx.getUserId());
        if (!longTermContext.isEmpty()) {
            ctx.injectLongTermMemoryContext(longTermContext);
        }

        // 6. 收集 Provider 的系统提示块
        String memorySystemPrompt = memoryOrchestrator.buildSystemPrompt();
        if (!memorySystemPrompt.isEmpty() && !ctx.hasInjectedMemorySystem()) {
            ctx.injectMemorySystemPrompt(memorySystemPrompt);
        }

        // 7. 通知 Provider 新轮次开始
        memoryOrchestrator.onTurnStartAll(ctx.getMessages().size(), userMessage, Map.of());

        // 8. 调用 LLM（支持 Function Calling，包括 memory / session_search 工具）
        return llmService.chatWithTools(history, ctx.getSessionId())
                .doOnNext(response -> {
                    ctx.addMessage(response);
                    memoryManager.saveMessage(ctx.getSessionId(), response);

                    // 索引助手回复
                    if (response.getContent() != null) {
                        searchService.indexDocument(ctx.getSessionId(), "assistant", response.getContent());
                    }

                    // ─── 四类记忆自动维护 ───

                    // 【机制一】会话摘要：消息数超阈值时自动压缩
                    boolean summarized = summaryManager.checkAndSummarize(
                            ctx.getSessionId(),
                            ctx.getMessages(),
                            SUMMARY_THRESHOLD,
                            SUMMARY_KEEP_LAST
                    );
                    if (summarized) {
                        log.info("[Agent] 会话 {} 已压缩，摘要次数: {}",
                                ctx.getSessionId(), summaryManager.getSummaryCount(ctx.getSessionId()));
                        // 压缩后重置注入标志，确保下一轮重新注入上下文
                        ctx.resetInjectionFlags();
                    }

                    // 【机制二】用户画像提取：异步从对话中识别用户背景/偏好
                    userProfileManager.extractAndUpdateProfile(
                            ctx.getSessionId(),
                            userMessage,
                            response.getContent()
                    );

                    // 【机制三】同步对话轮次到记忆 Provider
                    memoryOrchestrator.syncAll(userMessage,
                            response.getContent() != null ? response.getContent() : "",
                            ctx.getSessionId());

                    // 【机制四】后台预取下一轮记忆
                    memoryOrchestrator.queuePrefetchAll(userMessage, ctx.getSessionId());

                    // 【机制五】长期记忆评估写入：每轮对话后评估是否值得写入长期记忆
                    longTermMemoryManager.evaluateAndStore(
                            ctx.getSessionId(),
                            userMessage,
                            response.getContent() != null ? response.getContent() : ""
                    );

                    // 【机制六】用户画像融合：学习模式识别 + 画像↔长期记忆双向同步
                    userProfileExtService.detectLearnedPattern(
                            ctx.getUserId(),
                            userMessage,
                            response.getContent() != null ? response.getContent() : ""
                    );
                    userProfileExtService.syncProfileToLongTermMemory(ctx.getUserId());
                    userProfileExtService.incrementConversationCount(ctx.getUserId());
                })
                .map(Message::getContent);
    }

    /*private Flux<String> processMessageStream(ConversationContext ctx, String userMessage) {
        ctx.addMessage(new Message("user", userMessage, Instant.now()));
        memoryManager.saveMessage(ctx.getSessionId(), new Message("user", userMessage, Instant.now()));
        searchService.indexDocument(ctx.getSessionId(), "user", userMessage);

        List<Message> history = ctx.getHistory(config.getMaxHistory());

        String profilePrompt = userProfileManager.getContextPrompt(ctx.getSessionId());
        if (!profilePrompt.isEmpty() && !ctx.hasInjectedProfile()) {
            ctx.injectProfilePrompt(profilePrompt);
        }

        String memoryContext = memoryOrchestrator.prefetchAll(userMessage, ctx.getSessionId());
        if (!memoryContext.isEmpty() && !ctx.hasInjectedMemory()) {
            ctx.injectMemoryContext(memoryContext);
        }

        String memorySystemPrompt = memoryOrchestrator.buildSystemPrompt();
        if (!memorySystemPrompt.isEmpty() && !ctx.hasInjectedMemorySystem()) {
            ctx.injectMemorySystemPrompt(memorySystemPrompt);
        }

        StringBuilder full = new StringBuilder();

        return llmService.chatStream(history, ctx.getSessionId())
                .doOnNext(full::append)
                .doOnComplete(() -> {
                    String content = full.toString();
                    Message assistantMsg = new Message("assistant", content, Instant.now());
                    ctx.addMessage(assistantMsg);
                    memoryManager.saveMessage(ctx.getSessionId(), assistantMsg);
                    searchService.indexDocument(ctx.getSessionId(), "assistant", content);

                    summaryManager.checkAndSummarize(ctx.getSessionId(), ctx.getMessages(),
                            SUMMARY_THRESHOLD, SUMMARY_KEEP_LAST);
                    userProfileManager.extractAndUpdateProfile(
                            ctx.getSessionId(), userMessage, content);
                    memoryOrchestrator.syncAll(userMessage, content, ctx.getSessionId());
                    memoryOrchestrator.queuePrefetchAll(userMessage, ctx.getSessionId());
                });
    }*/

    private Flux<String> processMessageStream(ConversationContext ctx, String userMessage) {
        ctx.addMessage(new Message("user", userMessage, Instant.now()));
        memoryManager.saveMessage(ctx.getSessionId(), new Message("user", userMessage, Instant.now()));
        searchService.indexDocument(ctx.getSessionId(), "user", userMessage);

        List<Message> history = ctx.getHistory(config.getMaxHistory());

        String profilePrompt = userProfileManager.getContextPrompt(ctx.getSessionId());
        if (!profilePrompt.isEmpty() && !ctx.hasInjectedProfile()) {
            ctx.injectProfilePrompt(profilePrompt);
        }

        String memoryContext = memoryOrchestrator.prefetchAll(userMessage, ctx.getSessionId());
        if (!memoryContext.isEmpty() && !ctx.hasInjectedMemory()) {
            ctx.injectMemoryContext(memoryContext);
        }

        String memorySystemPrompt = memoryOrchestrator.buildSystemPrompt();
        if (!memorySystemPrompt.isEmpty() && !ctx.hasInjectedMemorySystem()) {
            ctx.injectMemorySystemPrompt(memorySystemPrompt);
        }

        // 用来保存上一次的完整文本，计算增量
        AtomicReference<String> lastFullText = new AtomicReference("");

        return llmService.chatStream(history, ctx.getSessionId())
                .map(chunk -> {
                    // 重点：计算【增量】，只返回新增的文字！
                    String last = lastFullText.get();
                    String delta = chunk.substring(Math.min(last.length(), chunk.length()));
                    lastFullText.set(chunk);
                    return delta;
                })
                .filter(StringUtils::hasText)
                .doOnComplete(() -> {
                    String content = lastFullText.get();
                    Message assistantMsg = new Message("assistant", content, Instant.now());
                    ctx.addMessage(assistantMsg);
                    memoryManager.saveMessage(ctx.getSessionId(), assistantMsg);
                    searchService.indexDocument(ctx.getSessionId(), "assistant", content);

                    summaryManager.checkAndSummarize(ctx.getSessionId(), ctx.getMessages(),
                            SUMMARY_THRESHOLD, SUMMARY_KEEP_LAST);
                    userProfileManager.extractAndUpdateProfile(
                            ctx.getSessionId(), userMessage, content);
                    memoryOrchestrator.syncAll(userMessage, content, ctx.getSessionId());
                    memoryOrchestrator.queuePrefetchAll(userMessage, ctx.getSessionId());
                });
    }

    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
        memoryManager.clearSession(sessionId);
        searchService.clearSession(sessionId);
        summaryManager.reset(sessionId);
    }

    /**
     * 定期淘汰空闲超时的会话，防止内存泄漏。
     * 每次检查会移除超过 SESSION_IDLE_TIMEOUT 未活动的会话。
     */
    private void evictExpiredSessions() {
        try {
            Instant cutoff = Instant.now().minus(SESSION_IDLE_TIMEOUT);
            int evicted = 0;
            for (Map.Entry<String, ConversationContext> entry : sessions.entrySet()) {
                if (entry.getValue().getLastUpdated().isBefore(cutoff)) {
                    String sid = entry.getKey();
                    sessions.remove(sid);
                    evicted++;
                    log.info("[Agent] Evicted idle session {} (last activity: {})",
                            sid, entry.getValue().getLastUpdated());
                }
            }
            if (evicted > 0) {
                log.info("[Agent] Session eviction complete: {} sessions removed ({} active)",
                        evicted, sessions.size());
            }
        } catch (Exception e) {
            log.error("[Agent] Session eviction failed: {}", e.getMessage());
        }
    }

    public Set<String> getActiveSessions() {
        return new HashSet<>(sessions.keySet());
    }

    public int getSessionMessageCount(String sessionId) {
        ConversationContext ctx = sessions.get(sessionId);
        return ctx != null ? ctx.getMessages().size() : 0;
    }

    // ─────────────────────────── 内部类 ───────────────────────────

    public static class ConversationContext {
        private final String sessionId;
        private String userId = "global";
        private final List<Message> messages = new ArrayList<>();
        private Instant lastUpdated;
        private boolean profileInjected;
        private boolean memoryInjected;
        private boolean memorySystemInjected;
        private boolean longTermMemoryInjected;

        public ConversationContext(String sessionId) {
            this.sessionId = sessionId;
            this.lastUpdated = Instant.now();
            this.profileInjected = false;
            this.memoryInjected = false;
            this.memorySystemInjected = false;
            this.longTermMemoryInjected = false;
        }

        public String getSessionId() { return sessionId; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        public synchronized void addMessage(Message msg) {
            messages.add(msg);
            lastUpdated = Instant.now();
        }

        public synchronized void injectProfilePrompt(String prompt) {
            if (profileInjected) return;
            Message profileMsg = new Message("system", prompt, Instant.now());
            messages.add(0, profileMsg);
            profileInjected = true;
        }

        public synchronized void injectMemoryContext(String context) {
            if (memoryInjected) return;
            Message memMsg = new Message("system", context, Instant.now());
            messages.add(0, memMsg);
            memoryInjected = true;
        }

        public synchronized void injectMemorySystemPrompt(String prompt) {
            if (memorySystemInjected) return;
            Message sysMsg = new Message("system", prompt, Instant.now());
            messages.add(0, sysMsg);
            memorySystemInjected = true;
        }

        public synchronized void injectLongTermMemoryContext(String context) {
            if (longTermMemoryInjected) return;
            Message memMsg = new Message("system", "[长期记忆]\n" + context, Instant.now());
            messages.add(0, memMsg);
            longTermMemoryInjected = true;
        }

        public boolean hasInjectedProfile() { return profileInjected; }
        public boolean hasInjectedMemory() { return memoryInjected; }
        public boolean hasInjectedMemorySystem() { return memorySystemInjected; }
        public boolean hasInjectedLongTermMemory() { return longTermMemoryInjected; }

        public List<Message> getHistory(int max) {
            int start = Math.max(0, messages.size() - max);
            return new ArrayList<>(messages.subList(start, messages.size()));
        }

        public List<Message> getMessages() { return messages; }

        public Instant getLastUpdated() { return lastUpdated; }

        /** 压缩后重置注入标志，确保下一轮重新注入上下文 */
        public synchronized void resetInjectionFlags() {
            profileInjected = false;
            memoryInjected = false;
            memorySystemInjected = false;
            longTermMemoryInjected = false;
        }
    }

    /** 仅供 ChatController 获取内存中实时消息（不含已压缩到摘要的） */
    public List<Map<String, Object>> getInMemoryMessages(String sessionId) {
        ConversationContext ctx = sessions.get(sessionId);
        if (ctx == null) return List.of();
        return ctx.getMessages().stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", "");
            map.put("role", m.getRole());
            map.put("content", m.getContent());
            map.put("timestamp", m.getTimestamp() != null ? m.getTimestamp().toEpochMilli() : 0L);
            return map;
        }).collect(Collectors.toList());
    }
}
