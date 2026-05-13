package com.hermes.agent.skills.loader;

import com.hermes.agent.config.AgentConfig;
import com.hermes.agent.skills.model.Skill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillLoader 单元测试
 */
class SkillLoaderTest {

    @TempDir
    Path tempDir;

    private SkillLoader loader;

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
    }

    @Test
    void testLoadSimpleSkill() throws IOException {
        // 创建测试技能
        String skillContent = """
            ---
            name: test-skill
            description: A simple test skill
            version: 1.0.0
            author: Test Author
            ---
            
            # Test Skill
            
            This is a test skill content.
            """;
        
        Path skillDir = tempDir.resolve("custom").resolve("test-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), skillContent);
        
        // 加载技能
        Skill skill = loader.load(skillDir.resolve("SKILL.md"), Skill.Source.CUSTOM);
        
        assertNotNull(skill);
        assertEquals("test-skill", skill.getName());
        assertEquals("A simple test skill", skill.getDescription());
        assertEquals("1.0.0", skill.getVersion());
        assertEquals("Test Author", skill.getAuthor());
        assertEquals(Skill.Source.CUSTOM, skill.getSource());
        assertTrue(skill.getContent().contains("# Test Skill"));
    }

    @Test
    void testLoadSkillWithPrerequisites() throws IOException {
        String skillContent = """
            ---
            name: advanced-skill
            description: Skill with prerequisites
            prerequisites:
              commands: [git, docker]
              env_vars: [API_KEY]
              files: [config.json]
            ---
            
            # Advanced Skill
            """;
        
        Path skillDir = tempDir.resolve("custom").resolve("advanced-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), skillContent);
        
        Skill skill = loader.load(skillDir.resolve("SKILL.md"), Skill.Source.CUSTOM);
        
        assertNotNull(skill);
        assertEquals("advanced-skill", skill.getName());
        assertNotNull(skill.getPrerequisites());
        assertTrue(skill.getPrerequisites().getCommands().contains("git"));
        assertTrue(skill.getPrerequisites().getCommands().contains("docker"));
        assertTrue(skill.getPrerequisites().getEnvVars().contains("API_KEY"));
        assertTrue(skill.getPrerequisites().getFiles().contains("config.json"));
    }

    @Test
    void testLoadSkillWithMetadata() throws IOException {
        String skillContent = """
            ---
            name: meta-skill
            description: Skill with metadata
            metadata:
              hermes:
                tags: [web, api, test]
                related_skills: [http, rest]
              custom:
                key: value
            ---
            
            # Meta Skill
            """;
        
        Path skillDir = tempDir.resolve("custom").resolve("meta-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), skillContent);
        
        Skill skill = loader.load(skillDir.resolve("SKILL.md"), Skill.Source.CUSTOM);
        
        assertNotNull(skill);
        assertEquals("meta-skill", skill.getName());
        assertNotNull(skill.getMetadata());
        assertTrue(skill.getTags().contains("web"));
        assertTrue(skill.getTags().contains("api"));
        assertTrue(skill.getRelatedSkills().contains("http"));
    }

    @Test
    void testLoadSkillWithCRLF() throws IOException {
        // 测试 Windows 换行符
        String skillContent = "---\r\n" +
            "name: crlf-skill\r\n" +
            "description: Skill with CRLF line endings\r\n" +
            "version: 1.0.0\r\n" +
            "---\r\n\r\n" +
            "# CRLF Skill\r\n\r\n" +
            "This skill uses Windows line endings.\r\n";
        
        Path skillDir = tempDir.resolve("custom").resolve("crlf-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), skillContent);
        
        Skill skill = loader.load(skillDir.resolve("SKILL.md"), Skill.Source.CUSTOM);
        
        assertNotNull(skill);
        assertEquals("crlf-skill", skill.getName());
        assertEquals("Skill with CRLF line endings", skill.getDescription());
    }

    @Test
    void testLoadInvalidSkill() throws IOException {
        String invalidContent = "This is not a valid SKILL.md";
        
        Path skillDir = tempDir.resolve("custom").resolve("invalid-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), invalidContent);
        
        assertThrows(IllegalArgumentException.class, () -> {
            loader.load(skillDir.resolve("SKILL.md"), Skill.Source.CUSTOM);
        });
    }

    @Test
    void testLoadAllSkills() throws IOException {
        // 创建多个技能
        createSkill(tempDir, "custom", "skill1", "Skill 1", "First skill");
        createSkill(tempDir, "custom", "skill2", "Skill 2", "Second skill");
        createSkill(tempDir, "hub", "skill3", "Skill 3", "Hub skill");
        
        Map<String, Skill> skills = loader.loadAll();
        
        assertEquals(3, skills.size());
        assertTrue(skills.containsKey("skill1"));
        assertTrue(skills.containsKey("skill2"));
        assertTrue(skills.containsKey("skill3"));
    }

    @Test
    void testPlatformCompatibility() throws IOException {
        String skillContent = """
            ---
            name: platform-skill
            description: Platform-specific skill
            platforms: [linux, macos]
            ---
            
            # Platform Skill
            """;
        
        Path skillDir = tempDir.resolve("custom").resolve("platform-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), skillContent);
        
        Skill skill = loader.load(skillDir.resolve("SKILL.md"), Skill.Source.CUSTOM);
        
        assertNotNull(skill);
        assertEquals(2, skill.getPlatforms().size());
        assertTrue(skill.getPlatforms().contains("linux"));
        assertTrue(skill.getPlatforms().contains("macos"));
    }

    // 辅助方法
    private void createSkill(Path root, String source, String name, String displayName, String desc) throws IOException {
        String content = String.format("""
            ---
            name: %s
            description: %s
            version: 1.0.0
            ---
            
            # %s
            """, name, desc, displayName);
        
        Path skillDir = root.resolve(source).resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), content);
    }
}
