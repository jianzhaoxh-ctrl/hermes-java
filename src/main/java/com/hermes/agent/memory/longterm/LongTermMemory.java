package com.hermes.agent.memory.longterm;

import java.io.Serializable;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长期记忆实体 — 跨会话持久化的记忆数据模型
 *
 * <p>设计目标：
 * <ul>
 *   <li>全局唯一 ID：mem_{timestamp}_{uuid}</li>
 *   <li>重要性评分：LLM 自动判断（0.0 ~ 1.0）</li>
 *   <li>分类存储：fact | preference | context | lesson | decision</li>
 *   <li>跨时空检索：FTS5 全文 + 向量语义</li>
 *   <li>智能遗忘：基于访问频率 + 重要性</li>
 * </ul>
 *
 * <p>数据流：
 * <pre>
 * Agent 每轮对话 → LongTermMemoryManager.add() → LLM 评分重要性
 *                                              ↓
 *                                          写入 SQLite
 *                                              ↓
 *                               MemoryOrchestrator.prefetchContext()
 *                                              ↓
 *                                   注入 Agent 上下文
 * </pre>
 */
public class LongTermMemory implements Serializable {

    private static final long serialVersionUID = 1L;

    // ═══════════════════════════════════════════════════════════════
    // 核心字段
    // ═══════════════════════════════════════════════════════════════

    /** 记忆 ID，全局唯一 */
    private String id;

    /** 归属用户 ID（"global" 表示全局记忆） */
    private String userId;

    /** 记忆分类 */
    private Category category;

    /** 记忆内容（Markdown 格式） */
    private String content;

    /** 重要性评分（0.0 ~ 1.0，LLM 生成） */
    private double importance;

    /** 是否应自动遗忘 */
    private boolean autoForget;

    /** 向量表示（可选，用于语义搜索） */
    private float[] embedding;

    /** 来源会话 ID */
    private String sourceSessionId;

    /** 来源上下文（为何记住这段记忆） */
    private String sourceContext;

    // ═══════════════════════════════════════════════════════════════════
    // 元数据字段
    // ═══════════════════════════════════════════════════════════════

    /** 创建时间戳 */
    private long createdAt;

    /** 最后访问时间戳 */
    private long lastAccessedAt;

    /** 访问次数 */
    private int accessCount;

    /** 预测遗忘时间戳（艾宾浩斯曲线计算） */
    private Long predictedForgetAt;

    /** 额外元数据（标签、来源工具等） */
    private Map<String, Object> metadata;

    // ═══════════════════════════════════════════════════════════════════════
    // 构造方法
    // ═══════════════════════════════════════════════════════════════

    public LongTermMemory() {
    }

    public LongTermMemory(String id, String userId, Category category, String content,
                       double importance, boolean autoForget, String sourceSessionId,
                       String sourceContext) {
        this.id = id;
        this.userId = userId;
        this.category = category;
        this.content = content;
        this.importance = importance;
        this.autoForget = autoForget;
        this.sourceSessionId = sourceSessionId;
        this.sourceContext = sourceContext;
        this.createdAt = System.currentTimeMillis();
        this.lastAccessedAt = this.createdAt;
        this.accessCount = 0;
        this.metadata = new ConcurrentHashMap<>();
    }

    // ═══════════════════════════════════════════════════════════════
    // 静态工厂方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 创建新记忆（自动生成 ID）
     */
    public static LongTermMemory create(String userId, Category category, String content,
                                    double importance, String sourceSessionId,
                                    String sourceContext) {
        String id = generateId();
        return new LongTermMemory(id, userId, category, content, importance,
                importance < 0.3, sourceSessionId, sourceContext);
    }

    /**
     * 生成全局唯一 ID
     */
    public static String generateId() {
        return "mem_" + System.currentTimeMillis() + "_" + 
               java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    // ═══════════════════════════════════════════════════════════════
    // 业务方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 标记访问（更新访问计数和时间）
     */
    public void markAccessed() {
        this.lastAccessedAt = System.currentTimeMillis();
        this.accessCount++;
    }

    /**
     * 判断是否应该遗忘
     *
     * <p>遗忘条件：
     * <ul>
     *   <li>importance < 0.3 且 access_count < 3</li>
     *   <li>importance < 0.5 且 last_accessed > 30 天</li>
     *   <li>auto_forget = true 且 last_accessed > 7 天</li>
     * </ul>
     */
    public boolean shouldForget() {
        long now = System.currentTimeMillis();
        long daysSinceLastAccess = (now - lastAccessedAt) / (1000 * 60 * 60 * 24);

        if (importance < 0.3 && accessCount < 3) {
            return true;
        }
        if (importance < 0.5 && daysSinceLastAccess > 30) {
            return true;
        }
        if (autoForget && daysSinceLastAccess > 7) {
            return true;
        }
        return false;
    }

    /**
     * 计算遗忘优先级（越小越优先遗忘）
     */
    public double getForgetPriority() {
        // 基于重要性（越低越优先）+ 访问频率（越低越优先）+ 时间（越久越优先）
        double importanceFactor = 1.0 - importance;  // importance 越低，分数越高
        double accessFactor = 1.0 / (1.0 + accessCount);  // accessCount 越低，分数越高
        double timeFactor = (lastAccessedAt - createdAt) / (1000.0 * 60 * 60 * 24 * 30);  // 月为单位
        
        return importanceFactor * 0.5 + accessFactor * 0.3 + Math.min(timeFactor, 1.0) * 0.2;
    }

    // ═══════════════════════════════════════════════════════════════
    // Getter / Setter
    // ═══════════════��═══════════════════════════════════════════════

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public double getImportance() { return importance; }
    public void setImportance(double importance) { this.importance = importance; }

    public boolean isAutoForget() { return autoForget; }
    public void setAutoForget(boolean autoForget) { this.autoForget = autoForget; }

    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }

    public String getSourceSessionId() { return sourceSessionId; }
    public void setSourceSessionId(String sourceSessionId) { this.sourceSessionId = sourceSessionId; }

    public String getSourceContext() { return sourceContext; }
    public void setSourceContext(String sourceContext) { this.sourceContext = sourceContext; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getLastAccessedAt() { return lastAccessedAt; }
    public void setLastAccessedAt(long lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }

    public int getAccessCount() { return accessCount; }
    public void setAccessCount(int accessCount) { this.accessCount = accessCount; }

    public Long getPredictedForgetAt() { return predictedForgetAt; }
    public void setPredictedForgetAt(Long predictedForgetAt) { this.predictedForgetAt = predictedForgetAt; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    // ═══════════════════════════════════════════════════════════════════
    // 分类枚举
    // ═══════════════════════════════════════════════════════════════

    /**
     * 记忆分类
     */
    public enum Category {
        /** 客观事实 */
        FACT("fact"),
        /** 用户偏好 */
        PREFERENCE("preference"),
        /** 工作上下文 */
        CONTEXT("context"),
        /** 经验教训 */
        LESSON("lesson"),
        /** 重要决策 */
        DECISION("decision");

        private final String value;

        Category(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static Category fromValue(String value) {
            for (Category c : values()) {
                if (c.value.equals(value)) {
                    return c;
                }
            }
            return CONTEXT;  // 默认
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 转换方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 转换为 Map（用于数据库存储）
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("user_id", userId);
        map.put("category", category != null ? category.getValue() : null);
        map.put("content", content);
        map.put("importance", importance);
        map.put("auto_forget", autoForget);
        map.put("source_session_id", sourceSessionId);
        map.put("source_context", sourceContext);
        map.put("created_at", createdAt);
        map.put("last_accessed_at", lastAccessedAt);
        map.put("access_count", accessCount);
        map.put("predicted_forget_at", predictedForgetAt);
        // 元数据在Dao层单独处理
        return map;
    }

    /**
     * 从 Map 恢复
     */
    @SuppressWarnings("unchecked")
    public static LongTermMemory fromMap(Map<String, Object> map) {
        if (map == null) return null;
        
        LongTermMemory mem = new LongTermMemory();
        mem.setId((String) map.get("id"));
        mem.setUserId((String) map.get("user_id"));
        mem.setCategory(Category.fromValue((String) map.get("category")));
        mem.setContent((String) map.get("content"));
        mem.setImportance(map.get("importance") != null ? 
                ((Number) map.get("importance")).doubleValue() : 0.5);
        mem.setAutoForget(map.get("auto_forget") != null && 
                (Boolean) map.get("auto_forget"));
        mem.setSourceSessionId((String) map.get("source_session_id"));
        mem.setSourceContext((String) map.get("source_context"));
        mem.setCreatedAt(map.get("created_at") != null ? 
                ((Number) map.get("created_at")).longValue() : System.currentTimeMillis());
        mem.setLastAccessedAt(map.get("last_accessed_at") != null ? 
                ((Number) map.get("last_accessed_at")).longValue() : mem.getCreatedAt());
        mem.setAccessCount(map.get("access_count") != null ? 
                ((Number) map.get("access_count")).intValue() : 0);
        mem.setPredictedForgetAt(map.get("predicted_forget_at") != null ?
                ((Number) map.get("predicted_forget_at")).longValue() : null);
        
        // 元数据
        Object metaObj = map.get("metadata");
        if (metaObj instanceof Map) {
            mem.setMetadata((Map<String, Object>) metaObj);
        }
        
        return mem;
    }

    @Override
    public String toString() {
        return String.format("LongTermMemory{id='%s', category=%s, importance=%.2f, content='%s'}",
                id, category, importance, 
                content != null && content.length() > 50 ? 
                        content.substring(0, 50) + "..." : content);
    }
}