package com.hermes.agent.gateway.adapters;

import com.hermes.agent.gateway.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * 钉钉平台适配器
 * 
 * 使用钉钉开放平台 API 实现消息收发。
 * 
 * 支持模式：
 * - Stream 模式（WebSocket 长连接）
 * - Webhook 回调模式
 * 
 * 特性：
 * - 企业内部机器人
 * - AI 智能卡片（流式输出）
 * - 富文本消息
 */
public class DingTalkAdapter extends BasePlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(DingTalkAdapter.class);
    private static final String API_BASE = "https://api.dingtalk.com";
    private static final String OAPI_BASE = "https://oapi.dingtalk.com";

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String clientId;
    private final String clientSecret;

    private volatile String accessToken;
    private volatile long tokenExpireTime;
    private final Object tokenLock = new Object();

    public DingTalkAdapter(PlatformConfig config) {
        super(config, Platform.DINGTALK);
        this.clientId = config.getExtraString("client_id")
            .orElse(System.getenv("DINGTALK_CLIENT_ID"));
        this.clientSecret = config.getExtraString("client_secret")
            .orElse(System.getenv("DINGTALK_CLIENT_SECRET"));

        this.webClient = WebClient.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    }

    /**
     * 钉钉 AI 卡片需要显式 finalize 编辑
     * （BasePlatformAdapter 基类未定义此方法，作为自定义扩展）
     */
    public boolean isRequiresEditFinalize() {
        return true;
    }

    @Override
    public Mono<Boolean> connect() {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            setFatalError("missing_credentials", "DingTalk client_id and client_secret not configured", false);
            return Mono.just(false);
        }

        return getAccessToken()
            .map(token -> {
                if (token != null) {
                    log.info("DingTalk connected for client: {}", clientId);
                    markConnected();
                    return true;
                }
                setFatalError("auth_failed", "Failed to get access token", false);
                return false;
            })
            .onErrorResume(e -> {
                log.error("Failed to connect to DingTalk: {}", e.getMessage());
                setFatalError("connection_failed", e.getMessage(), true);
                return Mono.just(false);
            });
    }

    @Override
    public Mono<Void> disconnect() {
        markDisconnected();
        log.info("DingTalk disconnected");
        return Mono.empty();
    }

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        return ensureToken().flatMap(token -> {
            // 普通文本消息
            Map<String, Object> text = Map.of("content", content);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("msgtype", "text");
            body.put("text", text);

            // 使用机器人 Webhook
            String webhook = config.getExtraString("webhook").orElse(null);
            if (webhook != null && !webhook.isBlank()) {
                return webClient.post()
                    .uri(webhook)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(this::parseWebhookResult);
            }

            // 使用 API 发送
            Map<String, Object> apiBody = new LinkedHashMap<>();
            apiBody.put("robotCode", clientId);
            apiBody.put("userIds", List.of(chatId));
            apiBody.put("msgKey", "sampleText");
            apiBody.put("msgParam", toJsonString(text));

            return webClient.post()
                .uri(API_BASE + "/v1.0/robot/oToMessages/batchSend")
                .header("x-acs-dingtalk-access-token", token)
                .bodyValue(apiBody)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseSendResult);
        });
    }

    @Override
    public Mono<SendResult> editMessage(String chatId, String messageId, String content, boolean finalize) {
        // 钉钉 AI 卡片编辑
        return ensureToken().flatMap(token -> {
            Map<String, Object> cardContent = new LinkedHashMap<>();
            cardContent.put("contentType", "aiCard");
            cardContent.put("content", Map.of(
                "text", content,
                "streaming", !finalize
            ));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("openConversationId", chatId);
            body.put("messageId", messageId);
            body.put("content", toJsonString(cardContent));

            return webClient.post()
                .uri(API_BASE + "/v1.0/card/instances/update")
                .header("x-acs-dingtalk-access-token", token)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseSendResult)
                .onErrorResume(e -> Mono.just(SendResult.failure(e.getMessage(), true)));
        });
    }

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        return ensureToken().flatMap(token -> 
            webClient.get()
                .uri(API_BASE + "/v1.0/contact/users/" + chatId)
                .header("x-acs-dingtalk-access-token", token)
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> {
                    try {
                        JsonNode json = objectMapper.readTree(response);
                        Map<String, Object> info = new HashMap<>();
                        if (json.has("nickName")) {
                            info.put("name", json.get("nickName").asText());
                        }
                        info.put("id", chatId);
                        info.put("type", "dm");
                        return info;
                    } catch (Exception e) {
                        return Map.of("error", e.getMessage());
                    }
                })
        );
    }

    // ========== 私有方法 ==========

    /**
     * 安全的 JSON 序列化
     */
    private String toJsonString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    private Mono<String> getAccessToken() {
        synchronized (tokenLock) {
            if (accessToken != null && System.currentTimeMillis() < tokenExpireTime) {
                return Mono.just(accessToken);
            }
        }

        Map<String, String> body = Map.of(
            "appKey", clientId,
            "appSecret", clientSecret
        );

        return webClient.post()
            .uri(OAPI_BASE + "/gettoken")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .map(response -> {
                try {
                    JsonNode json = objectMapper.readTree(response);
                    if (json.has("errcode") && json.get("errcode").asInt() == 0) {
                        String token = json.get("access_token").asText();
                        int expire = json.get("expires_in").asInt();
                        
                        synchronized (tokenLock) {
                            accessToken = token;
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
            if (accessToken != null && System.currentTimeMillis() < tokenExpireTime) {
                return Mono.just(accessToken);
            }
        }
        return getAccessToken();
    }

    private SendResult parseSendResult(String response) {
        try {
            JsonNode json = objectMapper.readTree(response);
            if (json.has("processQueryKeys")) {
                JsonNode keys = json.get("processQueryKeys");
                if (keys.isArray() && !keys.isEmpty()) {
                    return SendResult.success(keys.get(0).asText());
                }
            }
            return SendResult.failure(json.has("message") 
                ? json.get("message").asText() 
                : "Unknown error");
        } catch (Exception e) {
            return SendResult.failure(e.getMessage());
        }
    }

    private SendResult parseWebhookResult(String response) {
        try {
            JsonNode json = objectMapper.readTree(response);
            if (json.has("errcode") && json.get("errcode").asInt() == 0) {
                return SendResult.success("sent");
            }
            return SendResult.failure(json.has("errmsg") ? json.get("errmsg").asText() : "Unknown error");
        } catch (Exception e) {
            return SendResult.failure(e.getMessage());
        }
    }

    /**
     * 处理钉钉回调事件
     */
    public Mono<Void> handleCallback(String body, Map<String, String> headers) {
        try {
            JsonNode event = objectMapper.readTree(body);
            String msgType = event.has("msgtype") ? event.get("msgtype").asText() : "";

            if ("text".equals(msgType)) {
                String text = "";
                if (event.has("text") && event.get("text").has("content")) {
                    text = event.get("text").get("content").asText();
                }
                String senderId = event.has("senderId") ? event.get("senderId").asText() : "";
                String senderNick = event.has("senderNick") ? event.get("senderNick").asText() : null;
                String conversationId = event.has("conversationId") ? event.get("conversationId").asText() : "";
                String conversationType = event.has("conversationType") ? event.get("conversationType").asText() : "dm";
                String msgId = event.has("msgid") ? event.get("msgid").asText() : null;

                SessionSource source = buildSource(
                    senderId,
                    senderNick,
                    conversationType,
                    senderId,
                    senderNick,
                    null,
                    null
                );

                MessageEvent messageEvent = new MessageEvent();
                messageEvent.setSource(source);
                messageEvent.setText(text);
                messageEvent.setMessageId(msgId);

                handleMessage(messageEvent).subscribe();
            }

            return Mono.empty();
        } catch (Exception e) {
            log.error("Failed to handle DingTalk callback: {}", e.getMessage());
            return Mono.empty();
        }
    }
}
