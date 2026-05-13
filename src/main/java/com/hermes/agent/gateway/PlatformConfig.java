package com.hermes.agent.gateway;

import java.util.*;

/**
 * 平台配置
 */
public class PlatformConfig {
    private boolean enabled = false;
    private String token;           // Bot token (Telegram, Discord)
    private String apiKey;          // API key（如果与 token 不同）
    private HomeChannel homeChannel;
    private String replyToMode = "first";  // "off", "first", "all"
    private Map<String, Object> extra = new HashMap<>();

    public PlatformConfig() {}

    public PlatformConfig(boolean enabled, String token) {
        this.enabled = enabled;
        this.token = token;
    }

    // Getters and Setters
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public HomeChannel getHomeChannel() { return homeChannel; }
    public void setHomeChannel(HomeChannel homeChannel) { this.homeChannel = homeChannel; }
    public String getReplyToMode() { return replyToMode; }
    public void setReplyToMode(String replyToMode) { this.replyToMode = replyToMode; }
    public Map<String, Object> getExtra() { return extra; }
    public void setExtra(Map<String, Object> extra) { this.extra = extra; }

    public Optional<String> getExtraString(String key) {
        Object value = extra.get(key);
        return value != null ? Optional.of(String.valueOf(value)) : Optional.empty();
    }

    public Optional<Integer> getExtraInt(String key) {
        Object value = extra.get(key);
        if (value instanceof Number) {
            return Optional.of(((Number) value).intValue());
        }
        return Optional.empty();
    }

    public Optional<Boolean> getExtraBoolean(String key) {
        Object value = extra.get(key);
        if (value instanceof Boolean) {
            return Optional.of((Boolean) value);
        }
        if (value instanceof String) {
            return Optional.of(Boolean.parseBoolean((String) value));
        }
        return Optional.empty();
    }

    public Optional<Long> getExtraLong(String key) {
        Object value = extra.get(key);
        if (value instanceof Number) {
            return Optional.of(((Number) value).longValue());
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> getExtraStringMap(String key) {
        Object value = extra.get(key);
        if (value instanceof Map) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                result.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            return result;
        }
        return Map.of();
    }

    /**
     * 获取 extra 中的字符串列表
     */
    @SuppressWarnings("unchecked")
    public List<String> getExtraStringList(String key) {
        Object value = extra.get(key);
        if (value instanceof List) {
            List<String> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return List.of();
    }

    /**
     * 带默认值的便捷方法
     */
    public String getExtraString(String key, String defaultValue) {
        return getExtraString(key).orElse(defaultValue);
    }

    public int getExtraInt(String key, int defaultValue) {
        return getExtraInt(key).orElse(defaultValue);
    }

    public boolean getExtraBoolean(String key, boolean defaultValue) {
        return getExtraBoolean(key).orElse(defaultValue);
    }

    public long getExtraLong(String key, long defaultValue) {
        return getExtraLong(key).orElse(defaultValue);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", enabled);
        map.put("reply_to_mode", replyToMode);
        if (token != null) map.put("token", token);
        if (apiKey != null) map.put("api_key", apiKey);
        if (homeChannel != null) map.put("home_channel", homeChannel.toMap());
        map.put("extra", extra);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static PlatformConfig fromMap(Map<String, Object> map) {
        PlatformConfig config = new PlatformConfig();
        config.setEnabled(((Boolean) map.getOrDefault("enabled", false)));
        config.setToken((String) map.get("token"));
        config.setApiKey((String) map.get("api_key"));
        config.setReplyToMode((String) map.getOrDefault("reply_to_mode", "first"));
        
        Object hc = map.get("home_channel");
        if (hc instanceof Map) {
            config.setHomeChannel(HomeChannel.fromMap((Map<String, Object>) hc));
        }
        
        Object extra = map.get("extra");
        if (extra instanceof Map) {
            config.setExtra((Map<String, Object>) extra);
        }
        
        return config;
    }
}
