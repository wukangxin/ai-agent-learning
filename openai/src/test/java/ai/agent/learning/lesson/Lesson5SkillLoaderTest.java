package ai.agent.learning.lesson;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lesson5RunSimple - SkillLoader 单元测试
 */
class Lesson5SkillLoaderTest {

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setupSkills() throws Exception {
        // 创建测试skill目录和文件
        Path skill1Dir = tempDir.resolve("skill1");
        Files.createDirectories(skill1Dir);
        Files.writeString(skill1Dir.resolve("SKILL.md"),
                "---\n" +
                "name: test-skill\n" +
                "description: A test skill for unit testing\n" +
                "tags: test, demo\n" +
                "---\n" +
                "# Test Skill\n\n" +
                "This is the body of the test skill.\n" +
                "It contains instructions for the agent.");

        Path skill2Dir = tempDir.resolve("skill2");
        Files.createDirectories(skill2Dir);
        Files.writeString(skill2Dir.resolve("SKILL.md"),
                "---\n" +
                "name: another-skill\n" +
                "description: Another skill\n" +
                "---\n" +
                "Another skill content.");

        // Skill without frontmatter (uses directory name)
        Path skill3Dir = tempDir.resolve("default-skill");
        Files.createDirectories(skill3Dir);
        Files.writeString(skill3Dir.resolve("SKILL.md"),
                "This skill has no frontmatter.");
    }

    @Test
    @DisplayName("测试加载skills - 获取描述列表")
    void testGetDescriptions() {
        Lesson5RunSimple.SkillLoader loader = new Lesson5RunSimple.SkillLoader(tempDir);
        String descriptions = loader.getDescriptions();

        assertTrue(descriptions.contains("test-skill"));
        assertTrue(descriptions.contains("A test skill for unit testing"));
        assertTrue(descriptions.contains("another-skill"));
        assertTrue(descriptions.contains("default-skill"));
    }

    @Test
    @DisplayName("测试获取skill内容")
    void testGetContent() {
        Lesson5RunSimple.SkillLoader loader = new Lesson5RunSimple.SkillLoader(tempDir);
        String content = loader.getContent("test-skill");

        assertTrue(content.contains("<skill name=\"test-skill\">"));
        assertTrue(content.contains("# Test Skill"));
        assertTrue(content.contains("This is the body of the test skill"));
    }

    @Test
    @DisplayName("测试获取不存在的skill")
    void testGetNonExistentSkill() {
        Lesson5RunSimple.SkillLoader loader = new Lesson5RunSimple.SkillLoader(tempDir);
        String content = loader.getContent("nonexistent");

        assertTrue(content.contains("Error: Unknown skill"));
        assertTrue(content.contains("Available:"));
    }

    @Test
    @DisplayName("测试skill元数据解析")
    void testMetadataParsing() {
        Lesson5RunSimple.SkillLoader loader = new Lesson5RunSimple.SkillLoader(tempDir);
        String descriptions = loader.getDescriptions();

        // 验证tags被解析
        assertTrue(descriptions.contains("[test, demo]"));
    }

    @Test
    @DisplayName("测试无frontmatter的skill使用目录名")
    void testDefaultSkillName() {
        Lesson5RunSimple.SkillLoader loader = new Lesson5RunSimple.SkillLoader(tempDir);
        String content = loader.getContent("default-skill");

        assertTrue(content.contains("default-skill"));
        assertTrue(content.contains("This skill has no frontmatter"));
    }

    @Test
    @DisplayName("测试空目录返回无skills")
    void testEmptyDirectory() {
        Lesson5RunSimple.SkillLoader loader = new Lesson5RunSimple.SkillLoader(tempDir.resolve("nonexistent"));
        String descriptions = loader.getDescriptions();

        assertEquals("(no skills available)", descriptions);
    }
}
