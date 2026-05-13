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
 * 微信个人号适配器 (Weixin)
 *
 * 基于 wechaty / wechat4u 等微信协议实现。
 * 本适配器使用 HTTP 桥接模式，连接一个微信协议桥接服务。
 *
 * 支持功能：
 * - 私聊消息收发
 * - 群聊消息收发（@机器人触发）
 * - 图片/文件/语音消息
 * - 好友管理
 * - 联系人/群列表缓存
 *
 * 依赖：外部微信桥接服务（如 wechaty-puppet-service 或 wechatferry）
 *
 * 配置 (config.yaml):
 * <pre>
 * gateway:
 *   platforms:
 *     weixin:
 *       transport: bridge
 *       extra:
 *         bridge_url: "http://localhost:3001"
 *         bridge_token: "your-bridge-token"
 *         auto_accept_friend: true
 *         group_at_only: true
 *         contact_cache_ttl: 3600
 * </pre>
 *
 * ⚠️ 注意：微信个人号机器人存在封号风险，仅用于开发/测试
 */
public class WeixinAdapter extends BasePlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(WeixinAdapter.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String bridgeUrl;
    private final String bridgeToken;
    private final boolean autoAcceptFriend;
    private final boolean groupAtOnly;
    private final int contactCacheTtl;

    private WebClient bridgeClient;

    // 联系人/群缓存
    private final Map<String, ContactInfo> contacts = new ConcurrentHashMap<>();
    private volatile Instant contactsLastRefreshed = Instant.EPOCH;

    // 长轮询
    private ScheduledExecutorService pollExecutor;
    private volatile boolean polling = false;

    public WeixinAdapter(PlatformConfig config) {
        super(config, Platform.WEIXIN);
        this.bridgeUrl = config.getExtraString("bridge_url", "http://localhost:3001");
        this.bridgeToken = config.getExtraString("bridge_token", "");
        this.autoAcceptFriend = config.getExtraBoolean("auto_accept_friend", true);
        this.groupAtOnly = config.getExtraBoolean("group_at_only", true);
        this.contactCacheTtl = config.getExtraInt("contact_cache_ttl", 3600);
    }

    // ========== 联系人信息 ==========

    private static class ContactInfo {
        String id;
        String name;
        String alias;
        String type; // friend, group

        ContactInfo(String id, String name, String type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }
    }

    // ========== 生命周期 ==========

    @Override
    public Mono<Boolean> connect() {
        if (bridgeUrl.isEmpty()) {
            setFatalError("CONFIG_MISSING", "Weixin bridge_url not configured", false);
            return Mono.just(false);
        }

        this.bridgeClient = WebClient.builder()
            .baseUrl(bridgeUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bridgeToken)
            .build();

        return checkBridge()
            .flatMap(available -> {
                if (!available) {
                    setFatalError("BRIDGE_UNAVAILABLE",
                        "Weixin bridge not reachable at " + bridgeUrl, true);
                    return Mono.just(false);
                }
                markConnected();
                refreshContacts();
                startPolling();
                log.info("Weixin connected via bridge: {}", bridgeUrl);
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
        log.info("Weixin disconnected");
        return Mono.empty();
    }

    // ========== 桥接服务检查 ==========

    private Mono<Boolean> checkBridge() {
        return bridgeClient.get()
            .uri("/health")
            .retrieve()
            .bodyToMono(String.class)
            .map(resp -> {
                JsonNode json = parseJson(resp);
                return json.path("status").asText("").equals("ok")
                    || json.path("ready").asBoolean(false);
            })
            .onErrorResume(e -> {
                log.error("Weixin bridge health check failed: {}", e.getMessage());
                return Mono.just(false);
            });
    }

    // ========== 联系人缓存 ==========

    private void refreshContacts() {
        bridgeClient.get()
            .uri("/contacts")
            .retrieve()
            .bodyToMono(String.class)
            .subscribe(resp -> {
                try {
                    JsonNode json = parseJson(resp);
                    if (json.isArray()) {
                        for (JsonNode contact : json) {
                            String id = contact.path("id").asText("");
                            String name = contact.path("name").asText("");
                            String type = contact.path("type").asText("friend");
                            if (!id.isEmpty()) {
                                contacts.put(id, new ContactInfo(id, name, type));
                            }
                        }
                        contactsLastRefreshed = Instant.now();
                        log.debug("Weixin contacts refreshed: {} entries", contacts.size());
                    }
                } catch (Exception e) {
                    log.error("Weixin contacts parse error: {}", e.getMessage());
                }
            }, error -> log.error("Weixin contacts refresh failed: {}", error.getMessage()));
    }

    private String getContactName(String id) {
        ContactInfo info = contacts.get(id);
        return info != null ? info.name : id;
    }

    private String getContactType(String id) {
        ContactInfo info = contacts.get(id);
        return info != null ? info.type : "unknown";
    }

    // ========== 长轮询 ==========

    private void startPolling() {
        if (polling) return;
        polling = true;

        pollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "weixin-poll");
            t.setDaemon(true);
            return t;
        });

        pollExecutor.scheduleWithFixedDelay(this::pollMessages, 0, 2, TimeUnit.SECONDS);
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
            bridgeClient.get()
                .uri("/messages/pending")
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(resp -> {
                    JsonNode json = parseJson(resp);
                    if (json.isArray()) {
                        for (JsonNode msg : json) {
                            processMessage(msg);
                        }
                    }
                }, error -> log.trace("Weixin poll error: {}", error.getMessage()));
        } catch (Exception e) {
            log.trace("Weixin poll exception: {}", e.getMessage());
        }
    }

    private void processMessage(JsonNode msg) {
        String msgType = msg.path("type").asText("text");
        String fromId = msg.path("from_id").asText("");
        String roomId = msg.path("room_id").asText("");
        String content = msg.path("content").asText("");
        String senderId = msg.path("sender_id").asText("");
        String senderName = msg.path("sender_name").asText("");
        String msgId = msg.path("id").asText(UUID.randomUUID().toString());

        if (content.isEmpty()) return;

        // 群聊 @机器人 过滤
        boolean isGroup = !roomId.isEmpty();
        if (isGroup && groupAtOnly) {
            // 检查是否 @了机器人（bridge 通常已预处理，去掉 @文本）
            if (!msg.path("mentioned").asBoolean(false)) return;
        }

        String chatId = isGroup ? roomId : fromId;
        String chatName = isGroup ? getContactName(roomId) : getContactName(fromId);
        String chatType = isGroup ? "group" : "dm";

        // 处理自动加好友
        if ("friend_request".equals(msgType) && autoAcceptFriend) {
            acceptFriendRequest(fromId);
            return;
        }

        SessionSource source = buildSource(chatId, chatName, chatType,
            senderId, senderName, null, null);

        MessageEvent event = new MessageEvent();
        event.setSource(source);
        event.setText(content);
        event.setMessageId(msgId);

        // 附件处理
        if (msg.has("media_url")) {
            event.setMediaUrls(List.of(msg.get("media_url").asText()));
        }

        handleMessage(event).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    private void acceptFriendRequest(String userId) {
        bridgeClient.post()
            .uri("/friends/accept")
            .bodyValue("{\"user_id\":\"" + userId + "\"}")
            .retrieve()
            .bodyToMono(String.class)
            .subscribe(
                v -> log.info("Weixin auto-accepted friend: {}", userId),
                e -> log.error("Weixin friend accept failed: {}", e.getMessage())
            );
    }

    // ========== 消息发送 ==========

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("to_id", chatId);
            body.put("content", content);
            body.put("type", "text");
            if (replyTo != null) {
                body.put("reply_to", replyTo);
            }

            return bridgeClient.post()
                .uri("/messages/send")
                .bodyValue(objectMapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> {
                    JsonNode json = parseJson(resp);
                    String id = json.path("id").asText("");
                    return SendResult.success(id);
                })
                .onErrorResume(e -> {
                    log.error("Weixin send failed: {}", e.getMessage());
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
            body.put("to_id", chatId);
            body.put("type", "image");
            body.put("url", imageUrl);
            if (caption != null) body.put("content", caption);

            return bridgeClient.post()
                .uri("/messages/send")
                .bodyValue(objectMapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> SendResult.success(parseJson(resp).path("id").asText("")))
                .onErrorResume(e -> Mono.just(SendResult.failure(e.getMessage())));
        } catch (Exception e) {
            return Mono.just(SendResult.failure(e.getMessage()));
        }
    }

    @Override
    public Mono<SendResult> sendVoice(String chatId, String audioPath, String caption, String replyTo) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("to_id", chatId);
            body.put("type", "voice");
            body.put("url", audioPath);
            if (caption != null) body.put("content", caption);

            return bridgeClient.post()
                .uri("/messages/send")
                .bodyValue(objectMapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> SendResult.success(parseJson(resp).path("id").asText("")))
                .onErrorResume(e -> Mono.just(SendResult.failure(e.getMessage())));
        } catch (Exception e) {
            return Mono.just(SendResult.failure(e.getMessage()));
        }
    }

    @Override
    public Mono<SendResult> sendDocument(String chatId, String filePath, String caption,
                                          String fileName, String replyTo) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("to_id", chatId);
            body.put("type", "file");
            body.put("url", filePath);
            if (fileName != null) body.put("filename", fileName);
            if (caption != null) body.put("content", caption);

            return bridgeClient.post()
                .uri("/messages/send")
                .bodyValue(objectMapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> SendResult.success(parseJson(resp).path("id").asText("")))
                .onErrorResume(e -> Mono.just(SendResult.failure(e.getMessage())));
        } catch (Exception e) {
            return Mono.just(SendResult.failure(e.getMessage()));
        }
    }

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        // 刷新缓存（如果过期）
        if (Instant.now().isAfter(contactsLastRefreshed.plusSeconds(contactCacheTtl))) {
            refreshContacts();
        }

        ContactInfo info = contacts.get(chatId);
        if (info != null) {
            return Mono.just(Map.of(
                "id", info.id,
                "name", info.name,
                "type", info.type
            ));
        }

        // 尝试从桥接服务获取
        return bridgeClient.get()
            .uri("/contacts/" + chatId)
            .retrieve()
            .bodyToMono(String.class)
            .map(resp -> {
                JsonNode json = parseJson(resp);
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", json.path("id").asText(chatId));
                map.put("name", json.path("name").asText(chatId));
                map.put("type", json.path("type").asText("unknown"));
                return map;
            })
            .onErrorResume(e -> Mono.just(Map.of("id", chatId, "name", chatId, "type", "unknown")));
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
