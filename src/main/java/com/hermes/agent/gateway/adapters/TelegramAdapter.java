package com.hermes.agent.gateway.adapters;

import com.hermes.agent.gateway.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Telegram 平台适配器
 * 
 * 使用 Telegram Bot API 实现消息收发。
 * 
 * 特性：
 * - Long polling 获取更新
 * - MarkdownV2 格式支持
 * - 图片/视频/语音原生发送
 * - 消息编辑（流式输出）
 * - UTF-16 长度限制处理
 */
public class TelegramAdapter extends BasePlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(TelegramAdapter.class);
    private static final String API_BASE = "https://api.telegram.org/bot";
    private static final int MAX_MESSAGE_LENGTH = 4096;  // Telegram UTF-16 限制
    private static final Pattern MARKDOWN_SPECIAL = Pattern.compile("([_*\\[\\]()~`>#+\\-=|{}.!\\\\])");

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String botToken;
    private volatile int lastUpdateId = 0;
    private volatile boolean polling = false;

    public TelegramAdapter(PlatformConfig config) {
        super(config, Platform.TELEGRAM);
        this.botToken = config.getToken();
        
        // 配置代理（如果有）
        String proxyUrl = config.getExtraString("proxy_url").orElse(
            System.getenv("TELEGRAM_PROXY")
        );
        
        WebClient.Builder builder = WebClient.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024));
        
        // TODO: 配置代理
        
        this.webClient = builder.build();
    }

    @Override
    public Mono<Boolean> connect() {
        if (botToken == null || botToken.isBlank()) {
            setFatalError("missing_token", "Telegram bot token not configured", false);
            return Mono.just(false);
        }

        return getMe()
            .map(response -> {
                if (response.has("ok") && response.get("ok").asBoolean()) {
                    JsonNode botInfo = response.get("result");
                    String botName = botInfo.has("username") 
                        ? "@" + botInfo.get("username").asText() 
                        : "Bot";
                    log.info("Telegram connected as {}", botName);
                    markConnected();
                    startPolling();
                    return true;
                }
                setFatalError("auth_failed", "Failed to authenticate with Telegram", false);
                return false;
            })
            .onErrorResume(e -> {
                log.error("Failed to connect to Telegram: {}", e.getMessage());
                setFatalError("connection_failed", e.getMessage(), true);
                return Mono.just(false);
            });
    }

    @Override
    public Mono<Void> disconnect() {
        polling = false;
        markDisconnected();
        log.info("Telegram disconnected");
        return Mono.empty();
    }

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        // 处理消息长度限制
        List<String> chunks = truncateMessageUtf16(content, MAX_MESSAGE_LENGTH);
        
        if (chunks.size() == 1) {
            return sendSingleMessage(chatId, chunks.get(0), replyTo, metadata);
        }

        // 多条消息
        String replyToMode = config.getReplyToMode();
        return Flux.fromIterable(chunks)
            .index()
            .flatMap(tuple -> {
                long index = tuple.getT1();
                String chunk = tuple.getT2();
                // 只在 "all" 模式下，所有消息都回复原消息
                // "first" 模式下，只有第一条回复
                String replyToMsg = "all".equals(replyToMode) ? replyTo :
                    (index == 0 ? replyTo : null);
                return sendSingleMessage(chatId, chunk, replyToMsg, metadata);
            })
            .then(Mono.just(SendResult.success("multi")));
    }

    @Override
    public Mono<SendResult> editMessage(String chatId, String messageId, String content, boolean finalize) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", escapeMarkdownV2(content));
        body.put("parse_mode", "MarkdownV2");

        return webClient.post()
            .uri(API_BASE + botToken + "/editMessageText")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .map(response -> {
                try {
                    JsonNode json = objectMapper.readTree(response);
                    if (json.has("ok") && json.get("ok").asBoolean()) {
                        return SendResult.success(messageId);
                    }
                    return SendResult.failure(json.has("description") 
                        ? json.get("description").asText() 
                        : "Edit failed");
                } catch (Exception e) {
                    return SendResult.failure(e.getMessage());
                }
            })
            .onErrorResume(e -> Mono.just(SendResult.failure(e.getMessage(), true)));
    }

    @Override
    public Mono<Void> sendTyping(String chatId, Map<String, Object> metadata) {
        Map<String, Object> body = Map.of("chat_id", chatId, "action", "typing");
        
        return webClient.post()
            .uri(API_BASE + botToken + "/sendChatAction")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .then()
            .onErrorResume(e -> {
                log.debug("Failed to send typing indicator: {}", e.getMessage());
                return Mono.empty();
            });
    }

    @Override
    public Mono<SendResult> sendImage(String chatId, String imageUrl, String caption, String replyTo, Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("photo", imageUrl);
        if (caption != null) {
            body.put("caption", escapeMarkdownV2(caption));
            body.put("parse_mode", "MarkdownV2");
        }
        if (replyTo != null) {
            body.put("reply_to_message_id", Integer.parseInt(replyTo));
        }

        return webClient.post()
            .uri(API_BASE + botToken + "/sendPhoto")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::parseSendResult)
            .onErrorResume(e -> {
                // 图片发送失败，回退为文本
                log.warn("Failed to send image, falling back to text: {}", e.getMessage());
                return send(chatId, imageUrl, replyTo, metadata);
            });
    }

    @Override
    public Mono<SendResult> sendAnimation(String chatId, String animationUrl, String caption, String replyTo, Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("animation", animationUrl);
        if (caption != null) {
            body.put("caption", escapeMarkdownV2(caption));
            body.put("parse_mode", "MarkdownV2");
        }
        if (replyTo != null) {
            body.put("reply_to_message_id", Integer.parseInt(replyTo));
        }

        return webClient.post()
            .uri(API_BASE + botToken + "/sendAnimation")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::parseSendResult)
            .onErrorResume(e -> sendImage(chatId, animationUrl, caption, replyTo, metadata));
    }

    @Override
    public Mono<SendResult> sendVoice(String chatId, String audioPath, String caption, String replyTo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("voice", audioPath);
        if (caption != null) {
            body.put("caption", escapeMarkdownV2(caption));
            body.put("parse_mode", "MarkdownV2");
        }
        if (replyTo != null) {
            body.put("reply_to_message_id", Integer.parseInt(replyTo));
        }

        return webClient.post()
            .uri(API_BASE + botToken + "/sendVoice")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::parseSendResult)
            .onErrorResume(e -> Mono.just(SendResult.failure(e.getMessage())));
    }

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        Map<String, Object> body = Map.of("chat_id", chatId);
        
        return webClient.post()
            .uri(API_BASE + botToken + "/getChat")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .map(response -> {
                try {
                    JsonNode json = objectMapper.readTree(response);
                    if (json.has("ok") && json.get("ok").asBoolean()) {
                        JsonNode result = json.get("result");
                        Map<String, Object> info = new HashMap<>();
                        info.put("id", result.get("id").asText());
                        info.put("type", result.get("type").asText());
                        if (result.has("title")) {
                            info.put("name", result.get("title").asText());
                        } else if (result.has("first_name")) {
                            String name = result.get("first_name").asText();
                            if (result.has("last_name")) {
                                name += " " + result.get("last_name").asText();
                            }
                            info.put("name", name);
                        }
                        return info;
                    }
                    return Map.of("error", "Chat not found");
                } catch (Exception e) {
                    return Map.of("error", e.getMessage());
                }
            });
    }

    @Override
    public String formatMessage(String content) {
        return escapeMarkdownV2(content);
    }

    // ========== 私有方法 ==========

    private Mono<SendResult> sendSingleMessage(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", escapeMarkdownV2(content));
        body.put("parse_mode", "MarkdownV2");

        if (replyTo != null) {
            try {
                body.put("reply_to_message_id", Integer.parseInt(replyTo));
            } catch (NumberFormatException e) {
                log.warn("Invalid reply_to message ID: {}", replyTo);
            }
        }

        // 禁用链接预览（如果配置）
        if (config.getExtraBoolean("disable_link_previews").orElse(false)) {
            body.put("link_preview_options", Map.of("is_disabled", true));
        }

        return webClient.post()
            .uri(API_BASE + botToken + "/sendMessage")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::parseSendResult)
            .onErrorResume(e -> Mono.just(SendResult.failure(e.getMessage(), true)));
    }

    private SendResult parseSendResult(String response) {
        try {
            JsonNode json = objectMapper.readTree(response);
            if (json.has("ok") && json.get("ok").asBoolean()) {
                JsonNode result = json.get("result");
                String messageId = result.has("message_id") 
                    ? String.valueOf(result.get("message_id").asInt()) 
                    : null;
                return SendResult.success(messageId, response);
            }
            String error = json.has("description") 
                ? json.get("description").asText() 
                : "Unknown error";
            return SendResult.failure(error);
        } catch (Exception e) {
            return SendResult.failure(e.getMessage());
        }
    }

    private Mono<JsonNode> getMe() {
        return webClient.get()
            .uri(API_BASE + botToken + "/getMe")
            .retrieve()
            .bodyToMono(String.class)
            .map(response -> {
                try {
                    return objectMapper.readTree(response);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to parse getMe response", e);
                }
            });
    }

    private void startPolling() {
        if (polling) return;
        polling = true;

        Flux.defer(() -> getUpdates(lastUpdateId + 1))
            .repeat()
            .takeWhile(update -> polling)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                updates -> processUpdates(updates),
                error -> log.error("Polling error: {}", error.getMessage()),
                () -> log.info("Polling stopped")
            );
    }

    private Mono<List<JsonNode>> getUpdates(int offset) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("offset", offset);
        body.put("timeout", 30);
        body.put("allowed_updates", List.of("message", "edited_message", "channel_post", "callback_query"));

        return webClient.post()
            .uri(API_BASE + botToken + "/getUpdates")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .map(response -> {
                try {
                    JsonNode json = objectMapper.readTree(response);
                    if (json.has("ok") && json.get("ok").asBoolean()) {
                        List<JsonNode> updates = new ArrayList<>();
                        json.get("result").forEach(updates::add);
                        return updates;
                    }
                    return Collections.<JsonNode>emptyList();
                } catch (Exception e) {
                    log.error("Failed to parse updates: {}", e.getMessage());
                    return Collections.<JsonNode>emptyList();
                }
            })
            .onErrorResume(e -> {
                log.error("Failed to get updates: {}", e.getMessage());
                return Mono.just(Collections.emptyList());
            });
    }

    private void processUpdates(List<JsonNode> updates) {
        for (JsonNode update : updates) {
            try {
                int updateId = update.get("update_id").asInt();
                lastUpdateId = Math.max(lastUpdateId, updateId);

                // 处理消息
                if (update.has("message")) {
                    processMessage(update.get("message"));
                } else if (update.has("edited_message")) {
                    processMessage(update.get("edited_message"));
                } else if (update.has("channel_post")) {
                    processChannelPost(update.get("channel_post"));
                }
            } catch (Exception e) {
                log.error("Error processing update: {}", e.getMessage());
            }
        }
    }

    private void processMessage(JsonNode message) {
        String chatId = message.get("chat").get("id").asText();
        String chatType = message.get("chat").get("type").asText();

        // 获取用户信息
        String userId = null;
        String userName = null;
        if (message.has("from")) {
            JsonNode from = message.get("from");
            userId = String.valueOf(from.get("id").asLong());
            userName = from.has("username") 
                ? "@" + from.get("username").asText()
                : from.get("first_name").asText();
        }

        // 获取消息文本
        String text = null;
        List<String> mediaUrls = new ArrayList<>();
        MessageEvent.MessageType messageType = MessageEvent.MessageType.TEXT;

        if (message.has("text")) {
            text = message.get("text").asText();
        } else if (message.has("photo")) {
            messageType = MessageEvent.MessageType.PHOTO;
            JsonNode photos = message.get("photo");
            JsonNode largest = photos.get(photos.size() - 1);
            mediaUrls.add("telegram:file:" + largest.get("file_id").asText());
            if (message.has("caption")) {
                text = message.get("caption").asText();
            }
        } else if (message.has("voice")) {
            messageType = MessageEvent.MessageType.VOICE;
            mediaUrls.add("telegram:file:" + message.get("voice").get("file_id").asText());
        } else if (message.has("video")) {
            messageType = MessageEvent.MessageType.VIDEO;
            mediaUrls.add("telegram:file:" + message.get("video").get("file_id").asText());
        } else if (message.has("document")) {
            messageType = MessageEvent.MessageType.DOCUMENT;
            mediaUrls.add("telegram:file:" + message.get("document").get("file_id").asText());
        }

        if (text == null && mediaUrls.isEmpty()) {
            return;  // 忽略空消息
        }

        // 构建事件
        SessionSource source = buildSource(
            chatId,
            message.get("chat").has("title") 
                ? message.get("chat").get("title").asText() 
                : userName,
            chatType,
            userId,
            userName,
            message.has("message_thread_id") 
                ? message.get("message_thread_id").asText() 
                : null,
            null
        );

        MessageEvent event = new MessageEvent();
        event.setSource(source);
        event.setText(text != null ? text : "");
        event.setMessageType(messageType);
        event.setMessageId(String.valueOf(message.get("message_id").asInt()));
        event.setMediaUrls(mediaUrls);

        // 处理消息
        handleMessage(event).subscribe();
    }

    private void processChannelPost(JsonNode post) {
        // 频道消息处理（类似群消息但不验证用户）
        String chatId = post.get("chat").get("id").asText();
        String text = post.has("text") ? post.get("text").asText() : "";

        SessionSource source = buildSource(
            chatId,
            post.get("chat").has("title") 
                ? post.get("chat").get("title").asText() 
                : "Channel",
            "channel",
            null,
            null,
            null,
            null
        );

        MessageEvent event = new MessageEvent();
        event.setSource(source);
        event.setText(text);
        event.setMessageId(String.valueOf(post.get("message_id").asInt()));

        handleMessage(event).subscribe();
    }

    /**
     * 转义 MarkdownV2 特殊字符
     */
    private String escapeMarkdownV2(String text) {
        if (text == null) return null;
        Matcher m = MARKDOWN_SPECIAL.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, "\\\\" + m.group(1));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * UTF-16 长度限制的消息截断
     */
    private List<String> truncateMessageUtf16(String content, int maxLength) {
        // 简化实现：使用 UTF-16 编码长度
        byte[] utf16Bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        int utf16Length = utf16Bytes.length / 2;

        if (utf16Length <= maxLength) {
            return List.of(content);
        }

        // 需要分割
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentLength = 0;

        for (char c : content.toCharArray()) {
            int charLen = Character.isSurrogate(c) ? 2 : 1;
            if (currentLength + charLen > maxLength - 10) {  // 预留指示器空间
                chunks.add(current.toString());
                current = new StringBuilder();
                currentLength = 0;
            }
            current.append(c);
            currentLength += charLen;
        }

        if (current.length() > 0) {
            chunks.add(current.toString());
        }

        // 添加分块指示器
        if (chunks.size() > 1) {
            int total = chunks.size();
            for (int i = 0; i < total; i++) {
                chunks.set(i, chunks.get(i) + " (" + (i + 1) + "/" + total + ")");
            }
        }

        return chunks;
    }
}
