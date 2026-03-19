package ai.agent.learning.lesson;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lesson11RunSimple - 自主代理 (Autonomous Agents) 单元测试
 *
 * 测试任务板轮询、任务认领等不依赖 OpenAI API 的独立行为。
 */
class Lesson11AutonomousTest {

    @TempDir
    Path tempDir;

    private Lesson11RunSimple instance;
    private Path tasksDir;

    @BeforeEach
    void setUp() throws Exception {
        instance = new Lesson11RunSimple();
        tasksDir = tempDir.resolve(".tasks");
        Files.createDirectories(tasksDir);

        // Inject tasksDir via reflection since it's a private field
        Field tasksDirField = Lesson11RunSimple.class.getDeclaredField("tasksDir");
        tasksDirField.setAccessible(true);
        tasksDirField.set(instance, tasksDir);
    }

    // ---- Helper: write a task JSON file ----
    private void writeTask(int id, String status, String owner, String subject, List<Integer> blockedBy) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"id\":").append(id);
        json.append(",\"status\":\"").append(status).append("\"");
        if (owner != null) {
            json.append(",\"owner\":\"").append(owner).append("\"");
        }
        json.append(",\"subject\":\"").append(subject).append("\"");
        if (blockedBy != null && !blockedBy.isEmpty()) {
            json.append(",\"blockedBy\":[");
            for (int i = 0; i < blockedBy.size(); i++) {
                if (i > 0) json.append(",");
                json.append(blockedBy.get(i));
            }
            json.append("]");
        } else {
            json.append(",\"blockedBy\":[]");
        }
        json.append("}");

        Path taskFile = tasksDir.resolve("task_" + id + ".json");
        try {
            Files.write(taskFile, json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            fail("Failed to write task file: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> invokeScanUnclaimedTasks() throws Exception {
        Method method = Lesson11RunSimple.class.getDeclaredMethod("scanUnclaimedTasks");
        method.setAccessible(true);
        return (List<Map<String, Object>>) method.invoke(instance);
    }

    private String invokeClaimTask(int taskId, String owner) throws Exception {
        Method method = Lesson11RunSimple.class.getDeclaredMethod("claimTask", int.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(instance, taskId, owner);
    }

    // ---- scanUnclaimedTasks 测试 ----

    @Test
    @DisplayName("扫描未认领任务 - 空任务目录返回空列表")
    void testScanUnclaimedTasks_emptyDir() throws Exception {
        List<Map<String, Object>> result = invokeScanUnclaimedTasks();
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("扫描未认领任务 - 发现pending且无owner的任务")
    void testScanUnclaimedTasks_findsPendingWithoutOwner() throws Exception {
        writeTask(1, "pending", null, "Write unit tests", null);

        List<Map<String, Object>> result = invokeScanUnclaimedTasks();

        assertEquals(1, result.size());
        assertEquals("pending", result.get(0).get("status"));
        assertNull(result.get(0).get("owner"));
    }

    @Test
    @DisplayName("扫描未认领任务 - 过滤掉已有owner的任务")
    void testScanUnclaimedTasks_filtersOwnedTasks() throws Exception {
        writeTask(1, "pending", "alice", "Owned task", null);

        List<Map<String, Object>> result = invokeScanUnclaimedTasks();

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("扫描未认领任务 - 过滤掉非pending状态的任务")
    void testScanUnclaimedTasks_filtersNonPendingTasks() throws Exception {
        writeTask(1, "in_progress", null, "In progress task", null);
        writeTask(2, "completed", null, "Completed task", null);

        List<Map<String, Object>> result = invokeScanUnclaimedTasks();

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("扫描未认领任务 - 过滤掉有blockedBy依赖的任务")
    void testScanUnclaimedTasks_filtersBlockedTasks() throws Exception {
        writeTask(1, "pending", null, "Blocked task", List.of(99));

        List<Map<String, Object>> result = invokeScanUnclaimedTasks();

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("扫描未认领任务 - 多个任务只返回符合条件的")
    void testScanUnclaimedTasks_mixedTasks() throws Exception {
        writeTask(1, "pending", null, "Unclaimed A", null);       // should match
        writeTask(2, "pending", "bob", "Claimed B", null);         // has owner
        writeTask(3, "in_progress", null, "In progress C", null);  // wrong status
        writeTask(4, "pending", null, "Blocked D", List.of(1));    // blocked
        writeTask(5, "pending", null, "Unclaimed E", null);        // should match

        List<Map<String, Object>> result = invokeScanUnclaimedTasks();

        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("扫描未认领任务 - 忽略非task_N.json命名的文件")
    void testScanUnclaimedTasks_ignoresNonTaskFiles() throws Exception {
        // Write a properly named task
        writeTask(1, "pending", null, "Valid task", null);

        // Write files that don't match the pattern
        Files.write(tasksDir.resolve("notes.json"),
                "{\"id\":99,\"status\":\"pending\",\"blockedBy\":[]}".getBytes(StandardCharsets.UTF_8));
        Files.write(tasksDir.resolve("task_abc.json"),
                "{\"id\":99,\"status\":\"pending\",\"blockedBy\":[]}".getBytes(StandardCharsets.UTF_8));

        List<Map<String, Object>> result = invokeScanUnclaimedTasks();

        assertEquals(1, result.size());
    }

    // ---- claimTask 测试 ----

    @Test
    @DisplayName("认领任务 - 成功认领并更新状态和owner")
    void testClaimTask_success() throws Exception {
        writeTask(1, "pending", null, "Claim me", null);

        String result = invokeClaimTask(1, "alice");

        assertTrue(result.contains("Claimed task #1"));
        assertTrue(result.contains("alice"));

        // Verify the file was updated
        String content = new String(Files.readAllBytes(tasksDir.resolve("task_1.json")), StandardCharsets.UTF_8);
        Map<String, Object> task = Lesson9RunSimple.parseJsonToMap(content);
        assertEquals("alice", task.get("owner"));
        assertEquals("in_progress", task.get("status"));
    }

    @Test
    @DisplayName("认领任务 - 任务不存在时返回错误")
    void testClaimTask_taskNotFound() throws Exception {
        String result = invokeClaimTask(999, "alice");

        assertTrue(result.contains("Error"));
        assertTrue(result.contains("999"));
    }

    @Test
    @DisplayName("认领任务 - 认领后文件保留原有字段")
    void testClaimTask_preservesExistingFields() throws Exception {
        writeTask(1, "pending", null, "Important task", null);

        invokeClaimTask(1, "bob");

        String content = new String(Files.readAllBytes(tasksDir.resolve("task_1.json")), StandardCharsets.UTF_8);
        Map<String, Object> task = Lesson9RunSimple.parseJsonToMap(content);
        assertEquals("Important task", task.get("subject"));
        assertEquals("bob", task.get("owner"));
        assertEquals("in_progress", task.get("status"));
    }

    // ---- makeIdentityBlock 测试 ----

    @Test
    @DisplayName("身份块生成 - 包含正确的名称、角色和团队信息")
    void testMakeIdentityBlock() throws Exception {
        Method method = Lesson11RunSimple.class.getDeclaredMethod("makeIdentityBlock",
                String.class, String.class, String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) method.invoke(instance, "alice", "developer", "alpha");

        assertEquals("user", block.get("role"));
        String content = (String) block.get("content");
        assertTrue(content.contains("alice"));
        assertTrue(content.contains("developer"));
        assertTrue(content.contains("alpha"));
        assertTrue(content.contains("<identity>"));
        assertTrue(content.contains("</identity>"));
    }

    // ---- 类结构测试 ----

    @Test
    @DisplayName("Lesson11实现RunSimple接口")
    void testImplementsRunSimple() {
        assertTrue(ai.agent.learning.base.RunSimple.class.isAssignableFrom(Lesson11RunSimple.class));
    }

    @Test
    @DisplayName("Lesson11有@Component注解")
    void testHasComponentAnnotation() {
        assertNotNull(Lesson11RunSimple.class.getAnnotation(org.springframework.stereotype.Component.class));
    }

    @Test
    @DisplayName("TeammateManager内部类继承Lesson10的TeammateManager")
    void testTeammateManagerExtendsLesson10() {
        Class<?>[] innerClasses = Lesson11RunSimple.class.getDeclaredClasses();
        boolean found = false;
        for (Class<?> inner : innerClasses) {
            if (inner.getSimpleName().equals("TeammateManager")) {
                assertTrue(Lesson10RunSimple.TeammateManager.class.isAssignableFrom(inner));
                found = true;
                break;
            }
        }
        assertTrue(found, "TeammateManager inner class should exist");
    }

    @Test
    @DisplayName("POLL_INTERVAL和IDLE_TIMEOUT常量存在且合理")
    void testConstants() throws Exception {
        Field pollInterval = Lesson11RunSimple.class.getDeclaredField("POLL_INTERVAL");
        pollInterval.setAccessible(true);
        int poll = (int) pollInterval.get(null);
        assertTrue(poll > 0, "POLL_INTERVAL should be positive");

        Field idleTimeout = Lesson11RunSimple.class.getDeclaredField("IDLE_TIMEOUT");
        idleTimeout.setAccessible(true);
        int idle = (int) idleTimeout.get(null);
        assertTrue(idle > poll, "IDLE_TIMEOUT should be greater than POLL_INTERVAL");
    }
}
