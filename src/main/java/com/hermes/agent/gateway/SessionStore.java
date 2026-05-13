package com.hermes.agent.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话存储
 * 
 * 管理会话的存储和检索。
 * 追踪会话键到会话 ID 的映射，以及会话元数据。
 */
@Component
public class SessionStore {
    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    private final Map<String, SessionEntry> entries = new ConcurrentHashMap<>();
    private final GatewayConfig config;

    public SessionStore(GatewayConfig config) {
        this.config = config;
    }

    /**
     * 获取或创建会话
     * 
     * @param source 消息来源
     * @param forceNew 强制创建新会话
     * @return 会话条目
     */
    public SessionEntry getOrCreateSession(SessionSource source, boolean forceNew) {
        String sessionKey = source.buildSessionKey(
            config.isGroupSessionsPerUser(),
            config.isThreadSessionsPerUser()
        );
        Instant now = Instant.now();

        SessionEntry existing = entries.get(sessionKey);
        if (existing != null && !forceNew) {
            // 检查是否需要重置
            String resetReason = shouldReset(existing, source);
            if (resetReason == null) {
                existing.setUpdatedAt(now);
                return existing;
            }
            // 会话过期，创建新会话
            log.info("Session {} expired due to {}", sessionKey, resetReason);
        }

        // 创建新会话
        String sessionId = generateSessionId(now);
        SessionEntry entry = new SessionEntry(
            sessionKey,
            sessionId,
            now,
            now,
            source
        );

        entries.put(sessionKey, entry);
        log.debug("Created new session: {} -> {}", sessionKey, sessionId);
        
        return entry;
    }

    /**
     * 更新会话
     */
    public void updateSession(String sessionKey) {
        SessionEntry entry = entries.get(sessionKey);
        if (entry != null) {
            entry.setUpdatedAt(Instant.now());
        }
    }

    /**
     * 重置会话
     */
    public SessionEntry resetSession(String sessionKey) {
        SessionEntry oldEntry = entries.get(sessionKey);
        if (oldEntry == null) {
            return null;
        }

        Instant now = Instant.now();
        String sessionId = generateSessionId(now);
        
        SessionEntry newEntry = new SessionEntry(
            sessionKey,
            sessionId,
            now,
            now,
            oldEntry.getOrigin()
        );

        entries.put(sessionKey, newEntry);
        log.info("Reset session {} -> {}", sessionKey, sessionId);
        
        return newEntry;
    }

    /**
     * 暂停会话（标记为需要重置）
     */
    public boolean suspendSession(String sessionKey) {
        SessionEntry entry = entries.get(sessionKey);
        if (entry != null) {
            entry.setSuspended(true);
            log.info("Suspended session: {}", sessionKey);
            return true;
        }
        return false;
    }

    /**
     * 列出所有会话
     */
    public List<SessionEntry> listSessions(Integer activeMinutes) {
        Instant cutoff = activeMinutes != null 
            ? Instant.now().minus(activeMinutes, ChronoUnit.MINUTES)
            : null;

        return entries.values().stream()
            .filter(e -> cutoff == null || e.getUpdatedAt().isAfter(cutoff))
            .sorted(Comparator.comparing(SessionEntry::getUpdatedAt).reversed())
            .toList();
    }

    /**
     * 获取会话
     */
    public SessionEntry getSession(String sessionKey) {
        return entries.get(sessionKey);
    }

    /**
     * 检查是否有任何会话
     */
    public boolean hasAnySessions() {
        return !entries.isEmpty();
    }

    // ========== 私有方法 ==========

    private String generateSessionId(Instant now) {
        return String.format("%s_%s",
            now.toString().substring(0, 19).replace("T", "_").replace(":", "").replace("-", ""),
            UUID.randomUUID().toString().substring(0, 8)
        );
    }

    private String shouldReset(SessionEntry entry, SessionSource source) {
        // 检查暂停标志
        if (entry.isSuspended()) {
            return "suspended";
        }

        SessionResetPolicy policy = config.getResetPolicy(
            source.getPlatform(),
            source.getChatType()
        );

        if ("none".equals(policy.getMode())) {
            return null;
        }

        Instant now = Instant.now();

        // 检查空闲超时
        if (policy.shouldCheckIdle()) {
            Instant idleDeadline = entry.getUpdatedAt().plus(policy.getIdleMinutes(), ChronoUnit.MINUTES);
            if (now.isAfter(idleDeadline)) {
                return "idle";
            }
        }

        // 检查每日重置
        if (policy.shouldCheckDaily()) {
            LocalDateTime nowLocal = LocalDateTime.now();
            LocalDateTime todayReset = nowLocal
                .withHour(policy.getAtHour())
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
            
            if (nowLocal.getHour() < policy.getAtHour()) {
                todayReset = todayReset.minusDays(1);
            }

            LocalDateTime entryTime = LocalDateTime.ofInstant(entry.getUpdatedAt(), 
                java.time.ZoneId.systemDefault());
            
            if (entryTime.isBefore(todayReset)) {
                return "daily";
            }
        }

        return null;
    }
}
