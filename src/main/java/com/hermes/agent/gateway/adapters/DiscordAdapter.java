package com.hermes.agent.gateway.adapters;

import com.hermes.agent.gateway.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

/**
 * Discord 平台适配器
 *
 * 使用 Discord Bot API 实现消息收发。
 *
 * 特性：
 * - Gateway WebSocket 连接（实时事件）
 * - REST API 消息发送
 * - Embed 富文本
 * - 文件上传（附件）
 * - 频道/DM/服务器消息
 * - Slash 命令
 * - 反应（emoji reactions）
 *
 * 配置（PlatformConfig）：
 * - token: Bot Token (MTIz...)
 * - extra.application_id: Application ID (用于 Slash 命令注册)
 * - extra.proxy_url: 代理 URL (可选)
 * - extra.allowed_guilds: 允许的服务器 ID 列表 (可选，逗号分隔)
 */
public class DiscordAdapter extends BasePlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiscordAdapter.class);
    private static final String API_BASE = "https://discord.com/api/v10";
    private static final int MAX_MESSAGE_LENGTH = 2000;  // Discord 消息限制

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String botToken;
    private final String applicationId;
    private volatile String botId;

    public DiscordAdapter(PlatformConfig config) {
        super(config, Platform.DISCORD);
        this.botToken = config.getToken();
        this.applicationId = config.getExtraString("application_id").orElse(null);

        this.webClient = WebClient.builder()
            .baseUrl(API_BASE)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bot " + botToken)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    }

    // ========== 生命周期 ==========

    @Override
    public Mono<Boolean> connect() {
        return getGatewayBot()
            .flatMap(gatewayUrl -> {
                log.info("Discord Gateway URL obtained: {}", gatewayUrl);
                // TODO: 建立 WebSocket 连接到 Gateway
                // 实际生产环境应使用 discord4j 或类似库
                markConnected();
                return Mono.just(true);
            })
            .onErrorResume(e -> {
                log.error("Discord connection failed: {}", e.getMessage());
                setFatalError("CONNECTION_ERROR", e.getMessage(), true);
                return Mono.just(false);
            });
    }

    @Override
    public Mono<Void> disconnect() {
        // TODO: 关闭 WebSocket 连接
        markDisconnected();
        log.info("Discord disconnected");
        return Mono.empty();
    }

    // ========== Gateway ==========

    private Mono<String> getGatewayBot() {
        return webClient.get()
            .uri("/gateway/bot")
            .retrieve()
            .bodyToMono(String.class)
            .map(resp -> {
                try {
                    JsonNode root = objectMapper.readTree(resp);
                    return root.path("url").asText();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to parse gateway response: " + e.getMessage());
                }
            });
    }

    // ========== 消息发送 ==========

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();

        // Discord 消息格式
        body.put("content", content);

        // 回复消息
        if (replyTo != null && !replyTo.isBlank()) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("message_id", replyTo);
            body.put("message_reference", ref);
        }

        // Embed（如果 metadata 提供）
        if (metadata != null && metadata.containsKey("embeds")) {
            body.put("embeds", metadata.get("embeds"));
        }

        // 静默模式（不触发通知）
        if (metadata != null && Boolean.TRUE.equals(metadata.get("silent"))) {
            body.put("flags", 4096);  // SUPPRESS_EMBEDS
        }

        return webClient.post()
            .uri("/channels/{channelId}/messages", chatId)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::parseMessageResponse)
            .onErrorResume(e -> {
                log.error("Discord send failed to channel {}: {}", chatId, e.getMessage());
                return Mono.just(SendResult.failure("Send failed: " + e.getMessage()));
            });
    }

    @Override
    public Mono<SendResult> editMessage(String chatId, String messageId, String content, boolean finalize) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);

        return webClient.patch()
            .uri("/channels/{channelId}/messages/{messageId}", chatId, messageId)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::parseMessageResponse)
            .onErrorResume(e -> {
                log.error("Discord edit failed: {}", e.getMessage());
                return Mono.just(SendResult.failure("Edit failed: " + e.getMessage()));
            });
    }

    // ========== 媒体发送 ==========

    @Override
    public Mono<SendResult> sendImage(String chatId, String imageUrl, String caption,
                                       String replyTo, Map<String, Object> metadata) {
        // Discord 可以发送 URL，会自动生成 embed preview
        String text = caption != null ? caption + "\n" + imageUrl : imageUrl;
        return send(chatId, text, replyTo, metadata);
    }

    @Override
    public Mono<SendResult> sendDocument(String chatId, String filePath, String caption,
                                          String fileName, String replyTo) {
        // TODO: 使用 multipart/form-data 上传文件
        String text = (caption != null ? caption + "\n" : "") + "📎 File: " + filePath;
        return send(chatId, text, replyTo, null);
    }

    // ========== 反应 ==========

    /**
     * 添加反应（emoji）
     */
    public Mono<Void> addReaction(String channelId, String messageId, String emoji) {
        return webClient.put()
            .uri("/channels/{channelId}/messages/{messageId}/reactions/{emoji}/@me",
                channelId, messageId, emoji)
            .retrieve()
            .bodyToMono(Void.class)
            .onErrorResume(e -> {
                log.error("Discord add reaction failed: {}", e.getMessage());
                return Mono.empty();
            });
    }

    // ========== 输入指示器 ==========

    @Override
    public Mono<Void> sendTyping(String chatId, Map<String, Object> metadata) {
        return webClient.post()
            .uri("/channels/{channelId}/typing", chatId)
            .retrieve()
            .bodyToMono(Void.class)
            .onErrorResume(e -> {
                log.debug("Discord typing indicator failed: {}", e.getMessage());
                return Mono.empty();
            });
    }

    // ========== Webhook 处理 ==========

    /**
     * 处理 Discord 交互事件（Slash 命令、按钮点击等）
     *
     * Discord 使用 Interactions API 而非传统 Webhook
     */
    public Mono<Map<String, Object>> handleInteraction(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            int type = root.path("type").asInt();

            return switch (type) {
                case 1 -> {
                    // Ping
                    yield Mono.just(Map.of("type", 1));
                }
                case 2 -> {
                    // Application Command
                    String name = root.path("data").path("name").asText();
                    log.info("Discord slash command: {}", name);
                    yield Mono.just(Map.of("type", 5));  // DEFERRED_CHANNEL_MESSAGE_WITH_SOURCE
                }
                case 3 -> {
                    // Message Component (button, select menu)
                    String customId = root.path("data").path("custom_id").asText();
                    log.info("Discord component interaction: {}", customId);
                    yield Mono.just(Map.of("type", 6));  // DEFERRED_UPDATE_MESSAGE
                }
                default -> Mono.just(Map.of("type", 1));
            };

        } catch (Exception e) {
            log.error("Error handling Discord interaction: {}", e.getMessage());
            return Mono.just(Map.of("type", 1));
        }
    }

    // ========== 事件处理（WebSocket 回调） ==========

    /**
     * 处理从 WebSocket Gateway 收到的事件
     *
     * 在生产环境中，WebSocket 客户端会调用此方法
     */
    public void handleGatewayEvent(JsonNode event) {
        String eventType = event.path("t").asText();
        JsonNode data = event.path("d");

        switch (eventType) {
            case "MESSAGE_CREATE" -> handleDiscordMessage(data);
            case "MESSAGE_UPDATE" -> log.debug("Discord message updated: {}", data.path("id").asText());
            case "MESSAGE_DELETE" -> log.debug("Discord message deleted: {}", data.path("id").asText());
            case "READY" -> {
                this.botId = data.path("user").path("id").asText();
                log.info("Discord READY: bot_id={}", botId);
            }
            case "GUILD_CREATE" -> log.info("Discord guild available: {}", data.path("name").asText());
            default -> log.debug("Discord event: {}", eventType);
        }
    }

    private void handleDiscordMessage(JsonNode data) {
        // 忽略机器人自己的消息
        if (botId != null && botId.equals(data.path("author").path("id").asText())) {
            return;
        }

        String content = data.path("content").asText();
        if (content.isEmpty()) return;

        String channelId = data.path("channel_id").asText();
        String userId = data.path("author").path("id").asText();
        String username = data.path("author").path("username").asText();
        String messageId = data.path("id").asText();

        // 检查允许的服务器
        String guildId = data.path("guild_id").asText(null);
        if (!isGuildAllowed(guildId)) return;

        // 构建消息事件
        MessageEvent messageEvent = new MessageEvent();
        messageEvent.setText(content);
        messageEvent.setMessageId(messageId);

        // 确定聊天类型
        String chatType = guildId != null ? "channel" : "dm";

        SessionSource source = buildSource(
            channelId,
            channelId,  // channel name 需要 API 查询
            chatType,
            userId,
            username,
            null,
            null
        );

        // 服务器信息
        if (guildId != null) {
            source.setGuildId(guildId);
        }

        messageEvent.setSource(source);

        if (messageHandler != null) {
            messageHandler.apply(messageEvent)
                .subscribe(
                    response -> {},
                    error -> log.error("Error handling Discord message: {}", error.getMessage())
                );
        }
    }

    private boolean isGuildAllowed(String guildId) {
        if (guildId == null) return true;  // DM 始终允许
        String allowedGuilds = config.getExtraString("allowed_guilds").orElse(null);
        if (allowedGuilds == null || allowedGuilds.isBlank()) return true;
        return List.of(allowedGuilds.split(",")).contains(guildId);
    }

    // ========== 辅助方法 ==========

    private SendResult parseMessageResponse(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            String messageId = root.path("id").asText();
            String channelId = root.path("channel_id").asText();

            if (!messageId.isEmpty()) {
                return SendResult.success(messageId);
            } else {
                // 错误响应
                int code = root.path("code").asInt();
                String message = root.path("message").asText();
                log.error("Discord API error {}: {}", code, message);
                return SendResult.failure(code + ": " + message);
            }
        } catch (Exception e) {
            return SendResult.failure("Parse error: " + e.getMessage());
        }
    }

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        return webClient.get()
            .uri("/channels/{channelId}", chatId)
            .retrieve()
            .bodyToMono(String.class)
            .map(resp -> {
                try {
                    JsonNode root = objectMapper.readTree(resp);
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", root.path("id").asText());
                    info.put("name", root.path("name").asText());
                    info.put("type", root.path("type").asInt());

                    int type = root.path("type").asInt();
                    info.put("type_name", switch (type) {
                        case 0 -> "text";
                        case 1 -> "dm";
                        case 3 -> "group_dm";
                        case 4 -> "category";
                        case 5 -> "announcement";
                        default -> "unknown";
                    });

                    info.put("topic", root.path("topic").asText());
                    info.put("guild_id", root.path("guild_id").asText(""));

                    return info;
                } catch (Exception e) {
                    Map<String, Object> errMap = new LinkedHashMap<>();
                    errMap.put("error", e.getMessage());
                    return errMap;
                }
            })
            .onErrorResume(e -> {
                Map<String, Object> errMap = new LinkedHashMap<>();
                errMap.put("error", e.getMessage());
                return Mono.just(errMap);
            });
    }

    @Override
    public String formatMessage(String content) {
        // Discord 支持 Markdown，但有些限制：
        // - 不支持 # 标题（但会渲染）
        // - 支持 **bold**, *italic*, `code`, ```code block```
        // - 支持 ~~strikethrough~~
        return content;
    }

    @Override
    public List<String> truncateMessage(String content, int maxLength) {
        return super.truncateMessage(content, Math.min(maxLength, MAX_MESSAGE_LENGTH));
    }
}
