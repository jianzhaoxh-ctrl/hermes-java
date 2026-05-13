package com.hermes.agent.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

/**
 * 适配器工具类
 *
 * 提供所有平台适配器共享的工具方法，参考 Python 版 messaging/base.py 实现。
 *
 * 功能：
 * - UTF-16 字符串长度计算（Telegram 等平台限制）
 * - 网络可达性检查
 * - 代理配置解析
 * - SSRF 重定向防护
 * - 媒体 URL/文件路径提取
 * - 消息分块（保持代码块完整性）
 */
public final class AdapterUtils {

    private static final Logger log = LoggerFactory.getLogger(AdapterUtils.class);

    // ========== 媒体文件扩展名 ==========

    public static final Set<String> IMAGE_EXTENSIONS = Set.of(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "tiff", "tif", "avif"
    );

    public static final Set<String> AUDIO_EXTENSIONS = Set.of(
        "mp3", "wav", "ogg", "flac", "aac", "m4a", "wma", "opus"
    );

    public static final Set<String> VIDEO_EXTENSIONS = Set.of(
        "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "m4v", "3gp"
    );

    public static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "txt", "csv", "json", "xml", "zip", "rar", "7z", "tar", "gz"
    );

    // URL 和文件路径匹配模式
    private static final Pattern URL_PATTERN = Pattern.compile(
        "https?://[^\\s<>\"]+\\.(?:jpg|jpeg|png|gif|webp|bmp|mp4|avi|mkv|mp3|wav|ogg|flac|pdf|doc|docx|zip)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern LOCAL_FILE_PATTERN = Pattern.compile(
        "(?:^|\\s)(/[^\\s]+\\.(?:jpg|jpeg|png|gif|webp|mp4|mp3|wav|ogg|flac|pdf|doc|zip))",
        Pattern.CASE_INSENSITIVE
    );

    // 代码块模式（用于消息分块）
    private static final Pattern CODE_BLOCK_START = Pattern.compile("^```", Pattern.MULTILINE);
    private static final Pattern CODE_BLOCK_END = Pattern.compile("^```\\s*$", Pattern.MULTILINE);

    private AdapterUtils() {} // 工具类不允许实例化

    // ========== UTF-16 长度计算 ==========

    /**
     * 计算 UTF-16 编码长度（代理对占2个码元）
     *
     * Telegram 等平台使用 UTF-16 码元计数来限制消息长度。
     * CJK 字符大多在 BMP 内（1个码元），但 emoji 和稀有字符可能在补充平面（2个码元）。
     *
     * @param text 输入文本
     * @return UTF-16 码元数量
     */
    public static int utf16Len(String text) {
        if (text == null) return 0;
        return text.length() + (int) text.chars().filter(c -> Character.isSurrogate((char) c)).count();
    }

    /**
     * 精确计算 UTF-16 码元数量
     */
    public static int utf16CodeUnitCount(String text) {
        if (text == null) return 0;
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                count += 2; // 代理对 = 2个码元
                i++; // 跳过低代理
            } else {
                count += 1;
            }
        }
        return count;
    }

    // ========== 网络可达性 ==========

    /**
     * 检查 URL 是否可访问（不实际下载内容）
     *
     * @param url URL 字符串
     * @return 是否可达
     */
    public static boolean isNetworkAccessible(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            var uri = new URI(url);
            if (uri.getScheme() == null || uri.getHost() == null) return false;
            // 禁止内网地址
            if (isPrivateAddress(uri.getHost())) return false;
            return true;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * 检查主机名是否为内网地址
     */
    public static boolean isPrivateAddress(String host) {
        if (host == null) return true;
        String lower = host.toLowerCase();
        if (lower.equals("localhost") || lower.equals("127.0.0.1") || lower.equals("0.0.0.0")) {
            return true;
        }
        if (lower.endsWith(".local") || lower.endsWith(".localhost")) {
            return true;
        }
        // 10.x.x.x, 172.16-31.x.x, 192.168.x.x
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return false; // 无法解析的不算内网
        }
    }

    // ========== 代理配置 ==========

    /**
     * 从环境变量解析代理 URL
     *
     * 优先级：HTTPS_PROXY > HTTP_PROXY > ALL_PROXY
     * 也在 macOS 系统偏好设置中查找代理（通过 system_profiler）
     *
     * @return 代理 URL 或 null
     */
    public static String resolveProxyUrl() {
        // 环境变量
        String proxy = getEnvAny("HTTPS_PROXY", "https_proxy",
                                  "HTTP_PROXY", "http_proxy",
                                  "ALL_PROXY", "all_proxy");
        if (proxy != null && !proxy.isBlank()) {
            return proxy.trim();
        }

        // JVM 系统属性
        String httpsProxyHost = System.getProperty("https.proxyHost");
        String httpsProxyPort = System.getProperty("https.proxyPort");
        if (httpsProxyHost != null && !httpsProxyHost.isBlank()) {
            int port = httpsProxyPort != null ? Integer.parseInt(httpsProxyPort) : 443;
            return "https://" + httpsProxyHost + ":" + port;
        }

        String httpProxyHost = System.getProperty("http.proxyHost");
        String httpProxyPort = System.getProperty("http.proxyPort");
        if (httpProxyHost != null && !httpProxyHost.isBlank()) {
            int port = httpProxyPort != null ? Integer.parseInt(httpProxyPort) : 80;
            return "http://" + httpProxyHost + ":" + port;
        }

        return null;
    }

    /**
     * 构建带代理的 HttpClient
     */
    public static HttpClient buildHttpClient(Duration timeout) {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL);

        String proxyUrl = resolveProxyUrl();
        if (proxyUrl != null) {
            try {
                var proxyUri = new URI(proxyUrl);
                builder.proxy(ProxySelector.of(
                    new InetSocketAddress(proxyUri.getHost(), proxyUri.getPort())
                ));
            } catch (Exception e) {
                log.warn("Invalid proxy URL: {}", proxyUrl);
            }
        }

        return builder.build();
    }

    // ========== SSRF 防护 ==========

    /**
     * SSRF 重定向防护
     *
     * 验证重定向目标是否安全，防止通过重定向访问内网资源。
     *
     * @param originalUrl 原始 URL
     * @param redirectUrl 重定向目标 URL
     * @return 是否允许重定向
     */
    public static boolean ssrfRedirectGuard(String originalUrl, String redirectUrl) {
        if (redirectUrl == null || redirectUrl.isBlank()) return false;

        try {
            var redirectUri = new URI(redirectUrl);

            // 仅允许 HTTP/HTTPS
            String scheme = redirectUri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                log.warn("SSRF guard: blocked non-HTTP redirect to {}", redirectUrl);
                return false;
            }

            // 禁止重定向到内网
            if (isPrivateAddress(redirectUri.getHost())) {
                log.warn("SSRF guard: blocked redirect to private address {}", redirectUri.getHost());
                return false;
            }

            // 禁止重定向到非常规端口
            int port = redirectUri.getPort();
            if (port > 0 && port != 80 && port != 443 && port < 1024) {
                log.warn("SSRF guard: blocked redirect to suspicious port {}", port);
                return false;
            }

            return true;
        } catch (URISyntaxException e) {
            log.warn("SSRF guard: invalid redirect URL {}", redirectUrl);
            return false;
        }
    }

    /**
     * 安全下载 URL 内容，带 SSRF 防护
     */
    public static Optional<byte[]> safeDownload(String url, Duration timeout, long maxBytes) {
        if (!isNetworkAccessible(url)) {
            log.warn("safeDownload: URL not accessible: {}", url);
            return Optional.empty();
        }

        try {
            HttpClient client = buildHttpClient(timeout);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .build();

            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                log.warn("safeDownload: HTTP {} for {}", response.statusCode(), url);
                return Optional.empty();
            }

            byte[] body = response.body();
            if (maxBytes > 0 && body.length > maxBytes) {
                log.warn("safeDownload: response too large ({} > {})", body.length, maxBytes);
                return Optional.empty();
            }

            return Optional.of(body);
        } catch (IOException e) {
            log.warn("safeDownload: IO error for {}: {}", url, e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    // ========== 媒体提取 ==========

    /**
     * 从消息文本中提取图片 URL
     */
    public static List<String> extractImages(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> images = new ArrayList<>();
        Matcher m = URL_PATTERN.matcher(text);
        while (m.find()) {
            String url = m.group();
            String ext = getExtension(url).toLowerCase();
            if (IMAGE_EXTENSIONS.contains(ext)) {
                images.add(url);
            }
        }
        return images;
    }

    /**
     * 从消息文本中提取所有媒体 URL
     */
    public static List<MediaInfo> extractMedia(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<MediaInfo> media = new ArrayList<>();
        Matcher m = URL_PATTERN.matcher(text);
        while (m.find()) {
            String url = m.group();
            String ext = getExtension(url).toLowerCase();
            MediaType type = classifyExtension(ext);
            if (type != MediaType.UNKNOWN) {
                media.add(new MediaInfo(url, type, ext));
            }
        }
        return media;
    }

    /**
     * 从消息文本中提取本地文件路径
     */
    public static List<String> extractLocalFiles(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> files = new ArrayList<>();
        Matcher m = LOCAL_FILE_PATTERN.matcher(text);
        while (m.find()) {
            files.add(m.group(1));
        }
        return files;
    }

    /**
     * 分类文件扩展名
     */
    public static MediaType classifyExtension(String ext) {
        if (ext == null) return MediaType.UNKNOWN;
        String lower = ext.toLowerCase().replace(".", "");
        if (IMAGE_EXTENSIONS.contains(lower)) return MediaType.IMAGE;
        if (AUDIO_EXTENSIONS.contains(lower)) return MediaType.AUDIO;
        if (VIDEO_EXTENSIONS.contains(lower)) return MediaType.VIDEO;
        if (DOCUMENT_EXTENSIONS.contains(lower)) return MediaType.DOCUMENT;
        return MediaType.UNKNOWN;
    }

    // ========== 消息分块 ==========

    /**
     * 智能消息分块
     *
     * 在分割时保持代码块完整性，避免在代码块中间截断。
     *
     * @param content 消息内容
     * @param maxLength 每块最大长度
     * @return 分块列表
     */
    public static List<String> chunkMessage(String content, int maxLength) {
        if (content == null || content.isEmpty()) return List.of();
        if (content.length() <= maxLength) return List.of(content);

        List<String> chunks = new ArrayList<>();
        String remaining = content;

        while (!remaining.isEmpty()) {
            if (remaining.length() <= maxLength) {
                chunks.add(remaining);
                break;
            }

            // 查找分割点
            int splitAt = findSafeSplitPoint(remaining, maxLength);

            chunks.add(remaining.substring(0, splitAt));
            remaining = remaining.substring(splitAt).stripLeading();
        }

        // 添加分块指示器
        if (chunks.size() > 1) {
            int total = chunks.size();
            for (int i = 0; i < total; i++) {
                String indicator = " [" + (i + 1) + "/" + total + "]";
                // 确保不超过 maxLength
                String chunk = chunks.get(i);
                if (chunk.length() + indicator.length() <= maxLength + 10) {
                    chunks.set(i, chunk + indicator);
                }
            }
        }

        return chunks;
    }

    /**
     * 找到安全的分割点（不在代码块中间）
     */
    private static int findSafeSplitPoint(String text, int maxLength) {
        int target = maxLength - 20; // 预留指示器空间

        // 计算当前位置是否在代码块内
        int codeBlockDepth = 0;
        int lastSafeSplit = target;

        for (int i = 0; i < Math.min(text.length(), maxLength); i++) {
            if (i < text.length() - 2 && text.charAt(i) == '`' && text.charAt(i + 1) == '`' && text.charAt(i + 2) == '`') {
                codeBlockDepth = (codeBlockDepth == 0) ? 1 : 0;
            }
            if (codeBlockDepth == 0) {
                // 优先在换行处分割
                if (text.charAt(i) == '\n') {
                    lastSafeSplit = i + 1;
                }
            }
        }

        // 如果在代码块内，跳到代码块结束
        if (codeBlockDepth > 0) {
            int endIdx = text.indexOf("```\n", target - 50);
            if (endIdx > 0 && endIdx < maxLength + 100) {
                return endIdx + 4;
            }
            endIdx = text.indexOf("```", target);
            if (endIdx > 0 && endIdx < maxLength + 100) {
                return endIdx + 3;
            }
        }

        return Math.max(lastSafeSplit, Math.min(target, text.length()));
    }

    // ========== 辅助方法 ==========

    private static String getExtension(String url) {
        try {
            String path = new URI(url).getPath();
            if (path == null) return "";
            int dotIdx = path.lastIndexOf('.');
            return dotIdx >= 0 ? path.substring(dotIdx + 1) : "";
        } catch (URISyntaxException e) {
            return "";
        }
    }

    private static String getEnvAny(String... keys) {
        for (String key : keys) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    // ========== 内部类型 ==========

    public enum MediaType {
        IMAGE, AUDIO, VIDEO, DOCUMENT, UNKNOWN
    }

    /**
     * 媒体信息
     */
    public static class MediaInfo {
        private final String url;
        private final MediaType type;
        private final String extension;

        public MediaInfo(String url, MediaType type, String extension) {
            this.url = url;
            this.type = type;
            this.extension = extension;
        }

        public String getUrl() { return url; }
        public MediaType getType() { return type; }
        public String getExtension() { return extension; }
    }
}
