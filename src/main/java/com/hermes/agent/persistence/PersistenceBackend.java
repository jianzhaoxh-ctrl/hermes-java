package com.hermes.agent.persistence;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 持久化后端接口 — 支持 JSON 文件、SQLite、Redis 等多种存储后端。
 *
 * <p>设计目标：
 * <ul>
 *   <li>统一 API 屏蔽底层存储差异</li>
 *   <li>支持会话消息、用户画像、技能记忆等多种数据类型</li>
 *   <li>提供全文搜索能力</li>
 *   <li>异步写入不阻塞主流程</li>
 * </ul>
 *
 * <p>新增特性（v2）：
 * <ul>
 *   <li>会话生命周期管理（创建、结束、重开）</li>
 *   <li>会话标题管理</li>
 *   <li>压缩链支持</li>
 *   <li>Token 计数与计费</li>
 *   <li>增强搜索（来源过滤、上下文窗口）</li>
 * </ul>
 */
public interface PersistenceBackend {

    // ═══════════════════════════════════════════════════════════════════
    // 基础方法
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 后端名称（用于配置选择）
     */
    String name();

    /**
     * 检查后端是否可用
     */
    boolean isAvailable();

    /**
     * 初始化后端（创建表、连接池等）
     */
    void initialize();

    /**
     * 关闭后端（释放资源）
     */
    void shutdown();

    // ═══════════════════════════════════════════════════════════════════
    // 会话消息
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 保存消息到指定会话
     */
    void saveMessage(String sessionId, Map<String, Object> message);

    /**
     * 异步保存消息
     */
    void saveMessageAsync(String sessionId, Map<String, Object> message);

    /**
     * 获取会话历史消息
     */
    List<Map<String, Object>> getSessionMessages(String sessionId);

    /**
     * 获取会话最近 N 条消息
     */
    List<Map<String, Object>> getSessionMessages(String sessionId, int limit);

    /**
     * 获取所有会话 ID
     */
    List<String> getAllSessionIds();

    /**
     * 清除指定会话
     */
    void clearSession(String sessionId);

    // ═══════════════════════════════════════════════════════════════════
    // 用户画像
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 保存用户画像
     */
    void saveUserProfile(String userId, Map<String, Object> profile);

    /**
     * 获取用户画像
     */
    Optional<Map<String, Object>> getUserProfile(String userId);

    /**
     * 获取所有用户画像
     */
    Map<String, Map<String, Object>> getAllUserProfiles();

    // ═══════════════════════════════════════════════════════════════════
    // 技能记忆
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 保存技能内容
     */
    void saveSkillMemory(String skillName, String content);

    /**
     * 获取技能历史记录
     */
    List<String> getSkillMemory(String skillName);

    /**
     * 获取所有技能记忆
     */
    Map<String, List<String>> getAllSkillMemories();

    // ═══════════════════════════════════════════════════════════════════
    // 全文搜索
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 搜索消息内容
     *
     * @param query 搜索关键词
     * @param sessionId 可选的会话 ID 限定
     * @param limit 返回结果上限
     * @return 搜索结果列表
     */
    List<SearchResult> searchMessages(String query, String sessionId, int limit);

    /**
     * 搜索结果数据类
     */
    record SearchResult(
            String messageId,
            String sessionId,
            String role,
            String snippet,  // 摘要片段
            double score,
            long timestamp,
            List<Map<String, String>> context  // 前后各 1 条上下文消息
    ) {
        /**
         * 兼容旧构造（无上下文）
         */
        public SearchResult(String messageId, String sessionId, String role,
                            String snippet, double score, long timestamp) {
            this(messageId, sessionId, role, snippet, score, timestamp, Collections.emptyList());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("messageId", messageId);
            map.put("sessionId", sessionId);
            map.put("role", role);
            map.put("snippet", snippet);
            map.put("score", String.format("%.4f", score));
            map.put("timestamp", timestamp);
            if (context != null && !context.isEmpty()) {
                map.put("context", context);
            }
            return map;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 会话管理增强（P1）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 增强版会话列表 — 包含预览和最后活跃时间
     *
     * @param source     来源过滤（可选）
     * @param limit      返回条数上限
     * @param offset     偏移量
     * @param projectCompressionTips 是否投影压缩链到最新续接
     * @return 会话列表，每个条目包含 preview 和 lastActive 字段
     */
    List<Map<String, Object>> listSessionsRich(String source, int limit, int offset,
                                                 boolean projectCompressionTips);

    /**
     * 删除会话 — 子会话孤儿化处理
     *
     * @param sessionId 会话 ID
     * @return true 如果会话存在并已删除
     */
    boolean deleteSession(String sessionId);

    /**
     * 按时间清理已结束的旧会话
     *
     * @param olderThanDays 清理多少天前的会话
     * @param source        来源过滤（可选，null 表示所有来源）
     * @return 被清理的会话数量
     */
    int pruneSessions(int olderThanDays, String source);
}
