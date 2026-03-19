package ai.agent.learning.lesson;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lesson7RunSimple - TaskManager 单元测试
 */
class Lesson7TaskManagerTest {

    @TempDir
    Path tempDir;

    private Lesson7RunSimple.TaskManager taskManager;

    @BeforeEach
    void setUp() {
        taskManager = new Lesson7RunSimple.TaskManager(tempDir);
    }

    @Test
    @DisplayName("测试创建任务")
    void testCreateTask() {
        String result = taskManager.create("实现登录功能", "使用JWT实现用户登录");

        assertTrue(result.contains("\"id\":1"));
        assertTrue(result.contains("\"subject\":\"实现登录功能\""));
        assertTrue(result.contains("\"status\":\"pending\""));
        assertTrue(result.contains("\"description\":\"使用JWT实现用户登录\""));
    }

    @Test
    @DisplayName("测试创建多个任务 - ID自动递增")
    void testCreateMultipleTasks() {
        taskManager.create("任务1", null);
        String result2 = taskManager.create("任务2", null);

        assertTrue(result2.contains("\"id\":2"));
    }

    @Test
    @DisplayName("测试获取任务")
    void testGetTask() {
        taskManager.create("测试任务", "描述");
        String result = taskManager.get(1);

        assertTrue(result.contains("\"id\":1"));
        assertTrue(result.contains("\"subject\":\"测试任务\""));
    }

    @Test
    @DisplayName("测试获取不存在的任务")
    void testGetNonExistentTask() {
        assertThrows(IllegalArgumentException.class, () -> taskManager.get(999));
    }

    @Test
    @DisplayName("测试更新任务状态 - pending到in_progress")
    void testUpdateTaskStatus() {
        taskManager.create("任务", null);
        String result = taskManager.update(1, "in_progress", null, null);

        assertTrue(result.contains("\"status\":\"in_progress\""));
    }

    @Test
    @DisplayName("测试更新任务状态 - 完成任务")
    void testCompleteTask() {
        taskManager.create("任务", null);
        String result = taskManager.update(1, "completed", null, null);

        assertTrue(result.contains("\"status\":\"completed\""));
    }

    @Test
    @DisplayName("测试无效状态")
    void testInvalidStatus() {
        taskManager.create("任务", null);
        assertThrows(IllegalArgumentException.class, () -> taskManager.update(1, "invalid", null, null));
    }

    @Test
    @DisplayName("测试任务依赖 - blockedBy")
    void testTaskBlockedBy() {
        taskManager.create("前置任务", null);
        taskManager.create("后续任务", null);

        String result = taskManager.update(2, null, List.of(1), null);

        assertTrue(result.contains("\"blockedBy\":[1]"));
    }

    @Test
    @DisplayName("测试任务依赖 - blocks (双向更新)")
    void testTaskBlocks() {
        taskManager.create("前置任务", null);
        taskManager.create("后续任务", null);

        // 设置任务1阻塞任务2
        taskManager.update(1, null, null, List.of(2));

        // 验证任务1的blocks包含2
        String task1 = taskManager.get(1);
        assertTrue(task1.contains("\"blocks\":[2]"));

        // 验证任务2的blockedBy包含1 (双向更新)
        String task2 = taskManager.get(2);
        assertTrue(task2.contains("\"blockedBy\":[1]"));
    }

    @Test
    @DisplayName("测试完成任务后清除依赖")
    void testClearDependencyOnComplete() {
        taskManager.create("前置任务", null);
        taskManager.create("后续任务", null);

        // 设置依赖
        taskManager.update(2, null, List.of(1), null);

        // 完成前置任务
        taskManager.update(1, "completed", null, null);

        // 验证后续任务的blockedBy被清除
        String task2 = taskManager.get(2);
        assertTrue(task2.contains("\"blockedBy\":[]"));
    }

    @Test
    @DisplayName("测试列出所有任务")
    void testListAllTasks() {
        taskManager.create("任务A", null);
        taskManager.create("任务B", null);
        taskManager.update(2, "in_progress", null, null);

        String result = taskManager.listAll();

        assertTrue(result.contains("#1"));
        assertTrue(result.contains("任务A"));
        assertTrue(result.contains("[ ]")); // pending
        assertTrue(result.contains("#2"));
        assertTrue(result.contains("任务B"));
        assertTrue(result.contains("[>]")); // in_progress
    }

    @Test
    @DisplayName("测试列出空任务列表")
    void testListEmpty() {
        String result = taskManager.listAll();
        assertEquals("No tasks.", result);
    }

    @Test
    @DisplayName("测试列表显示阻塞信息")
    void testListWithBlockedInfo() {
        taskManager.create("前置任务", null);
        taskManager.create("后续任务", null);
        taskManager.update(2, null, List.of(1), null);

        String result = taskManager.listAll();

        assertTrue(result.contains("blocked by: [1]"));
    }

    @Test
    @DisplayName("测试任务持久化 - 文件存在")
    void testTaskPersistence() {
        taskManager.create("持久化测试", "测试描述");

        assertTrue(Files.exists(tempDir.resolve("task_1.json")));
    }

    @Test
    @DisplayName("测试重新加载已有任务")
    void testReloadExistingTasks() {
        // 创建第一个TaskManager并添加任务
        taskManager.create("任务1", null);
        taskManager.create("任务2", null);

        // 创建新的TaskManager，应该能读取已有任务
        Lesson7RunSimple.TaskManager newManager = new Lesson7RunSimple.TaskManager(tempDir);

        // 新创建的任务应该从ID 3开始
        String result = newManager.create("任务3", null);
        assertTrue(result.contains("\"id\":3"));
    }

    @Test
    @DisplayName("测试创建任务无描述")
    void testCreateTaskWithoutDescription() {
        String result = taskManager.create("只有标题", null);

        assertTrue(result.contains("\"description\":\"\""));
    }

    @Test
    @DisplayName("测试多个依赖关系")
    void testMultipleDependencies() {
        taskManager.create("任务A", null);
        taskManager.create("任务B", null);
        taskManager.create("任务C", null);

        // C依赖于A和B
        taskManager.update(3, null, List.of(1, 2), null);

        String result = taskManager.get(3);
        assertTrue(result.contains("\"blockedBy\":[1,2]"));
    }
}
