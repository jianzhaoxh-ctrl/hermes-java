package com.hermes.agent.memory.vector;

import com.hermes.agent.config.AgentConfig;
import com.hermes.agent.memory.MemoryProvider;
import com.hermes.agent.model.Message;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量记忆 Provider — 提供语义相似度搜索能力。
 *
 * <p>核心功能：
 * <ul>
 *   <li>语义记忆存储（自动向量化）</li>
 *   <li>相似度搜索（基于 embedding）</li>
 *   <li>用户画像存储</li>
 *   <li>会话隔离</li>
 *   <li>自动过期清理</li>
 * </ul>
 *
 * <p>工具列表：
 * <ul>
 *   <li>memory_add - 添加记忆</li>
 *   <li>memory_search - 语义搜索</li>
 *   <li>memory_get - 获取指定记忆</li>
 *   <li>memory_delete - 删除记忆</li>
 *   <li>memory_list - 列出记忆</li>
 * </ul>
 *
 * <p>参考：Python 版 Mem0 Provider 实现
 */
@Component
public class VectorMemoryProvider implements MemoryProvider {

    private static final Logger log = LoggerFactory.getLogger(VectorMemoryProvider.class);

    private final AgentConfig config;
    private final EmbeddingService embeddingService;
    private MemoryVectorStore vectorStore;

    // 缓存
    private final Map<String, String> userProfileCache = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> sessionContextCache = new ConcurrentHashMap<>();

    // 配置
    private final float similarityThreshold;
    private final int topK;
    private final boolean enabled;

    // ID 生成器
    private long idCounter = System.currentTimeMillis();

    public VectorMemoryProvider(AgentConfig config, EmbeddingService embeddingService) {
        this.config = config;
        this.embeddingService = embeddingService;

        this.similarityThreshold = config.getVectorMemorySimilarityThreshold();
        this.topK = config.getVectorMemoryTopK();
        this.enabled = config.isVectorMemoryEnabled();
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("[VectorMemory] 未启用，跳过初始化");
            return;
        }

        this.vectorStore = new MemoryVectorStore(config, embeddingService);
        log.info("[VectorMemory] 初始化完成: similarityThreshold={}, topK={}", similarityThreshold, topK);
    }

    @Override
    public String name() {
        return "vector";
    }

    @Override
    public boolean isAvailable() {
        return enabled && embeddingService != null && embeddingService.isAvailable();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  生命周期方法
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void initialize(String sessionId, Map<String, Object> kwargs) {
        log.debug("[VectorMemory] 初始化会话: {}", sessionId);
    }

    @Override
    public String systemPromptBlock() {
        if (!isAvailable()) {
            return "";
        }

        return "\n\n[Vector Memory Provider]\n" +
                "You have access to semantic memory search capabilities. " +
                "Use `memory_search` to find relevant memories by meaning, not just keywords. " +
                "Use `memory_add` to store important information for future reference.\n";
    }

    @Override
    public String prefetch(String query, String sessionId) {
        if (!isAvailable() || query == null || query.isBlank()) {
            return "";
        }

        try {
            List<MemoryVectorStore.SearchResult> results = vectorStore.searchText(query, topK, sessionId, null);

            if (results.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[Recalled memories (semantic search)]:\n");

            for (MemoryVectorStore.SearchResult result : results) {
                if (result.score >= similarityThreshold) {
                    sb.append("- ").append(result.entry.content);
                    sb.append(" (score: ").append(String.format("%.2f", result.score)).append(")\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("[VectorMemory] 预取失败: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public void syncTurn(String userContent, String assistantContent, String sessionId) {
        if (!isAvailable() || userContent == null) {
            return;
        }

        // 提取重要信息并存储
        try {
            // 简单的关键信息提取（实际应用中可使用 LLM）
            String importantInfo = extractImportantInfo(userContent, assistantContent);
            if (importantInfo != null && !importantInfo.isBlank()) {
                String id = "mem_" + (idCounter++);
                vectorStore.addText(id, importantInfo, sessionId, "conversation", Map.of(
                        "source", "sync_turn",
                        "timestamp", Instant.now().toString()
                ));
                log.debug("[VectorMemory] 同步轮次记忆: id={}", id);
            }
        } catch (Exception e) {
            log.warn("[VectorMemory] 同步失败: {}", e.getMessage());
        }
    }

    @Override
    public void shutdown() {
        if (vectorStore != null) {
            vectorStore.shutdown();
        }
        log.info("[VectorMemory] 关闭");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  工具定义
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public List<Map<String, Object>> getToolSchemas() {
        if (!isAvailable()) {
            return List.of();
        }

        List<Map<String, Object>> schemas = new ArrayList<>();

        // memory_add
        schemas.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "memory_add",
                        "description", "Store a memory for future semantic search. Use this to remember important facts, preferences, or context.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "content", Map.of(
                                                "type", "string",
                                                "description", "The memory content to store"
                                        ),
                                        "type", Map.of(
                                                "type", "string",
                                                "description", "Memory type: fact, preference, context, or general",
                                                "enum", List.of("fact", "preference", "context", "general")
                                        ),
                                        "metadata", Map.of(
                                                "type", "object",
                                                "description", "Optional metadata (key-value pairs)"
                                        )
                                ),
                                "required", List.of("content")
                        )
                )
        ));

        // memory_search
        schemas.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "memory_search",
                        "description", "Search memories by semantic similarity. Returns memories that are meaningfully related to the query.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of(
                                                "type", "string",
                                                "description", "The search query (natural language)"
                                        ),
                                        "type", Map.of(
                                                "type", "string",
                                                "description", "Filter by memory type (optional)",
                                                "enum", List.of("fact", "preference", "context", "general", "conversation")
                                        ),
                                        "limit", Map.of(
                                                "type", "integer",
                                                "description", "Maximum number of results (default: 5)",
                                                "default", 5
                                        )
                                ),
                                "required", List.of("query")
                        )
                )
        ));

        // memory_get
        schemas.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "memory_get",
                        "description", "Retrieve a specific memory by ID",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "memory_id", Map.of(
                                                "type", "string",
                                                "description", "The memory ID to retrieve"
                                        )
                                ),
                                "required", List.of("memory_id")
                        )
                )
        ));

        // memory_delete
        schemas.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "memory_delete",
                        "description", "Delete a memory by ID",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "memory_id", Map.of(
                                                "type", "string",
                                                "description", "The memory ID to delete"
                                        )
                                ),
                                "required", List.of("memory_id")
                        )
                )
        ));

        // memory_list
        schemas.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "memory_list",
                        "description", "List all memories for the current session or of a specific type",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "type", Map.of(
                                                "type", "string",
                                                "description", "Filter by memory type (optional)",
                                                "enum", List.of("fact", "preference", "context", "general", "conversation")
                                        ),
                                        "limit", Map.of(
                                                "type", "integer",
                                                "description", "Maximum number of results (default: 20)",
                                                "default", 20
                                        )
                                )
                        )
                )
        ));

        return schemas;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  工具处理
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public String handleToolCall(String toolName, Map<String, Object> args) {
        try {
            switch (toolName) {
                case "memory_add":
                    return handleMemoryAdd(args);
                case "memory_search":
                    return handleMemorySearch(args);
                case "memory_get":
                    return handleMemoryGet(args);
                case "memory_delete":
                    return handleMemoryDelete(args);
                case "memory_list":
                    return handleMemoryList(args);
                default:
                    return errorJson("Unknown tool: " + toolName);
            }
        } catch (Exception e) {
            log.error("[VectorMemory] 工具调用失败: {} - {}", toolName, e.getMessage());
            return errorJson("Tool call failed: " + e.getMessage());
        }
    }

    private String handleMemoryAdd(Map<String, Object> args) {
        String content = (String) args.get("content");
        if (content == null || content.isBlank()) {
            return errorJson("content is required");
        }

        String type = (String) args.getOrDefault("type", "general");
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) args.get("metadata");

        String id = "mem_" + (idCounter++);
        boolean success = vectorStore.addText(id, content, "default", type, metadata != null ? metadata : new HashMap<>());

        if (success) {
            return successJson(Map.of(
                    "memory_id", id,
                    "content", content,
                    "type", type,
                    "message", "Memory stored successfully"
            ));
        } else {
            return errorJson("Failed to store memory");
        }
    }

    private String handleMemorySearch(Map<String, Object> args) {
        String query = (String) args.get("query");
        if (query == null || query.isBlank()) {
            return errorJson("query is required");
        }

        String type = (String) args.get("type");
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 5;

        List<MemoryVectorStore.SearchResult> results = vectorStore.searchText(query, limit, null, type);

        List<Map<String, Object>> memories = new ArrayList<>();
        for (MemoryVectorStore.SearchResult result : results) {
            if (result.score >= similarityThreshold) {
                memories.add(result.toMap());
            }
        }

        return successJson(Map.of(
                "query", query,
                "count", memories.size(),
                "memories", memories
        ));
    }

    private String handleMemoryGet(Map<String, Object> args) {
        String memoryId = (String) args.get("memory_id");
        if (memoryId == null || memoryId.isBlank()) {
            return errorJson("memory_id is required");
        }

        MemoryVectorStore.VectorEntry entry = vectorStore.get(memoryId);
        if (entry == null) {
            return errorJson("Memory not found: " + memoryId);
        }

        return successJson(Map.of(
                "memory_id", entry.id,
                "content", entry.content,
                "type", entry.type,
                "session_id", entry.sessionId,
                "metadata", entry.metadata,
                "timestamp", entry.timestamp
        ));
    }

    private String handleMemoryDelete(Map<String, Object> args) {
        String memoryId = (String) args.get("memory_id");
        if (memoryId == null || memoryId.isBlank()) {
            return errorJson("memory_id is required");
        }

        boolean success = vectorStore.delete(memoryId);

        if (success) {
            return successJson(Map.of(
                    "memory_id", memoryId,
                    "message", "Memory deleted successfully"
            ));
        } else {
            return errorJson("Memory not found: " + memoryId);
        }
    }

    private String handleMemoryList(Map<String, Object> args) {
        String type = (String) args.get("type");
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 20;

        // 获取所有条目（简化实现）
        List<MemoryVectorStore.VectorEntry> allEntries = new ArrayList<>();
        // 注意：这里需要 VectorStore 提供一个 listAll 方法

        List<Map<String, Object>> memories = new ArrayList<>();
        int count = 0;
        for (MemoryVectorStore.VectorEntry entry : allEntries) {
            if (type != null && !type.equals(entry.type)) {
                continue;
            }
            memories.add(Map.of(
                    "memory_id", entry.id,
                    "content", entry.content.length() > 100 ? entry.content.substring(0, 100) + "..." : entry.content,
                    "type", entry.type,
                    "timestamp", entry.timestamp
            ));
            if (++count >= limit) break;
        }

        return successJson(Map.of(
                "count", memories.size(),
                "memories", memories
        ));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════════════

    private String extractImportantInfo(String userContent, String assistantContent) {
        // 简化实现：提取包含关键词的句子
        // 实际应用中应使用 LLM 进行智能提取
        String[] keywords = {"重要", "记住", "remember", "important", "关键", "偏好", "prefer", "喜欢", "like"};

        String combined = userContent + " " + assistantContent;
        for (String keyword : keywords) {
            if (combined.toLowerCase().contains(keyword.toLowerCase())) {
                // 提取包含关键词的句子
                String[] sentences = combined.split("[。！？.!?]");
                for (String sentence : sentences) {
                    if (sentence.toLowerCase().contains(keyword.toLowerCase())) {
                        return sentence.trim();
                    }
                }
            }
        }

        return null;
    }

    private String successJson(Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.putAll(data);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
        } catch (Exception e) {
            return "{\"success\":true}";
        }
    }

    private String errorJson(String error) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                    "success", false,
                    "error", error
            ));
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"Unknown error\"}";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  可选钩子
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void onSessionEnd(List<Message> messages) {
        if (!isAvailable() || messages == null || messages.isEmpty()) {
            return;
        }

        // 从会话中提取重要信息并存储
        log.debug("[VectorMemory] 会话结束，处理 {} 条消息", messages.size());
    }

    @Override
    public String onPreCompress(List<Message> messages) {
        if (!isAvailable()) {
            return "";
        }

        // 在压缩前提取关键信息
        StringBuilder sb = new StringBuilder();

        for (Message msg : messages) {
            String content = msg.getContent();
            if (content == null) continue;

            // 提取可能重要的事实
            if (content.contains("记住") || content.contains("remember") || content.contains("重要")) {
                sb.append("- ").append(content.substring(0, Math.min(200, content.length()))).append("\n");
            }
        }

        return sb.toString();
    }
}
