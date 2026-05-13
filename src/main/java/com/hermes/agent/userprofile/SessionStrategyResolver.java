package com.hermes.agent.userprofile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Session Strategy Resolver — resolves session IDs based on workspace context.
 *
 * <p>Strategies:
 * <ul>
 *   <li><b>per-session</b>: unique ID per run (default, isolated)</li>
 *   <li><b>per-directory</b>: hash of working directory (project-scoped)</li>
 *   <li><b>per-repo</b>: hash of git root (repo-scoped, crosses subdirs)</li>
 *   <li><b>global</b>: single shared session across all contexts</li>
 * </ul>
 *
 * <p>Resolution chain:
 * <ol>
 *   <li>Explicit session ID from caller (always wins)</li>
 *   <li>Gateway session key (if available)</li>
 *   <li>Session title (if provided)</li>
 *   <li>Git repository root hash (per-repo strategy)</li>
 *   <li>Working directory hash (per-directory strategy)</li>
 *   <li>Fallback to provided sessionId or default</li>
 * </ol>
 *
 * <p>Cache: resolved session keys are cached for the duration of the JVM process.
 */
@Component
public class SessionStrategyResolver {

    private static final Logger log = LoggerFactory.getLogger(SessionStrategyResolver.class);

    private final UserProfileConfig config;

    /** Cache: original sessionId → resolved session key */
    private final java.util.concurrent.ConcurrentHashMap<String, String> resolutionCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Current working directory at startup */
    private final Path cwd;

    /** Git root cache: cwd → gitRoot (or null if not a repo) */
    private volatile Path cachedGitRoot;
    private volatile boolean gitRootChecked = false;

    public SessionStrategyResolver(UserProfileConfig config) {
        this.config = config;
        this.cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        log.info("[SessionStrategy] Initialized: strategy={}, cwd={}", 
                 config.getSessionStrategy(), cwd);
    }

    /**
     * Resolve the effective session key from the given parameters.
     *
     * @param sessionId       The session ID provided by the caller
     * @param sessionTitle    Optional session title
     * @param gatewayKey      Optional gateway session key
     * @param explicitKey     Optional explicit session key (highest priority)
     * @return Resolved session key
     */
    public String resolveSessionKey(String sessionId, String sessionTitle, 
                                     String gatewayKey, String explicitKey) {
        String strategy = config.getSessionStrategy();

        // 1. Explicit key wins absolutely
        if (explicitKey != null && !explicitKey.isBlank()) {
            log.debug("[SessionStrategy] Using explicit key: {}", explicitKey);
            return sanitizeKey(explicitKey);
        }

        // 2. Gateway session key
        if (gatewayKey != null && !gatewayKey.isBlank()) {
            log.debug("[SessionStrategy] Using gateway key: {}", gatewayKey);
            return sanitizeKey(gatewayKey);
        }

        // 3. Session title
        if (sessionTitle != null && !sessionTitle.isBlank()) {
            String key = sanitizeKey(sessionTitle);
            log.debug("[SessionStrategy] Using title key: {}", key);
            return key;
        }

        // 4. Strategy-based resolution
        String resolvedKey = switch (strategy) {
            case "global" -> {
                log.debug("[SessionStrategy] Using global key");
                yield "hermes-global";
            }
            case "per-repo" -> {
                String key = resolveRepoKey();
                log.debug("[SessionStrategy] Using repo key: {}", key);
                yield key;
            }
            case "per-directory" -> {
                String key = resolveDirectoryKey();
                log.debug("[SessionStrategy] Using directory key: {}", key);
                yield key;
            }
            default -> {
                // per-session: use the provided sessionId or generate one
                log.debug("[SessionStrategy] Using per-session key: {}", sessionId);
                yield sessionId != null ? sessionId : "hermes-session-" + System.currentTimeMillis();
            }
        };

        return resolvedKey;
    }

    /**
     * Resolve session key based on git repository root.
     * Returns a hash of the .git directory path.
     */
    private String resolveRepoKey() {
        Path gitRoot = findGitRoot();
        if (gitRoot == null) {
            log.debug("[SessionStrategy] No git root found, falling back to directory key");
            return resolveDirectoryKey();
        }
        return "repo-" + hashPath(gitRoot);
    }

    /**
     * Resolve session key based on current working directory.
     * Returns a hash of the directory path.
     */
    private String resolveDirectoryKey() {
        return "dir-" + hashPath(cwd);
    }

    /**
     * Find the git repository root by walking up the directory tree.
     * Caches the result for the lifetime of the process.
     */
    private Path findGitRoot() {
        if (gitRootChecked) {
            return cachedGitRoot;
        }

        synchronized (this) {
            if (gitRootChecked) {
                return cachedGitRoot;
            }

            Path current = cwd;
            while (current != null) {
                Path gitDir = current.resolve(".git");
                if (Files.isDirectory(gitDir)) {
                    cachedGitRoot = current.normalize();
                    gitRootChecked = true;
                    log.debug("[SessionStrategy] Found git root: {}", cachedGitRoot);
                    return cachedGitRoot;
                }
                current = current.getParent();
            }

            gitRootChecked = true;
            log.debug("[SessionStrategy] No git root found from: {}", cwd);
            return null;
        }
    }

    /**
     * Get the repository name from git root or directory name.
     */
    public Optional<String> getRepositoryName() {
        Path gitRoot = findGitRoot();
        if (gitRoot != null) {
            return Optional.of(gitRoot.getFileName().toString());
        }
        return Optional.ofNullable(cwd.getFileName()).map(Path::toString);
    }

    /**
     * Get the current working directory.
     */
    public Path getWorkingDirectory() {
        return cwd;
    }

    /**
     * Get the git root if available.
     */
    public Optional<Path> getGitRoot() {
        return Optional.ofNullable(findGitRoot());
    }

    /**
     * Get a display-friendly session label.
     */
    public String getSessionLabel() {
        String strategy = config.getSessionStrategy();

        return switch (strategy) {
            case "global" -> "global";
            case "per-repo" -> getRepositoryName().orElse("repo");
            case "per-directory" -> cwd.getFileName() != null 
                    ? cwd.getFileName().toString() : "directory";
            default -> "session";
        };
    }

    /**
     * Get strategy information for diagnostics.
     */
    public java.util.Map<String, Object> getStrategyInfo() {
        return java.util.Map.of(
            "strategy", config.getSessionStrategy(),
            "cwd", cwd.toString(),
            "gitRoot", cachedGitRoot != null ? cachedGitRoot.toString() : "none",
            "label", getSessionLabel()
        );
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * Sanitize a session key for use as identifier.
     */
    private String sanitizeKey(String key) {
        if (key == null || key.isBlank()) return "default";
        return key.replaceAll("[^a-zA-Z0-9_\\-]", "-")
                  .replaceAll("-+", "-")
                  .replaceAll("^-|-$", "")
                  .toLowerCase();
    }

    /**
     * Hash a path to a short identifier.
     */
    private String hashPath(Path path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(path.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            // Fallback to simple hash
            return Integer.toHexString(path.hashCode());
        }
    }

    /**
     * Force re-detection of git root (call if directory changes).
     */
    public void refreshGitRoot() {
        gitRootChecked = false;
        cachedGitRoot = null;
    }
}
