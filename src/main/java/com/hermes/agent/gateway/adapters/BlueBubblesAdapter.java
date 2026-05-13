package com.hermes.agent.gateway.adapters;

import com.hermes.agent.gateway.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * BlueBubbles 适配器
 *
 * 通过 BlueBubbles Server API 连接 iMessage。
 * BlueBubbles 是一个自托管服务，允许通过 REST API 收发 iMessage/SMS。
 *
 * 功能：
 * - iMessage 文本消息收发
 * - SMS 文本消息收发
 * - 附件（图片/文件）收发
 * - 群组消息
 * - Tapback（表情回应）
 * - 已读回执
 * - 长轮询接收新消息
 *
 * 依赖：BlueBubbles Server 运行中（https://bluebubbles.app/）
 *
 * 配置 (config.yaml):
 * <pre>
 * gateway:
 *   platforms:
 *     bluebubbles:
 *       transport: http
 *       extra:
 *         server_url: "http://localhost:1234"
 *         password: "your-bb-password"
 *         poll_interval: 3
 *         send_as_sms: false
 *         auto_mark_read: true
 * </pre>
 */
public class BlueBubblesAdapter extends BasePlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(BlueBubblesAdapter.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String serverUrl;
    private final String password;
    private final int pollInterval;
    private final boolean sendAsSms;
    private final boolean autoMarkRead;

    private WebClient apiClient;

    // 长轮询
    private ScheduledExecutorService pollExecutor;
    private volatile boolean polling = false;

    // 消息去重
    private final Set<String> processedGuids = ConcurrentHashMap.newKeySet(512);
    private volatile long lastPollTimestamp = 0;

    // 聊天缓存
    private final Map<String, ChatInfo> chatCache = new ConcurrentHashMap<>();

    public BlueBubblesAdapter(PlatformConfig config) {
        super(config, Platform.BLUEBUBBLES);
        this.serverUrl = config.getExtraString("server_url", "http://localhost:1234");
        this.password = config.getExtraString("password", "");
        this.pollInterval = config.getExtraInt("poll_interval", 3);
        this.sendAsSms = config.getExtraBoolean("send_as_sms", false);
        this.autoMarkRead = config.getExtraBoolean("auto_mark_read", true);
    }

    // ========== 聊天信息 ==========

    private static class ChatInfo {
        String guid;
        String name;
        String style; // imessage, sms, group

        ChatInfo(String guid, String name, String style) {
            this.guid = guid;
            this.name = name;
            this.style = style;
        }
    }

    // ========== 生命周期 ==========

    @Override
    public Mono<Boolean> connect() {
        if (serverUrl.isEmpty() || password.isEmpty()) {
            setFatalError("CONFIG_MISSING", "BlueBubbles server_url/password not configured", false);
            return Mono.just(false);
        }

        this.apiClient = WebClient.builder()
            .baseUrl(serverUrl + "/api/v1")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();

        return checkServer()
            .flatMap(ok -> {
                if (!ok) {
                    setFatalError("SERVER_UNAVAILABLE",
                        "BlueBubbles server not reachable at " + serverUrl, true);
                    return Mono.just(false);
                }
                markConnected();
                refreshChats();
                startPolling();
                log.info("BlueBubbles connected: {}", serverUrl);
                return Mono.just(true);
            })
            .onErrorResume(e -> {
                setFatalError("CONNECT_ERROR", e.getMessage(), true);
                return Mono.just(false);
            });
    }

    @Override
    public Mono<Void> disconnect() {
        stopPolling();
        markDisconnected();
        log.info("BlueBubbles disconnected");
        return Mono.empty();
    }

    // ========== 服务器检查 ==========

    private Mono<Boolean> checkServer() {
        return apiClient.get()
            .uri("/server/info?password=" + password)
            .retrieve()
            .bodyToMono(String.class)
            .map(resp -> {
                JsonNode json = parseJson(resp);
                return json.path("status").asInt() == 200;
            })
            .onErrorResume(e -> {
                log.error("BlueBubbles server check failed: {}", e.getMessage());
                return Mono.just(false);
            });
    }

    // ========== 聊天缓存 ==========

    private void refreshChats() {
        apiClient.get()
            .uri("/chat?password=" + password + "&limit=100")
            .retrieve()
            .bodyToMono(String.class)
            .subscribe(resp -> {
                try {
                    JsonNode json = parseJson(resp);
                    JsonNode data = json.path("data");
                    if (data.isArray()) {
                        for (JsonNode chat : data) {
                            String guid = chat.path("guid").asText("");
                            String name = chat.path("displayName").asText("");
                            String style = chat.path("style").asText("imessage");
                            if (!guid.isEmpty()) {
                                chatCache.put(guid, new ChatInfo(guid, name, style));
                            }
                        }
                        log.debug("BlueBubbles chats refreshed: {} entries", chatCache.size());
                    }
                } catch (Exception e) {
                    log.error("BlueBubbles chat parse error: {}", e.getMessage());
                }
            }, error -> log.error("BlueBubbles chat refresh failed: {}", error.getMessage()));
    }

    // ========== 长轮询 ==========

    private void startPolling() {
        if (polling) return;
        polling = true;

        pollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bluebubbles-poll");
            t.setDaemon(true);
            return t;
        });

        pollExecutor.scheduleWithFixedDelay(
            this::pollMessages, 0, pollInterval, TimeUnit.SECONDS);
    }

    private void stopPolling() {
        polling = false;
        if (pollExecutor != null) {
            pollExecutor.shutdownNow();
        }
    }

    private void pollMessages() {
        if (!polling) return;

        try {
            String url = "/message?password=" + password
                + "&limit=10&sort=desc&with=chat";

            if (lastPollTimestamp > 0) {
                url += "&after=" + lastPollTimestamp;
            }

            apiClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(resp -> {
                    JsonNode json = parseJson(resp);
                    JsonNode data = json.path("data");
                    if (data.isArray()) {
                        List<JsonNode> messages = new ArrayList<>();
                        data.forEach(messages::add);
                        // 倒序处理（时间升序）
                        Collections.reverse(messages);
                        for (JsonNode msg : messages) {
                            processMessage(msg);
                        }
                    }
                }, error -> log.trace("BlueBubbles poll error: {}", error.getMessage()));
        } catch (Exception e) {
            log.trace("BlueBubbles poll exception: {}", e.getMessage());
        }
    }

    private void processMessage(JsonNode msg) {
        String guid = msg.path("guid").asText("");
        if (guid.isEmpty() || processedGuids.contains(guid)) return;
        processedGuids.add(guid);

        // 只处理收到的消息
        boolean isFromMe = msg.path("isFromMe").asBoolean(false);
        if (isFromMe) return;

        long dateCreated = msg.path("dateCreated").asLong(0) / 1000;
        if (dateCreated > lastPollTimestamp) {
            lastPollTimestamp = dateCreated;
        }

        String text = msg.path("text").asText("");
        if (text.isEmpty()) return;

        String chatGuid = msg.path("chatGuid").asText("");
        String senderId = msg.path("handle").path("address").asText("unknown");
        String senderName = msg.path("handle").path("displayName").asText(senderId);

        // 确定聊天类型
        ChatInfo chatInfo = chatCache.get(chatGuid);
        String chatName = chatInfo != null ? chatInfo.name : senderId;
        String chatType = chatGuid.contains(";") ? "group" : "dm";

        // 自动标记已读
        if (autoMarkRead) {
            markRead(chatGuid);
        }

        SessionSource source = buildSource(chatGuid, chatName, chatType,
            senderId, senderName, null, null);

        MessageEvent event = new MessageEvent();
        event.setSource(source);
        event.setText(text);
        event.setMessageId(guid);

        // 附件
        JsonNode attachments = msg.path("attachments");
        if (attachments.isArray() && !attachments.isEmpty()) {
            List<String> mediaUrls = new ArrayList<>();
            for (JsonNode att : attachments) {
                String originalGUID = att.path("originalROWID").asText("");
                if (!originalGUID.isEmpty()) {
                    mediaUrls.add(serverUrl + "/api/v1/attachment/"
                        + originalGUID + "?password=" + password);
                }
            }
            if (!mediaUrls.isEmpty()) {
                event.setMediaUrls(mediaUrls);
            }
        }

        handleMessage(event).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    private void markRead(String chatGuid) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("chatGuid", chatGuid);

            apiClient.post()
                .uri("/chat/" + chatGuid + "/read?password=" + password)
                .bodyValue(objectMapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(
                    v -> {},
                    e -> log.trace("BlueBubbles mark read failed: {}", e.getMessage())
                );
        } catch (Exception e) { /* ignore */ }
    }

    // ========== 消息发送 ==========

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("chatGuid", chatId);
            body.put("message", content);
            body.put("method", sendAsSms ? "sms" : "apple-script");
            if (replyTo != null) {
                body.put("selectedMessageGuid", replyTo);
            }

            return apiClient.post()
                .uri("/message/text?password=" + password)
                .bodyValue(objectMapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> {
                    JsonNode json = parseJson(resp);
                    String msgGuid = json.path("data").path("guid").asText("");
                    if (!msgGuid.isEmpty()) {
                        log.info("BlueBubbles message sent: guid={}", msgGuid);
                        return SendResult.success(msgGuid);
                    }
                    String error = json.path("error").asText("Unknown error");
                    return SendResult.failure(error);
                })
                .onErrorResume(e -> {
                    log.error("BlueBubbles send failed: {}", e.getMessage());
                    return Mono.just(SendResult.failure(e.getMessage()));
                });
        } catch (Exception e) {
            return Mono.just(SendResult.failure(e.getMessage()));
        }
    }

    @Override
    public Mono<SendResult> sendImage(String chatId, String imageUrl, String caption,
                                       String replyTo, Map<String, Object> metadata) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("chatGuid", chatId);
            body.put("attachment", imageUrl);
            body.put("method", sendAsSms ? "sms" : "apple-script");
            if (caption != null) body.put("message", caption);

            return apiClient.post()
                .uri("/message/attachment?password=" + password)
                .bodyValue(objectMapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> {
                    JsonNode json = parseJson(resp);
                    String msgGuid = json.path("data").path("guid").asText("");
                    return SendResult.success(msgGuid);
                })
                .onErrorResume(e -> Mono.just(SendResult.failure(e.getMessage())));
        } catch (Exception e) {
            return Mono.just(SendResult.failure(e.getMessage()));
        }
    }

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        ChatInfo info = chatCache.get(chatId);
        if (info != null) {
            return Mono.just(Map.of(
                "id", info.guid,
                "name", info.name,
                "style", info.style
            ));
        }

        return apiClient.get()
            .uri("/chat/" + chatId + "?password=" + password)
            .retrieve()
            .bodyToMono(String.class)
            .map(resp -> {
                JsonNode json = parseJson(resp).path("data");
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", json.path("guid").asText(chatId));
                map.put("name", json.path("displayName").asText(chatId));
                map.put("style", json.path("style").asText("imessage"));
                return map;
            })
            .onErrorResume(e -> Mono.just(Map.of("id", chatId, "name", chatId, "style", "unknown")));
    }

    // ========== 辅助 ==========

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
