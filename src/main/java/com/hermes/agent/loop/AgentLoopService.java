package com.hermes.agent.loop;

import com.hermes.agent.config.AgentConfig;
import com.hermes.agent.llm.LLMService;
import com.hermes.agent.model.Message;
import com.hermes.agent.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Agent Loop Service - integrates AgentLoop with existing LLMService.
 * 
 * This service provides a high-level API for running multi-turn agent conversations
 * with tool calling, wrapping LLMService with the AgentLoop implementation.
 * 
 * Usage:
 * <pre>
 * @Autowired AgentLoopService agentLoopService;
 * 
 * // Simple call
 * String response = agentLoopService.chat(sessionId, userMessage).block();
 * 
 * // Streaming call
 * agentLoopService.chatStream(sessionId, userMessage).subscribe(chunk -> { ... });
 * 
 * // Advanced usage with AgentLoopResult
 * AgentLoopResult result = agentLoopService.runWithTools(sessionId, messages).block();
 * </pre>
 * 
 * Designed to be a drop-in replacement for Agent.chat() in the future,
 * while maintaining backward compatibility.
 */
@Component
public class AgentLoopService implements AgentLoop.LLMClient {
    
    private static final Logger log = LoggerFactory.getLogger(AgentLoopService.class);
    
    private final LLMService llmService;
    private final ToolRegistry toolRegistry;
    private final AgentConfig config;
    
    public AgentLoopService(LLMService llmService, ToolRegistry toolRegistry, AgentConfig config) {
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.config = config;
    }
    
    // ─────────────────────────── High-Level API ───────────────────────────
    
    /**
     * Simple chat - runs agent loop with a single user message.
     * 
     * @param sessionId Session identifier
     * @param userMessage User message text
     * @return Mono of final response text
     */
    public Mono<String> chat(String sessionId, String userMessage) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("user", userMessage, Instant.now()));
        
        return chat(sessionId, messages)
                .map(AgentLoop.AgentLoopResult::getFinalResponse);
    }
    
    /**
     * Chat with message history.
     * 
     * @param sessionId Session identifier
     * @param messages Message history including the new user message as the last element
     * @return Mono of AgentLoopResult
     */
    public Mono<AgentLoop.AgentLoopResult> chat(String sessionId, List<Message> messages) {
        AgentLoop loop = createLoop();
        return loop.run(messages, sessionId);
    }
    
    /**
     * Streaming chat - returns text chunks from the final response.
     * 
     * @param sessionId Session identifier
     * @param userMessage User message text
     * @return Flux of response text chunks
     */
    public Flux<String> chatStream(String sessionId, String userMessage) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("user", userMessage, Instant.now()));
        
        AgentLoop loop = createLoop();
        return loop.runStream(messages, sessionId);
    }
    
    /**
     * Advanced: Run agent loop with full result.
     * 
     * @param sessionId Session identifier
     * @param messages Message history
     * @return Mono of AgentLoopResult with full metadata
     */
    public Mono<AgentLoop.AgentLoopResult> runWithTools(String sessionId, List<Message> messages) {
        AgentLoop loop = createLoop();
        return loop.run(messages, sessionId);
    }
    
    /**
     * Advanced: Run agent loop with streaming.
     * 
     * @param sessionId Session identifier
     * @param messages Message history
     * @return Flux of response text chunks
     */
    public Flux<String> runWithToolsStream(String sessionId, List<Message> messages) {
        AgentLoop loop = createLoop();
        return loop.runStream(messages, sessionId);
    }
    
    // ─────────────────────────── Factory Methods ───────────────────────────
    
    /**
     * Create a new AgentLoop instance configured with this service.
     */
    public AgentLoop createLoop() {
        AgentLoop loop = new AgentLoop(this, toolRegistry, config);
        
        // Set default callbacks
        loop.setStepCallback((iteration, maxIterations, previousTools) -> {
            log.debug("[AgentLoop] Step {}/{} - {} tools executed", 
                    iteration, maxIterations, previousTools.size());
        });
        
        return loop;
    }
    
    /**
     * Create a new AgentLoop with custom configuration.
     */
    public AgentLoop createLoop(int maxIterations, boolean parallelTools) {
        AgentConfig customConfig = new AgentConfig();
        customConfig.setMaxIterations(maxIterations);
        customConfig.setParallelToolExecution(parallelTools);
        customConfig.setMaxIterations(maxIterations);
        customConfig.setToolTimeoutMs(config.getToolTimeoutMs());
        
        return new AgentLoop(this, toolRegistry, customConfig);
    }
    
    // ─────────────────────────── AgentLoop.LLMClient Implementation ───────────────────────────
    
    /**
     * Implementation of LLMClient interface - delegates to LLMService.
     * 
     * This method is designed for the AgentLoop's iterative pattern.
     * It does NOT recurse - that's handled by AgentLoop itself.
     * 
     * Returns a Message that may contain tool_calls, which the AgentLoop
     * will execute and then call this method again.
     */
    @Override
    public Mono<Message> chat(List<Message> messages, String sessionId) {
        // Delegate to LLMService
        // Note: LLMService.chatWithTools does ONE level of tool execution
        // For AgentLoop we want to return the raw response with tool_calls
        // and let AgentLoop handle the iteration
        
        return llmService.chatSingle(messages, sessionId);
    }
    
    @Override
    public Flux<String> chatStream(List<Message> messages, String sessionId) {
        return llmService.chatStream(messages, sessionId);
    }
    
    // ─────────────────────────── Utility Methods ───────────────────────────
    
    /**
     * Check if the service is healthy.
     */
    public boolean isHealthy() {
        return llmService != null && toolRegistry != null;
    }
    
    /**
     * Get the configured max iterations.
     */
    public int getMaxIterations() {
        return config.getMaxIterations();
    }
    
    /**
     * Generate a unique session ID.
     */
    public static String generateSessionId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
