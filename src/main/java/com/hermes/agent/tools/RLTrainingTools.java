package com.hermes.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.agent.rl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RL 训练工具集 — 10 个工具，参照 Python 版 rl_training_tool.py。
 *
 * <p>工具列表：
 * <ol>
 *   <li>rl_list_environments    — 列出所有可用 RL 环境</li>
 *   <li>rl_select_environment   — 选择环境并加载配置</li>
 *   <li>rl_get_current_config   — 获取当前环境配置</li>
 *   <li>rl_edit_config          — 修改配置字段</li>
 *   <li>rl_start_training       — 启动训练</li>
 *   <li>rl_check_status         — 检查训练状态（含速率限制）</li>
 *   <li>rl_stop_training        — 停止训练</li>
 *   <li>rl_get_results          — 获取训练结果</li>
 *   <li>rl_list_runs            — 列出所有运行</li>
 *   <li>rl_test_inference       — 快速推理测试</li>
 * </ol>
 *
 * <p>全部在 boundedElastic 上执行，避免阻塞 Netty 事件循环。
 */
@Component
public class RLTrainingTools {

    private static final Logger log = LoggerFactory.getLogger(RLTrainingTools.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final Scheduler blockingScheduler = Schedulers.boundedElastic();
    private final ToolRegistry registry;

    private final RLTrainingService rlService;
    private final TrajectoryCompressor trajectoryCompressor;

    public RLTrainingTools(ToolRegistry registry, RLTrainingService rlService,
                           TrajectoryCompressor trajectoryCompressor) {
        this.registry = registry;
        this.rlService = rlService;
        this.trajectoryCompressor = trajectoryCompressor;
        registerTools();
    }

    private void registerTools() {
        // ── 1. rl_list_environments ─────────────────────────────────────
        registry.register("rl_list_environments",
                "List all available RL environments. Scans tinker-atropos/environments/ for BaseEnv subclasses. " +
                "Returns environment names, class names, file paths, and descriptions. " +
                "TIP: Read the file_path to understand how each environment works (verifiers, data loading, rewards).",
                paramObj(Map.of(), List.of()),
                args -> Mono.fromCallable(() -> {
                    List<EnvironmentInfo> envs = rlService.getEnvironments();
                    List<Map<String, Object>> envList = envs.stream()
                            .map(EnvironmentInfo::toMap)
                            .collect(Collectors.toList());

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("environments", envList);
                    result.put("count", envList.size());
                    result.put("tips", List.of(
                            "Use rl_select_environment(name) to select an environment",
                            "Read file_path to understand how each environment works",
                            "Look for load_dataset(), score_answer(), get_next_item() methods"
                    ));
                    return objectMapper.writeValueAsString(result);
                }).subscribeOn(blockingScheduler));

        // ── 2. rl_select_environment ────────────────────────────────────
        Map<String, Object> selProps = new LinkedHashMap<>();
        selProps.put("name", objectSchema("string", "Name of the environment to select (from rl_list_environments)", null, null));
        registry.register("rl_select_environment",
                "Select an RL environment for training. Loads the environment's default configuration. " +
                "After selecting, use rl_get_current_config() to see settings and rl_edit_config() to modify.",
                paramObj(selProps, List.of("name")),
                args -> Mono.fromCallable(() -> {
                    String name = extractString(args, "name");
                    Map<String, Object> result = rlService.selectEnvironment(name);
                    return objectMapper.writeValueAsString(result);
                }).subscribeOn(blockingScheduler));

        // ── 3. rl_get_current_config ────────────────────────────────────
        registry.register("rl_get_current_config",
                "Get the current environment configuration. Returns configurable fields (can be edited) " +
                "and locked fields (infrastructure settings like lora_rank, learning_rate).",
                paramObj(Map.of(), List.of()),
                args -> Mono.fromCallable(() -> {
                    Map<String, Object> result = rlService.getCurrentConfig();
                    return objectMapper.writeValueAsString(result);
                }).subscribeOn(blockingScheduler));

        // ── 4. rl_edit_config ───────────────────────────────────────────
        Map<String, Object> editProps = new LinkedHashMap<>();
        editProps.put("field", objectSchema("string", "Name of the field to update (from rl_get_current_config)", null, null));
        editProps.put("value", objectSchema("string", "New value for the field", null, null));
        registry.register("rl_edit_config",
                "Update a configuration field. Use rl_get_current_config() first to see available fields. " +
                "Infrastructure settings (tokenizer, URLs, lora_rank, learning_rate) are locked and cannot be changed.",
                paramObj(editProps, List.of("field", "value")),
                args -> Mono.fromCallable(() -> {
                    String field = extractString(args, "field");
                    Object value = args.get("value");
                    Map<String, Object> result = rlService.editConfig(field, value);
                    return objectMapper.writeValueAsString(result);
                }).subscribeOn(blockingScheduler));

        // ── 5. rl_start_training ─────────────────────────────────────────
        registry.register("rl_start_training",
                "Start a new RL training run with the current environment and config. " +
                "WARNING: Training runs take hours. Check status every 30 minutes with rl_check_status().",
                paramObj(Map.of(), List.of()),
                args -> Mono.fromCallable(() -> {
                    Map<String, Object> result = rlService.startTraining();
                    return objectMapper.writeValueAsString(result);
                }).subscribeOn(blockingScheduler));

        // ── 6. rl_check_status ───────────────────────────────────────────
        Map<String, Object> statusProps = new LinkedHashMap<>();
        statusProps.put("run_id", objectSchema("string", "The run ID from rl_start_training()", null, null));
        registry.register("rl_check_status",
                "Get status and metrics for a training run. RATE LIMITED: enforces 30-minute minimum between checks. " +
                "Returns WandB metrics: step, state, reward_mean, percent_correct, eval_percent_correct.",
                paramObj(statusProps, List.of("run_id")),
                args -> Mono.fromCallable(() -> {
                    String runId = extractString(args, "run_id");
                    Map<String, Object> result = rlService.checkStatus(runId);
                    return objectMapper.writeValueAsString(result);
                }).subscribeOn(blockingScheduler));

        // ── 7. rl_stop_training ──────────────────────────────────────────
        Map<String, Object> stopProps = new LinkedHashMap<>();
        stopProps.put("run_id", objectSchema("string", "The run ID to stop", null, null));
        registry.register("rl_stop_training",
                "Stop a running training job. Use if metrics look bad, training is stagnant, " +
                "or you want to try different settings.",
                paramObj(stopProps, List.of("run_id")),
                args -> Mono.fromCallable(() -> {
                    String runId = extractString(args, "run_id");
                    Map<String, Object> result = rlService.stopTraining(runId);
                    return objectMapper.writeValueAsString(result);
                }).subscribeOn(blockingScheduler));

        // ── 8. rl_get_results ────────────────────────────────────────────
        Map<String, Object> resultsProps = new LinkedHashMap<>();
        resultsProps.put("run_id", objectSchema("string", "The run ID to get results for", null, null));
        registry.register("rl_get_results",
                "Get final results and metrics for a completed training run. Returns final metrics, " +
                "WandB URL, and path to trained weights.",
                paramObj(resultsProps, List.of("run_id")),
                args -> Mono.fromCallable(() -> {
                    String runId = extractString(args, "run_id");
                    Map<String, Object> result = rlService.getResults(runId);
                    return objectMapper.writeValueAsString(result);
                }).subscribeOn(blockingScheduler));

        // ── 9. rl_list_runs ──────────────────────────────────────────────
        registry.register("rl_list_runs",
                "List all training runs (active and completed) with their status, environment, and WandB run name.",
                paramObj(Map.of(), List.of()),
                args -> Mono.fromCallable(() -> {
                    List<Map<String, Object>> runs = rlService.listRuns();
                    Map<String, Object> result = Map.of("runs", runs, "count", runs.size());
                    return objectMapper.writeValueAsString(result);
                }).subscribeOn(blockingScheduler));

        // ── 10. rl_test_inference ────────────────────────────────────────
        Map<String, Object> testProps = new LinkedHashMap<>();
        testProps.put("num_steps", objectSchema("integer",
                "Number of steps to run (default: 3, recommended max for testing)", null, null));
        testProps.put("group_size", objectSchema("integer",
                "Completions per step (default: 16, like training)", null, null));
        testProps.put("models", objectSchema("array",
                "Optional list of OpenRouter model IDs to test. Default: qwen/qwen3-8b, z-ai/glm-4.7-flash",
                null, null));
        registry.register("rl_test_inference",
                "Quick inference test for any environment using OpenRouter. Runs a few steps of inference + scoring " +
                "to validate environment loading, prompt construction, inference parsing, and verifier logic. " +
                "Default: 3 steps x 16 completions = 48 rollouts per model. " +
                "Use BEFORE training to catch environment issues early.",
                paramObj(testProps, List.of()),
                args -> Mono.fromCallable(() -> {
                    String numSteps = extractString(args, "num_steps");
                    String groupSize = extractString(args, "group_size");
                    @SuppressWarnings("unchecked")
                    List<String> models = (List<String>) args.get("models");
                    int steps = numSteps.isEmpty() ? 3 : Integer.parseInt(numSteps);
                    int group = groupSize.isEmpty() ? 16 : Integer.parseInt(groupSize);
                    Map<String, Object> result = runInferenceTest(steps, group, models);
                    return objectMapper.writeValueAsString(result);
                }).subscribeOn(blockingScheduler));

        log.info("RL training tools registered: 10");
    }

    // ── 推理测试（调用 RLTrainingService） ─────────────────────────────

    private Map<String, Object> runInferenceTest(int numSteps, int groupSize, List<String> models) {
        // 直接调用 RLTrainingService.testInference
        return rlService.testInference(numSteps, groupSize, models);
    }

    // ── 辅助 ─────────────────────────────────────────────────────────

    private Map<String, Object> objectSchema(String type, String desc, Map<String, Object> props, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", type);
        if (desc != null) s.put("description", desc);
        if (props != null) s.put("properties", props);
        if (required != null) s.put("required", required);
        return s;
    }

    private Map<String, Object> paramObj(Map<String, Object> props, List<String> required) {
        return objectSchema("object", null, props, required.isEmpty() ? null : required);
    }

    private String extractString(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString().trim() : "";
    }
}
