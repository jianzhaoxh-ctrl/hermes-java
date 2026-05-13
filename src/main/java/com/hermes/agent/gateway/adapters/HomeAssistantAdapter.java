package com.hermes.agent.gateway.adapters;

import com.hermes.agent.gateway.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Home Assistant 适配器
 *
 * 通过 Home Assistant REST API / WebSocket 与智能家居系统集成。
 *
 * 功能：
 * - 接收 HA 事件（state_changed, automation_triggered 等）
 * - 调用 HA 服务（light.turn_on, switch.toggle 等）
 * - 查询实体状态
 * - 接收通知（notify 服务 → Agent 处理）
 * - WebSocket 长连接实时事件推送（完整实现）
 * - 自动重连与认证
 *
 * 配置 (config.yaml):
 * <pre>
 * gateway:
 *   platforms:
 *     homeassistant:
 *       transport: websocket
 *       extra:
 *         ha_url: "http://homeassistant.local:8123"
 *         ha_token: "eyJhbG..."     # Long-Lived Access Token
 *         subscribe_events:
 *           - "state_changed"
 *           - "automation_triggered"
 *           - "notify.hermes"
 *         entity_filters:
 *           - "light.*"
 *           - "switch.*"
 *           - "sensor.*"
 *         notify_only: false         # true=仅接收通知, false=接收所有事件
 * </pre>
 */
public class HomeAssistantAdapter extends BasePlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(HomeAssistantAdapter.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String haUrl;
    private final String haToken;
    private final List<String> subscribeEvents;
    private final List<String> entityFilters;
    private final boolean notifyOnly;

    private WebClient apiClient;
    private WebSocketClient wsClient;

    // WebSocket 连接
    private Disposable wsConnection;
    private volatile boolean wsRunning = false;
    private volatile int commandId = 1;
    private final Map<Integer, CompletableFuture<JsonNode>> pendingCommands = new ConcurrentHashMap<>();

    // 实体状态缓存
    private final Map<String, EntityState> entityStates = new ConcurrentHashMap<>();

    // 心跳
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledFuture<?> heartbeatTask;

    public HomeAssistantAdapter(PlatformConfig config) {
        super(config, Platform.HOMEASSISTANT);
        this.haUrl = config.getExtraString("ha_url", "http://homeassistant.local:8123");
        this.haToken = config.getExtraString("ha_token", "");
        this.subscribeEvents = config.getExtraStringList("subscribe_events");
        this.entityFilters = config.getExtraStringList("entity_filters");
        this.notifyOnly = config.getExtraBoolean("notify_only", false);
    }

    // ========== 实体状态 ==========

    private static class EntityState {
        String entityId;
        String state;
        Map<String, Object> attributes;
        Instant lastChanged;

        EntityState(String entityId, String state, Map<String, Object> attributes) {
            this.entityId = entityId;
            this.state = state;
            this.attributes = attributes != null ? attributes : Map.of();
            this.lastChanged = Instant.now();
        }
    }

    // ========== 生命周期 ==========

    @Override
    public Mono<Boolean> connect() {
        if (haUrl.isEmpty() || haToken.isEmpty()) {
            setFatalError("CONFIG_MISSING", "Home Assistant ha_url/ha_token not configured", false);
            return Mono.just(false);
        }

        this.apiClient = WebClient.builder()
            .baseUrl(haUrl + "/api")
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + haToken)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();

        this.wsClient = new ReactorNettyWebSocketClient();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ha-heartbeat");
            t.setDaemon(true);
            return t;
        });

        return checkConnection()
            .flatMap(ok -> {
                if (!ok) {
                    setFatalError("AUTH_FAILED", "Home Assistant connection/auth failed", true);
                    return Mono.just(false);
                }
                markConnected();
                refreshEntityStates();
                startWebSocket();
                log.info("Home Assistant connected: {}", haUrl);
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
        log.info("Home Assistant disconnected");
        return Mono.empty();
    }

    // ========== HA API ==========

    private Mono<Boolean> checkConnection() {
        return apiClient.get()
            .uri("/")
            .retrieve()
            .bodyToMono(String.class)
            .map(resp -> {
                JsonNode json = parseJson(resp);
                String status = json.path("message").asText("");
                return "API running".equals(status);
            })
            .onErrorResume(e -> Mono.just(false));
    }

    private void refreshEntityStates() {
        apiClient.get()
            .uri("/states")
            .retrieve()
            .bodyToMono(String.class)
            .subscribe(resp -> {
                try {
                    JsonNode json = parseJson(resp);
                    if (json.isArray()) {
                        for (JsonNode entity : json) {
                            String entityId = entity.path("entity_id").asText("");
                            String state = entity.path("state").asText("");
                            Map<String, Object> attrs = new LinkedHashMap<>();
                            JsonNode attrNode = entity.path("attributes");
                            if (attrNode.isObject()) {
                                attrNode.fields().forEachRemaining(e ->
                                    attrs.put(e.getKey(), e.getValue().asText()));
                            }
                            entityStates.put(entityId, new EntityState(entityId, state, attrs));
                        }
                        log.debug("HA entity states refreshed: {} entities", entityStates.size());
                    }
                } catch (Exception e) {
                    log.error("HA entity state parse error: {}", e.getMessage());
                }
            }, error -> log.error("HA entity refresh failed: {}", error.getMessage()));
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

        String wsUrl = haUrl.replace("http://", "ws://").replace("https://", "wss://") + "/api/websocket";
        URI uri = URI.create(wsUrl);

        log.info("Home Assistant connecting to WebSocket: {}", wsUrl);

        wsConnection = wsClient.execute(uri, session -> {
            // 接收消息流
            return session.receive()
                .doOnNext(msg -> handleWsMessage(msg.getPayloadAsText(), session))
                .doOnError(e -> log.error("HA WS receive error: {}", e.getMessage()))
                .doOnComplete(() -> {
                    log.warn("HA WS completed, reconnecting...");
                    scheduleReconnect();
                })
                .then();
        })
        .retryWhen(Retry.backoff(5, Duration.ofSeconds(3))
            .maxBackoff(Duration.ofSeconds(60))
            .doBeforeRetry(signal -> log.warn("HA WS retry attempt {}", signal.totalRetries() + 1))
            .onRetryExhaustedThrow((spec, signal) -> {
                log.error("HA WS retry exhausted");
                return signal.failure();
            }))
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(
            v -> {},
            e -> {
                log.error("HA WS error: {}", e.getMessage());
                scheduleReconnect();
            },
            () -> log.info("HA WS completed")
        );
    }

    private void scheduleReconnect() {
        if (!wsRunning) return;
        heartbeatExecutor.schedule(this::connectWebSocket, 5, TimeUnit.SECONDS);
    }

    private void handleWsMessage(String payload, org.springframework.web.reactive.socket.WebSocketSession session) {
        try {
            JsonNode event = parseJson(payload);
            String type = event.path("type").asText("");

            switch (type) {
                case "auth_required" -> sendAuth(session);
                case "auth_ok" -> {
                    log.info("HA WebSocket authenticated");
                    subscribeToEvents(session);
                }
                case "auth_invalid" -> {
                    log.error("HA WebSocket auth invalid: {}", event.path("message").asText(""));
                    stopWebSocket();
                }
                case "pong" -> handlePong(event);
                case "result" -> handleResult(event);
                case "event" -> handleEvent(event);
                default -> log.debug("HA WS type {} ignored", type);
            }
        } catch (Exception e) {
            log.error("HA WS event parse error: {}", e.getMessage());
        }
    }

    /**
     * 发送认证
     */
    private void sendAuth(org.springframework.web.reactive.socket.WebSocketSession session) {
        try {
            ObjectNode auth = objectMapper.createObjectNode();
            auth.put("type", "auth");
            auth.put("access_token", haToken);

            String msg = objectMapper.writeValueAsString(auth);
            session.send(Mono.just(session.textMessage(msg)))
                .subscribe(
                    v -> log.debug("HA auth sent"),
                    e -> log.error("HA auth send error: {}", e.getMessage())
                );
        } catch (Exception e) {
            log.error("HA auth build failed: {}", e.getMessage());
        }
    }

    /**
     * 订阅事件
     */
    private void subscribeToEvents(org.springframework.web.reactive.socket.WebSocketSession session) {
        // 默认订阅事件
        List<String> events = subscribeEvents.isEmpty()
            ? List.of("state_changed", "automation_triggered")
            : subscribeEvents;

        for (String eventType : events) {
            try {
                ObjectNode sub = objectMapper.createObjectNode();
                sub.put("id", commandId++);
                sub.put("type", "subscribe_events");
                sub.put("event_type", eventType);

                String msg = objectMapper.writeValueAsString(sub);
                session.send(Mono.just(session.textMessage(msg)))
                    .subscribe(
                        v -> log.debug("HA subscribed to event: {}", eventType),
                        e -> log.error("HA subscribe error: {}", e.getMessage())
                    );
            } catch (Exception e) {
                log.error("HA subscribe build failed: {}", e.getMessage());
            }
        }

        // 启动心跳
        startHeartbeat(session);
    }

    /**
     * 启动心跳（ping/pong）
     */
    private void startHeartbeat(org.springframework.web.reactive.socket.WebSocketSession session) {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                ObjectNode ping = objectMapper.createObjectNode();
                ping.put("id", commandId++);
                ping.put("type", "ping");

                String msg = objectMapper.writeValueAsString(ping);
                session.send(Mono.just(session.textMessage(msg)))
                    .subscribe(
                        v -> log.trace("HA ping sent"),
                        e -> log.error("HA ping error: {}", e.getMessage())
                    );
            } catch (Exception e) {
                log.error("HA ping build failed: {}", e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void handlePong(JsonNode event) {
        log.trace("HA pong received");
    }

    private void handleResult(JsonNode event) {
        int id = event.path("id").asInt(0);
        boolean success = event.path("success").asBoolean(false);

        CompletableFuture<JsonNode> future = pendingCommands.remove(id);
        if (future != null) {
            if (success) {
                future.complete(event.path("result"));
            } else {
                JsonNode error = event.path("error");
                future.completeExceptionally(new RuntimeException(error.path("message").asText("Unknown error")));
            }
        }
    }

    /**
     * 处理事件
     */
    private void handleEvent(JsonNode event) {
        JsonNode data = event.path("data");
        String eventType = data.path("event_type").asText("");

        if (notifyOnly && !"notify.hermes".equals(eventType)) return;

        switch (eventType) {
            case "state_changed" -> handleStateChanged(data);
            case "automation_triggered" -> handleAutomation(data);
            case "notify.hermes" -> handleNotify(data);
            default -> {
                // 检查是否是 notify 服务调用
                if (eventType.startsWith("notify.")) {
                    handleNotify(data);
                } else {
                    log.debug("HA event type {} ignored", eventType);
                }
            }
        }
    }

    private void handleStateChanged(JsonNode data) {
        String entityId = data.path("data").path("entity_id").asText("");

        // 实体过滤器
        if (!matchesFilter(entityId)) return;

        JsonNode newState = data.path("data").path("new_state");
        String state = newState.path("state").asText("");

        // 更新缓存
        Map<String, Object> attrs = new LinkedHashMap<>();
        JsonNode attrNode = newState.path("attributes");
        if (attrNode.isObject()) {
            attrNode.fields().forEachRemaining(e ->
                attrs.put(e.getKey(), e.getValue().asText()));
        }
        entityStates.put(entityId, new EntityState(entityId, state, attrs));

        // 构建通知消息
        String friendlyName = attrs.getOrDefault("friendly_name", entityId).toString();
        String oldState = data.path("data").path("old_state").path("state").asText("?");
        String text = String.format("🏠 %s 状态变更: %s → %s", friendlyName, oldState, state);

        SessionSource source = buildSource("ha-events", "Home Assistant", "channel",
            "homeassistant", "Home Assistant", null, "智能家居事件");

        MessageEvent event = new MessageEvent();
        event.setSource(source);
        event.setText(text);
        event.setMessageId("ha-" + System.currentTimeMillis());

        handleMessage(event).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    private void handleAutomation(JsonNode data) {
        String automationName = data.path("data").path("name").asText("unknown");
        String text = String.format("⚡ 自动化触发: %s", automationName);

        SessionSource source = buildSource("ha-automations", "Home Assistant", "channel",
            "homeassistant", "Home Assistant", null, "自动化事件");

        MessageEvent event = new MessageEvent();
        event.setSource(source);
        event.setText(text);
        event.setMessageId("ha-auto-" + System.currentTimeMillis());

        handleMessage(event).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    private void handleNotify(JsonNode data) {
        String message = data.path("data").path("message").asText("");
        String title = data.path("data").path("title").asText("");

        if (message.isEmpty()) return;

        String text = title.isEmpty() ? message : title + "\n" + message;

        SessionSource source = buildSource("ha-notify", "Home Assistant", "channel",
            "homeassistant", "Home Assistant", null, "通知");

        MessageEvent event = new MessageEvent();
        event.setSource(source);
        event.setText(text);
        event.setMessageId("ha-notify-" + System.currentTimeMillis());

        handleMessage(event).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    private boolean matchesFilter(String entityId) {
        if (entityFilters.isEmpty()) return true;
        for (String filter : entityFilters) {
            // 简单通配符匹配
            String regex = filter.replace(".", "\\.").replace("*", ".*");
            if (entityId.matches(regex)) return true;
        }
        return false;
    }

    // ========== 消息发送 ==========

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        // 发送到 HA 主要是调用 HA 服务
        // chatId 可以是服务名 (如 "light.turn_on") 或 "notify" (发送持久通知)
        try {
            if ("notify".equals(chatId) || chatId.startsWith("ha-")) {
                return sendNotification(content);
            }

            // 尝试作为服务调用
            return callService(chatId, metadata);
        } catch (Exception e) {
            return Mono.just(SendResult.failure(e.getMessage()));
        }
    }

    /**
     * 发送 HA 持久通知
     */
    private Mono<SendResult> sendNotification(String content) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("title", "Hermes Agent");
            body.put("message", content);

            return apiClient.post()
                .uri("/services/persistent_notification/create")
                .bodyValue(objectMapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> {
                    log.info("HA notification sent");
                    return SendResult.success("ha-notify-" + System.currentTimeMillis());
                })
                .onErrorResume(e -> {
                    log.error("HA notification failed: {}", e.getMessage());
                    return Mono.just(SendResult.failure(e.getMessage()));
                });
        } catch (Exception e) {
            return Mono.just(SendResult.failure(e.getMessage()));
        }
    }

    /**
     * 调用 HA 服务
     */
    private Mono<SendResult> callService(String serviceId, Map<String, Object> metadata) {
        try {
            // serviceId 格式: domain.service (如 light.turn_on)
            String[] parts = serviceId.split("\\.", 2);
            if (parts.length != 2) {
                return Mono.just(SendResult.failure("Invalid service format, expected 'domain.service'"));
            }

            String domain = parts[0];
            String service = parts[1];
            String endpoint = "/services/" + domain + "/" + service;

            String body;
            if (metadata != null && !metadata.isEmpty()) {
                body = objectMapper.writeValueAsString(metadata);
            } else {
                body = "{}";
            }

            return apiClient.post()
                .uri(endpoint)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> {
                    log.info("HA service called: {}.{}", domain, service);
                    return SendResult.success("ha-service-" + System.currentTimeMillis());
                })
                .onErrorResume(e -> {
                    log.error("HA service call failed: {}", e.getMessage());
                    return Mono.just(SendResult.failure(e.getMessage()));
                });
        } catch (Exception e) {
            return Mono.just(SendResult.failure(e.getMessage()));
        }
    }

    // ========== 公开 API ==========

    /**
     * 查询实体状态
     */
    public Mono<JsonNode> getEntityState(String entityId) {
        EntityState cached = entityStates.get(entityId);
        if (cached != null && cached.lastChanged.isAfter(Instant.now().minusSeconds(30))) {
            try {
                return Mono.just(objectMapper.readTree(
                    objectMapper.writeValueAsString(Map.of(
                        "entity_id", cached.entityId,
                        "state", cached.state,
                        "attributes", cached.attributes
                    ))
                ));
            } catch (Exception e) { /* fall through */ }
        }

        return apiClient.get()
            .uri("/states/" + entityId)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::parseJson)
            .onErrorResume(e -> Mono.just(objectMapper.createObjectNode()));
    }

    /**
     * 调用服务（WebSocket 方式）
     */
    public Mono<JsonNode> callServiceViaWs(String domain, String service, Map<String, Object> serviceData) {
        // 需要 WebSocket session 引用才能实现
        // 这里简化为 HTTP API 调用
        return callService(domain + "." + service, serviceData)
            .map(result -> {
                if (result.isSuccess()) {
                    return objectMapper.createObjectNode().put("status", "ok");
                } else {
                    return objectMapper.createObjectNode().put("error", result.getError());
                }
            });
    }

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        return Mono.just(Map.of(
            "id", chatId,
            "type", "homeassistant",
            "url", haUrl
        ));
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
