package com.hermes.agent.memory.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.agent.config.AgentConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DashScope Embedding 服务实现。
 *
 * <p>支持模型：
 * <ul>
 *   <li>text-embedding-v3 - 1024 维度，推荐</li>
 *   <li>text-embedding-v2 - 1536 维度</li>
 *   <li>text-embedding-v1 - 1536 维度</li>
 * </ul>
 *
 * <p>API 文档：https://help.aliyun.com/document_detail/2712123.html
 */
@Component
public class DashScopeEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEmbeddingService.class);

    private static final String API_URL = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

    private final AgentConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private OkHttpClient httpClient;

    private String apiKey;
    private String model;
    private int dimension;

    public DashScopeEmbeddingService(AgentConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        this.apiKey = config.getVectorMemoryApiKey();
        if (this.apiKey == null || this.apiKey.isBlank()) {
            this.apiKey = System.getenv("DASHSCOPE_API_KEY");
        }
        
        this.model = config.getVectorMemoryEmbeddingModel();
        if (this.model == null || this.model.isBlank()) {
            this.model = "text-embedding-v3";
        }

        this.dimension = config.getVectorMemoryDimension();
        if (this.dimension <= 0) {
            this.dimension = 1024;  // text-embedding-v3 默认维度
        }

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        log.info("[DashScopeEmbedding] 初始化完成: model={}, dimension={}, available={}",
                model, dimension, isAvailable());
    }

    @Override
    public String getProviderName() {
        return "dashscope";
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    @Override
    public float[] embed(String text) throws EmbeddingException {
        if (text == null || text.isBlank()) {
            throw new EmbeddingException("Text cannot be null or empty");
        }

        if (!isAvailable()) {
            throw new EmbeddingException("DashScope API key not configured");
        }

        try {
            // 构建请求体
            String requestBody = objectMapper.writeValueAsString(new java.util.HashMap<String, Object>() {{
                put("model", model);
                put("input", new java.util.HashMap<String, Object>() {{
                    put("texts", List.of(text));
                }});
                put("parameters", new java.util.HashMap<String, Object>() {{
                    put("text_type", "query");
                }});
            }});

            Request request = new Request.Builder()
                    .url(API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                    throw new EmbeddingException("API request failed: " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                JsonNode root = objectMapper.readTree(responseBody);

                // 检查错误
                if (root.has("code") && !"Success".equals(root.get("code").asText())) {
                    String message = root.has("message") ? root.get("message").asText() : "Unknown error";
                    throw new EmbeddingException("DashScope error: " + message);
                }

                // 提取 embedding
                JsonNode embeddings = root.path("output").path("embeddings");
                if (embeddings.isArray() && embeddings.size() > 0) {
                    JsonNode embedding = embeddings.get(0).path("embedding");
                    if (embedding.isArray()) {
                        float[] vector = new float[embedding.size()];
                        for (int i = 0; i < embedding.size(); i++) {
                            vector[i] = (float) embedding.get(i).asDouble();
                        }
                        log.debug("[DashScopeEmbedding] 生成长度: {}", vector.length);
                        return vector;
                    }
                }

                throw new EmbeddingException("Invalid response format: embedding not found");
            }
        } catch (IOException e) {
            throw new EmbeddingException("API request failed", e);
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) throws EmbeddingException {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        if (!isAvailable()) {
            throw new EmbeddingException("DashScope API key not configured");
        }

        // DashScope 支持批量请求，最多 25 个文本
        List<float[]> results = new ArrayList<>();
        int batchSize = 25;

        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);

            try {
                String requestBody = objectMapper.writeValueAsString(new java.util.HashMap<String, Object>() {{
                    put("model", model);
                    put("input", new java.util.HashMap<String, Object>() {{
                        put("texts", batch);
                    }});
                    put("parameters", new java.util.HashMap<String, Object>() {{
                        put("text_type", "query");
                    }});
                }});

                Request request = new Request.Builder()
                        .url(API_URL)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                        throw new EmbeddingException("API request failed: " + response.code() + " - " + errorBody);
                    }

                    String responseBody = response.body().string();
                    JsonNode root = objectMapper.readTree(responseBody);

                    if (root.has("code") && !"Success".equals(root.get("code").asText())) {
                        String message = root.has("message") ? root.get("message").asText() : "Unknown error";
                        throw new EmbeddingException("DashScope error: " + message);
                    }

                    JsonNode embeddings = root.path("output").path("embeddings");
                    if (embeddings.isArray()) {
                        for (JsonNode embNode : embeddings) {
                            JsonNode embedding = embNode.path("embedding");
                            if (embedding.isArray()) {
                                float[] vector = new float[embedding.size()];
                                for (int j = 0; j < embedding.size(); j++) {
                                    vector[j] = (float) embedding.get(j).asDouble();
                                }
                                results.add(vector);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new EmbeddingException("Batch API request failed", e);
            }
        }

        log.debug("[DashScopeEmbedding] 批量生成: {} 个向量", results.size());
        return results;
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }
}
