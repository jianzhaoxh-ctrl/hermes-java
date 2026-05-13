package com.hermes.agent.skills.loader;

import com.hermes.agent.config.AgentConfig;
import com.hermes.agent.skills.model.Skill;
import com.hermes.agent.skills.model.Skill.Prerequisites;
import com.hermes.agent.skills.model.Skill.Source;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能加载器 - 解析 SKILL.md 文件
 */
@Component
public class SkillLoader {
    
    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);
    
    /** SKILL.md 格式：YAML frontmatter + Markdown */
    // 支持 LF、CRLF、CR 三种换行符
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
        "^---\\s*[\\r\\n]+([\\s\\S]*?)[\\r\\n]+---\\s*[\\r\\n]+([\\s\\S]*)$"
    );
    
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final Path skillsRoot;
    
    public SkillLoader(AgentConfig config) {
        String skillsHome = config.getSkillsHome();
        if (skillsHome == null || skillsHome.isBlank()) {
            skillsHome = System.getProperty("user.home") + "/.hermes/skills";
        }
        this.skillsRoot = Paths.get(skillsHome);
        log.info("Skills root: {}", skillsRoot);
    }
    
    /**
     * 扫描所有技能目录，加载技能
     */
    public Map<String, Skill> loadAll() {
        Map<String, Skill> skills = new LinkedHashMap<>();
        
        for (Source source : Source.values()) {
            Path sourceDir = skillsRoot.resolve(source.getDir());
            if (!Files.exists(sourceDir)) {
                log.debug("Source directory not found: {}", sourceDir);
                continue;
            }
            
            log.info("Scanning skills from: {}", sourceDir);
            int loaded = 0;
            
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(sourceDir)) {
                for (Path skillDir : stream) {
                    if (!Files.isDirectory(skillDir)) continue;
                    
                    Path skillFile = skillDir.resolve("SKILL.md");
                    if (!Files.exists(skillFile)) {
                        // 尝试小写文件名
                        skillFile = skillDir.resolve("skill.md");
                    }
                    
                    if (Files.exists(skillFile)) {
                        try {
                            Skill skill = load(skillFile, source);
                            skills.put(skill.getName(), skill);
                            loaded++;
                        } catch (Exception e) {
                            log.warn("Failed to load skill: {} - {}", skillDir.getFileName(), e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                log.error("Error scanning skills directory: {}", sourceDir, e);
            }
            
            log.info("Loaded {} skills from {}", loaded, source.getDir());
        }
        
        return skills;
    }
    
    /**
     * 加载单个 SKILL.md 文件
     */
    public Skill load(Path skillFile, Source source) throws IOException {
        String rawContent = Files.readString(skillFile, StandardCharsets.UTF_8);
        
        // 解析 YAML frontmatter
        Matcher matcher = FRONTMATTER_PATTERN.matcher(rawContent);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid SKILL.md format: missing YAML frontmatter in " + skillFile);
        }
        
        String frontmatterYaml = matcher.group(1);
        String content = matcher.group(2).trim();
        
        // 解析 YAML
        @SuppressWarnings("unchecked")
        Map<String, Object> frontmatter = yamlMapper.readValue(frontmatterYaml, Map.class);
        
        Skill skill = new Skill();
        
        // 基础字段
        skill.setName(getString(frontmatter, "name"));
        skill.setDescription(getString(frontmatter, "description"));
        skill.setVersion(getString(frontmatter, "version", "1.0.0"));
        skill.setAuthor(getString(frontmatter, "author", "Unknown"));
        skill.setLicense(getString(frontmatter, "license", "MIT"));
        
        // 平台列表
        Object platforms = frontmatter.get("platforms");
        if (platforms instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> platformList = ((List<?>) platforms).stream()
                .map(Object::toString)
                .toList();
            skill.setPlatforms(platformList);
        }
        
        // 元数据
        Object metadata = frontmatter.get("metadata");
        if (metadata instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> metaMap = (Map<String, Object>) metadata;
            skill.setMetadata(metaMap);
        }
        
        // 依赖声明
        Object prereq = frontmatter.get("prerequisites");
        if (prereq instanceof Map) {
            Prerequisites prerequisites = new Prerequisites();
            @SuppressWarnings("unchecked")
            Map<String, Object> prereqMap = (Map<String, Object>) prereq;
            
            Object commands = prereqMap.get("commands");
            if (commands instanceof List) {
                prerequisites.setCommands(((List<?>) commands).stream()
                    .map(Object::toString)
                    .toList());
            }
            
            Object envVars = prereqMap.get("env_vars");
            if (envVars instanceof List) {
                prerequisites.setEnvVars(((List<?>) envVars).stream()
                    .map(Object::toString)
                    .toList());
            }
            
            Object files = prereqMap.get("files");
            if (files instanceof List) {
                prerequisites.setFiles(((List<?>) files).stream()
                    .map(Object::toString)
                    .toList());
            }
            
            skill.setPrerequisites(prerequisites);
        }
        
        // 内容和来源
        skill.setContent(content);
        skill.setRawContent(rawContent);
        skill.setSource(source);
        skill.setFilePath(skillFile.toString());
        
        // 文件时间戳
        try {
            skill.setUpdatedAt(Files.getLastModifiedTime(skillFile).toInstant());
        } catch (IOException e) {
            skill.setUpdatedAt(Instant.now());
        }
        
        return skill;
    }
    
    /**
     * 检查平台兼容性
     */
    public boolean isPlatformCompatible(Skill skill) {
        if (skill.getPlatforms() == null || skill.getPlatforms().isEmpty()) {
            return true;
        }
        
        String currentOs = detectOs();
        return skill.getPlatforms().stream()
            .anyMatch(p -> p.equalsIgnoreCase(currentOs));
    }
    
    /**
     * 检查依赖是否满足
     */
    public List<String> checkPrerequisites(Skill skill) {
        List<String> missing = new ArrayList<>();
        
        Prerequisites prereq = skill.getPrerequisites();
        if (prereq == null) {
            return missing;
        }
        
        // 检查命令
        for (String cmd : prereq.getCommands()) {
            if (!isCommandAvailable(cmd)) {
                missing.add("command: " + cmd);
            }
        }
        
        // 检查环境变量
        for (String env : prereq.getEnvVars()) {
            if (System.getenv(env) == null && System.getProperty(env) == null) {
                missing.add("env_var: " + env);
            }
        }
        
        // 检查文件
        for (String file : prereq.getFiles()) {
            String expandedPath = expandPath(file);
            if (!Files.exists(Paths.get(expandedPath))) {
                missing.add("file: " + file);
            }
        }
        
        return missing;
    }
    
    // === 私有辅助方法 ===
    
    private String getString(Map<String, Object> map, String key) {
        return getString(map, key, null);
    }
    
    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
    
    private String detectOs() {
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "macos";
        return "linux";
    }
    
    private boolean isCommandAvailable(String cmd) {
        try {
            String[] checkCmd = detectOs().equals("windows") 
                ? new String[]{"where", cmd}
                : new String[]{"which", cmd};
            
            ProcessBuilder pb = new ProcessBuilder(checkCmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean completed = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return completed && p.waitFor() == 0;
        } catch (Exception e) {
            log.debug("Command check failed for {}: {}", cmd, e.getMessage());
            return false;
        }
    }
    
    private String expandPath(String path) {
        if (path == null) return "";
        return path.replace("~", System.getProperty("user.home"))
                   .replace("$HOME", System.getProperty("user.home"));
    }
    
    public Path getSkillsRoot() {
        return skillsRoot;
    }
}
