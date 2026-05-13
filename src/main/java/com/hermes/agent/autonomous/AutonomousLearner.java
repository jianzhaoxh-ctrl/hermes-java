package com.hermes.agent.autonomous;

import com.hermes.agent.autonomous.SkillGenerator;
import com.hermes.agent.memory.MemoryManager;
import com.hermes.agent.model.Message;
import com.hermes.agent.skills.SkillSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 自主学习引擎 — 三类记忆自动维护·调度协调器
 *
 * 职责：
 *   1. 定时触发批量技能检测（基于高频主题）
 *   2. 定期整理会话记忆、优化画像统计
 *   3. 提供系统学习状态的统一查询入口
 *
 * 与 SkillGenerator 的关系：
 *   - SkillGenerator 负责单个技能的提炼逻辑
 *   - AutonomousLearner 负责批量扫描和时间驱动的维护
 */
@Component
public class AutonomousLearner {

    private static final Logger log = LoggerFactory.getLogger(AutonomousLearner.class);

    private final MemoryManager memoryManager;
    private final SkillSystem skillSystem;
    private final SkillGenerator skillGenerator;

    public AutonomousLearner(MemoryManager memoryManager,
                             SkillSystem skillSystem,
                             SkillGenerator skillGenerator) {
        this.memoryManager = memoryManager;
        this.skillSystem = skillSystem;
        this.skillGenerator = skillGenerator;
    }

    /**
     * 每 5 分钟运行一次：批量扫描会话，提炼技能
     */
    @Scheduled(fixedRate = 300000)
    public void periodicSelfReview() {
        log.info("[自学] === 定时自检开始 ===");
        Set<String> sessionIds = memoryManager.getAllSessionIds();

        for (String sessionId : sessionIds) {
            int size = memoryManager.getSessionSize(sessionId);
            if (size < 5) continue;

            log.debug("[自学] 扫描 session={}，消息数={}", sessionId, size);

            // 获取会话历史，触发 SkillGenerator 的批量检测
            List<Message> history = memoryManager.getSessionHistory(sessionId, 50);
            if (history.size() >= 5) {
                // SkillGenerator 的 detectAndGenerateSkills 现在自动扫描 taskHistory
                skillGenerator.detectAndGenerateSkills();
            }

            // 定期记录记忆统计
            logMemoryStats(sessionId, history);
        }

        log.info("[自学] 自检完成，自动生成技能: {}",
                skillGenerator.getGeneratedSkills().size());
    }

    /**
     * 每小时生成一次学习报告
     */
    @Scheduled(fixedRate = 3600000)
    public void hourlyLearningReport() {
        Set<String> sessionIds = memoryManager.getAllSessionIds();
        log.info("[学习报告] 活跃会话数={}，已生成技能数={}，技能总数={}",
                sessionIds.size(),
                skillGenerator.getGeneratedSkills().size(),
                skillSystem.getAllSkillNames().size());

        // 输出推荐技能（使用频率最高的）
        List<String> suggestions = skillGenerator.getSuggestedSkills();
        if (!suggestions.isEmpty()) {
            log.info("[学习报告] 推荐技能: {}", suggestions);
        }
    }

    /**
     * 供外部查询的学习状态
     */
    public Map<String, Object> getLearningStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("active_sessions", memoryManager.getAllSessionIds().size());
        stats.put("auto_generated_skills", skillGenerator.getGeneratedSkills().stream()
                .map(SkillGenerator.GeneratedSkill::getName)
                .toList());
        stats.put("suggested_skills", skillGenerator.getSuggestedSkills());
        stats.put("total_registered_skills", skillSystem.getAllSkillNames().size());
        return stats;
    }

    // ─────────────────────────── private ───────────────────────────

    private void logMemoryStats(String sessionId, List<Message> history) {
        if (history.isEmpty()) return;

        // 统计用户消息数（了解用户参与度）
        long userMsgs = history.stream()
                .filter(m -> "user".equals(m.getRole()))
                .count();

        // 提取最近 3 条用户消息的内容特征（前 30 字）
        List<String> recentTopics = history.stream()
                .filter(m -> "user".equals(m.getRole()) && m.getContent() != null)
                .skip(Math.max(0, userMsgs - 3))
                .map(m -> m.getContent().length() > 30
                        ? m.getContent().substring(0, 30) + "..."
                        : m.getContent())
                .toList();

        log.debug("[自学] session={} 用户消息={}，最近话题={}",
                sessionId, userMsgs, recentTopics);
    }
}
