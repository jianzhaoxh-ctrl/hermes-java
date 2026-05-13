package com.hermes.agent.rl;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 轨迹数据模型 — ShareGPT 格式的对话轨迹。
 *
 * <p>参照 Python 版 trajectory.py 和 trajectory_compressor.py 的数据格式。
 * 用于保存 Agent 的完整交互轨迹，供 RL 训练使用。
 */
public class Trajectory {

    private List<Turn> conversations;
    private Instant timestamp;
    private String model;
    private boolean completed;
    private Map<String, Object> metadata;

    public Trajectory() {}

    public Trajectory(List<Turn> conversations, String model, boolean completed) {
        this.conversations = conversations;
        this.timestamp = Instant.now();
        this.model = model;
        this.completed = completed;
    }

    public List<Turn> getConversations() { return conversations; }
    public void setConversations(List<Turn> conversations) { this.conversations = conversations; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    /** 轨迹中的对话轮次 */
    public int getTurnCount() {
        return conversations != null ? conversations.size() : 0;
    }

    /** 估算 token 数（粗略：1 中文 ≈ 1.5 token，1 英文单词 ≈ 1.3 token） */
    public int estimateTokens() {
        if (conversations == null) return 0;
        int total = 0;
        for (Turn t : conversations) {
            if (t.content != null) {
                // 粗略估算
                int cjk = 0, ascii = 0;
                for (char c : t.content.toCharArray()) {
                    if (c >= 0x4e00 && c <= 0x9fff) cjk++;
                    else if (c > ' ') ascii++;
                }
                total += (int)(cjk * 1.5 + ascii * 0.3);
            }
        }
        return total;
    }

    /**
     * 对话轮次。
     * ShareGPT 格式：from (system/human/gpt/tool), value (内容)。
     */
    public static class Turn {
        private String from;   // system, human, gpt, tool
        private String content;

        public Turn() {}

        public Turn(String from, String content) {
            this.from = from;
            this.content = content;
        }

        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public Map<String, String> toMap() {
            return Map.of("from", from != null ? from : "", "value", content != null ? content : "");
        }
    }
}
