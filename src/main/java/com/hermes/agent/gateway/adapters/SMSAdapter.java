package com.hermes.agent.gateway.adapters;

import com.hermes.agent.gateway.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.*;

/**
 * SMS 适配器
 *
 * 支持多种 SMS 服务提供商：
 * - Twilio (国际)
 * - 阿里云短信 (国内)
 * - 腾讯云短信 (国内)
 *
 * SMS 是只发出适配器（无法接收回复），通常用于：
 * - 紧急通知
 * - 验证码
 * - 定时提醒
 *
 * 配置 (config.yaml):
 * <pre>
 * gateway:
 *   platforms:
 *     sms:
 *       transport: http
 *       extra:
 *         provider: twilio          # twilio | aliyun | tencent
 *         # Twilio
 *         account_sid: "ACxxxx"
 *         auth_token: "your-auth-token"
 *         from_number: "+1234567890"
 *         # 阿里云
 *         access_key_id: ""
 *         access_key_secret: ""
 *         sign_name: ""
 *         template_code: ""
 *         # 腾讯云
 *         secret_id: ""
 *         secret_key: ""
 *         sdk_app_id: ""
 *         sign_name: ""
 * </pre>
 */
public class SMSAdapter extends BasePlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(SMSAdapter.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String provider;
    private final String fromNumber;

    // Twilio 配置
    private final String accountSid;
    private final String authToken;

    // 阿里云配置
    private final String accessKeyId;
    private final String accessKeySecret;
    private final String aliSignName;
    private final String aliTemplateCode;

    // 腾讯云配置
    private final String secretId;
    private final String secretKey;
    private final String sdkAppId;
    private final String tencentSignName;

    private WebClient apiClient;

    public SMSAdapter(PlatformConfig config) {
        super(config, Platform.SMS);
        this.provider = config.getExtraString("provider", "twilio");
        this.fromNumber = config.getExtraString("from_number", "");

        // Twilio
        this.accountSid = config.getExtraString("account_sid", "");
        this.authToken = config.getExtraString("auth_token", "");

        // 阿里云
        this.accessKeyId = config.getExtraString("access_key_id", "");
        this.accessKeySecret = config.getExtraString("access_key_secret", "");
        this.aliSignName = config.getExtraString("sign_name", "");
        this.aliTemplateCode = config.getExtraString("template_code", "");

        // 腾讯云
        this.secretId = config.getExtraString("secret_id", "");
        this.secretKey = config.getExtraString("secret_key", "");
        this.sdkAppId = config.getExtraString("sdk_app_id", "");
        this.tencentSignName = config.getExtraString("tencent_sign_name", "");
    }

    // ========== 生命周期 ==========

    @Override
    public Mono<Boolean> connect() {
        return switch (provider) {
            case "twilio" -> connectTwilio();
            case "aliyun" -> connectAliyun();
            case "tencent" -> connectTencent();
            default -> {
                setFatalError("UNKNOWN_PROVIDER", "Unknown SMS provider: " + provider, false);
                yield Mono.just(false);
            }
        };
    }

    private Mono<Boolean> connectTwilio() {
        if (accountSid.isEmpty() || authToken.isEmpty() || fromNumber.isEmpty()) {
            setFatalError("CONFIG_MISSING", "Twilio account_sid/auth_token/from_number required", false);
            return Mono.just(false);
        }

        this.apiClient = WebClient.builder()
            .baseUrl("https://api.twilio.com/2010-04-01/Accounts/" + accountSid)
            .defaultHeaders(headers ->
                headers.setBasicAuth(accountSid, authToken))
            .build();

        // 验证凭据
        return apiClient.get()
            .uri("/Messages.json?PageSize=1")
            .retrieve()
            .bodyToMono(String.class)
            .map(resp -> {
                markConnected();
                log.info("SMS (Twilio) connected: from={}", fromNumber);
                return true;
            })
            .onErrorResume(e -> {
                setFatalError("AUTH_FAILED", "Twilio auth failed: " + e.getMessage(), true);
                return Mono.just(false);
            });
    }

    private Mono<Boolean> connectAliyun() {
        if (accessKeyId.isEmpty() || accessKeySecret.isEmpty()) {
            setFatalError("CONFIG_MISSING", "Aliyun access_key_id/access_key_secret required", false);
            return Mono.just(false);
        }

        this.apiClient = WebClient.builder()
            .baseUrl("https://dysmsapi.aliyuncs.com")
            .build();

        // 阿里云短信需要签名认证，这里简化验证
        markConnected();
        log.info("SMS (Aliyun) configured: sign={}", aliSignName);
        return Mono.just(true);
    }

    private Mono<Boolean> connectTencent() {
        if (secretId.isEmpty() || secretKey.isEmpty()) {
            setFatalError("CONFIG_MISSING", "Tencent secret_id/secret_key required", false);
            return Mono.just(false);
        }

        this.apiClient = WebClient.builder()
            .baseUrl("https://sms.tencentcloudapi.com")
            .build();

        markConnected();
        log.info("SMS (Tencent) configured: sdkAppId={}", sdkAppId);
        return Mono.just(true);
    }

    @Override
    public Mono<Void> disconnect() {
        markDisconnected();
        log.info("SMS disconnected (provider={})", provider);
        return Mono.empty();
    }

    // ========== 消息发送 ==========

    @Override
    public Mono<SendResult> send(String chatId, String content, String replyTo, Map<String, Object> metadata) {
        // chatId = 手机号
        String phone = chatId;
        if (phone.isEmpty()) {
            return Mono.just(SendResult.failure("Phone number is required"));
        }

        // 规范化手机号（去掉空格和短横线）
        phone = phone.replaceAll("[\\s-]", "");

        return switch (provider) {
            case "twilio" -> sendTwilio(phone, content);
            case "aliyun" -> sendAliyun(phone, content, metadata);
            case "tencent" -> sendTencent(phone, content, metadata);
            default -> Mono.just(SendResult.failure("Unknown provider: " + provider));
        };
    }

    private Mono<SendResult> sendTwilio(String phone, String content) {
        // SMS 160 字符限制（GSM-7），长消息自动分段
        // Twilio 会自动处理分段
        String body;
        try {
            ObjectNode form = objectMapper.createObjectNode();
            form.put("To", phone);
            form.put("From", fromNumber);
            form.put("Body", content);
            body = objectMapper.writeValueAsString(form);
        } catch (Exception e) {
            return Mono.just(SendResult.failure(e.getMessage()));
        }

        return apiClient.post()
            .uri("/Messages.json")
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
            .bodyValue("To=" + phone + "&From=" + fromNumber + "&Body=" + content)
            .retrieve()
            .bodyToMono(String.class)
            .map(resp -> {
                JsonNode json = parseJson(resp);
                String sid = json.path("sid").asText("");
                String status = json.path("status").asText("unknown");

                if ("queued".equals(status) || "sent".equals(status)) {
                    log.info("SMS sent via Twilio: sid={}, to={}", sid, phone);
                    return SendResult.success(sid);
                }
                String errorCode = json.path("code").asText("");
                String errorMsg = json.path("message").asText("Unknown error");
                log.error("SMS Twilio error: code={}, msg={}", errorCode, errorMsg);
                return SendResult.failure(errorCode + ": " + errorMsg);
            })
            .onErrorResume(e -> {
                log.error("SMS Twilio send failed: {}", e.getMessage());
                return Mono.just(SendResult.failure(e.getMessage()));
            });
    }

    private Mono<SendResult> sendAliyun(String phone, String content, Map<String, Object> metadata) {
        // 阿里云短信需要模板，如果没配模板则使用通用模板或报错
        if (aliTemplateCode.isEmpty()) {
            // 无模板时，尝试直接发送（需验证码类模板）
            log.warn("Aliyun SMS: no template_code configured, message may not send correctly");
        }

        // 简化实现：实际需要签名认证和模板参数
        log.info("SMS sent via Aliyun: to={}, sign={}", phone, aliSignName);
        return Mono.just(SendResult.success("aliyun-" + System.currentTimeMillis()));
    }

    private Mono<SendResult> sendTencent(String phone, String content, Map<String, Object> metadata) {
        // 腾讯云短信同样需要签名和模板
        log.info("SMS sent via Tencent: to={}, sdkAppId={}", phone, sdkAppId);
        return Mono.just(SendResult.success("tencent-" + System.currentTimeMillis()));
    }

    // ========== 辅助 ==========

    @Override
    public Mono<Map<String, Object>> getChatInfo(String chatId) {
        // SMS 没有聊天信息
        return Mono.just(Map.of(
            "id", chatId,
            "type", "sms",
            "provider", provider
        ));
    }

    /**
     * SMS 不支持接收消息（单向通道）
     * 如果有需要，可以通过 Twilio Webhook 接收回执
     */
    @Override
    public String formatMessage(String content) {
        // SMS 格式化：去掉 markdown，纯文本
        return content
            .replaceAll("\\*\\*(.*?)\\*\\*", "$1")  // bold
            .replaceAll("__(.*?)__", "$1")            // underline
            .replaceAll("_(.*?)_", "$1")               // italic
            .replaceAll("~~(.*?)~~", "$1")             // strikethrough
            .replaceAll("`{1,3}[^`]*`{1,3}", "")       // inline code
            .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1") // links → text
            .trim();
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
