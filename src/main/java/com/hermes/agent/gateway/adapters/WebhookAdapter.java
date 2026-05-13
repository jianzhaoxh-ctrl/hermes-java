package com.hermes.agent.gateway.adapters;

import com.hermes.agent.gateway.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

/**
 * 通用 Webhook 适配器
 *
 * 参考Python版 webhook.py 实现。支持：
 * - 接收任意 HTTP POST 请求作为消息
 * - 通过 Webhook URL 发送消息到外部系统
 * - 自定义请求头验证（签名校验）
 * - 自定义消息格式解析（JSON path 提取）
 * - 回调 URL 注册（发送后通知）
 *
 * 配置 (config.yaml):
 * <pre>
 * gateway:
 *   platforms:
 *     webhook:
 *       transport: http
 *       extra:
 *         secret: "your-webhook-secret"
 *         verify_signature: true
 *         message_path: "$.content"          # JSON path 提取消息内容
 *         sender_path: "$.sender.name"       # JSON path 提取发送者
 *         chat_id_path: "$.channel.id"       # JSON path 提取聊天 ID
 *         outbound_url: "https://example.com/webhook"
 *         outbound_headers:
 *           Authorization: "Bearer xxx"
 * </pre>
 */
public class WebhookAdapter extends BasePlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(WebhookAdapter.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String secret;
    private final boolean verifySignature;
    private final String messagePath;
    private final String senderPath;
    private final String chatIdPath;
    private final String outboundUrl;
    private final Map<String, String> outboundHeaders;

    // 回调注册表：chatId → callback URL
    private final Map<String, String> callbackUrls = new ConcurrentHashMap<>();

    public WebhookAdapter(PlatformConfig config) {
        super(config, Platform.WEBHOOK);
        this.secret = config.getExtraString("secret").orElse("");
        this.verifySignature = config.getExtraString("verify_signature")
            .map(Boolean::parseBoolean).orElse(false);
        this.messagePath = config.getExtraString("message_path").orElse("$.content");
        this.senderPath = config.getExtraString("sender_path").orElse("$.sender.name");
        this.chatIdPath = config.getExtraString("chat_id_path").orElse("$.channel.id");
        this.outboundUrl = config.getExtraString("outbound_url").orElse("");
        this.outboundHeaders = config.getExtraStringMap("outbound_headers");
    }

    @Override
    public Mono<Boolean> connect() {
        // Webhook 不需要主动连接，只要 HTTP 服务在运行即可
        markConnected();
        log.info("Webhook adapter ready (verify_signature={})", verifySignature);
        return Mono.just(true);
    }

    @Override
    public Mono<Void> disconnect() {
        markDisconnected();
        callbackUrls.clear();
        return Mono.empty();
    }

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        // 优先使用回调 URL，其次使用 outbound URL
        String targetUrl = callbackUrls.getOrDefault(chatId, outboundUrl);

        if (targetUrl == null || targetUrl.isBlank()) {
            log.warn("No outbound URL configured for webhook chatId: {}", chatId);
            return Mono.just(SendResult.failure("No outbound URL for chatId: " + chatId));
        }

        return Mono.fromCallable(() -> {
            try {
                // 构建请求体
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("content", content);
                payload.put("chat_id", chatId);
                payload.put("timestamp", Instant.now().toString());
                if (replyTo != null) {
                    payload.put("reply_to", replyTo);
                }
                if (metadata != null) {
                    payload.put("metadata", metadata);
                }

                String json = objectMapper.writeValueAsString(payload);

                // 发送 HTTP POST
                var httpClient = AdapterUtils.buildHttpClient(java.time.Duration.ofSeconds(30));
                var requestBuilder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(targetUrl))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json));

                // 添加自定义头部
                for (Map.Entry<String, String> header : outboundHeaders.entrySet()) {
                    requestBuilder.header(header.getKey(), header.getValue());
                }

                // 签名
                if (!secret.isBlank()) {
                    String signature = computeSignature(json, secret);
                    requestBuilder.header("X-Webhook-Signature", signature);
                }

                var response = httpClient.send(requestBuilder.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return SendResult.success("webhook-" + System.currentTimeMillis());
                } else {
                    log.warn("Webhook send failed: HTTP {} - {}", response.statusCode(), response.body());
                    return SendResult.failure("HTTP " + response.statusCode(), response.statusCode() >= 500);
                }
            } catch (Exception e) {
                log.error("Webhook send error: {}", e.getMessage());
                return SendResult.failure(e.getMessage(), true);
            }
        });
    }

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("type", "webhook");
        info.put("chat_id", chatId);
        info.put("callback_url", callbackUrls.get(chatId));
        return Mono.just(info);
    }

    // ========== Webhook 入口 ==========

    /**
     * 处理收到的 Webhook 请求
     *
     * 由 GatewayController 调用。
     *
     * @param body 请求体
     * @param headers 请求头
     * @return 响应内容
     */
    public Mono<String> handleWebhookRequest(String body, Map<String, String> headers) {
        // 签名验证
        if (verifySignature && !secret.isBlank()) {
            String receivedSig = headers.getOrDefault("x-webhook-signature",
                headers.getOrDefault("X-Webhook-Signature", ""));
            String expectedSig = computeSignature(body, secret);
            if (!expectedSig.equals(receivedSig)) {
                log.warn("Webhook signature verification failed");
                return Mono.just("{\"error\":\"invalid_signature\"}");
            }
        }

        return Mono.fromCallable(() -> {
            try {
                // 解析 JSON
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.readValue(body, Map.class);

                // 提取字段
                String text = extractByPath(data, messagePath);
                String sender = extractByPath(data, senderPath);
                String chatId = extractByPath(data, chatIdPath);

                if (text == null || text.isBlank()) {
                    log.warn("Webhook: no content found at path {}", messagePath);
                    return "{\"error\":\"no_content\"}";
                }

                // 注册回调 URL（如果提供）
                String callbackUrl = extractByPath(data, "$.callback_url");
                if (callbackUrl != null && chatId != null) {
                    callbackUrls.put(chatId, callbackUrl);
                }

                // 构建消息事件
                SessionSource source = buildSource(
                    chatId != null ? chatId : "webhook",
                    chatId,
                    "webhook",
                    sender,
                    sender,
                    null,
                    null
                );

                MessageEvent event = new MessageEvent();
                event.setText(text);
                event.setMessageType(MessageEvent.MessageType.TEXT);
                event.setSource(source);
                event.setMessageId("wh-" + System.currentTimeMillis());
                event.setRawMessage(data);

                // 触发消息处理器
                if (messageHandler != null) {
                    messageHandler.apply(event).subscribe();
                }

                return "{\"status\":\"ok\"}";
            } catch (Exception e) {
                log.error("Error processing webhook request: {}", e.getMessage());
                return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
            }
        });
    }

    /**
     * 注册回调 URL
     */
    public void registerCallback(String chatId, String callbackUrl) {
        callbackUrls.put(chatId, callbackUrl);
        log.info("Registered webhook callback: {} → {}", chatId, callbackUrl);
    }

    // ========== 辅助方法 ==========

    /**
     * 通过简单 JSON path 提取值
     *
     * 支持格式：$.field.subfield，仅支持点号分隔的路径
     */
    @SuppressWarnings("unchecked")
    private String extractByPath(Map<String, Object> data, String path) {
        if (path == null || path.isBlank() || data == null) return null;

        String[] parts = path.replace("$.", "").split("\\.");
        Object current = data;

        for (String part : parts) {
            if (part.isBlank()) continue;
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                return null;
            }
        }

        return current != null ? current.toString() : null;
    }

    /**
     * 计算 HMAC-SHA256 签名
     */
    private String computeSignature(String payload, String secret) {
        try {
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("Error computing webhook signature: {}", e.getMessage());
            return "";
        }
    }
}
