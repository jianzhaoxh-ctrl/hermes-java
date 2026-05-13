package com.hermes.agent.gateway;

import java.util.Map;
import java.util.HashMap;

/**
 * Home Channel - 平台的默认目标
 * 
 * 当定时任务指定 deliver="telegram" 但没有特定聊天 ID 时，
 * 消息发送到此 home channel。
 */
public class HomeChannel {
    private final Platform platform;
    private final String chatId;
    private final String name;  // 人类可读名称

    public HomeChannel(Platform platform, String chatId, String name) {
        this.platform = platform;
        this.chatId = chatId;
        this.name = name != null ? name : "Home";
    }

    public Platform getPlatform() { return platform; }
    public String getChatId() { return chatId; }
    public String getName() { return name; }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("platform", platform.getValue());
        map.put("chat_id", chatId);
        map.put("name", name);
        return map;
    }

    public static HomeChannel fromMap(Map<String, Object> map) {
        return new HomeChannel(
            Platform.fromValue((String) map.get("platform")),
            String.valueOf(map.get("chat_id")),
            (String) map.getOrDefault("name", "Home")
        );
    }
}
