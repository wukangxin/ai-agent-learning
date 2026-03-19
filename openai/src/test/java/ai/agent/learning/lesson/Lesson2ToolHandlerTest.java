package ai.agent.learning.lesson;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lesson2RunSimple - 工具函数单元测试
 */
class Lesson2ToolHandlerTest {

    @TempDir
    Path tempDir;

    private Lesson2RunSimple lesson;
    private Path workDir;

    @BeforeEach
    void setUp() throws Exception {
        lesson = new Lesson2RunSimple();

        // 设置workDir
        workDir = tempDir;
        java.lang.reflect.Field workDirField = Lesson2RunSimple.class.getDeclaredField("workDir");
        workDirField.setAccessible(true);
        workDirField.set(lesson, workDir);
    }

    @Test
    @DisplayName("测试bash工具 - 安全命令")
    void testBashSafeCommand() throws Exception {
        Method runBash = Lesson2RunSimple.class.getDeclaredMethod("runBash", String.class);
        runBash.setAccessible(true);

        String result = (String) runBash.invoke(lesson, "echo hello");
        assertTrue(result.contains("hello") || result.equals("(no output)"));
    }

    @Test
    @DisplayName("测试bash工具 - 危险命令被阻止")
    void testBashDangerousCommand() throws Exception {
        Method runBash = Lesson2RunSimple.class.getDeclaredMethod("runBash", String.class);
        runBash.setAccessible(true);

        String result = (String) runBash.invoke(lesson, "rm -rf /");
        assertTrue(result.contains("Dangerous command blocked"));
    }

    @Test
    @DisplayName("测试bash工具 - sudo被阻止")
    void testBashSudoBlocked() throws Exception {
        Method runBash = Lesson2RunSimple.class.getDeclaredMethod("runBash", String.class);
        runBash.setAccessible(true);

        String result = (String) runBash.invoke(lesson, "sudo apt update");
        assertTrue(result.contains("Dangerous command blocked"));
    }

    @Test
    @DisplayName("测试read_file工具 - 读取存在的文件")
    void testReadExistingFile() throws Exception {
        Path testFile = workDir.resolve("test.txt");
        Files.writeString(testFile, "Hello World\nLine 2");

        Method runRead = Lesson2RunSimple.class.getDeclaredMethod("runRead", String.class, Integer.class);
        runRead.setAccessible(true);

        String result = (String) runRead.invoke(lesson, "test.txt", null);
        assertTrue(result.contains("Hello World"));
        assertTrue(result.contains("Line 2"));
    }

    @Test
    @DisplayName("测试read_file工具 - 读取带行数限制")
    void testReadWithLimit() throws Exception {
        Path testFile = workDir.resolve("test.txt");
        Files.writeString(testFile, "Line 1\nLine 2\nLine 3\nLine 4\nLine 5");

        Method runRead = Lesson2RunSimple.class.getDeclaredMethod("runRead", String.class, Integer.class);
        runRead.setAccessible(true);

        String result = (String) runRead.invoke(lesson, "test.txt", 2);
        assertTrue(result.contains("Line 1"));
        assertTrue(result.contains("Line 2"));
        assertFalse(result.contains("Line 5"));
    }

    @Test
    @DisplayName("测试read_file工具 - 读取不存在的文件")
    void testReadNonExistentFile() throws Exception {
        Method runRead = Lesson2RunSimple.class.getDeclaredMethod("runRead", String.class, Integer.class);
        runRead.setAccessible(true);

        String result = (String) runRead.invoke(lesson, "nonexistent.txt", null);
        assertTrue(result.contains("Error"));
    }

    @Test
    @DisplayName("测试read_file工具 - 路径逃逸防护")
    void testReadPathEscape() throws Exception {
        Method runRead = Lesson2RunSimple.class.getDeclaredMethod("runRead", String.class, Integer.class);
        runRead.setAccessible(true);

        String result = (String) runRead.invoke(lesson, "../outside.txt", null);
        assertTrue(result.contains("Error") || result.contains("escapes workspace"));
    }

    @Test
    @DisplayName("测试write_file工具 - 写入新文件")
    void testWriteNewFile() throws Exception {
        Method runWrite = Lesson2RunSimple.class.getDeclaredMethod("runWrite", String.class, String.class);
        runWrite.setAccessible(true);

        String result = (String) runWrite.invoke(lesson, "newfile.txt", "New content");
        assertTrue(result.contains("Wrote") || result.contains("bytes"));

        Path createdFile = workDir.resolve("newfile.txt");
        assertTrue(Files.exists(createdFile));
        assertEquals("New content", Files.readString(createdFile));
    }

    @Test
    @DisplayName("测试write_file工具 - 写入嵌套目录")
    void testWriteNestedDirectory() throws Exception {
        Method runWrite = Lesson2RunSimple.class.getDeclaredMethod("runWrite", String.class, String.class);
        runWrite.setAccessible(true);

        String result = (String) runWrite.invoke(lesson, "subdir/nested/file.txt", "Nested content");
        assertTrue(result.contains("Wrote"));

        Path createdFile = workDir.resolve("subdir/nested/file.txt");
        assertTrue(Files.exists(createdFile));
    }

    @Test
    @DisplayName("测试edit_file工具 - 替换文本")
    void testEditFile() throws Exception {
        Path testFile = workDir.resolve("edit.txt");
        Files.writeString(testFile, "Hello World");

        Method runEdit = Lesson2RunSimple.class.getDeclaredMethod("runEdit", String.class, String.class, String.class);
        runEdit.setAccessible(true);

        String result = (String) runEdit.invoke(lesson, "edit.txt", "World", "Java");
        assertTrue(result.contains("Edited"));

        assertEquals("Hello Java", Files.readString(testFile));
    }

    @Test
    @DisplayName("测试edit_file工具 - 文本不存在")
    void testEditTextNotFound() throws Exception {
        Path testFile = workDir.resolve("edit.txt");
        Files.writeString(testFile, "Hello World");

        Method runEdit = Lesson2RunSimple.class.getDeclaredMethod("runEdit", String.class, String.class, String.class);
        runEdit.setAccessible(true);

        String result = (String) runEdit.invoke(lesson, "edit.txt", "NotExist", "Java");
        assertTrue(result.contains("Error") || result.contains("not found"));
    }

    @Test
    @DisplayName("测试truncate方法")
    void testTruncate() throws Exception {
        Method truncate = Lesson2RunSimple.class.getDeclaredMethod("truncate", String.class, int.class);
        truncate.setAccessible(true);

        String shortStr = "hello";
        assertEquals("hello", truncate.invoke(lesson, shortStr, 10));

        String longStr = "a".repeat(100);
        String result = (String) truncate.invoke(lesson, longStr, 50);
        assertEquals(50, result.length());
    }

    @Test
    @DisplayName("测试parseArguments - 简单JSON")
    void testParseArgumentsSimple() throws Exception {
        Method parseArgs = Lesson2RunSimple.class.getDeclaredMethod("parseArguments", String.class);
        parseArgs.setAccessible(true);

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result =
            (java.util.Map<String, Object>) parseArgs.invoke(lesson, "{\"command\":\"ls -la\"}");

        assertEquals("ls -la", result.get("command"));
    }

    @Test
    @DisplayName("测试parseArguments - 多参数")
    void testParseArgumentsMultiple() throws Exception {
        Method parseArgs = Lesson2RunSimple.class.getDeclaredMethod("parseArguments", String.class);
        parseArgs.setAccessible(true);

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result =
            (java.util.Map<String, Object>) parseArgs.invoke(lesson, "{\"path\":\"test.txt\",\"limit\":10}");

        assertEquals("test.txt", result.get("path"));
        assertEquals(10, result.get("limit"));
    }

    @Test
    @DisplayName("测试parseArguments - 空字符串")
    void testParseArgumentsEmpty() throws Exception {
        Method parseArgs = Lesson2RunSimple.class.getDeclaredMethod("parseArguments", String.class);
        parseArgs.setAccessible(true);

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) parseArgs.invoke(lesson, "");

        assertTrue(result.isEmpty());
    }
}
