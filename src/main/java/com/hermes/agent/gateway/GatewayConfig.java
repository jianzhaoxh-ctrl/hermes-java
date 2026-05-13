package com.hermes.agent.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Gateway 配置
 * 
 * 管理所有平台连接、会话策略和投递设置。
 */
@Component
@ConfigurationProperties(prefix = "gateway")
public class GatewayConfig {
    private static final Logger log = LoggerFactory.getLogger(GatewayConfig.class);

    // 平台配置
    private Map<String, PlatformConfig> platforms = new HashMap<>();

    // 会话重置策略
    private SessionResetPolicy defaultResetPolicy = new SessionResetPolicy();
    private Map<String, SessionResetPolicy> resetByType = new HashMap<>();
    private Map<String, SessionResetPolicy> resetByPlatform = new HashMap<>();

    // 重置触发命令
    private List<String> resetTriggers = List.of("/new", "/reset");

    // 快捷命令
    private Map<String, Object> quickCommands = new HashMap<>();

    // 投递设置
    private boolean alwaysLogLocal = true;
    private boolean sttEnabled = true;
    private boolean groupSessionsPerUser = true;
    private boolean threadSessionsPerUser = false;
    private String unauthorizedDmBehavior = "pair";  // "pair" or "ignore"

    // 流式配置
    private StreamingConfig streaming = new StreamingConfig();

    // 会话存储最大天数
    private int sessionStoreMaxAgeDays = 90;

    // ========== Getters and Setters ==========

    public Map<String, PlatformConfig> getPlatforms() { return platforms; }
    public void setPlatforms(Map<String, PlatformConfig> platforms) { this.platforms = platforms; }

    public SessionResetPolicy getDefaultResetPolicy() { return defaultResetPolicy; }
    public void setDefaultResetPolicy(SessionResetPolicy policy) { this.defaultResetPolicy = policy; }

    public Map<String, SessionResetPolicy> getResetByType() { return resetByType; }
    public void setResetByType(Map<String, SessionResetPolicy> resetByType) { this.resetByType = resetByType; }

    public Map<String, SessionResetPolicy> getResetByPlatform() { return resetByPlatform; }
    public void setResetByPlatform(Map<String, SessionResetPolicy> resetByPlatform) { this.resetByPlatform = resetByPlatform; }

    public List<String> getResetTriggers() { return resetTriggers; }
    public void setResetTriggers(List<String> resetTriggers) { this.resetTriggers = resetTriggers; }

    public Map<String, Object> getQuickCommands() { return quickCommands; }
    public void setQuickCommands(Map<String, Object> quickCommands) { this.quickCommands = quickCommands; }

    public boolean isAlwaysLogLocal() { return alwaysLogLocal; }
    public void setAlwaysLogLocal(boolean alwaysLogLocal) { this.alwaysLogLocal = alwaysLogLocal; }

    public boolean isSttEnabled() { return sttEnabled; }
    public void setSttEnabled(boolean sttEnabled) { this.sttEnabled = sttEnabled; }

    public boolean isGroupSessionsPerUser() { return groupSessionsPerUser; }
    public void setGroupSessionsPerUser(boolean groupSessionsPerUser) { this.groupSessionsPerUser = groupSessionsPerUser; }

    public boolean isThreadSessionsPerUser() { return threadSessionsPerUser; }
    public void setThreadSessionsPerUser(boolean threadSessionsPerUser) { this.threadSessionsPerUser = threadSessionsPerUser; }

    public String getUnauthorizedDmBehavior() { return unauthorizedDmBehavior; }
    public void setUnauthorizedDmBehavior(String unauthorizedDmBehavior) { this.unauthorizedDmBehavior = unauthorizedDmBehavior; }

    public StreamingConfig getStreaming() { return streaming; }
    public void setStreaming(StreamingConfig streaming) { this.streaming = streaming; }

    public int getSessionStoreMaxAgeDays() { return sessionStoreMaxAgeDays; }
    public void setSessionStoreMaxAgeDays(int sessionStoreMaxAgeDays) { this.sessionStoreMaxAgeDays = sessionStoreMaxAgeDays; }

    // ========== 便捷方法 ==========

    /**
     * 获取已连接的平台列表
     */
    public List<Platform> getConnectedPlatforms() {
        List<Platform> connected = new ArrayList<>();
        
        for (Map.Entry<String, PlatformConfig> entry : platforms.entrySet()) {
            String platformName = entry.getKey();
            PlatformConfig config = entry.getValue();
            
            if (!config.isEnabled()) {
                continue;
            }

            try {
                Platform platform = Platform.fromValue(platformName);
                
                // 检查配置完整性
                boolean valid = switch (platform) {
                    case TELEGRAM, DISCORD, SLACK, MATTERMOST, MATRIX ->
                        config.getToken() != null && !config.getToken().isBlank();
                    case WHATSAPP -> true;  // WhatsApp 使用 bridge 处理认证
                    case SIGNAL -> config.getExtraString("http_url").isPresent();
                    case EMAIL -> config.getExtraString("address").isPresent();
                    case SMS -> System.getenv("TWILIO_ACCOUNT_SID") != null;
                    case DINGTALK ->
                        config.getExtraString("client_id").isPresent() || 
                        System.getenv("DINGTALK_CLIENT_ID") != null;
                    case FEISHU -> config.getExtraString("app_id").isPresent();
                    case WECOM -> config.getExtraString("bot_id").isPresent();
                    case WECOM_CALLBACK ->
                        config.getExtraString("corp_id").isPresent() || 
                        config.getExtraString("apps").isPresent();
                    case WEIXIN ->
                        config.getExtraString("account_id").isPresent() && 
                        (config.getToken() != null || config.getExtraString("token").isPresent());
                    case BLUEBUBBLES ->
                        config.getExtraString("server_url").isPresent() && 
                        config.getExtraString("password").isPresent();
                    case QQBOT ->
                        config.getExtraString("app_id").isPresent() && 
                        config.getExtraString("client_secret").isPresent();
                    case API_SERVER, WEBHOOK -> true;
                    case HOMEASSISTANT -> config.getToken() != null;
                    case LOCAL -> false;
                };
                
                if (valid) {
                    connected.add(platform);
                }
            } catch (IllegalArgumentException e) {
                log.warn("Unknown platform in config: {}", platformName);
            }
        }
        
        return connected;
    }

    /**
     * 获取平台的 home channel
     */
    public HomeChannel getHomeChannel(Platform platform) {
        PlatformConfig config = platforms.get(platform.getValue());
        return config != null ? config.getHomeChannel() : null;
    }

    /**
     * 获取重置策略
     */
    public SessionResetPolicy getResetPolicy(Platform platform, String sessionType) {
        // 平台特定覆盖优先
        if (platform != null) {
            SessionResetPolicy platformPolicy = resetByPlatform.get(platform.getValue());
            if (platformPolicy != null) {
                return platformPolicy;
            }
        }

        // 类型特定覆盖（dm、group、thread）
        if (sessionType != null) {
            SessionResetPolicy typePolicy = resetByType.get(sessionType);
            if (typePolicy != null) {
                return typePolicy;
            }
        }

        return defaultResetPolicy;
    }

    /**
     * 获取平台配置
     */
    public PlatformConfig getPlatformConfig(Platform platform) {
        return platforms.get(platform.getValue());
    }

    /**
     * 流式配置
     */
    public static class StreamingConfig {
        private boolean enabled = false;
        private String transport = "edit";
        private double editInterval = 1.0;
        private int bufferThreshold = 40;
        private String cursor = " ▉";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getTransport() { return transport; }
        public void setTransport(String transport) { this.transport = transport; }
        public double getEditInterval() { return editInterval; }
        public void setEditInterval(double editInterval) { this.editInterval = editInterval; }
        public int getBufferThreshold() { return bufferThreshold; }
        public void setBufferThreshold(int bufferThreshold) { this.bufferThreshold = bufferThreshold; }
        public String getCursor() { return cursor; }
        public void setCursor(String cursor) { this.cursor = cursor; }
    }
}
