package ai.agent.learning.lesson;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lesson12RunSimple - TaskManager 单元测试
 */
class Lesson12TaskManagerTest {

    @TempDir
    Path tempDir;

    private Lesson12RunSimple.TaskManager taskManager;

    @BeforeEach
    void setUp() {
        taskManager = new Lesson12RunSimple.TaskManager(tempDir);
    }

    @Test
    @DisplayName("测试创建任务")
    void testCreateTask() {
        String result = taskManager.create("实现功能", "详细描述");

        assertTrue(result.contains("\"id\":1"));
        assertTrue(result.contains("\"subject\":\"实现功能\""));
        assertTrue(result.contains("\"description\":\"详细描述\""));
        assertTrue(result.contains("\"status\":\"pending\""));
    }

    @Test
    @DisplayName("测试任务包含worktree字段")
    void testTaskHasWorktreeField() {
        String result = taskManager.create("测试任务", null);

        assertTrue(result.contains("\"worktree\":\"\""));
    }

    @Test
    @DisplayName("测试获取任务")
    void testGetTask() {
        taskManager.create("任务1", null);
        String result = taskManager.get(1);

        assertTrue(result.contains("\"id\":1"));
        assertTrue(result.contains("\"subject\":\"任务1\""));
    }

    @Test
    @DisplayName("测试更新任务状态")
    void testUpdateTaskStatus() {
        taskManager.create("任务", null);
        String result = taskManager.update(1, "in_progress", null);

        assertTrue(result.contains("\"status\":\"in_progress\""));
    }

    @Test
    @DisplayName("测试更新任务所有者")
    void testUpdateTaskOwner() {
        taskManager.create("任务", null);
        String result = taskManager.update(1, null, "agent-1");

        assertTrue(result.contains("\"owner\":\"agent-1\""));
    }

    @Test
    @DisplayName("测试绑定worktree")
    void testBindWorktree() {
        taskManager.create("任务", null);
        String result = taskManager.bindWorktree(1, "wt-feature", "agent-1");

        assertTrue(result.contains("\"worktree\":\"wt-feature\""));
        assertTrue(result.contains("\"owner\":\"agent-1\""));
        assertTrue(result.contains("\"status\":\"in_progress\""));
    }

    @Test
    @DisplayName("测试绑定worktree不覆盖已完成状态")
    void testBindWorktreeKeepCompletedStatus() {
        taskManager.create("任务", null);
        taskManager.update(1, "completed", null);
        String result = taskManager.bindWorktree(1, "wt-feature", null);

        assertTrue(result.contains("\"worktree\":\"wt-feature\""));
        assertTrue(result.contains("\"status\":\"completed\""));
    }

    @Test
    @DisplayName("测试解绑worktree")
    void testUnbindWorktree() {
        taskManager.create("任务", null);
        taskManager.bindWorktree(1, "wt-feature", null);
        String result = taskManager.unbindWorktree(1);

        assertTrue(result.contains("\"worktree\":\"\""));
    }

    @Test
    @DisplayName("测试列出所有任务")
    void testListAllTasks() {
        taskManager.create("任务A", null);
        taskManager.create("任务B", null);
        taskManager.bindWorktree(2, "wt-b", "agent-1");

        String result = taskManager.listAll();

        assertTrue(result.contains("#1"));
        assertTrue(result.contains("任务A"));
        assertTrue(result.contains("#2"));
        assertTrue(result.contains("任务B"));
        assertTrue(result.contains("wt=wt-b"));
        assertTrue(result.contains("owner=agent-1"));
    }

    @Test
    @DisplayName("测试列出空任务")
    void testListEmpty() {
        String result = taskManager.listAll();
        assertEquals("No tasks.", result);
    }

    @Test
    @DisplayName("测试任务存在检查")
    void testTaskExists() {
        taskManager.create("任务", null);

        assertTrue(taskManager.exists(1));
        assertFalse(taskManager.exists(999));
    }

    @Test
    @DisplayName("测试获取不存在的任务")
    void testGetNonExistentTask() {
        assertThrows(IllegalArgumentException.class, () -> taskManager.get(999));
    }

    @Test
    @DisplayName("测试任务时间戳")
    void testTaskTimestamps() {
        String result = taskManager.create("任务", null);

        assertTrue(result.contains("\"created_at\":"));
        assertTrue(result.contains("\"updated_at\":"));
    }

    @Test
    @DisplayName("测试ID自动递增")
    void testIdAutoIncrement() {
        taskManager.create("任务1", null);
        taskManager.create("任务2", null);
        taskManager.create("任务3", null);

        assertTrue(taskManager.get(1).contains("\"id\":1"));
        assertTrue(taskManager.get(2).contains("\"id\":2"));
        assertTrue(taskManager.get(3).contains("\"id\":3"));
    }
}
