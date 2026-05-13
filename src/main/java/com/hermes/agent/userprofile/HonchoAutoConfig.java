package com.hermes.agent.userprofile;

import com.hermes.agent.llm.LLMService;
import com.hermes.agent.memory.MemoryOrchestrator;
import com.hermes.agent.memory.MemoryProvider;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Auto-configuration that registers HonchoMemoryProvider with MemoryOrchestrator.
 * Activated when hermes.profile.enabled=true (default: false to preserve backward compat).
 *
 * Also provides Bean definitions for:
 * - SessionStrategyResolver (session key resolution)
 * - VectorStore (semantic search storage)
 * - EmbeddingService (embedding generation)
 */
@Component
@ConditionalOnProperty(name = "hermes.profile.enabled", havingValue = "true", matchIfMissing = false)
public class HonchoAutoConfig {

    private static final Logger log = LoggerFactory.getLogger(HonchoAutoConfig.class);

    private final HonchoMemoryProvider honchoProvider;
    private final MemoryOrchestrator orchestrator;

    public HonchoAutoConfig(HonchoMemoryProvider honchoProvider, MemoryOrchestrator orchestrator) {
        this.honchoProvider = honchoProvider;
        this.orchestrator = orchestrator;
    }

    @PostConstruct
    public void register() {
        orchestrator.addProvider(honchoProvider);
        log.info("[Honcho] Auto-configured and registered with MemoryOrchestrator");
    }

    @Bean
    public SessionStrategyResolver sessionStrategyResolver(UserProfileConfig config) {
        return new SessionStrategyResolver(config);
    }

    @Bean
    public VectorStore vectorStore(UserProfileConfig config) {
        return new VectorStore(config);
    }

    @Bean
    public EmbeddingService embeddingService(UserProfileConfig config, LLMService llmService) {
        return new EmbeddingService(config, llmService);
    }
}
