package com.hermes.agent.acp.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * ACP authentication provider detection.
 *
 * <p>Detects the currently configured Hermes runtime provider (e.g. OpenAI,
 * Anthropic, OpenRouter) by inspecting environment variables and configuration.
 * Advertised to ACP clients during the {@code initialize} handshake so that
 * editors can display authentication status.
 *
 * <p>Reference: Python acp_adapter/auth.py
 */
public final class ACPAuthProvider {

    private static final Logger log = LoggerFactory.getLogger(ACPAuthProvider.class);

    /** Known provider environment variable prefixes */
    private static final String[][] PROVIDER_ENV_MAP = {
            {"openai",       "OPENAI_API_KEY"},
            {"anthropic",    "ANTHROPIC_API_KEY"},
            {"openrouter",   "OPENROUTER_API_KEY"},
            {"deepseek",     "DEEPSEEK_API_KEY"},
            {"dashscope",    "DASHSCOPE_API_KEY"},
            {"gemini",       "GEMINI_API_KEY"},
            {"google",       "GOOGLE_API_KEY"},
            {"mistral",      "MISTRAL_API_KEY"},
            {"groq",         "GROQ_API_KEY"},
            {"together",     "TOGETHER_API_KEY"},
            {"xai",          "XAI_API_KEY"},
            {"azure",        "AZURE_OPENAI_API_KEY"},
    };

    /** Cached provider (detected once, then reused) */
    private static volatile String cachedProvider;

    private ACPAuthProvider() {}

    /**
     * Detect the active Hermes runtime provider by checking environment variables.
     *
     * @return the provider name (lowercase), or empty if no provider is configured
     */
    public static Optional<String> detectProvider() {
        if (cachedProvider != null) {
            return Optional.of(cachedProvider);
        }

        // 1. Check HERMES_PROVIDER env var first (explicit override)
        String explicitProvider = System.getenv("HERMES_PROVIDER");
        if (explicitProvider != null && !explicitProvider.isBlank()) {
            cachedProvider = explicitProvider.strip().toLowerCase();
            log.info("ACP provider set via HERMES_PROVIDER: {}", cachedProvider);
            return Optional.of(cachedProvider);
        }

        // 2. Check HERMES_MODEL env var for provider prefix (e.g. "openrouter:deepseek/chat")
        String hermesModel = System.getenv("HERMES_MODEL");
        if (hermesModel != null && hermesModel.contains(":")) {
            String prefix = hermesModel.substring(0, hermesModel.indexOf(':')).strip().toLowerCase();
            cachedProvider = prefix;
            log.info("ACP provider inferred from HERMES_MODEL prefix: {}", cachedProvider);
            return Optional.of(cachedProvider);
        }

        // 3. Check known API key environment variables
        for (String[] entry : PROVIDER_ENV_MAP) {
            String provider = entry[0];
            String envKey = entry[1];
            String value = System.getenv(envKey);
            if (value != null && !value.isBlank()) {
                cachedProvider = provider;
                log.info("ACP provider detected from {}: {}", envKey, provider);
                return Optional.of(cachedProvider);
            }
        }

        // 4. Check application config (hermes.agent.provider property)
        String configProvider = System.getProperty("hermes.agent.provider");
        if (configProvider != null && !configProvider.isBlank()) {
            cachedProvider = configProvider.strip().toLowerCase();
            log.info("ACP provider from system property: {}", cachedProvider);
            return Optional.of(cachedProvider);
        }

        log.debug("No ACP provider detected from environment or config");
        return Optional.empty();
    }

    /**
     * Check if any provider is available.
     */
    public static boolean hasProvider() {
        return detectProvider().isPresent();
    }

    /**
     * Reset cached provider (for testing).
     */
    public static void resetCache() {
        cachedProvider = null;
    }
}
