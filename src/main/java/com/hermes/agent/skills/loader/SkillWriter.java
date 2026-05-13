package com.hermes.agent.skills.loader;

import com.hermes.agent.skills.model.Skill;
import com.hermes.agent.skills.model.Skill.Prerequisites;
import com.hermes.agent.skills.model.Skill.Source;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 技能写入器 - 序列化 Skill 为 SKILL.md 格式
 */
@Component
public class SkillWriter {
    
    private static final Logger log = LoggerFactory.getLogger(SkillWriter.class);
    
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final Path skillsRoot;
    
    public SkillWriter(SkillLoader skillLoader) {
        this.skillsRoot = skillLoader.getSkillsRoot();
    }
    
    /**
     * 保存技能到文件系统
     * 
     * @param skill 技能对象
     * @return 保存的文件路径
     */
    public Path save(Skill skill) throws IOException {
        // 确定目标目录
        String sourceDir = skill.getSource() != null 
            ? skill.getSource().getDir() 
            : Source.CUSTOM.getDir();
        
        // 技能名称安全处理
        String safeName = sanitizeSkillName(skill.getName());
        
        Path skillDir = skillsRoot.resolve(sourceDir).resolve(safeName);
        Files.createDirectories(skillDir);
        
        Path skillFile = skillDir.resolve("SKILL.md");
        
        // 序列化为 SKILL.md 格式
        String content = serialize(skill);
        
        // 写入文件
        Files.writeString(skillFile, content, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, 
            StandardOpenOption.TRUNCATE_EXISTING);
        
        log.info("Saved skill: {} -> {}", skill.getName(), skillFile);
        
        // 更新文件路径和时间戳
        skill.setFilePath(skillFile.toString());
        skill.setUpdatedAt(Instant.now());
        
        return skillFile;
    }
    
    /**
     * 序列化 Skill 为 SKILL.md 格式
     */
    public String serialize(Skill skill) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        
        // 构建 frontmatter Map
        Map<String, Object> frontmatter = new LinkedHashMap<>();
        
        // 必需字段
        frontmatter.put("name", skill.getName());
        frontmatter.put("description", skill.getDescription());
        frontmatter.put("version", skill.getVersion() != null ? skill.getVersion() : "1.0.0");
        frontmatter.put("author", skill.getAuthor() != null ? skill.getAuthor() : "Unknown");
        frontmatter.put("license", skill.getLicense() != null ? skill.getLicense() : "MIT");
        
        // 平台列表（非默认时添加）
        List<String> defaultPlatforms = Arrays.asList("linux", "macos", "windows");
        if (skill.getPlatforms() != null && !skill.getPlatforms().equals(defaultPlatforms)) {
            frontmatter.put("platforms", skill.getPlatforms());
        }
        
        // 元数据
        if (skill.getMetadata() != null && !skill.getMetadata().isEmpty()) {
            frontmatter.put("metadata", skill.getMetadata());
        }
        
        // 依赖声明
        Prerequisites prereq = skill.getPrerequisites();
        if (prereq != null && !prereq.isEmpty()) {
            Map<String, Object> prereqMap = new LinkedHashMap<>();
            
            if (!prereq.getCommands().isEmpty()) {
                prereqMap.put("commands", prereq.getCommands());
            }
            if (!prereq.getEnvVars().isEmpty()) {
                prereqMap.put("env_vars", prereq.getEnvVars());
            }
            if (!prereq.getFiles().isEmpty()) {
                prereqMap.put("files", prereq.getFiles());
            }
            
            if (!prereqMap.isEmpty()) {
                frontmatter.put("prerequisites", prereqMap);
            }
        }
        
        // 使用 Jackson YAML mapper 序列化
        try {
            String yaml = yamlMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(frontmatter);
            // Jackson 会在开头加 "---\n"，我们需要去掉
            if (yaml.startsWith("---\n")) {
                yaml = yaml.substring(4);
            }
            sb.append(yaml);
        } catch (JsonProcessingException e) {
            log.warn("YAML serialization failed, using fallback: {}", e.getMessage());
            sb.append(toYamlFallback(frontmatter));
        }
        
        sb.append("---\n\n");
        
        // Markdown 内容
        String content = skill.getContent();
        if (content != null && !content.isBlank()) {
            sb.append(content);
            if (!content.endsWith("\n")) {
                sb.append("\n");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 删除技能
     */
    public void delete(Skill skill) throws IOException {
        String sourceDir = skill.getSource() != null 
            ? skill.getSource().getDir() 
            : Source.CUSTOM.getDir();
        
        String safeName = sanitizeSkillName(skill.getName());
        Path skillDir = skillsRoot.resolve(sourceDir).resolve(safeName);
        
        if (!Files.exists(skillDir)) {
            log.warn("Skill directory not found: {}", skillDir);
            return;
        }
        
        // 递归删除目录
        Files.walk(skillDir)
            .sorted(Comparator.reverseOrder())
            .forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.warn("Failed to delete: {}", p);
                }
            });
        
        log.info("Deleted skill: {}", skill.getName());
    }
    
    /**
     * 检查技能是否已存在
     */
    public boolean exists(String skillName, Source source) {
        String safeName = sanitizeSkillName(skillName);
        Path skillDir = skillsRoot.resolve(source.getDir()).resolve(safeName);
        return Files.exists(skillDir.resolve("SKILL.md")) || 
               Files.exists(skillDir.resolve("skill.md"));
    }
    
    // === 私有辅助方法 ===
    
    private String sanitizeSkillName(String name) {
        if (name == null || name.isBlank()) {
            return "unnamed_skill_" + System.currentTimeMillis();
        }
        // 替换不安全字符为下划线
        return name.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5-]", "_")
                   .replaceAll("_+", "_")
                   .replaceAll("^_|_$", "");
    }
    
    /**
     * Fallback YAML 序列化器（当 Jackson 失败时）
     */
    private String toYamlFallback(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            sb.append(entry.getKey()).append(": ");
            Object value = entry.getValue();
            
            if (value instanceof List) {
                sb.append("\n");
                for (Object item : (List<?>) value) {
                    sb.append("  - ").append(quoteIfNeeded(item.toString())).append("\n");
                }
            } else if (value instanceof Map) {
                sb.append("\n");
                @SuppressWarnings("unchecked")
                Map<String, Object> subMap = (Map<String, Object>) value;
                for (Map.Entry<String, Object> sub : subMap.entrySet()) {
                    sb.append("  ").append(sub.getKey()).append(": ");
                    Object subValue = sub.getValue();
                    if (subValue instanceof List) {
                        sb.append("\n");
                        for (Object item : (List<?>) subValue) {
                            sb.append("    - ").append(quoteIfNeeded(item.toString())).append("\n");
                        }
                    } else {
                        sb.append(quoteIfNeeded(subValue.toString())).append("\n");
                    }
                }
            } else {
                sb.append(quoteIfNeeded(value.toString())).append("\n");
            }
        }
        return sb.toString();
    }
    
    private String quoteIfNeeded(String value) {
        if (value == null) return "null";
        if (value.contains(":") || value.contains("#") || value.contains("\n") ||
            value.startsWith("[") || value.startsWith("{") || value.startsWith("\"")) {
            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
        return value;
    }
    
    public Path getSkillsRoot() {
        return skillsRoot;
    }
}
