package com.hermes.agent.gateway.adapters;

import com.hermes.agent.gateway.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.net.http.HttpClient.Version;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WhatsApp Business API 适配器
 *
 * 参考Python版 whatsapp.py 实现。支持：
 * - WhatsApp Cloud API (Meta Business)
 * - Webhook 接收消息
 * - 文本/图片/文档/音频/视频/位置 消息收发
 * - 群组消息
 * - 消息模板
 * - 交互式按钮/列表
 *
 * 配置 (config.yaml):
 * <pre>
 * gateway:
 *   platforms:
 *     whatsapp:
 *       transport: webhook
 *       extra:
 *         verify_token: "your-verify-token"
 *         access_token: "your-access-token"
 *         phone_number_id: "your-phone-number-id"
 *         business_account_id: "your-business-account-id"
 *         webhook_secret: "your-app-secret"
 *         api_version: "v18.0"
 *         allowed_phones: ["1234567890"]
 *         group_enabled: true
 * </pre>
 */
public class WhatsAppAdapter extends BasePlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppAdapter.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 配置参数
    private final String verifyToken;
    private final String accessToken;
    private final String phoneNumberId;
    private final String businessAccountId;
    private final String apiVersion;
    private final String appSecret;
    private final boolean groupEnabled;
    private final Set<String> allowedPhones;

    // 媒体缓存（下载后的本地路径）
    private final Map<String, String> mediaCache = new ConcurrentHashMap<>();

    public WhatsAppAdapter(PlatformConfig config) {
        super(config, Platform.WHATSAPP);
        this.verifyToken = config.getExtraString("verify_token", "");
        this.accessToken = config.getExtraString("access_token", "");
        this.phoneNumberId = config.getExtraString("phone_number_id", "");
        this.businessAccountId = config.getExtraString("business_account_id", "");
        this.apiVersion = config.getExtraString("api_version", "v18.0");
        this.appSecret = config.getExtraString("webhook_secret", "");
        this.groupEnabled = config.getExtraBoolean("group_enabled", false);
        this.allowedPhones = new HashSet<>(config.getExtraStringList("allowed_phones"));
    }

    // ========== 生命周期 ==========

    @Override
    public Mono<Boolean> connect() {
        if (accessToken.isEmpty() || phoneNumberId.isEmpty()) {
            setFatalError("CONFIG_MISSING", "WhatsApp access_token or phone_number_id not configured", false);
            return Mono.just(false);
        }
        // WhatsApp Cloud API 不需要持久连接，只需验证 access token 有效性
        return verifyAccessToken()
            .doOnNext(valid -> {
                if (valid) {
                    markConnected();
                    log.info("WhatsApp adapter connected (phone_number_id={})", phoneNumberId);
                } else {
                    setFatalError("AUTH_FAILED", "WhatsApp access token invalid", true);
                }
            })
            .onErrorResume(e -> {
                log.warn("WhatsApp connect verification failed: {}", e.getMessage());
                setFatalError("CONNECT_FAILED", e.getMessage(), true);
                return Mono.just(false);
            });
    }

    @Override
    public Mono<Void> disconnect() {
        mediaCache.clear();
        markDisconnected();
        log.info("WhatsApp adapter disconnected");
        return Mono.empty();
    }

    // ========== Webhook 验证 ==========

    /**
     * 处理 WhatsApp Webhook 验证请求 (GET)
     */
    public boolean verifyWebhook(String mode, String token, String challenge) {
        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            log.info("WhatsApp webhook verified");
            return true;
        }
        log.warn("WhatsApp webhook verification failed");
        return false;
    }

    /**
     * 处理 WhatsApp Webhook 消息通知 (POST)
     */
    public Mono<Void> handleWebhookNotification(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);

            // 验证签名（如果配置了 appSecret）
            // TODO: HMAC-SHA256 签名验证

            JsonNode entryArray = root.path("entry");
            if (!entryArray.isArray()) return Mono.empty();

            List<Mono<Void>> processing = new ArrayList<>();

            for (JsonNode entry : entryArray) {
                JsonNode changes = entry.path("changes");
                for (JsonNode change : changes) {
                    JsonNode value = change.path("value");
                    JsonNode messages = value.path("messages");
                    JsonNode statuses = value.path("statuses");

                    // 处理入站消息
                    if (messages.isArray()) {
                        for (JsonNode msg : messages) {
                            processing.add(processInboundMessage(msg, value));
                        }
                    }

                    // 处理状态更新（delivered/read）
                    if (statuses.isArray()) {
                        for (JsonNode status : statuses) {
                            processStatusUpdate(status);
                        }
                    }
                }
            }

            return Flux.merge(processing).then();
        } catch (Exception e) {
            log.error("Failed to parse WhatsApp webhook: {}", e.getMessage());
            return Mono.empty();
        }
    }

    // ========== 消息接收 ==========

    private Mono<Void> processInboundMessage(JsonNode msg, JsonNode value) {
        String from = msg.path("from").asText("");       // 发送者手机号
        String msgId = msg.path("id").asText("");
        String timestamp = msg.path("timestamp").asText("");
        String type = msg.path("type").asText("text");

        // 检查是否在允许列表中
        if (!allowedPhones.isEmpty() && !allowedPhones.contains(from)) {
            log.debug("Ignoring message from unallowed phone: {}", from);
            return Mono.empty();
        }

        // 群组消息检查
        String chatId = from;
        String chatType = "dm";
        JsonNode groupInfo = value.path("contacts");  // WhatsApp Cloud API 通过 contacts 获取信息
        // WhatsApp Business API 暂时没有群组 ID 在消息中，需要通过 group metadata

        // 提取发送者名称
        String senderName = from;
        JsonNode contacts = value.path("contacts");
        if (contacts.isArray() && !contacts.isEmpty()) {
            senderName = contacts.get(0).path("profile").path("name").asText(from);
        }

        // 根据消息类型提取内容
        String text = extractMessageContent(msg, type);

        // 构建消息事件
        SessionSource source = buildSource(
            chatId, senderName, chatType, from, senderName, null, null
        );

        MessageEvent event = new MessageEvent();
        event.setText(text);
        event.setMessageId(msgId);
        event.setSource(source);

        // 媒体消息
        if (!type.equals("text") && !type.equals("reaction") && !type.equals("contacts")) {
            String mediaId = extractMediaId(msg, type);
            if (mediaId != null) {
                event.setMessageType(mapMessageType(type));
                event.setMediaUrls(List.of(mediaId));  // WhatsApp 需要先下载再缓存
            }
        }

        return handleMessage(event);
    }

    private String extractMessageContent(JsonNode msg, String type) {
        return switch (type) {
            case "text" -> msg.path("text").path("body").asText("");
            case "image", "video", "audio", "document", "sticker" -> {
                JsonNode mediaNode = msg.path(type);
                String caption = mediaNode.path("caption").asText("");
                String filename = mediaNode.path("filename").asText("");
                yield caption.isEmpty() ? "[" + type + "]" + (filename.isEmpty() ? "" : ": " + filename) : caption;
            }
            case "location" -> {
                JsonNode loc = msg.path("location");
                double lat = loc.path("latitude").asDouble();
                double lng = loc.path("longitude").asDouble();
                String name = loc.path("name").asText("");
                String address = loc.path("address").asText("");
                yield "📍 " + (name.isEmpty() ? lat + ", " + lng : name + " (" + address + ")");
            }
            case "contacts" -> "[Contacts shared]";
            case "reaction" -> {
                String emoji = msg.path("reaction").path("emoji").asText("");
                yield "Reacted: " + emoji;
            }
            case "interactive" -> {
                JsonNode interactive = msg.path("interactive");
                String iType = interactive.path("type").asText("");
                yield switch (iType) {
                    case "button_reply" -> interactive.path("button_reply").path("title").asText("");
                    case "list_reply" -> interactive.path("list_reply").path("title").asText("");
                    default -> "[Interactive: " + iType + "]";
                };
            }
            default -> "[" + type + " message]";
        };
    }

    private String extractMediaId(JsonNode msg, String type) {
        return msg.path(type).path("id").asText(null);
    }

    private MessageEvent.MessageType mapMessageType(String type) {
        return switch (type) {
            case "image" -> MessageEvent.MessageType.PHOTO;
            case "video" -> MessageEvent.MessageType.VIDEO;
            case "audio" -> MessageEvent.MessageType.AUDIO;
            case "document" -> MessageEvent.MessageType.DOCUMENT;
            case "sticker" -> MessageEvent.MessageType.STICKER;
            case "location" -> MessageEvent.MessageType.LOCATION;
            default -> MessageEvent.MessageType.TEXT;
        };
    }

    private void processStatusUpdate(JsonNode status) {
        String id = status.path("id").asText("");
        String statusValue = status.path("status").asText("");
        String recipientId = status.path("recipient_id").asText("");
        log.debug("WhatsApp message {} status: {} (to: {})", id, statusValue, recipientId);
    }

    // ========== 消息发送 ==========

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        if (!isConnected()) {
            return Mono.just(SendResult.failure("Not connected"));
        }

        // WhatsApp 消息长度限制 4096 字符
        List<String> chunks = truncateMessage(content, 4096);

        return Flux.fromIterable(chunks)
            .index()
            .concatMap(tuple -> {
                String chunk = tuple.getT2();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("messaging_product", "whatsapp");
                body.put("recipient_type", "individual");
                body.put("to", chatId);
                body.put("type", "text");
                Map<String, String> textObj = new LinkedHashMap<>();
                textObj.put("preview_url", "true");
                textObj.put("body", chunk);
                body.put("text", textObj);

                if (replyTo != null && !replyTo.isEmpty()) {
                    // WhatsApp 不直接支持 reply_to，需用 context
                    Map<String, String> context = new LinkedHashMap<>();
                    context.put("message_id", replyTo);
                    body.put("context", context);
                }

                return callWhatsAppApi(body)
                    .map(response -> {
                        JsonNode msgNode = response.path("messages");
                        if (msgNode.isArray() && !msgNode.isEmpty()) {
                            String sentId = msgNode.get(0).path("id").asText("");
                            return SendResult.success(sentId);
                        }
                        return SendResult.failure("No message ID in response");
                    });
            })
            .last(SendResult.failure("No chunks to send"));
    }

    @Override
    public Mono<SendResult> sendImage(String chatId, String imageUrl, String caption,
                                       String replyTo, Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", chatId);
        body.put("type", "image");

        Map<String, String> imageObj = new LinkedHashMap<>();
        imageObj.put("link", imageUrl);
        if (caption != null) imageObj.put("caption", caption);
        body.put("image", imageObj);

        return callWhatsAppApi(body)
            .map(this::extractSendResult);
    }

    @Override
    public Mono<SendResult> sendDocument(String chatId, String filePath, String caption,
                                          String fileName, String replyTo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", chatId);
        body.put("type", "document");

        Map<String, String> docObj = new LinkedHashMap<>();
        docObj.put("link", filePath);
        if (fileName != null) docObj.put("filename", fileName);
        if (caption != null) docObj.put("caption", caption);
        body.put("document", docObj);

        return callWhatsAppApi(body)
            .map(this::extractSendResult);
    }

    @Override
    public Mono<SendResult> sendVoice(String chatId, String audioPath, String caption, String replyTo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", chatId);
        body.put("type", "audio");

        Map<String, String> audioObj = new LinkedHashMap<>();
        audioObj.put("link", audioPath);
        body.put("audio", audioObj);

        return callWhatsAppApi(body)
            .map(this::extractSendResult);
    }

    @Override
    public Mono<SendResult> sendVideo(String chatId, String videoPath, String caption, String replyTo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", chatId);
        body.put("type", "video");

        Map<String, String> videoObj = new LinkedHashMap<>();
        videoObj.put("link", videoPath);
        if (caption != null) videoObj.put("caption", caption);
        body.put("video", videoObj);

        return callWhatsAppApi(body)
            .map(this::extractSendResult);
    }

    /**
     * 发送交互式按钮消息
     */
    public Mono<SendResult> sendButtons(String chatId, String bodyText,
                                         List<Map<String, String>> buttons) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", chatId);
        body.put("type", "interactive");

        Map<String, Object> interactive = new LinkedHashMap<>();
        interactive.put("type", "button");

        Map<String, String> bodyObj = new LinkedHashMap<>();
        bodyObj.put("text", bodyText);
        interactive.put("body", bodyObj);

        List<Map<String, Object>> actionButtons = new ArrayList<>();
        for (Map<String, String> btn : buttons) {
            Map<String, Object> button = new LinkedHashMap<>();
            button.put("type", "reply");
            Map<String, String> reply = new LinkedHashMap<>();
            reply.put("id", btn.getOrDefault("id", UUID.randomUUID().toString()));
            reply.put("title", btn.getOrDefault("title", "Button"));
            button.put("reply", reply);
            actionButtons.add(button);
        }
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("buttons", actionButtons);
        interactive.put("action", action);

        body.put("interactive", interactive);

        return callWhatsAppApi(body)
            .map(this::extractSendResult);
    }

    /**
     * 标记消息为已读
     */
    public Mono<Void> markAsRead(String messageId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("status", "read");
        body.put("message_id", messageId);

        return callWhatsAppApi(body).then();
    }

    // ========== API 调用 ==========

    private Mono<JsonNode> callWhatsAppApi(Map<String, Object> payload) {
        return Mono.fromCallable(() -> {
            String url = "https://graph.facebook.com/" + apiVersion + "/" + phoneNumberId + "/messages";
            String jsonBody = objectMapper.writeValueAsString(payload);

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_2)
                .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return objectMapper.readTree(response.body());
            } else {
                throw new RuntimeException("WhatsApp API error " + response.statusCode() + ": " + response.body());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Boolean> verifyAccessToken() {
        return Mono.fromCallable(() -> {
            String url = "https://graph.facebook.com/" + apiVersion + "/" + phoneNumberId +
                "?fields=display_phone_number,verified_name";
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().build();
            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

            return response.statusCode() == 200;
        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorReturn(false);
    }

    private SendResult extractSendResult(JsonNode response) {
        JsonNode msgNode = response.path("messages");
        if (msgNode.isArray() && !msgNode.isEmpty()) {
            String sentId = msgNode.get(0).path("id").asText("");
            return SendResult.success(sentId);
        }
        return SendResult.failure("No message ID in response");
    }

    // ========== 其他 ==========

    /**
     * 下载 WhatsApp 媒体文件
     */
    public Mono<String> downloadMedia(String mediaId) {
        if (mediaCache.containsKey(mediaId)) {
            return Mono.just(mediaCache.get(mediaId));
        }

        return Mono.fromCallable(() -> {
            // 第一步：获取媒体 URL
            String url = "https://graph.facebook.com/" + apiVersion + "/" + mediaId;
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().build();
            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to get media URL: " + response.body());
            }

            JsonNode mediaInfo = objectMapper.readTree(response.body());
            String downloadUrl = mediaInfo.path("url").asText("");
            String mimeType = mediaInfo.path("mime_type").asText("application/octet-stream");

            // 第二步：下载媒体文件
            // TODO: 下载到本地缓存目录
            mediaCache.put(mediaId, downloadUrl);
            return downloadUrl;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        // WhatsApp 不提供聊天信息 API，chatId 就是手机号
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("platform", "whatsapp");
        info.put("phone", chatId);
        info.put("type", "dm");
        return Mono.just(info);
    }

    @Override
    public String formatMessage(String content) {
        // WhatsApp 支持 *bold*, _italic_, ~strikethrough~, ```code```
        // 但不支持标准 markdown，需要转换
        return content
            .replaceAll("\\*\\*(.+?)\\*\\*", "*$1*")   // **bold** → *bold*
            .replaceAll("__(.+?)__", "_$1_")            // __italic__ → _italic_
            .replaceAll("~~(.+?)~~", "~$1~");           // ~~strike~~ → ~strike~
    }
}
