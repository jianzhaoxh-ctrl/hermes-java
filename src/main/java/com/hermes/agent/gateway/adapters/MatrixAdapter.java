package com.hermes.agent.gateway.adapters;

import com.hermes.agent.gateway.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Matrix 协议适配器
 *
 * 参考Python版 matrix.py 实现。支持：
 * - Matrix Client-Server API (v1.x)
 * - 长轮询 /sync 接收消息
 * - 房间消息收发
 * - Markdown 格式消息
 * - 媒体上传/下载
 * - 加密房间（需 pantalaimon 代理）
 * - 多房间管理
 *
 * 配置 (config.yaml):
 * <pre>
 * gateway:
 *   platforms:
 *     matrix:
 *       transport: sync
 *       extra:
 *         homeserver_url: "https://matrix.org"
 *         user_id: "@bot:matrix.org"
 *         access_token: "syt_xxx_xxx"
 *         or password: "your-password"
 *         allowed_rooms: ["!roomid:matrix.org"]
 *         sync_timeout: 30000
 *         encryption: false
 *         pantalaimon_url: ""
 * </pre>
 */
public class MatrixAdapter extends BasePlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(MatrixAdapter.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String homeserverUrl;
    private final String userId;
    private final String accessToken;
    private final String password;
    private final Set<String> allowedRooms;
    private final int syncTimeout;
    private final boolean encryption;
    private final String pantalaimonUrl;

    // 同步状态
    private volatile String sinceToken = null;
    private volatile Thread syncThread = null;

    // 房间名称缓存
    private final Map<String, String> roomNames = new ConcurrentHashMap<>();
    // 房间成员缓存
    private final Map<String, Set<String>> roomMembers = new ConcurrentHashMap<>();

    public MatrixAdapter(PlatformConfig config) {
        super(config, Platform.MATRIX);
        this.homeserverUrl = config.getExtraString("homeserver_url", "https://matrix.org");
        this.userId = config.getExtraString("user_id", "");
        this.accessToken = config.getExtraString("access_token", "");
        this.password = config.getExtraString("password", "");
        this.allowedRooms = new HashSet<>(config.getExtraStringList("allowed_rooms"));
        this.syncTimeout = config.getExtraInt("sync_timeout", 30000);
        this.encryption = config.getExtraBoolean("encryption", false);
        this.pantalaimonUrl = config.getExtraString("pantalaimon_url", "");
    }

    private String getBaseUrl() {
        return encryption && !pantalaimonUrl.isEmpty() ? pantalaimonUrl : homeserverUrl;
    }

    // ========== 生命周期 ==========

    @Override
    public Mono<Boolean> connect() {
        if (userId.isEmpty()) {
            setFatalError("CONFIG_MISSING", "Matrix user_id not configured", false);
            return Mono.just(false);
        }

        Mono<String> tokenMono;
        if (!accessToken.isEmpty()) {
            tokenMono = Mono.just(accessToken);
        } else if (!password.isEmpty()) {
            tokenMono = loginWithPassword();
        } else {
            setFatalError("CONFIG_MISSING", "Matrix access_token or password required", false);
            return Mono.just(false);
        }

        return tokenMono.flatMap(token -> {
            return whoAmI(token)
                .map(valid -> {
                    if (valid) {
                        markConnected();
                        log.info("Matrix adapter connected as {}", userId);
                        startSync();
                        return true;
                    } else {
                        setFatalError("AUTH_FAILED", "Matrix access token invalid", true);
                        return false;
                    }
                });
        }).onErrorResume(e -> {
            log.error("Matrix connect failed: {}", e.getMessage());
            setFatalError("CONNECT_FAILED", e.getMessage(), true);
            return Mono.just(false);
        });
    }

    @Override
    public Mono<Void> disconnect() {
        if (syncThread != null) {
            syncThread.interrupt();
            syncThread = null;
        }
        markDisconnected();
        log.info("Matrix adapter disconnected");
        return Mono.empty();
    }

    // ========== 同步消息 ==========

    private void startSync() {
        syncThread = new Thread(() -> {
            while (running) {
                try {
                    syncOnce().block();
                } catch (Exception e) {
                    if (running) {
                        log.warn("Matrix sync error: {}", e.getMessage());
                        try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
                    }
                }
            }
        }, "matrix-sync");
        syncThread.setDaemon(true);
        syncThread.start();
    }

    private Mono<Void> syncOnce() {
        return Mono.fromCallable(() -> {
            StringBuilder url = new StringBuilder(getBaseUrl());
            url.append("/_matrix/client/v3/sync?");
            url.append("timeout=").append(syncTimeout);
            url.append("&filter=").append(URLEncoder.encode(
                "{\"room\":{\"timeline\":{\"limit\":50}},\"presence\":{\"not_types\":[\"*\"]}}",
                StandardCharsets.UTF_8));
            if (sinceToken != null) {
                url.append("&since=").append(sinceToken);
            }

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode data = objectMapper.readTree(response.body());
                sinceToken = data.path("next_batch").asText(null);

                // 处理房间事件
                JsonNode rooms = data.path("rooms").path("join");
                if (rooms.isObject()) {
                    rooms.fieldNames().forEachRemaining(roomId -> {
                        JsonNode roomData = rooms.path(roomId);
                        processRoomEvents(roomId, roomData);
                    });
                }
            } else {
                log.warn("Matrix sync returned {}: {}", response.statusCode(), response.body());
            }
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void processRoomEvents(String roomId, JsonNode roomData) {
        // 更新房间名称
        JsonNode state = roomData.path("state").path("events");
        if (state.isArray()) {
            for (JsonNode event : state) {
                if ("m.room.name".equals(event.path("type").asText())) {
                    String name = event.path("content").path("name").asText("");
                    if (!name.isEmpty()) {
                        roomNames.put(roomId, name);
                    }
                }
            }
        }

        // 处理时间线消息
        JsonNode timeline = roomData.path("timeline").path("events");
        if (timeline.isArray()) {
            for (JsonNode event : timeline) {
                String type = event.path("type").asText("");
                String sender = event.path("sender").asText("");
                String eventId = event.path("event_id").asText("");

                // 忽略自己的消息
                if (sender.equals(userId)) continue;

                if ("m.room.message".equals(type)) {
                    String msgType = event.path("content").path("msgtype").asText("m.text");
                    String body = event.path("content").path("body").asText("");

                    // 检查房间是否在允许列表
                    if (!allowedRooms.isEmpty() && !allowedRooms.contains(roomId)) {
                        continue;
                    }

                    // 构建消息事件
                    String roomName = roomNames.getOrDefault(roomId, roomId);
                    SessionSource source = buildSource(
                        roomId, roomName, "group", sender, sender, null, null
                    );

                    MessageEvent event2 = new MessageEvent();
                    event2.setText(body);
                    event2.setMessageId(eventId);
                    event2.setSource(source);

                    // 媒体类型映射
                    switch (msgType) {
                        case "m.image" -> event2.setMessageType(MessageEvent.MessageType.PHOTO);
                        case "m.video" -> event2.setMessageType(MessageEvent.MessageType.VIDEO);
                        case "m.audio" -> event2.setMessageType(MessageEvent.MessageType.AUDIO);
                        case "m.file" -> event2.setMessageType(MessageEvent.MessageType.DOCUMENT);
                        default -> event2.setMessageType(MessageEvent.MessageType.TEXT);
                    }

                    if (!msgType.equals("m.text")) {
                        String mxcUrl = event.path("content").path("url").asText("");
                        if (!mxcUrl.isEmpty()) {
                            event2.setMediaUrls(List.of(mxcUrl));
                        }
                    }

                    handleMessage(event2).subscribe();
                }
            }
        }
    }

    // ========== 消息发送 ==========

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        if (!isConnected()) {
            return Mono.just(SendResult.failure("Not connected"));
        }

        // Matrix 消息长度限制约 65536
        List<String> chunks = truncateMessage(content, 65536);

        List<Mono<SendResult>> results = new ArrayList<>();
        for (String chunk : chunks) {
            results.add(sendChunk(chatId, chunk, replyTo));
        }

        // 返回最后一个发送结果
        return reactor.core.publisher.Flux.concat(results)
            .last(SendResult.failure("No chunks sent"));
    }

    private Mono<SendResult> sendChunk(String roomId, String content, String replyTo) {
        return Mono.fromCallable(() -> {
            String txnId = "txn_" + System.currentTimeMillis();
            String url = getBaseUrl() + "/_matrix/client/v3/rooms/" +
                URLEncoder.encode(roomId, StandardCharsets.UTF_8) +
                "/send/m.room.message/" + txnId;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("msgtype", "m.text");
            body.put("body", content);

            // 如果内容包含 Markdown，添加 formatted_body
            if (content.contains("**") || content.contains("```") || content.contains("- ")) {
                Map<String, Object> formattedBody = new LinkedHashMap<>();
                formattedBody.put("format", "org.matrix.custom.html");
                formattedBody.put("formatted_body", markdownToHtml(content));
                body.putAll(formattedBody);
            }

            // 回复消息
            if (replyTo != null && !replyTo.isEmpty()) {
                Map<String, Object> relatesTo = new LinkedHashMap<>();
                relatesTo.put("m.in_reply_to", Map.of("event_id", replyTo));
                body.put("m.relates_to", relatesTo);
            }

            String jsonBody = objectMapper.writeValueAsString(body);

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().build();
            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode resp = objectMapper.readTree(response.body());
                String eventId = resp.path("event_id").asText("");
                return SendResult.success(eventId);
            } else {
                return SendResult.failure("Matrix send error " + response.statusCode());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<SendResult> sendImage(String chatId, String imageUrl, String caption,
                                       String replyTo, Map<String, Object> metadata) {
        // Matrix 需要先上传媒体再发送 m.image 事件
        // 简化：发送为文本消息包含图片链接
        return send(chatId, (caption != null ? caption + "\n" : "") + "🖼️ " + imageUrl, replyTo, metadata);
    }

    // ========== 认证 ==========

    private Mono<String> loginWithPassword() {
        return Mono.fromCallable(() -> {
            String url = getBaseUrl() + "/_matrix/client/v3/login";
            Map<String, Object> body = Map.of(
                "type", "m.login.password",
                "identifier", Map.of("type", "m.id.user", "user", userId),
                "password", password
            );

            String jsonBody = objectMapper.writeValueAsString(body);
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().build();
            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode data = objectMapper.readTree(response.body());
                return data.path("access_token").asText("");
            }
            throw new RuntimeException("Login failed: " + response.body());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Boolean> whoAmI(String token) {
        return Mono.fromCallable(() -> {
            String url = getBaseUrl() + "/_matrix/client/v3/account/whoami";
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().build();
            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode data = objectMapper.readTree(response.body());
                return userId.equals(data.path("user_id").asText(""));
            }
            return false;
        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorReturn(false);
    }

    // ========== 辅助 ==========

    private String markdownToHtml(String markdown) {
        // 简化的 Markdown → HTML 转换
        return markdown
            .replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>")
            .replaceAll("\\*(.+?)\\*", "<em>$1</em>")
            .replaceAll("```(\\w*)\\n([\\s\\S]*?)```", "<pre><code>$2</code></pre>")
            .replaceAll("`(.+?)`", "<code>$1</code>")
            .replaceAll("\n", "<br>");
    }

    /**
     * 将 MXC URI 转换为 HTTP 下载 URL
     */
    public String mxcToHttp(String mxcUrl) {
        if (mxcUrl == null || !mxcUrl.startsWith("mxc://")) return mxcUrl;
        // mxc://server/mediaId → https://server/_matrix/media/v3/download/server/mediaId
        String stripped = mxcUrl.substring(6); // server/mediaId
        int slash = stripped.indexOf('/');
        if (slash < 0) return mxcUrl;
        String server = stripped.substring(0, slash);
        String mediaId = stripped.substring(slash + 1);
        return getBaseUrl() + "/_matrix/media/v3/download/" + server + "/" + mediaId;
    }

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("platform", "matrix");
        info.put("room_id", chatId);
        info.put("name", roomNames.getOrDefault(chatId, chatId));
        info.put("type", "group");
        return Mono.just(info);
    }

    @Override
    public String formatMessage(String content) {
        // Matrix 原生支持 Markdown
        return content;
    }
}
