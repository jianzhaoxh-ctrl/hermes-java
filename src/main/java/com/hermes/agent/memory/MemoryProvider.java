package com.hermes.agent.memory;

import com.hermes.agent.model.Message;

import java.util.List;
import java.util.Map;

/**
 * 可插拔的记忆 Provider 抽象接口。
 *
 * <p>参照 Python 版 hermes-agent 的 MemoryProvider 设计，支持：
 * <ul>
 *   <li>内置 Provider（MEMORY.md / USER.md 文件持久化）</li>
 *   <li>外部 Provider（Honcho / Mem0 等，未来扩展）</li>
 * </ul>
 *
 * <p>生命周期：
 * <ol>
 *   <li>initialize() — 初始化连接、加载资源</li>
 *   <li>systemPromptBlock() — 返回注入系统提示的静态文本</li>
 *   <li>prefetch(query) — 每轮对话前预取相关记忆</li>
 *   <li>syncTurn(user, assistant) — 每轮对话后同步记忆</li>
 *   <li>getToolSchemas() — 返回暴露给 LLM 的工具 schema</li>
 *   <li>handleToolCall() — 分发工具调用</li>
 *   <li>shutdown() — 清理退出</li>
 * </ol>
 */
public interface MemoryProvider {

    /** Provider 短标识，如 "builtin"、"honcho" */
    String name();

    /** 是否可用（检查配置和依赖） */
    boolean isAvailable();

    /** 初始化，session 开始时调用 */
    default void initialize(String sessionId, Map<String, Object> kwargs) {}

    /** 返回注入系统提示的文本块，空字符串表示跳过 */
    default String systemPromptBlock() { return ""; }

    /** 预取相关记忆上下文（每轮对话前调用） */
    default String prefetch(String query, String sessionId) { return ""; }

    /** 后台预取（当前轮完成后调用，结果供下一轮使用） */
    default void queuePrefetch(String query, String sessionId) {}

    /** 同步一轮对话到后端 */
    default void syncTurn(String userContent, String assistantContent, String sessionId) {}

    /** 返回工具 schema 列表（OpenAI function calling 格式） */
    default List<Map<String, Object>> getToolSchemas() { return List.of(); }

    /** 处理工具调用 */
    default String handleToolCall(String toolName, Map<String, Object> args) {
        throw new UnsupportedOperationException("Provider " + name() + " does not handle tool " + toolName);
    }

    /** 每轮开始时通知 */
    default void onTurnStart(int turnNumber, String message, Map<String, Object> kwargs) {}

    /** 会话结束时通知 */
    default void onSessionEnd(List<Message> messages) {}

    /** 上下文压缩前通知，返回要保留的文本 */
    default String onPreCompress(List<Message> messages) { return ""; }

    /** 当内置记忆工具写入时通知外部 Provider */
    default void onMemoryWrite(String action, String target, String content) {}

    /** 子 Agent 完成时通知 */
    default void onDelegation(String task, String result, String childSessionId) {}

    /** 关闭 */
    default void shutdown() {}
}
