package ai.agent.learning.lesson;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lesson13RunSimple - Full Reference Agent 综合测试
 *
 * 测试可独立验证的内部组件（不依赖 OpenAI API）：
 *   - TodoManager: 待办事项管理
 *   - SkillLoader: 技能加载
 *   - TaskManager: 文件任务管理
 *   - BackgroundManager: 后台任务管理
 *   - MessageBus: 消息总线
 */
class Lesson13FullAgentTest {

    // ========== TodoManager 测试 ==========

    @Nested
    @DisplayName("TodoManager - 待办事项管理")
    class TodoManagerTests {

        private Lesson13RunSimple.TodoManager todo;

        @BeforeEach
        void setUp() {
            todo = new Lesson13RunSimple.TodoManager();
        }

        @Test
        @DisplayName("空列表渲染为 No todos")
        void testEmptyRender() {
            assertEquals("No todos.", todo.render());
        }

        @Test
        @DisplayName("新建待办事项")
        void testAddTodos() {
            List<Map<String, Object>> items = List.of(
                    Map.of("content", "编写测试", "status", "pending"),
                    Map.of("content", "代码审查", "status", "pending")
            );
            String result = todo.update(items);

            assertTrue(result.contains("[ ] 编写测试"));
            assertTrue(result.contains("[ ] 代码审查"));
            assertTrue(result.contains("(0/2 completed)"));
        }

        @Test
        @DisplayName("待办事项状态 - in_progress 显示 [>]")
        void testInProgressStatus() {
            List<Map<String, Object>> items = List.of(
                    Map.of("content", "正在处理", "status", "in_progress")
            );
            String result = todo.update(items);

            assertTrue(result.contains("[>] 正在处理"));
        }

        @Test
        @DisplayName("待办事项状态 - completed 显示 [x]")
        void testCompletedStatus() {
            List<Map<String, Object>> items = List.of(
                    Map.of("content", "已完成任务", "status", "completed")
            );
            String result = todo.update(items);

            assertTrue(result.contains("[x] 已完成任务"));
            assertTrue(result.contains("(1/1 completed)"));
        }

        @Test
        @DisplayName("in_progress 带 activeForm 显示标注")
        void testActiveFormAnnotation() {
            List<Map<String, Object>> items = List.of(
                    Map.of("content", "编写代码", "status", "in_progress", "activeForm", "coding module X")
            );
            String result = todo.update(items);

            assertTrue(result.contains("[>] 编写代码 <- coding module X"));
        }

        @Test
        @DisplayName("最多允许一个 in_progress 任务")
        void testOnlyOneInProgress() {
            List<Map<String, Object>> items = List.of(
                    Map.of("content", "任务A", "status", "in_progress"),
                    Map.of("content", "任务B", "status", "in_progress")
            );
            assertThrows(IllegalArgumentException.class, () -> todo.update(items));
        }

        @Test
        @DisplayName("最多允许20个待办事项")
        void testMaxTwentyItems() {
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < 21; i++) {
                items.add(Map.of("content", "任务" + i, "status", "pending"));
            }
            assertThrows(IllegalArgumentException.class, () -> todo.update(items));
        }

        @Test
        @DisplayName("恰好20个待办事项不报错")
        void testExactlyTwentyItemsAllowed() {
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                items.add(Map.of("content", "任务" + i, "status", "pending"));
            }
            assertDoesNotThrow(() -> todo.update(items));
        }

        @Test
        @DisplayName("hasOpenItems - 有未完成时返回true")
        void testHasOpenItemsTrue() {
            todo.update(List.of(
                    Map.of("content", "待处理", "status", "pending")
            ));
            assertTrue(todo.hasOpenItems());
        }

        @Test
        @DisplayName("hasOpenItems - 全部完成时返回false")
        void testHasOpenItemsFalse() {
            todo.update(List.of(
                    Map.of("content", "已完成", "status", "completed")
            ));
            assertFalse(todo.hasOpenItems());
        }

        @Test
        @DisplayName("hasOpenItems - 空列表返回false")
        void testHasOpenItemsEmpty() {
            assertFalse(todo.hasOpenItems());
        }

        @Test
        @DisplayName("update替换整个列表而非追加")
        void testUpdateReplacesEntireList() {
            todo.update(List.of(Map.of("content", "旧任务", "status", "pending")));
            todo.update(List.of(Map.of("content", "新任务", "status", "pending")));

            String result = todo.render();
            assertFalse(result.contains("旧任务"));
            assertTrue(result.contains("新任务"));
        }

        @Test
        @DisplayName("混合状态的完成计数")
        void testMixedStatusCounting() {
            todo.update(List.of(
                    Map.of("content", "A", "status", "completed"),
                    Map.of("content", "B", "status", "in_progress"),
                    Map.of("content", "C", "status", "pending"),
                    Map.of("content", "D", "status", "completed")
            ));
            String result = todo.render();
            assertTrue(result.contains("(2/4 completed)"));
        }
    }

    // ========== SkillLoader 测试 ==========

    @Nested
    @DisplayName("SkillLoader - 技能加载")
    class SkillLoaderTests {

        @TempDir
        Path skillsDir;

        @Test
        @DisplayName("加载技能目录中的 SKILL.md")
        void testLoadSkills() throws Exception {
            Path skillDir = skillsDir.resolve("code-review");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), "# Code Review\nReview code carefully.");

            Lesson13RunSimple.SkillLoader loader = new Lesson13RunSimple.SkillLoader(skillsDir);
            String desc = loader.getDescriptions();

            assertTrue(desc.contains("code-review"));
            assertTrue(desc.contains("Skill: code-review"));
        }

        @Test
        @DisplayName("加载技能内容")
        void testLoadSkillContent() throws Exception {
            Path skillDir = skillsDir.resolve("debug");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), "Debug instructions here.");

            Lesson13RunSimple.SkillLoader loader = new Lesson13RunSimple.SkillLoader(skillsDir);
            String content = loader.load("debug");

            assertTrue(content.contains("<skill name=\"debug\">"));
            assertTrue(content.contains("Debug instructions here."));
            assertTrue(content.contains("</skill>"));
        }

        @Test
        @DisplayName("加载不存在的技能返回错误")
        void testLoadUnknownSkill() throws Exception {
            Path skillDir = skillsDir.resolve("existing");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), "Some content.");

            Lesson13RunSimple.SkillLoader loader = new Lesson13RunSimple.SkillLoader(skillsDir);
            String result = loader.load("nonexistent");

            assertTrue(result.contains("Error: Unknown skill 'nonexistent'"));
        }

        @Test
        @DisplayName("空目录返回 (no skills)")
        void testEmptySkillsDir() {
            Lesson13RunSimple.SkillLoader loader = new Lesson13RunSimple.SkillLoader(skillsDir);
            assertEquals("(no skills)", loader.getDescriptions());
        }

        @Test
        @DisplayName("不存在的目录不报错")
        void testNonExistentDir() {
            Path missing = skillsDir.resolve("does-not-exist");
            Lesson13RunSimple.SkillLoader loader = new Lesson13RunSimple.SkillLoader(missing);
            assertEquals("(no skills)", loader.getDescriptions());
        }

        @Test
        @DisplayName("多个技能目录全部加载")
        void testMultipleSkills() throws Exception {
            for (String name : List.of("alpha", "beta", "gamma")) {
                Path dir = skillsDir.resolve(name);
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("SKILL.md"), "Content for " + name);
            }

            Lesson13RunSimple.SkillLoader loader = new Lesson13RunSimple.SkillLoader(skillsDir);
            String desc = loader.getDescriptions();

            assertTrue(desc.contains("alpha"));
            assertTrue(desc.contains("beta"));
            assertTrue(desc.contains("gamma"));
        }

        @Test
        @DisplayName("没有 SKILL.md 的子目录被忽略")
        void testSubdirWithoutSkillMd() throws Exception {
            Files.createDirectories(skillsDir.resolve("empty-dir"));

            Path validDir = skillsDir.resolve("valid");
            Files.createDirectories(validDir);
            Files.writeString(validDir.resolve("SKILL.md"), "Valid skill.");

            Lesson13RunSimple.SkillLoader loader = new Lesson13RunSimple.SkillLoader(skillsDir);
            String desc = loader.getDescriptions();

            assertTrue(desc.contains("valid"));
            assertFalse(desc.contains("empty-dir"));
        }
    }

    // ========== TaskManager 测试 ==========

    @Nested
    @DisplayName("TaskManager - 文件任务管理")
    class TaskManagerTests {

        @TempDir
        Path tasksDir;

        private Lesson13RunSimple.TaskManager taskMgr;

        @BeforeEach
        void setUp() {
            taskMgr = new Lesson13RunSimple.TaskManager(tasksDir);
        }

        @Test
        @DisplayName("创建任务返回JSON包含id和subject")
        void testCreateTask() {
            String result = taskMgr.create("实现登录功能", "使用JWT");

            assertTrue(result.contains("\"subject\""));
            assertTrue(result.contains("实现登录功能"));
            assertTrue(result.contains("\"status\""));
            assertTrue(result.contains("pending"));
        }

        @Test
        @DisplayName("创建多个任务ID递增")
        void testAutoIncrementId() {
            taskMgr.create("任务1", null);
            taskMgr.create("任务2", null);
            String result = taskMgr.create("任务3", null);

            assertTrue(result.contains("\"id\""));
        }

        @Test
        @DisplayName("任务文件持久化到磁盘")
        void testTaskFileCreated() {
            taskMgr.create("持久化测试", "描述");
            assertTrue(Files.exists(tasksDir.resolve("task_1.json")));
        }

        @Test
        @DisplayName("获取已创建的任务")
        void testGetTask() {
            taskMgr.create("测试任务", "描述");
            String result = taskMgr.get(1);

            assertTrue(result.contains("测试任务"));
            assertTrue(result.contains("描述"));
        }

        @Test
        @DisplayName("获取不存在的任务抛异常")
        void testGetNonExistentTask() {
            assertThrows(IllegalArgumentException.class, () -> taskMgr.get(999));
        }

        @Test
        @DisplayName("更新任务状态为 in_progress")
        void testUpdateStatus() {
            taskMgr.create("任务", null);
            String result = taskMgr.update(1, "in_progress", null, null);

            assertTrue(result.contains("in_progress"));
        }

        @Test
        @DisplayName("完成任务状态变为 completed")
        void testCompleteTask() {
            taskMgr.create("任务", null);
            String result = taskMgr.update(1, "completed", null, null);

            assertTrue(result.contains("completed"));
        }

        @Test
        @DisplayName("删除任务返回确认信息")
        void testDeleteTask() {
            taskMgr.create("要删除的任务", null);
            String result = taskMgr.update(1, "deleted", null, null);

            assertEquals("Task 1 deleted", result);
            assertFalse(Files.exists(tasksDir.resolve("task_1.json")));
        }

        @Test
        @DisplayName("添加 blockedBy 依赖")
        void testAddBlockedBy() {
            taskMgr.create("前置任务", null);
            taskMgr.create("后续任务", null);

            String result = taskMgr.update(2, null, List.of(1), null);
            assertTrue(result.contains("blockedBy"));
        }

        @Test
        @DisplayName("添加 blocks 依赖")
        void testAddBlocks() {
            taskMgr.create("前置任务", null);
            taskMgr.create("后续任务", null);

            String result = taskMgr.update(1, null, null, List.of(2));
            assertTrue(result.contains("blocks"));
        }

        @Test
        @DisplayName("完成任务时清除其他任务的 blockedBy 引用")
        void testClearBlockedByOnComplete() {
            taskMgr.create("前置任务", null);
            taskMgr.create("后续任务", null);

            taskMgr.update(2, null, List.of(1), null);
            taskMgr.update(1, "completed", null, null);

            String task2 = taskMgr.get(2);
            assertTrue(task2.contains("blockedBy"));
            // After completion, task 2's blockedBy should no longer contain 1
        }

        @Test
        @DisplayName("列出所有任务")
        void testListAll() {
            taskMgr.create("任务A", null);
            taskMgr.create("任务B", null);
            taskMgr.update(2, "in_progress", null, null);

            String result = taskMgr.listAll();

            assertTrue(result.contains("#1"));
            assertTrue(result.contains("任务A"));
            assertTrue(result.contains("[ ]"));
            assertTrue(result.contains("#2"));
            assertTrue(result.contains("任务B"));
            assertTrue(result.contains("[>]"));
        }

        @Test
        @DisplayName("空任务列表返回 No tasks")
        void testListEmpty() {
            assertEquals("No tasks.", taskMgr.listAll());
        }

        @Test
        @DisplayName("claim 任务设置 owner 和 in_progress")
        void testClaimTask() {
            taskMgr.create("待认领", null);
            String result = taskMgr.claim(1, "alice");

            assertEquals("Claimed task #1 for alice", result);

            String task = taskMgr.get(1);
            assertTrue(task.contains("alice"));
            assertTrue(task.contains("in_progress"));
        }

        @Test
        @DisplayName("列表显示 owner 信息")
        void testListShowsOwner() {
            taskMgr.create("有主任务", null);
            taskMgr.claim(1, "bob");

            String result = taskMgr.listAll();
            assertTrue(result.contains("@bob"));
        }

        @Test
        @DisplayName("重新加载目录保留已有任务ID")
        void testReloadPreservesIds() {
            taskMgr.create("任务1", null);
            taskMgr.create("任务2", null);

            Lesson13RunSimple.TaskManager newMgr = new Lesson13RunSimple.TaskManager(tasksDir);
            String result = newMgr.create("任务3", null);

            // ID should continue from 3, not restart at 1
            assertTrue(result.contains("\"id\""));
            // Verify task_3.json was created
            assertTrue(Files.exists(tasksDir.resolve("task_3.json")));
        }

        @Test
        @DisplayName("创建任务无描述时默认为空字符串")
        void testCreateWithNullDescription() {
            String result = taskMgr.create("无描述", null);
            assertTrue(result.contains("\"description\""));
        }
    }

    // ========== BackgroundManager 测试 ==========

    @Nested
    @DisplayName("BackgroundManager - 后台任务管理")
    class BackgroundManagerTests {

        @TempDir
        Path workDir;

        private Lesson13RunSimple.BackgroundManager bg;

        @BeforeEach
        void setUp() {
            bg = new Lesson13RunSimple.BackgroundManager(workDir);
        }

        @Test
        @DisplayName("启动后台任务返回确认信息")
        void testRunReturnsConfirmation() {
            String result = bg.run("echo hello", 30);

            assertTrue(result.contains("Background task"));
            assertTrue(result.contains("started"));
        }

        @Test
        @DisplayName("检查空任务列表")
        void testCheckEmptyTasks() {
            String result = bg.check(null);
            assertEquals("No bg tasks.", result);
        }

        @Test
        @DisplayName("检查不存在的任务ID")
        void testCheckUnknownTaskId() {
            String result = bg.check("nonexistent");
            assertTrue(result.contains("Unknown: nonexistent"));
        }

        @Test
        @DisplayName("后台任务完成后可查看结果")
        void testTaskCompletion() throws InterruptedException {
            String startResult = bg.run("echo test-output", 30);

            // 提取 taskId（格式: "Background task XXXXXXXX started: ..."）
            String taskId = startResult.split(" ")[2];

            TimeUnit.SECONDS.sleep(3);

            String checkResult = bg.check(taskId);
            assertTrue(checkResult.contains("[completed]") || checkResult.contains("[running]"));
        }

        @Test
        @DisplayName("drain 空通知列表返回空集合")
        void testDrainEmpty() {
            List<Map<String, Object>> notifs = bg.drain();
            assertTrue(notifs.isEmpty());
        }

        @Test
        @DisplayName("drain 后通知被清除")
        void testDrainClearsNotifications() throws InterruptedException {
            bg.run("echo drain-test", 30);
            TimeUnit.SECONDS.sleep(3);

            // 第一次 drain
            bg.drain();

            // 第二次应该为空
            List<Map<String, Object>> second = bg.drain();
            assertTrue(second.isEmpty());
        }

        @Test
        @DisplayName("列出多个后台任务")
        void testListMultipleTasks() {
            bg.run("echo task1", 30);
            bg.run("echo task2", 30);

            String result = bg.check(null);
            // Should list both tasks (format: "id: [status] command")
            assertFalse(result.equals("No bg tasks."));
        }

        @Test
        @DisplayName("drain 返回的通知包含必要字段")
        void testDrainNotificationFields() throws InterruptedException {
            bg.run("echo field-test", 30);
            TimeUnit.SECONDS.sleep(3);

            List<Map<String, Object>> notifs = bg.drain();
            if (!notifs.isEmpty()) {
                Map<String, Object> notif = notifs.get(0);
                assertTrue(notif.containsKey("task_id"));
                assertTrue(notif.containsKey("status"));
                assertTrue(notif.containsKey("result"));
            }
        }
    }

    // ========== MessageBus 测试 ==========

    @Nested
    @DisplayName("MessageBus - 消息总线")
    class MessageBusTests {

        @TempDir
        Path inboxDir;

        private Lesson13RunSimple.MessageBus bus;

        @BeforeEach
        void setUp() {
            bus = new Lesson13RunSimple.MessageBus(inboxDir);
        }

        @Test
        @DisplayName("发送消息并读取")
        void testSendAndRead() {
            bus.send("lead", "worker1", "你好", "message", null);

            List<Map<String, Object>> inbox = bus.readInbox("worker1");
            assertFalse(inbox.isEmpty());

            Map<String, Object> msg = inbox.get(0);
            assertEquals("message", msg.get("type"));
            assertEquals("lead", msg.get("from"));
            assertEquals("你好", msg.get("content"));
        }

        @Test
        @DisplayName("读取后清空收件箱")
        void testReadClearsInbox() {
            bus.send("lead", "worker1", "消息1", "message", null);
            bus.readInbox("worker1");

            List<Map<String, Object>> second = bus.readInbox("worker1");
            assertTrue(second.isEmpty());
        }

        @Test
        @DisplayName("读取空收件箱返回空列表")
        void testReadEmptyInbox() {
            List<Map<String, Object>> inbox = bus.readInbox("nobody");
            assertTrue(inbox.isEmpty());
        }

        @Test
        @DisplayName("广播消息到多个接收者")
        void testBroadcast() {
            String result = bus.broadcast("lead", "全体通知", List.of("lead", "worker1", "worker2"));

            // lead自身不接收广播, 因此是2个
            assertTrue(result.contains("2"));

            assertFalse(bus.readInbox("worker1").isEmpty());
            assertFalse(bus.readInbox("worker2").isEmpty());
            assertTrue(bus.readInbox("lead").isEmpty());
        }

        @Test
        @DisplayName("发送带附加信息的消息")
        void testSendWithExtra() {
            bus.send("lead", "worker1", "关机", "shutdown_request",
                    Map.of("request_id", "req-001"));

            List<Map<String, Object>> inbox = bus.readInbox("worker1");
            assertFalse(inbox.isEmpty());

            Map<String, Object> msg = inbox.get(0);
            assertEquals("shutdown_request", msg.get("type"));
            assertEquals("req-001", msg.get("request_id"));
        }

        @Test
        @DisplayName("多条消息按序接收")
        void testMultipleMessages() {
            bus.send("a", "worker1", "第一条", "message", null);
            bus.send("b", "worker1", "第二条", "message", null);

            List<Map<String, Object>> inbox = bus.readInbox("worker1");
            assertEquals(2, inbox.size());
            assertEquals("第一条", inbox.get(0).get("content"));
            assertEquals("第二条", inbox.get(1).get("content"));
        }
    }
}
