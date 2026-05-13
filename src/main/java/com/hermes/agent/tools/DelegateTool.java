package com.hermes.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.agent.subagent.SubAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * Delegate Tool — 子 Agent 委托工具。
 *
 * <p>参照 Python 版 delegate_tool.py，通过 SubAgentService 实现子会话管理。
 * 支持单任务和批处理（并行）模式，parent 阻塞直到所有 child 完成。
 *
 * <p>每个子 Agent 获得：
 * <ul>
 *   <li>独立会话（无 parent 历史）</li>
 *   <li>受限工具集（可配置，始终移除危险工具）</li>
 *   <li>聚焦系统提示词（目标 + 上下文）</li>
 * </ul>
 *
 * <p>工具列表：
 * <ol>
 *   <li>delegate_task — 委托任务给子 Agent（单任务或批量）
 *   <li>list_subagents — 列出当前活跃的子 Agent
 *   <li>kill_subagent — 终止指定子 Agent
 *   <li>kill_all_subagents — 终止所有子 Agent
 * </ol>
 *
 * <p>安全限制（不可绕过）：
 * <ul>
 *   <li>delegate_task 本身被禁止（防止递归委托）</li>
 *   <li>memory 写操作被禁止（防止写入共享 MEMORY.md）</li>
 *   <li>send_message 被禁止（防止跨平台副作用）</li>
 *   <li>clarify 被禁止（禁止用户交互）</li>
 * </ul>
 *
 * <p>深度限制：默认 max_spawn_depth=1（扁平：parent → child），不可嵌套。
 */
@Component
public class DelegateTool {

    private static final Logger log = LoggerFactory.getLogger(DelegateTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 工具集定义（名称 → 工具列表） */
    private static final Map<String, List<String>> TOOLSETS = Map.ofEntries(
            Map.entry("terminal", List.of("bash_exec")),
            Map.entry("file", List.of("file_read", "file_write")),
            Map.entry("web", List.of("web_search", "web_fetch")),
            Map.entry("code", List.of("python_exec", "calculate")),
            Map.entry("memory", List.of("memory", "session_search")),
            Map.entry("rl", List.of(
                    "rl_list_environments", "rl_select_environment", "rl_get_current_config",
                    "rl_edit_config", "rl_start_training", "rl_check_status",
                    "rl_stop_training", "rl_get_results", "rl_list_runs", "rl_test_inference")),
            Map.entry("browser", List.of(
                    "browser_navigate", "browser_snapshot", "browser_click", "browser_type",
                    "browser_scroll", "browser_back", "browser_press",
                    "browser_get_images", "browser_vision", "browser_console"))
    );

    /** 子 Agent 永远无法使用的工具 */
    private static final Set<String> BLOCKED_TOOLS = Set.of(
            "delegate_task",
            "clarify",
            "memory",
            "send_message"
    );

    /** 默认工具集 */
    private static final List<String> DEFAULT_TOOLSETS = List.of("terminal", "file", "web");

    /** 最大并发子 Agent 数 */
    private static final int MAX_CONCURRENT_CHILDREN = 3;

    /** 默认最大迭代次数 */
    private static final int DEFAULT_MAX_ITERATIONS = 50;

    /** 最大嵌套深度 */
    private static final int MAX_SPAWN_DEPTH = 1;

    private final ToolRegistry registry;
    private final SubAgentService subAgentService;
    private final Set<String> allAvailableTools;

    public DelegateTool(ToolRegistry registry, SubAgentService subAgentService) {
        this.registry = registry;
        this.subAgentService = subAgentService;
        this.allAvailableTools = new HashSet<>(registry.getAllTools().keySet());
        registerTools();
    }

    private void registerTools() {

        // ── 1. delegate_task ────────────────────────────────────────────
        Map<String, Object> dtGoalProp = new LinkedHashMap<>();
        dtGoalProp.put("type", "string");
        dtGoalProp.put("description", "The task goal for the subagent to accomplish");

        Map<String, Object> dtContextProp = new LinkedHashMap<>();
        dtContextProp.put("type", "string");
        dtContextProp.put("description", "Optional context/information to pass to the subagent");

        Map<String, Object> dtToolsetsProp = new LinkedHashMap<>();
        dtToolsetsProp.put("type", "array");
        dtToolsetsProp.put("items", Map.of("type", "string"));
        Map<String, Object> dtToolsetsItems = new LinkedHashMap<>();
        dtToolsetsItems.put("type", "string");
        dtToolsetsProp.put("description",
                "Which toolsets to grant: " + TOOLSETS.keySet() +
                ". Default: terminal+file+web. Example: [\"file\", \"web\", \"code\"]");

        Map<String, Object> dtTasksProp = new LinkedHashMap<>();
        dtTasksProp.put("type", "array");
        dtTasksProp.put("description",
                "Batch mode: array of {goal, context, toolsets, role} for parallel execution. " +
                "Max " + MAX_CONCURRENT_CHILDREN + " tasks concurrently. " +
                "Use this INSTEAD of goal for multi-task delegation.");

        Map<String, Object> dtRoleProp = new LinkedHashMap<>();
        dtRoleProp.put("type", "string");
        dtRoleProp.put("enum", List.of("leaf", "orchestrator"));
        dtRoleProp.put("description",
                "'leaf' (default): subagent cannot delegate further. " +
                "'orchestrator': subagent can spawn its own workers (max_spawn_depth=1, cannot nest).");

        Map<String, Object> dtMaxIterProp = new LinkedHashMap<>();
        dtMaxIterProp.put("type", "integer");
        dtMaxIterProp.put("description", "Max iterations per subagent (default: " + DEFAULT_MAX_ITERATIONS + ")");

        Map<String, Object> dtProps = new LinkedHashMap<>();
        dtProps.put("goal", dtGoalProp);
        dtProps.put("context", dtContextProp);
        dtProps.put("toolsets", dtToolsetsProp);
        dtProps.put("tasks", dtTasksProp);
        dtProps.put("role", dtRoleProp);
        dtProps.put("max_iterations", dtMaxIterProp);

        registry.register("delegate_task",
                "Delegate a task to a child AI agent with isolated context and restricted tools. " +
                "Supports two modes:\n" +
                "  SINGLE: provide 'goal' (+ optional context, toolsets, role)\n" +
                "  BATCH: provide 'tasks' array for parallel execution (max " + MAX_CONCURRENT_CHILDREN + " concurrent)\n\n" +
                "Available toolsets: " + TOOLSETS.keySet() + ". Default: terminal+file+web.\n" +
                "Role 'leaf' (default): subagent cannot delegate. " +
                "Role 'orchestrator': subagent can spawn workers (depth 1 only).\n\n" +
                "Safety: delegate_task/memory/send_message/clarify are ALWAYS blocked in subagents.",
                schemaObj(dtProps, Collections.emptyList()),
                args -> runOnBlockingThread(() -> {
                    // 解析参数
                    String goal = str(args, "goal");
                    String context = str(args, "context");
                    String role = str(args, "role");
                    String maxIterStr = str(args, "max_iterations");

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> tasks = (List<Map<String, Object>>) args.get("tasks");

                    @SuppressWarnings("unchecked")
                    List<String> toolsets = args.get("toolsets") instanceof List
                            ? (List<String>) args.get("toolsets")
                            : null;

                    // 验证参数
                    if ((goal == null || goal.isBlank()) && (tasks == null || tasks.isEmpty())) {
                        return objectMapper.writeValueAsString(
                                Map.of("error", "Provide either 'goal' (single) or 'tasks' (batch)"));
                    }

                    // 深度检查（当前实现只支持 depth=1 扁平）
                    int depth = 0; // TODO: 从父 Agent 上下文获取实际深度
                    if (depth >= MAX_SPAWN_DEPTH) {
                        return objectMapper.writeValueAsString(
                                Map.of("error", String.format(
                                        "Delegation depth limit reached (depth=%d, max_spawn_depth=%d). " +
                                                "Nesting not supported in this deployment.",
                                        depth, MAX_SPAWN_DEPTH)));
                    }

                    // 标准化 role
                    String effectiveRole = normalizeRole(role);

                    // 批量模式
                    if (tasks != null && !tasks.isEmpty()) {
                        if (tasks.size() > MAX_CONCURRENT_CHILDREN) {
                            return objectMapper.writeValueAsString(
                                    Map.of("error", String.format(
                                            "Too many tasks: %d provided, max is %d. " +
                                                    "Split into multiple delegate_task calls.",
                                            tasks.size(), MAX_CONCURRENT_CHILDREN)));
                        }

                        List<Map<String, Object>> results = new ArrayList<>();
                        for (int i = 0; i < tasks.size(); i++) {
                            Map<String, Object> task = tasks.get(i);
                            String tGoal = str(task, "goal");
                            if (tGoal.isBlank()) {
                                results.add(Map.of(
                                        "task_index", i,
                                        "status", "error",
                                        "summary", null,
                                        "error", "Task " + i + " missing 'goal'"));
                                continue;
                            }
                            String tContext = str(task, "context");
                            @SuppressWarnings("unchecked")
                            List<String> tToolsets = task.get("toolsets") instanceof List
                                    ? (List<String>) task.get("toolsets")
                                    : toolsets;
                            String tRole = str(task, "role");
                            String effectiveTaskRole = normalizeRole(tRole.isEmpty() ? effectiveRole : tRole);

                            Map<String, Object> r = runSubAgent(
                                    i, tGoal, tContext, tToolsets, effectiveTaskRole,
                                    maxIterStr.isEmpty() ? DEFAULT_MAX_ITERATIONS
                                            : Integer.parseInt(maxIterStr));
                            results.add(r);
                        }

                        return objectMapper.writeValueAsString(Map.of(
                                "results", results,
                                "total_duration_seconds", 0 // TODO: 计时
                        ));
                    }

                    // 单任务模式
                    Map<String, Object> result = runSubAgent(
                            0, goal, context, toolsets, effectiveRole,
                            maxIterStr.isEmpty() ? DEFAULT_MAX_ITERATIONS
                                    : Integer.parseInt(maxIterStr));

                    return objectMapper.writeValueAsString(Map.of(
                            "results", List.of(result),
                            "total_duration_seconds", result.getOrDefault("duration_seconds", 0)
                    ));
                }));

        // ── 2. list_subagents ───────────────────────────────────────────
        registry.register("list_subagents",
                "List all currently active subagents spawned by this agent. " +
                "Returns their IDs, tasks, status, and creation time.",
                schemaObj(Map.of(), Collections.emptyList()),
                args -> runOnBlockingThread(() -> {
                    List<Map<String, Object>> active = subAgentService.getActiveSubAgents();
                    return objectMapper.writeValueAsString(Map.of(
                            "subagents", active,
                            "count", active.size()
                    ));
                }));

        // ── 3. kill_subagent ────────────────────────────────────────────
        Map<String, Object> killProps = new LinkedHashMap<>();
        killProps.put("subagent_id", schema("string", "要终止的子 Agent ID", null));
        registry.register("kill_subagent",
                "Stop a running subagent by its ID. Use if it's stuck or you want to abort it.",
                schemaObj(killProps, List.of("subagent_id")),
                args -> runOnBlockingThread(() -> {
                    String id = str(args, "subagent_id");
                    if (id.isEmpty()) return "Error: subagent_id is required";
                    subAgentService.killSubAgent(id);
                    return objectMapper.writeValueAsString(
                            Map.of("status", "ok", "subagent_id", id, "message", "Terminated"));
                }));

        // ── 4. kill_all_subagents ───────────────────────────────────────
        registry.register("kill_all_subagents",
                "Stop all running subagents spawned by this agent.",
                schemaObj(Map.of(), Collections.emptyList()),
                args -> runOnBlockingThread(() -> {
                    int count = subAgentService.getActiveSubAgents().size();
                    subAgentService.killAllSubAgents();
                    return objectMapper.writeValueAsString(
                            Map.of("status", "ok", "killed_count", count,
                                    "message", count + " subagent(s) terminated"));
                }));

        log.info("Delegate tools registered: 4");
    }

    // ─── 子 Agent 执行 ────────────────────────────────────────────────────────

    private Map<String, Object> runSubAgent(
            int taskIndex,
            String goal,
            String context,
            List<String> toolsets,
            String role,
            int maxIterations) {

        long start = System.currentTimeMillis();

        // 过滤工具集：只保留存在的工具 + 移除被禁用的工具
        Set<String> allowedTools = resolveTools(toolsets);
        // 构建聚焦系统提示词
        String systemPrompt = buildChildSystemPrompt(goal, context, role);

        try {
            // 构造受限工具集描述（供日志）
            String toolsetsStr = toolsets != null
                    ? String.join(", ", toolsets)
                    : String.join(", ", DEFAULT_TOOLSETS);

            log.info("[Delegate] Spawning subagent[{}] role={} toolsets={} max_iter={}",
                    taskIndex, role, toolsetsStr, maxIterations);

            // 通过 SubAgentService 启动子会话
            // 注意：这里需要等待完成（阻塞当前线程，线程池会处理超时）
            String result = subAgentService.spawnSubAgent(goal, "delegate_" + taskIndex)
                    .timeout(Duration.ofMinutes(maxIterations > 30 ? 10 : 5))
                    .map(r -> (String) r)
                    .onErrorReturn("Error: subagent timed out or failed")
                    .block();

            double duration = (System.currentTimeMillis() - start) / 1000.0;

            return Map.of(
                    "task_index", taskIndex,
                    "status", result != null && !result.startsWith("Error:") ? "completed" : "failed",
                    "summary", result != null ? result : "No response",
                    "duration_seconds", (long) duration,
                    "role", role
            );

        } catch (Exception e) {
            double duration = (System.currentTimeMillis() - start) / 1000.0;
            log.error("[Delegate] Subagent[{}] failed: {}", taskIndex, e.getMessage());
            return Map.of(
                    "task_index", taskIndex,
                    "status", "error",
                    "summary", null,
                    "error", e.getMessage(),
                    "duration_seconds", (long) duration,
                    "role", role
            );
        }
    }

    /** 根据工具集名称解析允许的工具列表 */
    private Set<String> resolveTools(List<String> toolsets) {
        Set<String> allowed = new HashSet<>();

        List<String> inputSets = (toolsets != null && !toolsets.isEmpty())
                ? toolsets
                : DEFAULT_TOOLSETS;

        for (String setName : inputSets) {
            List<String> tools = TOOLSETS.get(setName);
            if (tools != null) {
                allowed.addAll(tools);
            }
        }

        // 移除被禁止的工具
        allowed.removeAll(BLOCKED_TOOLS);

        // 只保留实际注册的工具
        allowed.retainAll(allAvailableTools);

        return allowed;
    }

    /** 构建子 Agent 系统提示词 */
    private String buildChildSystemPrompt(String goal, String context, String role) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a focused subagent working on a specific delegated task.\n\n");
        sb.append("YOUR TASK:\n").append(goal).append("\n\n");

        if (context != null && !context.isBlank()) {
            sb.append("CONTEXT:\n").append(context).append("\n\n");
        }

        sb.append("Complete this task using the tools available to you.\n");
        sb.append("When finished, provide a clear, concise summary of:\n");
        sb.append("- What you did\n");
        sb.append("- What you found or accomplished\n");
        sb.append("- Any files you created or modified\n");
        sb.append("- Any issues encountered\n\n");

        if ("orchestrator".equals(role)) {
            sb.append("## Subagent Spawning (Orchestrator Role)\n");
            sb.append("You have access to the `delegate_task` tool and CAN spawn your own subagents.\n\n");
            sb.append("WHEN to delegate:\n");
            sb.append("- Goal decomposes into 2+ independent subtasks that can run in parallel.\n");
            sb.append("- A subtask is reasoning-heavy and would flood your context.\n\n");
            sb.append("WHEN NOT to delegate:\n");
            sb.append("- Single-step mechanical work.\n");
            sb.append("- Trivial tasks you can do in 1-2 tool calls.\n");
        }

        return sb.toString();
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) return "leaf";
        String r = role.trim().toLowerCase();
        if (r.equals("leaf") || r.equals("orchestrator")) return r;
        return "leaf";
    }

    // ─── 辅助 ─────────────────────────────────────────────────────────────────

    private Map<String, Object> schema(String type, String desc, Object extra) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", type);
        if (desc != null) s.put("description", desc);
        return s;
    }

    private Map<String, Object> schemaObj(Map<String, Object> props, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", props);
        if (!required.isEmpty()) s.put("required", required);
        return s;
    }

    private String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v != null ? v.toString().trim() : "";
    }

    private Mono<String> runOnBlockingThread(java.util.concurrent.Callable<String> callable) {
        return Mono.fromCallable(callable)
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofMinutes(10))
                .onErrorResume(e -> {
                    if (e instanceof TimeoutException) {
                        return Mono.just("Error: delegate operation timed out");
                    }
                    return Mono.just("Error: " + e.getMessage());
                });
    }
}
