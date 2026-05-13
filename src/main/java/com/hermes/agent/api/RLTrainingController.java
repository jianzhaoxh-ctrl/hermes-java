package com.hermes.agent.api;

import com.hermes.agent.rl.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.*;

/**
 * RL 训练 REST API。
 *
 * <p>参照 Python 版 rl_training_tool.py 的 10 个工具，提供 HTTP API 接口：
 * <pre>
 * GET  /api/rl/environments           — 列出所有环境
 * POST /api/rl/environments/select     — 选择环境
 * GET  /api/rl/config                 — 获取当前配置
 * POST /api/rl/config/edit            — 修改配置
 * POST /api/rl/training/start         — 启动训练
 * GET  /api/rl/training/status/{id}   — 检查状态
 * POST /api/rl/training/stop/{id}     — 停止训练
 * GET  /api/rl/training/results/{id}  — 获取结果
 * GET  /api/rl/training/runs          — 列出所有运行
 * GET  /api/rl/requirements           — 环境就绪状态
 * POST /api/rl/trajectory/compress     — 压缩轨迹目录
 * </pre>
 */
@RestController
@RequestMapping("/api/rl")
@CrossOrigin(origins = "*")
public class RLTrainingController {

    private final RLTrainingService rlService;
    private final TrajectoryCompressor trajectoryCompressor;

    public RLTrainingController(RLTrainingService rlService, TrajectoryCompressor trajectoryCompressor) {
        this.rlService = rlService;
        this.trajectoryCompressor = trajectoryCompressor;
    }

    /** 列出所有可用环境 */
    @GetMapping("/environments")
    public Map<String, Object> listEnvironments() {
        List<EnvironmentInfo> envs = rlService.getEnvironments();
        List<Map<String, Object>> envList = envs.stream()
                .map(EnvironmentInfo::toMap)
                .toList();
        return Map.of(
                "environments", envList,
                "count", envList.size()
        );
    }

    /** 选择一个环境 */
    @PostMapping("/environments/select")
    public Map<String, Object> selectEnvironment(@RequestBody Map<String, Object> body) {
        String name = (String) body.getOrDefault("name", "");
        if (name.isBlank()) return Map.of("error", "name is required");
        return rlService.selectEnvironment(name);
    }

    /** 获取当前配置 */
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        return rlService.getCurrentConfig();
    }

    /** 修改配置 */
    @PostMapping("/config/edit")
    public Map<String, Object> editConfig(@RequestBody Map<String, Object> body) {
        String field = (String) body.getOrDefault("field", "");
        Object value = body.get("value");
        if (field.isBlank()) return Map.of("error", "field is required");
        return rlService.editConfig(field, value);
    }

    /** 启动训练 */
    @PostMapping("/training/start")
    public Map<String, Object> startTraining() {
        return rlService.startTraining();
    }

    /** 检查训练状态 */
    @GetMapping("/training/status/{runId}")
    public Map<String, Object> checkStatus(@PathVariable String runId) {
        return rlService.checkStatus(runId);
    }

    /** 停止训练 */
    @PostMapping("/training/stop/{runId}")
    public Map<String, Object> stopTraining(@PathVariable String runId) {
        return rlService.stopTraining(runId);
    }

    /** 获取训练结果 */
    @GetMapping("/training/results/{runId}")
    public Map<String, Object> getResults(@PathVariable String runId) {
        return rlService.getResults(runId);
    }

    /** 列出所有运行 */
    @GetMapping("/training/runs")
    public Map<String, Object> listRuns() {
        List<Map<String, Object>> runs = rlService.listRuns();
        return Map.of("runs", runs, "count", runs.size());
    }

    /** 检查环境就绪状态 */
    @GetMapping("/requirements")
    public Map<String, Object> getRequirements() {
        return rlService.getRequirementsStatus();
    }

    /** 批量压缩轨迹目录 */
    @PostMapping("/trajectory/compress")
    public Map<String, Object> compressTrajectories(@RequestBody Map<String, Object> body) {
        String inputDir = (String) body.getOrDefault("input_dir", "");
        String outputDir = (String) body.getOrDefault("output_dir", "");
        int maxTokens = body.containsKey("max_tokens")
                ? ((Number) body.get("max_tokens")).intValue()
                : 16384;
        int samplePercent = body.containsKey("sample_percent")
                ? ((Number) body.get("sample_percent")).intValue()
                : 0;

        if (inputDir.isBlank()) {
            return Map.of("error", "input_dir is required");
        }

        Path inPath = Path.of(inputDir);
        Path outPath = outputDir.isBlank()
                ? inPath.getParent().resolve("compressed")
                : Path.of(outputDir);

        return trajectoryCompressor.compressDirectory(inPath, outPath, maxTokens, samplePercent);
    }
}
