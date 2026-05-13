package com.hermes.agent.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式输出消费者
 * 
 * 参考 Python 版 gateway/stream_consumer.py 实现。
 * 
 * 桥接同步 Agent 回调到异步平台投递：
 * 1. 通过 onDelta() 接收增量文本（线程安全）
 * 2. 缓冲、限速、逐步编辑平台消息
 * 3. 支持 edit 传输模式（先发初始消息，再编辑更新）
 * 
 * 设计：使用 edit 传输（发送初始消息，然后 editMessage 更新），
 * 这在 Telegram、Discord、Slack 上广泛支持。
 */
public class StreamConsumer {
    private static final Logger log = LoggerFactory.getLogger(StreamConsumer.class);

    // 连续 flood-control 失败后永久禁用渐进编辑的最大次数
    private static final int MAX_FLOOD_STRIKES = 3;

    // Think/Reasoning 标签过滤
    private static final String[] OPEN_THINK_TAGS = {
        "<REASONING_SCRATCHPAD>", "<think>", "<reasoning>",
        "<THINKING>", "<thinking>", "<thought>"
    };
    private static final String[] CLOSE_THINK_TAGS = {
        "</REASONING_SCRATCHPAD>", "</think>", "</reasoning>",
        "</THINKING>", "</thinking>", "</thought>"
    };

    // Markdown 图片提取
    private static final Pattern MD_IMAGE_PATTERN = Pattern.compile(
        "!\\[([^\\]]*)\\]\\((https?://[^\\s\\)]+)\\)"
    );

    // MEDIA: 标签提取
    private static final Pattern MEDIA_TAG_PATTERN = Pattern.compile(
        "MEDIA:\\s*(\\S+)"
    );

    // 内部标记
    private static final String DONE_MARKER = "__DONE__";
    private static final String SEGMENT_BREAK_MARKER = "__NEW_SEGMENT__";

    /**
     * 流消费者配置
     */
    public static class StreamConsumerConfig {
        private double editInterval = 1.0;       // 编辑间隔（秒）
        private int bufferThreshold = 40;         // 缓冲阈值（字符数）
        private String cursor = " ▉";             // 流式光标
        private boolean bufferOnly = false;       // 仅缓冲模式（不发送）

        public double getEditInterval() { return editInterval; }
        public void setEditInterval(double editInterval) { this.editInterval = editInterval; }
        public int getBufferThreshold() { return bufferThreshold; }
        public void setBufferThreshold(int bufferThreshold) { this.bufferThreshold = bufferThreshold; }
        public String getCursor() { return cursor; }
        public void setCursor(String cursor) { this.cursor = cursor; }
        public boolean isBufferOnly() { return bufferOnly; }
        public void setBufferOnly(boolean bufferOnly) { this.bufferOnly = bufferOnly; }
    }

    private final BasePlatformAdapter adapter;
    private final String chatId;
    private final StreamConsumerConfig config;
    private final Map<String, Object> metadata;

    // 线程安全队列
    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();

    // 累积文本
    private final StringBuilder accumulated = new StringBuilder();

    // 状态
    private volatile String messageId = null;
    private volatile boolean alreadySent = false;
    private volatile boolean editSupported = true;
    private volatile boolean finalResponseSent = false;
    private volatile int floodStrikes = 0;
    private volatile double currentEditInterval;
    private String lastSentText = "";

    // Think-block 过滤状态
    private boolean inThinkBlock = false;
    private String thinkBuffer = "";

    // 适配器是否需要显式 finalize
    private final boolean adapterRequiresFinalize;

    public StreamConsumer(BasePlatformAdapter adapter, String chatId,
                          StreamConsumerConfig config, Map<String, Object> metadata) {
        this.adapter = adapter;
        this.chatId = chatId;
        this.config = config != null ? config : new StreamConsumerConfig();
        this.metadata = metadata;
        this.currentEditInterval = this.config.getEditInterval();

        // 检测适配器是否需要 finalize
        this.adapterRequiresFinalize = false;  // Java 版暂不支持此属性检测
    }

    /**
     * 接收增量文本（线程安全，从 Agent 工作线程调用）
     * 
     * @param text 增量文本，null 表示工具边界
     */
    public void onDelta(String text) {
        if (text == null) {
            // 工具边界：开始新的消息段落
            queue.offer(SEGMENT_BREAK_MARKER);
            return;
        }
        queue.offer(text);
    }

    /**
     * 信号流完成
     */
    public void finish() {
        queue.offer(DONE_MARKER);
    }

    /**
     * 请求新段落
     */
    public void onSegmentBreak() {
        queue.offer(SEGMENT_BREAK_MARKER);
    }

    /**
     * 异步运行消费者
     */
    public Mono<Void> run() {
        return Mono.<Void>create(sink -> {
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    processQueue();
                    sink.success();
                } catch (Exception e) {
                    sink.error(e);
                }
            });
        });
    }

    /**
     * 是否已发送至少一条消息
     */
    public boolean isAlreadySent() {
        return alreadySent;
    }

    /**
     * 最终响应是否已发送
     */
    public boolean isFinalResponseSent() {
        return finalResponseSent;
    }

    /**
     * 获取累积的完整文本
     */
    public String getAccumulatedText() {
        return accumulated.toString();
    }

    // ========== 内部处理 ==========

    private void processQueue() {
        long lastEditTime = 0;
        String buffer = "";

        while (true) {
            try {
                Object item = queue.poll((long)(config.getEditInterval()), TimeUnit.SECONDS);

                if (item == null) {
                    // 超时：检查是否需要编辑
                    if (alreadySent && !buffer.isEmpty() && editSupported) {
                        long now = System.currentTimeMillis();
                        if (now - lastEditTime >= (long)(currentEditInterval * 1000)) {
                            lastEditTime = doEdit(buffer, false);
                            buffer = "";
                        }
                    }
                    continue;
                }

                // 完成信号
                if (DONE_MARKER.equals(item)) {
                    // 发送最终内容
                    if (!buffer.isEmpty()) {
                        doFinalSend(buffer);
                    } else if (alreadySent && messageId != null && !accumulated.isEmpty()) {
                        // 最终编辑（去掉光标）
                        doEdit(accumulated.toString(), true);
                    }
                    finalResponseSent = true;
                    return;
                }

                // 段落中断
                if (SEGMENT_BREAK_MARKER.equals(item)) {
                    // 先发送当前段落
                    if (!buffer.isEmpty()) {
                        doFinalSend(buffer);
                        buffer = "";
                    }
                    // 重置段落状态
                    messageId = null;
                    accumulated.setLength(0);
                    lastSentText = "";
                    continue;
                }

                // 文本增量
                if (item instanceof String text) {
                    // Think-block 过滤
                    String filtered = filterThinkBlocks(text);
                    if (filtered == null || filtered.isEmpty()) continue;

                    accumulated.append(filtered);
                    buffer += filtered;

                    // 检查是否需要发送/编辑
                    if (buffer.length() >= config.getBufferThreshold()) {
                        if (!alreadySent) {
                            // 首次发送
                            doInitialSend(buffer);
                            buffer = "";
                        } else if (editSupported && messageId != null) {
                            long now = System.currentTimeMillis();
                            if (now - lastEditTime >= (long)(currentEditInterval * 1000)) {
                                lastEditTime = doEdit(accumulated.toString(), false);
                                buffer = "";
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void doInitialSend(String content) {
        String sendContent = content + config.getCursor();
        SendResult result = adapter.send(chatId, sendContent, null, metadata).block();
        if (result != null && result.isSuccess()) {
            messageId = result.getMessageId();
            alreadySent = true;
            lastSentText = sendContent;
        } else {
            // 编辑不支持，切换到 buffer-only + 最终发送模式
            editSupported = false;
        }
    }

    private long doEdit(String content, boolean isFinal) {
        String editContent = isFinal ? content : content + config.getCursor();

        // 跳过冗余编辑
        if (!isFinal && editContent.equals(lastSentText)) {
            return System.currentTimeMillis();
        }

        try {
            SendResult result = adapter.editMessage(chatId, messageId, editContent, isFinal).block();
            if (result != null && result.isSuccess()) {
                lastSentText = editContent;
                floodStrikes = 0;
                currentEditInterval = config.getEditInterval();
            } else {
                handleEditFailure(result);
            }
        } catch (Exception e) {
            log.warn("Edit failed: {}", e.getMessage());
            handleEditFailure(null);
        }

        return System.currentTimeMillis();
    }

    private void doFinalSend(String content) {
        // 提取媒体标签
        String cleanedContent = stripMediaTags(content);

        // 提取图片 URL
        List<ImageRef> images = extractImages(cleanedContent);
        cleanedContent = removeImageTags(cleanedContent, images);

        // 发送文本
        if (!cleanedContent.isBlank()) {
            if (alreadySent && messageId != null && editSupported) {
                doEdit(cleanedContent, true);
            } else {
                SendResult result = adapter.send(chatId, cleanedContent, null, metadata).block();
                if (result != null && result.isSuccess()) {
                    messageId = result.getMessageId();
                    alreadySent = true;
                }
            }
        }

        // 发送图片
        for (ImageRef img : images) {
            try {
                adapter.sendImage(chatId, img.url, img.altText, null, metadata).block();
            } catch (Exception e) {
                log.warn("Failed to send image: {}", e.getMessage());
            }
        }
    }

    private void handleEditFailure(SendResult result) {
        floodStrikes++;
        if (floodStrikes >= MAX_FLOOD_STRIKES) {
            log.warn("Too many edit failures, disabling progressive edits for this stream");
            editSupported = false;
        } else {
            // 指数退避
            currentEditInterval = Math.min(currentEditInterval * 1.5, 5.0);
        }
    }

    // ========== Think-block 过滤 ==========

    private String filterThinkBlocks(String text) {
        if (text == null || text.isEmpty()) return text;

        String result = text;

        for (String openTag : OPEN_THINK_TAGS) {
            if (result.contains(openTag)) {
                inThinkBlock = true;
                int idx = result.indexOf(openTag);
                thinkBuffer = result.substring(idx + openTag.length());
                result = result.substring(0, idx);
            }
        }

        if (inThinkBlock) {
            for (String closeTag : CLOSE_THINK_TAGS) {
                if (thinkBuffer.contains(closeTag)) {
                    int idx = thinkBuffer.indexOf(closeTag);
                    thinkBuffer = thinkBuffer.substring(idx + closeTag.length());
                    inThinkBlock = false;
                    result += thinkBuffer;
                    thinkBuffer = "";
                    break;
                }
            }
            return result.isEmpty() ? null : result;
        }

        return result;
    }

    // ========== 媒体提取 ==========

    private record ImageRef(String url, String altText) {}

    private List<ImageRef> extractImages(String content) {
        List<ImageRef> images = new ArrayList<>();
        Matcher matcher = MD_IMAGE_PATTERN.matcher(content);
        while (matcher.find()) {
            String altText = matcher.group(1);
            String url = matcher.group(2);
            // 仅提取看起来像图片的 URL
            String lower = url.toLowerCase();
            if (lower.contains(".png") || lower.contains(".jpg") || lower.contains(".jpeg") ||
                lower.contains(".gif") || lower.contains(".webp") ||
                lower.contains("fal.media") || lower.contains("fal-cdn")) {
                images.add(new ImageRef(url, altText));
            }
        }
        return images;
    }

    private String removeImageTags(String content, List<ImageRef> images) {
        if (images.isEmpty()) return content;
        String cleaned = content;
        for (ImageRef img : images) {
            String tag = "![" + img.altText + "](" + img.url + ")";
            cleaned = cleaned.replace(tag, "");
        }
        // 清理多余空行
        cleaned = cleaned.replaceAll("\n{3,}", "\n\n").strip();
        return cleaned;
    }

    private String stripMediaTags(String content) {
        return MEDIA_TAG_PATTERN.matcher(content).replaceAll("").strip();
    }
}
