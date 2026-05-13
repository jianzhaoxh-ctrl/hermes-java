package com.hermes.agent.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 媒体缓存系统
 * 
 * 参考 Python 版 gateway/platforms/base.py 中的缓存工具实现。
 * 
 * 当用户在消息平台发送图片/音频/视频/文档时，下载到本地缓存，
 * 以便工具（视觉、STT 等）通过本地文件路径访问。
 * 避免平台临时 URL 过期问题（如 Telegram 文件 URL 约1小时后过期）。
 */
@Component
public class MediaCache {
    private static final Logger log = LoggerFactory.getLogger(MediaCache.class);

    private final String baseCacheDir;

    // 支持的图片类型
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp"
    );

    // 支持的音频类型
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
        ".ogg", ".opus", ".mp3", ".wav", ".m4a", ".flac"
    );

    // 支持的视频类型
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
        ".mp4", ".mov", ".avi", ".mkv", ".webm", ".3gp"
    );

    // 支持的文档类型
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
        ".pdf", ".md", ".txt", ".log", ".zip",
        ".docx", ".xlsx", ".pptx"
    );

    // 图片 magic bytes 检测
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF_MAGIC_87 = "GIF87a".getBytes();
    private static final byte[] GIF_MAGIC_89 = "GIF89a".getBytes();
    private static final byte[] BMP_MAGIC = "BM".getBytes();
    private static final byte[] WEBP_RIFF = "RIFF".getBytes();
    private static final byte[] WEBP_WEBP = "WEBP".getBytes();

    public MediaCache() {
        String homeDir = System.getProperty("user.home");
        this.baseCacheDir = homeDir + "/.hermes/cache";
        ensureCacheDirs();
    }

    public MediaCache(String baseCacheDir) {
        this.baseCacheDir = baseCacheDir;
        ensureCacheDirs();
    }

    private void ensureCacheDirs() {
        for (String subDir : List.of("images", "audio", "videos", "documents")) {
            try {
                Files.createDirectories(Path.of(baseCacheDir, subDir));
            } catch (IOException e) {
                log.error("Failed to create cache directory: {}", e.getMessage());
            }
        }
    }

    // ========== 图片缓存 ==========

    /**
     * 缓存图片字节
     * 
     * @param data 图片原始字节
     * @param ext 文件扩展名（含点号，如 ".jpg"）
     * @return 缓存文件的绝对路径
     */
    public String cacheImageFromBytes(byte[] data, String ext) {
        if (!looksLikeImage(data)) {
            String snippet = new String(data, 0, Math.min(80, data.length));
            throw new IllegalArgumentException(
                "Refusing to cache non-image data as " + ext + " (starts with: " + snippet + ")"
            );
        }

        String filename = "img_" + generateId() + ext;
        Path filePath = Path.of(baseCacheDir, "images", filename);
        return writeCacheFile(filePath, data);
    }

    /**
     * 缓存图片（从 URL 下载）
     */
    public String cacheImageFromUrl(String url, String ext) {
        // TODO: 使用 WebClient 下载并缓存
        throw new UnsupportedOperationException("URL download not yet implemented");
    }

    // ========== 音频缓存 ==========

    /**
     * 缓存音频字节
     */
    public String cacheAudioFromBytes(byte[] data, String ext) {
        String filename = "audio_" + generateId() + ext;
        Path filePath = Path.of(baseCacheDir, "audio", filename);
        return writeCacheFile(filePath, data);
    }

    /**
     * 缓存音频（从 URL 下载）
     */
    public String cacheAudioFromUrl(String url, String ext) {
        // TODO: 使用 WebClient 下载并缓存
        throw new UnsupportedOperationException("URL download not yet implemented");
    }

    // ========== 视频缓存 ==========

    /**
     * 缓存视频字节
     */
    public String cacheVideoFromBytes(byte[] data, String ext) {
        String filename = "video_" + generateId() + ext;
        Path filePath = Path.of(baseCacheDir, "videos", filename);
        return writeCacheFile(filePath, data);
    }

    // ========== 文档缓存 ==========

    /**
     * 缓存文档字节
     */
    public String cacheDocumentFromBytes(byte[] data, String originalFilename) {
        // 安全处理文件名
        String safeName = Path.of(originalFilename != null ? originalFilename : "document").getFileName().toString();
        safeName = safeName.replace("\0", "").strip();
        if (safeName.isEmpty() || safeName.equals(".") || safeName.equals("..")) {
            safeName = "document";
        }

        String filename = "doc_" + generateId() + "_" + safeName;
        Path filePath = Path.of(baseCacheDir, "documents", filename);

        // 安全检查：确保路径不逃逸缓存目录
        if (!filePath.normalize().startsWith(Path.of(baseCacheDir, "documents").normalize())) {
            throw new IllegalArgumentException("Path traversal rejected: " + originalFilename);
        }

        return writeCacheFile(filePath, data);
    }

    // ========== 缓存清理 ==========

    /**
     * 清理过期的缓存文件
     * 
     * @param maxAgeHours 最大保留时间（小时）
     * @return 删除的文件数量
     */
    public int cleanupCache(int maxAgeHours) {
        long cutoffMs = System.currentTimeMillis() - (maxAgeHours * 3600_000L);
        int removed = 0;

        for (String subDir : List.of("images", "audio", "videos", "documents")) {
            Path dir = Path.of(baseCacheDir, subDir);
            if (!Files.exists(dir)) continue;

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path file : stream) {
                    if (Files.isRegularFile(file)) {
                        try {
                            long lastModified = Files.getLastModifiedTime(file).toMillis();
                            if (lastModified < cutoffMs) {
                                Files.deleteIfExists(file);
                                removed++;
                            }
                        } catch (IOException e) {
                            // 跳过无法删除的文件
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to scan cache directory {}: {}", dir, e.getMessage());
            }
        }

        if (removed > 0) {
            log.info("Cleaned up {} expired cache files (max age: {}h)", removed, maxAgeHours);
        }
        return removed;
    }

    /**
     * 获取缓存目录大小
     */
    public long getCacheSizeBytes() {
        long totalSize = 0;
        for (String subDir : List.of("images", "audio", "videos", "documents")) {
            Path dir = Path.of(baseCacheDir, subDir);
            if (!Files.exists(dir)) continue;

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path file : stream) {
                    if (Files.isRegularFile(file)) {
                        totalSize += Files.size(file);
                    }
                }
            } catch (IOException e) {
                // 忽略
            }
        }
        return totalSize;
    }

    // ========== 辅助方法 ==========

    private String writeCacheFile(Path filePath, byte[] data) {
        try {
            Files.write(filePath, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return filePath.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to write cache file: " + filePath, e);
        }
    }

    private String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 检测字节是否看起来像图片
     */
    public static boolean looksLikeImage(byte[] data) {
        if (data == null || data.length < 4) return false;

        // PNG
        if (data.length >= 8 && startsWith(data, PNG_MAGIC)) return true;
        // JPEG
        if (data.length >= 3 && startsWith(data, JPEG_MAGIC)) return true;
        // GIF
        if (data.length >= 6 && (startsWith(data, GIF_MAGIC_87) || startsWith(data, GIF_MAGIC_89))) return true;
        // BMP
        if (data.length >= 2 && startsWith(data, BMP_MAGIC)) return true;
        // WebP
        if (data.length >= 12 && startsWith(data, WEBP_RIFF) &&
            data.length >= 12 && startsWith(data, 8, WEBP_WEBP)) return true;

        return false;
    }

    /**
     * 根据扩展名判断媒体类型
     */
    public static MediaType detectMediaType(String filename) {
        if (filename == null) return MediaType.UNKNOWN;
        String ext = filename.toLowerCase();
        int dotIndex = ext.lastIndexOf('.');
        if (dotIndex >= 0) ext = ext.substring(dotIndex);

        if (IMAGE_EXTENSIONS.contains(ext)) return MediaType.IMAGE;
        if (AUDIO_EXTENSIONS.contains(ext)) return MediaType.AUDIO;
        if (VIDEO_EXTENSIONS.contains(ext)) return MediaType.VIDEO;
        if (DOCUMENT_EXTENSIONS.contains(ext)) return MediaType.DOCUMENT;
        return MediaType.UNKNOWN;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return startsWith(data, 0, prefix);
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        if (data.length < offset + prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix[i]) return false;
        }
        return true;
    }

    public enum MediaType {
        IMAGE, AUDIO, VIDEO, DOCUMENT, UNKNOWN
    }

    // ========== Getters ==========

    public String getImageCacheDir() { return Path.of(baseCacheDir, "images").toString(); }
    public String getAudioCacheDir() { return Path.of(baseCacheDir, "audio").toString(); }
    public String getVideoCacheDir() { return Path.of(baseCacheDir, "videos").toString(); }
    public String getDocumentCacheDir() { return Path.of(baseCacheDir, "documents").toString(); }
}
