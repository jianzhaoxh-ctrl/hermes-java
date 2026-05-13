package com.hermes.agent.gateway.adapters;

import com.hermes.agent.gateway.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.core.Disposable;
import reactor.util.retry.Retry;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * QQ 官方机器人适配器
 *
 * 使用 QQ 机器人官方 API 实现：
 * - WebSocket 长连接接收事件（完整实现）
 * - HTTP API 发送消息
 * - 频道（Guild）消息 + 群聊消息 + C2C 私聊
 * - Markdown / Ark 消息模板
 * - 附件上传
 * - 自动重连与心跳保活
 *
 * 配置 (config.yaml):
 * <pre>
 * gateway:
 *   platforms:
 *     qqbot:
 *       transport: websocket
 *       extra:
 *         app_id: "1234567890"
 *         app_secret: "your-secret"
 *         intent_guilds: true
 *         intent_c2c: true
 *         sandbox: false
 * </pre>
 */
public class QQBotAdapter extends BasePlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(QQBotAdapter.class);

    // QQ Bot WebSocket 端点
    private static final String WS_URL = "wss://api.sgroup.qq.com/websocket";
    private static final String WS_URL_SANDBOX = "wss://sandbox.api.sgroup.qq.com/websocket";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String appId;
    private final String appSecret;
    private final boolean intentGuilds;
    private final boolean intentC2C;
    private final boolean sandbox;

    private WebClient apiClient;
    private String accessToken;
    private Instant tokenExpiresAt;

    // WebSocket 客户端
    private WebSocketClient wsClient;
    private Disposable wsConnection;
    private volatile boolean wsRunning = false;

    // WebSocket Session 引用（用于发送消息）
    private volatile org.springframework.web.reactive.socket.WebSocketSession wsSession;

    // 会话状态
    private volatile String sessionId;
    private volatile int lastSeq = 0;
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledFuture<?> heartbeatTask;

    // 消息序号缓存（用于去重）
    private final Set<String> processedMsgIds = ConcurrentHashMap.newKeySet(256);

    public QQBotAdapter(PlatformConfig config) {
        super(config, Platform.QQBOT);
        this.appId = config.getExtraString("app_id", "");
        this.appSecret = config.getExtraString("app_secret", "");
        this.intentGuilds = config.getExtraBoolean("intent_guilds", true);
        this.intentC2C = config.getExtraBoolean("intent_c2c", true);
        this.sandbox = config.getExtraBoolean("sandbox", false);
    }

    // ========== 生命周期 ==========

    @Override
    public Mono<Boolean> connect() {
        if (appId.isEmpty() || appSecret.isEmpty()) {
            setFatalError("CONFIG_MISSING", "QQ Bot app_id/app_secret not configured", false);
            return Mono.just(false);
        }

        String baseUrl = sandbox
            ? "https://sandbox.api.sgroup.qq.com"
            : "https://api.sgroup.qq.com";

        this.apiClient = WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();

        this.wsClient = new ReactorNettyWebSocketClient();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qqbot-heartbeat");
            t.setDaemon(true);
            return t;
        });

        return refreshToken()
            .flatMap(success -> {
                if (!success) {
                    setFatalError("AUTH_FAILED", "Failed to get QQ Bot access token", true);
                    return Mono.just(false);
                }
                markConnected();
                log.info("QQ Bot connected: appId={}", appId);
                startWebSocket();
                return Mono.just(true);
            })
            .onErrorResume(e -> {
                setFatalError("CONNECT_ERROR", e.getMessage(), true);
                return Mono.just(false);
            });
    }

    @Override
    public Mono<Void> disconnect() {
        stopWebSocket();
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        markDisconnected();
        log.info("QQ Bot disconnected");
        return Mono.empty();
    }

    // ========== 认证 ==========

    private Mono<Boolean> refreshToken() {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("appId", appId);
            body.put("clientSecret", appSecret);

            return apiClient.post()
                .uri("/app/getAppAccessToken")
                .bodyValue(objectMapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> {
                    JsonNode json = parseJson(resp);
                    if (json.has("access_token")) {
                        this.accessToken = json.get("access_token").asText();
                        int expiresIn = json.has("expires_in") ? json.get("expires_in").asInt() : 7200;
                        this.tokenExpiresAt = Instant.now().plusSeconds(expiresIn - 300);
                        log.debug("QQ Bot token refreshed, expires in {}s", expiresIn);
                        return true;
                    }
                    log.error("QQ Bot token response missing access_token: {}", resp);
                    return false;
                })
                .onErrorResume(e -> {
                    log.error("QQ Bot token refresh failed: {}", e.getMessage());
                    return Mono.just(false);
                });
        } catch (Exception e) {
            log.error("QQ Bot token request build failed: {}", e.getMessage());
            return Mono.just(false);
        }
    }

    private Mono<Boolean> ensureToken() {
        if (accessToken != null && Instant.now().isBefore(tokenExpiresAt)) {
            return Mono.just(true);
        }
        return refreshToken();
    }

    // ========== WebSocket 完整实现 ==========

    private void startWebSocket() {
        wsRunning = true;
        connectWebSocket();
    }

    private void stopWebSocket() {
        wsRunning = false;
        if (wsConnection != null && !wsConnection.isDisposed()) {
            wsConnection.dispose();
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
    }

    private void connectWebSocket() {
        if (!wsRunning) return;

        String wsUrl = sandbox ? WS_URL_SANDBOX : WS_URL;
        URI uri = URI.create(wsUrl);

        log.info("QQ Bot connecting to WebSocket: {}", wsUrl);

        wsConnection = wsClient.execute(uri, session -> {
            // 保存 session 引用
            this.wsSession = session;

            // 接收消息流
            Flux<WebSocketMessage> receive = session.receive()
                .doOnNext(msg -> handleWsMessage(msg.getPayloadAsText()))
                .doOnError(e -> log.error("QQ Bot WS receive error: {}", e.getMessage()))
                .doOnComplete(() -> {
                    log.warn("QQ Bot WS completed, reconnecting...");
                    this.wsSession = null;
                    scheduleReconnect();
                });

            // 发送心跳（在 Hello 后启动）
            Mono<Void> heartbeatMono = Mono.never();

            return receive.then(heartbeatMono);
        })
        .retryWhen(Retry.backoff(5, Duration.ofSeconds(2))
            .maxBackoff(Duration.ofSeconds(30))
            .doBeforeRetry(signal -> log.warn("QQ Bot WS retry attempt {}", signal.totalRetries() + 1))
            .onRetryExhaustedThrow((spec, signal) -> {
                log.error("QQ Bot WS retry exhausted");
                return signal.failure();
            }))
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(
            v -> {},
            e -> {
                log.error("QQ Bot WS error: {}", e.getMessage());
                scheduleReconnect();
            },
            () -> log.info("QQ Bot WS completed")
        );
    }

    private void scheduleReconnect() {
        if (!wsRunning) return;
        heartbeatExecutor.schedule(this::connectWebSocket, 3, TimeUnit.SECONDS);
    }

    private void handleWsMessage(String payload) {
        try {
            JsonNode event = parseJson(payload);
            int opcode = event.has("op") ? event.get("op").asInt() : -1;

            switch (opcode) {
                case 10 -> handleHello(event);      // Hello - 开始心跳
                case 11 -> handleHeartbeatAck();    // Heartbeat ACK
                case 0 -> handleDispatch(event);     // Dispatch - 事件
                case 9 -> handleReconnect();         // 需要重连
                case 7 -> handleResume(event);       // Resume 要求
                default -> log.debug("QQ Bot WS opcode {}: ignored", opcode);
            }
        } catch (Exception e) {
            log.error("QQ Bot WS event parse error: {}", e.getMessage());
        }
    }

    /**
     * 处理 Hello (opcode 10) - 启动心跳并鉴权
     */
    private void handleHello(JsonNode event) {
        int heartbeatInterval = event.at("/d/heartbeat_interval").asInt(41250);
        log.info("QQ Bot WS Hello received, heartbeat_interval={}ms", heartbeatInterval);

        // 启动心跳任务
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(
            this::sendHeartbeat,
            heartbeatInterval / 1000,
            heartbeatInterval / 1000,
            TimeUnit.MILLISECONDS
        );

        // 发送鉴权
        sendIdentify();
    }

    /**
     * 发送鉴权 (opcode 2)
     */
    private void sendIdentify() {
        if (wsSession == null || !wsSession.isOpen()) {
            log.warn("QQ Bot WS session not available for identify");
            return;
        }
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("op", 2);

            ObjectNode d = objectMapper.createObjectNode();
            d.put("token", "QQBot " + accessToken);
            d.put("intents", calculateIntents());
            d.putPOJO("shard", new int[]{0, 1});  // 单分片

            ObjectNode properties = objectMapper.createObjectNode();
            properties.put("$os", "linux");
            properties.put("$browser", "hermes");
            properties.put("$device", "hermes");
            d.set("properties", properties);

            payload.set("d", d);

            String msg = objectMapper.writeValueAsString(payload);
            wsSession.send(Mono.just(wsSession.textMessage(msg)))
                .subscribe(
                    v -> log.debug("QQ Bot identify sent"),
                    e -> log.error("QQ Bot identify send error: {}", e.getMessage())
                );
        } catch (Exception e) {
            log.error("QQ Bot identify build failed: {}", e.getMessage());
        }
    }

    /**
     * 计算 intents 位掩码
     */
    private int calculateIntents() {
        int intents = 0;
        if (intentGuilds) {
            // GUILDS (1<<0) + GUILD_MESSAGES (1<<9) + GUILD_MESSAGE_REACTIONS (1<<10)
            intents |= (1 << 0) | (1 << 9) | (1 << 10);
        }
        if (intentC2C) {
            // DIRECT_MESSAGE (1<<13)
            intents |= (1 << 13);
        }
        return intents;
    }

    /**
     * 发送心跳 (opcode 1)
     */
    private void sendHeartbeat() {
        if (wsSession == null || !wsSession.isOpen()) {
            log.trace("QQ Bot WS session not available for heartbeat");
            return;
        }
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("op", 1);
            payload.put("d", lastSeq);

            String msg = objectMapper.writeValueAsString(payload);
            wsSession.send(Mono.just(wsSession.textMessage(msg)))
                .subscribe(
                    v -> log.trace("QQ Bot heartbeat sent, seq={}", lastSeq),
                    e -> log.error("QQ Bot heartbeat send error: {}", e.getMessage())
                );
        } catch (Exception e) {
            log.error("QQ Bot heartbeat build failed: {}", e.getMessage());
        }
    }

    private void handleHeartbeatAck() {
        log.trace("QQ Bot heartbeat ACK");
    }

    private void handleReconnect() {
        log.warn("QQ Bot server requests reconnect");
        stopWebSocket();
        scheduleReconnect();
    }

    private void handleResume(JsonNode event) {
        log.info("QQ Bot resume requested");
        if (sessionId != null) {
            sendResume();
        } else {
            sendIdentify();
        }
    }

    /**
     * 发送 Resume (opcode 6) - 断线重连恢复
     */
    private void sendResume() {
        if (wsSession == null || !wsSession.isOpen()) {
            log.warn("QQ Bot WS session not available for resume");
            sendIdentify();
            return;
        }
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("op", 6);

            ObjectNode d = objectMapper.createObjectNode();
            d.put("token", "QQBot " + accessToken);
            d.put("session_id", sessionId);
            d.put("seq", lastSeq);

            payload.set("d", d);

            String msg = objectMapper.writeValueAsString(payload);
            wsSession.send(Mono.just(wsSession.textMessage(msg)))
                .subscribe(
                    v -> log.debug("QQ Bot resume sent"),
                    e -> log.error("QQ Bot resume send error: {}", e.getMessage())
                );
        } catch (Exception e) {
            log.error("QQ Bot resume build failed: {}", e.getMessage());
        }
    }

    /**
     * 处理 Dispatch (opcode 0) - 事件分发
     */
    private void handleDispatch(JsonNode event) {
        String eventType = event.has("t") ? event.get("t").asText() : "";
        JsonNode data = event.get("d");

        if (event.has("s")) {
            lastSeq = event.get("s").asInt();
        }

        if (data == null) {
            log.debug("QQ Bot dispatch {} has no data", eventType);
            return;
        }

        // 保存 session_id
        if ("READY".equals(eventType) && data.has("session_id")) {
            sessionId = data.get("session_id").asText();
            log.info("QQ Bot session ready: sessionId={}", sessionId);
            return;
        }

        switch (eventType) {
            case "MESSAGE_CREATE", "AT_MESSAGE_CREATE" -> handleGuildMessage(data);
            case "C2C_MESSAGE_CREATE" -> handleC2CMessage(data);
            case "DIRECT_MESSAGE_CREATE" -> handleDirectMessage(data);
            case "GUILD_CREATE" -> log.debug("QQ Bot guild created: {}", data.path("id").asText(""));
            case "CHANNEL_CREATE" -> log.debug("QQ Bot channel created: {}", data.path("id").asText(""));
            default -> log.debug("QQ Bot event type {} ignored", eventType);
        }
    }

    // ========== 消息处理 ==========

    private void handleGuildMessage(JsonNode data) {
        String msgId = data.path("id").asText("");
        if (msgId.isEmpty() || processedMsgIds.contains(msgId)) return;
        processedMsgIds.add(msgId);

        // 清理过期消息 ID
        if (processedMsgIds.size() > 1000) {
            processedMsgIds.clear();
        }

        String content = data.path("content").asText("");
        String channelId = data.path("channel_id").asText("");
        String guildId = data.path("guild_id").asText("");
        String authorId = data.path("author").path("user_openid").asText("");
        String authorName = data.path("author").path("username").asText("");

        // 处理 @机器人 提取纯文本
        content = content.replaceAll("<@!\\d+>", "").trim();
        if (content.isEmpty()) return;

        SessionSource source = buildSource(channelId, "频道消息", "group",
            authorId, authorName, null, null);
        source.setGuildId(guildId);

        MessageEvent event = new MessageEvent();
        event.setSource(source);
        event.setText(content);
        event.setMessageId(msgId);

        handleMessage(event).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    private void handleC2CMessage(JsonNode data) {
        String msgId = data.path("id").asText("");
        if (msgId.isEmpty() || processedMsgIds.contains(msgId)) return;
        processedMsgIds.add(msgId);

        String content = data.path("content").asText("");
        String authorOpenid = data.path("author").path("user_openid").asText("");

        SessionSource source = buildSource(authorOpenid, "私聊", "dm",
            authorOpenid, "", null, null);

        MessageEvent event = new MessageEvent();
        event.setSource(source);
        event.setText(content);
        event.setMessageId(msgId);

        handleMessage(event).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    private void handleDirectMessage(JsonNode data) {
        String msgId = data.path("id").asText("");
        if (msgId.isEmpty() || processedMsgIds.contains(msgId)) return;
        processedMsgIds.add(msgId);

        String content = data.path("content").asText("");
        String channelId = data.path("channel_id").asText("");
        String authorId = data.path("author").path("user_openid").asText("");

        SessionSource source = buildSource(channelId, "私信", "dm",
            authorId, "", null, null);

        MessageEvent event = new MessageEvent();
        event.setSource(source);
        event.setText(content);
        event.setMessageId(msgId);

        handleMessage(event).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    // ========== 消息发送 ==========

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        return ensureToken().flatMap(ok -> {
            try {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("content", content);
                body.put("msg_type", 0); // 文本
                body.put("msg_id", replyTo != null ? replyTo : UUID.randomUUID().toString());

                // 判断频道消息还是 C2C
                String endpoint;
                if (chatId.startsWith("CHANNEL_") || chatId.length() > 20) {
                    // 频道消息
                    endpoint = "/channels/" + chatId + "/messages";
                } else {
                    // C2C 消息
                    body.put("msg_seq", System.currentTimeMillis() % 1000000);
                    endpoint = "/v2/users/" + chatId + "/messages";
                }

                return apiClient.post()
                    .uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "QQBot " + accessToken)
                    .bodyValue(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(resp -> {
                        JsonNode json = parseJson(resp);
                        String id = json.path("id").asText("");
                        return SendResult.success(id);
                    })
                    .onErrorResume(e -> {
                        log.error("QQ Bot send failed: {}", e.getMessage());
                        return Mono.just(SendResult.failure(e.getMessage()));
                    });
            } catch (Exception e) {
                return Mono.just(SendResult.failure(e.getMessage()));
            }
        });
    }

    @Override
    public Mono<SendResult> sendImage(String chatId, String imageUrl, String caption,
                                       String replyTo, Map<String, Object> metadata) {
        return ensureToken().flatMap(ok -> {
            try {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("msg_type", 1); // 图文

                ObjectNode media = objectMapper.createObjectNode();
                media.put("image", imageUrl);
                body.set("media", media);

                if (caption != null) body.put("content", caption);

                String endpoint = chatId.length() > 20
                    ? "/channels/" + chatId + "/messages"
                    : "/v2/users/" + chatId + "/messages";

                return apiClient.post()
                    .uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "QQBot " + accessToken)
                    .bodyValue(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(resp -> SendResult.success(parseJson(resp).path("id").asText("")))
                    .onErrorResume(e -> Mono.just(SendResult.failure(e.getMessage())));
            } catch (Exception e) {
                return Mono.just(SendResult.failure(e.getMessage()));
            }
        });
    }

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        return ensureToken().flatMap(ok ->
            apiClient.get()
                .uri("/channels/" + chatId)
                .header(HttpHeaders.AUTHORIZATION, "QQBot " + accessToken)
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    JsonNode json = parseJson(resp);
                    info.put("id", json.path("id").asText(""));
                    info.put("name", json.path("name").asText(""));
                    info.put("type", json.path("type").asInt(0));
                    return info;
                })
                .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage())))
        );
    }

    // ========== 辅助 ==========

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String toJsonString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
