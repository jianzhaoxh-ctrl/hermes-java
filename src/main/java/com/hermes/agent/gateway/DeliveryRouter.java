package com.hermes.agent.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息投递路由器
 * 
 * 将消息路由到合适的目的地。支持：
 * - "origin" → 返回消息来源
 * - "local" → 仅保存到本地文件
 * - "telegram" → Telegram Home Channel
 * - "telegram:123456" → 指定 Telegram 聊天
 * - "telegram:123456:789" → 指定 Telegram 聊天的线程
 * 
 * 参考 Python 版 gateway/delivery.py 实现
 */
@Component
public class DeliveryRouter {
    private static final Logger log = LoggerFactory.getLogger(DeliveryRouter.class);

    public static final int MAX_PLATFORM_OUTPUT = 4000;
    public static final int TRUNCATED_VISIBLE = 3800;

    private final GatewayConfig config;
    private final Map<Platform, BasePlatformAdapter> adapters;

    public DeliveryRouter(GatewayConfig config, Map<Platform, BasePlatformAdapter> adapters) {
        this.config = config;
        this.adapters = adapters;
    }

    /**
     * 投递目标
     */
    public static class DeliveryTarget {
        private final Platform platform;
        private final String chatId;
        private final String threadId;
        private final boolean isOrigin;
        private final boolean isExplicit;

        public DeliveryTarget(Platform platform, String chatId, String threadId,
                              boolean isOrigin, boolean isExplicit) {
            this.platform = platform;
            this.chatId = chatId;
            this.threadId = threadId;
            this.isOrigin = isOrigin;
            this.isExplicit = isExplicit;
        }

        /**
         * 解析投递目标字符串
         * 
         * 格式：
         * - "origin" → 返回来源
         * - "local" → 本地文件
         * - "telegram" → Telegram Home Channel
         * - "telegram:123456" → 指定聊天
         * - "telegram:123456:789" → 指定聊天的线程
         */
        public static DeliveryTarget parse(String target, SessionSource origin) {
            if (target == null || target.isBlank()) {
                return new DeliveryTarget(Platform.LOCAL, null, null, false, false);
            }

            String normalized = target.strip().toLowerCase();

            // "origin" → 返回来源
            if ("origin".equals(normalized)) {
                if (origin != null) {
                    return new DeliveryTarget(
                        origin.getPlatform(),
                        origin.getChatId(),
                        origin.getThreadId(),
                        true, false
                    );
                }
                return new DeliveryTarget(Platform.LOCAL, null, null, true, false);
            }

            // "local" → 本地文件
            if ("local".equals(normalized)) {
                return new DeliveryTarget(Platform.LOCAL, null, null, false, false);
            }

            // "platform:chat_id" 或 "platform:chat_id:thread_id"
            if (normalized.contains(":")) {
                String[] parts = normalized.split(":", 3);
                String platformStr = parts[0];
                String chatId = parts.length > 1 ? parts[1] : null;
                String threadId = parts.length > 2 ? parts[2] : null;

                try {
                    Platform platform = Platform.fromValue(platformStr);
                    return new DeliveryTarget(platform, chatId, threadId, false, true);
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown platform in delivery target: {}", platformStr);
                    return new DeliveryTarget(Platform.LOCAL, null, null, false, false);
                }
            }

            // 仅平台名 → 使用 Home Channel
            try {
                Platform platform = Platform.fromValue(normalized);
                return new DeliveryTarget(platform, null, null, false, false);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown platform in delivery target: {}", normalized);
                return new DeliveryTarget(Platform.LOCAL, null, null, false, false);
            }
        }

        /**
         * 转换回字符串格式
         */
        public String toString() {
            if (isOrigin) return "origin";
            if (platform == Platform.LOCAL) return "local";
            StringBuilder sb = new StringBuilder(platform.getValue());
            if (chatId != null) {
                sb.append(":").append(chatId);
                if (threadId != null) {
                    sb.append(":").append(threadId);
                }
            }
            return sb.toString();
        }

        // Getters
        public Platform getPlatform() { return platform; }
        public String getChatId() { return chatId; }
        public String getThreadId() { return threadId; }
        public boolean isOrigin() { return isOrigin; }
        public boolean isExplicit() { return isExplicit; }
    }

    /**
     * 投递结果
     */
    public static class DeliveryResult {
        private final boolean success;
        private final String target;
        private final String error;
        private final String messageId;

        public DeliveryResult(boolean success, String target, String error, String messageId) {
            this.success = success;
            this.target = target;
            this.error = error;
            this.messageId = messageId;
        }

        public static DeliveryResult success(String target, String messageId) {
            return new DeliveryResult(true, target, null, messageId);
        }

        public static DeliveryResult failure(String target, String error) {
            return new DeliveryResult(false, target, error, null);
        }

        public static DeliveryResult localOnly(String path) {
            return new DeliveryResult(true, "local", null, path);
        }

        public boolean isSuccess() { return success; }
        public String getTarget() { return target; }
        public String getError() { return error; }
        public String getMessageId() { return messageId; }
    }

    /**
     * 投递消息到指定目标
     */
    public Mono<DeliveryResult> deliver(String content, DeliveryTarget target) {
        if (target.getPlatform() == Platform.LOCAL) {
            // 本地保存
            return saveToLocal(content)
                .map(path -> DeliveryResult.localOnly(path));
        }

        BasePlatformAdapter adapter = adapters.get(target.getPlatform());
        if (adapter == null) {
            return Mono.just(DeliveryResult.failure(
                target.toString(),
                "Platform not connected: " + target.getPlatform().getValue()
            ));
        }

        // 确定目标聊天 ID
        String chatId = target.getChatId();
        if (chatId == null) {
            // 使用 Home Channel
            HomeChannel home = config.getHomeChannel(target.getPlatform());
            if (home != null) {
                chatId = home.getChatId();
            }
        }

        if (chatId == null) {
            return Mono.just(DeliveryResult.failure(
                target.toString(),
                "No chat ID or home channel for platform: " + target.getPlatform().getValue()
            ));
        }

        // 截断超长消息
        String finalContent = truncateForPlatform(content);

        Map<String, Object> metadata = new HashMap<>();
        if (target.getThreadId() != null) {
            metadata.put("thread_id", target.getThreadId());
        }

        return adapter.send(chatId, finalContent, null, metadata)
            .map(result -> {
                if (result.isSuccess()) {
                    return DeliveryResult.success(target.toString(), result.getMessageId());
                }
                return DeliveryResult.failure(target.toString(), result.getError());
            });
    }

    /**
     * 投递消息到多个目标
     */
    public Mono<List<DeliveryResult>> deliverMulti(String content, List<DeliveryTarget> targets) {
        return Flux.fromIterable(targets)
            .flatMap(target -> deliver(content, target))
            .collectList();
    }

    /**
     * 解析并投递
     */
    public Mono<DeliveryResult> deliverTo(String content, String targetStr, SessionSource origin) {
        DeliveryTarget target = DeliveryTarget.parse(targetStr, origin);
        return deliver(content, target);
    }

    /**
     * 获取可用的投递选项描述
     */
    public String getDeliveryOptionsDescription(SessionSource source) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Delivery options for scheduled tasks:**\n");

        // Origin
        if (source.getPlatform() == Platform.LOCAL) {
            sb.append("- `\"origin\"` → Local output (saved to files)\n");
        } else {
            String originLabel = source.getChatName() != null
                ? source.getChatName() : source.getChatId();
            sb.append("- `\"origin\"` → Back to this chat (").append(originLabel).append(")\n");
        }

        // Local
        sb.append("- `\"local\"` → Save to local files only\n");

        // Home channels
        for (Platform platform : config.getConnectedPlatforms()) {
            HomeChannel home = config.getHomeChannel(platform);
            if (home != null) {
                sb.append("- `\"").append(platform.getValue()).append("\"` → Home channel (")
                  .append(home.getName()).append(")\n");
            }
        }

        sb.append("\n*For explicit targeting, use `\"platform:chat_id\"` format.*\n");
        return sb.toString();
    }

    // ========== 私有方法 ==========

    private Mono<String> saveToLocal(String content) {
        // 保存到本地 cron 输出目录
        return Mono.fromCallable(() -> {
            try {
                String homeDir = System.getProperty("user.home");
                java.nio.file.Path outputDir = java.nio.file.Path.of(homeDir, ".hermes", "cron", "output");
                java.nio.file.Files.createDirectories(outputDir);

                String timestamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                String filename = "cron_" + timestamp + ".md";
                java.nio.file.Path filePath = outputDir.resolve(filename);

                java.nio.file.Files.writeString(filePath, content);
                log.info("Saved delivery output to: {}", filePath);
                return filePath.toString();
            } catch (Exception e) {
                log.error("Failed to save delivery output locally: {}", e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    private String truncateForPlatform(String content) {
        if (content == null || content.length() <= MAX_PLATFORM_OUTPUT) {
            return content;
        }

        String truncated = content.substring(0, TRUNCATED_VISIBLE);
        return truncated + "\n\n... (truncated)";
    }
}
