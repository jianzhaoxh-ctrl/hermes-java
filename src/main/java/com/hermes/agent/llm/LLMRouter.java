package com.hermes.agent.llm;

import com.hermes.agent.config.AgentConfig;
import com.hermes.agent.model.Message;
import com.hermes.agent.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 服务路由器
 * 
 * 管理多个 LLM 提供商，根据模型名称或提供商 ID 路由请求。
 * 
 * 支持的路由格式：
 * - "qwen-plus" → 自动路由到 DashScope
 * - "openai/gpt-4o" → 显式指定 OpenAI 提供商
 * - "deepseek/deepseek-chat" → 显式指定 DeepSeek
 * - "ollama/llama3.1" → 显式指定 Ollama
 * 
 * 配置示例（config.yaml）：
 * <pre>
 * llm:
 *   default_provider: dashscope
 *   providers:
 *     dashscope:
 *       api_key: ${DASHSCOPE_API_KEY}
 *       default_model: qwen-plus
 *     openai:
 *       api_key: ${OPENAI_API_KEY}
 *       default_model: gpt-4o-mini
 *     deepseek:
 *       api_key: ${DEEPSEEK_API_KEY}
 *       default_model: deepseek-chat
 *     ollama:
 *       base_url: http://localhost:11434/v1
 *       default_model: qwen2.5
 * </pre>
 */
@Component
public class LLMRouter implements LLMChat {

    private static final Logger log = LoggerFactory.getLogger(LLMRouter.class);

    private final Map<String, LLMChat> providers = new ConcurrentHashMap<>();
    /** P1-14: 模型名→提供商缓存，避免 O(n*m) 遍历 */
    private final Map<String, String> modelToProviderCache = new ConcurrentHashMap<>();
    private String defaultProviderId;  // 改为非 final，允许运行时修改

    public LLMRouter(AgentConfig config, ToolRegistry toolRegistry) {
        this.defaultProviderId = config.getDefaultProvider() != null 
            ? config.getDefaultProvider() : "dashscope";

        // 注册 DashScope（始终可用）
        DashScopeProvider dashScope = new DashScopeProvider(config, toolRegistry);
        registerProvider(dashScope);

        // 注册可选提供商
        String openaiKey = System.getenv("OPENAI_API_KEY");
        if (openaiKey != null && !openaiKey.isBlank()) {
            String openaiModel = System.getenv("OPENAI_DEFAULT_MODEL");
            registerProvider(OpenAICompatibleProvider.openai(openaiKey, openaiModel, toolRegistry));
        }

        String deepseekKey = System.getenv("DEEPSEEK_API_KEY");
        if (deepseekKey != null && !deepseekKey.isBlank()) {
            String deepseekModel = System.getenv("DEEPSEEK_DEFAULT_MODEL");
            registerProvider(OpenAICompatibleProvider.deepseek(deepseekKey, deepseekModel, toolRegistry));
        }

        String ollamaUrl = System.getenv("OLLAMA_BASE_URL");
        if (ollamaUrl != null) {
            String ollamaModel = System.getenv("OLLAMA_DEFAULT_MODEL");
            registerProvider(OpenAICompatibleProvider.ollama(ollamaUrl, ollamaModel, toolRegistry));
        }

        log.info("LLM Router initialized with {} providers: {}",
            providers.size(), String.join(", ", providers.keySet()));
    }

    /**
     * 注册提供商
     */
    public void registerProvider(LLMChat provider) {
        if (provider instanceof LLMProvider info) {
            providers.put(info.getProviderId(), provider);
            // P1-14: 预构建模型→提供商映射缓存
            for (String m : info.getAvailableModels()) {
                modelToProviderCache.putIfAbsent(m, info.getProviderId());
            }
            log.info("Registered LLM provider: {} ({})", info.getProviderId(), info.getDisplayName());
        }
    }

    /**
     * 解析模型字符串为 (providerId, model)
     * 
     * 格式：
     * - "qwen-plus" → (defaultProvider, "qwen-plus")
     * - "openai/gpt-4o" → ("openai", "gpt-4o")
     */
    protected ParsedModel parseModel(String model) {
        if (model == null || model.isBlank()) {
            LLMChat defaultProvider = providers.get(defaultProviderId);
            String defaultModel = (defaultProvider instanceof LLMProvider info) 
                ? info.getDefaultModel() : "qwen-plus";
            return new ParsedModel(defaultProviderId, defaultModel);
        }

        if (model.contains("/")) {
            int slash = model.indexOf('/');
            String providerId = model.substring(0, slash);
            String modelName = model.substring(slash + 1);
            return new ParsedModel(providerId, modelName);
        }

        // P1-14: O(1) 缓存查找，替代 O(n*m) 遍历
        String cachedProvider = modelToProviderCache.get(model);
        if (cachedProvider != null && providers.containsKey(cachedProvider)) {
            return new ParsedModel(cachedProvider, model);
        }

        // 缓存未命中 → 使用默认提供商（未知模型也走默认）
        return new ParsedModel(defaultProviderId, model);
    }

    private LLMChat resolveProvider(String providerId) {
        LLMChat provider = providers.get(providerId);
        if (provider != null) return provider;

        log.warn("Provider '{}' not found, falling back to default '{}'", providerId, defaultProviderId);
        return providers.getOrDefault(defaultProviderId, providers.values().iterator().next());
    }

    // ========== LLMChat 委托 ==========

    @Override
    public Mono<Message> chat(List<Message> history, String model, String sessionId) {
        ParsedModel pm = parseModel(model);
        log.debug("Routing chat to provider: {}, model: {}", pm.providerId(), pm.model());
        return resolveProvider(pm.providerId()).chat(history, pm.model(), sessionId);
    }

    @Override
    public Mono<Message> chatWithTools(List<Message> history, String model, String sessionId) {
        ParsedModel pm = parseModel(model);
        return resolveProvider(pm.providerId()).chatWithTools(history, pm.model(), sessionId);
    }

    @Override
    public Mono<Message> chatSingle(List<Message> history, String model, String sessionId) {
        ParsedModel pm = parseModel(model);
        return resolveProvider(pm.providerId()).chatSingle(history, pm.model(), sessionId);
    }

    @Override
    public Flux<String> chatStream(List<Message> history, String model, String sessionId) {
        ParsedModel pm = parseModel(model);
        return resolveProvider(pm.providerId()).chatStream(history, pm.model(), sessionId);
    }

    // ========== 管理接口 ==========

    /**
     * 获取所有已注册提供商
     */
    public Map<String, LLMProvider> getProviders() {
        Map<String, LLMProvider> result = new LinkedHashMap<>();
        for (Map.Entry<String, LLMChat> entry : providers.entrySet()) {
            if (entry.getValue() instanceof LLMProvider info) {
                result.put(entry.getKey(), info);
            }
        }
        return result;
    }

    /**
     * 获取所有可用模型（带提供商前缀）
     */
    public List<String> getAllAvailableModels() {
        List<String> models = new ArrayList<>();
        for (Map.Entry<String, LLMChat> entry : providers.entrySet()) {
            if (entry.getValue() instanceof LLMProvider info) {
                for (String m : info.getAvailableModels()) {
                    models.add(entry.getKey() + "/" + m);
                }
            }
        }
        return models;
    }

    /**
     * 设置默认提供商
     */
    public void setDefaultProvider(String providerId) {
        if (providers.containsKey(providerId)) {
            this.defaultProviderId = providerId;
            log.info("Default provider set to: {}", providerId);
        } else {
            log.warn("Cannot set default provider: '{}' not registered", providerId);
        }
    }

    /**
     * 解析后的模型信息
     */
    protected record ParsedModel(String providerId, String model) {}
}
