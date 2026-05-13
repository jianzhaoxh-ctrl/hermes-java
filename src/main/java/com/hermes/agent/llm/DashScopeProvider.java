package com.hermes.agent.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.agent.config.AgentConfig;
import com.hermes.agent.model.Message;
import com.hermes.agent.model.ToolCall;
import com.hermes.agent.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * DashScope (阿里云通义千问) LLM 适配器
 * 
 * 使用 DashScope 原生 API 格式。
 * 支持：qwen-plus, qwen-max, qwen-turbo 等模型。
 * 
 * API 文档：https://help.aliyun.com/document_detail/2712195.html
 */
public class DashScopeProvider implements LLMProvider, LLMChat {

    private static final Logger log = LoggerFactory.getLogger(DashScopeProvider.class);
    private static final String API_BASE = "https://dashscope.aliyuncs.com/api/v1";

    private final AgentConfig config;
    private final ToolRegistry toolRegistry;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService toolExecutor = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors()), r -> {
            Thread t = new Thread(r, "dashscope-tool");
            t.setDaemon(true);
            return t;
        });

    public DashScopeProvider(AgentConfig config, ToolRegistry toolRegistry) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.webClient = WebClient.builder()
            .baseUrl(API_BASE)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

    // ========== LLMProvider ==========

    @Override
    public String getProviderId() {
        return "dashscope";
    }

    @Override
    public String getDisplayName() {
        return "阿里云 DashScope (通义千问)";
    }

    @Override
    public boolean isAvailable() {
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    @Override
    public String getDefaultModel() {
        return config.getDefaultModel() != null ? config.getDefaultModel() : "qwen-plus";
    }

    @Override
    public List<String> getAvailableModels() {
        return List.of("qwen-plus", "qwen-max-latest", "qwen-turbo", 
                       "qwen-long", "qwen-vl-plus", "qwen-vl-max");
    }

    @Override
    public int getMaxContextLength() {
        return 131072; // qwen-plus 支持 128K
    }

    // ========== LLMChat ==========

    @Override
    public Mono<Message> chat(List<Message> history, String model, String sessionId) {
        String useModel = model != null ? model : getDefaultModel();
        List<Map<String, Object>> messages = buildSimpleMessages(history);

        Map<String, Object> requestBody = buildRequestBody(useModel, messages, false);

        return webClient.post()
            .uri("/services/aigc/text-generation/generation")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(30))
            .map(this::parseTextResponse)
            .doOnError(e -> log.error("DashScope chat failed for session {}: {}", sessionId, e.getMessage()))
            .onErrorReturn(new Message("assistant", "LLM 请求失败，请检查 API Key 和网络连接。", Instant.now()));
    }

    @Override
    public Mono<Message> chatWithTools(List<Message> history, String model, String sessionId) {
        String useModel = model != null ? model : getDefaultModel();
        List<Map<String, Object>> messages = buildMessages(history);
        Map<String, Object> requestBody = buildRequestBody(useModel, messages, true);

        return doRequestWithTools(requestBody, sessionId, history);
    }

    @Override
    public Mono<Message> chatSingle(List<Message> history, String model, String sessionId) {
        String useModel = model != null ? model : getDefaultModel();
        List<Map<String, Object>> messages = buildMessages(history);
        Map<String, Object> requestBody = buildRequestBody(useModel, messages, true);

        return webClient.post()
            .uri("/services/aigc/text-generation/generation")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(30))
            .map(this::parseSingleResponse)
            .doOnError(e -> log.error("DashScope single-shot failed for session {}: {}", sessionId, e.getMessage()))
            .onErrorReturn(new Message("assistant", "LLM 请求失败。", Instant.now()));
    }

    @Override
    public Flux<String> chatStream(List<Message> history, String model, String sessionId) {
        String useModel = model != null ? model : getDefaultModel();
        List<Map<String, Object>> messages = buildSimpleMessages(history);

        Map<String, Object> requestBody = buildRequestBody(useModel, messages, false);
        // 启用流式
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) ((Map<String, Object>) requestBody.get("parameters"));
        params.put("stream", true);

        return webClient.post()
            .uri("/services/aigc/text-generation/generation")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(60))
            .flatMapMany(resp -> {
                String[] lines = resp.split("\\n");
                return Flux.fromArray(lines);
            })
            .filter(line -> line.startsWith("data: ") && !line.equals("data: [DONE]"))
            .map(line -> line.substring(6))
            .flatMap(line -> {
                try {
                    if (line.startsWith("{")) {
                        Map<String, Object> obj = objectMapper.readValue(line, Map.class);
                        Map<String, Object> output = (Map<String, Object>) obj.get("output");
                        if (output != null) {
                            Object text = output.get("text");
                            return Flux.just(text != null ? text.toString() : "");
                        }
                    }
                } catch (Exception ignored) {}
                return Flux.empty();
            });
    }

    // ========== 请求构建 ==========

    private Map<String, Object> buildRequestBody(String model, List<Map<String, Object>> messages, boolean withTools) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", messages);
        requestBody.put("input", input);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("max_tokens", config.getMaxTokens());
        parameters.put("temperature", config.getTemperature());
        parameters.put("top_p", 1.0);

        if (withTools && toolRegistry != null) {
            List<Map<String, Object>> toolsSpec = buildToolsSpec();
            if (!toolsSpec.isEmpty()) {
                parameters.put("tools", toolsSpec);
                parameters.put("tool_choice", "auto");
            }
        }

        requestBody.put("parameters", parameters);
        return requestBody;
    }

    private List<Map<String, Object>> buildSimpleMessages(List<Message> history) {
        return history.stream().map(m -> {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", m.getRole());
            msg.put("content", m.getContent());
            return msg;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildMessages(List<Message> history) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message m : history) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", m.getRole());
            msg.put("content", m.getContent());

            if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                List<Map<String, Object>> tcList = new ArrayList<>();
                for (ToolCall tc : m.getToolCalls()) {
                    Map<String, Object> tcMap = new LinkedHashMap<>();
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", tc.getName());
                    fn.put("arguments", tc.getArguments());
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
    private Message parseTextResponse(String rawResponse) {
        try {
            Map<String, Object> response = objectMapper.readValue(rawResponse, Map.class);
            Map<String, Object> output = (Map<String, Object>) response.get("output");
            if (output == null) {
                return new Message("assistant", "模型无输出。", Instant.now());
            }
            String content = (String) output.get("text");
            return new Message("assistant", content != null ? content : "", Instant.now());
        } catch (Exception e) {
            log.error("Failed to parse DashScope response: {}", e.getMessage());
            return new Message("assistant", "响应解析错误: " + e.getMessage(), Instant.now());
        }
    }

    @SuppressWarnings("unchecked")
    private Message parseSingleResponse(String rawResponse) {
        try {
            Map<String, Object> response = objectMapper.readValue(rawResponse, Map.class);
            Map<String, Object> output = (Map<String, Object>) response.get("output");
            if (output == null) {
                return new Message("assistant", "模型无输出。", Instant.now());
            }

            List<Map<String, Object>> choices = (List<Map<String, Object>>) output.get("choices");
            if (choices == null || choices.isEmpty()) {
                String text = (String) output.get("text");
                return new Message("assistant", text != null ? text : "无响应。", Instant.now());
            }

            Map<String, Object> choice = choices.get(0);
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            if (message == null) {
                return new Message("assistant", "响应中无消息。", Instant.now());
            }

            String content = (String) message.get("content");
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");

            Message result = new Message("assistant", content != null ? content : "", Instant.now());
            if (toolCalls != null && !toolCalls.isEmpty()) {
                log.info("DashScope returned {} tool call(s) - single-shot mode", toolCalls.size());
                result.setToolCalls(extractToolCalls(toolCalls));
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to parse DashScope single-shot response: {}", e.getMessage());
            return new Message("assistant", "响应解析错误: " + e.getMessage(), Instant.now());
        }
    }

    // ========== 工具执行 ==========

    @SuppressWarnings("unchecked")
    private Mono<Message> doRequestWithTools(Map<String, Object> requestBody,
                                              String sessionId, List<Message> originalHistory) {
        return webClient.post()
            .uri("/services/aigc/text-generation/generation")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(30))
            .flatMap(resp -> handleToolCallingResponse(resp, sessionId, originalHistory, requestBody))
            .doOnError(e -> log.error("DashScope tool-calling request failed for session {}: {}", sessionId, e.getMessage()))
            .onErrorReturn(new Message("assistant", "LLM 请求失败。请检查 API Key 和网络连接。", Instant.now()));
    }

    @SuppressWarnings("unchecked")
    private Mono<Message> handleToolCallingResponse(String rawResponse, String sessionId,
                                                     List<Message> originalHistory,
                                                     Map<String, Object> requestBody) {
        try {
            Map<String, Object> response = objectMapper.readValue(rawResponse, Map.class);
            Map<String, Object> output = (Map<String, Object>) response.get("output");
            if (output == null) {
                return Mono.just(new Message("assistant", "模型无输出。", Instant.now()));
            }

            List<Map<String, Object>> choices = (List<Map<String, Object>>) output.get("choices");
            if (choices == null || choices.isEmpty()) {
                String text = (String) output.get("text");
                return Mono.just(new Message("assistant", text != null ? text : "无响应。", Instant.now()));
            }

            Map<String, Object> choice = choices.get(0);
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            if (message == null) {
                return Mono.just(new Message("assistant", "响应中无消息。", Instant.now()));
            }

            String content = (String) message.get("content");
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");

            if (toolCalls == null || toolCalls.isEmpty()) {
                return Mono.just(new Message("assistant", content != null ? content : "", Instant.now()));
            }

            log.info("DashScope requested {} tool call(s)", toolCalls.size());

            List<Message> enrichedHistory = new ArrayList<>(originalHistory);
            Message assistantMsg = new Message("assistant",
                content != null ? content : "正在调用工具...", Instant.now());
            assistantMsg.setToolCalls(extractToolCalls(toolCalls));
            enrichedHistory.add(assistantMsg);

            for (Map<String, Object> tc : toolCalls) {
                Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                String toolName = (String) fn.get("name");
                String arguments = (String) fn.get("arguments");
                String toolCallId = (String) tc.get("id");

                String result = executeToolSafely(toolName, arguments);

                Message toolMsg = new Message("tool", result, Instant.now());
                toolMsg.setToolCallId(toolCallId);
                enrichedHistory.add(toolMsg);
            }

            // 递归调用
            String model = (String) requestBody.get("model");
            List<Map<String, Object>> nextMessages = buildMessages(enrichedHistory);
            Map<String, Object> nextRequestBody = buildRequestBody(model, nextMessages, true);

            return doRequestWithTools(nextRequestBody, sessionId, enrichedHistory);

        } catch (Exception e) {
            log.error("Failed to handle DashScope tool-calling response: {}", e.getMessage());
            return Mono.just(new Message("assistant", "工具调用处理错误: " + e.getMessage(), Instant.now()));
        }
    }

    private String executeToolSafely(String toolName, String arguments) {
        try {
            final Map<String, Object> parsedArgs = parseArguments(arguments);

            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    String r = toolRegistry.execute(toolName, parsedArgs)
                        .timeout(Duration.ofSeconds(30))
                        .block();
                    return r != null ? r : "工具执行无输出。";
                } catch (Exception e) {
                    return "工具执行错误: " + e.getMessage();
                }
            }, toolExecutor);

            return future.get(35, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("Tool {} timed out", toolName);
            return "工具执行超时 (35s)";
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String arguments) {
        try {
            return objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("args", arguments);
            return fallback;
        }
    }
}
