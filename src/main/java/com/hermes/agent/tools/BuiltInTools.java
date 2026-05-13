package com.hermes.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Component
public class BuiltInTools {
    private static final Logger log = LoggerFactory.getLogger(BuiltInTools.class);
    private final ToolRegistry registry;
    private final com.hermes.agent.memory.MemoryOrchestrator memoryOrchestrator;
    private final com.hermes.agent.memory.SessionSearchService searchService;
    private final com.hermes.agent.memory.longterm.CrossSessionSearchService crossSessionSearchService;
    private final com.hermes.agent.memory.longterm.LongTermMemoryManager longTermMemoryManager;
    /** HttpClient configured to follow redirects automatically */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** Dedicated scheduler for blocking I/O operations (HTTP, process exec) */
    private final Scheduler blockingScheduler = Schedulers.boundedElastic();

    public BuiltInTools(ToolRegistry registry,
                        com.hermes.agent.memory.MemoryOrchestrator memoryOrchestrator,
                        com.hermes.agent.memory.SessionSearchService searchService,
                        com.hermes.agent.memory.longterm.CrossSessionSearchService crossSessionSearchService,
                        com.hermes.agent.memory.longterm.LongTermMemoryManager longTermMemoryManager) {
        this.registry = registry;
        this.memoryOrchestrator = memoryOrchestrator;
        this.searchService = searchService;
        this.crossSessionSearchService = crossSessionSearchService;
        this.longTermMemoryManager = longTermMemoryManager;
        registerTools();
    }

    private Map<String, Object> objectSchema(String type, String desc, Map<String, Object> props, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", type);
        if (desc != null && !desc.isEmpty()) s.put("description", desc);
        if (props != null) s.put("properties", props);
        if (required != null) s.put("required", required);
        return s;
    }

    private Map<String, Object> paramObj(Map<String, Object> props, List<String> required) {
        return objectSchema("object", null, props, required);
    }

    private void registerTools() {

        // ── 1. web_search ──────────────────────────────────────────────────
        // Uses Bing HTML scraping (works in China). Runs on boundedElastic so
        // it never blocks the Netty event-loop thread.
        Map<String, Object> wsProps = new LinkedHashMap<>();
        wsProps.put("query", objectSchema("string", "The search query to look up", null, null));
        registry.register("web_search",
                "Search the web for real-time information. Use this when user asks about current events, prices, weather, news, stock data, or anything needing up-to-date data from the internet.",
                paramObj(wsProps, List.of("query")),
                args -> {
                    String query = extractString(args, "query");
                    if (query.isEmpty()) return Mono.just("Error: web_search requires 'query' parameter");

                    // Offload the blocking HTTP call to boundedElastic pool
                    return Mono.fromCallable(() -> {
                        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
                        String searchUrl = "https://www.bing.com/search?q=" + encoded + "&setlang=zh-CN&cc=CN";
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(searchUrl))
                                .timeout(Duration.ofSeconds(12))
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                .header("Accept-Language", "zh-CN,zh;q=0.9")
                                .header("Accept", "text/html,application/xhtml+xml")
                                .GET().build();
                        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                        return response.body();
                    })
                    .subscribeOn(blockingScheduler)
                    .timeout(Duration.ofSeconds(15))
                    .map(html -> {
                        // Strip scripts / styles before regex
                        html = html.replaceAll("(?i)<script[^>]*>.*?</script>", "");
                        html = html.replaceAll("(?i)<style[^>]*>.*?</style>", "");

                        StringBuilder sb = new StringBuilder();
                        sb.append("Search results for: ").append(query).append("\n\n");

                        java.util.regex.Pattern liPattern = java.util.regex.Pattern.compile(
                                "<li[^>]*class=\"[^\"]*b_algo[^\"]*\"[^>]*>(.*?)</li>",
                                java.util.regex.Pattern.DOTALL);
                        java.util.regex.Matcher liMatcher = liPattern.matcher(html);

                        int count = 0;
                        while (liMatcher.find() && count < 5) {
                            String li = liMatcher.group(1);

                            // Title
                            String title = "";
                            java.util.regex.Matcher am = java.util.regex.Pattern
                                    .compile("<a[^>]*aria-label=\"([^\"]{3,200})\"", java.util.regex.Pattern.DOTALL)
                                    .matcher(li);
                            if (am.find()) title = htmlToText(am.group(1));
                            else {
                                java.util.regex.Matcher hm = java.util.regex.Pattern
                                        .compile("<h2[^>]*>(.*?)</h2>", java.util.regex.Pattern.DOTALL)
                                        .matcher(li);
                                if (hm.find()) title = htmlToText(hm.group(1));
                            }
                            if (title.isEmpty() || title.startsWith("http")) continue;

                            // Snippet
                            String snippet = "";
                            java.util.regex.Matcher pm = java.util.regex.Pattern
                                    .compile("<p[^>]*>(.*?)</p>", java.util.regex.Pattern.DOTALL)
                                    .matcher(li);
                            if (pm.find()) snippet = htmlToText(pm.group(1)).trim();

                            // URL
                            String url = "";
                            java.util.regex.Matcher um = java.util.regex.Pattern
                                    .compile("href=\"(https?://[^\"]{10,200})\"")
                                    .matcher(li);
                            if (um.find()) url = um.group(1);

                            if (snippet.length() > 15) {
                                sb.append(++count).append(". ").append(title).append("\n");
                                if (!url.isEmpty()) sb.append("   ").append(url, 0, Math.min(80, url.length())).append("\n");
                                sb.append("   ").append(snippet.length() > 280 ? snippet.substring(0, 280) + "..." : snippet).append("\n\n");
                            }
                        }
                        String result = sb.toString().trim();
                        return result.equals("Search results for: " + query)
                                ? "No results found for: " + query : result;
                    })
                    .onErrorResume(e -> {
                        log.warn("Web search failed: {}", e.getMessage());
                        return Mono.just("Search failed: " + e.getMessage());
                    });
                });

        // ── 2. web_fetch ───────────────────────────────────────────────────
        Map<String, Object> wfProps = new LinkedHashMap<>();
        wfProps.put("url", objectSchema("string", "The URL to fetch content from", null, null));
        registry.register("web_fetch",
                "Fetch and extract the content of a specific URL. Use when user provides a link and wants its content.",
                paramObj(wfProps, List.of("url")),
                args -> {
                    String url = extractString(args, "url");
                    if (url.isEmpty()) return Mono.just("Error: url parameter is required");

                    return Mono.fromCallable(() -> {
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .timeout(Duration.ofSeconds(12))
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                .header("Accept-Language", "zh-CN,zh;q=0.9")
                                .GET().build();
                        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                        return response.body();
                    })
                    .subscribeOn(blockingScheduler)
                    .timeout(Duration.ofSeconds(15))
                    .map(body -> {
                        body = body.replaceAll("(?i)<script[^>]*>.*?</script>", "");
                        body = body.replaceAll("(?i)<style[^>]*>.*?</style>", "");
                        body = body.replaceAll("<[^>]+>", " ");
                        body = body.replaceAll("\\s+", " ").trim();
                        String truncated = body.length() > 4000 ? body.substring(0, 4000) + "\n...(truncated)" : body;
                        return "URL: " + url + "\n\n" + truncated;
                    })
                    .onErrorResume(e -> Mono.just("Error fetching URL: " + e.getMessage()));
                });

        // ── 3. python_exec ─────────────────────────────────────────────────
        Map<String, Object> pyProps = new LinkedHashMap<>();
        pyProps.put("code", objectSchema("string", "The Python source code to execute", null, null));
        registry.register("python_exec",
                "Execute Python code and return stdout/stderr. Use for calculations, data processing, running scripts.",
                paramObj(pyProps, List.of("code")),
                args -> {
                    String code = extractString(args, "code");
                    if (code.isEmpty()) return Mono.just("Error: code parameter is required");

                    return Mono.fromCallable(() -> {
                        Path tempFile = Files.createTempFile("hermes_", ".py");
                        Files.writeString(tempFile, code, StandardCharsets.UTF_8);
                        ProcessBuilder pb = new ProcessBuilder("python", tempFile.toString());
                        pb.redirectErrorStream(false);
                        Process process = pb.start();
                        StringBuilder out = new StringBuilder();
                        StringBuilder err = new StringBuilder();
                        try (BufferedReader r = new BufferedReader(
                                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                             BufferedReader er = new BufferedReader(
                                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = r.readLine()) != null) out.append(line).append("\n");
                            while ((line = er.readLine()) != null) err.append(line).append("\n");
                            boolean done = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
                            if (!done) { process.destroyForcibly(); return "Error: Execution timed out (15s)"; }
                        } finally {
                            Files.deleteIfExists(tempFile);
                        }
                        String result = out.toString().trim();
                        if (!err.isEmpty()) result += "\nSTDERR: " + err.toString().trim();
                        return result.isEmpty() ? "(no output)" : result;
                    })
                    .subscribeOn(blockingScheduler)
                    .timeout(Duration.ofSeconds(20))
                    .onErrorResume(e -> Mono.just("Python execution error: " + e.getMessage()));
                });

        // ── 4. bash_exec ───────────────────────────────────────────────────
        Map<String, Object> bhProps = new LinkedHashMap<>();
        bhProps.put("command", objectSchema("string", "The shell command to execute", null, null));
        registry.register("bash_exec",
                "Execute a shell/bash command on the system. Use for file management, git operations, system tasks.",
                paramObj(bhProps, List.of("command")),
                args -> {
                    String command = extractString(args, "command");
                    if (command.isEmpty()) return Mono.just("Error: command parameter is required");

                    return Mono.fromCallable(() -> {
                        Process process = Runtime.getRuntime().exec(command);
                        StringBuilder out = new StringBuilder();
                        try (BufferedReader r = new BufferedReader(
                                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = r.readLine()) != null) out.append(line).append("\n");
                            boolean done = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
                            if (!done) { process.destroyForcibly(); return "Error: Command timed out (15s)"; }
                        }
                        String result = out.toString().trim();
                        return result.isEmpty() ? "(no output)" : result;
                    })
                    .subscribeOn(blockingScheduler)
                    .timeout(Duration.ofSeconds(20))
                    .onErrorResume(e -> Mono.just("Bash execution error: " + e.getMessage()));
                });

        // ── 5. file_read ───────────────────────────────────────────────────
        Map<String, Object> frProps = new LinkedHashMap<>();
        frProps.put("path", objectSchema("string", "Absolute or relative path of the file to read", null, null));
        registry.register("file_read",
                "Read the full content of a local file from the filesystem.",
                paramObj(frProps, List.of("path")),
                args -> {
                    String path = extractString(args, "path");
                    if (path.isEmpty()) return Mono.just("Error: path parameter is required");
                    try {
                        Path filePath = Paths.get(path);
                        if (!Files.exists(filePath)) return Mono.just("Error: File not found: " + path);
                        long size = Files.size(filePath);
                        if (size > 500_000) return Mono.just("File too large (>500KB). Size: " + size + " bytes");
                        String content = Files.readString(filePath, StandardCharsets.UTF_8);
                        String truncated = content.length() > 5000 ? content.substring(0, 5000) + "\n...(truncated)" : content;
                        return Mono.just("File: " + path + "\n\n" + truncated);
                    } catch (Exception e) {
                        return Mono.just("File read error: " + e.getMessage());
                    }
                });

        // ── 6. file_write ─────────────────────────────────────────────────
        Map<String, Object> fwProps = new LinkedHashMap<>();
        fwProps.put("path", objectSchema("string", "Destination file path", null, null));
        fwProps.put("content", objectSchema("string", "Content to write into the file", null, null));
        registry.register("file_write",
                "Write content to a local file. Creates the file and all parent directories if they do not exist.",
                paramObj(fwProps, List.of("path", "content")),
                args -> {
                    String path = extractString(args, "path");
                    String content = extractString(args, "content");
                    if (path.isEmpty()) return Mono.just("Error: path parameter is required");
                    try {
                        Path filePath = Paths.get(path);
                        Files.createDirectories(filePath.getParent());
                        Files.writeString(filePath, content, StandardCharsets.UTF_8);
                        return Mono.just("Successfully wrote " + content.length() + " characters to: " + path);
                    } catch (Exception e) {
                        return Mono.just("File write error: " + e.getMessage());
                    }
                });

        // ── 7. get_time ─────────────────────────────────────────────────────
        Map<String, Object> gtProps = new LinkedHashMap<>();
        registry.register("get_time",
                "Get the current date and time. Returns ISO format timestamp.",
                paramObj(gtProps, Collections.emptyList()),
                args -> {
                    LocalDateTime now = LocalDateTime.now();
                    return Mono.just("Current time: " + now.toString()
                            + "\nDate: " + now.toLocalDate()
                            + "\nTime: " + now.toLocalTime());
                });

        // ── 8. calculate ───────────────────────────────────────────────────
        Map<String, Object> calcProps = new LinkedHashMap<>();
        calcProps.put("expression", objectSchema("string",
                "Mathematical expression to evaluate, e.g. \"2+2\" or \"Math.sqrt(16)\"", null, null));
        registry.register("calculate",
                "Evaluate a mathematical expression and return the numeric result. Supports JavaScript math syntax.",
                paramObj(calcProps, List.of("expression")),
                args -> {
                    String expr = extractString(args, "expression");
                    if (expr.isEmpty()) return Mono.just("Error: expression parameter is required");
                    try {
                        javax.script.ScriptEngineManager m = new javax.script.ScriptEngineManager();
                        javax.script.ScriptEngine engine = m.getEngineByName("JavaScript");
                        Object result = engine.eval(expr);
                        return Mono.just(expr + " = " + result);
                    } catch (Exception e) {
                        return Mono.just("Calculation error: " + e.getMessage());
                    }
                });

        // ── 9. memory ───────────────────────────────────────────────────
        // 持久化记忆工具：通过 MemoryOrchestrator 路由到 BuiltinMemoryProvider
        Map<String, Object> memActionProp = new LinkedHashMap<>();
        memActionProp.put("type", "string");
        memActionProp.put("enum", List.of("add", "replace", "remove", "read"));
        memActionProp.put("description", "The action: add, replace, remove, or read");
        Map<String, Object> memTargetProp = new LinkedHashMap<>();
        memTargetProp.put("type", "string");
        memTargetProp.put("enum", List.of("memory", "user"));
        memTargetProp.put("description", "Which store: 'memory' for agent notes, 'user' for user profile");
        Map<String, Object> memContentProp = new LinkedHashMap<>();
        memContentProp.put("type", "string");
        memContentProp.put("description", "The entry content. Required for add/replace.");
        Map<String, Object> memOldTextProp = new LinkedHashMap<>();
        memOldTextProp.put("type", "string");
        memOldTextProp.put("description", "Short unique substring identifying entry to replace/remove.");
        Map<String, Object> memProps = new LinkedHashMap<>();
        memProps.put("action", memActionProp);
        memProps.put("target", memTargetProp);
        memProps.put("content", memContentProp);
        memProps.put("old_text", memOldTextProp);
        registry.register("memory",
                "Save durable information to persistent memory that survives across sessions. " +
                "TWO TARGETS: 'memory' for agent notes (environment facts, lessons learned), " +
                "'user' for user profile (name, preferences, communication style). " +
                "ACTIONS: add (new entry), replace (update existing), remove (delete), read (view). " +
                "Proactively save: user corrections, preferences, environment facts, conventions. " +
                "Do NOT save task progress or temporary state.",
                paramObj(memProps, List.of("action", "target")),
                args -> {
                    String action = extractString(args, "action");
                    String target = extractString(args, "target");
                    if (action.isEmpty()) return Mono.just("Error: action is required");
                    Map<String, Object> toolArgs = new LinkedHashMap<>();
                    toolArgs.put("action", action);
                    toolArgs.put("target", target.isEmpty() ? "memory" : target);
                    if (args.containsKey("content")) toolArgs.put("content", args.get("content"));
                    if (args.containsKey("old_text")) toolArgs.put("old_text", args.get("old_text"));
                    String result = memoryOrchestrator.handleToolCall("memory", toolArgs);
                    return Mono.just(result);
                });

        // ── 10. session_search ──────────────────────────────────────────
        // 跨会话全文搜索历史对话
        Map<String, Object> ssQueryProp = objectSchema("string", "Search query to find in past conversations", null, null);
        Map<String, Object> ssLimitProp = objectSchema("string", "Maximum results to return (default 10)", null, null);
        Map<String, Object> ssSessionProp = objectSchema("string", "Optional session ID to limit search scope", null, null);
        Map<String, Object> ssProps = new LinkedHashMap<>();
        ssProps.put("query", ssQueryProp);
        ssProps.put("limit", ssLimitProp);
        ssProps.put("session_id", ssSessionProp);
        registry.register("session_search",
                "Search past conversation history for relevant information. " +
                "Use when you need to recall something from previous sessions, " +
                "find past decisions, or look up details discussed earlier. " +
                "Returns matching conversation excerpts sorted by relevance.",
                paramObj(ssProps, List.of("query")),
                args -> {
                    String query = extractString(args, "query");
                    if (query.isEmpty()) return Mono.just("Error: query parameter is required");
                    int limit = 10;
                    String limitStr = extractString(args, "limit");
                    if (!limitStr.isEmpty()) {
                        try { limit = Integer.parseInt(limitStr); } catch (NumberFormatException ignored) {}
                    }
                    String sessionId = extractString(args, "session_id");

                    List<com.hermes.agent.memory.SessionSearchService.SearchResult> results =
                            searchService.search(query, limit, sessionId.isEmpty() ? null : sessionId);

                    if (results.isEmpty()) {
                        return Mono.just("No results found for: " + query);
                    }

                    StringBuilder sb = new StringBuilder();
                    sb.append("Search results for: ").append(query).append(" (").append(results.size()).append(" matches)\n\n");
                    for (int i = 0; i < results.size(); i++) {
                        var r = results.get(i);
                        sb.append(i + 1).append(". [").append(r.document.role).append("] ");
                        sb.append("(session: ").append(r.document.sessionId).append(")\n");
                        sb.append("   ").append(r.document.content.length() > 200
                                ? r.document.content.substring(0, 200) + "..."
                                : r.document.content).append("\n\n");
                    }
                    return Mono.just(sb.toString().trim());
                });

        // ── 11. memory_search ──────────────────────────────────────────
        // 跨会话长期记忆搜索（混合搜索：记忆 + 消息 + 摘要）
        Map<String, Object> msQueryProp = objectSchema("string", "Search query to find in long-term memory", null, null);
        Map<String, Object> msLimitProp = objectSchema("string", "Maximum results per category (default 5)", null, null);
        Map<String, Object> msUserIdProp = objectSchema("string", "User ID to scope search", null, null);
        Map<String, Object> msProps = new LinkedHashMap<>();
        msProps.put("query", msQueryProp);
        msProps.put("limit", msLimitProp);
        msProps.put("user_id", msUserIdProp);
        registry.register("memory_search",
                "Search across all long-term memories, past conversations, and session summaries. " +
                "Unlike session_search (single session), this searches ALL sessions and memories. " +
                "Returns memories, message matches, and relevant session summaries.",
                paramObj(msProps, List.of("query")),
                args -> {
                    String query = extractString(args, "query");
                    if (query.isEmpty()) return Mono.just("Error: query is required");
                    int limit = 5;
                    String limitStr = extractString(args, "limit");
                    if (!limitStr.isEmpty()) {
                        try { limit = Integer.parseInt(limitStr); } catch (NumberFormatException ignored) {}
                    }
                    String userId = extractString(args, "user_id");
                    if (userId.isEmpty()) userId = "global";

                    var hybridResult = crossSessionSearchService.hybridSearch(query, userId, limit);

                    if (hybridResult.isEmpty()) {
                        return Mono.just("No results found in long-term memory for: " + query);
                    }

                    StringBuilder sb = new StringBuilder();
                    sb.append("Long-term memory search: ").append(query)
                      .append(" (").append(hybridResult.totalCount()).append(" total matches)\n\n");

                    if (!hybridResult.getMemories().isEmpty()) {
                        sb.append("## 长期记忆\n");
                        for (int i = 0; i < hybridResult.getMemories().size(); i++) {
                            var m = hybridResult.getMemories().get(i);
                            sb.append(i + 1).append(". [").append(m.getCategory().getValue())
                              .append("] importance=").append(String.format("%.1f", m.getImportance()))
                              .append("\n   ").append(m.getContent().length() > 200
                                      ? m.getContent().substring(0, 200) + "..."
                                      : m.getContent()).append("\n\n");
                        }
                    }

                    if (!hybridResult.getMessages().isEmpty()) {
                        sb.append("## 对话消息\n");
                        for (int i = 0; i < hybridResult.getMessages().size(); i++) {
                            var m = hybridResult.getMessages().get(i);
                            sb.append(i + 1).append(". [").append(m.getRole())
                              .append("] session=").append(m.getSessionId()).append("\n")
                              .append("   ").append(m.getSnippet()).append("\n\n");
                        }
                    }

                    if (!hybridResult.getSummaries().isEmpty()) {
                        sb.append("## 会话摘要\n");
                        for (int i = 0; i < hybridResult.getSummaries().size(); i++) {
                            var s = hybridResult.getSummaries().get(i);
                            sb.append(i + 1).append(". ").append(s.getTitle() != null ? s.getTitle() : s.getSessionId())
                              .append(" (importance=").append(String.format("%.1f", s.getImportance())).append(")\n");
                            if (s.getActiveTask() != null) {
                                sb.append("   Task: ").append(s.getActiveTask()).append("\n");
                            }
                            if (s.getGoal() != null) {
                                sb.append("   Goal: ").append(s.getGoal()).append("\n");
                            }
                            sb.append("\n");
                        }
                    }

                    return Mono.just(sb.toString().trim());
                });

        // ── 12. memory_write ──────────────────────────────────────────
        // 显式写入长期记忆
        Map<String, Object> mwContentProp = objectSchema("string", "Content to store in long-term memory", null, null);
        Map<String, Object> mwCategoryProp = objectSchema("string", "Category: fact, preference, lesson, decision, context (default: fact)", null, null);
        Map<String, Object> mwImportanceProp = objectSchema("string", "Importance score 0.0-1.0 (default: 0.5)", null, null);
        Map<String, Object> mwProps = new LinkedHashMap<>();
        mwProps.put("content", mwContentProp);
        mwProps.put("category", mwCategoryProp);
        mwProps.put("importance", mwImportanceProp);
        registry.register("memory_write",
                "Explicitly save important information to long-term memory. " +
                "Use for: key decisions, important facts, lessons learned, user preferences, " +
                "project context that should persist across sessions. " +
                "Categories: fact (客观事实), preference (用户偏好), lesson (经验教训), " +
                "decision (关键决策), context (项目上下文).",
                paramObj(mwProps, List.of("content")),
                args -> {
                    String content = extractString(args, "content");
                    if (content.isEmpty()) return Mono.just("Error: content is required");
                    String categoryStr = extractString(args, "category");
                    if (categoryStr.isEmpty()) categoryStr = "fact";
                    double importance = 0.5;
                    String importanceStr = extractString(args, "importance");
                    if (!importanceStr.isEmpty()) {
                        try { importance = Double.parseDouble(importanceStr); } catch (NumberFormatException ignored) {}
                    }

                    try {
                        com.hermes.agent.memory.longterm.LongTermMemory.Category category =
                                com.hermes.agent.memory.longterm.LongTermMemory.Category.fromValue(categoryStr);
                        String memoryId = longTermMemoryManager.add(
                                content, category, importance, "global", null, "用户显式写入").getId();
                        return Mono.just("Memory saved: " + memoryId +
                                " [" + category.getValue() + "] importance=" +
                                String.format("%.1f", importance));
                    } catch (Exception e) {
                        return Mono.just("Error saving memory: " + e.getMessage());
                    }
                });

        // ── 13. weather ──────────────────────────────────────────────────
        // 天气查询工具：使用 wttr.in API（无需 API key，在中国可用）
        Map<String, Object> weatherLocationProp = objectSchema("string",
                "City name or location (e.g., 'Beijing', 'Shanghai', 'New York'). Defaults to 'Beijing'.", null, null);
        Map<String, Object> weatherDaysProp = objectSchema("string",
                "Number of days to forecast: 1 (today only), 2 (today+tomorrow), 3 (full 3-day forecast). Default: 1", null, null);
        Map<String, Object> weatherProps = new LinkedHashMap<>();
        weatherProps.put("location", weatherLocationProp);
        weatherProps.put("days", weatherDaysProp);
        registry.register("weather",
                "Get current weather and forecast for any city/location. " +
                "Use this tool when user asks about weather, temperature, rain, snow, forecasts, etc. " +
                "Returns: temperature, feels-like, humidity, wind, UV index, precipitation chance, and forecast.",
                paramObj(weatherProps, Collections.emptyList()),
                args -> {
                    String rawLocation = extractString(args, "location");
                    String location = rawLocation.isEmpty() ? "Beijing" : rawLocation;
                    String daysStr = extractString(args, "days");
                    int days = 1;
                    if (!daysStr.isEmpty()) {
                        try {
                            days = Integer.parseInt(daysStr);
                        } catch (NumberFormatException ignored) {}
                    }
                    final int finalDays = Math.max(1, Math.min(3, days));
                    final String finalLocation = location;

                    return Mono.fromCallable(() -> {
                        String encodedLocation = URLEncoder.encode(finalLocation, StandardCharsets.UTF_8);
                        String weatherUrl = "http://wttr.in/" + encodedLocation + "?format=j1";
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(weatherUrl))
                                .timeout(Duration.ofSeconds(10))
                                .header("User-Agent", "Mozilla/5.0")
                                .header("Accept", "application/json")
                                .GET().build();
                        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                        return response.body();
                    })
                    .subscribeOn(blockingScheduler)
                    .timeout(Duration.ofSeconds(15))
                    .map(jsonStr -> {
                        try {
                            // 解析 JSON 响应
                            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(jsonStr);
                            com.fasterxml.jackson.databind.JsonNode nearestArea = root.path("nearest_area").get(0);
                            com.fasterxml.jackson.databind.JsonNode currentCondition = root.path("current_condition").get(0);

                            String areaName = nearestArea.path("areaName").get(0).path("value").asText();
                            String country = nearestArea.path("country").get(0).path("value").asText();

                            // 当前天气
                            String tempC = currentCondition.path("temp_C").asText();
                            String feelsLikeC = currentCondition.path("FeelsLikeC").asText();
                            String humidity = currentCondition.path("humidity").asText();
                            String weatherDesc = currentCondition.path("weatherDesc").get(0).path("value").asText();
                            String windSpeed = currentCondition.path("windspeedKmph").asText();
                            String windDir = currentCondition.path("winddir16Point").asText();
                            String uvIndex = currentCondition.path("uvIndex").asText();
                            String visibility = currentCondition.path("visibility").asText();
                            String pressure = currentCondition.path("pressure").asText();

                            StringBuilder sb = new StringBuilder();
                            sb.append("┌─────────────────────────────────────────┐\n");
                            sb.append("│         🌤️  天气预报 - ").append(areaName).append("              │\n");
                            sb.append("└─────────────────────────────────────────┘\n\n");
                            sb.append("📍 位置: ").append(areaName).append(", ").append(country).append("\n");
                            sb.append("🌡️  当前温度: ").append(tempC).append("°C (体感 ").append(feelsLikeC).append("°C)\n");
                            sb.append("☁️  天气状况: ").append(weatherDesc).append("\n");
                            sb.append("💧 湿度: ").append(humidity).append("%\n");
                            sb.append("🌬️  风速: ").append(windSpeed).append(" km/h (").append(windDir).append(")\n");
                            sb.append("☀️  紫外线指数: ").append(uvIndex).append("\n");
                            sb.append("👁️  能见度: ").append(visibility).append(" km\n");
                            sb.append("📊 气压: ").append(pressure).append(" mb\n");

                            // 天气预报
                            com.fasterxml.jackson.databind.JsonNode weatherArray = root.path("weather");
                            int forecastDays = Math.min(finalDays, weatherArray.size());

                            if (forecastDays > 0) {
                                sb.append("\n┌─────────────────────────────────────────┐\n");
                                sb.append("│              📅 天气预报                │\n");
                                sb.append("└─────────────────────────────────────────┘\n");
                            }

                            for (int i = 0; i < forecastDays; i++) {
                                com.fasterxml.jackson.databind.JsonNode dayData = weatherArray.get(i);
                                String date = dayData.path("date").asText();
                                String maxTemp = dayData.path("maxtempC").asText();
                                String minTemp = dayData.path("mintempC").asText();
                                String avgTemp = dayData.path("avgtempC").asText();
                                String uvIndexDay = dayData.path("uvIndex").asText();
                                String sunHour = dayData.path("sunHour").asText();

                                // 日出日落
                                String sunrise = dayData.path("astronomy").get(0).path("sunrise").asText();
                                String sunset = dayData.path("astronomy").get(0).path("sunset").asText();

                                String dayLabel = i == 0 ? "今天" : (i == 1 ? "明天" : date);
                                sb.append("\n📆 ").append(dayLabel).append(" (").append(date).append(")\n");
                                sb.append("   🌡️ 温度: ").append(minTemp).append("°C ~ ").append(maxTemp).append("°C (平均 ").append(avgTemp).append("°C)\n");
                                sb.append("   ☀️ 紫外线: ").append(uvIndexDay).append(" | 🌅 日出: ").append(sunrise).append(" | 🌇 日落: ").append(sunset).append("\n");
                                sb.append("   ☀️ 日照时长: ").append(sunHour).append(" 小时\n");

                                // 每小时预报（只显示关键时段）
                                com.fasterxml.jackson.databind.JsonNode hourlyArray = dayData.path("hourly");
                                if (hourlyArray.size() > 0) {
                                    sb.append("   ⏰ 关键时段预报:\n");
                                    int[] keyHours = {3, 6, 9, 12, 15, 18, 21};
                                    for (int h : keyHours) {
                                        if (h < hourlyArray.size()) {
                                            com.fasterxml.jackson.databind.JsonNode hourData = hourlyArray.get(h);
                                            String hourTime = h + ":00";
                                            String hourTemp = hourData.path("tempC").asText();
                                            String hourWeather = hourData.path("weatherDesc").get(0).path("value").asText().trim();
                                            String hourWind = hourData.path("windspeedKmph").asText();
                                            String hourRain = hourData.path("chanceofrain").asText();
                                            sb.append("      ").append(hourTime).append(": ");
                                            sb.append(hourWeather).append(", ").append(hourTemp).append("°C, ");
                                            sb.append("风 ").append(hourWind).append("km/h, ");
                                            sb.append("降雨概率 ").append(hourRain).append("%\n");
                                        }
                                    }
                                }
                            }

                            sb.append("\n─────────────────────────────\n");
                            sb.append("💡 提示: 天气数据来自 wttr.in\n");

                            return sb.toString();
                        } catch (Exception e) {
                            log.error("Weather parsing failed: {}", e.getMessage());
                            return "天气查询失败: " + e.getMessage() + "\n原始响应: " +
                                   (jsonStr.length() > 500 ? jsonStr.substring(0, 500) + "..." : jsonStr);
                        }
                    })
                    .onErrorResume(e -> {
                        log.warn("Weather query failed: {}", e.getMessage());
                        return Mono.just("天气查询失败: " + e.getMessage() +
                                "\n请检查城市名称是否正确，或稍后重试。");
                    });
                });

        log.info("Built-in tools registered: 13");
    }

    private String extractString(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val != null) {
            String s = val.toString().trim();
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    /** Strip HTML tags and decode common HTML entities */
    private String htmlToText(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("\u200b", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public Set<String> getToolNames() {
        return registry.getAllTools().keySet();
    }
}
