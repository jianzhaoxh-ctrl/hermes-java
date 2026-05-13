package com.hermes.agent.gateway;

import com.hermes.agent.gateway.adapters.*;
import com.hermes.agent.Agent;
import com.hermes.agent.loop.AgentLoopService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gateway 服务 - 多平台消息集成
 * 
 * 职责：
 * - 管理平台适配器生命周期
 * - 路由消息到 Agent
 * - 会话管理（持久会话与重置策略）
 * - 动态上下文注入（Agent 知道消息来源）
 * - 投递路由（定时任务输出到合适频道）
 * - 平台特定工具集
 */
@Service
public class GatewayService {
    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final Agent agent;
    private final AgentLoopService agentLoopService;
    private final GatewayConfig config;
    private final SessionStore sessionStore;
    private final GatewayHooks hooks;
    private final ChannelDirectory channelDirectory;
    private final MediaCache mediaCache;
    private TranscriptManager transcriptManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 平台适配器注册表
    private final Map<Platform, BasePlatformAdapter> adapters = new ConcurrentHashMap<>();

    // 投递路由器（初始化适配器后创建）
    private DeliveryRouter deliveryRouter;

    public GatewayService(Agent agent, AgentLoopService agentLoopService, GatewayConfig config, SessionStore sessionStore,
                          GatewayHooks hooks,
                          ChannelDirectory channelDirectory, MediaCache mediaCache) {
        this.agent = agent;
        this.agentLoopService = agentLoopService;
        this.config = config;
        this.sessionStore = sessionStore;
        this.hooks = hooks;
        this.channelDirectory = channelDirectory;
        this.mediaCache = mediaCache;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing GatewayService...");

        // 注册内置钩子
        String workspaceDir = System.getProperty("user.home") + "/.hermes";
        hooks.registerBuiltinHooks(workspaceDir);

        // 触发启动钩子
        GatewayHooks.HookContext bootCtx = hooks.fire(GatewayHooks.HookPoint.ON_BOOT);
        if (bootCtx.isCancelled()) {
            log.warn("Gateway boot cancelled by hook: {}", bootCtx.getCancelReason());
            return;
        }

        initializeAdapters();

        // 初始化投递路由器（依赖 adapters）
        this.deliveryRouter = new DeliveryRouter(config, adapters);

        // 初始化 transcript 管理器
        this.transcriptManager = new TranscriptManager();
        try {
            this.transcriptManager.init();
        } catch (java.sql.SQLException e) {
            log.error("Failed to initialize transcript manager: {}", e.getMessage());
        }

        connectAllPlatforms();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down GatewayService...");
        hooks.fire(GatewayHooks.HookPoint.ON_SHUTDOWN);
        disconnectAllPlatforms();
        mediaCache.cleanupCache(0);  // 清理所有缓存
        if (transcriptManager != null) {
            transcriptManager.shutdown();
        }
    }

    /**
     * 初始化所有配置的平台适配器
     */
    private void initializeAdapters() {
        for (Platform platform : config.getConnectedPlatforms()) {
            PlatformConfig platformConfig = config.getPlatformConfig(platform);
            if (platformConfig == null || !platformConfig.isEnabled()) {
                continue;
            }

            BasePlatformAdapter adapter = createAdapter(platform, platformConfig);
            if (adapter != null) {
                adapter.setMessageHandler(event -> handleIncomingMessage(event));
                adapters.put(platform, adapter);
                log.info("Initialized adapter for platform: {}", platform.getValue());
            }
        }
    }

    /**
     * 创建平台适配器
     */
    private BasePlatformAdapter createAdapter(Platform platform, PlatformConfig platformConfig) {
        return switch (platform) {
            case TELEGRAM -> new TelegramAdapter(platformConfig);
            case FEISHU -> new FeishuAdapter(platformConfig);
            case DINGTALK -> new DingTalkAdapter(platformConfig);
            case WECOM -> new WeComAdapter(platformConfig);
            // TODO: 实现更多平台适配器
            case DISCORD -> new DiscordAdapter(platformConfig);
            case SLACK -> new SlackAdapter(platformConfig);
            case EMAIL -> new EmailAdapter(platformConfig);
            case WEBHOOK -> new WebhookAdapter(platformConfig);
            case WHATSAPP -> new WhatsAppAdapter(platformConfig);
            case SIGNAL -> new SignalAdapter(platformConfig);
            case MATTERMOST -> new MattermostAdapter(platformConfig);
            case MATRIX -> new MatrixAdapter(platformConfig);
            case QQBOT -> new QQBotAdapter(platformConfig);
            case WEIXIN -> new WeixinAdapter(platformConfig);
            case SMS -> new SMSAdapter(platformConfig);
            case HOMEASSISTANT -> new HomeAssistantAdapter(platformConfig);
            case BLUEBUBBLES -> new BlueBubblesAdapter(platformConfig);
            case WECOM_CALLBACK -> new WecomCallbackAdapter(platformConfig);
            case API_SERVER -> new ApiServerAdapter(platformConfig);
            case LOCAL -> null;
        };
    }

    /**
     * 连接所有平台（错误隔离：单个平台失败不影响其他平台）
     */
    private void connectAllPlatforms() {
        int total = adapters.size();
        log.info("Connecting {} platform(s)...", total);

        adapters.values().forEach(adapter -> {
            adapter.connect()
                .doOnSuccess(v -> log.info("Platform connected: {}", adapter.getName()))
                .doOnError(e -> log.warn("Platform connection failed (non-fatal): {}: {}",
                    adapter.getName(), e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
        });
    }

    /**
     * 断开所有平台
     */
    private void disconnectAllPlatforms() {
        adapters.values().forEach(adapter -> {
            try {
                adapter.disconnect().subscribe(
                    v -> {},
                    error -> log.error("Error disconnecting {}: {}", adapter.getName(), error.getMessage())
                );
            } catch (Exception e) {
                log.error("Error disconnecting {}: {}", adapter.getName(), e.getMessage());
            }
        });
        log.info("All platforms disconnected");
    }

    /**
     * 处理传入消息
     */
    private Mono<String> handleIncomingMessage(MessageEvent event) {
        SessionSource source = event.getSource();
        if (source == null) {
            return Mono.just("Error: No source in message event");
        }

        // 触发消息接收钩子
        Map<String, Object> hookData = new HashMap<>();
        hookData.put("event", event);
        hookData.put("source", source);
        GatewayHooks.HookContext hookCtx = hooks.fire(
            GatewayHooks.HookPoint.ON_MESSAGE_RECEIVED, hookData);
        if (hookCtx.isCancelled()) {
            log.info("Message processing cancelled by hook: {}", hookCtx.getCancelReason());
            return Mono.just("");
        }

        // 更新频道目录
        channelDirectory.updateFromSource(source);

        // 获取或创建会话
        SessionEntry session = sessionStore.getOrCreateSession(source, false);
        String sessionId = session.getSessionId();

        // 更新会话活动时间
        sessionStore.updateSession(session.getSessionKey());

        // 构建会话上下文
        String contextPrompt = buildSessionContextPrompt(source, session);

        // 触发处理开始钩子
        hooks.fire(GatewayHooks.HookPoint.ON_PROCESSING_START, hookData);

        // 调用 Agent 处理消息
        return Mono.fromCallable(() -> {
            try {
                // 注入会话上下文到系统提示
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("session_id", sessionId);
                context.put("session_key", session.getSessionKey());
                context.put("source", source.toMap());
                context.put("connected_platforms", 
                    config.getConnectedPlatforms().stream().map(Platform::getValue).toList());
                context.put("context_prompt", contextPrompt);

                // 频道特定系统提示
                if (event.getChannelPrompt() != null) {
                    context.put("channel_prompt", event.getChannelPrompt());
                }

                // 自动加载技能
                if (event.getAutoSkill() != null) {
                    context.put("auto_skill", event.getAutoSkill());
                }

                // 启动钩子注入的上下文
                Object bootAgentsMd = hookCtx.get("boot_agents.md", Object.class);
                if (bootAgentsMd != null) {
                    context.put("agents_md", bootAgentsMd);
                }

                // 调用 Agent（当前 Agent.chat 签名只接受2个参数，上下文通过系统提示注入）
                // TODO: 扩展 Agent.chat 签名支持 context 参数
                String response = agentLoopService.chat(sessionId, event.getText()).block();

                // 记录到 transcript
                if (transcriptManager != null) {
                    transcriptManager.appendToTranscript(
                        session.getSessionKey(), sessionId, "user", event.getText());
                    if (response != null) {
                        transcriptManager.appendToTranscript(
                            session.getSessionKey(), sessionId, "assistant", response);
                    }
                }

                // 触发处理完成钩子
                hookData.put("response", response);
                hooks.fire(GatewayHooks.HookPoint.ON_PROCESSING_COMPLETE, hookData);

                return response;
            } catch (Exception e) {
                log.error("Error processing message: {}", e.getMessage(), e);

                // 触发错误钩子
                hookData.put("error", e);
                hooks.fire(GatewayHooks.HookPoint.ON_ERROR, hookData);

                return "抱歉，处理消息时发生错误：" + e.getMessage();
            }
        });
    }

    /**
     * 构建会话上下文提示
     */
    private String buildSessionContextPrompt(SessionSource source, SessionEntry session) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前会话上下文\n\n");

        // 来源信息
        String platformName = source.getPlatform().getValue();
        if (source.getPlatform() == Platform.LOCAL) {
            sb.append("**来源:** ").append(platformName).append(" (运行此 Agent 的机器)\n");
        } else {
            sb.append("**来源:** ").append(platformName).append(" (").append(source.getDescription()).append(")\n");
        }

        // 频道主题
        if (source.getChatTopic() != null) {
            sb.append("**频道主题:** ").append(source.getChatTopic()).append("\n");
        }

        // 用户信息
        if (source.getUserName() != null) {
            sb.append("**用户:** ").append(source.getUserName()).append("\n");
        } else if (source.getUserId() != null) {
            sb.append("**用户 ID:** ").append(source.getUserId()).append("\n");
        }

        // 已连接平台
        List<String> platforms = new ArrayList<>();
        platforms.add("local (本机文件)");
        for (Platform p : config.getConnectedPlatforms()) {
            if (p != Platform.LOCAL) {
                platforms.add(p.getValue() + ": 已连接 ✓");
            }
        }
        sb.append("**已连接平台:** ").append(String.join(", ", platforms)).append("\n");

        // Home Channels
        Map<Platform, HomeChannel> homeChannels = new LinkedHashMap<>();
        for (Platform p : config.getConnectedPlatforms()) {
            HomeChannel hc = config.getHomeChannel(p);
            if (hc != null) {
                homeChannels.put(p, hc);
            }
        }

        if (!homeChannels.isEmpty()) {
            sb.append("\n**Home Channels (默认目标):**\n");
            for (Map.Entry<Platform, HomeChannel> entry : homeChannels.entrySet()) {
                sb.append("  - ").append(entry.getKey().getValue()).append(": ")
                  .append(entry.getValue().getName())
                  .append(" (ID: ").append(entry.getValue().getChatId()).append(")\n");
            }
        }

        // 投递选项
        sb.append("\n**定时任务投递选项:**\n");
        if (source.getPlatform() == Platform.LOCAL) {
            sb.append("- `\"origin\"` → 本地输出（保存到文件）\n");
        } else {
            sb.append("- `\"origin\"` → 返回此聊天 (").append(source.getChatName() != null ? 
                source.getChatName() : source.getChatId()).append(")\n");
        }
        sb.append("- `\"local\"` → 仅保存到本地文件\n");
        for (Platform p : homeChannels.keySet()) {
            sb.append("- `\"").append(p.getValue()).append("\"` → Home channel\n");
        }

        return sb.toString();
    }

    // ========== 公开 API ==========

    /**
     * 获取平台适配器
     */
    public Optional<BasePlatformAdapter> getAdapter(Platform platform) {
        return Optional.ofNullable(adapters.get(platform));
    }

    /**
     * 发送消息到指定平台
     */
    public Mono<SendResult> send(Platform platform, String chatId, String content) {
        BasePlatformAdapter adapter = adapters.get(platform);
        if (adapter == null) {
            return Mono.just(SendResult.failure("Platform not connected: " + platform.getValue()));
        }
        return adapter.send(chatId, content, null, null);
    }

    /**
     * 获取所有已连接的平台
     */
    public List<Platform> getConnectedPlatforms() {
        return adapters.entrySet().stream()
            .filter(e -> e.getValue().isConnected())
            .map(Map.Entry::getKey)
            .toList();
    }

    /**
     * 获取会话存储
     */
    public SessionStore getSessionStore() {
        return sessionStore;
    }

    /**
     * 获取 Gateway 配置
     */
    public GatewayConfig getConfig() {
        return config;
    }

    /**
     * 获取投递路由器
     */
    public DeliveryRouter getDeliveryRouter() {
        return deliveryRouter;
    }

    /**
     * 获取钩子系统
     */
    public GatewayHooks getHooks() {
        return hooks;
    }

    /**
     * 获取频道目录
     */
    public ChannelDirectory getChannelDirectory() {
        return channelDirectory;
    }

    /**
     * 获取媒体缓存
     */
    public MediaCache getMediaCache() {
        return mediaCache;
    }

    /**
     * 获取 transcript 管理器
     */
    public TranscriptManager getTranscriptManager() {
        return transcriptManager;
    }

    /**
     * 处理飞书 Webhook 回调
     */
    public Mono<String> handleFeishuWebhook(String body, Map<String, String> headers) {
        return getAdapter(Platform.FEISHU)
            .map(adapter -> ((FeishuAdapter) adapter).handleWebhookEvent(body, headers)
                .then(Mono.just("ok")))
            .orElse(Mono.just("Platform not configured"));
    }

    /**
     * 处理钉钉回调
     */
    public Mono<String> handleDingTalkCallback(String body, Map<String, String> headers) {
        return getAdapter(Platform.DINGTALK)
            .map(adapter -> ((DingTalkAdapter) adapter).handleCallback(body, headers)
                .then(Mono.just("ok")))
            .orElse(Mono.just("Platform not configured"));
    }

    // ========== 内部消息类 ==========

    /**
     * 网关消息
     */
    public static class GatewayMessage {
        private String id;
        private String chatId;
        private String userId;
        private String content;
        private String platform;
        private Instant timestamp;

        public GatewayMessage() {
            this.id = UUID.randomUUID().toString();
            this.timestamp = Instant.now();
        }

        // Getters and Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getChatId() { return chatId; }
        public void setChatId(String chatId) { this.chatId = chatId; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getPlatform() { return platform; }
        public void setPlatform(String platform) { this.platform = platform; }
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    }
}
