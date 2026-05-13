package com.hermes.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.agent.config.AgentConfig;
import com.hermes.agent.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 内置记忆 Provider — MEMORY.md + USER.md 文件持久化。
 *
 * <p>参照 Python 版 memory_tool.py 的 MemoryStore 实现，核心特性：
 * <ul>
 *   <li>两个独立存储：memory（Agent 个人笔记）和 user（用户画像）</li>
 *   <li>字符上限控制（默认 memory 2200 / user 1375）</li>
 *   <li>条目级 CRUD：add / replace / remove / read</li>
 *   <li>系统提示冻结快照（前缀缓存友好）</li>
 *   <li>注入/泄露安全扫描</li>
 *   <li>原子文件写入（temp + rename）</li>
 * </ul>
 */
@Component
public class BuiltinMemoryProvider implements MemoryProvider {

    private static final Logger log = LoggerFactory.getLogger(BuiltinMemoryProvider.class);

    static final String ENTRY_DELIMITER = "\n§\n";

    private final AgentConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── 活跃状态 ──
    private final List<String> memoryEntries = new ArrayList<>();
    private final List<String> userEntries = new ArrayList<>();
    private final int memoryCharLimit;
    private final int userCharLimit;

    // ── 冻结快照（系统提示注入用，加载时捕获，会话期间不变） ──
    private String memorySnapshot = "";
    private String userSnapshot = "";

    // ── 安全扫描模式 ──
    private static final List<ThreatPattern> THREAT_PATTERNS = List.of(
            new ThreatPattern(Pattern.compile("ignore\\s+(previous|all|above|prior)\\s+instructions", Pattern.CASE_INSENSITIVE), "prompt_injection"),
            new ThreatPattern(Pattern.compile("you\\s+are\\s+now\\s+", Pattern.CASE_INSENSITIVE), "role_hijack"),
            new ThreatPattern(Pattern.compile("do\\s+not\\s+tell\\s+the\\s+user", Pattern.CASE_INSENSITIVE), "deception_hide"),
            new ThreatPattern(Pattern.compile("system\\s+prompt\\s+override", Pattern.CASE_INSENSITIVE), "sys_prompt_override"),
            new ThreatPattern(Pattern.compile("disregard\\s+(your|all|any)\\s+(instructions|rules|guidelines)", Pattern.CASE_INSENSITIVE), "disregard_rules"),
            new ThreatPattern(Pattern.compile("curl\\s+.*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)", Pattern.CASE_INSENSITIVE), "exfil_curl"),
            new ThreatPattern(Pattern.compile("wget\\s+.*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)", Pattern.CASE_INSENSITIVE), "exfil_wget"),
            new ThreatPattern(Pattern.compile("cat\\s+.*(\\.env|credentials|\\.netrc|\\.pgpass)", Pattern.CASE_INSENSITIVE), "read_secrets"),
            new ThreatPattern(Pattern.compile("authorized_keys", Pattern.CASE_INSENSITIVE), "ssh_backdoor"),
            new ThreatPattern(Pattern.compile("act\\s+as\\s+(if|though)\\s+you\\s+(have\\s+no|don.?t\\s+have)\\s+(restrictions|limits|rules)", Pattern.CASE_INSENSITIVE), "bypass_restrictions")
    );

    // ── 不可见字符 ──
    private static final Set<String> INVISIBLE_CHARS = Set.of(
            "\u200b", "\u200c", "\u200d", "\u2060", "\ufeff",
            "\u202a", "\u202b", "\u202c", "\u202d", "\u202e"
    );

    private Path memoryDir;

    public BuiltinMemoryProvider(AgentConfig config) {
        this.config = config;
        this.memoryCharLimit = 2200;
        this.userCharLimit = 1375;
    }

    @Override
    public String name() { return "builtin"; }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public void initialize(String sessionId, Map<String, Object> kwargs) {
        String dataDir = config.resolveDataDir();
        this.memoryDir = Paths.get(dataDir).resolve("memories");
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException e) {
            log.warn("[BuiltinMemory] 创建 memories 目录失败: {}", e.getMessage());
        }
        loadFromDisk();
    }

    @Override
    public String systemPromptBlock() {
        StringBuilder sb = new StringBuilder();
        if (!memorySnapshot.isEmpty()) sb.append(memorySnapshot).append("\n\n");
        if (!userSnapshot.isEmpty()) sb.append(userSnapshot);
        return sb.toString().trim();
    }

    @Override
    public void syncTurn(String userContent, String assistantContent, String sessionId) {
        // 内置 Provider 不需要每轮同步，由 memory tool 显式写入
    }

    @Override
    public List<Map<String, Object>> getToolSchemas() {
        return List.of(buildMemoryToolSchema());
    }

    @Override
    public String handleToolCall(String toolName, Map<String, Object> args) {
        if (!"memory".equals(toolName)) {
            return "{\"success\":false,\"error\":\"Unknown tool: " + toolName + "\"}";
        }

        String action = (String) args.getOrDefault("action", "");
        String target = (String) args.getOrDefault("target", "memory");
        String content = (String) args.get("content");
        String oldText = (String) args.get("old_text");

        if (!"memory".equals(target) && !"user".equals(target)) {
            return "{\"success\":false,\"error\":\"Invalid target '" + target + "'. Use 'memory' or 'user'.\"}";
        }

        try {
            Map<String, Object> result = switch (action) {
                case "add" -> add(target, content != null ? content : "");
                case "replace" -> replace(target, oldText != null ? oldText : "", content != null ? content : "");
                case "remove" -> remove(target, oldText != null ? oldText : "");
                case "read" -> read(target);
                default -> Map.<String, Object>of("success", false, "error", "Unknown action '" + action + "'. Use: add, replace, remove, read");
            };
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CRUD 操作
    // ═══════════════════════════════════════════════════════════

    public synchronized Map<String, Object> add(String target, String content) {
        content = content.trim();
        if (content.isEmpty()) return Map.of("success", false, "error", "Content cannot be empty.");

        String scanError = scanContent(content);
        if (scanError != null) return Map.of("success", false, "error", scanError);

        List<String> entries = entriesFor(target);
        int limit = limitFor(target);

        // 去重
        if (entries.contains(content)) {
            return successResponse(target, "Entry already exists (no duplicate added).");
        }

        int newTotal = charCount(entries) + ENTRY_DELIMITER.length() + content.length();
        if (newTotal > limit) {
            int current = charCount(entries);
            return Map.of("success", false, "error",
                    String.format("Memory at %d/%d chars. Adding this entry (%d chars) would exceed the limit. Replace or remove existing entries first.",
                            current, limit, content.length()));
        }

        entries.add(content);
        saveToDisk(target);
        return successResponse(target, "Entry added.");
    }

    public synchronized Map<String, Object> replace(String target, String oldText, String newContent) {
        oldText = oldText.trim();
        newContent = newContent.trim();
        if (oldText.isEmpty()) return Map.of("success", false, "error", "old_text cannot be empty.");
        if (newContent.isEmpty()) return Map.of("success", false, "error", "new_content cannot be empty. Use 'remove' to delete entries.");

        String scanError = scanContent(newContent);
        if (scanError != null) return Map.of("success", false, "error", scanError);

        List<String> entries = entriesFor(target);
        List<Integer> matchIndices = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).contains(oldText)) matchIndices.add(i);
        }

        if (matchIndices.isEmpty()) return Map.of("success", false, "error", "No entry matched '" + oldText + "'.");
        if (matchIndices.size() > 1) {
            Set<String> uniqueTexts = new HashSet<>();
            for (int idx : matchIndices) uniqueTexts.add(entries.get(idx));
            if (uniqueTexts.size() > 1) {
                return Map.of("success", false, "error", "Multiple entries matched '" + oldText + "'. Be more specific.");
            }
        }

        int idx = matchIndices.get(0);
        List<String> testEntries = new ArrayList<>(entries);
        testEntries.set(idx, newContent);
        int newTotal = charCount(testEntries);
        int limit = limitFor(target);
        if (newTotal > limit) {
            return Map.of("success", false, "error",
                    String.format("Replacement would put memory at %d/%d chars. Shorten or remove first.", newTotal, limit));
        }

        entries.set(idx, newContent);
        saveToDisk(target);
        return successResponse(target, "Entry replaced.");
    }

    public synchronized Map<String, Object> remove(String target, String oldText) {
        oldText = oldText.trim();
        if (oldText.isEmpty()) return Map.of("success", false, "error", "old_text cannot be empty.");

        List<String> entries = entriesFor(target);
        List<Integer> matchIndices = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).contains(oldText)) matchIndices.add(i);
        }

        if (matchIndices.isEmpty()) return Map.of("success", false, "error", "No entry matched '" + oldText + "'.");
        if (matchIndices.size() > 1) {
            Set<String> uniqueTexts = new HashSet<>();
            for (int idx : matchIndices) uniqueTexts.add(entries.get(idx));
            if (uniqueTexts.size() > 1) {
                return Map.of("success", false, "error", "Multiple entries matched '" + oldText + "'. Be more specific.");
            }
        }

        entries.remove(matchIndices.get(0).intValue());
        saveToDisk(target);
        return successResponse(target, "Entry removed.");
    }

    public Map<String, Object> read(String target) {
        List<String> entries = entriesFor(target);
        int current = charCount(entries);
        int limit = limitFor(target);
        return Map.of(
                "success", true,
                "target", target,
                "entries", new ArrayList<>(entries),
                "usage", String.format("%d/%d chars", current, limit),
                "entry_count", entries.size()
        );
    }

    /** 获取冻结快照（系统提示注入用） */
    public String getFrozenSnapshot(String target) {
        return "user".equals(target) ? userSnapshot : memorySnapshot;
    }

    /** 获取活跃条目（工具响应用） */
    public List<String> getLiveEntries(String target) {
        return new ArrayList<>(entriesFor(target));
    }

    // ═══════════════════════════════════════════════════════════
    //  磁盘 I/O
    // ═══════════════════════════════════════════════════════════

    public void loadFromDisk() {
        memoryEntries.clear();
        userEntries.clear();

        List<String> memEntries = readFile(memoryDir.resolve("MEMORY.md"));
        List<String> usrEntries = readFile(memoryDir.resolve("USER.md"));

        // 去重（保留首次出现）
        memoryEntries.addAll(deduplicate(memEntries));
        userEntries.addAll(deduplicate(usrEntries));

        // 捕获冻结快照
        memorySnapshot = renderBlock("memory", memoryEntries);
        userSnapshot = renderBlock("user", userEntries);

        log.info("[BuiltinMemory] 加载完成: memory={} 条, user={} 条", memoryEntries.size(), userEntries.size());
    }

    public void saveToDisk(String target) {
        if (memoryDir == null) return;
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException e) {
            log.error("[BuiltinMemory] 创建目录失败: {}", e.getMessage());
            return;
        }

        List<String> entries = entriesFor(target);
        Path path = "user".equals(target) ? memoryDir.resolve("USER.md") : memoryDir.resolve("MEMORY.md");
        writeFile(path, entries);
    }

    // ═══════════════════════════════════════════════════════════
    //  工具 Schema
    // ═══════════════════════════════════════════════════════════

    private Map<String, Object> buildMemoryToolSchema() {
        Map<String, Object> actionProp = new LinkedHashMap<>();
        actionProp.put("type", "string");
        actionProp.put("enum", List.of("add", "replace", "remove", "read"));
        actionProp.put("description", "The action to perform.");

        Map<String, Object> targetProp = new LinkedHashMap<>();
        targetProp.put("type", "string");
        targetProp.put("enum", List.of("memory", "user"));
        targetProp.put("description", "Which memory store: 'memory' for personal notes, 'user' for user profile.");

        Map<String, Object> contentProp = new LinkedHashMap<>();
        contentProp.put("type", "string");
        contentProp.put("description", "The entry content. Required for 'add' and 'replace'.");

        Map<String, Object> oldTextProp = new LinkedHashMap<>();
        oldTextProp.put("type", "string");
        oldTextProp.put("description", "Short unique substring identifying the entry to replace or remove.");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", actionProp);
        props.put("target", targetProp);
        props.put("content", contentProp);
        props.put("old_text", oldTextProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description", "Save durable information to persistent memory that survives across sessions. " +
                "TWO TARGETS: 'memory' for agent notes (environment facts, lessons learned), " +
                "'user' for user profile (name, preferences, communication style). " +
                "ACTIONS: add, replace, remove, read. " +
                "Proactively save: user corrections, preferences, environment facts, conventions. " +
                "Do NOT save task progress or temporary state.");
        schema.put("properties", props);
        schema.put("required", List.of("action", "target"));

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "memory");
        function.put("description", schema.get("description"));
        function.put("parameters", Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("action", "target")
        ));

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("function", function);
        return tool;
    }

    // ═══════════════════════════════════════════════════════════
    //  内部工具
    // ═══════════════════════════════════════════════════════════

    private List<String> entriesFor(String target) {
        return "user".equals(target) ? userEntries : memoryEntries;
    }

    private int limitFor(String target) {
        return "user".equals(target) ? userCharLimit : memoryCharLimit;
    }

    private int charCount(List<String> entries) {
        if (entries.isEmpty()) return 0;
        return String.join(ENTRY_DELIMITER, entries).length();
    }

    private String renderBlock(String target, List<String> entries) {
        if (entries.isEmpty()) return "";
        int limit = limitFor(target);
        String content = String.join(ENTRY_DELIMITER, entries);
        int current = content.length();
        int pct = Math.min(100, (int) ((double) current / limit * 100));

        String header = "user".equals(target)
                ? String.format("USER PROFILE (who the user is) [%d%% — %d/%d chars]", pct, current, limit)
                : String.format("MEMORY (your personal notes) [%d%% — %d/%d chars]", pct, current, limit);

        String separator = "═".repeat(46);
        return separator + "\n" + header + "\n" + separator + "\n" + content;
    }

    private Map<String, Object> successResponse(String target, String message) {
        List<String> entries = entriesFor(target);
        int current = charCount(entries);
        int limit = limitFor(target);
        int pct = Math.min(100, (int) ((double) current / limit * 100));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("target", target);
        resp.put("entries", new ArrayList<>(entries));
        resp.put("usage", String.format("%d%% — %d/%d chars", pct, current, limit));
        resp.put("entry_count", entries.size());
        if (message != null) resp.put("message", message);
        return resp;
    }

    private String scanContent(String content) {
        // 不可见字符
        for (String ch : INVISIBLE_CHARS) {
            if (content.contains(ch)) {
                return String.format("Blocked: content contains invisible unicode character (possible injection).");
            }
        }
        // 威胁模式
        for (ThreatPattern tp : THREAT_PATTERNS) {
            if (tp.pattern.matcher(content).find()) {
                return String.format("Blocked: content matches threat pattern '%s'. Memory entries must not contain injection or exfiltration payloads.", tp.id);
            }
        }
        return null;
    }

    private List<String> deduplicate(List<String> entries) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String e : entries) {
            if (seen.add(e)) result.add(e);
        }
        return result;
    }

    // ── 文件 I/O（原子写入） ──

    private static List<String> readFile(Path path) {
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            if (raw.isBlank()) return new ArrayList<>();
            List<String> entries = new ArrayList<>();
            for (String e : raw.split(ENTRY_DELIMITER)) {
                String trimmed = e.trim();
                if (!trimmed.isEmpty()) entries.add(trimmed);
            }
            return entries;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static void writeFile(Path path, List<String> entries) {
        String content = entries.isEmpty() ? "" : String.join(ENTRY_DELIMITER, entries);
        try {
            Files.createDirectories(path.getParent());
            // 原子写入：先写临时文件，再 rename
            Path tempFile = Files.createTempFile(path.getParent(), ".mem_", ".tmp");
            try {
                Files.writeString(tempFile, content, StandardCharsets.UTF_8);
                Files.move(tempFile, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
                throw e;
            }
        } catch (IOException e) {
            log.error("[BuiltinMemory] 写入文件失败 {}: {}", path, e.getMessage());
        }
    }

    // ── 威胁模式内部类 ──

    private record ThreatPattern(Pattern pattern, String id) {}
}
