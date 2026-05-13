package com.hermes.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.regex.*;

/**
 * Browser Automation Tool — 通过 agent-browser CLI 实现浏览器自动化。
 *
 * <p>参照 Python 版 browser_tool.py，调用本地 headless Chromium（agent-browser）
 * 实现。支持的工具：
 * <ol>
 *   <li>browser_navigate   — 导航到 URL，加载页面</li>
 *   <li>browser_snapshot   — 获取无障碍树快照（可折叠/完整）</li>
 *   <li>browser_click      — 点击元素（@e5 等引用）</li>
 *   <li>browser_type       — 向输入框输入文本</li>
 *   <li>browser_scroll     — 滚动页面</li>
 *   <li>browser_back       — 后退</li>
 *   <li>browser_press      — 按键盘键</li>
 *   <li>browser_get_images — 获取页面图片列表</li>
 *   <li>browser_vision     — 截图 + 视觉分析</li>
 *   <li>browser_console    — 浏览器控制台输出</li>
 *   <li>browser_screenshot — 保存截图文件</li>
 * </ol>
 *
 * <p>会话隔离：每个 taskId 一个浏览器会话，超时 5 分钟自动清理。
 * SSRF 保护：禁止访问私有 IP 地址（127.0.0.1, 10.x, 172.16-31.x, 192.168.x）。
 */
@Component
public class BrowserAutomationTool {

    private static final Logger log = LoggerFactory.getLogger(BrowserAutomationTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int COMMAND_TIMEOUT_SEC = 30;
    private static final int INACTIVITY_TIMEOUT_SEC = 300;

    /** 按 taskId 存储会话名 */
    private static final Map<String, String> activeSessions = new ConcurrentHashMap<>();
    /** 每个会话最后活动时间戳 */
    private static final Map<String, Long> sessionLastActivity = new ConcurrentHashMap<>();
    private static final Object sessionLock = new Object();

    private final ToolRegistry registry;

    public BrowserAutomationTool(ToolRegistry registry) {
        this.registry = registry;
        registerTools();
    }

    // ─── 工具注册 ────────────────────────────────────────────────────────────

    private void registerTools() {

        // ── 1. browser_navigate ─────────────────────────────────────────
        Map<String, Object> navProps = new LinkedHashMap<>();
        navProps.put("url", schema("string", "要访问的 URL，如 https://example.com", null));
        registry.register("browser_navigate",
                "Navigate to a URL in the browser. Initializes the session and loads the page. " +
                "Returns a compact page snapshot with interactive elements and ref IDs — " +
                "no need to call browser_snapshot separately after navigating. " +
                "For simple information retrieval, prefer web_search/web_fetch (faster, cheaper). " +
                "Use browser tools when you need to interact with a page (click, fill forms, dynamic content).",
                schemaObj(navProps, List.of("url")),
                args -> runOnBlockingThread(() -> {
                    String url = str(args, "url");
                    if (url.isEmpty()) return "Error: url is required";
                    // SSRF 检查
                    String ssrf = checkSsrf(url);
                    if (ssrf != null) return ssrf;
                    String sessionName = ensureSession(null);
                    return execBrowser(sessionName, "navigate", url);
                }));

        // ── 2. browser_snapshot ──────────────────────────────────────────
        Map<String, Object> snapProps = new LinkedHashMap<>();
        snapProps.put("full", schema("boolean", "若为 true 返回完整页面内容，false（默认）只返回交互元素", null));
        registry.register("browser_snapshot",
                "Get a text-based snapshot of the current page's accessibility tree. " +
                "Returns interactive elements with ref IDs (@e1, @e2, ...) for browser_click and browser_type. " +
                "full=false (default): compact view with interactive elements only. " +
                "full=true: complete page content. " +
                "Note: browser_navigate already returns a compact snapshot — " +
                "use this to refresh after interactions that change the page.",
                schemaObj(snapProps, Collections.emptyList()),
                args -> runOnBlockingThread(() -> {
                    String sessionName = ensureSession(null);
                    String full = str(args, "full");
                    String mode = "false".equalsIgnoreCase(full) || full.isEmpty() ? "compact" : "full";
                    return execBrowser(sessionName, "snapshot", "--mode", mode);
                }));

        // ── 3. browser_click ─────────────────────────────────────────────
        Map<String, Object> clickProps = new LinkedHashMap<>();
        clickProps.put("ref", schema("string", "元素引用 ID，如 @e5（来自 snapshot 输出）", null));
        registry.register("browser_click",
                "Click on an element identified by its ref ID from the snapshot. " +
                "Requires browser_navigate to be called first.",
                schemaObj(clickProps, List.of("ref")),
                args -> runOnBlockingThread(() -> {
                    String ref = str(args, "ref");
                    if (ref.isEmpty()) return "Error: ref is required";
                    String sessionName = ensureSession(null);
                    // 去掉前缀 @，如果存在
                    if (ref.startsWith("@")) ref = ref.substring(1);
                    return execBrowser(sessionName, "click", ref);
                }));

        // ── 4. browser_type ──────────────────────────────────────────────
        Map<String, Object> typeProps = new LinkedHashMap<>();
        typeProps.put("ref", schema("string", "元素引用 ID，如 @e3", null));
        typeProps.put("text", schema("string", "要输入的文本", null));
        registry.register("browser_type",
                "Type text into an input field identified by its ref ID. " +
                "Clears the field first, then types the new text. " +
                "Requires browser_navigate and browser_snapshot to be called first.",
                schemaObj(typeProps, List.of("ref", "text")),
                args -> runOnBlockingThread(() -> {
                    String ref = str(args, "ref");
                    String text = str(args, "text");
                    if (ref.isEmpty()) return "Error: ref is required";
                    if (text.isEmpty()) return "Error: text is required";
                    if (ref.startsWith("@")) ref = ref.substring(1);
                    String sessionName = ensureSession(null);
                    return execBrowser(sessionName, "type", ref, text);
                }));

        // ── 5. browser_scroll ────────────────────────────────────────────
        Map<String, Object> scrollProps = new LinkedHashMap<>();
        scrollProps.put("direction", schema("string", "滚动方向：up 或 down", null));
        registry.register("browser_scroll",
                "Scroll the page. Use this to reveal more content below or above the current viewport. " +
                "Requires browser_navigate to be called first.",
                schemaObj(scrollProps, List.of("direction")),
                args -> runOnBlockingThread(() -> {
                    String direction = str(args, "direction");
                    if (direction.isEmpty()) return "Error: direction is required";
                    String sessionName = ensureSession(null);
                    return execBrowser(sessionName, "scroll", direction);
                }));

        // ── 6. browser_back ──────────────────────────────────────────────
        registry.register("browser_back",
                "Navigate back to the previous page in browser history. " +
                "Requires browser_navigate to be called first.",
                schemaObj(Map.of(), Collections.emptyList()),
                args -> runOnBlockingThread(() -> {
                    String sessionName = ensureSession(null);
                    return execBrowser(sessionName, "back");
                }));

        // ── 7. browser_press ────────────────────────────────────────────
        Map<String, Object> pressProps = new LinkedHashMap<>();
        pressProps.put("key", schema("string", "按键名称，如 Enter、Tab、Escape、ArrowDown", null));
        registry.register("browser_press",
                "Press a keyboard key. Useful for submitting forms (Enter), navigating (Tab), shortcuts. " +
                "Requires browser_navigate to be called first.",
                schemaObj(pressProps, List.of("key")),
                args -> runOnBlockingThread(() -> {
                    String key = str(args, "key");
                    if (key.isEmpty()) return "Error: key is required";
                    String sessionName = ensureSession(null);
                    return execBrowser(sessionName, "press", key);
                }));

        // ── 8. browser_get_images ───────────────────────────────────────
        registry.register("browser_get_images",
                "Get a list of all images on the current page with their URLs and alt text. " +
                "Requires browser_navigate to be called first.",
                schemaObj(Map.of(), Collections.emptyList()),
                args -> runOnBlockingThread(() -> {
                    String sessionName = ensureSession(null);
                    return execBrowser(sessionName, "get-images");
                }));

        // ── 9. browser_vision ────────────────────────────────────────────
        Map<String, Object> visionProps = new LinkedHashMap<>();
        visionProps.put("question", schema("string", "关于页面视觉想了解什么", null));
        visionProps.put("annotate", schema("boolean", "是否在截图中标注交互元素编号", null));
        registry.register("browser_vision",
                "Take a screenshot of the current page and analyze it with vision AI. " +
                "Use when you need to visually understand the page — CAPTCHAs, complex layouts, " +
                "visual verification challenges. " +
                "Returns both the AI analysis and a screenshot_path. " +
                "Requires browser_navigate to be called first.",
                schemaObj(visionProps, List.of("question")),
                args -> runOnBlockingThread(() -> {
                    String question = str(args, "question");
                    String annotate = str(args, "annotate");
                    if (question.isEmpty()) return "Error: question is required";
                    String sessionName = ensureSession(null);
                    String annotateFlag = "true".equalsIgnoreCase(annotate) ? "--annotate" : "";
                    String result = execBrowser(sessionName, "vision", "--question", question);
                    // 追加截图路径（如果 agent-browser 返回）
                    return result;
                }));

        // ── 10. browser_console ──────────────────────────────────────────
        Map<String, Object> consoleProps = new LinkedHashMap<>();
        consoleProps.put("clear", schema("boolean", "读取后是否清空控制台", null));
        consoleProps.put("expression", schema("string", "在页面上下文中执行的 JS 表达式", null));
        registry.register("browser_console",
                "Get browser console output and JavaScript errors from the current page. " +
                "When 'expression' is provided, evaluates JavaScript and returns the result. " +
                "Requires browser_navigate to be called first.",
                schemaObj(consoleProps, Collections.emptyList()),
                args -> runOnBlockingThread(() -> {
                    String clear = str(args, "clear");
                    String expr = str(args, "expression");
                    String sessionName = ensureSession(null);
                    String clearFlag = "true".equalsIgnoreCase(clear) ? "--clear" : "";
                    if (!expr.isEmpty()) {
                        return execBrowser(sessionName, "console", "--expression", expr, clearFlag);
                    }
                    return execBrowser(sessionName, "console", clearFlag);
                }));

        // ── 11. browser_screenshot ───────────────────────────────────────
        Map<String, Object> ssProps = new LinkedHashMap<>();
        ssProps.put("path", schema("string", "截图保存路径（可选，默认保存到临时目录）", null));
        registry.register("browser_screenshot",
                "Take and save a screenshot of the current page. " +
                "Returns the path to the saved screenshot file. " +
                "Requires browser_navigate to be called first.",
                schemaObj(ssProps, Collections.emptyList()),
                args -> runOnBlockingThread(() -> {
                    String path = str(args, "path");
                    String sessionName = ensureSession(null);
                    if (!path.isEmpty()) {
                        return execBrowser(sessionName, "screenshot", "--path", path);
                    }
                    return execBrowser(sessionName, "screenshot");
                }));

        log.info("Browser automation tools registered: 11");
    }

    // ─── 会话管理 ─────────────────────────────────────────────────────────────

    private String ensureSession(String existingSession) {
        String taskId = Thread.currentThread().getName();
        synchronized (sessionLock) {
            String sessionName = activeSessions.get(taskId);
            if (sessionName != null) {
                sessionLastActivity.put(sessionName, System.currentTimeMillis());
                return sessionName;
            }
            // 创建新会话
            String newSession = "h_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            activeSessions.put(taskId, newSession);
            sessionLastActivity.put(newSession, System.currentTimeMillis());
            return newSession;
        }
    }

    // ─── SSRF 保护 ────────────────────────────────────────────────────────────

    private static final Pattern PRIVATE_IP_PATTERNS = Pattern.compile(
            "(?i)^(127\\.\\d+\\.\\d+\\.\\d+)|" +
            "(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})|" +
            "(172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3})|" +
            "(192\\.168\\.\\d{1,3}\\.\\d{1,3})|" +
            "(localhost)|" +
            "(::1)|" +
            "(fe80:)|" +
            "(0:0:0:0:0:0:0:1)"
    );

    private String checkSsrf(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) return null;

            // 检查 host 是否为私有 IP
            if (PRIVATE_IP_PATTERNS.matcher(host).find()) {
                return "SSRF blocked: private IP address not allowed. URL: " + url;
            }

            // 解析 A 记录检查 IP（可选，放开时启用）
            // InetAddress addr = InetAddress.getByName(host);
            // if (addr.isSiteLocalAddress() || addr.isLoopbackAddress()) ...

        } catch (Exception e) {
            return "Error parsing URL: " + e.getMessage();
        }
        return null;
    }

    // ─── 核心：执行 agent-browser CLI ─────────────────────────────────────────

    private String execBrowser(String sessionName, String command, String... args) {
        touchSession(sessionName);

        List<String> cmd = new ArrayList<>();
        cmd.add("agent-browser");
        cmd.add("--session");
        cmd.add(sessionName);
        cmd.add(command);
        cmd.addAll(Arrays.asList(args));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            pb.environment().put("PATH", buildBrowserPath());

            Process process = pb.start();

            // 读取 stdout
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();

            Thread stdoutThread = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) out.append(line).append("\n");
                } catch (IOException e) {
                    log.debug("stdout read error: {}", e.getMessage());
                }
            });
            stdoutThread.start();

            Thread stderrThread = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) err.append(line).append("\n");
                } catch (IOException e) {
                    log.debug("stderr read error: {}", e.getMessage());
                }
            });
            stderrThread.start();

            boolean finished = process.waitFor(COMMAND_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS);
            stdoutThread.join(1000);
            stderrThread.join(1000);

            if (!finished) {
                process.destroyForcibly();
                return "Error: browser command timed out after " + COMMAND_TIMEOUT_SEC + "s";
            }

            String stdout = out.toString().trim();
            String stderr = err.toString().trim();

            if (process.exitValue() != 0 && stdout.isEmpty()) {
                return "Browser error (exit " + process.exitValue() + "): " +
                        (stderr.isEmpty() ? "unknown error" : stderr);
            }

            // 错误检查
            if (stderr.contains("Failed to connect") || stderr.contains("no such file")) {
                return installHint();
            }
            if (stderr.contains("net::ERR_")) {
                return "Navigation failed: " + stderr.substring(stderr.indexOf("net::ERR_"),
                        Math.min(stderr.indexOf("net::ERR_") + 100, stderr.length()));
            }

            return stdout.isEmpty() ? "(no output)" : stdout;

        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Cannot run program")) {
                return installHint();
            }
            return "Browser execution error: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Browser execution interrupted";
        }
    }

    private String installHint() {
        return "agent-browser CLI not found. Install it with:\n" +
                "  npm install -g agent-browser && agent-browser install\n" +
                "Or run: npx agent-browser install\n" +
                "See: https://github.com/nousresearch/agent-browser";
    }

    private void touchSession(String sessionName) {
        synchronized (sessionLock) {
            sessionLastActivity.put(sessionName, System.currentTimeMillis());
        }
    }

    private String buildBrowserPath() {
        String existing = System.getenv("PATH");
        // 追加常见 Node/bin 路径
        String extra = File.pathSeparator + "C:\\Program Files\\nodejs" +
                File.pathSeparator + System.getProperty("user.home") + "\\AppData\\Roaming\\npm";
        return existing + extra;
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
                .timeout(Duration.ofSeconds(COMMAND_TIMEOUT_SEC + 5))
                .onErrorResume(e -> {
                    if (e instanceof TimeoutException) {
                        return Mono.just("Error: browser command timed out");
                    }
                    return Mono.just("Error: " + e.getMessage());
                });
    }

    // ─── 清理（供外部调用）─────────────────────────────────────────────────────

    /** 关闭指定 taskId 的浏览器会话 */
    public void closeSession(String taskId) {
        synchronized (sessionLock) {
            String sessionName = activeSessions.remove(taskId);
            if (sessionName != null) {
                try {
                    new ProcessBuilder("agent-browser", "--session", sessionName, "close")
                            .redirectErrorStream(true).start();
                } catch (IOException e) {
                    log.debug("Failed to close browser session {}: {}", sessionName, e.getMessage());
                }
                sessionLastActivity.remove(sessionName);
            }
        }
    }

    /** 关闭所有浏览器会话 */
    public void closeAllSessions() {
        synchronized (sessionLock) {
            for (String taskId : new ArrayList<>(activeSessions.keySet())) {
                closeSession(taskId);
            }
        }
    }

    /** 检查并清理不活跃的会话 */
    public void cleanupInactiveSessions() {
        long now = System.currentTimeMillis();
        long timeout = INACTIVITY_TIMEOUT_SEC * 1000L;
        synchronized (sessionLock) {
            List<String> toRemove = new ArrayList<>();
            for (Map.Entry<String, Long> e : sessionLastActivity.entrySet()) {
                if (now - e.getValue() > timeout) {
                    toRemove.add(e.getKey());
                }
            }
            for (String sessionName : toRemove) {
                closeSession(sessionName);
            }
        }
    }
}
