package com.hermes.agent.api;

import com.hermes.agent.Agent;
import com.hermes.agent.autonomous.SkillGenerator;
import com.hermes.agent.memory.*;
import com.hermes.agent.model.Message;
import com.hermes.agent.persistence.PersistenceBackendFactory;
import com.hermes.agent.persistence.PersistenceService;
import com.hermes.agent.scheduler.SchedulerService;
import com.hermes.agent.skills.SkillSystem;
import com.hermes.agent.skills.model.Skill;
import com.hermes.agent.subagent.SubAgentService;
import com.hermes.agent.userprofile.UserProfileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000", "http://127.0.0.1:5173", "http://127.0.0.1:3000"},
            allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST},
            allowCredentials = "true")
public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final Agent agent;
    private final SchedulerService schedulerService;
    private final SubAgentService subAgentService;
    private final SessionSummaryManager summaryManager;
    private final UserProfileManager profileManager;
    private final SkillSystem skillSystem;
    private final SkillGenerator skillGenerator;
    private final PersistenceService persistenceService;
    private final MemoryOrchestrator memoryOrchestrator;
    private final SessionSearchService searchService;
    private final MemoryManager memoryManager;
    private final PersistenceBackendFactory backendFactory;

    public ChatController(
            Agent agent,
            SchedulerService schedulerService,
            SubAgentService subAgentService,
            SessionSummaryManager summaryManager,
            UserProfileManager profileManager,
            SkillSystem skillSystem,
            SkillGenerator skillGenerator,
            PersistenceService persistenceService,
            MemoryOrchestrator memoryOrchestrator,
            SessionSearchService searchService,
            MemoryManager memoryManager,
            PersistenceBackendFactory backendFactory) {
        this.agent = agent;
        this.schedulerService = schedulerService;
        this.subAgentService = subAgentService;
        this.summaryManager = summaryManager;
        this.profileManager = profileManager;
        this.skillSystem = skillSystem;
        this.skillGenerator = skillGenerator;
        this.persistenceService = persistenceService;
        this.memoryOrchestrator = memoryOrchestrator;
        this.searchService = searchService;
        this.memoryManager = memoryManager;
        this.backendFactory = backendFactory;
    }

    // Sessions (in-memory + on-disk history)
    private Set<String> discoverAllSessionIds() {
        Set<String> r = new LinkedHashSet<>();
        r.addAll(agent.getActiveSessions());
        try {
            File dd = persistenceService.getDataDir().toFile();
            if (dd != null && dd.exists()) {
                File sf = new File(dd, "session_messages.json");
                if (sf.exists()) {
                    String raw = new String(Files.readAllBytes(sf.toPath()), StandardCharsets.UTF_8);
                    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = om.readValue(raw, Map.class);
                    r.addAll(m.keySet());
                }
                File pf = new File(dd, "user_profiles.json");
                if (pf.exists()) {
                    String raw = new String(Files.readAllBytes(pf.toPath()), StandardCharsets.UTF_8);
                    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = om.readValue(raw, Map.class);
                    r.addAll(m.keySet());
                }
            }
        } catch (Exception e) { }
        return r;
    }

    @PostMapping("/chat")
    public Mono<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
        String sessionId = (String) request.getOrDefault("sessionId", "default");
        String message = (String) request.get("message");
        if (message == null || message.isEmpty()) {
            return Mono.just(Map.of("error", "message is required"));
        }
        message = sanitizeMessage(message);
        return agent.chat(sessionId, message)
                .map(response -> Map.<String, Object>of("response", response, "sessionId", sessionId))
                .onErrorResume(e -> {
                    log.error("[Chat] 会话 {} 对话失败", sessionId, e);
                    return Mono.just(Map.<String, Object>of("error", "Chat failed: " + e.getMessage()));
                });
    }

    private String sanitizeMessage(String msg) {
        if (msg == null) return null;
        String s = msg
            .replaceAll("[\u2018\u2019]", "'")
            .replaceAll("[\u201C\u201D]", "\"")
            .replaceAll("[\u2013\u2014]", "-")
            .replaceAll("\u00A0", " ")
            // XSS 防护：转义 HTML 标签（仅对用户输入，LLM 回复由前端负责）
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;");
        // 限制消息长度，防止超大 payload 攻击
        return s.length() > 100_000 ? s.substring(0, 100_000) : s;
    }

    @PostMapping(
            value = "/chat/stream",
            produces = "text/plain; charset=UTF-8" // 👈 改成这个！
    )
    public reactor.core.publisher.Flux<String> chatStream(
            @RequestParam String sessionId,
            @RequestParam String message) {
        return agent.chatStream(sessionId, message);
    }

    @GetMapping("/sessions")
    public Map<String, Object> getSessions() {
        List<String> all = new ArrayList<>(discoverAllSessionIds());
        return Map.of("activeSessions", all, "count", all.size());
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> clearSession(@PathVariable String sessionId) {
        agent.clearSession(sessionId);
        return Map.of("cleared", sessionId);
    }

    @GetMapping("/scheduler/jobs")
    public List<Map<String, Object>> getJobs() { return schedulerService.getAllJobs(); }

    @PostMapping("/scheduler/jobs")
    public Map<String, Object> scheduleJob(@RequestBody Map<String, Object> request) {
        String cron = (String) request.get("cron");
        String task = (String) request.get("task");
        String sessionId = (String) request.getOrDefault("sessionId", "default");
        if (cron == null || task == null) return Map.of("error", "cron and task are required");
        String jobId = schedulerService.scheduleCron(cron, task, sessionId);
        return Map.of("jobId", jobId, "scheduled", true);
    }

    @DeleteMapping("/scheduler/jobs/{jobId}")
    public Map<String, Object> cancelJob(@PathVariable String jobId) {
        schedulerService.cancelJob(jobId);
        return Map.of("cancelled", jobId);
    }

    @GetMapping("/subagents")
    public List<Map<String, Object>> getSubAgents() { return subAgentService.getActiveSubAgents(); }

    @PostMapping("/subagents")
    public Mono<Map<String, Object>> spawnSubAgent(@RequestBody Map<String, Object> request) {
        String task = (String) request.get("task");
        String sessionId = (String) request.getOrDefault("sessionId", "default");
        if (task == null) return Mono.just(Map.of("error", "task is required"));
        return subAgentService.spawnSubAgent(task, sessionId)
                .map(r -> Map.<String, Object>of("result", r, "task", task))
                .onErrorReturn(Map.of("error", "Sub-agent failed"));
    }

    @DeleteMapping("/subagents/{subAgentId}")
    public Map<String, Object> killSubAgent(@PathVariable String subAgentId) {
        subAgentService.killSubAgent(subAgentId);
        return Map.of("killed", subAgentId);
    }

    @GetMapping("/skills")
    public Map<String, Object> getSkills(@RequestParam(required = false) String search) {
        List<Skill> src;
        if (search != null && !search.isBlank()) {
            src = skillSystem.searchSkills(search);
        } else {
            src = skillSystem.getAllSkillNames().stream()
                    .map(n -> skillSystem.getSkill(n).orElse(null))
                    .filter(Objects::nonNull).toList();
        }
        List<Map<String, Object>> res = src.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.getName());
            m.put("description", s.getDescription());
            m.put("autoGenerated", skillSystem.isAutoGenerated(s.getName()));
            return m;
        }).toList();
        return Map.of("skills", res, "count", res.size());
    }

    @DeleteMapping("/skills/{name}")
    public Map<String, Object> deleteSkill(@PathVariable String name) {
        boolean deleted = skillSystem.deleteSkill(name);
        return Map.of("deleted", deleted, "name", name);
    }

    @GetMapping("/skills/{name}")
    public ResponseEntity<Skill> getSkillByName(@PathVariable String name) {
        return skillSystem.getSkill(name)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/skills/search")
    public List<Skill> searchSkills(@RequestParam String q) {
        return skillSystem.searchSkills(q);
    }

    @PostMapping("/skills/reload")
    public Map<String, Object> reloadSkills() {
        skillSystem.reload();
        return Map.of("reloaded", true, "count", skillSystem.getAllSkillNames().size());
    }

    @GetMapping("/memory/summary/{sessionId}")
    public Map<String, Object> getSummaryStatus(@PathVariable String sessionId) {
        int cnt = summaryManager.getSummaryCount(sessionId);
        Optional<String> last = summaryManager.getLastSummary(sessionId);
        int msgs = agent.getSessionMessageCount(sessionId);
        return Map.of(
            "sessionId", sessionId,
            "summaryCount", cnt,
            "lastSummary", last.orElse(""),
            "messageCount", msgs,
            "threshold", 50,
            "needsSummary", msgs >= 50
        );
    }

    @GetMapping("/memory/summaries")
    public Map<String, Object> getAllSummaryStatuses() {
        Set<String> ss = discoverAllSessionIds();
        List<Map<String, Object>> lst = new ArrayList<>();
        for (String sid : ss) {
            int cnt = summaryManager.getSummaryCount(sid);
            Optional<String> last = summaryManager.getLastSummary(sid);
            int msgs = agent.getSessionMessageCount(sid);
            lst.add(new LinkedHashMap<>(Map.of(
                "sessionId", sid,
                "summaryCount", cnt,
                "lastSummary", last.orElse(""),
                "messageCount", msgs,
                "threshold", 50,
                "needsSummary", msgs >= 50,
                "compressed", cnt > 0
            )));
        }
        return Map.of("sessions", lst, "total", ss.size());
    }

    @GetMapping("/memory/profile/{sessionId}")
    public Map<String, Object> getProfile(@PathVariable String sessionId) {
        return profileManager.getSummary(sessionId);
    }

    @GetMapping("/memory/profiles")
    public Map<String, Object> getAllProfiles() {
        List<Map<String, Object>> ps = new ArrayList<>();
        for (String sid : discoverAllSessionIds()) ps.add(profileManager.getSummary(sid));
        return Map.of("profiles", ps, "total", ps.size());
    }

    @PostMapping("/memory/skills/generate")
    public Map<String, Object> triggerSkillGeneration() {
        int cnt = skillGenerator.getAutoGeneratedCount();
        return Map.of(
            "triggered", true,
            "totalAutoSkills", cnt,
            "message", cnt > 0 ? "Has " + cnt + " auto skills" : "No auto skills yet"
        );
    }

    @GetMapping("/memory/stats")
    public Map<String, Object> getMemoryStats() {
        Set<String> ss = discoverAllSessionIds();
        int totalS = ss.stream().mapToInt(s -> summaryManager.getSummaryCount(s)).sum();
        Map<String, Object> ss2 = skillSystem.getSkillStats();
        Map<String, Object> searchStats = searchService.getStats();

        List<Map<String, Object>> memoryTypes = new ArrayList<>();
        memoryTypes.add(Map.of("type", "SessionSummary", "enabled", true,
                "description", "Auto-compress history after 50 messages"));
        memoryTypes.add(Map.of("type", "UserProfile", "enabled", true,
                "description", "Extract user profile after each session"));
        memoryTypes.add(Map.of("type", "SkillGeneration", "enabled", true,
                "description", "Auto-generate skills after 3+ similar tasks"));
        memoryTypes.add(Map.of("type", "CuratedMemory", "enabled", true,
                "description", "Persistent MEMORY.md + USER.md via memory tool"));
        memoryTypes.add(Map.of("type", "SessionSearch", "enabled", true,
                "description", "Full-text search across all sessions"));

        return Map.of(
            "activeSessions", ss.size(),
            "totalSummaries", totalS,
            "totalSkills", ss2.getOrDefault("total_skills", 0),
            "autoSkills", skillGenerator.getAutoGeneratedCount(),
            "registeredSkills", ss2.getOrDefault("skill_names", List.of()),
            "searchIndex", searchStats,
            "memoryProviders", memoryOrchestrator.getProviders().stream()
                    .map(p -> Map.of("name", p.name(), "available", p.isAvailable()))
                    .toList(),
            "memoryTypes", memoryTypes
        );
    }

    // ═══════════════════════════════════════════════════════════
    //  会话消息 API（聊天历史加载）
    // ═══════════════════════════════════════════════════════════

    /**
     * 获取指定会话的所有消息（role + content），供前端聊天界面展示历史记录。
     * 同时从 Agent.inMemorySessions 和 SQLite 双重读取，保证完整性。
     */
    @GetMapping("/messages")
    public Map<String, Object> getMessages(@RequestParam String sessionId,
                                          @RequestParam(required = false, defaultValue = "100") int limit) {
        // 直接从数据库读取历史消息（按时间正序，最多 limit 条）
        List<Map<String, Object>> dbMessages = backendFactory.getDefaultBackend().getSessionMessages(sessionId, limit);

        // 从 Agent 内存中读取当前会话的消息（可能尚未持久化）
        List<Map<String, Object>> fromAgent = agent.getInMemoryMessages(sessionId);

        // 合并去重：数据库消息以 id 为 key，内存消息补漏
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();

        // 先放数据库的消息（有 id）
        for (Map<String, Object> m : dbMessages) {
            String key = String.valueOf(m.getOrDefault("id", ""));
            if (!key.isEmpty()) {
                merged.put(key, m);
            }
        }

        // 再放内存的消息（如果 key 不存在）
        for (Map<String, Object> m : fromAgent) {
            String id = String.valueOf(m.getOrDefault("id", ""));
            String key = !id.isEmpty() ? id : String.valueOf(m.getOrDefault("timestamp", ""));
            if (!merged.containsKey(key)) {
                merged.put(key, m);
            }
        }

        List<Map<String, Object>> messages = new ArrayList<>(merged.values());
        // 只保留 role / content / timestamp 三个必要字段，降低传输量
        List<Map<String, Object>> result = messages.stream().map(m -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", m.getOrDefault("role", ""));
            entry.put("content", m.getOrDefault("content", ""));
            entry.put("timestamp", m.getOrDefault("timestamp", 0L));
            Object tc = m.get("toolCallId");
            if (tc != null) entry.put("toolCallId", tc);
            return entry;
        }).collect(Collectors.toList());

        Map<String, Object> response = Map.of(
            "sessionId", sessionId,
            "messages", (Object) result,
            "count", result.size()
        );
        return response;
    }

    // ═══════════════════════════════════════════════════════════
    //  持久化记忆 API（MEMORY.md / USER.md）
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/memory/curated/{target}")
    public Map<String, Object> getCuratedMemory(@PathVariable String target) {
        if (!"memory".equals(target) && !"user".equals(target)) {
            return Map.of("error", "Invalid target. Use 'memory' or 'user'.");
        }
        BuiltinMemoryProvider builtin = memoryOrchestrator.getBuiltinProvider();
        return builtin.read(target);
    }

    @PostMapping("/memory/curated")
    public Map<String, Object> curatedMemoryAction(@RequestBody Map<String, Object> request) {
        String action = (String) request.getOrDefault("action", "");
        String target = (String) request.getOrDefault("target", "memory");
        String content = (String) request.get("content");
        String oldText = (String) request.get("old_text");

        Map<String, Object> toolArgs = new LinkedHashMap<>();
        toolArgs.put("action", action);
        toolArgs.put("target", target);
        if (content != null) toolArgs.put("content", content);
        if (oldText != null) toolArgs.put("old_text", oldText);

        String result = memoryOrchestrator.handleToolCall("memory", toolArgs);
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            return om.readValue(result, Map.class);
        } catch (Exception e) {
            return Map.of("raw", result);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  会话搜索 API
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/memory/search")
    public Map<String, Object> searchHistory(@RequestParam String query,
                                              @RequestParam(required = false) String sessionId,
                                              @RequestParam(required = false, defaultValue = "10") int limit) {
        List<SessionSearchService.SearchResult> results =
                searchService.search(query, limit, sessionId);

        List<Map<String, Object>> items = results.stream()
                .map(SessionSearchService.SearchResult::toMap)
                .toList();

        return Map.of(
            "query", query,
            "results", items,
            "count", items.size()
        );
    }

    @GetMapping("/memory/search/stats")
    public Map<String, Object> getSearchStats() {
        return searchService.getStats();
    }
}
