package com.hermes.agent.gateway;

/**
 * 平台枚举 - 支持的消息平台类型
 */
public enum Platform {
    LOCAL("local"),
    TELEGRAM("telegram"),
    DISCORD("discord"),
    WHATSAPP("whatsapp"),
    SLACK("slack"),
    SIGNAL("signal"),
    MATTERMOST("mattermost"),
    MATRIX("matrix"),
    HOMEASSISTANT("homeassistant"),
    EMAIL("email"),
    SMS("sms"),
    DINGTALK("dingtalk"),
    FEISHU("feishu"),
    WECOM("wecom"),
    WECOM_CALLBACK("wecom_callback"),
    WEIXIN("weixin"),
    BLUEBUBBLES("bluebubbles"),
    QQBOT("qqbot"),
    API_SERVER("api_server"),
    WEBHOOK("webhook");

    private final String value;

    Platform(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static Platform fromValue(String value) {
        for (Platform p : values()) {
            if (p.value.equalsIgnoreCase(value)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown platform: " + value);
    }
}
