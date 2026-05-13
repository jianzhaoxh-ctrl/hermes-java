package com.hermes.agent.memory.vector;

import com.hermes.agent.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Vector Memory Configuration — 自动配置向量记忆相关 Beans。
 *
 * <p>DashScopeEmbeddingService 已有 @Component 注解，会自动注册。
 * 此配置类主要注册 MemoryVectorStore Bean。
 *
 * <p>启用条件：hermes.vector.enabled=true（默认启用）
 */
@Configuration
@ConditionalOnProperty(prefix = "hermes.vector", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VectorMemoryConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorMemoryConfig.class);

    /**
     * Memory Vector Store Bean — 基于 HNSW 算法的向量存储。
     * 依赖 EmbeddingService（由 DashScopeEmbeddingService 自动注册）和 AgentConfig。
     */
    @Bean
    @ConditionalOnMissingBean(MemoryVectorStore.class)
    public MemoryVectorStore memoryVectorStore(AgentConfig config, EmbeddingService embeddingService) {
        log.info("[VectorMemory] MemoryVectorStore initialized");
        return new MemoryVectorStore(config, embeddingService);
    }
}
