package com.hermes.agent.llm;

/**
 * LLM 提供商接口
 * 
 * 抽象不同 LLM 提供商的 API 差异，支持：
 * - DashScope (阿里云通义千问)
 * - OpenAI 兼容 API (OpenAI / DeepSeek / 本地 Ollama 等)
 * - Anthropic (Claude)
 * - Azure OpenAI
 * 
 * 设计参考 Python 版 gateway 中多 LLM 适配器模式。
 */
public interface LLMProvider {

    /**
     * 提供商标识
     */
    String getProviderId();

    /**
     * 提供商显示名称
     */
    default String getDisplayName() {
        return getProviderId();
    }

    /**
     * 检查提供商是否可用（API Key 配置等）
     */
    boolean isAvailable();

    /**
     * 支持的默认模型
     */
    String getDefaultModel();

    /**
     * 获取可用模型列表
     */
    java.util.List<String> getAvailableModels();

    /**
     * 是否支持 Function Calling / Tools
     */
    default boolean supportsToolCalling() {
        return true;
    }

    /**
     * 是否支持流式输出
     */
    default boolean supportsStreaming() {
        return true;
    }

    /**
     * 最大上下文长度
     */
    default int getMaxContextLength() {
        return 32768;
    }
}
