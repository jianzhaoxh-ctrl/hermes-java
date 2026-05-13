package com.hermes.agent.memory.longterm;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 增强版会话摘要 — 13 字段结构化摘要 + 跨时空能力
 *
 * <p>设计目标：
 * <ul>
 *   <li>结构化摘要：13 个关键字段，便于后续查询和恢复</li>
 *   <li>跨时空追踪：parent_session_id 建立会话链</li>
 *   <li>重要性评分：LLM 自动判断会话重要性</li>
 *   <li>记忆关联：linked_memory_ids 关联长期记忆</li>
 * </ul>
 *
 * <p>13 字段说明：
 * <ol>
 *   <li>active_task — 当前任务描述</li>
 *   <li>goal — 用户目标/意图</li>
 *   <li>constraints — 约束条件</li>
 *   <li>completed_actions — 已完成动作</li>
 *   <li>active_state — 活跃状态</li>
 *   <li>in_progress — 进行中的工作</li>
 *   <li>blocked — 阻塞项</li>
 *   <li>key_decisions — 关键决策</li>
 *   <li>resolved_questions — 已解决问题</li>
 *   <li>pending_user_asks — 待用户确认项</li>
 *   <li>relevant_files — 相关文件</li>
 *   <li>remaining_work — 剩余工作</li>
 *   <li>critical_context — 关键上下文</li>
 * </ol>
 */
public class EnhancedSessionSummary {

    // ═══════════════════════════════════════════════════════════════
    // 基础字段
    // ═══════════════════════════════════════════════════════════════

    /** 会话 ID */
    private String sessionId;

    /** 父会话 ID（用于建立会话链） */
    private String parentSessionId;

    /** 会话标题 */
    private String title;

    /** 创建时间 */
    private long createdAt;

    /** 最后活跃时间 */
    private long lastActiveAt;

    // ═══════════════════════════════════════════════════════════════
    // 13 字段结构化摘要
    // ═══════════════════════════════════════════════════════════════

    /** 1. 当前任务描述 */
    private String activeTask;

    /** 2. 用户目标/意图 */
    private String goal;

    /** 3. 约束条件 */
    private String constraints;

    /** 4. 已完成动作 */
    private String completedActions;

    /** 5. 活跃状态 */
    private String activeState;

    /** 6. 进行中的工作 */
    private String inProgress;

    /** 7. 阻塞项 */
    private String blocked;

    /** 8. 关键决策 */
    private String keyDecisions;

    /** 9. 已解决问题 */
    private String resolvedQuestions;

    /** 10. 待用户确认项 */
    private String pendingUserAsks;

    /** 11. 相关文件 */
    private String relevantFiles;

    /** 12. 剩余工作 */
    private String remainingWork;

    /** 13. 关键上下文 */
    private String criticalContext;

    // ═══════════════════════════════════════════════════════════════
    // 跨时空增强字段
    // ═══════════════════════════════════════════════════════════════

    /** 重要性评分（0.0 ~ 1.0） */
    private double importance;

    /** 关联的长期记忆 ID 列表 */
    private List<String> linkedMemoryIds;

    /** 关联的会话 ID 列表 */
    private List<String> linkedSessionIds;

    /** 是否已提取到长期记忆 */
    private boolean extractedFromLongTerm;

    // ═══════════════════════════════════════════════════════════════════
    // 构造方法
    // ═══════════════════════════════════════════════════════════════

    public EnhancedSessionSummary() {
        this.createdAt = System.currentTimeMillis();
        this.lastActiveAt = this.createdAt;
        this.linkedMemoryIds = new ArrayList<>();
        this.linkedSessionIds = new ArrayList<>();
        this.importance = 0.5;
        this.extractedFromLongTerm = false;
    }

    public EnhancedSessionSummary(String sessionId) {
        this();
        this.sessionId = sessionId;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 静态工厂方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从文本摘要创建（LLM 解析）
     */
    public static EnhancedSessionSummary fromText(String sessionId, String summaryText) {
        EnhancedSessionSummary summary = new EnhancedSessionSummary(sessionId);
        summary.parseSummaryText(summaryText);
        return summary;
    }

    /**
     * 从 Map 恢复
     */
    @SuppressWarnings("unchecked")
    public static EnhancedSessionSummary fromMap(Map<String, Object> map) {
        if (map == null) return null;

        EnhancedSessionSummary s = new EnhancedSessionSummary();
        s.setSessionId((String) map.get("session_id"));
        s.setParentSessionId((String) map.get("parent_session_id"));
        s.setTitle((String) map.get("title"));
        s.setCreatedAt(map.get("created_at") != null ?
                ((Number) map.get("created_at")).longValue() : System.currentTimeMillis());
        s.setLastActiveAt(map.get("last_active_at") != null ?
                ((Number) map.get("last_active_at")).longValue() : s.getCreatedAt());

        // 13 字段
        s.setActiveTask((String) map.get("active_task"));
        s.setGoal((String) map.get("goal"));
        s.setConstraints((String) map.get("constraints"));
        s.setCompletedActions((String) map.get("completed_actions"));
        s.setActiveState((String) map.get("active_state"));
        s.setInProgress((String) map.get("in_progress"));
        s.setBlocked((String) map.get("blocked"));
        s.setKeyDecisions((String) map.get("key_decisions"));
        s.setResolvedQuestions((String) map.get("resolved_questions"));
        s.setPendingUserAsks((String) map.get("pending_user_asks"));
        s.setRelevantFiles((String) map.get("relevant_files"));
        s.setRemainingWork((String) map.get("remaining_work"));
        s.setCriticalContext((String) map.get("critical_context"));

        // 跨时空字段
        s.setImportance(map.get("importance") != null ?
                ((Number) map.get("importance")).doubleValue() : 0.5);
        s.setExtractedFromLongTerm(map.get("extracted_from_long_term") != null &&
                (Boolean) map.get("extracted_from_long_term"));

        // 列表字段
        Object memIds = map.get("linked_memory_ids");
        if (memIds instanceof String && !((String) memIds).isEmpty()) {
            s.setLinkedMemoryIds(Arrays.asList(((String) memIds).split(",")));
        } else if (memIds instanceof List) {
            s.setLinkedMemoryIds((List<String>) memIds);
        }

        Object sessIds = map.get("linked_session_ids");
        if (sessIds instanceof String && !((String) sessIds).isEmpty()) {
            s.setLinkedSessionIds(Arrays.asList(((String) sessIds).split(",")));
        } else if (sessIds instanceof List) {
            s.setLinkedSessionIds((List<String>) sessIds);
        }

        return s;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 业务方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 解析 LLM 生成的摘要文本
     *
     * <p>格式示例：
     * <pre>
     * ## 当前任务
     * xxx
     * ## 用户目标
     * xxx
     * ...
     * </pre>
     */
    public void parseSummaryText(String text) {
        if (text == null || text.isBlank()) return;

        // 简单的 Markdown 标题解析
        String[] sections = text.split("##\\s*");
        for (String section : sections) {
            String[] lines = section.trim().split("\n", 2);
            if (lines.length < 2) continue;

            String heading = lines[0].trim().toLowerCase();
            String content = lines.length > 1 ? lines[1].trim() : "";

            // 匹配 13 字段
            if (heading.contains("任务") || heading.contains("task")) {
                activeTask = content;
            } else if (heading.contains("目标") || heading.contains("goal")) {
                goal = content;
            } else if (heading.contains("约束") || heading.contains("constraint")) {
                constraints = content;
            } else if (heading.contains("完成") || heading.contains("completed")) {
                completedActions = content;
            } else if (heading.contains("状态") || heading.contains("state")) {
                activeState = content;
            } else if (heading.contains("进行") || heading.contains("progress")) {
                inProgress = content;
            } else if (heading.contains("阻塞") || heading.contains("blocked")) {
                blocked = content;
            } else if (heading.contains("决策") || heading.contains("decision")) {
                keyDecisions = content;
            } else if (heading.contains("解决") || heading.contains("resolved")) {
                resolvedQuestions = content;
            } else if (heading.contains("待确认") || heading.contains("pending")) {
                pendingUserAsks = content;
            } else if (heading.contains("文件") || heading.contains("file")) {
                relevantFiles = content;
            } else if (heading.contains("剩余") || heading.contains("remaining")) {
                remainingWork = content;
            } else if (heading.contains("关键") || heading.contains("critical")) {
                criticalContext = content;
            }
        }
    }

    /**
     * 生成格式化的摘要文本
     */
    public String toFormattedText() {
        StringBuilder sb = new StringBuilder();

        if (activeTask != null && !activeTask.isBlank())
            sb.append("## 当前任务\n").append(activeTask).append("\n\n");
        if (goal != null && !goal.isBlank())
            sb.append("## 用户目标\n").append(goal).append("\n\n");
        if (constraints != null && !constraints.isBlank())
            sb.append("## 约束条件\n").append(constraints).append("\n\n");
        if (completedActions != null && !completedActions.isBlank())
            sb.append("## 已完成动作\n").append(completedActions).append("\n\n");
        if (activeState != null && !activeState.isBlank())
            sb.append("## 当前状态\n").append(activeState).append("\n\n");
        if (inProgress != null && !inProgress.isBlank())
            sb.append("## 进行中\n").append(inProgress).append("\n\n");
        if (blocked != null && !blocked.isBlank())
            sb.append("## 阻塞项\n").append(blocked).append("\n\n");
        if (keyDecisions != null && !keyDecisions.isBlank())
            sb.append("## 关键决策\n").append(keyDecisions).append("\n\n");
        if (resolvedQuestions != null && !resolvedQuestions.isBlank())
            sb.append("## 已解决问题\n").append(resolvedQuestions).append("\n\n");
        if (pendingUserAsks != null && !pendingUserAsks.isBlank())
            sb.append("## 待确认\n").append(pendingUserAsks).append("\n\n");
        if (relevantFiles != null && !relevantFiles.isBlank())
            sb.append("## 相关文件\n").append(relevantFiles).append("\n\n");
        if (remainingWork != null && !remainingWork.isBlank())
            sb.append("## 剩余工作\n").append(remainingWork).append("\n\n");
        if (criticalContext != null && !criticalContext.isBlank())
            sb.append("## 关键上下文\n").append(criticalContext).append("\n\n");

        return sb.toString();
    }

    /**
     * 转换为 Map（用于数据库存储）
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("session_id", sessionId);
        map.put("parent_session_id", parentSessionId);
        map.put("title", title);
        map.put("created_at", createdAt);
        map.put("last_active_at", lastActiveAt);

        // 13 字段
        map.put("active_task", activeTask);
        map.put("goal", goal);
        map.put("constraints", constraints);
        map.put("completed_actions", completedActions);
        map.put("active_state", activeState);
        map.put("in_progress", inProgress);
        map.put("blocked", blocked);
        map.put("key_decisions", keyDecisions);
        map.put("resolved_questions", resolvedQuestions);
        map.put("pending_user_asks", pendingUserAsks);
        map.put("relevant_files", relevantFiles);
        map.put("remaining_work", remainingWork);
        map.put("critical_context", criticalContext);

        // 跨时空字段
        map.put("importance", importance);
        map.put("linked_memory_ids", linkedMemoryIds != null ?
                String.join(",", linkedMemoryIds) : "");
        map.put("linked_session_ids", linkedSessionIds != null ?
                String.join(",", linkedSessionIds) : "");
        map.put("extracted_from_long_term", extractedFromLongTerm);

        return map;
    }

    /**
     * 添加关联的记忆 ID
     */
    public void addLinkedMemoryId(String memoryId) {
        if (linkedMemoryIds == null) {
            linkedMemoryIds = new ArrayList<>();
        }
        if (!linkedMemoryIds.contains(memoryId)) {
            linkedMemoryIds.add(memoryId);
        }
    }

    /**
     * 添加关联的会话 ID
     */
    public void addLinkedSessionId(String sessionId) {
        if (linkedSessionIds == null) {
            linkedSessionIds = new ArrayList<>();
        }
        if (!linkedSessionIds.contains(sessionId)) {
            linkedSessionIds.add(sessionId);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Getter / Setter
    // ═══════════════════════════════════════════════════════════════

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getParentSessionId() { return parentSessionId; }
    public void setParentSessionId(String parentSessionId) { this.parentSessionId = parentSessionId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(long lastActiveAt) { this.lastActiveAt = lastActiveAt; }

    public String getActiveTask() { return activeTask; }
    public void setActiveTask(String activeTask) { this.activeTask = activeTask; }

    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }

    public String getConstraints() { return constraints; }
    public void setConstraints(String constraints) { this.constraints = constraints; }

    public String getCompletedActions() { return completedActions; }
    public void setCompletedActions(String completedActions) { this.completedActions = completedActions; }

    public String getActiveState() { return activeState; }
    public void setActiveState(String activeState) { this.activeState = activeState; }

    public String getInProgress() { return inProgress; }
    public void setInProgress(String inProgress) { this.inProgress = inProgress; }

    public String getBlocked() { return blocked; }
    public void setBlocked(String blocked) { this.blocked = blocked; }

    public String getKeyDecisions() { return keyDecisions; }
    public void setKeyDecisions(String keyDecisions) { this.keyDecisions = keyDecisions; }

    public String getResolvedQuestions() { return resolvedQuestions; }
    public void setResolvedQuestions(String resolvedQuestions) { this.resolvedQuestions = resolvedQuestions; }

    public String getPendingUserAsks() { return pendingUserAsks; }
    public void setPendingUserAsks(String pendingUserAsks) { this.pendingUserAsks = pendingUserAsks; }

    public String getRelevantFiles() { return relevantFiles; }
    public void setRelevantFiles(String relevantFiles) { this.relevantFiles = relevantFiles; }

    public String getRemainingWork() { return remainingWork; }
    public void setRemainingWork(String remainingWork) { this.remainingWork = remainingWork; }

    public String getCriticalContext() { return criticalContext; }
    public void setCriticalContext(String criticalContext) { this.criticalContext = criticalContext; }

    public double getImportance() { return importance; }
    public void setImportance(double importance) { this.importance = importance; }

    public List<String> getLinkedMemoryIds() { return linkedMemoryIds; }
    public void setLinkedMemoryIds(List<String> linkedMemoryIds) { this.linkedMemoryIds = linkedMemoryIds; }

    public List<String> getLinkedSessionIds() { return linkedSessionIds; }
    public void setLinkedSessionIds(List<String> linkedSessionIds) { this.linkedSessionIds = linkedSessionIds; }

    public boolean isExtractedFromLongTerm() { return extractedFromLongTerm; }
    public void setExtractedFromLongTerm(boolean extractedFromLongTerm) { this.extractedFromLongTerm = extractedFromLongTerm; }

    @Override
    public String toString() {
        return String.format("EnhancedSessionSummary{id='%s', title='%s', importance=%.2f}",
                sessionId, title, importance);
    }
}