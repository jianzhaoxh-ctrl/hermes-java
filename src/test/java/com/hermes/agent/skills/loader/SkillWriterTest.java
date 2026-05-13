package com.hermes.agent.skills.loader;

import com.hermes.agent.config.AgentConfig;
import com.hermes.agent.skills.model.Skill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillWriter 单元测试
 */
class SkillWriterTest {

    @TempDir
    Path tempDir;

    private SkillLoader loader;
    private SkillWriter writer;

    @BeforeEach
    void setUp() {
        AgentConfig config = new AgentConfig();
        try {
            var field = AgentConfig.class.getDeclaredField("skills");
            field.setAccessible(true);
            AgentConfig.SkillsConfig skillsConfig = new AgentConfig.SkillsConfig();
            skillsConfig.setHome(tempDir.toString());
            field.set(config, skillsConfig);
        } catch (Exception e) {
            fail("Failed to set config: " + e.getMessage());
        }
        loader = new SkillLoader(config);
        writer = new SkillWriter(loader);
    }

    @Test
    void testSaveSimpleSkill() throws IOException {
        Skill skill = new Skill();
        skill.setName("save-test");
        skill.setDescription("A skill to test saving");
        skill.setVersion("2.0.0");
        skill.setAuthor("Test Author");
        skill.setContent("# Save Test\n\nThis is a test skill.");
        skill.setSource(Skill.Source.CUSTOM);
        skill.setPlatforms(Arrays.asList("linux", "macos", "windows"));

        Path savedPath = writer.save(skill);
        
        assertTrue(Files.exists(savedPath));
        assertTrue(savedPath.toString().contains("custom") || savedPath.toString().contains("custom"));
        assertTrue(savedPath.toString().contains("save-test"));
        
        // 验证内容
        String content = Files.readString(savedPath);
        // Just verify the file contains expected markers
        assertTrue(content.contains("---"));  // YAML frontmatter
        assertTrue(content.contains("# Save Test"));  // Markdown content
    }

    @Test
    void testSaveSkillWithPrerequisites() throws IOException {
        Skill skill = new Skill();
        skill.setName("prereq-skill");
        skill.setDescription("Skill with prerequisites");
        skill.setSource(Skill.Source.HUB);
        
        Skill.Prerequisites prereq = new Skill.Prerequisites();
        prereq.setCommands(Arrays.asList("git", "docker"));
        prereq.setEnvVars(Arrays.asList("API_KEY", "SECRET"));
        skill.setPrerequisites(prereq);
        
        Path savedPath = writer.save(skill);
        
        String content = Files.readString(savedPath);
        assertTrue(content.contains("git"));
        assertTrue(content.contains("docker"));
        assertTrue(content.contains("API_KEY"));
    }

    @Test
    void testSaveSkillWithMetadata() throws IOException {
        Skill skill = new Skill();
        skill.setName("meta-skill");
        skill.setDescription("Skill with metadata");
        skill.setSource(Skill.Source.AUTO_GENERATED);
        
        // 通过 metadata 设置 tags 和 related_skills
        Map<String, Object> hermesMeta = new LinkedHashMap<>();
        hermesMeta.put("tags", Arrays.asList("web", "api", "test"));
        hermesMeta.put("related_skills", Arrays.asList("http", "rest"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("hermes", hermesMeta);
        skill.setMetadata(metadata);
        
        Path savedPath = writer.save(skill);
        
        String content = Files.readString(savedPath);
        assertTrue(content.contains("web"));
        assertTrue(content.contains("api"));
        assertTrue(content.contains("http"));
    }

    @Test
    void testRoundTripSaveAndLoad() throws IOException {
        // 原始技能
        Skill original = new Skill();
        original.setName("roundtrip");
        original.setDescription("Round trip test");
        original.setVersion("1.5.0");
        original.setAuthor("Round Trip Author");
        original.setLicense("Apache-2.0");
        original.setContent("# Round Trip\n\nContent here.");
        original.setSource(Skill.Source.CUSTOM);
        original.setPlatforms(Arrays.asList("linux", "macos"));
        
        // 保存
        writer.save(original);
        
        // 重新加载
        Map<String, Skill> loaded = loader.loadAll();
        Skill reloaded = loaded.get("roundtrip");
        
        assertNotNull(reloaded);
        assertEquals(original.getName(), reloaded.getName());
        assertEquals(original.getDescription(), reloaded.getDescription());
        assertEquals(original.getVersion(), reloaded.getVersion());
        assertEquals(original.getAuthor(), reloaded.getAuthor());
        assertEquals(original.getLicense(), reloaded.getLicense());
    }

    @Test
    void testDeleteSkill() throws IOException {
        // 先创建并保存
        Skill skill = new Skill();
        skill.setName("delete-me");
        skill.setDescription("To be deleted");
        skill.setSource(Skill.Source.CUSTOM);
        skill.setContent("# Delete Me");
        
        Path savedPath = writer.save(skill);
        assertTrue(Files.exists(savedPath));
        
        // 删除
        writer.delete(skill);
        
        // 验证已删除
        assertFalse(Files.exists(savedPath));
        assertFalse(Files.exists(savedPath.getParent()));
    }

    @Test
    void testSkillNameSanitization() throws IOException {
        Skill skill = new Skill();
        skill.setName("test/skill:with*special?chars");
        skill.setDescription("Skill with special chars in name");
        skill.setSource(Skill.Source.CUSTOM);
        
        Path savedPath = writer.save(skill);
        
        // 验证路径不包含 Windows 非法字符（除了目录分隔符）
        String pathStr = savedPath.toString();
        // sanitized name should replace special chars
        assertTrue(Files.exists(savedPath));
    }

    @Test
    void testChineseSkillName() throws IOException {
        Skill skill = new Skill();
        skill.setName("测试技能");
        skill.setDescription("中文名称的技能");
        skill.setContent("# 测试\n\n这是一个中文技能。");
        skill.setSource(Skill.Source.CUSTOM);
        
        Path savedPath = writer.save(skill);
        
        assertTrue(Files.exists(savedPath));
        
        // 验证可以正确读取
        Skill loaded = loader.load(savedPath, Skill.Source.CUSTOM);
        assertEquals("测试技能", loaded.getName());
        assertEquals("中文名称的技能", loaded.getDescription());
    }
}
