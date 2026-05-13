package com.hermes.agent.skills.model;

import java.time.Instant;
import java.util.*;

/**
 * 技能数据模型 - 对应 SKILL.md 文件
 */
public class Skill {
    
    // === YAML frontmatter 字段 ===
    private String name;
    private String description;
    private String version;
    private String author;
    private String license;
    private List<String> platforms;
    private Map<String, Object> metadata;
    private Prerequisites prerequisites;
    
    // === Markdown 内容 ===
    private String content;       // 解析后的 Markdown 正文
    private String rawContent;    // 原始 SKILL.md 内容
    
    // === 运行时字段 ===
    private Instant createdAt;
    private Instant updatedAt;
    private Source source;        // 来源：hub/custom/auto-generated
    private String filePath;      // SKILL.md 文件路径
    
    /**
     * 技能来源枚举
     */
    public enum Source {
        HUB("hub"),
        CUSTOM("custom"),
        AUTO_GENERATED("auto-generated");
        
        private final String dir;
        
        Source(String dir) {
            this.dir = dir;
        }
        
        public String getDir() {
            return dir;
        }
        
        public static Source fromString(String s) {
            for (Source source : values()) {
                if (source.dir.equalsIgnoreCase(s)) {
                    return source;
                }
            }
            return CUSTOM;
        }
    }
    
    /**
     * 依赖声明
     */
    public static class Prerequisites {
        private List<String> commands;
        private List<String> envVars;
        private List<String> files;
        
        public Prerequisites() {
            this.commands = new ArrayList<>();
            this.envVars = new ArrayList<>();
            this.files = new ArrayList<>();
        }
        
        // Getters and Setters
        public List<String> getCommands() { return commands; }
        public void setCommands(List<String> commands) { this.commands = commands != null ? commands : new ArrayList<>(); }
        
        public List<String> getEnvVars() { return envVars; }
        public void setEnvVars(List<String> envVars) { this.envVars = envVars != null ? envVars : new ArrayList<>(); }
        
        public List<String> getFiles() { return files; }
        public void setFiles(List<String> files) { this.files = files != null ? files : new ArrayList<>(); }
        
        public boolean isEmpty() {
            return (commands == null || commands.isEmpty()) &&
                   (envVars == null || envVars.isEmpty()) &&
                   (files == null || files.isEmpty());
        }
    }
    
    // === 构造函数 ===
    
    public Skill() {
        this.version = "1.0.0";
        this.license = "MIT";
        this.platforms = Arrays.asList("linux", "macos", "windows");
        this.metadata = new HashMap<>();
        this.prerequisites = new Prerequisites();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }
    
    public Skill(String name, String description, String content) {
        this();
        this.name = name;
        this.description = description;
        this.content = content;
    }
    
    // === 业务方法 ===
    
    /**
     * 检查平台兼容性
     */
    public boolean isPlatformCompatible(String currentOs) {
        if (platforms == null || platforms.isEmpty()) {
            return true;
        }
        return platforms.stream()
            .anyMatch(p -> p.equalsIgnoreCase(currentOs));
    }
    
    /**
     * 获取标签列表
     */
    @SuppressWarnings("unchecked")
    public List<String> getTags() {
        if (metadata == null || !metadata.containsKey("hermes")) {
            return Collections.emptyList();
        }
        Object hermesMeta = metadata.get("hermes");
        if (!(hermesMeta instanceof Map)) {
            return Collections.emptyList();
        }
        Object tags = ((Map<String, Object>) hermesMeta).get("tags");
        if (tags instanceof List) {
            return ((List<?>) tags).stream()
                .map(Object::toString)
                .toList();
        }
        return Collections.emptyList();
    }
    
    /**
     * 获取关联技能
     */
    @SuppressWarnings("unchecked")
    public List<String> getRelatedSkills() {
        if (metadata == null || !metadata.containsKey("hermes")) {
            return Collections.emptyList();
        }
        Object hermesMeta = metadata.get("hermes");
        if (!(hermesMeta instanceof Map)) {
            return Collections.emptyList();
        }
        Object related = ((Map<String, Object>) hermesMeta).get("related_skills");
        if (related instanceof List) {
            return ((List<?>) related).stream()
                .map(Object::toString)
                .toList();
        }
        return Collections.emptyList();
    }
    
    // === Getters and Setters ===
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    
    public String getLicense() { return license; }
    public void setLicense(String license) { this.license = license; }
    
    public List<String> getPlatforms() { return platforms; }
    public void setPlatforms(List<String> platforms) { this.platforms = platforms; }
    
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    
    public Prerequisites getPrerequisites() { return prerequisites; }
    public void setPrerequisites(Prerequisites prerequisites) { this.prerequisites = prerequisites; }
    
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    
    public String getRawContent() { return rawContent; }
    public void setRawContent(String rawContent) { this.rawContent = rawContent; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    
    public void setSource(String source) { this.source = Source.fromString(source); }
    
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    
    @Override
    public String toString() {
        return String.format("Skill{name='%s', version='%s', source=%s}", name, version, source);
    }
}
