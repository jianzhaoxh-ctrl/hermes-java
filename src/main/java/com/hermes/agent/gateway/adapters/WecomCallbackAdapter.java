package com.hermes.agent.gateway.adapters;

import com.hermes.agent.gateway.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企业微信 回调模式适配器（接收用户消息）
 *
 * 与 WeComAdapter（AI Bot 主动消息模式）互补：
 * - WeComAdapter: 调用企业微信「发送消息」API，主动推送通知给用户
 * - WecomCallbackAdapter: 接收用户通过「接收消息」URL 推送的事件
 *
 * 典型应用场景：
 * - 接收用户文本/图片/语音/位置等消息
 * - 回复用户消息（通过回调 URL 的 response 响应体）
 * - 处理关注/取消关注事件
 * - 处理点击菜单/跳转链接事件
 *
 * 配置 (config.yaml):
 *   platforms:
 *     - name: wecom_callback
 *       enabled: true
 *       token: "YOUR_WECOM_TOKEN"
 *       encoding_aes_key: "YOUR_32_CHAR_AES_KEY"
 *       default_session_template: "wecom:{from_user}"
 */
public class WecomCallbackAdapter extends BasePlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(WecomCallbackAdapter.class);

    private final String token;
    private final String encodingAesKey;
    private final String defaultSessionTemplate;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final Map<String, Long> messageIdCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> sessionLastSeen = new ConcurrentHashMap<>();

    public WecomCallbackAdapter(PlatformConfig config) {
        super(config, Platform.WECOM_CALLBACK);
        this.objectMapper = new ObjectMapper();
        this.token = config.getExtraString("token", "");
        this.encodingAesKey = config.getExtraString("encoding_aes_key", "");
        this.defaultSessionTemplate = config.getExtraString("default_session_template", "wecom:{from_user}");
        this.webClient = WebClient.builder()
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
        log.info("[WecomCallback] Initialized (token={}, aesKey={})",
            token.isEmpty() ? "MISSING" : "SET",
            encodingAesKey.isEmpty() ? "MISSING" : "SET");
    }

    // ========== 生命周期 ==========

    @Override public Mono<Boolean> connect() {
        log.info("[WecomCallback] Adapter started — webhook receiver ready");
        return Mono.just(true);
    }

    @Override public Mono<Void> disconnect() {
        log.info("[WecomCallback] Adapter stopped");
        return Mono.empty();
    }

    // ========== 消息发送 ==========

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        // 企业微信回调模式下，发送通过 buildReplyXml 在 HTTP 响应中完成
        // 此方法仅记录日志
        log.debug("[WecomCallback] send called (chatId={}, content={})", chatId,
            content != null ? content.substring(0, Math.min(50, content.length())) : "null");
        return Mono.just(SendResult.success(chatId, "queued_via_callback_response"));
    }

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        Map<String, Object> info = new HashMap<>();
        info.put("id", chatId);
        info.put("type", "wecom_callback");
        return Mono.just(info);
    }

    // ========== 回调处理 ==========

    /**
     * 处理企业微信回调请求
     * GET  → 首次验证 URL，返回解密后的 echostr
     * POST → 接收加密消息体，解密后处理并路由
     */
    public Mono<String> handleCallback(Map<String, String> params, String body) {
        if (body == null || body.trim().isEmpty()) {
            return handleUrlVerification(params);
        }
        return handleEncryptedMessage(params, body)
            .map(msg -> buildXmlResponse(msg.fromUserName, ""))
            .onErrorResume(e -> {
                log.error("[WecomCallback] Failed: {}", e.getMessage());
                return Mono.just(buildXmlResponse("", "处理失败"));
            });
    }

    private Mono<String> handleUrlVerification(Map<String, String> params) {
        String echostr = params.get("echostr");
        if (echostr == null || echostr.isEmpty()) {
            return Mono.just(buildXmlResponse("", ""));
        }
        if (token.isEmpty() || encodingAesKey.isEmpty()) {
            return Mono.just(echostr);
        }
        try {
            String decrypted = decrypt(echostr);
            log.info("[WecomCallback] URL verification SUCCESS");
            return Mono.just(decrypted);
        } catch (Exception e) {
            log.error("[WecomCallback] URL verification FAILED: {}", e.getMessage());
            return Mono.just("verification_failed");
        }
    }

    private Mono<WecomMessage> handleEncryptedMessage(Map<String, String> params, String body) {
        if (token.isEmpty() || encodingAesKey.isEmpty()) {
            return parsePlaintextMessage(body);
        }
        return Mono.fromCallable(() -> {
            String decrypted = decrypt(body);
            log.debug("[WecomCallback] Decrypted: {}", decrypted);
            return parseXmlMessage(decrypted);
        }).flatMap(wxMsg -> {
            if (isDuplicate(wxMsg.msgId, wxMsg.fromUserName)) {
                return Mono.empty();
            }
            return routeMessage(wxMsg);
        }).thenReturn(new WecomMessage("success", "", "", "text", "", Instant.now()));
    }

    private Mono<WecomMessage> parsePlaintextMessage(String body) {
        try {
            if (body.trim().startsWith("<")) {
                return Mono.just(parseXmlMessage(body));
            }
            JsonNode node = objectMapper.readTree(body);
            return Mono.just(new WecomMessage(
                getText(node, "MsgId"),
                getText(node, "FromUserName"),
                getText(node, "ToUserName"),
                getText(node, "MsgType"),
                getText(node, "Content"),
                Instant.now()
            ));
        } catch (Exception e) {
            log.warn("[WecomCallback] Failed to parse plaintext: {}", body);
            return Mono.just(new WecomMessage("unknown", "unknown", "unknown", "text", "", Instant.now()));
        }
    }

    private WecomMessage parseXmlMessage(String xml) {
        try {
            String msgId = extractXmlTag(xml, "MsgId");
            String fromUser = extractXmlTag(xml, "FromUserName");
            String toUser = extractXmlTag(xml, "ToUserName");
            String msgType = extractXmlTag(xml, "MsgType");
            String content = extractXmlTag(xml, "Content");
            String event = extractXmlTag(xml, "Event");
            String eventKey = extractXmlTag(xml, "EventKey");
            if ("event".equals(msgType) || !event.isEmpty()) {
                return new WecomMessage(msgId, fromUser, toUser, "event", event, eventKey, Instant.now());
            }
            return new WecomMessage(msgId, fromUser, toUser, msgType, content, Instant.now());
        } catch (Exception e) {
            log.error("[WecomCallback] XML parse error: {}", e.getMessage());
            return new WecomMessage("err", "err", "err", "text", "", Instant.now());
        }
    }

    private Mono<WecomMessage> routeMessage(WecomMessage msg) {
        if ("event".equals(msg.msgType)) {
            return handleEvent(msg);
        }
        String text = switch (msg.msgType) {
            case "text" -> msg.content;
            case "image" -> "[图片消息]";
            case "voice" -> "[语音消息]";
            case "video" -> "[视频消息]";
            case "location" -> "[位置] " + msg.content;
            case "link" -> "[链接] " + msg.content;
            default -> "[未知消息类型: " + msg.msgType + "]";
        };
        String sessionId = defaultSessionTemplate.replace("{from_user}", msg.fromUserName);
        SessionSource source = new SessionSource(Platform.WECOM_CALLBACK, msg.fromUserName);
        source.setUserId(msg.fromUserName);
        source.setChatName("企业微信用户 " + msg.fromUserName);
        source.setChatType("dm");
        return Mono.fromRunnable(() -> {
            MessageEvent event = new MessageEvent();
            event.setText(text);
            event.setSource(source);
            event.setMessageId(msg.msgId);
            event.setTimestamp(msg.timestamp);
            if (messageHandler != null) {
                messageHandler.apply(event).subscribe();
            }
        }).thenReturn(msg);
    }

    private Mono<WecomMessage> handleEvent(WecomMessage msg) {
        return Mono.fromRunnable(() -> {
            sessionLastSeen.put(msg.fromUserName, Instant.now());
            if (messageHandler == null) return;
            SessionSource src = new SessionSource(Platform.WECOM_CALLBACK, msg.fromUserName);
            src.setUserId(msg.fromUserName);
            MessageEvent event = new MessageEvent();
            event.setSource(src);
            event.setMessageId(msg.msgId);
            event.setTimestamp(msg.timestamp);
            switch (msg.event) {
                case "subscribe" -> {
                    log.info("[WecomCallback] User subscribed: {}", msg.fromUserName);
                    event.setText("【关注事件】");
                    messageHandler.apply(event).subscribe();
                }
                case "unsubscribe" -> log.info("[WecomCallback] User unsubscribed: {}", msg.fromUserName);
                case "CLICK" -> {
                    log.info("[WecomCallback] Menu click: {} -> {}", msg.fromUserName, msg.event);
                    event.setText("【菜单点击】" + msg.event);
                    messageHandler.apply(event).subscribe();
                }
                case "VIEW" -> log.info("[WecomCallback] Link viewed: {} -> {}", msg.fromUserName, msg.event);
                default -> log.debug("[WecomCallback] Event: {}", msg.event);
            }
        }).thenReturn(msg);
    }

    public Mono<SendResult> sendText(String toUser, String text) {
        String xml = buildReplyXml(toUser, text);
        return Mono.just(SendResult.success("wecom_callback_reply", xml))
            .delayElement(Duration.ofMillis(50));
    }

    private String buildReplyXml(String toUser, String content) {
        long now = System.currentTimeMillis() / 1000;
        return String.format(
            "<xml><ToUserName><![CDATA[%s]]></ToUserName>" +
            "<FromUserName><![CDATA[%s]]></FromUserName>" +
            "<CreateTime>%d</CreateTime><MsgType><![CDATA[text]]></MsgType>" +
            "<Content><![CDATA[%s]]></Content></xml>",
            toUser, "%FromUserName%", now, escapeXml(content));
    }

    private String buildXmlResponse(String toUser, String content) {
        long now = System.currentTimeMillis() / 1000;
        return String.format(
            "<xml><ToUserName><![CDATA[%s]]></ToUserName>" +
            "<FromUserName><![CDATA[%s]]></FromUserName>" +
            "<CreateTime>%d</CreateTime><MsgType><![CDATA[text]]></MsgType>" +
            "<Content><![CDATA[%s]]></Content></xml>",
            toUser.isEmpty() ? "%ToUserName%" : toUser, "%FromUserName%", now, content);
    }

    private String decrypt(String encrypted) throws Exception {
        if (encodingAesKey.isEmpty()) return encrypted;
        try {
            byte[] keyBytes = encodingAesKey.getBytes(StandardCharsets.UTF_8);
            byte[] encryptedBytes = Base64.getDecoder().decode(encrypted);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(
                Arrays.copyOf(keyBytes, 32), "AES");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec);
            byte[] decrypted = cipher.doFinal(encryptedBytes);
            String xml = new String(decrypted, StandardCharsets.UTF_8);
            if (xml.length() > 20) xml = xml.substring(20);
            int endIdx = xml.lastIndexOf("<");
            if (endIdx > 0) xml = xml.substring(0, endIdx);
            return xml.trim();
        } catch (Exception e) {
            log.warn("[WecomCallback] AES decrypt failed: {}", e.getMessage());
            return encrypted;
        }
    }

    public boolean verifySignature(String signature, String timestamp, String nonce) {
        if (token.isEmpty()) return true;
        String[] arr = new String[]{token, timestamp, nonce};
        Arrays.sort(arr);
        String str = arr[0] + arr[1] + arr[2];
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(str.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString().equalsIgnoreCase(signature);
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
    }

    private boolean isDuplicate(String msgId, String fromUser) {
        if (msgId == null || msgId.isEmpty()) return false;
        String key = msgId + ":" + fromUser;
        if (messageIdCache.containsKey(key)) return true;
        messageIdCache.put(key, System.currentTimeMillis());
        messageIdCache.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue() > 3600000);
        return false;
    }

    private static String extractXmlTag(String xml, String tag) {
        try {
            int start = xml.indexOf("<" + tag + ">");
            if (start < 0) return "";
            start += tag.length() + 2;
            int end = xml.indexOf("<", start);
            if (end < 0) return "";
            return xml.substring(start, end).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String getText(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? "" : n.asText();
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** 企业微信消息结构 */
    private static class WecomMessage {
        final String msgId;
        final String fromUserName;
        final String toUserName;
        final String msgType;
        final String content;
        final String event;
        final Instant timestamp;

        WecomMessage(String msgId, String fromUserName, String toUserName,
                     String msgType, String content, Instant timestamp) {
            this(msgId, fromUserName, toUserName, msgType, content, "", timestamp);
        }

        WecomMessage(String msgId, String fromUserName, String toUserName,
                     String msgType, String content, String event, Instant timestamp) {
            this.msgId = msgId; this.fromUserName = fromUserName;
            this.toUserName = toUserName; this.msgType = msgType;
            this.content = content; this.event = event; this.timestamp = timestamp;
        }
    }
}
