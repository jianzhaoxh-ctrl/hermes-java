package com.hermes.agent.loop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.agent.config.AgentConfig;
import com.hermes.agent.model.Message;
import com.hermes.agent.model.ToolCall;
import com.hermes.agent.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent Loop with multi-turn tool calling support.
 * 
 * Mirrors Python's run_agent.py agent loop:
 * - Iterative LLM calls with tool execution
 * - Iteration budget tracking (default max 90 iterations)
 * - Tool result accumulation and message history management
 * - Graceful handling of max_iterations with summary request
 * - Support for both blocking and streaming modes
 * 
 * Usage:
 * <pre>
 * AgentLoop loop = new AgentLoop(llmClient, toolRegistry, config);
 * AgentLoopResult result = loop.run(messages).block();
 * // or streaming:
 * loop.runStream(messages).subscribe(chunk -> { ... });
 * </pre>
 */
public class AgentLoop {
    
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    
    /** Default max iterations for the agent loop */
    public static final int DEFAULT_MAX_ITERATIONS = 90;
    
    /** Default timeout for a single tool execution */
    public static final Duration DEFAULT_TOOL_TIMEOUT = Duration.ofSeconds(35);
    
    /** Default timeout for the entire agent loop */
    public static final Duration DEFAULT_LOOP_TIMEOUT = Duration.ofMinutes(30);
    
    // ─────────────────────────── Core Components ───────────────────────────
    
    private final LLMClient llmClient;
    private final ToolRegistry toolRegistry;
    private final AgentConfig config;
    private final ObjectMapper objectMapper;
    
    // ─────────────────────────── Configuration ───────────────────────────
    
    private final int maxIterations;
    private final Duration toolTimeout;
    private final Duration loopTimeout;
    private final boolean parallelToolExecution;
    private final boolean quietMode;
    
    // ─────────────────────────── State ───────────────────────────
    
    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<String> pendingSteer = new AtomicReference<>(null);
    
    // ─────────────────────────── Callbacks ───────────────────────────
    
    private StepCallback stepCallback;
    private ToolProgressCallback toolProgressCallback;
    private StatusCallback statusCallback;
    
    // ─────────────────────────── Constructor ───────────────────────────
    
    public AgentLoop(LLMClient llmClient, ToolRegistry toolRegistry, AgentConfig config) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.config = config;
        this.objectMapper = new ObjectMapper();
        
        // Configuration with defaults
        this.maxIterations = config != null && config.getMaxIterations() > 0 
                ? config.getMaxIterations() 
                : DEFAULT_MAX_ITERATIONS;
        this.toolTimeout = config != null && config.getToolTimeoutMs() > 0
                ? Duration.ofMillis(config.getToolTimeoutMs())
                : DEFAULT_TOOL_TIMEOUT;
        this.loopTimeout = DEFAULT_LOOP_TIMEOUT;
        this.parallelToolExecution = config != null && config.isParallelToolExecution();
        this.quietMode = config != null && config.isQuietMode();
    }
    
    // ─────────────────────────── Main Loop Entry Points ───────────────────────────
    
    /**
     * Run the agent loop with tool calling until completion.
     * Returns the final response after all tool calls are executed.
     * 
     * @param messages Initial message history
     * @return Mono containing the loop result
     */
    public Mono<AgentLoopResult> run(List<Message> messages) {
        return run(messages, null);
    }
    
    /**
     * Run the agent loop with a specific session ID.
     * 
     * @param messages Initial message history
     * @param sessionId Session identifier for logging
     * @return Mono containing the loop result
     */
    public Mono<AgentLoopResult> run(List<Message> messages, String sessionId) {
        // 自动重置中断标志，确保上次中断不影响本次运行
        interrupted.set(false);
        pendingSteer.set(null);

        String sid = sessionId != null ? sessionId : UUID.randomUUID().toString().substring(0, 8);
        IterationBudget budget = new IterationBudget(maxIterations);
        List<Message> history = new ArrayList<>(messages);

        log.info("[AgentLoop:{}] Starting loop with max {} iterations, {} initial messages",
                sid, maxIterations, messages.size());
        
        return runLoopInternal(history, budget, sid, 0)
                .timeout(loopTimeout)
                .doOnSuccess(result -> {
                    log.info("[AgentLoop:{}] Completed after {} iterations, {} final messages", 
                            sid, result.getIterations(), result.getMessages().size());
                })
                .doOnError(e -> log.error("[AgentLoop:{}] Loop failed: {}", sid, e.getMessage()));
    }
    
    /**
     * Run the agent loop with streaming output.
     * Streams text chunks from the final response, tool execution updates are delivered via callbacks.
     * 
     * @param messages Initial message history
     * @return Flux of text chunks from the final response
     */
    public Flux<String> runStream(List<Message> messages) {
        return runStream(messages, null);
    }
    
    /**
     * Run the agent loop with streaming output for a specific session.
     * 
     * @param messages Initial message history
     * @param sessionId Session identifier
     * @return Flux of text chunks from the final response
     */
    public Flux<String> runStream(List<Message> messages, String sessionId) {
        String sid = sessionId != null ? sessionId : UUID.randomUUID().toString().substring(0, 8);
        
        return run(messages, sid)
                .flatMapMany(result -> {
                    if (result.getFinalResponse() != null) {
                        return Flux.just(result.getFinalResponse());
                    }
                    return Flux.empty();
                });
    }
    
    // ─────────────────────────── Core Loop Implementation ───────────────────────────
    
    /**
     * Internal recursive loop implementation.
     * 
     * This is the heart of the agent loop, mirroring Python's:
     * <pre>
     * while (api_call_count < self.max_iterations and self.iteration_budget.remaining > 0):
     *     # API call
     *     response = llm.chat(messages, tools)
     *     
     *     if response.tool_calls:
     *         execute_tools(response.tool_calls)
     *         append_tool_results_to_messages()
     *         continue  # Next iteration
     *     else:
     *         return response.content  # Final response
     * </pre>
     */
    private Mono<AgentLoopResult> runLoopInternal(
            List<Message> history,
            IterationBudget budget,
            String sessionId,
            int iteration
    ) {
        // Check for interrupt
        if (interrupted.get()) {
            log.info("[AgentLoop:{}] Interrupted by user at iteration {}", sessionId, iteration);
            return Mono.just(new AgentLoopResult(
                    null, history, iteration, false, true, "interrupted_by_user"
            ));
        }
        
        // Check budget
        if (!budget.canContinue()) {
            log.warn("[AgentLoop:{}] Iteration budget exhausted at {}", sessionId, iteration);
            return handleMaxIterations(history, budget, sessionId, iteration);
        }
        
        // Consume one iteration
        budget.consume();
        int currentIteration = iteration + 1;
        
        // Fire step callback
        if (stepCallback != null) {
            try {
                stepCallback.onStep(currentIteration, maxIterations, Collections.emptyList());
            } catch (Exception e) {
                log.debug("[AgentLoop:{}] stepCallback error: {}", sessionId, e.getMessage());
            }
        }
        
        log.debug("[AgentLoop:{}] API call #{}/{}", sessionId, currentIteration, maxIterations);
        emitStatus(sessionId, String.format("Making API call #%d/%d...", currentIteration, maxIterations));
        
        // P2-19 fix: 创建 history 的防御性副本，避免递归迭代中并发修改
        List<Message> historyCopy = new ArrayList<>(history);
        
        // ── Make LLM API call ──
        return llmClient.chat(historyCopy, sessionId)
                .flatMap(response -> handleLLMResponse(response, historyCopy, budget, sessionId, currentIteration))
                .onErrorResume(e -> {
                    log.error("[AgentLoop:{}] API call failed: {}", sessionId, e.getMessage());
                    // Return partial result on error
                    return Mono.just(new AgentLoopResult(
                            "Error: " + e.getMessage(),
                            historyCopy,
                            currentIteration,
                            false,
                            false,
                            "error: " + e.getClass().getSimpleName()
                    ));
                });
    }
    
    /**
     * Handle LLM response: check for tool calls or return final response.
     */
    private Mono<AgentLoopResult> handleLLMResponse(
            Message response,
            List<Message> history,
            IterationBudget budget,
            String sessionId,
            int iteration
    ) {
        // Check if response has tool calls
        List<ToolCall> toolCalls = response.getToolCalls();
        
        if (toolCalls != null && !toolCalls.isEmpty()) {
            log.info("[AgentLoop:{}] Processing {} tool call(s)", sessionId, toolCalls.size());
            
            // Build assistant message with tool calls
            Message assistantMsg = new Message("assistant", response.getContent(), Instant.now());
            assistantMsg.setToolCalls(toolCalls);
            history.add(assistantMsg);
            
            // Execute tools
            return executeToolCalls(toolCalls, history, sessionId, iteration)
                    .flatMap(toolResults -> {
                        // Continue loop for next iteration
                        return runLoopInternal(history, budget, sessionId, iteration);
                    });
        }
        
        // No tool calls - this is the final response
        log.debug("[AgentLoop:{}] Final response received at iteration {}", sessionId, iteration);
        
        String content = response.getContent();
        
        // Append final assistant message if not already present
        if (history.isEmpty() || !"assistant".equals(history.get(history.size() - 1).getRole())) {
            history.add(response);
        }
        
        return Mono.just(new AgentLoopResult(
                content,
                history,
                iteration,
                true,
                false,
                "final_response"
        ));
    }
    
    // ─────────────────────────── Tool Execution ───────────────────────────
    
    /**
     * Execute a batch of tool calls.
     * Supports both sequential and parallel execution.
     * 
     * Mirrors Python's _execute_tool_calls and _execute_tool_calls_concurrent.
     */
    private Mono<List<ToolResult>> executeToolCalls(
            List<ToolCall> toolCalls,
            List<Message> history,
            String sessionId,
            int iteration
    ) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Mono.just(Collections.emptyList());
        }
        
        // Determine execution mode
        boolean shouldParallelize = parallelToolExecution && shouldParallelize(toolCalls);
        
        if (shouldParallelize) {
            log.debug("[AgentLoop:{}] Executing {} tool calls in parallel", sessionId, toolCalls.size());
            return executeToolCallsParallel(toolCalls, history, sessionId);
        } else {
            log.debug("[AgentLoop:{}] Executing {} tool calls sequentially", sessionId, toolCalls.size());
            return executeToolCallsSequential(toolCalls, history, sessionId);
        }
    }
    
    /**
     * Execute tool calls sequentially.
     */
    private Mono<List<ToolResult>> executeToolCallsSequential(
            List<ToolCall> toolCalls,
            List<Message> history,
            String sessionId
    ) {
        List<ToolResult> results = new ArrayList<>();
        
        return Flux.fromIterable(toolCalls)
                .concatMap(toolCall -> {
                    emitStatus(sessionId, String.format("Executing tool: %s", toolCall.getName()));
                    
                    return executeSingleTool(toolCall, sessionId)
                            .doOnNext(results::add)
                            .map(result -> {
                                // Append tool result to history
                                Message toolMsg = new Message("tool", result.getOutput(), Instant.now());
                                toolMsg.setToolCallId(result.getToolCallId());
                                history.add(toolMsg);
                                return result;
                            });
                })
                .then(Mono.just(results));
    }
    
    /**
     * Execute tool calls in parallel.
     */
    private Mono<List<ToolResult>> executeToolCallsParallel(
            List<ToolCall> toolCalls,
            List<Message> history,
            String sessionId
    ) {
        List<ToolResult> results = Collections.synchronizedList(new ArrayList<>());
        
        return Flux.fromIterable(toolCalls)
                .flatMap(toolCall -> {
                    emitStatus(sessionId, String.format("Executing tool: %s", toolCall.getName()));
                    
                    return executeSingleTool(toolCall, sessionId)
                            .doOnNext(results::add);
                }, Math.min(toolCalls.size(), 8))  // Max concurrency
                .collectList()
                .doOnNext(allResults -> {
                    // Append all tool results to history in order
                    for (ToolResult result : allResults) {
                        Message toolMsg = new Message("tool", result.getOutput(), Instant.now());
                        toolMsg.setToolCallId(result.getToolCallId());
                        history.add(toolMsg);
                    }
                });
    }
    
    /**
     * Execute a single tool call.
     */
    private Mono<ToolResult> executeSingleTool(ToolCall toolCall, String sessionId) {
        String toolName = toolCall.getName();
        String toolCallId = toolCall.getId();
        String arguments = toolCall.getArguments();
        
        log.info("[AgentLoop:{}] Executing tool: {} with args: {}", 
                sessionId, toolName, truncate(arguments, 200));
        
        // Parse arguments
        Map<String, Object> parsedArgs;
        try {
            if (arguments == null || arguments.trim().isEmpty()) {
                parsedArgs = Collections.emptyMap();
            } else {
                parsedArgs = objectMapper.readValue(arguments, 
                        new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.warn("[AgentLoop:{}] Failed to parse tool arguments: {}", sessionId, e.getMessage());
            parsedArgs = Collections.singletonMap("args", arguments);
        }
        
        // Execute tool
        long startTime = System.currentTimeMillis();
        
        return toolRegistry.execute(toolName, parsedArgs)
                .timeout(toolTimeout)
                .map(output -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("[AgentLoop:{}] Tool {} completed in {}ms", sessionId, toolName, elapsed);
                    return new ToolResult(toolCallId, toolName, output, true);
                })
                .onErrorResume(e -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.error("[AgentLoop:{}] Tool {} failed after {}ms: {}", 
                            sessionId, toolName, elapsed, e.getMessage());
                    return Mono.just(new ToolResult(
                            toolCallId, 
                            toolName, 
                            "Tool execution error: " + e.getMessage(), 
                            false
                    ));
                });
    }
    
    /**
     * Determine if a batch of tool calls can be executed in parallel.
     * Mirrors Python's _should_parallelize_tool_batch.
     */
    private boolean shouldParallelize(List<ToolCall> toolCalls) {
        if (toolCalls.size() <= 1) {
            return false;
        }
        
        // Tools that must never run concurrently (interactive / user-facing)
        Set<String> neverParallel = Set.of("clarify", "ask_user", "prompt");
        
        // Read-only tools with no shared mutable session state
        Set<String> parallelSafe = Set.of(
                "read_file", "search_files", "session_search",
                "web_search", "web_extract", "vision_analyze",
                "ha_get_state", "ha_list_entities"
        );
        
        for (ToolCall tc : toolCalls) {
            String name = tc.getName();
            
            // Check for never-parallel tools
            if (neverParallel.contains(name)) {
                return false;
            }
            
            // Check if all tools are parallel-safe
            if (!parallelSafe.contains(name)) {
                // Check if it's a path-scoped tool with non-overlapping paths
                // For simplicity, default to sequential for non-safe tools
                return false;
            }
        }
        
        return true;
    }
    
    // ─────────────────────────── Max Iterations Handling ───────────────────────────
    
    /**
     * Handle reaching max iterations.
     * Requests a summary from the LLM instead of continuing tool calls.
     * 
     * Mirrors Python's _handle_max_iterations.
     */
    private Mono<AgentLoopResult> handleMaxIterations(
            List<Message> history,
            IterationBudget budget,
            String sessionId,
            int iteration
    ) {
        log.warn("[AgentLoop:{}] Max iterations ({}) reached, requesting summary", sessionId, maxIterations);
        emitStatus(sessionId, String.format("⚠️ Max iterations (%d) reached, requesting summary...", maxIterations));
        
        // P2-20 fix: 检查预算，如果完全没有剩余则跳过总结直接返回
        if (!budget.canContinue()) {
            log.info("[AgentLoop:{}] Budget fully exhausted, skipping summary request", sessionId);
            return Mono.just(new AgentLoopResult(
                    String.format("I reached the maximum iterations (%d). Please continue or start a new session.", 
                            maxIterations),
                    history,
                    iteration,
                    false,
                    false,
                    "max_iterations_no_budget"
            ));
        }
        
        // 消耗最后一次迭代用于总结
        budget.consume();
        
        // Create summary request message
        Message summaryRequest = new Message("user", 
                "[System: You have reached the maximum number of tool-calling iterations. " +
                "Please provide a summary of what you've accomplished so far and any partial results. " +
                "Do not call any more tools.]", 
                Instant.now());
        
        List<Message> summaryHistory = new ArrayList<>(history);
        summaryHistory.add(summaryRequest);
        
        // Make one final LLM call without tools
        return llmClient.chat(summaryHistory, sessionId)
                .map(response -> new AgentLoopResult(
                        response.getContent(),
                        summaryHistory,
                        iteration + 1,
                        false,
                        false,
                        "max_iterations_summary"
                ))
                .onErrorResume(e -> {
                    log.error("[AgentLoop:{}] Summary request failed: {}", sessionId, e.getMessage());
                    return Mono.just(new AgentLoopResult(
                            String.format("I reached the maximum iterations (%d) but couldn't summarize. Error: %s", 
                                    maxIterations, e.getMessage()),
                            history,
                            iteration,
                            false,
                            false,
                            "max_iterations_error"
                    ));
                });
    }
    
    // ─────────────────────────── Utility Methods ───────────────────────────
    
    private void emitStatus(String sessionId, String message) {
        if (!quietMode) {
            log.info("[AgentLoop:{}] {}", sessionId, message);
        }
        if (statusCallback != null) {
            try {
                statusCallback.onStatus(sessionId, message);
            } catch (Exception e) {
                log.debug("statusCallback error: {}", e.getMessage());
            }
        }
    }
    
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
    
    // ─────────────────────────── Control Methods ───────────────────────────
    
    /**
     * Request an interrupt of the agent loop.
     * The loop will exit at the next iteration check.
     */
    public void interrupt() {
        interrupted.set(true);
        log.info("Agent loop interrupt requested");
    }
    
    /**
     * Inject a steer message that will be added to the next tool result.
     * Used for mid-loop guidance.
     * 
     * @param steer The steer message to inject
     */
    public void steer(String steer) {
        pendingSteer.set(steer);
        log.info("Agent loop steer queued: {}", truncate(steer, 100));
    }
    
    /**
     * Reset the loop state for reuse.
     */
    public void reset() {
        interrupted.set(false);
        pendingSteer.set(null);
    }
    
    // ─────────────────────────── Setters for Callbacks ───────────────────────────
    
    public void setStepCallback(StepCallback stepCallback) {
        this.stepCallback = stepCallback;
    }
    
    public void setToolProgressCallback(ToolProgressCallback toolProgressCallback) {
        this.toolProgressCallback = toolProgressCallback;
    }
    
    public void setStatusCallback(StatusCallback statusCallback) {
        this.statusCallback = statusCallback;
    }
    
    // ─────────────────────────── Inner Classes ───────────────────────────
    
    /**
     * Interface for LLM client abstraction.
     * Implemented by LLMService or similar.
     */
    public interface LLMClient {
        /**
         * Send messages to LLM and get response.
         * The response may contain tool_calls which the agent loop will handle.
         */
        Mono<Message> chat(List<Message> messages, String sessionId);
        
        /**
         * Send messages to LLM with streaming response.
         */
        Flux<String> chatStream(List<Message> messages, String sessionId);
    }
    
    /**
     * Result of the agent loop.
     */
    public static class AgentLoopResult {
        private final String finalResponse;
        private final List<Message> messages;
        private final int iterations;
        private final boolean completed;
        private final boolean interrupted;
        private final String exitReason;
        
        public AgentLoopResult(String finalResponse, List<Message> messages, 
                               int iterations, boolean completed, boolean interrupted, String exitReason) {
            this.finalResponse = finalResponse;
            this.messages = messages;
            this.iterations = iterations;
            this.completed = completed;
            this.interrupted = interrupted;
            this.exitReason = exitReason;
        }
        
        public String getFinalResponse() { return finalResponse; }
        public List<Message> getMessages() { return messages; }
        public int getIterations() { return iterations; }
        public boolean isCompleted() { return completed; }
        public boolean isInterrupted() { return interrupted; }
        public String getExitReason() { return exitReason; }
        
        @Override
        public String toString() {
            return String.format("AgentLoopResult[iterations=%d, completed=%s, exit=%s]", 
                    iterations, completed, exitReason);
        }
    }
    
    /**
     * Result of a single tool execution.
     */
    public static class ToolResult {
        private final String toolCallId;
        private final String toolName;
        private final String output;
        private final boolean success;
        
        public ToolResult(String toolCallId, String toolName, String output, boolean success) {
            this.toolCallId = toolCallId;
            this.toolName = toolName;
            this.output = output;
            this.success = success;
        }
        
        public String getToolCallId() { return toolCallId; }
        public String getToolName() { return toolName; }
        public String getOutput() { return output; }
        public boolean isSuccess() { return success; }
    }
    
    /**
     * Callback for step events during the loop.
     */
    @FunctionalInterface
    public interface StepCallback {
        void onStep(int iteration, int maxIterations, List<ToolResult> previousTools);
    }
    
    /**
     * Callback for tool progress events.
     */
    @FunctionalInterface
    public interface ToolProgressCallback {
        void onToolProgress(String toolName, String status);
    }
    
    /**
     * Callback for status messages.
     */
    @FunctionalInterface
    public interface StatusCallback {
        void onStatus(String sessionId, String message);
    }
}
