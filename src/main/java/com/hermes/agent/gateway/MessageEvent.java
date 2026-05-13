package com.hermes.agent.gateway;

import java.time.Instant;
import java.util.*;

/**
 * 消息事件 - 平台传入消息的标准化表示
 * 
 * 所有平台适配器产生此统一格式
 */
public class MessageEvent {
    // 消息内容
    private String text;
    private MessageType messageType = MessageType.TEXT;

    // 来源信息
    private SessionSource source;

    // 原始平台数据
    private Object rawMessage;
    private String messageId;
    private Long platformUpdateId;  // Telegram update_id 等

    // 媒体附件
    private List<String> mediaUrls = new ArrayList<>();  // 本地文件路径
    private List<String> mediaTypes = new ArrayList<>();

    // 回复上下文
    private String replyToMessageId;
    private String replyToText;

    // 自动加载的技能
    private String autoSkill;

    // 频道临时系统提示
    private String channelPrompt;

    // 内部标志（后台进程完成通知等）
    private boolean internal = false;

    // 时间戳
    private Instant timestamp = Instant.now();

    public enum MessageType {
        TEXT, LOCATION, PHOTO, VIDEO, AUDIO, VOICE, DOCUMENT, STICKER, COMMAND
    }

    // Getters and Setters
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public MessageType getMessageType() { return messageType; }
    public void setMessageType(MessageType messageType) { this.messageType = messageType; }
    public SessionSource getSource() { return source; }
    public void setSource(SessionSource source) { this.source = source; }
    public Object getRawMessage() { return rawMessage; }
    public void setRawMessage(Object rawMessage) { this.rawMessage = rawMessage; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public Long getPlatformUpdateId() { return platformUpdateId; }
    public void setPlatformUpdateId(Long platformUpdateId) { this.platformUpdateId = platformUpdateId; }
    public List<String> getMediaUrls() { return mediaUrls; }
    public void setMediaUrls(List<String> mediaUrls) { this.mediaUrls = mediaUrls; }
    public List<String> getMediaTypes() { return mediaTypes; }
    public void setMediaTypes(List<String> mediaTypes) { this.mediaTypes = mediaTypes; }
    public String getReplyToMessageId() { return replyToMessageId; }
    public void setReplyToMessageId(String replyToMessageId) { this.replyToMessageId = replyToMessageId; }
    public String getReplyToText() { return replyToText; }
    public void setReplyToText(String replyToText) { this.replyToText = replyToText; }
    public String getAutoSkill() { return autoSkill; }
    public void setAutoSkill(String autoSkill) { this.autoSkill = autoSkill; }
    public String getChannelPrompt() { return channelPrompt; }
    public void setChannelPrompt(String channelPrompt) { this.channelPrompt = channelPrompt; }
    public boolean isInternal() { return internal; }
    public void setInternal(boolean internal) { this.internal = internal; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    /**
     * 检查是否为命令消息
     */
    public boolean isCommand() {
        return text != null && text.startsWith("/");
    }

    /**
     * 获取命令名称
     */
    public String getCommand() {
        if (!isCommand()) return null;
        String[] parts = text.split("\\s+", 2);
        String raw = parts[0].substring(1).toLowerCase();
        // 处理 @bot 格式
        if (raw.contains("@")) {
            raw = raw.split("@", 2)[0];
        }
        // 拒绝文件路径
        if (raw.contains("/")) return null;
        return raw;
    }

    /**
     * 获取命令参数
     */
    public String getCommandArgs() {
        if (!isCommand()) return text;
        String[] parts = text.split("\\s+", 2);
        return parts.length > 1 ? parts[1] : "";
    }
}
