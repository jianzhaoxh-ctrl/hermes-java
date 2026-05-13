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
 * Signal 适配器
 *
 * 参考Python版 signal.py 实现。支持：
 * - signal-cli REST API 作为后端
 * - 通过 signal-cli 发送/接收 Signal 消息
 * - 群组消息
 * - 附件处理
 * - 自动接收消息（WebSocket 长轮询）
 *
 * 依赖：signal-cli (https://github.com/AsamK/signal-cli) 运行在 REST 模式
 *
 * 配置 (config.yaml):
 * <pre>
 * gateway:
 *   platforms:
 *     signal:
 *       transport: rest
 *       extra:
 *         signal_cli_url: "http://localhost:8080"
 *         phone_number: "+1234567890"
 *         accept_everything: true
 *         group_as_chat: true
 *         auto_receive: true
 * </pre>
 */
public class SignalAdapter extends BasePlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(SignalAdapter.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String signalCliUrl;
    private final String phoneNumber;
    private final boolean acceptEverything;
    private final boolean groupAsChat;
    private final boolean autoReceive;

    // 群组名称缓存
    private final Map<String, String> groupNames = new ConcurrentHashMap<>();

    public SignalAdapter(PlatformConfig config) {
        super(config, Platform.SIGNAL);
        this.signalCliUrl = config.getExtraString("signal_cli_url", "http://localhost:8080");
        this.phoneNumber = config.getExtraString("phone_number", "");
        this.acceptEverything = config.getExtraBoolean("accept_everything", true);
        this.groupAsChat = config.getExtraBoolean("group_as_chat", true);
        this.autoReceive = config.getExtraBoolean("auto_receive", true);
    }

    // ========== 生命周期 ==========

    @Override
    public Mono<Boolean> connect() {
        if (phoneNumber.isEmpty()) {
            setFatalError("CONFIG_MISSING", "Signal phone_number not configured", false);
            return Mono.just(false);
        }

        return checkSignalCli()
            .flatMap(available -> {
                if (!available) {
                    setFatalError("CLI_UNAVAILABLE", "signal-cli not reachable at " + signalCliUrl, true);
                    return Mono.just(false);
                }
                // 注册账号（如果需要）
                return isRegistered()
                    .map(registered -> {
                        if (registered) {
                            markConnected();
                            log.info("Signal adapter connected ({})", phoneNumber);
                            if (autoReceive) {
                                startReceiving();
                            }
                            return true;
                        } else {
                            setFatalError("NOT_REGISTERED", "Signal number not registered. Run signal-cli register first.", false);
                            return false;
                        }
                    });
            })
            .onErrorResume(e -> {
                log.error("Signal connect failed: {}", e.getMessage());
                setFatalError("CONNECT_FAILED", e.getMessage(), true);
                return Mono.just(false);
            });
    }

    @Override
    public Mono<Void> disconnect() {
        markDisconnected();
        log.info("Signal adapter disconnected");
        return Mono.empty();
    }

    // ========== 消息接收 ==========

    /**
     * 启动消息接收循环（长轮询）
     */
    private void startReceiving() {
        // 在后台线程中持续轮询 signal-cli
        Thread receiverThread = new Thread(() -> {
            while (running) {
                try {
                    receiveMessages().block();
                } catch (Exception e) {
                    if (running) {
                        log.warn("Signal receive error: {}", e.getMessage());
                    }
                }
                try {
                    Thread.sleep(1000); // 1秒间隔
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "signal-receiver");
        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    private Mono<Void> receiveMessages() {
        return Mono.fromCallable(() -> {
            String url = signalCliUrl + "/v1/receive/" + phoneNumber;
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && !response.body().isEmpty()) {
                parseAndDispatchMessages(response.body());
            }
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void parseAndDispatchMessages(String body) {
        try {
            JsonNode envelopes = objectMapper.readTree(body);
            if (!envelopes.isArray()) return;

            for (JsonNode envelope : envelopes) {
                JsonNode dataMessage = envelope.path("dataMessage");
                if (dataMessage.isMissingNode() || dataMessage.isNull()) continue;

                String source = envelope.path("source").asText("");
                String sourceNumber = envelope.path("sourceNumber").asText(source);
                String sourceName = envelope.path("sourceName").asText(sourceNumber);
                String timestamp = dataMessage.path("timestamp").asText("");
                String messageText = dataMessage.path("message").asText("");
                String groupId = dataMessage.path("groupInfo").path("groupId").asText("");

                // 群组消息
                String chatId;
                String chatType;
                String chatName;

                if (!groupId.isEmpty() && groupAsChat) {
                    chatId = groupId;
                    chatType = "group";
                    chatName = groupNames.computeIfAbsent(groupId,
                        k -> dataMessage.path("groupInfo").path("name").asText("Group " + groupId.substring(0, 8)));
                } else {
                    chatId = sourceNumber;
                    chatType = "dm";
                    chatName = sourceName;
                }

                if (messageText.isEmpty()) {
                    // 可能是纯附件消息
                    JsonNode attachments = dataMessage.path("attachments");
                    if (attachments.isArray() && !attachments.isEmpty()) {
                        messageText = "[Attachment]";
                    } else {
                        continue;
                    }
                }

                SessionSource sessionSource = buildSource(
                    chatId, chatName, chatType, sourceNumber, sourceName,
                    groupId.isEmpty() ? null : groupId, null
                );

                MessageEvent event = new MessageEvent();
                event.setText(messageText);
                event.setMessageId(timestamp);
                event.setSource(sessionSource);

                // 附件处理
                JsonNode attachments = dataMessage.path("attachments");
                if (attachments.isArray()) {
                    List<String> mediaUrls = new ArrayList<>();
                    for (JsonNode att : attachments) {
                        String attPath = att.path("id").asText("");
                        if (!attPath.isEmpty()) {
                            mediaUrls.add(attPath);
                        }
                    }
                    if (!mediaUrls.isEmpty()) {
                        event.setMediaUrls(mediaUrls);
                    }
                }

                handleMessage(event).subscribe();
            }
        } catch (Exception e) {
            log.error("Failed to parse Signal messages: {}", e.getMessage());
        }
    }

    // ========== 消息发送 ==========

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        if (!isConnected()) {
            return Mono.just(SendResult.failure("Not connected"));
        }

        // Signal 消息长度限制约 65536
        List<String> chunks = truncateMessage(content, 65536);

        return Mono.fromCallable(() -> {
            // 判断是群组还是个人
            String endpoint;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", chunks.get(0));

            if (chatId.endsWith("=") || chatId.length() > 20) {
                // 看起来是群组 ID（Base64 编码）
                endpoint = signalCliUrl + "/v1/groups/" + phoneNumber;
                body.put("groupId", chatId);
            } else {
                endpoint = signalCliUrl + "/v1/messages/" + phoneNumber + "/" + chatId;
            }

            String jsonBody = objectMapper.writeValueAsString(body);

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().build();
            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return SendResult.success(String.valueOf(System.currentTimeMillis()));
            } else {
                return SendResult.failure("Signal API error " + response.statusCode() + ": " + response.body());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<SendResult> sendImage(String chatId, String imageUrl, String caption,
                                       String replyTo, Map<String, Object> metadata) {
        // signal-cli 支持附件发送
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", caption != null ? caption : "");
        body.put("base64_attachments", List.of());

        // TODO: 下载图片并转 base64 附件

        return send(chatId, caption != null ? caption : "[Image: " + imageUrl + "]", replyTo, metadata);
    }

    // ========== API 调用 ==========

    private Mono<Boolean> checkSignalCli() {
        return Mono.fromCallable(() -> {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(signalCliUrl + "/v1/about"))
                .GET()
                .timeout(java.time.Duration.ofSeconds(5))
                .build();

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().build();
            try {
                java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Boolean> isRegistered() {
        return Mono.fromCallable(() -> {
            String url = signalCliUrl + "/v1/accounts/" + phoneNumber;
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().build();
            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

            return response.statusCode() == 200;
        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorReturn(false);
    }

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("platform", "signal");

        if (chatId.endsWith("=") || chatId.length() > 20) {
            info.put("type", "group");
            info.put("name", groupNames.getOrDefault(chatId, "Unknown Group"));
        } else {
            info.put("type", "dm");
            info.put("phone", chatId);
        }

        return Mono.just(info);
    }

    @Override
    public String formatMessage(String content) {
        // Signal 支持 **bold**, *italic*, ~strikethrough~, ||spoiler||
        return content;
    }
}
