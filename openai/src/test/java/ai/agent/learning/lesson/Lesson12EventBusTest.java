package ai.agent.learning.lesson;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lesson12RunSimple - EventBus 单元测试
 */
class Lesson12EventBusTest {

    @TempDir
    Path tempDir;

    private Lesson12RunSimple.EventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new Lesson12RunSimple.EventBus(tempDir.resolve("events.jsonl"));
    }

    @Test
    @DisplayName("测试发送事件")
    void testEmitEvent() {
        eventBus.emit("worktree.create.before",
                Map.of("id", 1),
                Map.of("name", "wt-test", "base_ref", "HEAD"),
                null);

        String result = eventBus.listRecent(10);

        assertTrue(result.contains("worktree.create.before"));
        assertTrue(result.contains("wt-test"));
    }

    @Test
    @DisplayName("测试发送带错误的事件")
    void testEmitEventWithError() {
        eventBus.emit("worktree.create.failed",
                Map.of("id", 1),
                Map.of("name", "wt-test"),
                "Error: Git command failed");

        String result = eventBus.listRecent(10);

        assertTrue(result.contains("worktree.create.failed"));
        assertTrue(result.contains("Error: Git command failed"));
    }

    @Test
    @DisplayName("测试发送多个事件")
    void testMultipleEvents() {
        eventBus.emit("task.created", Map.of("id", 1), null, null);
        eventBus.emit("worktree.create.before", Map.of("id", 1), Map.of("name", "wt-1"), null);
        eventBus.emit("worktree.create.after", Map.of("id", 1), Map.of("name", "wt-1"), null);

        String result = eventBus.listRecent(10);

        assertTrue(result.contains("task.created"));
        assertTrue(result.contains("worktree.create.before"));
        assertTrue(result.contains("worktree.create.after"));
    }

    @Test
    @DisplayName("测试限制返回事件数量")
    void testListRecentWithLimit() {
        for (int i = 0; i < 10; i++) {
            eventBus.emit("event." + i, Map.of("id", i), null, null);
        }

        String result = eventBus.listRecent(5);

        // 应该只返回最近5个事件
        assertTrue(result.contains("event.9"));
        assertTrue(result.contains("event.5"));
        assertFalse(result.contains("event.4"));
    }

    @Test
    @DisplayName("测试事件包含时间戳")
    void testEventContainsTimestamp() {
        long beforeTime = System.currentTimeMillis() / 1000;
        eventBus.emit("test.event", null, null, null);
        long afterTime = System.currentTimeMillis() / 1000;

        String result = eventBus.listRecent(1);

        assertTrue(result.contains("\"ts\":"));
    }

    @Test
    @DisplayName("测试空事件列表")
    void testEmptyEventList() {
        // 创建新的EventBus（没有事件）
        Lesson12RunSimple.EventBus newBus =
            new Lesson12RunSimple.EventBus(tempDir.resolve("new-events.jsonl"));

        String result = newBus.listRecent(10);
        assertEquals("[]", result);
    }

    @Test
    @DisplayName("测试事件包含task和worktree字段")
    void testEventContainsTaskAndWorktree() {
        eventBus.emit("test.event",
                Map.of("id", 42, "status", "pending"),
                Map.of("name", "wt-feature", "path", "/path/to/wt"),
                null);

        String result = eventBus.listRecent(1);

        assertTrue(result.contains("\"task\":"));
        assertTrue(result.contains("\"worktree\":"));
        assertTrue(result.contains("42"));
        assertTrue(result.contains("wt-feature"));
    }
}
