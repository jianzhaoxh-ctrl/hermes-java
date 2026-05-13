package com.hermes.agent.rl;

import java.time.Instant;
import java.util.Map;

/**
 * 训练运行状态。
 *
 * <p>参照 Python 版 rl_training_tool.py 的 RunState dataclass。
 * 跟踪一次 RL 训练运行的完整生命周期。
 *
 * <p>状态流转：pending → starting → running → (completed | failed | stopped)
 */
public class RunState {

    public enum Status {
        PENDING, STARTING, RUNNING, STOPPING, STOPPED, COMPLETED, FAILED
    }

    private final String runId;
    private final String environment;
    private final Map<String, Object> config;
    private Status status = Status.PENDING;
    private String errorMessage = "";
    private String wandbProject = "";
    private String wandbRunName = "";
    private Instant startTime;
    private Instant endTime;

    /** 子进程 PID（API server / trainer / environment） */
    private Long apiPid;
    private Long trainerPid;
    private Long envPid;

    public RunState(String runId, String environment, Map<String, Object> config) {
        this.runId = runId;
        this.environment = environment;
        this.config = config;
    }

    // ── Getters ──

    public String getRunId() { return runId; }
    public String getEnvironment() { return environment; }
    public Map<String, Object> getConfig() { return config; }
    public Status getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public String getWandbProject() { return wandbProject; }
    public String getWandbRunName() { return wandbRunName; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public Long getApiPid() { return apiPid; }
    public Long getTrainerPid() { return envPid; }
    public Long getEnvPid() { return envPid; }

    // ── Setters ──

    public void setStatus(Status status) { this.status = status; }
    public void setErrorMessage(String msg) { this.errorMessage = msg; }
    public void setWandbProject(String p) { this.wandbProject = p; }
    public void setWandbRunName(String n) { this.wandbRunName = n; }
    public void setStartTime(Instant t) { this.startTime = t; }
    public void setEndTime(Instant t) { this.endTime = t; }
    public void setApiPid(Long pid) { this.apiPid = pid; }
    public void setTrainerPid(Long pid) { this.trainerPid = pid; }
    public void setEnvPid(Long pid) { this.envPid = pid; }

    /** 运行时长（分钟） */
    public double getRunningTimeMinutes() {
        if (startTime == null) return 0;
        Instant end = endTime != null ? endTime : Instant.now();
        return (end.getEpochSecond() - startTime.getEpochSecond()) / 60.0;
    }

    public boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.STOPPED;
    }

    public Map<String, Object> toSummaryMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("run_id", runId);
        m.put("environment", environment);
        m.put("status", status.name().toLowerCase());
        m.put("running_time_minutes", getRunningTimeMinutes());
        m.put("wandb_project", wandbProject);
        m.put("wandb_run_name", wandbRunName);
        if (!errorMessage.isEmpty()) m.put("error", errorMessage);
        return m;
    }

    public Map<String, Object> toDetailMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>(toSummaryMap());
        m.put("config", config);

        Map<String, String> processes = new java.util.LinkedHashMap<>();
        processes.put("api", apiPid != null ? "pid=" + apiPid : "not started");
        processes.put("trainer", trainerPid != null ? "pid=" + trainerPid : "not started");
        processes.put("env", envPid != null ? "pid=" + envPid : "not started");
        m.put("processes", processes);

        return m;
    }
}
