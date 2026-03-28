package ai.agent.learning.lesson;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Lesson6RunSimpleTest {

    @Test
    void buildShellCommandUsesBashOnLinux() {
        assertEquals(
                List.of("bash", "-lc", "ls -R"),
                Lesson6RunSimple.buildShellCommand("ls -R", false)
        );
    }

    @Test
    void resolveWorkspacePathKeepsAbsoluteWorkspacePath() {
        Path workDir = Paths.get("D:/code/ai-agent-learning/openai").normalize();
        Path resolved = Lesson6RunSimple.resolveWorkspacePath(
                workDir,
                "D:/code/ai-agent-learning/openai/src/main/java/App.java",
                true
        ).normalize();

        assertEquals(
                workDir.resolve("src/main/java/App.java").normalize(),
                resolved
        );
    }

    @Test
    void resolveWorkspacePathTranslatesWindowsPathForLinuxWorkspace() {
        Path workDir = Paths.get("/mnt/d/code/ai-agent-learning/openai").normalize();
        Path resolved = Lesson6RunSimple.resolveWorkspacePath(
                workDir,
                "D:\\code\\ai-agent-learning\\openai\\src\\main\\java\\App.java",
                false
        ).normalize();

        assertEquals(
                workDir.resolve("src/main/java/App.java").normalize(),
                resolved
        );
    }
}