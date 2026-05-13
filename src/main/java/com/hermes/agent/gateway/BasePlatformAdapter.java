package com.hermes.agent.gateway;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.util.Map;
import java.util.List;
import java.util.function.Function;

/**
 * 平台适配器基类
 * 
 * 所有平台适配器（Telegram、Discord、WhatsApp）继承此类并实现必要方法。
 * 
 * 职责：
 * - 连接和认证
 * - 接收消息
 * - 发送消息/响应
 * - 处理媒体
 */
public abstract class BasePlatformAdapter {

    protected final PlatformConfig config;
    protected final Platform platform;
    protected volatile boolean running = false;
    protected volatile String fatalErrorCode = null;
    protected volatile String fatalErrorMessage = null;
    protected volatile boolean fatalErrorRetryable = true;

    // 消息处理器
    protected Function<MessageEvent, Mono<String>> messageHandler;

    // 投递处理器（由 GatewayService 设置）
    protected java.util.function.BiConsumer<String, DeliveryRouter.DeliveryTarget> deliveryHandler;

    public BasePlatformAdapter(PlatformConfig config, Platform platform) {
        this.config = config;
        this.platform = platform;
    }

    // ========== 生命周期 ==========

    /**
     * 连接平台并开始接收消息
     */
    public abstract Mono<Boolean> connect();

    /**
     * 断开连接
     */
    public abstract Mono<Void> disconnect();

    /**
     * 检查是否已连接
     */
    public boolean isConnected() {
        return running;
    }

    /**
     * 检查是否有致命错误
     */
    public boolean hasFatalError() {
        return fatalErrorMessage != null;
    }

    public String getFatalErrorMessage() {
        return fatalErrorMessage;
    }

    public String getFatalErrorCode() {
        return fatalErrorCode;
    }

    // ========== 消息发送 ==========

    /**
     * 发送消息到聊天
     * 
     * @param chatId 聊天/频道 ID
     * @param content 消息内容（可能是 markdown）
     * @param replyTo 可选的回复消息 ID
     * @param metadata 平台特定选项
     */
    public abstract Mono<SendResult> send(
        String chatId, 
        String content, 
        String replyTo, 
        Map<String, Object> metadata
    );

    /**
     * 编辑已发送的消息
     */
    public Mono<SendResult> editMessage(
        String chatId, 
        String messageId, 
        String content,
        boolean finalize
    ) {
        return Mono.just(SendResult.failure("Not supported"));
    }

    /**
     * 发送正在输入指示器
     */
    public Mono<Void> sendTyping(String chatId, Map<String, Object> metadata) {
        return Mono.empty();
    }

    /**
     * 停止正在输入指示器
     */
    public Mono<Void> stopTyping(String chatId) {
        return Mono.empty();
    }

    // ========== 媒体发送 ==========

    /**
     * 发送图片
     */
    public Mono<SendResult> sendImage(
        String chatId,
        String imageUrl,
        String caption,
        String replyTo,
        Map<String, Object> metadata
    ) {
        // 默认回退：发送 URL 作为文本
        String text = caption != null ? caption + "\n" + imageUrl : imageUrl;
        return send(chatId, text, replyTo, metadata);
    }

    /**
     * 发送动画 GIF
     */
    public Mono<SendResult> sendAnimation(
        String chatId,
        String animationUrl,
        String caption,
        String replyTo,
        Map<String, Object> metadata
    ) {
        return sendImage(chatId, animationUrl, caption, replyTo, metadata);
    }

    /**
     * 发送语音消息
     */
    public Mono<SendResult> sendVoice(
        String chatId,
        String audioPath,
        String caption,
        String replyTo
    ) {
        String text = "🔊 Audio: " + audioPath;
        if (caption != null) text = caption + "\n" + text;
        return send(chatId, text, replyTo, null);
    }

    /**
     * 发送视频
     */
    public Mono<SendResult> sendVideo(
        String chatId,
        String videoPath,
        String caption,
        String replyTo
    ) {
        String text = "🎬 Video: " + videoPath;
        if (caption != null) text = caption + "\n" + text;
        return send(chatId, text, replyTo, null);
    }

    /**
     * 发送文档
     */
    public Mono<SendResult> sendDocument(
        String chatId,
        String filePath,
        String caption,
        String fileName,
        String replyTo
    ) {
        String text = "📎 File: " + filePath;
        if (caption != null) text = caption + "\n" + text;
        return send(chatId, text, replyTo, null);
    }

    // ========== 消息处理 ==========

    /**
     * 设置消息处理器
     */
    public void setMessageHandler(Function<MessageEvent, Mono<String>> handler) {
        this.messageHandler = handler;
    }

    /**
     * 处理传入消息（内部调用）
     */
    protected Mono<Void> handleMessage(MessageEvent event) {
        if (messageHandler == null) {
            return Mono.empty();
        }
        return messageHandler.apply(event)
            .flatMap(response -> {
                if (response != null && !response.isEmpty()) {
                    return send(event.getSource().getChatId(), response, 
                        event.getMessageId(), null).then();
                }
                return Mono.empty();
            });
    }

    // ========== 辅助方法 ==========

    /**
     * 获取平台名称
     */
    public String getName() {
        return platform.getValue();
    }

    /**
     * 获取聊天信息
     */
    public abstract Mono<Map<String, Object>> getChatInfo(String chatId);

    /**
     * 格式化消息（平台特定的 markdown 处理）
     */
    public String formatMessage(String content) {
        return content;
    }

    /**
     * 截断长消息为多个块
     */
    public List<String> truncateMessage(String content, int maxLength) {
        if (content.length() <= maxLength) {
            return List.of(content);
        }

        List<String> chunks = new java.util.ArrayList<>();
        String remaining = content;

        while (!remaining.isEmpty()) {
            if (remaining.length() <= maxLength - 10) { // 预留 "(x/y)" 空间
                chunks.add(remaining);
                break;
            }

            // 尝试在换行符处分割
            int splitAt = remaining.lastIndexOf('\n', maxLength - 10);
            if (splitAt < maxLength / 2) {
                splitAt = remaining.lastIndexOf(' ', maxLength - 10);
            }
            if (splitAt < 1) {
                splitAt = maxLength - 10;
            }

            chunks.add(remaining.substring(0, splitAt));
            remaining = remaining.substring(splitAt).stripLeading();
        }

        // 添加分块指示器
        if (chunks.size() > 1) {
            int total = chunks.size();
            for (int i = 0; i < total; i++) {
                chunks.set(i, chunks.get(i) + " (" + (i + 1) + "/" + total + ")");
            }
        }

        return chunks;
    }

    /**
     * 构建会话来源
     */
    protected SessionSource buildSource(
        String chatId,
        String chatName,
        String chatType,
        String userId,
        String userName,
        String threadId,
        String chatTopic
    ) {
        SessionSource source = new SessionSource(platform, chatId);
        source.setChatName(chatName);
        source.setChatType(chatType != null ? chatType : "dm");
        source.setUserId(userId);
        source.setUserName(userName);
        source.setThreadId(threadId);
        source.setChatTopic(chatTopic);
        return source;
    }

    // ========== 状态标记 ==========

    protected void markConnected() {
        running = true;
        fatalErrorCode = null;
        fatalErrorMessage = null;
        fatalErrorRetryable = true;
    }

    protected void markDisconnected() {
        running = false;
    }

    protected void setFatalError(String code, String message, boolean retryable) {
        running = false;
        fatalErrorCode = code;
        fatalErrorMessage = message;
        fatalErrorRetryable = retryable;
    }
}
