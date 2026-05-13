package com.hermes.agent.gateway.adapters;

import com.hermes.agent.gateway.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 企业微信（WeCom）平台适配器
 * 
 * 使用企业微信 AI 机器人 WebSocket 网关收发消息。
 * 
 * 核心流程：
 * - aibot_subscribe 认证
 * - aibot_msg_callback 接收入站消息
 * - aibot_send_msg 发送出站 Markdown 消息
 * - aibot_upload_media_* 上传出站媒体
 * 
 * 配置示例（config.yaml）：
 * <pre>
 * platforms:
 *   wecom:
 *     enabled: true
 *     extra:
 *       bot_id: "your-bot-id"
 *       secret: "your-secret"
 *       websocket_url: "wss://openws.work.weixin.qq.com"
 *       dm_policy: "open"           # open | allowlist | disabled | pairing
 *       allow_from: ["user_id_1"]
 *       group_policy: "open"
 * </pre>
 */
public class WeComAdapter extends BasePlatformAdapter {
    private static final Logger log = LoggerFactory.getLogger(WeComAdapter.class);

    private static final String DEFAULT_WS_URL = "wss://openws.work.weixin.qq.com";

    // WebSocket 命令
    private static final String CMD_SUBSCRIBE = "aibot_subscribe";
    private static final String CMD_CALLBACK = "aibot_msg_callback";
    private static final String CMD_SEND = "aibot_send_msg";
    private static final String CMD_PING = "ping";
    private static final String CMD_UPLOAD_INIT = "aibot_upload_media_init";
    private static final String CMD_UPLOAD_CHUNK = "aibot_upload_media_chunk";
    private static final String CMD_UPLOAD_FINISH = "aibot_upload_media_finish";

    private static final int MAX_MESSAGE_LENGTH = 4000;
    private static final double CONNECT_TIMEOUT = 20.0;
    private static final double HEARTBEAT_INTERVAL = 30.0;
    private static final int[] RECONNECT_BACKOFF = {2, 5, 10, 30, 60};

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String botId;
    private final String secret;
    private final String websocketUrl;
    private final String dmPolicy;
    private final String groupPolicy;
    private final Set<String> dmAllowFrom;
    private final Set<String> groupAllowFrom;

    // WebSocket 连接
    private final AtomicReference<WebSocketSession> wsSession = new AtomicReference<>();
    private final StandardWebSocketClient wsClient = new StandardWebSocketClient();
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledExecutorService reconnectExecutor;
    private volatile int reconnectAttempts = 0;
    private volatile boolean intentionalDisconnect = false;

    // 消息去重
    private final LinkedHashMap<String, Boolean> dedupCache = new LinkedHashMap<>(1000, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 1000;
        }
    };

    public WeComAdapter(PlatformConfig config) {
        super(config, Platform.WECOM);
        this.botId = config.getExtraString("bot_id")
            .orElse(System.getenv("WECOM_BOT_ID"));
        this.secret = config.getExtraString("secret")
            .orElse(System.getenv("WECOM_SECRET"));
        this.websocketUrl = config.getExtraString("websocket_url")
            .orElse(DEFAULT_WS_URL);
        this.dmPolicy = config.getExtraString("dm_policy")
            .orElse("open");
        this.groupPolicy = config.getExtraString("group_policy")
            .orElse("open");
        this.dmAllowFrom = new HashSet<>(config.getExtraStringList("allow_from"));
        this.groupAllowFrom = new HashSet<>(config.getExtraStringList("group_allow_from"));

        this.webClient = WebClient.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    }

    @Override
    public Mono<Boolean> connect() {
        if (botId == null || botId.isBlank() || secret == null || secret.isBlank()) {
            setFatalError("missing_credentials",
                "WeCom bot_id and secret not configured", false);
            return Mono.just(false);
        }

        return connectWebSocket()
            .map(success -> {
                if (success) {
                    markConnected();
                    reconnectAttempts = 0;
                    startHeartbeat();
                    log.info("WeCom connected for bot: {}", botId);
                    return true;
                }
                setFatalError("ws_connect_failed", "WebSocket connection failed", true);
                return false;
            })
            .onErrorResume(e -> {
                log.error("WeCom connection failed: {}", e.getMessage());
                setFatalError("connection_failed", e.getMessage(), true);
                scheduleReconnect();
                return Mono.just(false);
            });
    }

    @Override
    public Mono<Void> disconnect() {
        intentionalDisconnect = true;
        stopHeartbeat();

        WebSocketSession session = wsSession.getAndSet(null);
        if (session != null && session.isOpen()) {
            try {
                session.close(CloseStatus.NORMAL);
            } catch (IOException e) {
                log.warn("Error closing WeCom WebSocket: {}", e.getMessage());
            }
        }

        markDisconnected();
        log.info("WeCom disconnected");
        return Mono.empty();
    }

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo,
                                  Map<String, Object> metadata) {
        WebSocketSession session = wsSession.get();
        if (session == null || !session.isOpen()) {
            return Mono.just(SendResult.failure("WebSocket not connected", true));
        }

        // 截断超长消息
        String sendContent = content.length() > MAX_MESSAGE_LENGTH
            ? content.substring(0, MAX_MESSAGE_LENGTH - 20) + "\n\n... (truncated)"
            : content;

        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("cmd", CMD_SEND);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("bot_id", botId);
            body.put("chat_id", chatId);
            body.put("msg_type", "markdown");
            body.put("content", sendContent);
            if (replyTo != null) {
                body.put("quote_id", replyTo);
            }

            msg.put("body", body);

            String json = objectMapper.writeValueAsString(msg);
            session.sendMessage(new TextMessage(json));

            return Mono.just(SendResult.success("wecom_sent"));
        } catch (Exception e) {
            log.error("Failed to send WeCom message: {}", e.getMessage());
            return Mono.just(SendResult.failure(e.getMessage(), true));
        }
    }

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        // 企业微信不提供直接获取聊天信息的 API
        return Mono.just(Map.of("id", chatId, "type", "unknown"));
    }

    @Override
    public String formatMessage(String content) {
        // 企业微信支持 Markdown，但有限制
        // 不支持 HTML 标签
        return content;
    }

    // ========== WebSocket 连接管理 ==========

    private Mono<Boolean> connectWebSocket() {
        return Mono.fromCallable(() -> {
            try {
                WebSocketSession session = wsClient.doHandshake(
                    new WeComWebSocketHandler(),
                    websocketUrl
                ).get((long) (CONNECT_TIMEOUT * 1000), TimeUnit.MILLISECONDS);

                wsSession.set(session);

                // 发送订阅命令
                sendSubscribe(session);
                return true;
            } catch (Exception e) {
                throw new RuntimeException("WebSocket connection failed", e);
            }
        });
    }

    private void sendSubscribe(WebSocketSession session) throws IOException {
        Map<String, Object> subscribe = new LinkedHashMap<>();
        subscribe.put("cmd", CMD_SUBSCRIBE);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bot_id", botId);
        body.put("secret", secret);
        subscribe.put("body", body);

        String json = objectMapper.writeValueAsString(subscribe);
        session.sendMessage(new TextMessage(json));
        log.debug("Sent WeCom subscribe command");
    }

    private void startHeartbeat() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wecom-heartbeat");
            t.setDaemon(true);
            return t;
        });

        heartbeatExecutor.scheduleAtFixedRate(() -> {
            WebSocketSession session = wsSession.get();
            if (session != null && session.isOpen()) {
                try {
                    Map<String, Object> ping = Map.of("cmd", CMD_PING);
                    String json = objectMapper.writeValueAsString(ping);
                    session.sendMessage(new TextMessage(json));
                } catch (Exception e) {
                    log.warn("WeCom heartbeat failed: {}", e.getMessage());
                }
            }
        }, (long) HEARTBEAT_INTERVAL, (long) HEARTBEAT_INTERVAL, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    private void scheduleReconnect() {
        if (intentionalDisconnect) return;

        if (reconnectExecutor != null) {
            reconnectExecutor.shutdownNow();
        }
        reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wecom-reconnect");
            t.setDaemon(true);
            return t;
        });

        int backoffIndex = Math.min(reconnectAttempts, RECONNECT_BACKOFF.length - 1);
        int delay = RECONNECT_BACKOFF[backoffIndex];
        reconnectAttempts++;

        log.info("Scheduling WeCom reconnect in {}s (attempt {})", delay, reconnectAttempts);

        reconnectExecutor.schedule(() -> {
            if (!intentionalDisconnect) {
                connect().subscribe(success -> {
                    if (!success) {
                        scheduleReconnect();
                    }
                });
            }
        }, delay, TimeUnit.SECONDS);
    }

    // ========== 消息处理 ==========

    private void handleIncomingMessage(String payload) {
        try {
            JsonNode json = objectMapper.readTree(payload);
            String cmd = json.has("cmd") ? json.get("cmd").asText() : "";

            if (CMD_CALLBACK.equals(cmd)) {
                JsonNode body = json.get("body");
                if (body != null) {
                    processCallback(body);
                }
            } else if ("aibot_event_callback".equals(cmd)) {
                // 事件回调（如入群、退群等）
                log.debug("WeCom event callback received");
            }
        } catch (Exception e) {
            log.error("Failed to handle WeCom message: {}", e.getMessage());
        }
    }

    private void processCallback(JsonNode body) {
        String msgType = body.has("msg_type") ? body.get("msg_type").asText() : "text";
        String chatId = body.has("chat_id") ? body.get("chat_id").asText() : null;
        String userId = body.has("user_id") ? body.get("user_id").asText() : null;
        String userName = body.has("user_name") ? body.get("user_name").asText() : null;
        String chatType = body.has("chat_type") ? body.get("chat_type").asText() : "dm";
        String msgId = body.has("msg_id") ? body.get("msg_id").asText() : null;

        // 消息去重
        if (msgId != null) {
            synchronized (dedupCache) {
                if (dedupCache.containsKey(msgId)) {
                    log.debug("Skipping duplicate WeCom message: {}", msgId);
                    return;
                }
                dedupCache.put(msgId, true);
            }
        }

        // 权限检查
        if (!checkPermission(chatType, userId, chatId)) {
            log.debug("WeCom message from unauthorized user: {} in {}", userId, chatId);
            return;
        }

        String text = "";
        MessageEvent.MessageType messageType = MessageEvent.MessageType.TEXT;

        switch (msgType) {
            case "text":
                text = body.has("content") ? body.get("content").asText() : "";
                break;
            case "image":
                messageType = MessageEvent.MessageType.PHOTO;
                text = "[Image]";
                break;
            case "voice":
                messageType = MessageEvent.MessageType.VOICE;
                text = "[Voice message]";
                break;
            case "file":
                messageType = MessageEvent.MessageType.DOCUMENT;
                text = "[File]";
                break;
            default:
                text = body.has("content") ? body.get("content").asText() : "";
        }

        SessionSource source = buildSource(chatId, null, chatType, userId, userName, null, null);

        MessageEvent event = new MessageEvent();
        event.setSource(source);
        event.setText(text);
        event.setMessageType(messageType);
        event.setMessageId(msgId);

        handleMessage(event).subscribe();
    }

    private boolean checkPermission(String chatType, String userId, String chatId) {
        if ("dm".equals(chatType)) {
            return switch (dmPolicy) {
                case "open" -> true;
                case "allowlist" -> dmAllowFrom.contains(userId);
                case "disabled" -> false;
                case "pairing" -> {
                    // TODO: 实现 pairing 检查
                    yield true;
                }
                default -> true;
            };
        } else {
            return switch (groupPolicy) {
                case "open" -> true;
                case "allowlist" -> groupAllowFrom.contains(chatId);
                case "disabled" -> false;
                default -> true;
            };
        }
    }

    // ========== WebSocket 处理器 ==========

    private class WeComWebSocketHandler implements WebSocketHandler {
        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            log.info("WeCom WebSocket connection established");
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
            if (message instanceof TextMessage textMessage) {
                handleIncomingMessage(textMessage.getPayload());
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.error("WeCom WebSocket transport error: {}", exception.getMessage());
            if (!intentionalDisconnect) {
                scheduleReconnect();
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
            log.info("WeCom WebSocket closed: {}", closeStatus);
            if (!intentionalDisconnect) {
                scheduleReconnect();
            }
        }

        @Override
        public boolean supportsPartialMessages() {
            return false;
        }
    }
}
