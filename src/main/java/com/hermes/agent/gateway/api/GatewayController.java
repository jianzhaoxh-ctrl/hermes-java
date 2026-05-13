package com.hermes.agent.gateway.api;

import com.hermes.agent.gateway.GatewayService;
import com.hermes.agent.gateway.Platform;
import com.hermes.agent.gateway.SessionSource;
import com.hermes.agent.gateway.DeliveryRouter;
import com.hermes.agent.gateway.MediaCache;
import com.hermes.agent.gateway.adapters.WebhookAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.HashMap;

/**
 * Gateway REST Controller
 * 
 * 提供 Webhook 回调端点和 Gateway 管理 API。
 */
@RestController
@RequestMapping("/api/gateway")
public class GatewayController {
    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    private final GatewayService gatewayService;

    public GatewayController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    // ========== Webhook 端点 ==========

    /**
     * 飞书 Webhook 回调
     */
    @PostMapping("/webhook/feishu")
    public Mono<ResponseEntity<String>> feishuWebhook(
        @RequestBody String body,
        @RequestHeader Map<String, String> headers
    ) {
        log.debug("Received Feishu webhook");
        
        // 提取飞书特定的 header
        Map<String, String> feishuHeaders = new HashMap<>();
        headers.forEach((k, v) -> {
            String lower = k.toLowerCase();
            if (lower.startsWith("x-lark-") || lower.startsWith("x-feishu-")) {
                feishuHeaders.put(k, v);
            }
        });

        return gatewayService.handleFeishuWebhook(body, feishuHeaders)
            .map(ResponseEntity::ok)
            .onErrorResume(e -> {
                log.error("Feishu webhook error: {}", e.getMessage());
                return Mono.just(ResponseEntity.internalServerError().body("error"));
            });
    }

    /**
     * 钉钉回调
     */
    @PostMapping("/webhook/dingtalk")
    public Mono<ResponseEntity<String>> dingtalkCallback(
        @RequestBody String body,
        @RequestHeader Map<String, String> headers
    ) {
        log.debug("Received DingTalk callback");
        
        return gatewayService.handleDingTalkCallback(body, headers)
            .map(ResponseEntity::ok)
            .onErrorResume(e -> {
                log.error("DingTalk callback error: {}", e.getMessage());
                return Mono.just(ResponseEntity.internalServerError().body("error"));
            });
    }

    /**
     * 企业微信回调
     */
    @PostMapping("/webhook/wecom")
    public Mono<ResponseEntity<String>> wecomCallback(
        @RequestBody String body,
        @RequestHeader Map<String, String> headers
    ) {
        log.debug("Received WeCom callback");
        // WeCom 使用 WebSocket，不使用 Webhook
        // 此端点用于 WeCom Callback 模式（如需）
        return Mono.just(ResponseEntity.ok().body("success"));
    }

    // ========== 微信个人号回调 ==========

    /**
     * 微信个人号回调
     */
    @PostMapping("/webhook/weixin")
    public Mono<ResponseEntity<String>> weixinCallback(
        @RequestBody String body,
        @RequestHeader Map<String, String> headers
    ) {
        log.debug("Received Weixin callback");
        // TODO: 实现
        return Mono.just(ResponseEntity.ok().body("success"));
    }

    // ========== 通用 Webhook ==========

    /**
     * 通用 Webhook 接收端点
     *
     * 接收任意 JSON POST 请求，提取消息并转发给 Agent
     */
    @PostMapping("/webhook/generic")
    public Mono<ResponseEntity<String>> genericWebhook(
        @RequestBody String body,
        @RequestHeader Map<String, String> headers
    ) {
        log.debug("Received generic webhook");
        return gatewayService.getAdapter(Platform.WEBHOOK)
            .map(adapter -> ((WebhookAdapter) adapter).handleWebhookRequest(body, headers)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.error("Generic webhook error: {}", e.getMessage());
                    return Mono.just(ResponseEntity.internalServerError().body("{\"error\":\"internal_error\"}"));
                }))
            .orElse(Mono.just(ResponseEntity.badRequest().body("{\"error\":\"webhook_platform_not_configured\"}")));
    }

    // ========== Gateway 管理 API ==========

    /**
     * 获取 Gateway 状态
     */
    @GetMapping("/status")
    public Mono<ResponseEntity<Map<String, Object>>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("connected_platforms", gatewayService.getConnectedPlatforms());
        status.put("config", gatewayService.getConfig());
        status.put("sessions_count", gatewayService.getSessionStore().listSessions(null).size());
        
        return Mono.just(ResponseEntity.ok(status));
    }

    /**
     * 获取已连接平台列表
     */
    @GetMapping("/platforms")
    public Mono<ResponseEntity<Map<String, Object>>> getPlatforms() {
        Map<String, Object> result = new HashMap<>();
        result.put("connected", gatewayService.getConnectedPlatforms());
        result.put("configured", gatewayService.getConfig().getConnectedPlatforms());
        
        return Mono.just(ResponseEntity.ok(result));
    }

    /**
     * 发送消息到指定平台
     */
    @PostMapping("/send")
    public Mono<ResponseEntity<Map<String, Object>>> sendMessage(
        @RequestBody Map<String, String> request
    ) {
        String platform = request.get("platform");
        String chatId = request.get("chat_id");
        String content = request.get("content");

        if (platform == null || chatId == null || content == null) {
            return Mono.just(ResponseEntity.badRequest()
                .body(Map.of("error", "Missing required fields: platform, chat_id, content")));
        }

        try {
            Platform p = Platform.fromValue(platform);
            return gatewayService.send(p, chatId, content)
                .map(result -> {
                    if (result.isSuccess()) {
                        return ResponseEntity.ok(Map.<String, Object>of(
                            "success", true,
                            "message_id", result.getMessageId()
                        ));
                    }
                    return ResponseEntity.ok(Map.<String, Object>of(
                        "success", false,
                        "error", result.getError()
                    ));
                });
        } catch (IllegalArgumentException e) {
            return Mono.just(ResponseEntity.badRequest()
                .body(Map.of("error", "Unknown platform: " + platform)));
        }
    }

    /**
     * 重置会话
     */
    @PostMapping("/sessions/{sessionKey}/reset")
    public Mono<ResponseEntity<Map<String, Object>>> resetSession(
        @PathVariable String sessionKey
    ) {
        var entry = gatewayService.getSessionStore().resetSession(sessionKey);
        if (entry != null) {
            return Mono.just(ResponseEntity.ok(Map.of(
                "success", true,
                "new_session_id", entry.getSessionId()
            )));
        }
        return Mono.just(ResponseEntity.notFound().build());
    }

    /**
     * 列出会话
     */
    @GetMapping("/sessions")
    public Mono<ResponseEntity<Map<String, Object>>> listSessions(
        @RequestParam(required = false) Integer activeMinutes
    ) {
        var sessions = gatewayService.getSessionStore().listSessions(activeMinutes);
        return Mono.just(ResponseEntity.ok(Map.of(
            "count", sessions.size(),
            "sessions", sessions.stream().map(e -> Map.of(
                "session_key", e.getSessionKey(),
                "session_id", e.getSessionId(),
                "platform", e.getPlatform() != null ? e.getPlatform().getValue() : null,
                "chat_type", e.getChatType(),
                "display_name", e.getDisplayName() != null ? e.getDisplayName() : "",
                "updated_at", e.getUpdatedAt().toString(),
                "total_tokens", e.getTotalTokens()
            )).toList()
        )));
    }

    // ========== 投递 API ==========

    /**
     * 投递消息到指定目标
     */
    @PostMapping("/deliver")
    public Mono<ResponseEntity<Map<String, Object>>> deliverMessage(
        @RequestBody Map<String, String> request
    ) {
        String content = request.get("content");
        String target = request.get("target");
        String platform = request.get("platform");
        String chatId = request.get("chat_id");

        if (content == null || content.isBlank()) {
            return Mono.just(ResponseEntity.badRequest()
                .body(Map.of("error", "Missing required field: content")));
        }

        // 构建 SessionSource 用于 origin 投递
        SessionSource origin = null;
        if (platform != null && chatId != null) {
            try {
                origin = new SessionSource(Platform.fromValue(platform), chatId);
            } catch (IllegalArgumentException e) {
                // 忽略无效平台
            }
        }

        DeliveryRouter router = gatewayService.getDeliveryRouter();
        if (target != null) {
            return router.deliverTo(content, target, origin)
                .map(result -> ResponseEntity.ok(Map.<String, Object>of(
                    "success", result.isSuccess(),
                    "target", result.getTarget(),
                    "message_id", result.getMessageId() != null ? result.getMessageId() : "",
                    "error", result.getError() != null ? result.getError() : ""
                )));
        }

        return Mono.just(ResponseEntity.badRequest()
            .body(Map.of("error", "Missing required field: target")));
    }

    // ========== 媒体缓存 API ==========

    /**
     * 获取媒体缓存状态
     */
    @GetMapping("/media-cache")
    public Mono<ResponseEntity<Map<String, Object>>> getMediaCacheStatus() {
        MediaCache cache = gatewayService.getMediaCache();
        return Mono.just(ResponseEntity.ok(Map.of(
            "image_dir", cache.getImageCacheDir(),
            "audio_dir", cache.getAudioCacheDir(),
            "video_dir", cache.getVideoCacheDir(),
            "document_dir", cache.getDocumentCacheDir(),
            "total_size_bytes", cache.getCacheSizeBytes()
        )));
    }

    /**
     * 清理媒体缓存
     */
    @PostMapping("/media-cache/cleanup")
    public Mono<ResponseEntity<Map<String, Object>>> cleanupMediaCache(
        @RequestParam(defaultValue = "24") int maxAgeHours
    ) {
        int removed = gatewayService.getMediaCache().cleanupCache(maxAgeHours);
        return Mono.just(ResponseEntity.ok(Map.of(
            "removed", removed,
            "max_age_hours", maxAgeHours
        )));
    }
}
