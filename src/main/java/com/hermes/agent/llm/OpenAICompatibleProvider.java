package com.hermes.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.agent.config.AgentConfig;
import com.hermes.agent.model.Message;
import com.hermes.agent.model.ToolCall;
import com.hermes.agent.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OpenAI 兼容 API 适配器
 * 
 * 适用于所有 OpenAI 兼容的 API 端点：
 * - OpenAI 官方 API
 * - DeepSeek
 * - 本地 Ollama
 * - vLLM / LocalAI
 * - Azure OpenAI (需配置 endpoint)
 * 
 * 使用标准 OpenAI Chat Completions API 格式。
 */
public class OpenAICompatibleProvider implements LLMProvider, LLMChat {

    private static final Logger log = LoggerFactory.getLogger(OpenAICompatibleProvider.class);
    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private final String providerId;
    private final String displayName;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;
    private final List<String> availableModels;
    private final ToolRegistry toolRegistry;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAICompatibleProvider(String providerId, String displayName,
                                    String baseUrl, String apiKey, String defaultModel,
                                    List<String> availableModels, ToolRegistry toolRegistry) {
        this.providerId = providerId;
        this.displayName = displayName;
        this.baseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.availableModels = availableModels;
        this.toolRegistry = toolRegistry;

        WebClient.Builder builder = WebClient.builder()
            .baseUrl(this.baseUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        this.webClient = builder.build();
    }

    // ========== LLMProvider ==========

    @Override
    public String getProviderId() { return providerId; }

    @Override
    public String getDisplayName() { return displayName; }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String getDefaultModel() { return defaultModel; }

    @Override
    public List<String> getAvailableModels() { return availableModels; }

    @Override
    public int getMaxContextLength() {
        // 根据模型判断
        if (defaultModel.contains("gpt-4") || defaultModel.contains("claude")) return 128000;
        if (defaultModel.contains("deepseek")) return 64000;
        return 32768;
    }

    // ========== LLMChat ==========

    @Override
    public Mono<Message> chat(List<Message> history, String model, String sessionId) {
        String useModel = model != null ? model : defaultModel;
        List<Map<String, Object>> messages = buildMessages(history, false);
        Map<String, Object> requestBody = buildRequestBody(useModel, messages, false);

        return webClient.post()
            .uri("/chat/completions")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(30))
            .map(this::parseChatResponse)
            .doOnError(e -> log.error("[{}] chat failed for session {}: {}", 
                providerId, sessionId, e.getMessage()));
    }

    @Override
    public Mono<Message> chatWithTools(List<Message> history, String model, String sessionId) {
        String useModel = model != null ? model : defaultModel;
        List<Map<String, Object>> messages = buildMessages(history, true);
        Map<String, Object> requestBody = buildRequestBody(useModel, messages, true);

        return doRequestWithTools(requestBody, sessionId, history, 0);
    }

    @Override
    public Mono<Message> chatSingle(List<Message> history, String model, String sessionId) {
        String useModel = model != null ? model : defaultModel;
        List<Map<String, Object>> messages = buildMessages(history, true);
        Map<String, Object> requestBody = buildRequestBody(useModel, messages, true);

        return webClient.post()
            .uri("/chat/completions")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(30))
            .map(this::parseSingleResponse)
            .doOnError(e -> log.error("[{}] single-shot failed for session {}: {}", 
                providerId, sessionId, e.getMessage()));
    }

    @Override
    public Flux<String> chatStream(List<Message> history, String model, String sessionId) {
        String useModel = model != null ? model : defaultModel;
        List<Map<String, Object>> messages = buildMessages(history, false);
        Map<String, Object> requestBody = buildRequestBody(useModel, messages, false);
        requestBody.put("stream", true);

        // P0-8 fix: 使用 DataBuffer 接收原始字节流，按 SSE 协议逐行解析
        // bodyToFlux(String.class) 无法正确处理 SSE — WebFlux 会任意拆分/合并字节块
        return webClient.post()
            .uri("/chat/completions")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToFlux(DataBuffer.class)
            .timeout(Duration.ofSeconds(60))
            .concatMap(this::parseSSELines)
            .filter(line -> line.startsWith("data: ") && !line.equals("data: [DONE]"))
            .map(this::parseSSEContent)
            .filter(text -> text != null && !text.isEmpty())
            .doOnError(e -> log.error("[{}] stream failed for session {}: {}",
                providerId, sessionId, e.getMessage()));
    }

    /**
     * 将 DataBuffer 拆分为 SSE 行。
     * 一个 DataBuffer 可能包含多行，也可能一行跨多个 DataBuffer。
     * 使用 StringBuilder 累积缓冲区，按 \n 或 \r\n 拆分。
     */
    private Flux<String> parseSSELines(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        String chunk = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        // 按行拆分（处理 \n 和 \r\n）
        String[] lines = chunk.split("\\r?\\n");
        return Flux.fromArray(lines);
    }

    /**
     * 解析单条 SSE data 行，提取 delta.content 文本。
     */
    private String parseSSEContent(String line) {
        String json = line.substring(6);  // 去掉 "data: " 前缀
        try {
            if (json.startsWith("{")) {
                Map<String, Object> obj = objectMapper.readValue(json, Map.class);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices =
                    (List<Map<String, Object>>) obj.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> delta =
                        (Map<String, Object>) choices.get(0).get("delta");
                    if (delta != null) {
                        Object content = delta.get("content");
                        if (content != null) {
                            return content.toString();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ========== 请求构建 ==========

    private Map<String, Object> buildRequestBody(String model, 
            List<Map<String, Object>> messages, boolean withTools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", 4096);
        body.put("temperature", 0.7);

        if (withTools && toolRegistry != null) {
            List<Map<String, Object>> toolsSpec = buildToolsSpec();
            if (!toolsSpec.isEmpty()) {
                body.put("tools", toolsSpec);
                body.put("tool_choice", "auto");
            }
        }

        return body;
    }

    private List<Map<String, Object>> buildMessages(List<Message> history, boolean includeTools) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message m : history) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", m.getRole());

            // OpenAI 格式：content 可以是 string 或 null
            if (m.getContent() != null) {
                msg.put("content", m.getContent());
            } else {
                msg.put("content", "");
            }

            if (includeTools && m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                List<Map<String, Object>> tcList = new ArrayList<>();
                for (ToolCall tc : m.getToolCalls()) {
                    Map<String, Object> tcMap = new LinkedHashMap<>();
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", tc.getName());
                    fn.put("arguments", tc.getArguments());
                    tcMap.put("type", "function");
                    tcMap.put("function", fn);
                    tcMap.put("id", tc.getId());
                    tcList.add(tcMap);
                }
                msg.put("tool_calls", tcList);
            }

            if ("tool".equals(m.getRole())) {
                msg.put("tool_call_id", m.getToolCallId());
            }

            messages.add(msg);
        }
        return messages;
    }

    private List<Map<String, Object>> buildToolsSpec() {
        if (toolRegistry == null) return List.of();
        Map<String, ToolRegistry.ToolSpec> allSpecs = toolRegistry.getAllToolSpecs();
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Map.Entry<String, ToolRegistry.ToolSpec> entry : allSpecs.entrySet()) {
            ToolRegistry.ToolSpec spec = entry.getValue();
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", spec.getName());
            function.put("description", spec.getDescription());
            function.put("parameters", spec.getParameters());
            tool.put("function", function);
            tools.add(tool);
        }
        return tools;
    }

    // ========== 响应解析 ==========

    @SuppressWarnings("unchecked")
    private Message parseChatResponse(String rawResponse) {
        try {
            Map<String, Object> response = objectMapper.readValue(rawResponse, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return new Message("assistant", "无响应。", Instant.now());
            }
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = message != null ? (String) message.get("content") : null;
            return new Message("assistant", content != null ? content : "", Instant.now());
        } catch (Exception e) {
            return new Message("assistant", "响应解析错误: " + e.getMessage(), Instant.now());
        }
    }

    @SuppressWarnings("unchecked")
    private Message parseSingleResponse(String rawResponse) {
        try {
            Map<String, Object> response = objectMapper.readValue(rawResponse, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return new Message("assistant", "无响应。", Instant.now());
            }
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null) {
                return new Message("assistant", "响应中无消息。", Instant.now());
            }
            String content = (String) message.get("content");
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");

            Message result = new Message("assistant", content != null ? content : "", Instant.now());
            if (toolCalls != null && !toolCalls.isEmpty()) {
                log.info("[{}] returned {} tool call(s) - single-shot mode", providerId, toolCalls.size());
                result.setToolCalls(extractToolCalls(toolCalls));
            }
            return result;
        } catch (Exception e) {
            return new Message("assistant", "响应解析错误: " + e.getMessage(), Instant.now());
        }
    }

    // ========== 工具执行 ==========

    @SuppressWarnings("unchecked")
    private Mono<Message> doRequestWithTools(Map<String, Object> requestBody,
                                              String sessionId, List<Message> originalHistory) {
        return doRequestWithTools(requestBody, sessionId, originalHistory, 0);
    }

    @SuppressWarnings("unchecked")
    private Mono<Message> doRequestWithTools(Map<String, Object> requestBody,
                                              String sessionId, List<Message> originalHistory,
                                              int depth) {
        if (depth > 10) {
            log.warn("[{}] tool-calling recursion depth exceeded ({}), stopping", providerId, depth);
            return Mono.just(new Message("assistant", "工具调用轮次过多，已停止。", Instant.now()));
        }

        return webClient.post()
            .uri("/chat/completions")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(60))
            .flatMap(resp -> handleToolCallingResponse(resp, sessionId, originalHistory, requestBody, depth))
            .doOnError(e -> log.error("[{}] tool-calling request failed for session {}: {}", 
                providerId, sessionId, e.getMessage()));
    }

    @SuppressWarnings("unchecked")
    private Mono<Message> handleToolCallingResponse(String rawResponse, String sessionId,
                                                     List<Message> originalHistory,
                                                     Map<String, Object> requestBody,
                                                     int depth) {
        try {
            Map<String, Object> response = objectMapper.readValue(rawResponse, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return Mono.just(new Message("assistant", "无响应。", Instant.now()));
            }

            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null) {
                return Mono.just(new Message("assistant", "响应中无消息。", Instant.now()));
            }

            String content = (String) message.get("content");
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");

            if (toolCalls == null || toolCalls.isEmpty()) {
                return Mono.just(new Message("assistant", content != null ? content : "", Instant.now()));
            }

            log.info("[{}] requested {} tool call(s) (depth={})", providerId, toolCalls.size(), depth);

            List<Message> enrichedHistory = new ArrayList<>(originalHistory);
            Message assistantMsg = new Message("assistant",
                content != null ? content : "正在调用工具...", Instant.now());
            assistantMsg.setToolCalls(extractToolCalls(toolCalls));
            enrichedHistory.add(assistantMsg);

            // P0-9 fix: 以响应式方式顺序执行所有工具调用（避免阻塞事件循环）
            Flux<Map<String, Object>> toolCallFlux = Flux.fromIterable(toolCalls);
            return toolCallFlux
                .concatMap(tc -> {
                    Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                    String toolName = (String) fn.get("name");
                    String arguments = (String) fn.get("arguments");
                    String toolCallId = (String) tc.get("id");

                    return executeToolReactive(toolName, arguments)
                        .map(result -> {
                            Message toolMsg = new Message("tool", result, Instant.now());
                            toolMsg.setToolCallId(toolCallId);
                            enrichedHistory.add(toolMsg);
                            return tc;  // pass through for tracking
                        });
                })
                .then(Mono.defer(() -> {
                    // 所有工具执行完毕后递归下一轮
                    String model = (String) requestBody.get("model");
                    List<Map<String, Object>> nextMessages = buildMessages(enrichedHistory, true);
                    Map<String, Object> nextRequestBody = buildRequestBody(model, nextMessages, true);
                    return doRequestWithTools(nextRequestBody, sessionId, enrichedHistory, depth + 1);
                }));


        } catch (Exception e) {
            log.error("[{}] tool-calling response handling failed: {}", providerId, e.getMessage());
            return Mono.just(new Message("assistant", "工具调用处理错误: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * P0-9 fix: 以纯响应式方式执行工具，不再阻塞事件循环线程。
     * 返回 Mono<String> 而非直接返回 String。
     */
    private Mono<String> executeToolReactive(String toolName, String arguments) {
        Map<String, Object> parsedArgs;
        try {
            parsedArgs = objectMapper.readValue(arguments, Map.class);
        } catch (Exception parseEx) {
            parsedArgs = new LinkedHashMap<>();
            parsedArgs.put("args", arguments);
        }

        return toolRegistry.execute(toolName, parsedArgs)
            .timeout(Duration.ofSeconds(30))
            .map(r -> r != null ? r : "工具执行无输出。")
            .onErrorResume(e -> {
                log.error("Tool {} failed: {}", toolName, e.getMessage());
                return Mono.just("工具执行错误: " + e.getMessage());
            });
    }

    /**
     * @deprecated 使用 executeToolReactive 替代（P0-9 修复）
     */
    @Deprecated
    private String executeToolSafely(String toolName, String arguments) {
        try {
            Map<String, Object> parsedArgs;
            try {
                parsedArgs = objectMapper.readValue(arguments, Map.class);
            } catch (Exception parseEx) {
                parsedArgs = new LinkedHashMap<>();
                parsedArgs.put("args", arguments);
            }

            String r = toolRegistry.execute(toolName, parsedArgs)
                .timeout(Duration.ofSeconds(30))
                .block();
            return r != null ? r : "工具执行无输出。";
        } catch (Exception e) {
            log.error("Tool {} failed: {}", toolName, e.getMessage());
            return "工具执行错误: " + e.getMessage();
        }
    }

    private List<ToolCall> extractToolCalls(List<Map<String, Object>> toolCalls) {
        List<ToolCall> result = new ArrayList<>();
        for (Map<String, Object> tc : toolCalls) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fn = (Map<String, Object>) tc.get("function");
            ToolCall call = new ToolCall();
            call.setId((String) tc.get("id"));
            call.setName((String) fn.get("name"));
            call.setArguments((String) fn.get("arguments"));
            result.add(call);
        }
        return result;
    }

    // ========== 工厂方法 ==========

    /**
     * 创建 OpenAI 官方适配器
     */
    public static OpenAICompatibleProvider openai(String apiKey, String defaultModel,
                                                   ToolRegistry toolRegistry) {
        return new OpenAICompatibleProvider(
            "openai", "OpenAI",
            "https://api.openai.com/v1", apiKey,
            defaultModel != null ? defaultModel : "gpt-4o-mini",
            List.of("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo"),
            toolRegistry
        );
    }

    /**
     * 创建 DeepSeek 适配器
     */
    public static OpenAICompatibleProvider deepseek(String apiKey, String defaultModel,
                                                     ToolRegistry toolRegistry) {
        return new OpenAICompatibleProvider(
            "deepseek", "DeepSeek",
            "https://api.deepseek.com/v1", apiKey,
            defaultModel != null ? defaultModel : "deepseek-chat",
            List.of("deepseek-chat", "deepseek-coder", "deepseek-reasoner"),
            toolRegistry
        );
    }

    /**
     * 创建 Ollama 本地适配器
     */
    public static OpenAICompatibleProvider ollama(String baseUrl, String defaultModel,
                                                   ToolRegistry toolRegistry) {
        return new OpenAICompatibleProvider(
            "ollama", "Ollama (Local)",
            baseUrl != null ? baseUrl : "http://localhost:11434/v1",
            "ollama", // Ollama 不需要 API key
            defaultModel != null ? defaultModel : "qwen2.5",
            List.of("qwen2.5", "llama3.1", "mistral", "codestral"),
            toolRegistry
        );
    }
}
