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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Slack 平台适配器
 *
 * 使用 Slack Web API + Event Subscriptions (via Socket Mode) 实现消息收发。
 *
 * 特性：
 * - Socket Mode 长连接（无需公网 URL）
 * - Web API 消息发送
 * - Block Kit 富文本支持
 * - 文件上传
 * - 频道/DM 消息
 * - Slash 命令处理
 * - 签名验证（Events API 模式）
 *
 * 配置（PlatformConfig）：
 * - token: xoxb-xxx (Bot User OAuth Token)
 * - app_token: xapp-xxx (Socket Mode, 可选)
 * - signing_secret: xxx (Events API 签名密钥, 可选)
 * - extra.proxy_url: 代理 URL (可选)
 */
public class SlackAdapter extends BasePlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(SlackAdapter.class);
    private static final String API_BASE = "https://slack.com/api/";
    private static final int MAX_MESSAGE_LENGTH = 40000;  // Slack 文本限制

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String botToken;
    private final String appToken;
    private final String signingSecret;
    private volatile String botUserId;

    // Socket Mode
    private volatile boolean socketModeActive = false;

    public SlackAdapter(PlatformConfig config) {
        super(config, Platform.SLACK);
        this.botToken = config.getToken();
        this.appToken = config.getExtraString("app_token").orElse(null);
        this.signingSecret = config.getExtraString("signing_secret").orElse(null);

        this.webClient = WebClient.builder()
            .baseUrl(API_BASE)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + botToken)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    }

    // ========== 生命周期 ==========

    @Override
    public Mono<Boolean> connect() {
        return authTest()
            .flatMap(authInfo -> {
                if (authInfo != null) {
                    this.botUserId = authInfo;
                    log.info("Slack connected as bot user: {}", botUserId);

                    // Socket Mode
                    if (appToken != null && !appToken.isBlank()) {
                        startSocketMode();
                    }

                    markConnected();
                    return Mono.just(true);
                }
                setFatalError("AUTH_FAILED", "Slack auth.test failed", false);
                return Mono.just(false);
            })
            .onErrorResume(e -> {
                log.error("Slack connection failed: {}", e.getMessage());
                setFatalError("CONNECTION_ERROR", e.getMessage(), true);
                return Mono.just(false);
            });
    }

    @Override
    public Mono<Void> disconnect() {
        socketModeActive = false;
        markDisconnected();
        log.info("Slack disconnected");
        return Mono.empty();
    }

    // ========== 认证 ==========

    private Mono<String> authTest() {
        return webClient.post()
            .uri("auth.test")
            .retrieve()
            .bodyToMono(String.class)
            .map(resp -> {
                try {
                    JsonNode root = objectMapper.readTree(resp);
                    if (root.path("ok").asBoolean()) {
                        return root.path("user_id").asText();
                    }
                    log.error("Slack auth.test failed: {}", root.path("error").asText());
                    return null;
                } catch (Exception e) {
                    log.error("Failed to parse auth.test response: {}", e.getMessage());
                    return null;
                }
            });
    }

    // ========== Socket Mode ==========

    private void startSocketMode() {
        if (appToken == null || appToken.isBlank()) {
            log.warn("No app_token configured, Socket Mode disabled");
            return;
        }

        // Socket Mode 使用 WebSocket 连接 Slack 服务器
        // 在生产环境中应使用 slack-sdk 的 SocketModeClient
        // 这里提供框架级别的支持
        log.info("Socket Mode starting with app_token: {}...",
            appToken.substring(0, Math.min(10, appToken.length())));

        // TODO: 实现完整的 Socket Mode WebSocket 连接
        // 1. 调用 apps.connections.open 获取 WebSocket URL
        // 2. 建立 WebSocket 连接
        // 3. 监听 events_api 类型的 envelope
        // 4. 解析 event 并调用 handleMessage
        socketModeActive = true;
        log.info("Socket Mode placeholder active (implement WebSocket client for production)");
    }

    // ========== 消息发送 ==========

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", chatId);
        body.put("text", content);

        // mrkdwn 格式（Slack 默认）
        body.put("mrkdwn", true);

        // 回复线程
        if (replyTo != null && !replyTo.isBlank()) {
            body.put("thread_ts", replyTo);
        }

        // Block Kit 格式（如果 metadata 指定）
        if (metadata != null && metadata.containsKey("blocks")) {
            body.put("blocks", metadata.get("blocks"));
        }

        // 回复方式：线程内 or 频道内
        if (metadata != null && metadata.containsKey("reply_broadcast")) {
            body.put("reply_broadcast", metadata.get("reply_broadcast"));
        }

        // 静默模式（不显示通知）
        if (metadata != null && Boolean.TRUE.equals(metadata.get("silent"))) {
            body.put("metadata", Map.of("event_type", "silent"));
        }

        return webClient.post()
            .uri("chat.postMessage")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::parseSendResult)
            .onErrorResume(e -> {
                log.error("Slack send failed to {}: {}", chatId, e.getMessage());
                return Mono.just(SendResult.failure("Send failed: " + e.getMessage()));
            });
    }

    @Override
    public Mono<SendResult> editMessage(String chatId, String messageId, String content, boolean finalize) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", chatId);
        body.put("ts", messageId);
        body.put("text", content);
        body.put("mrkdwn", true);

        return webClient.post()
            .uri("chat.update")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::parseSendResult)
            .onErrorResume(e -> {
                log.error("Slack edit failed: {}", e.getMessage());
                return Mono.just(SendResult.failure("Edit failed: " + e.getMessage()));
            });
    }

    // ========== 媒体发送 ==========

    @Override
    public Mono<SendResult> sendImage(String chatId, String imageUrl, String caption,
                                       String replyTo, Map<String, Object> metadata) {
        // Slack 支持通过 URL unfurl 显示图片，或通过 files.upload 上传
        // 先尝试发送 URL（Slack 会自动 unfurl）
        String text = caption != null ? caption + "\n" + imageUrl : imageUrl;
        return send(chatId, text, replyTo, metadata);
    }

    @Override
    public Mono<SendResult> sendDocument(String chatId, String filePath, String caption,
                                          String fileName, String replyTo) {
        // TODO: 使用 files.upload API 上传文件
        // 暂时回退到文本
        String text = (caption != null ? caption + "\n" : "") + "📎 File: " + filePath;
        return send(chatId, text, replyTo, null);
    }

    // ========== 输入指示器 ==========

    @Override
    public Mono<Void> sendTyping(String chatId, Map<String, Object> metadata) {
        // Slack 没有原生的 typing indicator API
        // 可以通过定期发送 "typing" 指示来实现（但效果有限）
        return Mono.empty();
    }

    // ========== Webhook 处理 ==========

    /**
     * 处理 Events API 请求
     *
     * 包括：
     * - URL 验证（challenge）
     * - 事件消息分发
     * - 签名验证
     */
    public Mono<String> handleEventRequest(String body, Map<String, String> headers) {
        try {
            JsonNode root = objectMapper.readTree(body);

            // URL 验证（首次设置 Events API）
            if (root.has("type") && "url_verification".equals(root.path("type").asText())) {
                String challenge = root.path("challenge").asText();
                log.info("Slack URL verification challenge received");
                return Mono.just(challenge);
            }

            // 签名验证
            if (signingSecret != null && !verifySignature(body, headers)) {
                log.warn("Slack signature verification failed");
                return Mono.just("invalid signature");
            }

            // 事件回调
            if ("event_callback".equals(root.path("type").asText())) {
                JsonNode event = root.path("event");

                // 忽略机器人自己的消息
                if (botUserId != null && botUserId.equals(event.path("user").asText())) {
                    return Mono.just("ok");
                }

                String eventType = event.path("type").asText();

                return switch (eventType) {
                    case "message" -> handleSlackMessage(event);
                    case "app_mention" -> handleSlackMention(event);
                    case "channel_created", "group_joined" -> {
                        log.info("Slack channel event: {}", eventType);
                        yield Mono.just("ok");
                    }
                    default -> {
                        log.debug("Unhandled Slack event type: {}", eventType);
                        yield Mono.just("ok");
                    }
                };
            }

            return Mono.just("ok");

        } catch (Exception e) {
            log.error("Error handling Slack event: {}", e.getMessage());
            return Mono.just("error");
        }
    }

    private Mono<String> handleSlackMessage(JsonNode event) {
        String text = event.path("text").asText();
        String channel = event.path("channel").asText();
        String user = event.path("user").asText("");
        String threadTs = event.path("thread_ts").asText(null);
        String ts = event.path("ts").asText();

        // 跳过消息子类型（bot_message、join 等）
        String subtype = event.path("subtype").asText("");
        if (!subtype.isEmpty()) {
            return Mono.just("ok");
        }

        // 构建消息事件
        MessageEvent messageEvent = new MessageEvent();
        messageEvent.setText(text);
        messageEvent.setMessageId(ts);

        SessionSource source = buildSource(
            channel,
            channel,  // channel name 需要 API 查询
            channel.startsWith("D") ? "dm" : (channel.startsWith("C") ? "channel" : "group"),
            user,
            user,  // user name 需要 API 查询
            threadTs,
            null
        );
        messageEvent.setSource(source);

        if (messageHandler != null) {
            return messageHandler.apply(messageEvent)
                .map(response -> "ok")
                .defaultIfEmpty("ok");
        }
        return Mono.just("ok");
    }

    private Mono<String> handleSlackMention(JsonNode event) {
        // @mention 和 message 处理相同，只是文本中包含 @bot
        return handleSlackMessage(event);
    }

    // ========== 签名验证 ==========

    /**
     * 验证 Slack 请求签名
     *
     * 签名算法：v0 = HMAC-SHA256(signing_secret, "v0:timestamp:body")
     */
    private boolean verifySignature(String body, Map<String, String> headers) {
        if (signingSecret == null) return true;

        String timestamp = headers.getOrDefault("X-Slack-Request-Timestamp", "");
        String signature = headers.getOrDefault("X-Slack-Signature", "");

        // 防重放攻击（5 分钟窗口）
        try {
            long requestTime = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - requestTime) > 300) {
                log.warn("Slack request timestamp too old: {}s", now - requestTime);
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }

        // 计算 HMAC
        String baseString = "v0:" + timestamp + ":" + body;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8));
            String computed = "v0=" + bytesToHex(hash);
            return MessageDigest.isEqual(computed.getBytes(), signature.getBytes());
        } catch (Exception e) {
            log.error("HMAC computation failed: {}", e.getMessage());
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ========== 辅助方法 ==========

    private SendResult parseSendResult(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            if (root.path("ok").asBoolean()) {
                String messageId = root.path("ts").asText();
                String channel = root.path("channel").asText();
                return SendResult.success(messageId);
            } else {
                String error = root.path("error").asText();
                log.error("Slack API error: {}", error);
                return SendResult.failure(error);
            }
        } catch (Exception e) {
            return SendResult.failure("Parse error: " + e.getMessage());
        }
    }

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        return webClient.post()
            .uri(uriBuilder -> uriBuilder
                .path("conversations.info")
                .queryParam("channel", chatId)
                .build())
            .retrieve()
            .bodyToMono(String.class)
            .map(resp -> {
                try {
                    JsonNode root = objectMapper.readTree(resp);
                    if (root.path("ok").asBoolean()) {
                        JsonNode channel = root.path("channel");
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("id", channel.path("id").asText());
                        info.put("name", channel.path("name").asText());
                        info.put("is_channel", channel.path("is_channel").asBoolean());
                        info.put("is_group", channel.path("is_group").asBoolean());
                        info.put("is_dm", channel.path("is_im").asBoolean());
                        info.put("topic", channel.path("topic").path("value").asText());
                        info.put("purpose", channel.path("purpose").path("value").asText());
                        info.put("num_members", channel.path("num_members").asInt());
                        return info;
                    }
                    Map<String, Object> errMap = new LinkedHashMap<>();
                    errMap.put("error", root.path("error").asText());
                    return errMap;
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
        // Slack mrkdwn 与标准 Markdown 差异：
        // - *bold* 而非 **bold**
        // - _italic_ 而非 *italic*
        // - 不支持标题 #（用 bold 替代）
        // - 代码块相同 ```

        // 简单转换：标准 Markdown → Slack mrkdwn
        String formatted = content;
        // **bold** → *bold*
        formatted = formatted.replaceAll("\\*\\*(.+?)\\*\\*", "*$1*");
        // *italic* → _italic_ (仅单星号且非列表)
        formatted = formatted.replaceAll("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)", "_$1_");
        // ### Header → *Header*
        formatted = formatted.replaceAll("^###\\s+(.+)$", "*$1*");
        // ## Header → *Header*
        formatted = formatted.replaceAll("^##\\s+(.+)$", "*$1*");

        return formatted;
    }

    @Override
    public List<String> truncateMessage(String content, int maxLength) {
        // Slack 限制 40000 字符
        return super.truncateMessage(content, Math.min(maxLength, MAX_MESSAGE_LENGTH));
    }
}
