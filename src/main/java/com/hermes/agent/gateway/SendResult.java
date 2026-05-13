package com.hermes.agent.gateway;

import reactor.core.publisher.Mono;
import java.util.Map;
import java.util.List;

/**
 * 发送结果
 */
public class SendResult {
    private final boolean success;
    private final String messageId;
    private final String error;
    private final Object rawResponse;
    private final boolean retryable;  // 瞬态网络错误可重试

    public SendResult(boolean success, String messageId, String error, Object rawResponse, boolean retryable) {
        this.success = success;
        this.messageId = messageId;
        this.error = error;
        this.rawResponse = rawResponse;
        this.retryable = retryable;
    }

    public static SendResult success(String messageId) {
        return new SendResult(true, messageId, null, null, false);
    }

    public static SendResult success(String messageId, Object rawResponse) {
        return new SendResult(true, messageId, null, rawResponse, false);
    }

    public static SendResult failure(String error) {
        return new SendResult(false, null, error, null, false);
    }

    public static SendResult failure(String error, boolean retryable) {
        return new SendResult(false, null, error, null, retryable);
    }

    public boolean isSuccess() { return success; }
    public String getMessageId() { return messageId; }
    public String getError() { return error; }
    public Object getRawResponse() { return rawResponse; }
    public boolean isRetryable() { return retryable; }
}
