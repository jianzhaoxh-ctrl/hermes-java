package com.hermes.agent.gateway.adapters;

import com.hermes.agent.gateway.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * API Server 适配器 — 通过 HTTP REST API 接收外部系统消息
 *
 * 适用于：
 * - 第三方系统 webhook 回调（GitHub/GitLab/Jira 等事件推送）
 * - 物联网设备数据上报
 * - 移动 App 原生 API 调用
 * - 作为 AI Agent 的统一外部 API 网关
 *
 * 安全机制：
 * - API Key 认证（X-API-Key header）
 * - HMAC-SHA256 签名验证（X-Signature header）
 * - IP 白名单过滤
 * - 请求频率限制（基于 IP）
 *
 * 配置 (config.yaml):
 *   platforms:
 *     - name: api_server
 *       enabled: true
 *       listen_path: "/api/agent"
 *       api_key: "your-secret-api-key"
 *       hmac_secret: "your-hmac-secret"
 *       allowed_ips: ["127.0.0.1", "10.0.0.0/8"]
 *       rate_limit_per_minute: 60
 *       default_session: "api:{source}"
 *       webhook_url: "https://your-webhook-endpoint.com/notify"
 */
public class ApiServerAdapter extends BasePlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(ApiServerAdapter.class);

    private final String listenPath;
    private final String apiKey;
    private final String hmacSecret;
    private final Set<String> allowedIps;
    private final int rateLimitPerMinute;
    private final String defaultSession;
    private final String webhookUrl;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    private final Map<String, Long> rateLimitCache = new ConcurrentHashMap<>();
    private final Map<String, Long> messageIdCache = new ConcurrentHashMap<>();
    private final AtomicLong requestCount = new AtomicLong(0);
    private final AtomicReference<String> lastError = new AtomicReference<>("");

    public ApiServerAdapter(PlatformConfig config) {
        super(config, Platform.API_SERVER);
        this.objectMapper = new ObjectMapper();
        this.listenPath = config.getExtraString("listen_path", "/api/agent");
        this.apiKey = config.getExtraString("api_key", "");
        this.hmacSecret = config.getExtraString("hmac_secret", "");
        this.rateLimitPerMinute = config.getExtraInt("rate_limit_per_minute", 60);
        this.defaultSession = config.getExtraString("default_session", "api:{source}");
        this.webhookUrl = config.getExtraString("webhook_url", "");

        List<String> ipList = config.getExtraStringList("allowed_ips");
        this.allowedIps = ipList.isEmpty() ? Set.of() : new HashSet<>(ipList);

        this.webClient = WebClient.builder()
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();

        log.info("[ApiServer] Initialized path={} key={} ips={} rateLimit={}/min webhook={}",
            listenPath,
            apiKey.isEmpty() ? "NONE" : "SET",
            allowedIps.isEmpty() ? "ALL" : allowedIps.size(),
            rateLimitPerMinute,
            webhookUrl.isEmpty() ? "NONE" : "SET");
    }

    // ========== 生命周期 ==========

    @Override public Mono<Boolean> connect() {
        log.info("[ApiServer] Adapter started — listening on {}", listenPath);
        return Mono.just(true);
    }

    @Override public Mono<Void> disconnect() {
        log.info("[ApiServer] Adapter stopped ({} requests processed)", requestCount.get());
        return Mono.empty();
    }

    // ========== 消息发送 ==========

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        if (webhookUrl.isEmpty()) {
            log.debug("[ApiServer] send called but no webhook_url configured");
            return Mono.just(SendResult.success(chatId, "queued_no_webhook"));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", content);
        if (replyTo != null) payload.put("reply_to", replyTo);
        if (metadata != null) payload.putAll(metadata);

        return webClient.post()
            .uri(URI.create(webhookUrl))
            .bodyValue(payload)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(10))
            .map(resp -> SendResult.success(chatId, resp))
            .doOnError(e -> log.error("[ApiServer] send failed: {}", e.getMessage()))
            .onErrorResume(e -> Mono.just(SendResult.failure(e.getMessage())));
    }

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        Map<String, Object> info = new HashMap<>();
        info.put("id", chatId);
        info.put("type", "api_server");
        return Mono.just(info);
    }

    // ========== 核心处理方法 — 由 GatewayController 调用 ==========

    /**
     * 处理 API 请求
     *
     * 端点:
     *   POST {listenPath}/message  → 发送消息
     *   POST {listenPath}/deliver → 投递消息（带 target 路由）
     *   GET  {listenPath}/status  → 健康检查
     *   GET  {listenPath}/history/{sessionId} → 获取历史
     */
    public Mono<ApiResponse> handleRequest(
            String method,
            String path,
            Map<String, String> headers,
            Map<String, String> queryParams,
            String body,
            String remoteIp) {

        requestCount.incrementAndGet();

        // 1. IP 白名单检查
        if (!allowedIps.isEmpty() && !isIpAllowed(remoteIp)) {
            return Mono.just(ApiResponse.error(HttpStatus.FORBIDDEN, "IP not allowed: " + remoteIp));
        }

        // 2. API Key 认证
        if (!apiKey.isEmpty() && !verifyApiKey(headers.get("x-api-key"))) {
            return Mono.just(ApiResponse.error(HttpStatus.UNAUTHORIZED, "Invalid API key"));
        }

        // 3. HMAC 签名验证（GitHub/GitLab 风格）
        if (!hmacSecret.isEmpty()) {
            String signature = headers.get("x-signature");
            if (!verifyHmacSignature(body, signature, hmacSecret)) {
                return Mono.just(ApiResponse.error(HttpStatus.UNAUTHORIZED, "Invalid signature"));
            }
        }

        // 4. 速率限制
        if (!checkRateLimit(remoteIp)) {
            return Mono.just(ApiResponse.error(HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded for " + remoteIp));
        }

        // 5. 路由
        String normalizedPath = path.startsWith(listenPath)
            ? path.substring(listenPath.length()) : path;

        if (normalizedPath.isEmpty() || normalizedPath.equals("/")) {
            return handleStatus();
        }
        if (normalizedPath.equals("/message") || normalizedPath.equals("/message/")) {
            return handleMessage(body, headers);
        }
        if (normalizedPath.startsWith("/deliver")) {
            return handleDeliver(normalizedPath, body, headers);
        }
        if (normalizedPath.startsWith("/status")) {
            return handleStatus();
        }
        if (normalizedPath.startsWith("/history/")) {
            String sessionId = normalizedPath.substring("/history/".length());
            return handleHistory(sessionId, queryParams);
        }

        return Mono.just(ApiResponse.error(HttpStatus.NOT_FOUND, "Unknown path: " + normalizedPath));
    }

    // ========== 请求处理器 ==========

    private Mono<ApiResponse> handleMessage(String body, Map<String, String> headers) {
        return parseMessageBody(body)
            .flatMap(req -> {
                if (req.msgId != null && isDuplicate(req.msgId)) {
                    log.debug("[ApiServer] Duplicate message {} — skip", req.msgId);
                    Map<String, Object> dupData = new LinkedHashMap<>();
                    dupData.put("status", "duplicate");
                    dupData.put("msg_id", req.msgId);
                    return Mono.just(ApiResponse.ok(dupData));
                }

                String sessionId = buildSessionId(req.sessionId, req.source, req.userId);
                SessionSource source = new SessionSource(Platform.API_SERVER, req.channelId != null ? req.channelId : "");
                source.setUserId(req.userId != null ? req.userId : "");
                source.setGuildId(req.source != null ? req.source : "");
                String displayName = (req.source != null && !req.source.isEmpty()) ? req.source : "API User";
                source.setChatName(displayName);
                source.setChatType("dm");

                String finalText = req.text;
                String finalSessionId = sessionId;
                SessionSource finalSource = source;

                return Mono.fromRunnable(() -> {
                    if (messageHandler != null) {
                        MessageEvent event = new MessageEvent();
                        event.setText(finalText);
                        event.setSource(finalSource);
                        event.setTimestamp(Instant.now());
                        event.setMessageId(req.msgId);
                        event.setChannelPrompt(displayName);
                        messageHandler.apply(event).subscribe();
                    }
                }).thenReturn(ApiResponse.ok(new LinkedHashMap<String, Object>() {{
                    put("status", "queued");
                    put("session_id", finalSessionId);
                    put("platform", "api_server");
                }}));
            })
            .onErrorResume(e -> {
                log.error("[ApiServer] handleMessage error: {}", e.getMessage());
                lastError.set(e.getMessage());
                return Mono.just(ApiResponse.error(HttpStatus.BAD_REQUEST, e.getMessage()));
            });
    }

    private Mono<ApiResponse> handleDeliver(String path, String body, Map<String, String> headers) {
        return parseMessageBody(body)
            .flatMap(req -> {
                String target = req.target;
                if (target == null || target.isEmpty()) {
                    return Mono.just(ApiResponse.error(HttpStatus.BAD_REQUEST, "target is required"));
                }
                String text = req.text;
                String finalTarget = target;
                return Mono.fromRunnable(() -> {
                    if (deliveryHandler != null) {
                        SessionSource dummyOrigin = new SessionSource(Platform.API_SERVER, "");
                        DeliveryRouter.DeliveryTarget dt = DeliveryRouter.DeliveryTarget.parse(finalTarget, dummyOrigin);
                        deliveryHandler.accept(text, dt);
                    }
                }).thenReturn(ApiResponse.ok(new LinkedHashMap<String, Object>() {{
                    put("status", "delivered");
                    put("target", target);
                    put("platform", "api_server");
                }}));
            });
    }

    private Mono<ApiResponse> handleStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "ok");
        status.put("platform", "api_server");
        status.put("uptime_seconds", requestCount.get());
        status.put("listen_path", listenPath);
        status.put("auth_enabled", !apiKey.isEmpty());
        status.put("rate_limit_per_minute", rateLimitPerMinute);
        status.put("webhook_configured", !webhookUrl.isEmpty());
        status.put("last_error", lastError.get());
        status.put("timestamp", Instant.now().toString());
        return Mono.just(ApiResponse.ok(status));
    }

    private Mono<ApiResponse> handleHistory(String sessionId, Map<String, String> queryParams) {
        Map<String, Object> historyData = new LinkedHashMap<>();
        historyData.put("session_id", sessionId);
        historyData.put("history", List.of());
        historyData.put("note", "TranscriptManager integration pending");
        return Mono.just(ApiResponse.ok(historyData));
    }

    // ========== 请求解析 ==========

    private Mono<ApiMessageRequest> parseMessageBody(String body) {
        return Mono.fromCallable(() -> {
            if (body == null || body.trim().isEmpty()) {
                throw new IllegalArgumentException("Empty request body");
            }
            JsonNode node = objectMapper.readTree(body);
            String text = getText(node, "text");
            if (text == null || text.isEmpty()) {
                text = getText(node, "content");
            }
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("text field is required");
            }
            String sessionId = getText(node, "session_id");
            String source = getText(node, "source");
            String userId = getText(node, "user_id");
            String channelId = getText(node, "channel_id");
            String target = getText(node, "target");
            String msgId = getText(node, "msg_id");
            JsonNode metadata = node.get("metadata");
            Map<String, String> metaMap = new HashMap<>();
            if (metadata != null && metadata.isObject()) {
                metadata.fields().forEachRemaining(e -> metaMap.put(e.getKey(), e.getValue().asText()));
            }
            return new ApiMessageRequest(text, sessionId, source, userId, channelId, target, msgId, metaMap);
        });
    }

    // ========== 安全验证 ==========

    private boolean isIpAllowed(String ip) {
        if (ip == null) return false;
        if (allowedIps.contains(ip)) return true;
        for (String pattern : allowedIps) {
            if (pattern.endsWith("/0") && ip.startsWith(pattern.substring(0, pattern.length() - 2))) {
                return true;
            }
        }
        return false;
    }

    private boolean verifyApiKey(String provided) {
        if (apiKey.isEmpty()) return true;
        return apiKey.equals(provided);
    }

    private boolean verifyHmacSignature(String body, String signature, String secret) {
        if (signature == null || signature.isEmpty()) return false;
        try {
            String bodyToSign = body != null ? body : "";
            String expected = hmacHex("sha256", secret, bodyToSign);
            if (signature.startsWith("sha256=")) {
                return expected.equalsIgnoreCase(signature.substring(7));
            }
            return expected.equalsIgnoreCase(signature);
        } catch (Exception e) {
            log.warn("[ApiServer] HMAC verify failed: {}", e.getMessage());
            return false;
        }
    }

    private String hmacHex(String algorithm, String secret, String data) throws NoSuchAlgorithmException, java.security.InvalidKeyException {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private boolean checkRateLimit(String ip) {
        if (ip == null) return true;
        if (rateLimitPerMinute <= 0) return true;
        long now = System.currentTimeMillis() / 60000;
        String key = ip + ":" + now;
        Long count = rateLimitCache.get(key);
        if (count != null && count >= rateLimitPerMinute) return false;
        rateLimitCache.put(key, (count != null ? count : 0L) + 1);
        rateLimitCache.entrySet().removeIf(e -> {
            String[] parts = e.getKey().split(":");
            return parts.length == 2 && !parts[1].equals(String.valueOf(now));
        });
        return true;
    }

    // ========== 发送消息 ==========

    /**
     * 通过配置的 webhook URL 发送消息（回调通知）
     */
    public Mono<SendResult> sendText(String chatId, String text) {
        return send(chatId, text, null, null);
    }

    /**
     * 流式响应（Server-Sent Events）
     */
    public reactor.core.publisher.Flux<String> sendStream(String chatId, reactor.core.publisher.Flux<String> contentFlux) {
        if (webhookUrl.isEmpty()) {
            return contentFlux;
        }
        return contentFlux.flatMap(chunk -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chat_id", chatId);
            payload.put("text", chunk);
            payload.put("stream", true);
            return webClient.post()
                .uri(URI.create(webhookUrl))
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .thenReturn(chunk)
                .onErrorReturn(chunk);
        });
    }

    // ========== 工具方法 ==========

    private String buildSessionId(String sessionId, String source, String userId) {
        if (sessionId != null && !sessionId.isEmpty()) return sessionId;
        String tmpl = defaultSession;
        tmpl = tmpl.replace("{source}", source != null ? source : "unknown");
        tmpl = tmpl.replace("{user_id}", userId != null ? userId : "anon");
        return tmpl;
    }

    private boolean isDuplicate(String msgId) {
        if (messageIdCache.containsKey(msgId)) return true;
        messageIdCache.put(msgId, System.currentTimeMillis());
        messageIdCache.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue() > 60000);
        return false;
    }

    private static String getText(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    // ========== 内部类型 ==========

    private static class ApiMessageRequest {
        final String text;
        final String sessionId;
        final String source;
        final String userId;
        final String channelId;
        final String target;
        final String msgId;
        final Map<String, String> metadata;

        ApiMessageRequest(String text, String sessionId, String source,
                         String userId, String channelId, String target,
                         String msgId, Map<String, String> metadata) {
            this.text = text; this.sessionId = sessionId; this.source = source;
            this.userId = userId; this.channelId = channelId;
            this.target = target; this.msgId = msgId; this.metadata = metadata;
        }
    }

    /**
     * API 响应封装
     */
    public static class ApiResponse {
        public final int statusCode;
        public final boolean success;
        public final String message;
        public final Map<String, Object> data;

        private ApiResponse(int statusCode, boolean success, String message, Map<String, Object> data) {
            this.statusCode = statusCode; this.success = success;
            this.message = message; this.data = data;
        }

        public static ApiResponse ok(Map<String, Object> data) {
            return new ApiResponse(200, true, "ok", data);
        }

        public static ApiResponse error(HttpStatus status, String message) {
            Map<String, Object> errData = new LinkedHashMap<>();
            errData.put("error", message);
            return new ApiResponse(status.value(), false, message, errData);
        }

        public String toJsonString() {
            try {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("success", success);
                m.put("status", statusCode);
                if (message != null) m.put("message", message);
                if (data != null) m.putAll(data);
                return mapper.writeValueAsString(m);
            } catch (Exception e) {
                return "{\"success\":false,\"error\":\"" + message + "\"}";
            }
        }
    }
}
