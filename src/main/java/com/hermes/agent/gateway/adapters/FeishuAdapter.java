package com.hermes.agent.gateway.adapters;

import com.hermes.agent.gateway.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 飞书平台适配器
 * 
 * 使用飞书开放平台 API 实现消息收发。
 * 
 * 支持模式：
 * - WebSocket 长连接（推荐）
 * - Webhook 回调
 * 
 * 特性：
 * - 富文本消息
 * - 卡片消息
 * - 文件上传
 */
public class FeishuAdapter extends BasePlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(FeishuAdapter.class);
    private static final String API_BASE = "https://open.feishu.cn/open-apis";
    private static final String LARK_API_BASE = "https://open.larksuite.com/open-apis";

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String appId;
    private final String appSecret;
    private final String domain;
    private final String connectionMode;

    private volatile String tenantAccessToken;
    private volatile long tokenExpireTime;
    private final Object tokenLock = new Object();

    public FeishuAdapter(PlatformConfig config) {
        super(config, Platform.FEISHU);
        this.appId = config.getExtraString("app_id").orElse(System.getenv("FEISHU_APP_ID"));
        this.appSecret = config.getExtraString("app_secret").orElse(System.getenv("FEISHU_APP_SECRET"));
        this.domain = config.getExtraString("domain").orElse("feishu");
        this.connectionMode = config.getExtraString("connection_mode").orElse("websocket");

        this.webClient = WebClient.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    }

    @Override
    public Mono<Boolean> connect() {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            setFatalError("missing_credentials", "Feishu app_id and app_secret not configured", false);
            return Mono.just(false);
        }

        return getTenantAccessToken()
            .map(token -> {
                if (token != null) {
                    log.info("Feishu connected for app: {}", appId);
                    markConnected();
                    
                    if ("websocket".equals(connectionMode)) {
                        startWebSocketConnection();
                    }
                    return true;
                }
                setFatalError("auth_failed", "Failed to get tenant access token", false);
                return false;
            })
            .onErrorResume(e -> {
                log.error("Failed to connect to Feishu: {}", e.getMessage());
                setFatalError("connection_failed", e.getMessage(), true);
                return Mono.just(false);
            });
    }

    @Override
    public Mono<Void> disconnect() {
        markDisconnected();
        log.info("Feishu disconnected");
        return Mono.empty();
    }

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        return ensureToken().flatMap(token -> {
            String apiBase = getApiBase();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("receive_id", chatId);
            body.put("msg_type", "text");
            body.put("content", toJsonString(Map.of("text", content)));

            return webClient.post()
                .uri(apiBase + "/im/v1/messages?receive_id_type=chat_id")
                .header("Authorization", "Bearer " + token)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseSendResult)
                .onErrorResume(e -> Mono.just(SendResult.failure(e.getMessage(), true)));
        });
    }

    @Override
    public Mono<SendResult> sendImage(String chatId, String imageUrl, String caption, String replyTo, Map<String, Object> metadata) {
        return ensureToken().flatMap(token -> {
            String apiBase = getApiBase();
            Map<String, Object> imgContent = new LinkedHashMap<>();
            imgContent.put("image_key", imageUrl);
            if (caption != null) {
                imgContent.put("text", caption);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("receive_id", chatId);
            body.put("msg_type", "image");
            body.put("content", toJsonString(imgContent));

            return webClient.post()
                .uri(apiBase + "/im/v1/messages?receive_id_type=chat_id")
                .header("Authorization", "Bearer " + token)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseSendResult)
                .onErrorResume(e -> {
                    log.warn("Failed to send image: {}", e.getMessage());
                    return send(chatId, imageUrl, replyTo, metadata);
                });
        });
    }

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        return ensureToken().flatMap(token -> {
            String apiBase = getApiBase();
            return webClient.get()
                .uri(apiBase + "/im/v1/chats/" + chatId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> {
                    try {
                        JsonNode json = objectMapper.readTree(response);
                        if (json.has("code") && json.get("code").asInt() == 0) {
                            JsonNode data = json.get("data");
                            Map<String, Object> info = new HashMap<>();
                            info.put("id", data.get("chat_id").asText());
                            info.put("name", data.get("name").asText());
                            info.put("type", data.get("chat_mode").asText());
                            return info;
                        }
                        return Map.of("error", "Chat not found");
                    } catch (Exception e) {
                        return Map.of("error", e.getMessage());
                    }
                });
        });
    }

    // ========== 私有方法 ==========

    private String getApiBase() {
        return "lark".equals(domain) ? LARK_API_BASE : API_BASE;
    }

    /**
     * 安全的 JSON 序列化（将 checked exception 转换为 unchecked）
     */
    private String toJsonString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    private Mono<String> getTenantAccessToken() {
        synchronized (tokenLock) {
            if (tenantAccessToken != null && System.currentTimeMillis() < tokenExpireTime) {
                return Mono.just(tenantAccessToken);
            }
        }

        Map<String, String> body = Map.of(
            "app_id", appId,
            "app_secret", appSecret
        );

        String apiBase = getApiBase();
        return webClient.post()
            .uri(apiBase + "/auth/v3/tenant_access_token/internal")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .map(response -> {
                try {
                    JsonNode json = objectMapper.readTree(response);
                    if (json.has("tenant_access_token")) {
                        String token = json.get("tenant_access_token").asText();
                        int expire = json.get("expire").asInt();
                        
                        synchronized (tokenLock) {
                            tenantAccessToken = token;
                            tokenExpireTime = System.currentTimeMillis() + (expire - 60) * 1000L;
                        }
                        
                        return token;
                    }
                    throw new RuntimeException("Failed to get token: " + json.toString());
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new RuntimeException("Failed to parse token response", e);
                }
            });
    }

    private Mono<String> ensureToken() {
        synchronized (tokenLock) {
            if (tenantAccessToken != null && System.currentTimeMillis() < tokenExpireTime) {
                return Mono.just(tenantAccessToken);
            }
        }
        return getTenantAccessToken();
    }

    private SendResult parseSendResult(String response) {
        try {
            JsonNode json = objectMapper.readTree(response);
            if (json.has("code") && json.get("code").asInt() == 0) {
                JsonNode data = json.get("data");
                String messageId = data != null && data.has("message_id") 
                    ? data.get("message_id").asText() 
                    : null;
                return SendResult.success(messageId);
            }
            String error = json.has("msg") 
                ? json.get("msg").asText() 
                : "Unknown error";
            return SendResult.failure(error);
        } catch (Exception e) {
            return SendResult.failure(e.getMessage());
        }
    }

    private void startWebSocketConnection() {
        log.info("Starting Feishu WebSocket connection...");
        // TODO: 实现 WebSocket 连接接收消息
    }

    /**
     * 处理飞书事件回调（Webhook 模式）
     */
    public Mono<Void> handleWebhookEvent(String body, Map<String, String> headers) {
        try {
            JsonNode event = objectMapper.readTree(body);
            
            // 处理 URL 验证
            if (event.has("type") && "url_verification".equals(event.get("type").asText())) {
                return Mono.empty();
            }

            // 处理消息事件
            JsonNode header = event.get("header");
            if (header != null) {
                String eventType = header.has("event_type") ? header.get("event_type").asText() : "";
                
                if ("im.message.receive_v1".equals(eventType)) {
                    JsonNode msgBody = event.get("event");
                    if (msgBody != null && msgBody.has("message")) {
                        processReceivedMessage(msgBody.get("message"));
                    }
                }
            }

            return Mono.empty();
        } catch (Exception e) {
            log.error("Failed to handle webhook event: {}", e.getMessage());
            return Mono.empty();
        }
    }

    private void processReceivedMessage(JsonNode message) {
        try {
            String chatId = message.has("chat_id") ? message.get("chat_id").asText() : "";
            String chatType = message.has("chat_type") ? message.get("chat_type").asText() : "dm";
            String senderId = "";
            String senderName = null;

            if (message.has("sender") && message.get("sender").has("id")) {
                JsonNode senderIdNode = message.get("sender").get("id");
                senderId = senderIdNode.has("union_id") ? senderIdNode.get("union_id").asText() : "";
            }

            String msgType = message.has("message_type") ? message.get("message_type").asText() : "text";
            String messageId = message.has("message_id") ? message.get("message_id").asText() : null;

            String text = "";
            MessageEvent.MessageType messageType = MessageEvent.MessageType.TEXT;

            if ("text".equals(msgType) && message.has("content")) {
                JsonNode content = objectMapper.readTree(message.get("content").asText());
                text = content.has("text") ? content.get("text").asText() : "";
            } else if ("image".equals(msgType)) {
                messageType = MessageEvent.MessageType.PHOTO;
            } else if ("audio".equals(msgType)) {
                messageType = MessageEvent.MessageType.VOICE;
            }

            SessionSource source = buildSource(chatId, null, chatType, senderId, senderName, null, null);

            MessageEvent event = new MessageEvent();
            event.setSource(source);
            event.setText(text);
            event.setMessageType(messageType);
            event.setMessageId(messageId);

            handleMessage(event).subscribe();
        } catch (Exception e) {
            log.error("Failed to process Feishu message: {}", e.getMessage());
        }
    }
}
