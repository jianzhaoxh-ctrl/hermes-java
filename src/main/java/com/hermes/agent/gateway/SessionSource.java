package com.hermes.agent.gateway;

import java.time.Instant;
import java.util.*;

/**
 * 会话来源 - 描述消息的来源信息
 * 
 * 用于：
 * 1. 路由响应回正确位置
 * 2. 向系统提示注入上下文
 * 3. 追踪定时任务的来源
 */
public class SessionSource {
    private final Platform platform;
    private final String chatId;
    private String chatName;
    private String chatType = "dm"; // "dm", "group", "channel", "thread"
    private String userId;
    private String userName;
    private String threadId;      // 论坛主题、Discord 线程等
    private String chatTopic;     // 频道主题/描述
    private String userIdAlt;     // Signal UUID
    private String chatIdAlt;     // Signal 群组内部 ID
    private String guildId;       // Discord 服务器 ID
    private boolean isBot = false;

    public SessionSource(Platform platform, String chatId) {
        this.platform = platform;
        this.chatId = chatId;
    }

    public Platform getPlatform() { return platform; }
    public String getChatId() { return chatId; }
    public String getChatName() { return chatName; }
    public void setChatName(String chatName) { this.chatName = chatName; }
    public String getChatType() { return chatType; }
    public void setChatType(String chatType) { this.chatType = chatType; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getChatTopic() { return chatTopic; }
    public void setChatTopic(String chatTopic) { this.chatTopic = chatTopic; }
    public String getUserIdAlt() { return userIdAlt; }
    public void setUserIdAlt(String userIdAlt) { this.userIdAlt = userIdAlt; }
    public String getChatIdAlt() { return chatIdAlt; }
    public void setChatIdAlt(String chatIdAlt) { this.chatIdAlt = chatIdAlt; }
    public String getGuildId() { return guildId; }
    public void setGuildId(String guildId) { this.guildId = guildId; }
    public boolean isBot() { return isBot; }
    public void setBot(boolean bot) { isBot = bot; }

    /**
     * 生成人类可读的描述
     */
    public String getDescription() {
        if (platform == Platform.LOCAL) {
            return "CLI terminal";
        }

        StringBuilder sb = new StringBuilder();
        if ("dm".equals(chatType)) {
            sb.append("DM with ").append(userName != null ? userName : 
                (userId != null ? userId : "user"));
        } else if ("group".equals(chatType)) {
            sb.append("group: ").append(chatName != null ? chatName : chatId);
        } else if ("channel".equals(chatType)) {
            sb.append("channel: ").append(chatName != null ? chatName : chatId);
        } else {
            sb.append(chatName != null ? chatName : chatId);
        }

        if (threadId != null) {
            sb.append(", thread: ").append(threadId);
        }

        return sb.toString();
    }

    /**
     * 构建会话键
     */
    public String buildSessionKey(boolean groupSessionsPerUser, boolean threadSessionsPerUser) {
        String platformStr = platform.getValue();
        
        if ("dm".equals(chatType)) {
            if (chatId != null) {
                if (threadId != null) {
                    return String.format("agent:main:%s:dm:%s:%s", platformStr, chatId, threadId);
                }
                return String.format("agent:main:%s:dm:%s", platformStr, chatId);
            }
            if (threadId != null) {
                return String.format("agent:main:%s:dm:%s", platformStr, threadId);
            }
            return String.format("agent:main:%s:dm", platformStr);
        }

        // Group/Channel/Thread
        List<String> parts = new ArrayList<>();
        parts.add("agent:main");
        parts.add(platformStr);
        parts.add(chatType);

        if (chatId != null) {
            parts.add(chatId);
        }
        if (threadId != null) {
            parts.add(threadId);
        }

        // 线程默认共享（所有参与者看到同一会话）
        boolean isolateUser = groupSessionsPerUser;
        if (threadId != null && !threadSessionsPerUser) {
            isolateUser = false;
        }

        String participantId = userIdAlt != null ? userIdAlt : userId;
        if (isolateUser && participantId != null) {
            parts.add(participantId);
        }

        return String.join(":", parts);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("platform", platform.getValue());
        map.put("chat_id", chatId);
        map.put("chat_name", chatName);
        map.put("chat_type", chatType);
        map.put("user_id", userId);
        map.put("user_name", userName);
        map.put("thread_id", threadId);
        map.put("chat_topic", chatTopic);
        if (userIdAlt != null) map.put("user_id_alt", userIdAlt);
        if (chatIdAlt != null) map.put("chat_id_alt", chatIdAlt);
        if (guildId != null) map.put("guild_id", guildId);
        return map;
    }
}
