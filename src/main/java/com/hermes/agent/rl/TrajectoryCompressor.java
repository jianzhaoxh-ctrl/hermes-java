package com.hermes.agent.rl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermes.agent.llm.LLMService;
import com.hermes.agent.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * 轨迹压缩器 — 在 token 预算内压缩对话轨迹，保留训练信号质量。
 *
 * <p>参照 Python 版 trajectory_compressor.py 的核心压缩策略：
 * <ol>
 *   <li>保护首部轮次（system + human + 首次 gpt + 首次 tool）</li>
 *   <li>保护尾部 N 轮（最终动作和结论）</li>
 *   <li>仅压缩中间部分，从第 2 个 tool response 开始</li>
 *   <li>仅按需压缩，刚好满足 token 预算</li>
 *   <li>用单条 human summary 替换压缩区域</li>
 *   <li>保留剩余 tool calls（summary 后模型继续工作）</li>
 * </ol>
 */
@Component
public class TrajectoryCompressor {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryCompressor.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 默认最大 token 预算 */
    private static final int DEFAULT_MAX_TOKENS = 16384;

    /** 保护尾部轮次数 */
    private static final int PROTECT_TAIL_TURNS = 6;

    /** 保护首部轮次数 */
    private static final int PROTECT_HEAD_TURNS = 4;

    private final LLMService llmService;

    public TrajectoryCompressor(LLMService llmService) {
        this.llmService = llmService;
    }

    /**
     * 压缩单条轨迹。
     *
     * @param trajectory  原始轨迹
     * @param maxTokens   token 预算上限
     * @return 压缩后的轨迹（如无需压缩则返回原轨迹）
     */
    public Trajectory compress(Trajectory trajectory, int maxTokens) {
        int currentTokens = trajectory.estimateTokens();
        if (currentTokens <= maxTokens) {
            return trajectory; // 无需压缩
        }

        List<Trajectory.Turn> turns = trajectory.getConversations();
        if (turns.size() <= PROTECT_HEAD_TURNS + PROTECT_TAIL_TURNS) {
            log.warn("轨迹轮次过少({})，无法压缩", turns.size());
            return trajectory;
        }

        // 分区：head / middle / tail
        List<Trajectory.Turn> head = new ArrayList<>(turns.subList(0, PROTECT_HEAD_TURNS));
        List<Trajectory.Turn> middle = new ArrayList<>(turns.subList(PROTECT_HEAD_TURNS, turns.size() - PROTECT_TAIL_TURNS));
        List<Trajectory.Turn> tail = new ArrayList<>(turns.subList(turns.size() - PROTECT_TAIL_TURNS, turns.size()));

        // 计算需要压缩多少 token
        int headTokens = estimateTokens(head);
        int tailTokens = estimateTokens(tail);
        int budgetForMiddle = maxTokens - headTokens - tailTokens - 500; // 留 500 给 summary

        if (budgetForMiddle <= 0) {
            // 只能保留 head + summary + tail
            String summaryText = summarizeMiddle(middle, trajectory.getModel());
            List<Trajectory.Turn> compressed = new ArrayList<>(head);
            compressed.add(new Trajectory.Turn("human",
                    "[Conversation Summary] " + summaryText));
            compressed.addAll(tail);
            trajectory.setConversations(compressed);
            return trajectory;
        }

        // 按需压缩：从 middle 尾部开始逐步移除，直到满足预算
        List<Trajectory.Turn> keptMiddle = new ArrayList<>(middle);
        int middleTokens = estimateTokens(keptMiddle);

        while (middleTokens > budgetForMiddle && keptMiddle.size() > 2) {
            // 移除中间最旧的一条
            keptMiddle.remove(0);
            middleTokens = estimateTokens(keptMiddle);
        }

        // 如果仍然超预算，用 LLM 摘要替换整个 middle
        if (middleTokens > budgetForMiddle) {
            String summaryText = summarizeMiddle(middle, trajectory.getModel());
            List<Trajectory.Turn> compressed = new ArrayList<>(head);
            compressed.add(new Trajectory.Turn("human",
                    "[Conversation Summary] " + summaryText));
            compressed.addAll(tail);
            trajectory.setConversations(compressed);
        } else {
            List<Trajectory.Turn> compressed = new ArrayList<>(head);
            compressed.addAll(keptMiddle);
            compressed.addAll(tail);
            trajectory.setConversations(compressed);
        }

        log.info("轨迹压缩完成: {} turns → {} turns, ~{} tokens → ~{} tokens",
                turns.size(), trajectory.getTurnCount(), currentTokens, trajectory.estimateTokens());
        return trajectory;
    }

    /**
     * 使用 LLM 摘要中间轮次。
     */
    private String summarizeMiddle(List<Trajectory.Turn> middle, String model) {
        StringBuilder sb = new StringBuilder();
        sb.append("请将以下对话片段压缩为一段连贯的摘要，保留所有关键决策、工具调用结果和重要细节。\n\n");
        sb.append("=== 对话片段 ===\n");
        for (Trajectory.Turn t : middle) {
            String role = switch (t.getFrom()) {
                case "human" -> "用户";
                case "gpt" -> "助手";
                case "system" -> "系统";
                case "tool" -> "工具";
                default -> t.getFrom();
            };
            String content = t.getContent() != null ? t.getContent() : "(无内容)";
            // 截断过长的内容
            if (content.length() > 500) {
                content = content.substring(0, 500) + "...[truncated]";
            }
            sb.append("【").append(role).append("】").append(content).append("\n\n");
        }
        sb.append("=== 要求 ===\n")
          .append("输出 150-300 字摘要，涵盖：\n")
          .append("1. 用户需求或问题\n2. 使用的工具和关键结果\n3. 结论或决策\n4. 待解决事项\n");

        try {
            List<Message> history = List.of(
                    new Message("system", "你是一个精确的对话压缩助手，只输出压缩后的摘要文本。", Instant.now()),
                    new Message("user", sb.toString(), Instant.now())
            );
            Message result = llmService.chat(history, "trajectory-compress").block();
            if (result != null && result.getContent() != null) {
                return result.getContent().trim();
            }
        } catch (Exception e) {
            log.error("轨迹摘要 LLM 调用失败: {}", e.getMessage());
        }
        return "(本段对话摘要暂不可用)";
    }

    /**
     * 批量压缩目录下的轨迹文件（JSONL 格式）。
     *
     * @param inputDir   输入目录
     * @param outputDir  输出目录
     * @param maxTokens  token 预算
     * @param samplePercent 采样百分比（0-100，0=全部处理）
     * @return 处理统计
     */
    public Map<String, Object> compressDirectory(Path inputDir, Path outputDir,
                                                  int maxTokens, int samplePercent) {
        int total = 0, compressed = 0, skipped = 0, failed = 0;

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            return Map.of("error", "Cannot create output dir: " + e.getMessage());
        }

        try (Stream<Path> paths = Files.list(inputDir)) {
            List<Path> jsonlFiles = paths
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .toList();

            for (Path inputFile : jsonlFiles) {
                Path outputFile = outputDir.resolve(inputFile.getFileName());
                total++;

                try {
                    List<String> lines = Files.readAllLines(inputFile, StandardCharsets.UTF_8);

                    // 采样
                    if (samplePercent > 0 && samplePercent < 100) {
                        Random rng = new Random(42);
                        lines = lines.stream()
                                .filter(l -> rng.nextInt(100) < samplePercent)
                                .toList();
                    }

                    List<String> outputLines = new ArrayList<>();
                    for (String line : lines) {
                        if (line.isBlank()) continue;
                        try {
                            Trajectory traj = objectMapper.readValue(line, Trajectory.class);
                            int beforeTokens = traj.estimateTokens();
                            if (beforeTokens > maxTokens) {
                                traj = compress(traj, maxTokens);
                                compressed++;
                            } else {
                                skipped++;
                            }
                            outputLines.add(objectMapper.writeValueAsString(traj));
                        } catch (Exception e) {
                            failed++;
                            outputLines.add(line); // 保留原始行
                        }
                    }

                    Files.write(outputFile, outputLines, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    failed++;
                    log.error("处理文件 {} 失败: {}", inputFile, e.getMessage());
                }
            }
        } catch (IOException e) {
            return Map.of("error", "Cannot list input dir: " + e.getMessage());
        }

        return Map.of(
                "total_files", total,
                "compressed", compressed,
                "skipped", skipped,
                "failed", failed,
                "output_dir", outputDir.toString()
        );
    }

    // ── 工具方法 ──

    private static int estimateTokens(List<Trajectory.Turn> turns) {
        int total = 0;
        for (Trajectory.Turn t : turns) {
            if (t.getContent() != null) {
                int cjk = 0, ascii = 0;
                for (char c : t.getContent().toCharArray()) {
                    if (c >= 0x4e00 && c <= 0x9fff) cjk++;
                    else if (c > ' ') ascii++;
                }
                total += (int)(cjk * 1.5 + ascii * 0.3);
            }
        }
        return total;
    }
}
