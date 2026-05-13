package com.hermes.agent.skills.hub;

import com.hermes.agent.skills.SkillSystem;
import com.hermes.agent.skills.model.Skill;
import com.hermes.agent.skills.hub.SkillsHubClient.HubSkill;
import com.hermes.agent.skills.hub.SkillsHubClient.UpdateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Skills Hub 服务层 - 协调 Hub 客户端与技能系统
 */
@Service
public class SkillsHubService {
    
    private static final Logger log = LoggerFactory.getLogger(SkillsHubService.class);
    
    private final SkillsHubClient hubClient;
    private final SkillSystem skillSystem;
    
    public SkillsHubService(SkillsHubClient hubClient, @Lazy SkillSystem skillSystem) {
        this.hubClient = hubClient;
        this.skillSystem = skillSystem;
    }
    
    /**
     * 搜索技能
     */
    public List<HubSkill> searchSkills(String query) {
        log.debug("Searching Hub for: {}", query);
        return hubClient.search(query);
    }
    
    /**
     * 安装单个技能
     */
    public Mono<Skill> installSkill(String skillId) {
        log.info("Installing skill from Hub: {}", skillId);
        return hubClient.install(skillId)
            .doOnSuccess(skill -> {
                if (skill != null) {
                    skillSystem.registerSkill(skill);
                    log.info("Successfully installed: {} v{}", skill.getName(), skill.getVersion());
                }
            })
            .doOnError(e -> log.error("Failed to install skill {}: {}", skillId, e.getMessage()));
    }
    
    /**
     * 批量安装技能
     */
    public Flux<Skill> installSkills(List<String> skillIds) {
        log.info("Installing {} skills from Hub", skillIds.size());
        return Flux.fromIterable(skillIds)
            .flatMap(this::installSkill)
            .onErrorContinue((e, item) -> log.warn("Failed to install {}: {}", item, e.getMessage()));
    }
    
    /**
     * 预览技能（不安装）
     */
    public Mono<String> inspectSkill(String skillId) {
        return hubClient.inspect(skillId);
    }
    
    /**
     * 检查已安装技能的更新
     */
    public List<UpdateInfo> checkUpdates() {
        Map<String, Skill> installed = skillSystem.getAllSkills();
        List<UpdateInfo> updates = hubClient.checkUpdates(installed);
        
        if (!updates.isEmpty()) {
            log.info("Found {} skill updates available", updates.size());
            updates.forEach(u -> log.info("  - {}: {} -> {}", u.name(), u.currentVersion(), u.latestVersion()));
        }
        
        return updates;
    }
    
    /**
     * 更新单个技能
     */
    public Mono<Skill> updateSkill(String skillId) {
        log.info("Updating skill: {}", skillId);
        return hubClient.update(skillId)
            .doOnSuccess(skill -> {
                if (skill != null) {
                    skillSystem.registerSkill(skill);
                    log.info("Successfully updated: {} to v{}", skill.getName(), skill.getVersion());
                }
            });
    }
    
    /**
     * 更新所有过时技能
     */
    public Flux<Skill> updateAll() {
        List<UpdateInfo> updates = checkUpdates();
        if (updates.isEmpty()) {
            log.info("All skills are up to date");
            return Flux.empty();
        }
        
        log.info("Updating {} skills...", updates.size());
        return Flux.fromIterable(updates)
            .map(UpdateInfo::name)
            .flatMap(this::updateSkill)
            .onErrorContinue((e, item) -> log.warn("Failed to update {}: {}", item, e.getMessage()));
    }
    
    /**
     * 添加第三方技能源
     */
    public void addTap(String repoUrl) {
        hubClient.addTap(repoUrl);
    }
    
    /**
     * 获取已添加的第三方源列表
     */
    public List<String> getTaps() {
        return hubClient.getTaps();
    }
    
    /**
     * 获取技能详情（如果已安装则返回本地版本，否则预览 Hub 版本）
     */
    public Mono<String> getSkillContent(String skillId) {
        // 先检查本地是否已安装
        Skill local = skillSystem.getSkill(skillId).orElse(null);
        if (local != null) {
            return Mono.just(local.getRawContent());
        }
        
        // 未安装则从 Hub 预览
        return inspectSkill(skillId);
    }
}
