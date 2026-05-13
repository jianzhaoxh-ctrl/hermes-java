package com.hermes.agent.api;

import com.hermes.agent.skills.SkillSystem;
import com.hermes.agent.skills.model.Skill;
import com.hermes.agent.skills.hub.SkillsHubService;
import com.hermes.agent.skills.hub.SkillsHubClient.HubSkill;
import com.hermes.agent.skills.hub.SkillsHubClient.UpdateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Skills Hub REST API - 仅处理 Hub 相关操作
 * 本地技能管理 API 由 ChatController 提供
 */
@RestController
@RequestMapping("/api/skills/hub")
public class SkillController {
    
    private static final Logger log = LoggerFactory.getLogger(SkillController.class);
    
    private final SkillsHubService hubService;
    
    public SkillController(SkillsHubService hubService) {
        this.hubService = hubService;
    }
    
    // === Hub 搜索与浏览 ===
    
    /**
     * 搜索 Hub 技能
     */
    @GetMapping("/search")
    public ResponseEntity<List<HubSkill>> searchHub(@RequestParam String q) {
        return ResponseEntity.ok(hubService.searchSkills(q));
    }
    
    /**
     * 预览 Hub 技能（不安装）
     */
    @GetMapping("/inspect/{skillId}")
    public Mono<ResponseEntity<String>> inspectSkill(@PathVariable String skillId) {
        return hubService.inspectSkill(skillId)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }
    
    // === Hub 安装与更新 ===
    
    /**
     * 从 Hub 安装技能
     */
    @PostMapping("/install/{skillId}")
    public Mono<ResponseEntity<Skill>> installFromHub(@PathVariable String skillId) {
        return hubService.installSkill(skillId)
            .map(ResponseEntity::ok)
            .onErrorResume(e -> {
                log.error("Failed to install skill {}: {}", skillId, e.getMessage());
                return Mono.just(ResponseEntity.internalServerError().build());
            });
    }
    
    /**
     * 批量安装技能
     */
    @PostMapping("/install")
    public Flux<Skill> installMultiple(@RequestBody List<String> skillIds) {
        return hubService.installSkills(skillIds);
    }
    
    /**
     * 检查技能更新
     */
    @GetMapping("/updates")
    public ResponseEntity<List<UpdateInfo>> checkUpdates() {
        return ResponseEntity.ok(hubService.checkUpdates());
    }
    
    /**
     * 更新单个技能
     */
    @PostMapping("/update/{skillId}")
    public Mono<ResponseEntity<Skill>> updateSkill(@PathVariable String skillId) {
        return hubService.updateSkill(skillId)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }
    
    /**
     * 更新所有过时技能
     */
    @PostMapping("/update-all")
    public Flux<Skill> updateAll() {
        return hubService.updateAll();
    }
    
    // === 第三方源管理 ===
    
    /**
     * 添加第三方技能源
     */
    @PostMapping("/tap")
    public ResponseEntity<Void> addTap(@RequestBody Map<String, String> body) {
        String repoUrl = body.get("repo_url");
        if (repoUrl == null || repoUrl.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        hubService.addTap(repoUrl);
        return ResponseEntity.ok().build();
    }
    
    /**
     * 列出已添加的第三方源
     */
    @GetMapping("/taps")
    public ResponseEntity<List<String>> getTaps() {
        return ResponseEntity.ok(hubService.getTaps());
    }
}
