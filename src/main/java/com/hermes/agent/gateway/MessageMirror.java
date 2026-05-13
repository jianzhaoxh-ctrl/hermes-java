package com.hermes.agent.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息镜像模块
 *
 * 实现跨平台消息同步/镜像。当某个平台收到消息时，
 * 可以自动将消息（或其摘要）转发到其他平台。
 *
 * 使用场景：
 * - Telegram ↔ Discord 双向同步
 * - 飞书消息镜像到 Slack
 * - 企业微信 ↔ 钉钉 消息桥接
 * - 多平台通知广播
 *
 * 配置示例（config.yaml）：
 * <pre>
 * gateway:
 *   mirrors:
 *     - name: "tg-dc-sync"
 *       source: telegram
 *       targets: [discord, slack]
 *       chat_mapping:
 *         "telegram_chat_1": ["discord_channel_1", "slack_channel_1"]
 *       bidirectional: true
 *       sync_user_info: true        # 转发时附带原始用户名
 *       sync_attachments: true      # 转发附件/媒体
 *       sync_reactions: false       # 不同步反应
 *       format_prefix: "[Mirror]"   # 消息前缀
 *       exclude_bots: true          # 排除机器人消息
 * </pre>
 */
public class MessageMirror {

    private static final Logger log = LoggerFactory.getLogger(MessageMirror.class);

    private final GatewayConfig config;
    private final Map<Platform, BasePlatformAdapter> adapters;

    // 镜像规则注册表
    private final List<MirrorRule> rules = new ArrayList<>();

    // 消息去重（防止循环镜像）
    private final Map<String, Long> forwardedMessages = new ConcurrentHashMap<>();
    private static final long DEDUP_TTL_MS = 60_000;  // 1 分钟去重窗口

    public MessageMirror(GatewayConfig config, Map<Platform, BasePlatformAdapter> adapters) {
        this.config = config;
        this.adapters = adapters;
    }

    /**
     * 初始化镜像规则
     */
    public void initialize() {
        // 从配置加载镜像规则
        Object mirrorsObj = config.getQuickCommands().get("mirrors");
        if (mirrorsObj == null) {
            log.info("No mirror rules configured");
            return;
        }

        if (mirrorsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> mc) {
                    try {
                        @SuppressWarnings("unchecked")
                        MirrorRule rule = parseMirrorRule((Map<String, Object>) mc);
                        if (rule != null) {
                            rules.add(rule);
                            log.info("Loaded mirror rule: {} ({} → {})",
                                rule.name, rule.source, rule.targets);
                        }
                    } catch (Exception e) {
                        log.error("Failed to parse mirror rule: {}", e.getMessage());
                    }
                }
            }
        }

        // 启动去重清理
        startDedupCleanup();
    }

    /**
     * 处理消息镜像
     *
     * 在 GatewayService.handleIncomingMessage 中调用
     *
     * @param event 原始消息事件
     * @param response Agent 的响应
     */
    public void mirrorMessage(MessageEvent event, String response) {
        if (rules.isEmpty()) return;

        SessionSource source = event.getSource();
        if (source == null) return;

        Platform sourcePlatform = source.getPlatform();
        String sourceChatId = source.getChatId();
        String sourceMessageId = event.getMessageId();

        // 去重检查
        String dedupKey = sourcePlatform.getValue() + ":" + sourceMessageId;
        if (forwardedMessages.containsKey(dedupKey)) {
            return;  // 已转发过，跳过
        }

        for (MirrorRule rule : rules) {
            if (!rule.matchesSource(sourcePlatform)) continue;

            // 查找目标聊天 ID
            List<MirrorTarget> targets = rule.resolveTargets(sourcePlatform, sourceChatId);

            for (MirrorTarget target : targets) {
                if (rule.isBidirectional()) {
                    // 双向镜像：检查目标平台是否有反向规则
                    String reverseKey = target.platform.getValue() + ":" + sourceMessageId;
                    if (forwardedMessages.containsKey(reverseKey)) continue;
                }

                // 构建镜像消息
                String mirrorContent = buildMirrorContent(event, response, rule);

                // 发送到目标平台
                sendToTarget(target, mirrorContent, rule);

                // 标记已转发
                forwardedMessages.put(dedupKey, System.currentTimeMillis());
            }
        }
    }

    /**
     * 仅镜像用户消息（不含 Agent 响应）
     */
    public void mirrorUserMessage(MessageEvent event) {
        mirrorMessage(event, null);
    }

    // ========== 镜像内容构建 ==========

    private String buildMirrorContent(MessageEvent event, String response, MirrorRule rule) {
        StringBuilder sb = new StringBuilder();

        // 前缀
        if (rule.formatPrefix != null && !rule.formatPrefix.isBlank()) {
            sb.append(rule.formatPrefix).append(" ");
        }

        // 用户信息
        if (rule.syncUserInfo && event.getSource() != null) {
            String userName = event.getSource().getUserName();
            String platform = event.getSource().getPlatform().getValue();
            if (userName != null) {
                sb.append("[").append(userName).append("@").append(platform).append("] ");
            }
        }

        // 原始消息
        sb.append(event.getText());

        // Agent 响应
        if (response != null && !response.isBlank()) {
            sb.append("\n\n🤖 ").append(response);
        }

        return sb.toString();
    }

    // ========== 发送到目标 ==========

    private void sendToTarget(MirrorTarget target, String content, MirrorRule rule) {
        BasePlatformAdapter adapter = adapters.get(target.platform);
        if (adapter == null) {
            log.warn("Mirror target platform not connected: {}", target.platform.getValue());
            return;
        }

        adapter.send(target.chatId, content, null, null)
            .subscribe(
                result -> {
                    if (result.isSuccess()) {
                        log.debug("Mirrored message to {} chat {}", 
                            target.platform.getValue(), target.chatId);
                    } else {
                        log.warn("Mirror send failed: {}", result.getError());
                    }
                },
                error -> log.error("Mirror send error: {}", error.getMessage())
            );
    }

    // ========== 去重清理 ==========

    private void startDedupCleanup() {
        // 简单的定期清理（生产环境应用 ScheduledExecutorService）
        Thread cleanup = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(DEDUP_TTL_MS);
                    long cutoff = System.currentTimeMillis() - DEDUP_TTL_MS;
                    forwardedMessages.entrySet().removeIf(e -> e.getValue() < cutoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "mirror-dedup-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    // ========== 规则解析 ==========

    @SuppressWarnings("unchecked")
    private MirrorRule parseMirrorRule(Map<String, Object> mc) {
        String name = (String) mc.getOrDefault("name", "unnamed");

        // 源平台
        String sourceStr = (String) mc.get("source");
        if (sourceStr == null) return null;
        Platform source = Platform.fromValue(sourceStr);
        if (source == null) {
            log.warn("Unknown source platform: {}", sourceStr);
            return null;
        }

        // 目标平台列表
        List<String> targetStrs = (List<String>) mc.get("targets");
        if (targetStrs == null || targetStrs.isEmpty()) return null;
        List<Platform> targets = targetStrs.stream()
            .map(Platform::fromValue)
            .filter(Objects::nonNull)
            .toList();
        if (targets.isEmpty()) return null;

        MirrorRule rule = new MirrorRule(name, source, targets);

        // 聊天映射
        Map<String, Object> chatMapping = (Map<String, Object>) mc.get("chat_mapping");
        if (chatMapping != null) {
            for (Map.Entry<String, Object> entry : chatMapping.entrySet()) {
                String sourceChat = entry.getKey();
                List<String> targetChats = (List<String>) entry.getValue();
                rule.chatMapping.put(sourceChat, targetChats);
            }
        }

        // 选项
        rule.bidirectional = Boolean.TRUE.equals(mc.get("bidirectional"));
        rule.syncUserInfo = Boolean.TRUE.equals(mc.getOrDefault("sync_user_info", true));
        rule.syncAttachments = Boolean.TRUE.equals(mc.getOrDefault("sync_attachments", false));
        rule.syncReactions = Boolean.TRUE.equals(mc.getOrDefault("sync_reactions", false));
        rule.formatPrefix = (String) mc.getOrDefault("format_prefix", "[Mirror]");
        rule.excludeBots = Boolean.TRUE.equals(mc.getOrDefault("exclude_bots", true));

        return rule;
    }

    // ========== 内部类 ==========

    /**
     * 镜像规则
     */
    public static class MirrorRule {
        final String name;
        final Platform source;
        final List<Platform> targets;
        final Map<String, List<String>> chatMapping = new LinkedHashMap<>();

        boolean bidirectional = false;

        public boolean isBidirectional() { return bidirectional; }
        boolean syncUserInfo = true;
        boolean syncAttachments = false;
        boolean syncReactions = false;
        String formatPrefix = "[Mirror]";
        boolean excludeBots = true;

        public MirrorRule(String name, Platform source, List<Platform> targets) {
            this.name = name;
            this.source = source;
            this.targets = targets;
        }

        boolean matchesSource(Platform platform) {
            return source == platform;
        }

        List<MirrorTarget> resolveTargets(Platform sourcePlatform, String sourceChatId) {
            List<MirrorTarget> result = new ArrayList<>();

            // 如果有聊天映射，使用映射
            List<String> mappedChats = chatMapping.get(sourceChatId);
            if (mappedChats != null) {
                for (Platform target : targets) {
                    for (String chat : mappedChats) {
                        // chat 格式: "platform:chatId" 或 "chatId"
                        if (chat.contains(":")) {
                            String[] parts = chat.split(":", 2);
                            Platform p = Platform.fromValue(parts[0]);
                            if (p == target) {
                                result.add(new MirrorTarget(p, parts[1]));
                            }
                        } else {
                            result.add(new MirrorTarget(target, chat));
                        }
                    }
                }
            } else {
                // 无映射 → 使用 HomeChannel
                for (Platform target : targets) {
                    result.add(new MirrorTarget(target, null));  // null = HomeChannel
                }
            }

            return result;
        }
    }

    /**
     * 镜像目标
     */
    public record MirrorTarget(Platform platform, String chatId) {}

    /**
     * 获取所有规则
     */
    public List<MirrorRule> getRules() {
        return Collections.unmodifiableList(rules);
    }

    /**
     * 动态添加规则
     */
    public void addRule(MirrorRule rule) {
        rules.add(rule);
        log.info("Added mirror rule: {}", rule.name);
    }

    /**
     * 移除规则
     */
    public void removeRule(String name) {
        rules.removeIf(r -> r.name.equals(name));
    }
}
