package com.hermes.agent.memory;

import com.hermes.agent.memory.vector.VectorMemoryProvider;
import com.hermes.agent.model.Message;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记忆编排器 — 统一管理所有 MemoryProvider。
 *
 * <p>参照 Python 版 MemoryManager 设计，核心职责：
 * <ul>
 *   <li>注册和管理 Provider（内置 + 外部）</li>
 *   <li>收集系统提示块</li>
 *   <li>预取记忆上下文并构建安全围栏</li>
 *   <li>同步对话轮次</li>
 *   <li>路由工具调用到正确的 Provider</li>
 *   <li>生命周期管理</li>
 * </ul>
 *
 * <p>规则：BuiltinMemoryProvider 始终存在且不可移除，
 * 最多允许 {MAX_EXTERNAL_PROVIDERS} 个外部 Provider。
 */
@Component
public class MemoryOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MemoryOrchestrator.class);

    private final List<MemoryProvider> providers = new ArrayList<>();
    private final Map<String, MemoryProvider> toolToProvider = new ConcurrentHashMap<>();
    private static final int MAX_EXTERNAL_PROVIDERS = 5; // 防止工具 schema 过度膨胀

    private final BuiltinMemoryProvider builtinProvider;
    private final VectorMemoryProvider vectorMemoryProvider;

    @Autowired
    public MemoryOrchestrator(BuiltinMemoryProvider builtinProvider, 
                               @Autowired(required = false) VectorMemoryProvider vectorMemoryProvider) {
        this.builtinProvider = builtinProvider;
        this.vectorMemoryProvider = vectorMemoryProvider;
    }

    @PostConstruct
    public void init() {
        // 内置 Provider 始终第一个注册
        addProvider(builtinProvider);
        
        // 向量记忆 Provider（如果启用且可用）
        if (vectorMemoryProvider != null && vectorMemoryProvider.isAvailable()) {
            addProvider(vectorMemoryProvider);
            log.info("[MemoryOrchestrator] 向量记忆 Provider 已注册");
        }
    }

    // ── 注册 ──

    public void addProvider(MemoryProvider provider) {
        boolean isBuiltin = "builtin".equals(provider.name());

        if (!isBuiltin) {
            long externalCount = providers.stream().filter(p -> !"builtin".equals(p.name())).count();
            if (externalCount >= MAX_EXTERNAL_PROVIDERS) {
                log.warn("Rejected memory provider '{}' — {}/{} external providers already registered. Limit reached.",
                        provider.name(), externalCount, MAX_EXTERNAL_PROVIDERS);
                return;
            }
        }

        providers.add(provider);

        // 索引工具名 → Provider
        for (Map<String, Object> schema : provider.getToolSchemas()) {
            Map<String, Object> function = (Map<String, Object>) schema.get("function");
            if (function != null) {
                String toolName = (String) function.get("name");
                if (toolName != null && !toolToProvider.containsKey(toolName)) {
                    toolToProvider.put(toolName, provider);
                }
            }
        }

        log.info("Memory provider '{}' registered ({} tools)", provider.name(), provider.getToolSchemas().size());
    }

    // ── 系统提示 ──

    public String buildSystemPrompt() {
        return MemoryContextBuilder.collectSystemPrompt(providers);
    }

    // ── 预取 ──

    public String prefetchAll(String query, String sessionId) {
        return MemoryContextBuilder.collectAndBuild(providers, query, sessionId);
    }

    public void queuePrefetchAll(String query, String sessionId) {
        for (MemoryProvider provider : providers) {
            try {
                provider.queuePrefetch(query, sessionId);
            } catch (Exception e) {
                log.debug("Provider '{}' queuePrefetch failed: {}", provider.name(), e.getMessage());
            }
        }
    }

    // ── 同步 ──

    public void syncAll(String userContent, String assistantContent, String sessionId) {
        for (MemoryProvider provider : providers) {
            try {
                provider.syncTurn(userContent, assistantContent, sessionId);
            } catch (Exception e) {
                log.warn("Provider '{}' syncTurn failed: {}", provider.name(), e.getMessage());
            }
        }
    }

    // ── 工具路由 ──

    public List<Map<String, Object>> getAllToolSchemas() {
        List<Map<String, Object>> schemas = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MemoryProvider provider : providers) {
            for (Map<String, Object> schema : provider.getToolSchemas()) {
                Map<String, Object> function = (Map<String, Object>) schema.get("function");
                if (function != null) {
                    String name = (String) function.get("name");
                    if (name != null && seen.add(name)) schemas.add(schema);
                }
            }
        }
        return schemas;
    }

    public Set<String> getAllToolNames() {
        return new HashSet<>(toolToProvider.keySet());
    }

    public boolean hasTool(String toolName) {
        return toolToProvider.containsKey(toolName);
    }

    public String handleToolCall(String toolName, Map<String, Object> args) {
        MemoryProvider provider = toolToProvider.get(toolName);
        if (provider == null) {
            return "{\"success\":false,\"error\":\"No memory provider handles tool '" + toolName + "'\"}";
        }
        try {
            return provider.handleToolCall(toolName, args);
        } catch (Exception e) {
            log.error("Provider '{}' handleToolCall({}) failed: {}", provider.name(), toolName, e.getMessage());
            return "{\"success\":false,\"error\":\"Memory tool '" + toolName + "' failed: " + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    // ── 生命周期钩子 ──

    public void initializeAll(String sessionId, Map<String, Object> kwargs) {
        for (MemoryProvider provider : providers) {
            try {
                provider.initialize(sessionId, kwargs);
            } catch (Exception e) {
                log.warn("Provider '{}' initialize failed: {}", provider.name(), e.getMessage());
            }
        }
    }

    public void onTurnStartAll(int turnNumber, String message, Map<String, Object> kwargs) {
        for (MemoryProvider provider : providers) {
            try {
                provider.onTurnStart(turnNumber, message, kwargs);
            } catch (Exception e) {
                log.debug("Provider '{}' onTurnStart failed: {}", provider.name(), e.getMessage());
            }
        }
    }

    public void onSessionEndAll(List<Message> messages) {
        for (MemoryProvider provider : providers) {
            try {
                provider.onSessionEnd(messages);
            } catch (Exception e) {
                log.debug("Provider '{}' onSessionEnd failed: {}", provider.name(), e.getMessage());
            }
        }
    }

    public String onPreCompressAll(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (MemoryProvider provider : providers) {
            try {
                String result = provider.onPreCompress(messages);
                if (result != null && !result.isBlank()) {
                    if (!sb.isEmpty()) sb.append("\n\n");
                    sb.append(result);
                }
            } catch (Exception e) {
                log.debug("Provider '{}' onPreCompress failed: {}", provider.name(), e.getMessage());
            }
        }
        return sb.toString();
    }

    public void onDelegationAll(String task, String result, String childSessionId) {
        for (MemoryProvider provider : providers) {
            try {
                provider.onDelegation(task, result, childSessionId);
            } catch (Exception e) {
                log.debug("Provider '{}' onDelegation failed: {}", provider.name(), e.getMessage());
            }
        }
    }

    public void shutdownAll() {
        for (int i = providers.size() - 1; i >= 0; i--) {
            try {
                providers.get(i).shutdown();
            } catch (Exception e) {
                log.warn("Provider '{}' shutdown failed: {}", providers.get(i).name(), e.getMessage());
            }
        }
    }

    // ── 访问器 ──

    public BuiltinMemoryProvider getBuiltinProvider() {
        return builtinProvider;
    }

    public List<MemoryProvider> getProviders() {
        return Collections.unmodifiableList(providers);
    }
}
