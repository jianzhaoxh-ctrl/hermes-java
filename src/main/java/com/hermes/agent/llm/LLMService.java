package com.hermes.agent.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.agent.config.AgentConfig;
import com.hermes.agent.model.Message;
import com.hermes.agent.model.ToolCall;
import com.hermes.agent.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Component
public class LLMService {
    private static final Logger log = LoggerFactory.getLogger(LLMService.class);

    private final AgentConfig config;
    private final ToolRegistry toolRegistry;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    /** Dedicated scheduler for blocking tool execution */
    private final Scheduler blockingScheduler = Schedulers.boundedElastic();
    /** ExecutorService for running blocking tool calls off the Netty event-loop */
    private final ExecutorService toolExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()), r -> {
                Thread t = new Thread(r, "hermes-tool");
                t.setDaemon(true);
                return t;
            });

    public LLMService(AgentConfig config, ToolRegistry toolRegistry) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.objectMapper = new ObjectMapper();
        // Use DashScope native API for better model compatibility (especially dated models like qwen-plus-2025-04-28)
        this.webClient = WebClient.builder()
                .baseUrl("https://dashscope.aliyuncs.com/api/v1")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Text-only chat (no tools)
     * Uses DashScope native API format for better model compatibility
     */
    public Mono<Message> chat(List<Message> history, String sessionId) {
        List<Map<String, Object>> messages = history.stream()
                .map(m -> {
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("role", m.getRole());
                    msg.put("content", m.getContent());
                    return msg;
                }).collect(Collectors.toList());

        // DashScope native API format
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", config.getDefaultModel());

        // Input section
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", messages);
        requestBody.put("input", input);

        // Parameters section
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("max_tokens", config.getMaxTokens());
        parameters.put("temperature", config.getTemperature());
        parameters.put("top_p", 1.0);  // Required for dated models
        requestBody.put("parameters", parameters);

        return doRequest(requestBody, sessionId);
    }

    /**
     * Chat with tools (Function Calling) - 自动判断是否需要调用工具
     * 返回的是最终文本消息（工具已执行，结果已注入上下文）
     * Uses DashScope native API format
     */
    public Mono<Message> chatWithTools(List<Message> history, String sessionId) {
        List<Map<String, Object>> messages = buildMessages(history);

        // DashScope native API format
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", config.getDefaultModel());

        // Input section
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", messages);
        requestBody.put("input", input);

        // Parameters section
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("max_tokens", config.getMaxTokens());
        parameters.put("temperature", config.getTemperature());
        parameters.put("top_p", 1.0);  // Required for dated models

        // Tools (DashScope native format uses "tools" in parameters)
        parameters.put("tools", buildToolsSpec());
        parameters.put("tool_choice", "auto");

        requestBody.put("parameters", parameters);

        return doRequestWithTools(requestBody, sessionId, history);
    }

    /**
     * Single-shot chat with tools - returns raw LLM response WITHOUT executing tools.
     * Used by AgentLoop for multi-turn tool calling.
     *
     * The returned Message may contain tool_calls, which the caller (AgentLoop)
     * should execute and then call this method again with updated history.
     *
     * This is the key difference from chatWithTools() which recursively
     * handles tool execution in a single call.
     *
     * @param history Message history
     * @param sessionId Session identifier
     * @return Mono of raw Message (may contain tool_calls)
     */
    public Mono<Message> chatSingle(List<Message> history, String sessionId) {
        List<Map<String, Object>> messages = buildMessages(history);

        // DashScope native API format
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", config.getDefaultModel());

        // Input section
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", messages);
        requestBody.put("input", input);

        // Parameters section
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("max_tokens", config.getMaxTokens());
        parameters.put("temperature", config.getTemperature());
        parameters.put("top_p", 1.0);
        parameters.put("tools", buildToolsSpec());
        parameters.put("tool_choice", "auto");

        requestBody.put("parameters", parameters);

        return doRequestSingle(requestBody, sessionId);
    }

    /**
     * Build OpenAI-format tools spec from ToolRegistry
     */
    private List<Map<String, Object>> buildToolsSpec() {
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

    /**
     * Build messages list, converting history Messages to map format
     * Also preserves assistant tool_calls if present in history
     */
    private List<Map<String, Object>> buildMessages(List<Message> history) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message m : history) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", m.getRole());
            msg.put("content", m.getContent());

            // Forward tool call info if this is an assistant message with tool calls
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

            // Forward tool role messages
            if (m.getRole().equals("tool")) {
                msg.put("tool_call_id", m.getToolCallId());
            }

            messages.add(msg);
        }
        return messages;
    }

    /**
     * Core HTTP request for chat with tools (DashScope native API)
     */
    private Mono<Message> doRequestWithTools(Map<String, Object> requestBody,
                                              String sessionId,
                                              List<Message> originalHistory) {
        log.debug("LLM request body: {}", requestBody);

        return webClient.post()
                .uri("/services/aigc/text-generation/generation")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .flatMap(resp -> handleLLMResponse(resp, sessionId, requestBody, originalHistory))
                .doOnError(e -> log.error("LLM request failed for session {}: {}", sessionId, e.getMessage()))
                .onErrorReturn(new Message("assistant",
                        "LLM request failed. Check API key and network connection.", Instant.now()));
    }

    /**
     * Parse LLM response: handle text + tool_calls (DashScope native API format)
     */
    @SuppressWarnings("unchecked")
    private Mono<Message> handleLLMResponse(String rawResponse,
                                             String sessionId,
                                             Map<String, Object> currentRequestBody,
                                             List<Message> originalHistory) {
        try {
            Map<String, Object> response = objectMapper.readValue(rawResponse, Map.class);

            // DashScope native API response format: output.choices[0].message
            Map<String, Object> output = (Map<String, Object>) response.get("output");
            if (output == null) {
                return Mono.just(new Message("assistant", "No output from model.", Instant.now()));
            }

            // Try new format first (output.choices)
            List<Map<String, Object>> choices = (List<Map<String, Object>>) output.get("choices");
            if (choices == null || choices.isEmpty()) {
                // Fallback to simple format (output.text)
                String text = (String) output.get("text");
                return Mono.just(new Message("assistant",
                        text != null ? text : "No response from model.", Instant.now()));
            }

            Map<String, Object> choice = choices.get(0);
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            if (message == null) {
                return Mono.just(new Message("assistant", "No message in response.", Instant.now()));
            }

            String content = (String) message.get("content");
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");

            // If no tool calls, return text response
            if (toolCalls == null || toolCalls.isEmpty()) {
                return Mono.just(new Message("assistant",
                        content != null ? content : "", Instant.now()));
            }

            // --- Tool Calling Flow ---
            log.info("LLM requested {} tool call(s)", toolCalls.size());

            // Build enriched history synchronously on this thread (Netty) by
            // using Mono.defer+subscribeOn so the blocking tool calls stay off
            // the Netty event-loop. block() runs on boundedElastic, not Netty.
            List<Message> enrichedHistory = new ArrayList<>(originalHistory);
            Message assistantMsg = new Message("assistant",
                    content != null ? content : "Calling tool(s)...", Instant.now());
            assistantMsg.setToolCalls(extractToolCalls(toolCalls));
            enrichedHistory.add(assistantMsg);

            for (Map<String, Object> tc : toolCalls) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                String toolName = (String) fn.get("name");
                String arguments = (String) fn.get("arguments");
                String toolCallId = (String) tc.get("id");

                log.info("Executing tool: {} with args: {}", toolName, arguments);

                String result;
                try {
                    // Use CompletableFuture on a dedicated thread pool so the Netty
                    // event-loop thread is never blocked.
                    final String capturedToolName = toolName;
                    final String capturedArguments = arguments;
                    java.util.concurrent.atomic.AtomicReference<String> toolResult =
                            new java.util.concurrent.atomic.AtomicReference<>(null);
                    java.util.concurrent.atomic.AtomicReference<Throwable> toolError =
                            new java.util.concurrent.atomic.AtomicReference<>(null);

                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            Map<String, Object> parsedArgs;
                            try {
                                parsedArgs = objectMapper.readValue(capturedArguments,
                                        new TypeReference<Map<String, Object>>() {});
                            } catch (Exception parseEx) {
                                parsedArgs = new LinkedHashMap<>();
                                parsedArgs.put("args", capturedArguments);
                            }
                            String r = toolRegistry.execute(capturedToolName, parsedArgs)
                                    .timeout(Duration.ofSeconds(30))
                                    .block();
                            toolResult.set(r);
                        } catch (Throwable t) {
                            toolError.set(t);
                        }
                    }, toolExecutor);

                    future.get(35, TimeUnit.SECONDS);
                    if (toolError.get() != null) {
                        result = "Tool execution error: " + toolError.get().getMessage();
                    } else {
                        result = toolResult.get();
                    }
                    if (result == null) result = "Tool executed with no output.";
                } catch (TimeoutException e) {
                    result = "Tool execution timed out (35s)";
                    log.error("Tool {} timed out", toolName);
                } catch (Exception e) {
                    result = "Tool execution error: " + e.getMessage();
                    log.error("Tool {} failed: {}", toolName, e.getMessage());
                }

                Message toolMsg = new Message("tool", result, Instant.now());
                toolMsg.setToolCallId(toolCallId);
                enrichedHistory.add(toolMsg);
            }

            // Recursively call LLM with tool results
            List<Map<String, Object>> nextMessages = buildMessages(enrichedHistory);

            // Build next request in DashScope native format
            Map<String, Object> nextRequestBody = new LinkedHashMap<>();
            nextRequestBody.put("model", config.getDefaultModel());

            Map<String, Object> nextInput = new LinkedHashMap<>();
            nextInput.put("messages", nextMessages);
            nextRequestBody.put("input", nextInput);

            Map<String, Object> nextParams = new LinkedHashMap<>();
            nextParams.put("max_tokens", config.getMaxTokens());
            nextParams.put("temperature", config.getTemperature());
            nextParams.put("top_p", 1.0);
            nextParams.put("tools", buildToolsSpec());

            nextRequestBody.put("parameters", nextParams);

            return doRequestWithTools(nextRequestBody, sessionId, enrichedHistory);

        } catch (Exception e) {
            log.error("Failed to parse LLM response: {}", e.getMessage());
            return Mono.just(new Message("assistant",
                    "Error parsing response: " + e.getMessage(), Instant.now()));
        }
    }

    private List<ToolCall> extractToolCalls(List<Map<String, Object>> toolCalls) {
        List<ToolCall> result = new ArrayList<>();
        for (Map<String, Object> tc : toolCalls) {
            Map<String, Object> fn = (Map<String, Object>) tc.get("function");
            ToolCall call = new ToolCall();
            call.setId((String) tc.get("id"));
            call.setName((String) fn.get("name"));
            call.setArguments((String) fn.get("arguments"));
            result.add(call);
        }
        return result;
    }

    /**
     * Core HTTP POST + parse text-only response (DashScope native API)
     */
    private Mono<Message> doRequest(Map<String, Object> requestBody, String sessionId) {
        return webClient.post()
                .uri("/services/aigc/text-generation/generation")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .map(resp -> {
                    try {
                        Map<String, Object> response = objectMapper.readValue(resp, Map.class);
                        // DashScope native API response format
                        Map<String, Object> output = (Map<String, Object>) response.get("output");
                        if (output == null) {
                            return new Message("assistant", "No output from model.", Instant.now());
                        }
                        String content = (String) output.get("text");
                        return new Message("assistant", content != null ? content : "", Instant.now());
                    } catch (Exception e) {
                        log.error("Failed to parse LLM response: {}", e.getMessage());
                        return new Message("assistant", "Error parsing response: " + e.getMessage(), Instant.now());
                    }
                })
                .doOnError(e -> log.error("LLM request failed for session {}: {}", sessionId, e.getMessage()))
                .onErrorReturn(new Message("assistant",
                        "LLM request failed. Check API key and network connection.", Instant.now()));
    }

    /**
     * Single-shot request - returns raw response WITHOUT tool execution.
     * Used by AgentLoop for multi-turn tool calling.
     */
    @SuppressWarnings("unchecked")
    private Mono<Message> doRequestSingle(Map<String, Object> requestBody, String sessionId) {
        log.debug("Single-shot LLM request for session: {}", sessionId);

        return webClient.post()
                .uri("/services/aigc/text-generation/generation")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .map(resp -> {
                    try {
                        Map<String, Object> response = objectMapper.readValue(resp, Map.class);

                        // DashScope native API response format
                        Map<String, Object> output = (Map<String, Object>) response.get("output");
                        if (output == null) {
                            return new Message("assistant", "No output from model.", Instant.now());
                        }

                        // Try new format first (output.choices)
                        List<Map<String, Object>> choices = (List<Map<String, Object>>) output.get("choices");
                        if (choices == null || choices.isEmpty()) {
                            // Fallback to simple format (output.text)
                            String text = (String) output.get("text");
                            return new Message("assistant", text != null ? text : "No response.", Instant.now());
                        }

                        Map<String, Object> choice = choices.get(0);
                        Map<String, Object> message = (Map<String, Object>) choice.get("message");
                        if (message == null) {
                            return new Message("assistant", "No message in response.", Instant.now());
                        }

                        String content = (String) message.get("content");
                        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");

                        Message result = new Message("assistant", content != null ? content : "", Instant.now());

                        // If there are tool calls, set them but DON'T execute
                        if (toolCalls != null && !toolCalls.isEmpty()) {
                            log.info("LLM returned {} tool call(s) - not executing (single-shot mode)", toolCalls.size());
                            result.setToolCalls(extractToolCalls(toolCalls));
                        }

                        return result;

                    } catch (Exception e) {
                        log.error("Failed to parse single-shot LLM response: {}", e.getMessage());
                        return new Message("assistant", "Error parsing response: " + e.getMessage(), Instant.now());
                    }
                })
                .doOnError(e -> log.error("Single-shot LLM request failed for session {}: {}", sessionId, e.getMessage()))
                .onErrorReturn(new Message("assistant",
                        "LLM request failed. Check API key and network connection.", Instant.now()));
    }

    /*public Flux<String> chatStream(List<Message> history, String sessionId) {
        List<Map<String, Object>> messages = history.stream()
                .map(m -> {
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("role", m.getRole());
                    msg.put("content", m.getContent());
                    return msg;
                }).collect(Collectors.toList());

        // DashScope native API format for streaming
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", config.getDefaultModel());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", messages);
        requestBody.put("input", input);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("max_tokens", config.getMaxTokens());
        parameters.put("temperature", config.getTemperature());
        parameters.put("top_p", 1.0);
        parameters.put("stream", true);
        requestBody.put("parameters", parameters);

        return webClient.post()
                .uri("/services/aigc/text-generation/generation")
                .bodyValue(requestBody)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .retrieve()
                // Use bodyToFlux for true streaming instead of bodyToMono which buffers everything
                .bodyToFlux(DataBuffer.class)
                .timeout(Duration.ofSeconds(120))
                .flatMap(buffer -> {
                    // Decode each chunk to string
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return Flux.just(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                })
                // Line buffering: accumulate chars until newline, then emit complete lines
                .transformDeferred(flux -> {
                    StringBuilder lineBuffer = new StringBuilder();
                    return Flux.<String>create(sink -> {
                        flux.subscribe(
                            chunk -> {
                                for (char c : chunk.toString().toCharArray()) {
                                    if (c == '\n') {
                                        String line = lineBuffer.toString();
                                        lineBuffer.setLength(0);
                                        if (!line.isEmpty()) {
                                            emitSseLine(sink, line);
                                        }
                                    } else if (c != '\r') {
                                        lineBuffer.append(c);
                                    }
                                }
                            },
                            sink::error,
                            () -> {
                                // Emit any remaining content in buffer (last line without trailing newline)
                                if (lineBuffer.length() > 0) {
                                    emitSseLine(sink, lineBuffer.toString());
                                }
                                sink.complete();
                            }
                        );
                    });
                })
                .doOnError(e -> log.error("Stream failed for session {}: {}", sessionId, e.getMessage()));
    }*/

    public Flux<String> chatStream(List<Message> history, String sessionId) {
        List<Map<String, Object>> messages = history.stream()
                .map(m -> {
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("role", m.getRole());
                    msg.put("content", m.getContent());
                    return msg;
                }).collect(Collectors.toList());

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", "qwen-plus");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", messages);
        requestBody.put("input", input);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("max_tokens", 1000);
        parameters.put("temperature", 0.7);
        parameters.put("top_p", 1.0);
        parameters.put("stream", true);
        requestBody.put("parameters", parameters);

        return webClient.post()
                .uri("/services/aigc/text-generation/generation")
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .map(buffer -> {
                    String raw = buffer.toString(StandardCharsets.UTF_8);
                    DataBufferUtils.release(buffer);
                    return raw;
                })
                // 提取 data: 后的 JSON
                .filter(s -> s.contains("data:"))
                .map(s -> {
                    int idx = s.indexOf("data:");
                    return s.substring(idx + 5).trim();
                })
                // 解析 JSON 拿到 text
                .map(json -> {
                    try {
                        ObjectMapper mapper = new ObjectMapper();
                        JsonNode node = mapper.readTree(json);
                        return node.path("output").path("text").asText();
                    } catch (Exception e) {
                        return "";
                    }
                })
                .filter(StringUtils::hasText)
                .doOnNext(text -> log.info("[会话{}] 输出文本：{}", sessionId, text))
                .doOnComplete(() -> log.info("[会话{}] 流式完成", sessionId));
    }

    /**
     * Parse a single SSE "data: <json>" line and emit the text token
     */
    @SuppressWarnings("unchecked")
    private void emitSseLine(reactor.core.publisher.FluxSink<String> sink, String line) {
        if (!line.startsWith("data: ") || line.equals("data: [DONE]") || line.equals("data:")) {
            return;
        }
        String json = line.substring(6).trim();
        if (json.isEmpty()) return;

        try {
            Map<String, Object> obj = objectMapper.readValue(json, Map.class);
            Map<String, Object> output = (Map<String, Object>) obj.get("output");
            if (output != null) {
                Object text = output.get("text");
                if (text != null && !text.toString().isEmpty()) {
                    sink.next(text.toString());
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse SSE line: {}", e.getMessage());
        }
    }

    public void setModel(String model, String provider) {
        log.info("Switching model to: {} via provider: {}", model, provider);
    }

    public List<String> getAvailableModels() {
        return Arrays.asList(
                "qwen-plus",
                "qwen-max-latest",
                "qwen-turbo",
                "anthropic/claude-3.5-sonnet",
                "openai/gpt-4o-mini"
        );
    }
}
