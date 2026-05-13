package com.hermes.agent.gateway;

import java.time.Instant;
import java.util.Map;
import java.util.HashMap;

/**
 * 会话条目
 * 
 * 会话存储中的条目，映射会话键到当前会话 ID 和元数据。
 */
public class SessionEntry {
    private final String sessionKey;
    private final String sessionId;
    private final Instant createdAt;
    private Instant updatedAt;

    // 来源元数据（用于投递路由）
    private final SessionSource origin;

    // 显示元数据
    private String displayName;
    private Platform platform;
    private String chatType = "dm";

    // Token 追踪
    private int inputTokens = 0;
    private int outputTokens = 0;
    private int totalTokens = 0;
    private double estimatedCostUsd = 0.0;

    // 自动重置标志
    private boolean wasAutoReset = false;
    private String autoResetReason;
    private boolean suspended = false;

    public SessionEntry(
        String sessionKey,
        String sessionId,
        Instant createdAt,
        Instant updatedAt,
        SessionSource origin
    ) {
        this.sessionKey = sessionKey;
        this.sessionId = sessionId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.origin = origin;
        if (origin != null) {
            this.platform = origin.getPlatform();
            this.chatType = origin.getChatType();
            this.displayName = origin.getChatName();
        }
    }

    // Getters
    public String getSessionKey() { return sessionKey; }
    public String getSessionId() { return sessionId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public SessionSource getOrigin() { return origin; }
    public String getDisplayName() { return displayName; }
    public Platform getPlatform() { return platform; }
    public String getChatType() { return chatType; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public int getTotalTokens() { return totalTokens; }
    public double getEstimatedCostUsd() { return estimatedCostUsd; }
    public boolean isWasAutoReset() { return wasAutoReset; }
    public String getAutoResetReason() { return autoResetReason; }
    public boolean isSuspended() { return suspended; }

    // Setters
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }
    public void setEstimatedCostUsd(double estimatedCostUsd) { this.estimatedCostUsd = estimatedCostUsd; }
    public void setWasAutoReset(boolean wasAutoReset) { this.wasAutoReset = wasAutoReset; }
    public void setAutoResetReason(String autoResetReason) { this.autoResetReason = autoResetReason; }
    public void setSuspended(boolean suspended) { this.suspended = suspended; }

    /**
     * 添加 token 统计
     */
    public void addTokens(int input, int output) {
        this.inputTokens += input;
        this.outputTokens += output;
        this.totalTokens += input + output;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("session_key", sessionKey);
        map.put("session_id", sessionId);
        map.put("created_at", createdAt.toString());
        map.put("updated_at", updatedAt.toString());
        map.put("display_name", displayName);
        map.put("platform", platform != null ? platform.getValue() : null);
        map.put("chat_type", chatType);
        map.put("input_tokens", inputTokens);
        map.put("output_tokens", outputTokens);
        map.put("total_tokens", totalTokens);
        map.put("estimated_cost_usd", estimatedCostUsd);
        map.put("suspended", suspended);
        if (origin != null) {
            map.put("origin", origin.toMap());
        }
        return map;
    }
}
