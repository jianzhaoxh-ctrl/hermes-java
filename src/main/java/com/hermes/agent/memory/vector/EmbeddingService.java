package com.hermes.agent.memory.vector;

import java.util.List;

/**
 * Embedding 服务接口 — 将文本转换为向量表示。
 *
 * <p>支持多种 Embedding 提供商：
 * <ul>
 *   <li>DashScope (阿里云) - text-embedding-v3</li>
 *   <li>OpenAI - text-embedding-3-small/large</li>
 *   <li>Local - 本地模型（可选）</li>
 * </ul>
 */
public interface EmbeddingService {

    /**
     * 获取提供商名称
     */
    String getProviderName();

    /**
     * 获取模型名称
     */
    String getModelName();

    /**
     * 获取向量维度
     */
    int getDimension();

    /**
     * 将单个文本转换为向量
     *
     * @param text 输入文本
     * @return 向量表示（float 数组）
     * @throws EmbeddingException 转换失败时抛出
     */
    float[] embed(String text) throws EmbeddingException;

    /**
     * 批量将文本转换为向量
     *
     * @param texts 输入文本列表
     * @return 向量列表
     * @throws EmbeddingException 转换失败时抛出
     */
    List<float[]> embedBatch(List<String> texts) throws EmbeddingException;

    /**
     * 计算两个向量的余弦相似度
     *
     * @param vec1 向量1
     * @param vec2 向量2
     * @return 相似度 (0.0-1.0)
     */
    default float cosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1 == null || vec2 == null || vec1.length != vec2.length) {
            return 0.0f;
        }

        float dotProduct = 0.0f;
        float norm1 = 0.0f;
        float norm2 = 0.0f;

        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }

        if (norm1 == 0 || norm2 == 0) {
            return 0.0f;
        }

        return dotProduct / (float) (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    /**
     * 检查服务是否可用
     */
    boolean isAvailable();

    /**
     * Embedding 异常
     */
    class EmbeddingException extends Exception {
        public EmbeddingException(String message) {
            super(message);
        }

        public EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
