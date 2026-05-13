package com.hermes.agent.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 频道目录
 * 
 * 参考 Python 版 gateway/channel_directory.py 实现。
 * 
 * 管理频道/聊天元数据缓存：
 * - 频道名称和类型
 * - 用户昵称映射
 * - 频道成员列表
 * - 频道话题/主题
 * 
 * 用于：
 * - 为 Agent 提供频道上下文
 * - 投递路由时显示友好名称
 * - 会话管理中显示频道信息
 */
@Component
public class ChannelDirectory {
    private static final Logger log = LoggerFactory.getLogger(ChannelDirectory.class);

    /**
     * 频道信息
     */
    public static class ChannelInfo {
        private final String channelId;
        private final Platform platform;
        private String name;
        private String type;  // "dm", "group", "channel"
        private String topic;
        private String description;
        private int memberCount;
        private long updatedAt;

        public ChannelInfo(String channelId, Platform platform) {
            this.channelId = channelId;
            this.platform = platform;
            this.updatedAt = System.currentTimeMillis();
        }

        // Getters and Setters
        public String getChannelId() { return channelId; }
        public Platform getPlatform() { return platform; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; this.updatedAt = System.currentTimeMillis(); }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; this.updatedAt = System.currentTimeMillis(); }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public int getMemberCount() { return memberCount; }
        public void setMemberCount(int memberCount) { this.memberCount = memberCount; }
        public long getUpdatedAt() { return updatedAt; }

        /**
         * 是否过期
         */
        public boolean isExpired(long maxAgeMs) {
            return System.currentTimeMillis() - updatedAt > maxAgeMs;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("channel_id", channelId);
            map.put("platform", platform.getValue());
            map.put("name", name);
            map.put("type", type);
            map.put("topic", topic);
            map.put("description", description);
            map.put("member_count", memberCount);
            return map;
        }
    }

    /**
     * 用户信息
     */
    public static class UserInfo {
        private final String userId;
        private final Platform platform;
        private String displayName;
        private String userName;
        private long updatedAt;

        public UserInfo(String userId, Platform platform) {
            this.userId = userId;
            this.platform = platform;
            this.updatedAt = System.currentTimeMillis();
        }

        // Getters and Setters
        public String getUserId() { return userId; }
        public Platform getPlatform() { return platform; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; this.updatedAt = System.currentTimeMillis(); }
        public String getUserName() { return userName; }
        public void setUserName(String userName) { this.userName = userName; }
        public long getUpdatedAt() { return updatedAt; }

        public boolean isExpired(long maxAgeMs) {
            return System.currentTimeMillis() - updatedAt > maxAgeMs;
        }
    }

    // 缓存目录
    private final Map<String, ChannelInfo> channels = new ConcurrentHashMap<>();
    private final Map<String, UserInfo> users = new ConcurrentHashMap<>();

    // 缓存过期时间（默认24小时）
    private long channelCacheMaxAgeMs = 24 * 3600_000L;
    private long userCacheMaxAgeMs = 24 * 3600_000L;

    /**
     * 获取频道信息
     */
    public ChannelInfo getChannel(Platform platform, String channelId) {
        String key = buildKey(platform, channelId);
        ChannelInfo info = channels.get(key);

        if (info != null && info.isExpired(channelCacheMaxAgeMs)) {
            channels.remove(key);
            return null;
        }

        return info;
    }

    /**
     * 获取或创建频道信息
     */
    public ChannelInfo getOrCreateChannel(Platform platform, String channelId) {
        String key = buildKey(platform, channelId);
        return channels.computeIfAbsent(key, k -> new ChannelInfo(channelId, platform));
    }

    /**
     * 更新频道信息
     */
    public void updateChannel(Platform platform, String channelId,
                               String name, String type, String topic) {
        ChannelInfo info = getOrCreateChannel(platform, channelId);
        if (name != null) info.setName(name);
        if (type != null) info.setType(type);
        if (topic != null) info.setTopic(topic);
    }

    /**
     * 获取用户信息
     */
    public UserInfo getUser(Platform platform, String userId) {
        String key = buildKey(platform, userId);
        UserInfo info = users.get(key);

        if (info != null && info.isExpired(userCacheMaxAgeMs)) {
            users.remove(key);
            return null;
        }

        return info;
    }

    /**
     * 更新用户信息
     */
    public void updateUser(Platform platform, String userId, String displayName) {
        String key = buildKey(platform, userId);
        UserInfo info = users.computeIfAbsent(key, k -> new UserInfo(userId, platform));
        if (displayName != null) {
            info.setDisplayName(displayName);
        }
    }

    /**
     * 从 SessionSource 更新频道和用户信息
     */
    public void updateFromSource(SessionSource source) {
        if (source.getChatName() != null) {
            updateChannel(source.getPlatform(), source.getChatId(),
                source.getChatName(), source.getChatType(), source.getChatTopic());
        }
        if (source.getUserName() != null) {
            updateUser(source.getPlatform(), source.getUserId(), source.getUserName());
        }
    }

    /**
     * 获取频道显示名称
     */
    public String getChannelDisplayName(Platform platform, String channelId) {
        ChannelInfo info = getChannel(platform, channelId);
        if (info != null && info.getName() != null) {
            return info.getName();
        }
        return channelId;
    }

    /**
     * 获取用户显示名称
     */
    public String getUserDisplayName(Platform platform, String userId) {
        UserInfo info = getUser(platform, userId);
        if (info != null && info.getDisplayName() != null) {
            return info.getDisplayName();
        }
        return userId;
    }

    /**
     * 列出所有频道
     */
    public List<ChannelInfo> listChannels(Platform platform) {
        return channels.values().stream()
            .filter(c -> c.getPlatform() == platform)
            .toList();
    }

    /**
     * 清除过期缓存
     */
    public int clearExpired() {
        int removed = 0;

        Iterator<Map.Entry<String, ChannelInfo>> chIt = channels.entrySet().iterator();
        while (chIt.hasNext()) {
            if (chIt.next().getValue().isExpired(channelCacheMaxAgeMs)) {
                chIt.remove();
                removed++;
            }
        }

        Iterator<Map.Entry<String, UserInfo>> usIt = users.entrySet().iterator();
        while (usIt.hasNext()) {
            if (usIt.next().getValue().isExpired(userCacheMaxAgeMs)) {
                usIt.remove();
                removed++;
            }
        }

        if (removed > 0) {
            log.debug("Cleared {} expired channel directory entries", removed);
        }
        return removed;
    }

    private String buildKey(Platform platform, String id) {
        return platform.getValue() + ":" + id;
    }

    // Getters and Setters
    public long getChannelCacheMaxAgeMs() { return channelCacheMaxAgeMs; }
    public void setChannelCacheMaxAgeMs(long channelCacheMaxAgeMs) { this.channelCacheMaxAgeMs = channelCacheMaxAgeMs; }
    public long getUserCacheMaxAgeMs() { return userCacheMaxAgeMs; }
    public void setUserCacheMaxAgeMs(long userCacheMaxAgeMs) { this.userCacheMaxAgeMs = userCacheMaxAgeMs; }
}
