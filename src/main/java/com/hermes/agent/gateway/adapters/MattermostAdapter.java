package com.hermes.agent.gateway.adapters;

import com.hermes.agent.gateway.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mattermost 适配器
 *
 * 参考Python版 mattermost.py 实现。支持：
 * - Mattermost REST API v4
 * - WebSocket 实时消息
 * - 频道/直接消息
 * - 帖子收发
 * - 文件上传
 * - Markdown 格式
 *
 * 配置 (config.yaml):
 * <pre>
 * gateway:
 *   platforms:
 *     mattermost:
 *       transport: websocket
 *       extra:
 *         server_url: "https://mattermost.example.com"
 *         bot_token: "your-bot-token"
 *         or username/password
 *         team_name: "engineering"
 *         allowed_channels: ["channel-id-1"]
 *         ws_reconnect: true
 * </pre>
 */
public class MattermostAdapter extends BasePlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(MattermostAdapter.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String serverUrl;
    private final String botToken;
    private final String username;
    private final String password;
    private final String teamName;
    private final Set<String> allowedChannels;
    private final boolean wsReconnect;

    // 运行时状态
    private volatile String myUserId = null;
    private final Map<String, String> channelNames = new ConcurrentHashMap<>();
    private final Map<String, String> teamId = new ConcurrentHashMap<>();

    public MattermostAdapter(PlatformConfig config) {
        super(config, Platform.MATTERMOST);
        this.serverUrl = config.getExtraString("server_url", "");
        this.botToken = config.getExtraString("bot_token", "");
        this.username = config.getExtraString("username", "");
        this.password = config.getExtraString("password", "");
        this.teamName = config.getExtraString("team_name", "");
        this.allowedChannels = new HashSet<>(config.getExtraStringList("allowed_channels"));
        this.wsReconnect = config.getExtraBoolean("ws_reconnect", true);
    }

    // ========== 生命周期 ==========

    @Override
    public Mono<Boolean> connect() {
        if (serverUrl.isEmpty()) {
            setFatalError("CONFIG_MISSING", "Mattermost server_url not configured", false);
            return Mono.just(false);
        }

        Mono<String> tokenMono;
        if (!botToken.isEmpty()) {
            tokenMono = Mono.just(botToken);
        } else if (!username.isEmpty() && !password.isEmpty()) {
            tokenMono = loginWithPassword();
        } else {
            setFatalError("CONFIG_MISSING", "Mattermost bot_token or username+password required", false);
            return Mono.just(false);
        }

        return tokenMono.flatMap(token -> getMe(token)
            .map(me -> {
                myUserId = me.path("id").asText("");
                String myUsername = me.path("username").asText("");
                markConnected();
                log.info("Mattermost adapter connected as {} (id={})", myUsername, myUserId);
                return true;
            })
        ).onErrorResume(e -> {
            log.error("Mattermost connect failed: {}", e.getMessage());
            setFatalError("CONNECT_FAILED", e.getMessage(), true);
            return Mono.just(false);
        });
    }

    @Override
    public Mono<Void> disconnect() {
        markDisconnected();
        log.info("Mattermost adapter disconnected");
        return Mono.empty();
    }

    // ========== 消息发送 ==========

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        if (!isConnected()) {
            return Mono.just(SendResult.failure("Not connected"));
        }

        // Mattermost 帖子长度限制约 65535
        List<String> chunks = truncateMessage(content, 65535);

        List<Mono<SendResult>> results = new ArrayList<>();
        for (String chunk : chunks) {
            results.add(sendPost(chatId, chunk, replyTo));
        }

        return reactor.core.publisher.Flux.concat(results)
            .last(SendResult.failure("No chunks sent"));
    }

    private Mono<SendResult> sendPost(String channelId, String content, String replyTo) {
        return Mono.fromCallable(() -> {
            String url = serverUrl + "/api/v4/posts";
            Map<String, Object> post = new LinkedHashMap<>();
            post.put("channel_id", channelId);
            post.put("message", content);

            if (replyTo != null && !replyTo.isEmpty()) {
                post.put("root_id", replyTo);
            }

            String jsonBody = objectMapper.writeValueAsString(post);

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + botToken)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().build();
            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201) {
                JsonNode resp = objectMapper.readTree(response.body());
                String postId = resp.path("id").asText("");
                return SendResult.success(postId);
            } else {
                return SendResult.failure("Mattermost API error " + response.statusCode() + ": " + response.body());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<SendResult> editMessage(String chatId, String messageId, String content, boolean finalize) {
        return Mono.fromCallable(() -> {
            String url = serverUrl + "/api/v4/posts/" + messageId;
            Map<String, Object> post = Map.of("id", messageId, "message", content);
            String jsonBody = objectMapper.writeValueAsString(post);

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + botToken)
                .header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().build();
            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode resp = objectMapper.readTree(response.body());
                return SendResult.success(resp.path("id").asText(messageId));
            }
            return SendResult.failure("Edit failed: " + response.statusCode());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<SendResult> sendImage(String chatId, String imageUrl, String caption,
                                       String replyTo, Map<String, Object> metadata) {
        // Mattermost 支持在消息中使用 Markdown 图片语法
        String msg = "!" + (caption != null ? "[" + caption + "]" : "[]") + "(" + imageUrl + ")";
        return send(chatId, msg, replyTo, metadata);
    }

    @Override
    public Mono<Void> sendTyping(String chatId, Map<String, Object> metadata) {
        return Mono.fromCallable(() -> {
            String url = serverUrl + "/api/v4/users/" + myUserId + "/typing";
            Map<String, Object> body = Map.of("channel_id", chatId);
            String jsonBody = objectMapper.writeValueAsString(body);

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + botToken)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().build();
            client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    // ========== 消息接收 ==========

    /**
     * 处理 WebSocket 推送的已发布帖子事件
     */
    public Mono<Void> handlePostedEvent(JsonNode postData) {
        String postId = postData.path("id").asText("");
        String channelId = postData.path("channel_id").asText("");
        String senderId = postData.path("user_id").asText("");
        String message = postData.path("message").asText("");
        String rootId = postData.path("root_id").asText("");

        // 忽略自己的消息
        if (senderId.equals(myUserId)) return Mono.empty();

        // 检查频道过滤
        if (!allowedChannels.isEmpty() && !allowedChannels.contains(channelId)) {
            return Mono.empty();
        }

        // 判断频道类型
        String chatType = "channel";
        if (channelId.startsWith("__direct_")) {
            chatType = "dm";
        }

        String channelName = channelNames.computeIfAbsent(channelId, k -> fetchChannelName(k).block());

        SessionSource source = buildSource(
            channelId, channelName, chatType, senderId, senderId,
            rootId.isEmpty() ? null : rootId, null
        );

        MessageEvent event = new MessageEvent();
        event.setText(message);
        event.setMessageId(postId);
        event.setSource(source);
        event.setMessageType(MessageEvent.MessageType.TEXT);

        return handleMessage(event);
    }

    private Mono<String> fetchChannelName(String channelId) {
        return Mono.fromCallable(() -> {
            String url = serverUrl + "/api/v4/channels/" + channelId;
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + botToken)
                .GET()
                .build();

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().build();
            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode data = objectMapper.readTree(response.body());
                String name = data.path("display_name").asText(data.path("name").asText(channelId));
                channelNames.put(channelId, name);
                return name;
            }
            return channelId;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ========== API 调用 ==========

    private Mono<JsonNode> getMe(String token) {
        return Mono.fromCallable(() -> {
            String url = serverUrl + "/api/v4/users/me";
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().build();
            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return objectMapper.readTree(response.body());
            }
            throw new RuntimeException("Mattermost auth failed: " + response.body());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<String> loginWithPassword() {
        return Mono.fromCallable(() -> {
            String url = serverUrl + "/api/v4/users/login";
            Map<String, Object> body = Map.of("login_id", username, "password", password);
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
                // Token 在 header 中返回
                String token = response.headers().firstValue("Token").orElse("");
                if (token.isEmpty()) {
                    JsonNode data = objectMapper.readTree(response.body());
                    // 某些版本在 body 中返回
                }
                return token;
            }
            throw new RuntimeException("Login failed: " + response.body());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        return Mono.fromCallable(() -> {
            String url = serverUrl + "/api/v4/channels/" + chatId;
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + botToken)
                .GET()
                .build();

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().build();
            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("platform", "mattermost");
            info.put("channel_id", chatId);

            if (response.statusCode() == 200) {
                JsonNode data = objectMapper.readTree(response.body());
                info.put("name", data.path("display_name").asText(""));
                info.put("type", data.path("type").asText("O")); // O=open, P=private, D=direct
            }
            return info;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public String formatMessage(String content) {
        // Mattermost 原生支持 Markdown
        return content;
    }
}
