package com.hermes.agent.skills.hub;

import com.hermes.agent.skills.model.Skill;
import com.hermes.agent.skills.model.Skill.Source;
import com.hermes.agent.skills.loader.SkillLoader;
import com.hermes.agent.skills.loader.SkillWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Skills Hub 客户端 - 与官方技能仓库交互
 */
@Component
public class SkillsHubClient {
    
    private static final Logger log = LoggerFactory.getLogger(SkillsHubClient.class);
    
    // 默认 Hub 配置
    private static final String DEFAULT_HUB_API_URL = "https://api.hermes-agent.com/v1/skills";
    private static final String DEFAULT_HUB_INDEX_URL = "https://raw.githubusercontent.com/NousResearch/hermes-agent/main/skills/index.json";
    
    private final WebClient webClient;
    private final SkillLoader skillLoader;
    private final SkillWriter skillWriter;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // 本地索引缓存
    private final AtomicReference<List<HubSkill>> cachedIndex = new AtomicReference<>(Collections.emptyList());
    private final AtomicReference<Instant> indexLastUpdated = new AtomicReference<>(Instant.EPOCH);
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    
    public SkillsHubClient(SkillLoader skillLoader, SkillWriter skillWriter) {
        this.webClient = WebClient.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))  // 10MB
            .build();
        this.skillLoader = skillLoader;
        this.skillWriter = skillWriter;
    }
    
    /**
     * 搜索 Hub 上的技能
     */
    public List<HubSkill> search(String query) {
        List<HubSkill> index = fetchIndex();
        
        if (query == null || query.isBlank()) {
            return index.stream()
                .sorted(Comparator.comparingInt(HubSkill::getDownloads).reversed())
                .limit(20)
                .toList();
        }
        
        String lowerQuery = query.toLowerCase();
        return index.stream()
            .filter(s -> s.getName().toLowerCase().contains(lowerQuery) ||
                        s.getDescription().toLowerCase().contains(lowerQuery) ||
                        s.getTags().stream().anyMatch(t -> t.toLowerCase().contains(lowerQuery)))
            .sorted(Comparator.comparingInt(HubSkill::getDownloads).reversed())
            .limit(20)
            .toList();
    }
    
    /**
     * 获取技能详情（预览，不安装）
     */
    public Mono<String> inspect(String skillId) {
        return webClient.get()
            .uri(DEFAULT_HUB_API_URL + "/" + skillId + "/raw")
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(30))
            .doOnError(e -> log.warn("Failed to inspect skill {}: {}", skillId, e.getMessage()));
    }
    
    /**
     * 安装技能
     */
    public Mono<Skill> install(String skillId) {
        return inspect(skillId)
            .map(rawContent -> {
                try {
                    // 写入临时文件解析
                    Path tempFile = Files.createTempFile("skill_", ".md");
                    try {
                        Files.writeString(tempFile, rawContent);
                        Skill skill = skillLoader.load(tempFile, Source.HUB);
                        skill.setSource(Source.HUB);
                        
                        // 保存到正式目录
                        skillWriter.save(skill);
                        
                        log.info("Installed skill: {} v{}", skill.getName(), skill.getVersion());
                        return skill;
                    } finally {
                        Files.deleteIfExists(tempFile);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to install skill: " + skillId, e);
                }
            });
    }
    
    /**
     * 检查已安装技能的更新
     */
    public List<UpdateInfo> checkUpdates(Map<String, Skill> installed) {
        List<HubSkill> index = fetchIndex();
        List<UpdateInfo> updates = new ArrayList<>();
        
        for (HubSkill hubSkill : index) {
            Skill local = installed.get(hubSkill.getName());
            if (local != null && local.getSource() == Source.HUB) {
                if (compareVersions(hubSkill.getVersion(), local.getVersion()) > 0) {
                    updates.add(new UpdateInfo(
                        hubSkill.getName(),
                        local.getVersion(),
                        hubSkill.getVersion(),
                        hubSkill.getChangelog()
                    ));
                }
            }
        }
        
        return updates;
    }
    
    /**
     * 更新技能
     */
    public Mono<Skill> update(String skillId) {
        return install(skillId);  // 覆盖安装
    }
    
    /**
     * 添加第三方技能源
     */
    public void addTap(String repoUrl) {
        Path tapsFile = getTapsFile();
        try {
            List<String> taps = Files.exists(tapsFile) 
                ? Files.readAllLines(tapsFile) 
                : new ArrayList<>();
            
            if (!taps.contains(repoUrl)) {
                taps.add(repoUrl);
                Files.createDirectories(tapsFile.getParent());
                Files.write(tapsFile, taps);
                log.info("Added skill tap: {}", repoUrl);
            }
        } catch (IOException e) {
            log.error("Failed to add tap: {}", repoUrl, e);
        }
    }
    
    /**
     * 获取已添加的第三方源列表
     */
    public List<String> getTaps() {
        Path tapsFile = getTapsFile();
        if (!Files.exists(tapsFile)) {
            return Collections.emptyList();
        }
        try {
            return Files.readAllLines(tapsFile);
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }
    
    // === 私有方法 ===
    
    private List<HubSkill> fetchIndex() {
        // 检查缓存
        Instant lastUpdate = indexLastUpdated.get();
        if (!cachedIndex.get().isEmpty() && 
            Duration.between(lastUpdate, Instant.now()).compareTo(CACHE_TTL) < 0) {
            return cachedIndex.get();
        }
        
        // 从网络获取
        try {
            String json = webClient.get()
                .uri(DEFAULT_HUB_INDEX_URL)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(15))
                .block();
            
            if (json != null) {
                List<HubSkill> index = parseIndex(json);
                cachedIndex.set(index);
                indexLastUpdated.set(Instant.now());
                return index;
            }
        } catch (Exception e) {
            log.warn("Failed to fetch Hub index: {}", e.getMessage());
        }
        
        // 返回缓存（即使过期）或空列表
        return cachedIndex.get();
    }
    
    private List<HubSkill> parseIndex(String json) {
        try {
            // 解析 JSON 格式: {"skills": [...]}
            Map<String, Object> root = objectMapper.readValue(json, Map.class);
            Object skillsObj = root.get("skills");
            
            if (skillsObj instanceof List) {
                List<HubSkill> skills = new ArrayList<>();
                for (Object item : (List<?>) skillsObj) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) item;
                        HubSkill skill = new HubSkill();
                        skill.setId(getString(map, "id", ""));
                        skill.setName(getString(map, "name", ""));
                        skill.setDescription(getString(map, "description", ""));
                        skill.setVersion(getString(map, "version", "1.0.0"));
                        skill.setAuthor(getString(map, "author", "Unknown"));
                        skill.setDownloads(getInt(map, "downloads", 0));
                        skill.setChangelog(getString(map, "changelog", ""));
                        
                        Object tags = map.get("tags");
                        if (tags instanceof List) {
                            skill.setTags(((List<?>) tags).stream()
                                .map(Object::toString)
                                .toList());
                        }
                        
                        skills.add(skill);
                    }
                }
                return skills;
            }
        } catch (Exception e) {
            log.error("Failed to parse Hub index JSON", e);
        }
        return Collections.emptyList();
    }
    
    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
    
    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }
    
    private int compareVersions(String v1, String v2) {
        if (v1 == null || v2 == null) return 0;
        
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int length = Math.max(parts1.length, parts2.length);
        
        for (int i = 0; i < length; i++) {
            int num1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int num2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            if (num1 != num2) return num1 - num2;
        }
        return 0;
    }
    
    private int parseVersionPart(String part) {
        try {
            // 提取数字部分
            String num = part.replaceAll("[^0-9]", "");
            return num.isEmpty() ? 0 : Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    private Path getTapsFile() {
        return skillLoader.getSkillsRoot().resolve("taps.json");
    }
    
    // === 数据类 ===
    
    /**
     * Hub 技能索引条目
     */
    public static class HubSkill {
        private String id;
        private String name;
        private String description;
        private String version;
        private String author;
        private List<String> tags = new ArrayList<>();
        private int downloads;
        private String changelog;
        
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        
        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }
        
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags != null ? tags : new ArrayList<>(); }
        
        public int getDownloads() { return downloads; }
        public void setDownloads(int downloads) { this.downloads = downloads; }
        
        public String getChangelog() { return changelog; }
        public void setChangelog(String changelog) { this.changelog = changelog; }
        
        @Override
        public String toString() {
            return String.format("%s (%s) - %s", name, version, description);
        }
    }
    
    /**
     * 更新信息
     */
    public record UpdateInfo(String name, String currentVersion, String latestVersion, String changelog) {}
}
