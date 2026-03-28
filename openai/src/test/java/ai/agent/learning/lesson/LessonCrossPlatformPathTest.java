package ai.agent.learning.lesson;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LessonCrossPlatformPathTest {

    static Stream<Class<?>> resolverClasses() {
        return Stream.of(
                Lesson2RunSimple.class,
                Lesson3RunSimple.class,
                Lesson4RunSimple.class,
                Lesson5RunSimple.class,
                Lesson6RunSimple.class,
                Lesson7RunSimple.class,
                Lesson8RunSimple.class,
                Lesson9RunSimple.class,
                Lesson10RunSimple.class
        );
    }

    @ParameterizedTest
    @MethodSource("resolverClasses")
    void resolvesWorkspaceAbsolutePathOnWindowsHosts(Class<?> lessonClass) throws Exception {
        Path workDir = Paths.get("D:/code/ai-agent-learning/openai").normalize();
        Path resolved = invokeResolveWorkspacePath(
                lessonClass,
                workDir,
                "D:/code/ai-agent-learning/openai/src/main/java/App.java",
                true
        ).normalize();

        assertEquals(
                workDir.resolve("src/main/java/App.java").normalize(),
                resolved
        );
    }

    @ParameterizedTest
    @MethodSource("resolverClasses")
    void translatesWindowsAbsolutePathOnLinuxHosts(Class<?> lessonClass) throws Exception {
        Path workDir = Paths.get("/mnt/d/code/ai-agent-learning/openai").normalize();
        Path resolved = invokeResolveWorkspacePath(
                lessonClass,
                workDir,
                "D:\\code\\ai-agent-learning\\openai\\src\\main\\java\\App.java",
                false
        ).normalize();

        assertEquals(
                workDir.resolve("src/main/java/App.java").normalize(),
                resolved
        );
    }

    @Test
    void lesson10CoversDelegatedLessons() throws Exception {
        Path workDir = Paths.get("/mnt/d/code/ai-agent-learning/openai").normalize();
        Path resolved = invokeResolveWorkspacePath(
                Lesson10RunSimple.class,
                workDir,
                "D:\\code\\ai-agent-learning\\openai\\pom.xml",
                false
        ).normalize();

        assertEquals(workDir.resolve("pom.xml").normalize(), resolved);
    }

    private static Path invokeResolveWorkspacePath(Class<?> lessonClass, Path workDir, String inputPath, boolean windows) throws Exception {
        Method method = lessonClass.getDeclaredMethod("resolveWorkspacePath", Path.class, String.class, boolean.class);
        method.setAccessible(true);
        return (Path) method.invoke(null, workDir, inputPath, windows);
    }
}