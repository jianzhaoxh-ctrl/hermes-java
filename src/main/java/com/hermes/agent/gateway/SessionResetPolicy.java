package com.hermes.agent.gateway;

import java.time.Duration;
import java.util.Set;
import java.util.HashSet;

/**
 * 会话重置策略
 * 
 * 控制会话何时重置（丢失上下文）
 * 
 * 模式：
 * - "daily": 每天在特定小时重置
 * - "idle": N 分钟不活动后重置
 * - "both": 先触发的生效
 * - "none": 永不自动重置
 */
public class SessionResetPolicy {
    private String mode = "both";       // "daily", "idle", "both", "none"
    private int atHour = 4;             // 每日重置的小时（0-23，本地时间）
    private int idleMinutes = 1440;     // 不活动后重置的分钟数（24 小时）
    private boolean notify = true;      // 自动重置时发送通知
    private Set<String> notifyExcludePlatforms = Set.of("api_server", "webhook");

    public SessionResetPolicy() {}

    public SessionResetPolicy(String mode, int atHour, int idleMinutes) {
        this.mode = mode;
        this.atHour = atHour;
        this.idleMinutes = idleMinutes;
    }

    // Getters and Setters
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public int getAtHour() { return atHour; }
    public void setAtHour(int atHour) { this.atHour = atHour; }
    public int getIdleMinutes() { return idleMinutes; }
    public void setIdleMinutes(int idleMinutes) { this.idleMinutes = idleMinutes; }
    public boolean isNotify() { return notify; }
    public void setNotify(boolean notify) { this.notify = notify; }
    public Set<String> getNotifyExcludePlatforms() { return notifyExcludePlatforms; }
    public void setNotifyExcludePlatforms(Set<String> platforms) { this.notifyExcludePlatforms = platforms; }

    /**
     * 检查模式是否有效
     */
    public boolean isValidMode() {
        return "daily".equals(mode) || "idle".equals(mode) || 
               "both".equals(mode) || "none".equals(mode);
    }

    /**
     * 检查是否应该检查空闲超时
     */
    public boolean shouldCheckIdle() {
        return "idle".equals(mode) || "both".equals(mode);
    }

    /**
     * 检查是否应该检查每日重置
     */
    public boolean shouldCheckDaily() {
        return "daily".equals(mode) || "both".equals(mode);
    }

    /**
     * 获取空闲超时时长
     */
    public Duration getIdleDuration() {
        return Duration.ofMinutes(idleMinutes);
    }

    @Override
    public String toString() {
        return String.format("SessionResetPolicy{mode=%s, atHour=%d, idleMinutes=%d}", 
            mode, atHour, idleMinutes);
    }
}
