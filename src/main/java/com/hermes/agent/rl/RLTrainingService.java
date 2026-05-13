package com.hermes.agent.rl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.agent.config.AgentConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * RL 训练核心服务。
 *
 * <p>参照 Python 版 rl_training_tool.py 的核心逻辑，提供：
 * <ul>
 *   <li>环境自动发现（AST 扫描 BaseEnv 子类）</li>
 *   <li>配置管理（可编辑字段 + 锁定字段分离）</li>
 *   <li>训练运行生命周期（启动/监控/停止/结果）</li>
 *   <li>WandB 指标监控</li>
 * </ul>
 *
 * <p>与 Python 版不同，Java 版通过 subprocess 启动外部训练进程。
 * Tinker-Atropos 子模块存在于 hermes-agent 侧，hermes-java 通过 shell 命令调用。
 */
@Component
public class RLTrainingService {

    private static final Logger log = LoggerFactory.getLogger(RLTrainingService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ── 路径配置 ──

    /** hermes-agent 根目录（Python 版所在路径） */
    private final Path hermesRoot;

    /** Tinker-Atropos 子模块目录 */
    private final Path tinkerRoot;

    /** 环境文件目录 */
    private final Path environmentsDir;

    /** 配置文件目录 */
    private final Path configsDir;

    /** 日志目录 */
    private final Path logsDir;

    // ── 锁定配置（不可编辑） — 手动构建以支持超过 10 个键值对 ──

    private static final Map<String, Object> LOCKED_ENV_FIELDS;
    static {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("tokenizer_name", "Qwen/Qwen3-8B");
        m.put("rollout_server_url", "http://localhost:8000");
        m.put("use_wandb", true);
        m.put("max_token_length", 8192);
        m.put("max_num_workers", 2048);
        m.put("worker_timeout", 3600);
        m.put("total_steps", 2500);
        m.put("steps_per_eval", 25);
        m.put("max_batches_offpolicy", 3);
        m.put("inference_weight", 1.0);
        m.put("eval_limit_ratio", 0.1);
        LOCKED_ENV_FIELDS = Collections.unmodifiableMap(m);
    }

    private static final Map<String, Object> LOCKED_TINKER_FIELDS = Map.of(
            "lora_rank", 32,
            "learning_rate", 0.00004,
            "max_token_trainer_length", 9000,
            "checkpoint_dir", "./temp/",
            "save_checkpoint_interval", 25
    );

    private static final List<Map<String, Object>> LOCKED_OPENAI = List.of(
            Map.of(
                    "model_name", "Qwen/Qwen3-8B",
                    "base_url", "http://localhost:8001/v1",
                    "api_key", "x",
                    "weight", 1.0,
                    "num_requests_for_eval", 256,
                    "timeout", 3600,
                    "server_type", "sglang"
            )
    );

    // ── 推理测试模型配置 ──

    private static final List<Map<String, String>> TEST_MODELS;
    static {
        List<Map<String, String>> models = new ArrayList<>();
        models.add(Map.of("id", "qwen/qwen3-8b", "name", "Qwen3 8B", "scale", "small"));
        models.add(Map.of("id", "z-ai/glm-4.7-flash", "name", "GLM-4.7 Flash", "scale", "medium"));
        models.add(Map.of("id", "minimax/minimax-m2.7", "name", "MiniMax M2.7", "scale", "large"));
        TEST_MODELS = Collections.unmodifiableList(models);
    }

    /** 推理测试输出目录 */
    private Path inferenceTestOutputDir;

    // ── 状态 ──

    private final List<EnvironmentInfo> environments = new CopyOnWriteArrayList<>();
    private volatile String currentEnv = null;
    private final Map<String, Object> currentConfig = new ConcurrentHashMap<>();
    private final Map<String, EnvironmentInfo> envCache = new ConcurrentHashMap<>();
    private final Map<String, RunState> activeRuns = new ConcurrentHashMap<>();
    private final Map<String, Long> lastStatusCheck = new ConcurrentHashMap<>();

    /** 状态检查最小间隔（秒）— 30 分钟 */
    private static final long MIN_STATUS_CHECK_INTERVAL = 30 * 60;

    /** WandB API（可选） */
    private volatile WandbClient wandbClient;

    private final AgentConfig config;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public RLTrainingService(AgentConfig config) {
        this.config = config;
        // 路径推断：从 config 的 dataDir 推断 hermes home
        Path hermesHome = Path.of(config.resolveDataDir()).getParent();
        this.hermesRoot = hermesHome;
        this.tinkerRoot = hermesHome.resolve("tinker-atropos");
        this.environmentsDir = tinkerRoot.resolve("tinker_atropos").resolve("environments");
        this.configsDir = tinkerRoot.resolve("configs");
        this.logsDir = hermesHome.resolve("logs").resolve("rl_training");
        this.inferenceTestOutputDir = hermesHome.resolve("logs").resolve("rl_training").resolve("inference_tests");
    }

    @PostConstruct
    public void init() {
        // 确保日志目录存在
        try { Files.createDirectories(logsDir); } catch (IOException ignored) {}

        // 尝试初始化 WandB 客户端
        try {
            this.wandbClient = new WandbClient();
        } catch (Exception e) {
            log.warn("[RL] WandB 客户端初始化失败: {}", e.getMessage());
        }

        // 异步扫描环境
        scheduler.submit(this::scanEnvironments);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }

    // ═══════════════════════════════════════════════════════════
    //  环境发现
    // ═══════════════════════════════════════════════════════════

    /**
     * 扫描 tinker-atropos/environments/ 目录，
     * 通过 AST 解析找到所有 BaseEnv 子类。
     */
    public void scanEnvironments() {
        environments.clear();
        envCache.clear();

        if (!Files.exists(environmentsDir)) {
            log.warn("[RL] 环境目录不存在: {}", environmentsDir);
            return;
        }

        try (var stream = Files.list(environmentsDir)) {
            for (Path pyFile : stream.toList()) {
                if (!pyFile.toString().endsWith(".py") || pyFile.getFileName().toString().startsWith("_")) {
                    continue;
                }
                try {
                    List<EnvironmentInfo> found = parsePythonEnv(pyFile);
                    environments.addAll(found);
                    for (EnvironmentInfo info : found) {
                        envCache.put(info.getName(), info);
                    }
                } catch (Exception e) {
                    log.debug("无法解析环境文件 {}: {}", pyFile, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("[RL] 扫描环境目录失败: {}", e.getMessage());
        }

        log.info("[RL] 环境扫描完成，共发现 {} 个环境", environments.size());
    }

    private List<EnvironmentInfo> parsePythonEnv(Path pyFile) {
        List<EnvironmentInfo> result = new ArrayList<>();
        String content;
        try {
            content = Files.readString(pyFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return result;
        }

        // 简单正则解析：class Xxx(BaseEnv)
        Pattern classPattern = Pattern.compile("^class\\s+(\\w+)\\s*\\(\\s*BaseEnv\\s*\\)", Pattern.MULTILINE);
        Pattern namePattern = Pattern.compile("^\\s+name\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.MULTILINE);
        Pattern descPattern = Pattern.compile("^\"\"\"([^\"]+)\"\"\"", Pattern.MULTILINE);
        Pattern configClsPattern = Pattern.compile("^\\s+env_config_cls\\s*=\\s*(\\w+)", Pattern.MULTILINE);

        Matcher classMatcher = classPattern.matcher(content);
        String fileName = pyFile.getFileName().toString().replace(".py", "");
        String envName = fileName;
        String description = "";
        String configClass = "BaseEnvConfig";

        // 找 class 定义附近的 name/docstring/config_cls
        while (classMatcher.find()) {
            String className = classMatcher.group(1);
            int start = classMatcher.start();
            int end = Math.min(start + 2000, content.length());
            String region = content.substring(start, end);

            // Java 8 兼容：用 Matcher.find() 替代 Java 9+ 的 results().findFirst()
            String localName = className;
            Matcher nm = namePattern.matcher(region);
            if (nm.find()) localName = nm.group(1);

            String localDesc = "";
            Matcher dm = descPattern.matcher(region);
            if (dm.find()) localDesc = dm.group(1).split("\n")[0].trim();

            String localCfg = "BaseEnvConfig";
            Matcher cm = configClsPattern.matcher(region);
            if (cm.find()) localCfg = cm.group(1);

            result.add(new EnvironmentInfo(localName, className, pyFile.toString(),
                    localDesc, localCfg));
        }

        return result;
    }

    public List<EnvironmentInfo> getEnvironments() { return new ArrayList<>(environments); }

    // ═══════════════════════════════════════════════════════════
    //  环境选择与配置
    // ═══════════════════════════════════════════════════════════

    /**
     * 选择一个 RL 环境。
     */
    public Map<String, Object> selectEnvironment(String name) {
        EnvironmentInfo env = envCache.get(name);
        if (env == null) {
            return Map.of(
                    "error", "Environment '" + name + "' not found",
                    "available", environments.stream().map(EnvironmentInfo::getName).toList()
            );
        }

        currentEnv = name;

        // 尝试解析配置文件（如环境有静态 config 定义）
        Map<String, Map<String, Object>> fields = discoverConfigFields(env);
        env.setConfigFields(fields);

        // 初始化可编辑字段为默认值
        currentConfig.clear();
        for (Map.Entry<String, Map<String, Object>> e : fields.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue().get("locked"))) {
                currentConfig.put(e.getKey(), e.getValue().get("default"));
            }
        }

        // 自动设置 wandb_name
        String timestamp = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        currentConfig.put("wandb_name", name + "-" + timestamp);

        return Map.of(
                "message", "Selected environment: " + name,
                "environment", name,
                "file_path", env.getFilePath(),
                "configurable_fields", fields.values().stream()
                        .filter(f -> !Boolean.TRUE.equals(f.get("locked")))
                        .count()
        );
    }

    /**
     * 动态发现环境的配置字段。
     * 尝试读取 Python 源码中的 config_cls 定义。
     */
    private Map<String, Map<String, Object>> discoverConfigFields(EnvironmentInfo env) {
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        Path filePath = Path.of(env.getFilePath());
        if (!Files.exists(filePath)) return fields;

        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);

            // 尝试匹配 model_fields 或 dataclass fields
            Pattern fieldPattern = Pattern.compile(
                    "(\\w+)\\s*:\\s*(?:[^=,\\n]+(?:\\s*=\\s*[^,\\n]+)?)",
                    Pattern.MULTILINE);

            // 简单解析：找 config_cls 附近的字段
            int configStart = content.indexOf(env.getConfigClass());
            if (configStart < 0) configStart = 0;
            int configEnd = Math.min(configStart + 3000, content.length());
            String region = content.substring(configStart, configEnd);

            // 解析常见配置字段
            String[][] commonFields = {
                    {"dataset_path", "数据集路径"},
                    {"group_size", "每次采样的完成数"},
                    {"max_token_length", "最大 token 数"},
                    {"total_steps", "训练总步数"},
                    {"steps_per_eval", "评估间隔步数"},
                    {"use_wandb", "是否启用 WandB"},
                    {"wandb_project", "WandB 项目名"},
                    {"wandb_name", "WandB Run 名称"},
                    {"data_path", "数据文件路径"},
                    {"verifier", "验证器类型"},
                    {"max_num_workers", "最大工作进程数"}
            };

            Set<String> knownFields = new HashSet<>();
            for (String[] f : commonFields) {
                String fieldName = f[0];
                if (region.contains(fieldName) || content.contains(fieldName)) {
                    knownFields.add(fieldName);
                    boolean locked = LOCKED_ENV_FIELDS.containsKey(fieldName);
                    fields.put(fieldName, Map.of(
                            "type", inferType(content, fieldName),
                            "default", locked ? LOCKED_ENV_FIELDS.get(fieldName) : null,
                            "description", f[1],
                            "locked", locked,
                            "current_value", currentConfig.getOrDefault(fieldName,
                                    locked ? LOCKED_ENV_FIELDS.get(fieldName) : null)
                    ));
                }
            }

            // 从 region 中发现其他字段（简单启发式）
            Matcher m = fieldPattern.matcher(region);
            while (m.find()) {
                String fieldName = m.group(1);
                if (!knownFields.contains(fieldName) && fieldName.matches("[a-z_][a-z_0-9]*")) {
                    knownFields.add(fieldName);
                    fields.put(fieldName, Map.of(
                            "type", "unknown",
                            "default", null,
                            "description", "Environment-defined field",
                            "locked", LOCKED_ENV_FIELDS.containsKey(fieldName),
                            "current_value", currentConfig.getOrDefault(fieldName, null)
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("[RL] 无法解析环境配置 {}: {}", env.getName(), e.getMessage());
        }

        return fields;
    }

    private String inferType(String content, String fieldName) {
        // 简单启发式类型推断
        Pattern p = Pattern.compile(fieldName + "\\s*:\\s*(\\w+)");
        Matcher m = p.matcher(content);
        if (m.find()) return m.group(1);
        return "unknown";
    }

    public Map<String, Object> getCurrentConfig() {
        if (currentEnv == null) {
            return Map.of("error", "No environment selected. Use selectEnvironment(name) first.");
        }

        EnvironmentInfo env = envCache.get(currentEnv);
        Map<String, Map<String, Object>> fields = env != null && env.getConfigFields() != null
                ? env.getConfigFields() : Map.of();

        List<Map<String, Object>> configurable = new ArrayList<>();
        List<Map<String, Object>> locked = new ArrayList<>();

        for (Map.Entry<String, Map<String, Object>> e : fields.entrySet()) {
            Map<String, Object> fd = new LinkedHashMap<>(e.getValue());
            fd.put("name", e.getKey());
            fd.put("current_value", currentConfig.getOrDefault(e.getKey(), fd.get("default")));
            if (Boolean.TRUE.equals(fd.get("locked"))) locked.add(fd);
            else configurable.add(fd);
        }

        return Map.of(
                "environment", currentEnv,
                "configurable_fields", configurable,
                "locked_fields", locked,
                "tip", "Use editConfig(field, value) to change any configurable field."
        );
    }

    public Map<String, Object> editConfig(String field, Object value) {
        if (currentEnv == null) {
            return Map.of("error", "No environment selected.");
        }
        EnvironmentInfo env = envCache.get(currentEnv);
        Map<String, Map<String, Object>> fields = env != null && env.getConfigFields() != null
                ? env.getConfigFields() : Map.of();

        if (!fields.containsKey(field)) {
            return Map.of(
                    "error", "Unknown field '" + field + "'",
                    "available_fields", fields.keySet()
            );
        }
        if (Boolean.TRUE.equals(fields.get(field).get("locked"))) {
            return Map.of(
                    "error", "Field '" + field + "' is locked and cannot be changed",
                    "locked_value", LOCKED_ENV_FIELDS.get(field)
            );
        }

        currentConfig.put(field, value);
        return Map.of(
                "message", "Updated " + field + " = " + value,
                "field", field,
                "value", value
        );
    }

    // ═══════════════════════════════════════════════════════════
    //  训练运行管理
    // ═══════════════════════════════════════════════════════════

    /**
     * 启动一次新的 RL 训练。
     * 生成配置文件，并通过 subprocess 启动训练进程。
     */
    public Map<String, Object> startTraining() {
        if (currentEnv == null) {
            return Map.of("error", "No environment selected. Use selectEnvironment(name) first.");
        }

        String tinkerKey = System.getenv("TINKER_API_KEY");
        if (tinkerKey == null || tinkerKey.isBlank()) {
            return Map.of("error", "TINKER_API_KEY not set. Add it to your environment variables.");
        }

        // 生成 run ID
        String runId = UUID.randomUUID().toString().substring(0, 8);

        // 创建配置 YAML
        try {
            Files.createDirectories(configsDir);
        } catch (IOException e) {
            return Map.of("error", "Cannot create configs dir: " + e.getMessage());
        }

        Path configPath = configsDir.resolve("run_" + runId + ".yaml");
        Map<String, Object> runConfig = buildRunConfig();
        try {
            String yaml = objectMapper.writeValueAsString(runConfig)
                    .replace("---", "")
                    .replace("{", "")
                    .replace("}", "");
            Files.writeString(configPath, yaml, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return Map.of("error", "Cannot write config: " + e.getMessage());
        }

        // 创建运行状态
        RunState runState = new RunState(runId, currentEnv, new LinkedHashMap<>(currentConfig));
        runState.setStatus(RunState.Status.STARTING);
        String wandbProject = String.valueOf(currentConfig.getOrDefault("wandb_project", "atropos-tinker"));
        runState.setWandbProject(wandbProject);
        runState.setWandbRunName(currentEnv + "-" + runId);

        activeRuns.put(runId, runState);

        // 异步启动训练（在后台进程执行）
        scheduler.submit(() -> spawnTrainingRun(runState, configPath));

        return Map.of(
                "run_id", runId,
                "status", "starting",
                "environment", currentEnv,
                "config", currentConfig,
                "wandb_project", wandbProject,
                "wandb_run_name", runState.getWandbRunName(),
                "config_path", configPath.toString(),
                "logs_dir", logsDir.toString(),
                "message", "Training starting. Use checkStatus(run_id) to monitor (every 30 min recommended)."
        );
    }

    private Map<String, Object> buildRunConfig() {
        Map<String, Object> runConfig = new LinkedHashMap<>();

        // 锁定字段
        runConfig.put("env", new LinkedHashMap<>(LOCKED_ENV_FIELDS));
        runConfig.put("openai", new ArrayList<>(LOCKED_OPENAI));
        runConfig.put("tinker", new LinkedHashMap<>(LOCKED_TINKER_FIELDS));
        runConfig.put("slurm", false);
        runConfig.put("testing", false);

        // 覆盖可编辑字段
        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) runConfig.get("env");
        for (Map.Entry<String, Object> e : currentConfig.entrySet()) {
            if (e.getValue() != null && !"".equals(e.getValue())) {
                env.put(e.getKey(), e.getValue());
            }
        }

        // WandB 项目设置
        String wandbProject = String.valueOf(currentConfig.getOrDefault("wandb_project", "atropos-tinker"));
        @SuppressWarnings("unchecked")
        Map<String, Object> tinker = (Map<String, Object>) runConfig.get("tinker");
        tinker.put("wandb_project", wandbProject);
        tinker.put("wandb_run_name", currentEnv + "-" + UUID.randomUUID().toString().substring(0, 8));

        return runConfig;
    }

    private void spawnTrainingRun(RunState runState, Path configPath) {
        try {
            log.info("[RL] 启动训练 run_id={} env={}", runState.getRunId(), runState.getEnvironment());

            // Step 1: 启动 Atropos API server
            Path apiLog = logsDir.resolve("api_" + runState.getRunId() + ".log");
            ProcessBuilder apiPb = new ProcessBuilder("run-api");
            apiPb.directory(tinkerRoot.toFile());
            apiPb.redirectErrorStream(true);
            apiPb.redirectOutput(apiLog.toFile());
            Process apiProcess = apiPb.start();
            runState.setApiPid((long) apiProcess.pid());

            Thread.sleep(5000);
            if (!apiProcess.isAlive()) {
                runState.setStatus(RunState.Status.FAILED);
                runState.setErrorMessage("API server exited. Check " + apiLog);
                return;
            }

            // Step 2: 启动 Tinker trainer
            Path trainerLog = logsDir.resolve("trainer_" + runState.getRunId() + ".log");
            ProcessBuilder trainerPb = new ProcessBuilder(
                    "python", "-m", "launch_training", "--config", configPath.toString()
            );
            trainerPb.directory(tinkerRoot.toFile());
            Map<String, String> env = new HashMap<>(System.getenv());
            env.put("TINKER_API_KEY", System.getenv("TINKER_API_KEY"));
            trainerPb.environment().putAll(env);
            trainerPb.redirectErrorStream(true);
            trainerPb.redirectOutput(trainerLog.toFile());
            Process trainerProcess = trainerPb.start();
            runState.setTrainerPid((long) trainerProcess.pid());

            Thread.sleep(30_000); // 等待 trainer 初始化
            if (!trainerProcess.isAlive()) {
                runState.setStatus(RunState.Status.FAILED);
                runState.setErrorMessage("Trainer exited. Check " + trainerLog);
                stopProcess(apiProcess);
                return;
            }

            // Step 3: 启动 Environment
            Thread.sleep(90_000); // 等待 inference server 就绪

            EnvironmentInfo envInfo = envCache.get(runState.getEnvironment());
            if (envInfo == null) {
                runState.setStatus(RunState.Status.FAILED);
                runState.setErrorMessage("Environment not found: " + runState.getEnvironment());
                stopProcess(apiProcess);
                stopProcess(trainerProcess);
                return;
            }

            Path envLog = logsDir.resolve("env_" + runState.getRunId() + ".log");
            ProcessBuilder envPb = new ProcessBuilder(
                    "python", envInfo.getFilePath(), "serve", "--config", configPath.toString()
            );
            envPb.directory(tinkerRoot.toFile());
            envPb.redirectErrorStream(true);
            envPb.redirectOutput(envLog.toFile());
            Process envProcess = envPb.start();
            runState.setEnvPid((long) envProcess.pid());

            Thread.sleep(10_000);
            if (!envProcess.isAlive()) {
                runState.setStatus(RunState.Status.FAILED);
                runState.setErrorMessage("Environment exited. Check " + envLog);
                stopProcess(apiProcess);
                stopProcess(trainerProcess);
                return;
            }

            runState.setStatus(RunState.Status.RUNNING);
            runState.setStartTime(Instant.now());
            log.info("[RL] 训练运行启动成功 run_id={}", runState.getRunId());

            // 启动后台监控
            scheduler.submit(() -> monitorTrainingRun(runState, apiProcess, trainerProcess, envProcess));

        } catch (Exception e) {
            runState.setStatus(RunState.Status.FAILED);
            runState.setErrorMessage(e.getMessage());
            log.error("[RL] 启动训练失败 run_id={}: {}", runState.getRunId(), e.getMessage());
        }
    }

    private void monitorTrainingRun(RunState runState, Process api, Process trainer, Process env) {
        while (!runState.isTerminal()) {
            try { Thread.sleep(30_000); } catch (InterruptedException ignored) {}

            if (!env.isAlive()) {
                int code = env.exitValue();
                runState.setStatus(code == 0 ? RunState.Status.COMPLETED : RunState.Status.FAILED);
                if (code != 0) runState.setErrorMessage("Environment process exited with code " + code);
                runState.setEndTime(Instant.now());
                log.info("[RL] 训练运行结束 run_id={} status={}", runState.getRunId(), runState.getStatus());
                stopProcess(api);
                stopProcess(trainer);
                break;
            }
            if (!trainer.isAlive()) {
                int code = trainer.exitValue();
                runState.setStatus(code == 0 ? RunState.Status.COMPLETED : RunState.Status.FAILED);
                if (code != 0) runState.setErrorMessage("Trainer process exited with code " + code);
                runState.setEndTime(Instant.now());
                log.info("[RL] 训练运行结束 run_id={} status={}", runState.getRunId(), runState.getStatus());
                stopProcess(api);
                break;
            }
            if (!api.isAlive()) {
                runState.setStatus(RunState.Status.FAILED);
                runState.setErrorMessage("API server exited unexpectedly");
                runState.setEndTime(Instant.now());
                break;
            }
        }
    }

    private void stopProcess(Process p) {
        if (p.isAlive()) {
            p.destroy();
            try { p.waitFor(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
            if (p.isAlive()) p.destroyForcibly();
        }
    }

    /**
     * 检查训练运行状态（含速率限制：每 30 分钟检查一次）。
     */
    public Map<String, Object> checkStatus(String runId) {
        long now = System.currentTimeMillis() / 1000;
        Long last = lastStatusCheck.get(runId);
        if (last != null && (now - last) < MIN_STATUS_CHECK_INTERVAL) {
            long remaining = MIN_STATUS_CHECK_INTERVAL - (now - last);
            return Map.of(
                    "rate_limited", true,
                    "run_id", runId,
                    "message", "Rate limited. Next check in " + (remaining / 60) + " minutes.",
                    "next_check_in_seconds", remaining
            );
        }
        lastStatusCheck.put(runId, now);

        RunState run = activeRuns.get(runId);
        if (run == null) {
            return Map.of(
                    "error", "Run '" + runId + "' not found",
                    "active_runs", activeRuns.keySet()
            );
        }

        Map<String, Object> result = new LinkedHashMap<>(run.toSummaryMap());
        result.put("logs", Map.of(
                "api", logsDir.resolve("api_" + runId + ".log").toString(),
                "trainer", logsDir.resolve("trainer_" + runId + ".log").toString(),
                "env", logsDir.resolve("env_" + runId + ".log").toString()
        ));

        // 尝试获取 WandB 指标
        if (wandbClient != null && run.getStatus() == RunState.Status.RUNNING) {
            try {
                Map<String, Object> metrics = wandbClient.fetchMetrics(
                        run.getWandbProject(), run.getWandbRunName());
                if (metrics != null) {
                    result.put("metrics", metrics);
                    result.put("wandb_metrics", true);
                }
            } catch (Exception e) {
                result.put("wandb_error", e.getMessage());
            }
        }

        return result;
    }

    /**
     * 停止训练运行。
     */
    public Map<String, Object> stopTraining(String runId) {
        RunState run = activeRuns.get(runId);
        if (run == null) {
            return Map.of(
                    "error", "Run '" + runId + "' not found",
                    "active_runs", activeRuns.keySet()
            );
        }
        if (run.getStatus() != RunState.Status.RUNNING
                && run.getStatus() != RunState.Status.STARTING) {
            return Map.of("message", "Run '" + runId + "' is not running (status: " + run.getStatus() + ")");
        }

        run.setStatus(RunState.Status.STOPPING);
        run.setEndTime(Instant.now());
        log.info("[RL] 停止训练 run_id={}", runId);
        return Map.of(
                "message", "Stopping training run '" + runId + "'",
                "run_id", runId,
                "status", run.getStatus().name().toLowerCase()
        );
    }

    /**
     * 获取训练结果。
     */
    public Map<String, Object> getResults(String runId) {
        RunState run = activeRuns.get(runId);
        if (run == null) {
            return Map.of("error", "Run '" + runId + "' not found");
        }

        Map<String, Object> result = new LinkedHashMap<>(run.toSummaryMap());

        if (wandbClient != null) {
            try {
                Map<String, Object> finalMetrics = wandbClient.fetchMetrics(
                        run.getWandbProject(), run.getWandbRunName());
                if (finalMetrics != null) {
                    result.put("final_metrics", finalMetrics);
                    result.put("wandb_url", wandbClient.getRunUrl(run.getWandbProject(), run.getWandbRunName()));
                }
            } catch (Exception e) {
                result.put("wandb_error", e.getMessage());
            }
        }

        return result;
    }

    public List<Map<String, Object>> listRuns() {
        return activeRuns.values().stream()
                .map(RunState::toSummaryMap)
                .toList();
    }

    public Map<String, Object> getRequirementsStatus() {
        boolean py311plus = false;
        try {
            String version = System.getProperty("python.version", "");
            if (version.isEmpty()) {
                // 尝试执行 python --version
                Process p = new ProcessBuilder("python", "--version").start();
                java.util.Scanner s = new java.util.Scanner(p.getInputStream()).useDelimiter("\\A");
                version = s.hasNext() ? s.next() : "";
                p.destroy();
            }
            py311plus = version.contains("3.11") || version.contains("3.12");
        } catch (Exception ignored) {}

        String tinkerKey = System.getenv("TINKER_API_KEY");
        String wandbKey = System.getenv("WANDB_API_KEY");

        List<String> missing = new ArrayList<>();
        if (!py311plus) missing.add("Python >= 3.11 (required by tinker package)");
        if (tinkerKey == null || tinkerKey.isBlank()) missing.add("TINKER_API_KEY");
        if (wandbKey == null || wandbKey.isBlank()) missing.add("WANDB_API_KEY");
        if (!Files.exists(tinkerRoot)) missing.add("tinker-atropos submodule (not found at " + tinkerRoot + ")");

        return Map.of(
                "ready", missing.isEmpty(),
                "python_311_plus", py311plus,
                "has_tinker_key", tinkerKey != null && !tinkerKey.isBlank(),
                "has_wandb_key", wandbKey != null && !wandbKey.isBlank(),
                "has_tinker_submodule", Files.exists(tinkerRoot),
                "missing_requirements", missing
        );
    }

    // ═══════════════════════════════════════════════════════════
    //  推理测试（OpenRouter 多模型）
    // ═══════════════════════════════════════════════════════════

    /**
     * 快速推理测试，验证环境可运行。
     *
     * <p>参照 Python 版 rl_test_inference，使用 OpenRouter 多模型测试：
     * <ul>
     *   <li>qwen/qwen3-8b (small)</li>
     *   <li>z-ai/glm-4.7-flash (medium)</li>
     *   <li>minimax/minimax-m2.7 (large)</li>
     * </ul>
     *
     * @param numSteps 测试步数（默认 3）
     * @param groupSize 每步完成数（默认 16）
     * @param modelIds 可选模型 ID 列表，null 使用默认 3 个模型
     * @return 测试结果，包含每个模型的准确率统计
     */
    public Map<String, Object> testInference(int numSteps, int groupSize, List<String> modelIds) {
        // 1. 检查环境变量
        String openrouterKey = System.getenv("OPENROUTER_API_KEY");
        if (openrouterKey == null || openrouterKey.isBlank()) {
            return Map.of(
                    "error", "OPENROUTER_API_KEY not set. Required for inference testing.",
                    "tip", "Set OPENROUTER_API_KEY environment variable to use this feature."
            );
        }

        // 2. 检查是否选择了环境
        if (currentEnv == null) {
            return Map.of("error", "No environment selected. Use selectEnvironment(name) first.");
        }

        EnvironmentInfo envInfo = envCache.get(currentEnv);
        if (envInfo == null) {
            return Map.of("error", "Environment not found: " + currentEnv);
        }

        // 3. 确定测试模型
        List<Map<String, String>> testModels;
        if (modelIds != null && !modelIds.isEmpty()) {
            testModels = new ArrayList<>();
            for (String id : modelIds) {
                testModels.add(Map.of("id", id, "name", id, "scale", "custom"));
            }
        } else {
            testModels = new ArrayList<>(TEST_MODELS);
        }

        int rolloutsPerModel = numSteps * groupSize;
        int totalRollouts = rolloutsPerModel * testModels.size();

        // 4. 准备输出目录
        try {
            Files.createDirectories(inferenceTestOutputDir);
        } catch (IOException e) {
            return Map.of("error", "Cannot create output directory: " + e.getMessage());
        }

        // 5. 构建结果结构
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("environment", currentEnv);
        results.put("environment_file", envInfo.getFilePath());
        results.put("test_config", Map.of(
                "num_steps", numSteps,
                "group_size", groupSize,
                "rollouts_per_model", rolloutsPerModel,
                "total_rollouts", totalRollouts
        ));
        results.put("models_tested", new ArrayList<Map<String, Object>>());

        // 6. 逐个模型测试
        List<Map<String, Object>> modelsTested = (List<Map<String, Object>>) results.get("models_tested");
        List<Map<String, Object>> workingModels = new ArrayList<>();

        for (Map<String, String> modelInfo : testModels) {
            String modelId = modelInfo.get("id");
            String modelName = modelInfo.get("name");
            String modelScale = modelInfo.get("scale");

            log.info("[RL] 测试模型: {} ({})", modelName, modelId);

            Map<String, Object> modelResult = testSingleModel(
                    envInfo, modelId, modelName, modelScale,
                    numSteps, groupSize, openrouterKey
            );

            modelsTested.add(modelResult);

            if (modelResult.containsKey("steps_tested") && (Integer) modelResult.get("steps_tested") > 0) {
                workingModels.add(modelResult);
            }
        }

        // 7. 汇总结果
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("steps_requested", numSteps);
        summary.put("models_tested", testModels.size());
        summary.put("models_succeeded", workingModels.size());
        summary.put("environment_working", !workingModels.isEmpty());
        summary.put("output_directory", inferenceTestOutputDir.toString());

        if (!workingModels.isEmpty()) {
            // 找最佳模型
            Map<String, Object> best = workingModels.stream()
                    .max(Comparator.comparingDouble(m -> (Double) m.getOrDefault("accuracy", 0.0)))
                    .orElse(workingModels.get(0));
            summary.put("best_model", best.get("model"));

            // 计算平均准确率
            double avgAccuracy = workingModels.stream()
                    .mapToDouble(m -> (Double) m.getOrDefault("accuracy", 0.0))
                    .average().orElse(0.0);
            summary.put("avg_accuracy", Math.round(avgAccuracy * 1000.0) / 1000.0);
        }

        results.put("summary", summary);
        return results;
    }

    private Map<String, Object> testSingleModel(
            EnvironmentInfo envInfo, String modelId, String modelName, String modelScale,
            int numSteps, int groupSize, String openrouterKey) {

        String safeModelName = modelId.replace("/", "_");
        String testRunId = UUID.randomUUID().toString().substring(0, 8);
        String wandbRunName = "test_inference_" + currentEnv + "_" + safeModelName + "_" + testRunId;

        Path outputFile = inferenceTestOutputDir.resolve("test_" + currentEnv + "_" + safeModelName + ".jsonl");
        Path logFile = inferenceTestOutputDir.resolve("test_" + currentEnv + "_" + safeModelName + ".log");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", modelId);
        result.put("name", modelName);
        result.put("scale", modelScale);
        result.put("wandb_run", wandbRunName);
        result.put("output_file", outputFile.toString());
        result.put("steps", new ArrayList<Map<String, Object>>());
        result.put("steps_tested", 0);
        result.put("total_completions", 0);
        result.put("correct_completions", 0);

        // 构建 Python 命令
        List<String> cmd = new ArrayList<>();
        cmd.add("python");
        cmd.add(envInfo.getFilePath());
        cmd.add("process");
        // 测试参数
        cmd.add("--env.total_steps");
        cmd.add(String.valueOf(numSteps));
        cmd.add("--env.group_size");
        cmd.add(String.valueOf(groupSize));
        cmd.add("--env.use_wandb");
        cmd.add("true");
        cmd.add("--env.wandb_name");
        cmd.add(wandbRunName);
        cmd.add("--env.data_path_to_save_groups");
        cmd.add(outputFile.toString());
        // 锁定参数
        cmd.add("--env.tokenizer_name");
        cmd.add((String) LOCKED_ENV_FIELDS.get("tokenizer_name"));
        cmd.add("--env.max_token_length");
        cmd.add(String.valueOf(LOCKED_ENV_FIELDS.get("max_token_length")));
        cmd.add("--env.max_num_workers");
        cmd.add(String.valueOf(LOCKED_ENV_FIELDS.get("max_num_workers")));
        cmd.add("--env.max_batches_offpolicy");
        cmd.add(String.valueOf(LOCKED_ENV_FIELDS.get("max_batches_offpolicy")));
        // OpenRouter 配置
        cmd.add("--openai.base_url");
        cmd.add("https://openrouter.ai/api/v1");
        cmd.add("--openai.api_key");
        cmd.add(openrouterKey);
        cmd.add("--openai.model_name");
        cmd.add(modelId);
        cmd.add("--openai.server_type");
        cmd.add("openai");
        cmd.add("--openai.health_check");
        cmd.add("false");

        log.info("[RL] 执行命令: {} (model: {})", envInfo.getFilePath(), modelId);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(tinkerRoot.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(logFile.toFile());

            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.MINUTES);

            if (!finished) {
                process.destroyForcibly();
                result.put("error", "Process timed out after 10 minutes");
                return result;
            }

            int exitCode = process.exitValue();
            result.put("exit_code", exitCode);

            if (exitCode != 0) {
                result.put("error", "Process exited with code " + exitCode);
                // 读取日志最后几行
                try {
                    List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
                    List<String> lastLines = lines.subList(Math.max(0, lines.size() - 5), lines.size());
                    result.put("last_log_lines", lastLines);
                } catch (Exception ignored) {}
                return result;
            }

            // 解析 JSONL 输出
            if (Files.exists(outputFile)) {
                List<String> lines = Files.readAllLines(outputFile, StandardCharsets.UTF_8);
                List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");

                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> item = objectMapper.readValue(line, Map.class);
                        List<Number> scores = (List<Number>) item.get("scores");
                        if (scores != null && !scores.isEmpty()) {
                            int stepNum = (Integer) result.get("steps_tested") + 1;
                            result.put("steps_tested", stepNum);
                            result.put("total_completions", (Integer) result.get("total_completions") + scores.size());
                            int correct = (int) scores.stream().mapToDouble(Number::doubleValue).filter(s -> s > 0).count();
                            result.put("correct_completions", (Integer) result.get("correct_completions") + correct);

                            Map<String, Object> stepInfo = new LinkedHashMap<>();
                            stepInfo.put("step", stepNum);
                            stepInfo.put("completions", scores.size());
                            stepInfo.put("correct", correct);
                            stepInfo.put("scores", scores);
                            steps.add(stepInfo);
                        }
                    } catch (Exception e) {
                        log.debug("解析 JSONL 行失败: {}", e.getMessage());
                    }
                }
            } else {
                result.put("error", "Output file not created: " + outputFile);
            }

        } catch (Exception e) {
            result.put("error", e.getMessage());
            log.error("[RL] 推理测试失败: {}", e.getMessage());
        }

        // 计算准确率
        int total = (Integer) result.get("total_completions");
        int correct = (Integer) result.get("correct_completions");
        if (total > 0) {
            double accuracy = (double) correct / total;
            result.put("accuracy", Math.round(accuracy * 1000.0) / 1000.0);
        } else {
            result.put("accuracy", 0.0);
        }

        // 步骤成功率
        int stepsTested = (Integer) result.get("steps_tested");
        if (stepsTested > 0) {
            List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
            int stepsWithCorrect = (int) steps.stream().filter(s -> (Integer) s.get("correct") > 0).count();
            result.put("steps_with_correct", stepsWithCorrect);
            result.put("step_success_rate", Math.round((double) stepsWithCorrect / stepsTested * 1000.0) / 1000.0);
        } else {
            result.put("steps_with_correct", 0);
            result.put("step_success_rate", 0.0);
        }

        result.put("log_file", logFile.toString());
        return result;
    }

    /** 获取可用的测试模型列表 */
    public List<Map<String, String>> getTestModels() {
        return new ArrayList<>(TEST_MODELS);
    }

    // ── WandB 客户端（内部类） ──

    private class WandbClient {
        // 轻量实现：通过 HTTP API 获取 WandB 指标
        private static final String WANDB_API = "https://api.wandb.ai/api/v1";

        Map<String, Object> fetchMetrics(String project, String runName) {
            try {
                String apiKey = System.getenv("WANDB_API_KEY");
                if (apiKey == null) return null;

                java.net.URL url = new java.net.URL(WANDB_API + "/runs/" + project + "/" + runName);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestMethod("GET");

                if (conn.getResponseCode() == 200) {
                    try (var reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        var resp = objectMapper.readValue(sb.toString(), Map.class);
                        Map<String, Object> summary = (Map<String, Object>) resp.get("summaryMetrics");
                        if (summary != null) {
                            Map<String, Object> metrics = new LinkedHashMap<>();
                            metrics.put("step", summary.getOrDefault("_step", 0));
                            metrics.put("reward_mean", summary.get("train/reward_mean"));
                            metrics.put("percent_correct", summary.get("train/percent_correct"));
                            metrics.put("eval_percent_correct", summary.get("eval/percent_correct"));
                            return metrics;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("WandB fetch failed: {}", e.getMessage());
            }
            return null;
        }

        String getRunUrl(String project, String runName) {
            return "https://wandb.ai/" + project + "/" + runName;
        }
    }
}
