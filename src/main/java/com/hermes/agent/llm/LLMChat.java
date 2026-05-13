package com.hermes.agent.llm;

import com.hermes.agent.model.Message;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * LLM 聊天接口
 * 
 * 统一的聊天 API，屏蔽不同提供商的请求/响应格式差异。
 */
public interface LLMChat {

    /**
     * 纯文本聊天（无工具）
     * 
     * @param history 消息历史
     * @param model 模型名称（null 使用默认）
     * @param sessionId 会话 ID
     * @return 助手回复
     */
    Mono<Message> chat(List<Message> history, String model, String sessionId);

    /**
     * 带工具的聊天 - 自动执行工具并返回最终结果
     * 
     * @param history 消息历史
     * @param model 模型名称（null 使用默认）
     * @param sessionId 会话 ID
     * @return 助手回复（工具已执行完毕）
     */
    Mono<Message> chatWithTools(List<Message> history, String model, String sessionId);

    /**
     * 单次聊天 - 返回原始响应（不自动执行工具）
     * 
     * 用于 AgentLoop 多轮工具调用场景，由调用方决定何时执行工具。
     * 
     * @param history 消息历史
     * @param model 模型名称（null 使用默认）
     * @param sessionId 会话 ID
     * @return 原始 LLM 响应（可能包含 tool_calls）
     */
    Mono<Message> chatSingle(List<Message> history, String model, String sessionId);

    /**
     * 流式聊天
     * 
     * @param history 消息历史
     * @param model 模型名称（null 使用默认）
     * @param sessionId 会话 ID
     * @return 增量文本流
     */
    Flux<String> chatStream(List<Message> history, String model, String sessionId);

    /**
     * 带参数的流式聊天
     * 
     * @param history 消息历史
     * @param model 模型名称
     * @param sessionId 会话 ID
     * @param params 额外参数（temperature, max_tokens 等）
     * @return 增量文本流
     */
    default Flux<String> chatStream(List<Message> history, String model, 
                                     String sessionId, Map<String, Object> params) {
        return chatStream(history, model, sessionId);
    }
}
