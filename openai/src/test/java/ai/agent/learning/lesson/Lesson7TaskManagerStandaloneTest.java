package ai.agent.learning.lesson;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lesson7RunSimple - TaskManager 独立测试
 * 不依赖主代码编译，复制了必要的逻辑进行测试
 */
class Lesson7TaskManagerStandaloneTest {

    @TempDir
    Path tempDir;

    private TaskManager taskManager;

    static class TaskManager {
        private final Path dir;
        private int nextId;

        public TaskManager(Path tasksDir) {
            this.dir = tasksDir;
            try {
                Files.createDirectories(dir);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create tasks directory", e);
            }
            this.nextId = maxId() + 1;
        }

        private int maxId() {
            try {
                return Files.list(dir)
                        .filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                        .mapToInt(p -> {
                            String name = p.getFileName().toString();
                            return Integer.parseInt(name.substring(5, name.length() - 5));
                        })
                        .max()
                        .orElse(0);
            } catch (Exception e) {
                return 0;
            }
        }

        private Path path(int taskId) {
            return dir.resolve("task_" + taskId + ".json");
        }

        private Map<String, Object> load(int taskId) {
            try {
                String json = new String(Files.readAllBytes(path(taskId)), StandardCharsets.UTF_8);
                return parseJsonToMap(json);
            } catch (Exception e) {
                throw new IllegalArgumentException("Task " + taskId + " not found");
            }
        }

        private void save(Map<String, Object> task) {
            try {
                int id = ((Number) task.get("id")).intValue();
                Files.write(path(id), mapToJson(task).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new RuntimeException("Failed to save task", e);
            }
        }

        public String create(String subject, String description) {
            Map<String, Object> task = new HashMap<>();
            task.put("id", nextId);
            task.put("subject", subject);
            task.put("description", description != null ? description : "");
            task.put("status", "pending");
            task.put("blockedBy", new ArrayList<>());
            task.put("blocks", new ArrayList<>());
            task.put("owner", "");

            save(task);
            nextId++;
            return mapToJson(task);
        }

        public String get(int taskId) {
            return mapToJson(load(taskId));
        }

        public String update(int taskId, String status, List<Integer> addBlockedBy, List<Integer> addBlocks) {
            Map<String, Object> task = load(taskId);

            if (status != null) {
                if (!status.equals("pending") && !status.equals("in_progress") && !status.equals("completed")) {
                    throw new IllegalArgumentException("Invalid status: " + status);
                }
                task.put("status", status);

                if (status.equals("completed")) {
                    clearDependency(taskId);
                }
            }

            if (addBlockedBy != null) {
                @SuppressWarnings("unchecked")
                List<Integer> blockedBy = new ArrayList<>((List<Integer>) task.get("blockedBy"));
                for (Integer id : addBlockedBy) {
                    if (!blockedBy.contains(id)) {
                        blockedBy.add(id);
                    }
                }
                task.put("blockedBy", blockedBy);
            }

            if (addBlocks != null) {
                @SuppressWarnings("unchecked")
                List<Integer> blocks = new ArrayList<>((List<Integer>) task.get("blocks"));
                for (Integer blockedId : addBlocks) {
                    if (!blocks.contains(blockedId)) {
                        blocks.add(blockedId);
                    }
                    try {
                        Map<String, Object> blocked = load(blockedId);
                        @SuppressWarnings("unchecked")
                        List<Integer> blockedBy = new ArrayList<>((List<Integer>) blocked.get("blockedBy"));
                        if (!blockedBy.contains(taskId)) {
                            blockedBy.add(taskId);
                            blocked.put("blockedBy", blockedBy);
                            save(blocked);
                        }
                    } catch (Exception ignored) {}
                }
                task.put("blocks", blocks);
            }

            save(task);
            return mapToJson(task);
        }

        private void clearDependency(int completedId) {
            try {
                Files.list(dir)
                        .filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                        .forEach(p -> {
                            try {
                                Map<String, Object> t = parseJsonToMap(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                                @SuppressWarnings("unchecked")
                                List<Integer> blockedBy = new ArrayList<>((List<Integer>) t.get("blockedBy"));
                                if (blockedBy.contains(completedId)) {
                                    blockedBy.remove(Integer.valueOf(completedId));
                                    t.put("blockedBy", blockedBy);
                                    save(t);
                                }
                            } catch (Exception ignored) {}
                        });
            } catch (Exception ignored) {}
        }

        public String listAll() {
            List<Map<String, Object>> tasks = new ArrayList<>();
            try {
                Files.list(dir)
                        .filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                        .sorted()
                        .forEach(p -> {
                            try {
                                tasks.add(parseJsonToMap(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)));
                            } catch (Exception ignored) {}
                        });
            } catch (Exception ignored) {}

            if (tasks.isEmpty()) return "No tasks.";

            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> t : tasks) {
                String marker = switch ((String) t.get("status")) {
                    case "pending" -> "[ ]";
                    case "in_progress" -> "[>]";
                    case "completed" -> "[x]";
                    default -> "[?]";
                };
                @SuppressWarnings("unchecked")
                List<Integer> blockedBy = (List<Integer>) t.get("blockedBy");
                String blocked = blockedBy != null && !blockedBy.isEmpty()
                        ? " (blocked by: " + blockedBy + ")"
                        : "";
                sb.append(marker).append(" #").append(t.get("id")).append(": ")
                        .append(t.get("subject")).append(blocked).append("\n");
            }
            return sb.toString().trim();
        }

        // JSON helpers
        private Map<String, Object> parseJsonToMap(String json) {
            Map<String, Object> result = new HashMap<>();
            json = json.trim();
            if (json.startsWith("{") && json.endsWith("}")) {
                json = json.substring(1, json.length() - 1);
                for (String pair : splitTopLevel(json)) {
                    String[] kv = pair.split(":", 2);
                    if (kv.length == 2) {
                        String key = kv[0].trim().replace("\"", "");
                        String value = kv[1].trim();
                        result.put(key, parseValue(value));
                    }
                }
            }
            return result;
        }

        private List<String> splitTopLevel(String s) {
            List<String> parts = new ArrayList<>();
            int depth = 0;
            boolean inString = false;
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && inString) {
                    current.append(c);
                    if (i + 1 < s.length()) current.append(s.charAt(++i));
                    continue;
                }
                if (c == '"') inString = !inString;
                if (!inString) {
                    if (c == '{' || c == '[') depth++;
                    else if (c == '}' || c == ']') depth--;
                    else if (c == ',' && depth == 0) {
                        parts.add(current.toString());
                        current = new StringBuilder();
                        continue;
                    }
                }
                current.append(c);
            }
            if (current.length() > 0) parts.add(current.toString());
            return parts;
        }

        private Object parseValue(String value) {
            if (value.startsWith("\"") && value.endsWith("\"")) {
                return value.substring(1, value.length() - 1).replace("\\\"", "\"");
            } else if (value.startsWith("[")) {
                List<Object> list = new ArrayList<>();
                if (!value.equals("[]")) {
                    String inner = value.substring(1, value.length() - 1);
                    for (String item : splitTopLevel(inner)) {
                        item = item.trim();
                        if (!item.isEmpty()) {
                            list.add(parseValue(item));
                        }
                    }
                }
                return list;
            } else if (value.matches("-?\\d+")) {
                return Integer.parseInt(value);
            }
            return value;
        }

        private String mapToJson(Map<String, Object> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(e.getKey()).append("\":").append(valueToJson(e.getValue()));
            }
            return sb.append("}").toString();
        }

        private String valueToJson(Object value) {
            if (value == null) return "null";
            if (value instanceof String) return "\"" + ((String) value).replace("\"", "\\\"") + "\"";
            if (value instanceof Number) return value.toString();
            if (value instanceof List) {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Object item : (List<?>) value) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append(valueToJson(item));
                }
                return sb.append("]").toString();
            }
            return "\"" + value.toString() + "\"";
        }
    }

    @BeforeEach
    void setUp() {
        taskManager = new TaskManager(tempDir);
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

        taskManager.update(1, null, null, List.of(2));

        String task1 = taskManager.get(1);
        assertTrue(task1.contains("\"blocks\":[2]"));

        String task2 = taskManager.get(2);
        assertTrue(task2.contains("\"blockedBy\":[1]"));
    }

    @Test
    @DisplayName("测试完成任务后清除依赖")
    void testClearDependencyOnComplete() {
        taskManager.create("前置任务", null);
        taskManager.create("后续任务", null);

        taskManager.update(2, null, List.of(1), null);
        taskManager.update(1, "completed", null, null);

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
        assertTrue(result.contains("[ ]"));
        assertTrue(result.contains("#2"));
        assertTrue(result.contains("任务B"));
        assertTrue(result.contains("[>]"));
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
    void testTaskPersistence() throws IOException {
        taskManager.create("持久化测试", "测试描述");

        assertTrue(Files.exists(tempDir.resolve("task_1.json")));
    }

    @Test
    @DisplayName("测试重新加载已有任务")
    void testReloadExistingTasks() {
        taskManager.create("任务1", null);
        taskManager.create("任务2", null);

        TaskManager newManager = new TaskManager(tempDir);

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

        taskManager.update(3, null, List.of(1, 2), null);

        String result = taskManager.get(3);
        assertTrue(result.contains("\"blockedBy\":[1,2]"));
    }
}
