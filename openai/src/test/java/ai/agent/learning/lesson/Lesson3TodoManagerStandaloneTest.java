package ai.agent.learning.lesson;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lesson3RunSimple - TodoManager 独立测试
 * 不依赖主代码编译，复制了必要的逻辑进行测试
 */
class Lesson3TodoManagerStandaloneTest {

    static class TodoManager {
        private List<TodoItem> items = new ArrayList<>();

        public String update(List<Map<String, Object>> newItems) {
            if (newItems.size() > 20) {
                throw new IllegalArgumentException("Max 20 todos allowed");
            }

            List<TodoItem> validated = new ArrayList<>();
            int inProgressCount = 0;

            for (int i = 0; i < newItems.size(); i++) {
                Map<String, Object> item = newItems.get(i);
                String text = item.get("text") != null ? item.get("text").toString().trim() : "";
                String status = item.get("status") != null ? item.get("status").toString().toLowerCase() : "pending";
                String id = item.get("id") != null ? item.get("id").toString() : String.valueOf(i + 1);

                if (text.isEmpty()) {
                    throw new IllegalArgumentException("Item " + id + ": text required");
                }
                if (!status.equals("pending") && !status.equals("in_progress") && !status.equals("completed")) {
                    throw new IllegalArgumentException("Item " + id + ": invalid status '" + status + "'");
                }
                if (status.equals("in_progress")) {
                    inProgressCount++;
                }

                validated.add(new TodoItem(id, text, status));
            }

            if (inProgressCount > 1) {
                throw new IllegalArgumentException("Only one task can be in_progress at a time");
            }

            items = validated;
            return render();
        }

        public String render() {
            if (items.isEmpty()) {
                return "No todos.";
            }
            StringBuilder sb = new StringBuilder();
            for (TodoItem item : items) {
                String marker = switch (item.status) {
                    case "pending" -> "[ ]";
                    case "in_progress" -> "[>]";
                    case "completed" -> "[x]";
                    default -> "[?]";
                };
                sb.append(marker).append(" #").append(item.id).append(": ").append(item.text).append("\n");
            }
            long done = items.stream().filter(t -> t.status.equals("completed")).count();
            sb.append("\n(").append(done).append("/").append(items.size()).append(" completed)");
            return sb.toString();
        }

        public boolean hasOpenItems() {
            return items.stream().anyMatch(t -> !t.status.equals("completed"));
        }
    }

    static class TodoItem {
        String id;
        String text;
        String status;

        TodoItem(String id, String text, String status) {
            this.id = id;
            this.text = text;
            this.status = status;
        }
    }

    @Test
    @DisplayName("测试初始状态 - 无todos")
    void testEmptyState() {
        TodoManager todoManager = new TodoManager();
        String result = todoManager.render();
        assertEquals("No todos.", result);
        assertFalse(todoManager.hasOpenItems());
    }

    @Test
    @DisplayName("测试添加单个待办项")
    void testAddSingleItem() {
        TodoManager todoManager = new TodoManager();
        List<Map<String, Object>> items = List.of(
                Map.of("id", "1", "text", "测试任务", "status", "pending")
        );

        String result = todoManager.update(items);

        assertTrue(result.contains("[ ] #1: 测试任务"));
        assertTrue(result.contains("(0/1 completed)"));
        assertTrue(todoManager.hasOpenItems());
    }

    @Test
    @DisplayName("测试进行中状态")
    void testInProgressItem() {
        TodoManager todoManager = new TodoManager();
        List<Map<String, Object>> items = List.of(
                Map.of("id", "1", "text", "进行中任务", "status", "in_progress")
        );

        String result = todoManager.update(items);

        assertTrue(result.contains("[>] #1: 进行中任务"));
        assertTrue(todoManager.hasOpenItems());
    }

    @Test
    @DisplayName("测试已完成状态")
    void testCompletedItem() {
        TodoManager todoManager = new TodoManager();
        List<Map<String, Object>> items = List.of(
                Map.of("id", "1", "text", "已完成任务", "status", "completed")
        );

        String result = todoManager.update(items);

        assertTrue(result.contains("[x] #1: 已完成任务"));
        assertTrue(result.contains("(1/1 completed)"));
        assertFalse(todoManager.hasOpenItems());
    }

    @Test
    @DisplayName("测试多个待办项")
    void testMultipleItems() {
        TodoManager todoManager = new TodoManager();
        List<Map<String, Object>> items = List.of(
                Map.of("id", "1", "text", "任务1", "status", "completed"),
                Map.of("id", "2", "text", "任务2", "status", "in_progress"),
                Map.of("id", "3", "text", "任务3", "status", "pending")
        );

        String result = todoManager.update(items);

        assertTrue(result.contains("[x] #1: 任务1"));
        assertTrue(result.contains("[>] #2: 任务2"));
        assertTrue(result.contains("[ ] #3: 任务3"));
        assertTrue(result.contains("(1/3 completed)"));
    }

    @Test
    @DisplayName("测试无效状态 - 应抛出异常")
    void testInvalidStatus() {
        TodoManager todoManager = new TodoManager();
        List<Map<String, Object>> items = List.of(
                Map.of("id", "1", "text", "任务", "status", "invalid")
        );

        assertThrows(IllegalArgumentException.class, () -> todoManager.update(items));
    }

    @Test
    @DisplayName("测试空文本 - 应抛出异常")
    void testEmptyText() {
        TodoManager todoManager = new TodoManager();
        List<Map<String, Object>> items = List.of(
                Map.of("id", "1", "text", "", "status", "pending")
        );

        assertThrows(IllegalArgumentException.class, () -> todoManager.update(items));
    }

    @Test
    @DisplayName("测试多个进行中任务 - 应抛出异常")
    void testMultipleInProgress() {
        TodoManager todoManager = new TodoManager();
        List<Map<String, Object>> items = List.of(
                Map.of("id", "1", "text", "任务1", "status", "in_progress"),
                Map.of("id", "2", "text", "任务2", "status", "in_progress")
        );

        assertThrows(IllegalArgumentException.class, () -> todoManager.update(items));
    }

    @Test
    @DisplayName("测试超过20个待办项 - 应抛出异常")
    void testMaxItemsExceeded() {
        TodoManager todoManager = new TodoManager();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            items.add(Map.of("id", String.valueOf(i), "text", "任务" + i, "status", "pending"));
        }

        assertThrows(IllegalArgumentException.class, () -> todoManager.update(items));
    }

    @Test
    @DisplayName("测试自动生成ID")
    void testAutoGeneratedId() {
        TodoManager todoManager = new TodoManager();
        List<Map<String, Object>> items = List.of(
                Map.of("text", "无ID任务", "status", "pending")
        );

        String result = todoManager.update(items);
        assertTrue(result.contains("#1: 无ID任务"));
    }

    @Test
    @DisplayName("测试状态不区分大小写")
    void testCaseInsensitiveStatus() {
        TodoManager todoManager = new TodoManager();
        List<Map<String, Object>> items = List.of(
                Map.of("id", "1", "text", "任务", "status", "IN_PROGRESS")
        );

        String result = todoManager.update(items);
        assertTrue(result.contains("[>] #1: 任务"));
    }
}
