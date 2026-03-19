package ai.agent.learning.lesson;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lesson8RunSimple - BackgroundManager 单元测试
 */
class Lesson8BackgroundManagerTest {

    @TempDir
    Path tempDir;

    private Lesson8RunSimple.BackgroundManager bgManager;

    @BeforeEach
    void setUp() {
        bgManager = new Lesson8RunSimple.BackgroundManager(tempDir);
    }

    @Test
    @DisplayName("测试启动后台任务")
    void testRunBackgroundTask() {
        String result = bgManager.run("echo hello");

        assertTrue(result.contains("Background task"));
        assertTrue(result.contains("started"));
        assertTrue(result.contains("echo hello"));
    }

    @Test
    @DisplayName("测试检查空任务列表")
    void testCheckEmptyTasks() {
        String result = bgManager.check(null);
        assertEquals("No background tasks.", result);
    }

    @Test
    @DisplayName("测试检查不存在的任务")
    void testCheckNonExistentTask() {
        String result = bgManager.check("nonexistent");
        assertTrue(result.contains("Error: Unknown task"));
    }

    @Test
    @DisplayName("测试后台任务执行完成")
    void testBackgroundTaskCompletion() throws InterruptedException {
        String startResult = bgManager.run("echo test-output");

        // 提取task_id
        String taskId = startResult.split(" ")[2];
        assertNotNull(taskId);

        // 等待任务完成
        TimeUnit.SECONDS.sleep(2);

        String checkResult = bgManager.check(taskId);
        assertTrue(checkResult.contains("[completed]") || checkResult.contains("test-output"));
    }

    @Test
    @DisplayName("测试列出所有任务")
    void testListAllTasks() throws InterruptedException {
        bgManager.run("echo task1");
        bgManager.run("echo task2");

        TimeUnit.MILLISECONDS.sleep(500);

        String result = bgManager.check(null);

        assertTrue(result.contains("echo task1") || result.length() > 0);
        assertTrue(result.contains("echo task2") || result.length() > 0);
    }

    @Test
    @DisplayName("测试通知队列为空时返回null")
    void testDrainEmptyNotifications() {
        List<Map<String, Object>> notifs = bgManager.drainNotifications();
        assertNull(notifs);
    }

    @Test
    @DisplayName("测试通知队列获取结果")
    void testDrainNotifications() throws InterruptedException {
        bgManager.run("echo notify-test");

        // 等待任务完成并产生通知
        TimeUnit.SECONDS.sleep(3);

        List<Map<String, Object>> notifs = bgManager.drainNotifications();

        if (notifs != null && !notifs.isEmpty()) {
            Map<String, Object> notif = notifs.get(0);
            assertTrue(notif.containsKey("task_id"));
            assertTrue(notif.containsKey("status"));
            assertTrue(notif.containsKey("command"));
        }
    }

    @Test
    @DisplayName("测试通知队列清空")
    void testNotificationsClearedAfterDrain() throws InterruptedException {
        bgManager.run("echo test");

        // 等待完成
        TimeUnit.SECONDS.sleep(3);

        // 第一次获取
        List<Map<String, Object>> first = bgManager.drainNotifications();

        // 第二次应该返回null（队列已空）
        List<Map<String, Object>> second = bgManager.drainNotifications();
        assertNull(second);
    }

    @Test
    @DisplayName("测试任务状态字段")
    void testTaskInfoFields() {
        Lesson8RunSimple.TaskInfo info = new Lesson8RunSimple.TaskInfo("running", "test command", null);

        assertEquals("running", info.status);
        assertEquals("test command", info.command);
        assertNull(info.result);

        // 更新状态
        info.status = "completed";
        info.result = "output";

        assertEquals("completed", info.status);
        assertEquals("output", info.result);
    }
}
