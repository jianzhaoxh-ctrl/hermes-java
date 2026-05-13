package com.hermes.agent.subagent;

import com.hermes.agent.Agent;
import com.hermes.agent.autonomous.SkillGenerator;
import com.hermes.agent.model.Message;
import com.hermes.agent.llm.LLMService;
import com.hermes.agent.memory.MemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SubAgentService {

    private static final Logger log = LoggerFactory.getLogger(SubAgentService.class);

    private final Map<String, SubAgent> activeSubAgents = new ConcurrentHashMap<>();
    private final Agent mainAgent;
    private final LLMService llmService;
    private final MemoryManager memoryManager;
    private final SkillGenerator skillGenerator;
    private long subAgentIdCounter = 1;

    public SubAgentService(
            @Lazy Agent mainAgent,
            LLMService llmService,
            MemoryManager memoryManager,
            SkillGenerator skillGenerator) {
        this.mainAgent = mainAgent;
        this.llmService = llmService;
        this.memoryManager = memoryManager;
        this.skillGenerator = skillGenerator;
    }

    public Mono<String> spawnSubAgent(String task, String sessionId) {
        String subAgentId = "sub_" + subAgentIdCounter++;
        SubAgent subAgent = new SubAgent(subAgentId, task, sessionId);
        activeSubAgents.put(subAgentId, subAgent);
        log.info("[SubAgent] Spawned {} for task: {}", subAgentId, task);

        return mainAgent.chat(subAgentId, task)
                .doOnSuccess(result -> {
                    subAgent.setStatus("completed");
                    subAgent.setResult(result);
                    log.info("[SubAgent] {} completed ({} chars)", subAgentId, result.length());

                    // ─── 机制三：技能提炼 ───
                    // 子 Agent 成功完成后，自动提炼为可复用技能
                    skillGenerator.onTaskSuccess(task, result, sessionId);
                })
                .doOnError(error -> {
                    subAgent.setStatus("failed");
                    subAgent.setError(error.getMessage());
                    log.error("[SubAgent] {} failed: {}", subAgentId, error.getMessage());
                });
    }

    public Flux<String> spawnParallelSubAgents(List<String> tasks, String sessionId) {
        return Flux.fromIterable(tasks)
                .flatMap(task -> spawnSubAgent(task, sessionId)
                        .onErrorReturn("Error: " + task), 4);
    }

    public Map<String, Object> getSubAgentInfo(String subAgentId) {
        SubAgent subAgent = activeSubAgents.get(subAgentId);
        if (subAgent == null) return Map.of("error", "Sub-agent not found");
        return subAgent.toMap();
    }

    public List<Map<String, Object>> getActiveSubAgents() {
        return activeSubAgents.values().stream()
                .map(SubAgent::toMap)
                .toList();
    }

    public void killSubAgent(String subAgentId) {
        activeSubAgents.remove(subAgentId);
        memoryManager.clearSession(subAgentId);
        log.info("[SubAgent] Killed: {}", subAgentId);
    }

    public void killAllSubAgents() {
        for (String id : new ArrayList<>(activeSubAgents.keySet())) {
            killSubAgent(id);
        }
    }

    // ─────────────────────────── SubAgent 内部类 ───────────────────────────

    public static class SubAgent {
        public final String id;
        public final String task;
        public final String parentSessionId;
        public final Instant createdAt;
        private String status;
        private String result;
        private String error;

        public SubAgent(String id, String task, String parentSessionId) {
            this.id = id;
            this.task = task;
            this.parentSessionId = parentSessionId;
            this.createdAt = Instant.now();
            this.status = "running";
        }

        public void setStatus(String status) { this.status = status; }
        public void setResult(String result) { this.result = result; }
        public void setError(String error) { this.error = error; }
        public String getStatus() { return status; }
        public String getResult() { return result; }
        public String getError() { return error; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("id", id);
            map.put("task", task);
            map.put("parentSessionId", parentSessionId);
            map.put("createdAt", createdAt.toString());
            map.put("status", status);
            if (result != null) map.put("result", result);
            if (error != null) map.put("error", error);
            return map;
        }
    }
}
