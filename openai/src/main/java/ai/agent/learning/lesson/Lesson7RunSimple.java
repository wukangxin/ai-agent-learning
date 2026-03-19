package ai.agent.learning.lesson;

import ai.agent.learning.base.RunSimple;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.ChatCompletion;
import com.openai.models.ChatCompletionCreateParams;
import com.openai.models.ChatCompletionMessage;
import com.openai.models.ChatCompletionMessageParam;
import com.openai.models.ChatCompletionMessageToolCall;
import com.openai.models.ChatCompletionTool;
import com.openai.models.ChatCompletionToolMessageParam;
import com.openai.models.ChatCompletionUserMessageParam;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Lesson7RunSimple - Tasks
 *
 * Tasks persist as JSON files in .tasks/ so they survive context compression.
 * Each task has a dependency graph (blockedBy/blocks).
 *
 * Key insight: "State that survives compression -- because it's outside the conversation."
 */
@Component
public class Lesson7RunSimple implements RunSimple {

    private static final Logger log = LoggerFactory.getLogger(Lesson7RunSimple.class);

    @Value("${openai.api-key}")
    private String apiKey;

    @Value("${openai.base-url}")
    private String baseUrl;

    @Value("${openai.model}")
    private String modelName;

    @Value("${openai.system-prompt}")
    private String systemPrompt;

    @Value("${proxy.host}")
    private String proxyHost;

    @Value("${proxy.port}")
    private int proxyPort;

    private Path workDir;
    private TaskManager taskManager;

    @Override
    public void run(String userPrompt) {
        log.info("Starting Lesson7 (Task System) with model: {}", modelName);

        workDir = Paths.get(System.getProperty("user.dir"));
        Path tasksDir = workDir.resolve(".tasks");
        taskManager = new TaskManager(tasksDir);

        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .proxy(proxy)
                .build();

        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(userPrompt).build()));

        List<ChatCompletionTool> tools = createTools();
        Map<String, ToolHandler> handlers = createHandlers();

        String sysPrompt = systemPrompt != null && !systemPrompt.isEmpty()
                ? systemPrompt
                : "You are a coding agent at " + workDir + ". Use task tools to plan and track work.";

        agentLoop(client, messages, tools, handlers, sysPrompt);
    }

    private void agentLoop(OpenAIClient client, List<ChatCompletionMessageParam> messages,
                           List<ChatCompletionTool> tools, Map<String, ToolHandler> handlers, String sysPrompt) {
        while (true) {
            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .model(ChatModel.of(modelName))
                    .messages(messages)
                    .tools(tools)
                    .addSystemMessage(sysPrompt);

            ChatCompletion completion = client.chat().completions().create(paramsBuilder.build());
            ChatCompletion.Choice choice = completion.choices().get(0);
            ChatCompletionMessage assistantMessage = choice.message();

            messages.add(ChatCompletionMessageParam.ofAssistant(assistantMessage.toParam()));

            if (choice.finishReason() != ChatCompletion.Choice.FinishReason.TOOL_CALLS) {
                assistantMessage.content().ifPresent(content -> log.info("Assistant: {}", content));
                break;
            }

            if (assistantMessage.toolCalls().isPresent()) {
                for (ChatCompletionMessageToolCall toolCall : assistantMessage.toolCalls().get()) {
                    ChatCompletionMessageToolCall.Function function = toolCall.function();
                    String toolName = function.name();
                    String arguments = function.arguments();

                    log.info("Tool call: {} with args: {}", toolName, arguments);

                    String output = executeTool(handlers, toolName, arguments);
                    log.info("Output (truncated): {}", output.length() > 200 ? output.substring(0, 200) : output);

                    messages.add(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                            .toolCallId(toolCall.id())
                            .content(output)
                            .build()));
                }
            }
        }
    }

    // -- TaskManager: CRUD with dependency graph, persisted as JSON files --
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

                // When a task is completed, remove it from all other tasks' blockedBy
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
                    // Bidirectional: also update the blocked tasks' blockedBy lists
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

        // Simple JSON helpers
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

    // -- Tool implementations --
    private Path safePath(String p) {
        Path path = workDir.resolve(p).normalize();
        if (!path.startsWith(workDir)) {
            throw new IllegalArgumentException("Path escapes workspace: " + p);
        }
        return path;
    }

    private String runBash(String command) {
        String[] dangerous = {"rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"};
        for (String d : dangerous) {
            if (command.contains(d)) {
                return "Error: Dangerous command blocked";
            }
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: Timeout (120s)";
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String output = reader.lines().collect(Collectors.joining("\n")).trim();
                return output.isEmpty() ? "(no output)" : truncate(output, 50000);
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String runRead(String path, Integer limit) {
        try {
            List<String> lines = Files.readAllLines(safePath(path), StandardCharsets.UTF_8);
            if (limit != null && limit < lines.size()) {
                lines = lines.subList(0, limit);
                lines.add("... (" + (lines.size() - limit) + " more)");
            }
            return truncate(String.join("\n", lines), 50000);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String runWrite(String path, String content) {
        try {
            Path fp = safePath(path);
            Files.createDirectories(fp.getParent());
            Files.write(fp, content.getBytes(StandardCharsets.UTF_8));
            return "Wrote " + content.length() + " bytes";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String runEdit(String path, String oldText, String newText) {
        try {
            Path fp = safePath(path);
            String content = new String(Files.readAllBytes(fp), StandardCharsets.UTF_8);
            if (!content.contains(oldText)) {
                return "Error: Text not found in " + path;
            }
            content = content.replaceFirst(escapeRegex(oldText), newText);
            Files.write(fp, content.getBytes(StandardCharsets.UTF_8));
            return "Edited " + path;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String escapeRegex(String s) {
        return s.replace("\\", "\\\\").replace("$", "\\$")
                .replace(".", "\\.").replace("*", "\\*")
                .replace("+", "\\+").replace("?", "\\?")
                .replace("[", "\\[").replace("]", "\\]")
                .replace("(", "\\(").replace(")", "\\)")
                .replace("{", "\\{").replace("}", "\\}")
                .replace("|", "\\|").replace("^", "\\^");
    }

    private String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private String executeTool(Map<String, ToolHandler> handlers, String toolName, String arguments) {
        ToolHandler handler = handlers.get(toolName);
        if (handler == null) {
            return "Unknown tool: " + toolName;
        }
        try {
            Map<String, Object> args = parseArguments(arguments);
            return handler.execute(args);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // -- Tool definitions --
    private List<ChatCompletionTool> createTools() {
        List<ChatCompletionTool> tools = new ArrayList<>();

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("bash").description("Run a shell command.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of("command", Map.of("type", "string"))))
                                .putAdditionalProperty("required", JsonValue.from(List.of("command")))
                                .build())
                        .build())
                .build());

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("read_file").description("Read file contents.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of("path", Map.of("type", "string"), "limit", Map.of("type", "integer"))))
                                .putAdditionalProperty("required", JsonValue.from(List.of("path")))
                                .build())
                        .build())
                .build());

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("write_file").description("Write content to file.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string"))))
                                .putAdditionalProperty("required", JsonValue.from(List.of("path", "content")))
                                .build())
                        .build())
                .build());

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("edit_file").description("Replace exact text in file.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of("path", Map.of("type", "string"), "old_text", Map.of("type", "string"), "new_text", Map.of("type", "string"))))
                                .putAdditionalProperty("required", JsonValue.from(List.of("path", "old_text", "new_text")))
                                .build())
                        .build())
                .build());

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("task_create").description("Create a new task.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of("subject", Map.of("type", "string"), "description", Map.of("type", "string"))))
                                .putAdditionalProperty("required", JsonValue.from(List.of("subject")))
                                .build())
                        .build())
                .build());

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("task_update").description("Update a task's status or dependencies.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                        "task_id", Map.of("type", "integer"),
                                        "status", Map.of("type", "string", "enum", List.of("pending", "in_progress", "completed")),
                                        "addBlockedBy", Map.of("type", "array", "items", Map.of("type", "integer")),
                                        "addBlocks", Map.of("type", "array", "items", Map.of("type", "integer"))
                                )))
                                .putAdditionalProperty("required", JsonValue.from(List.of("task_id")))
                                .build())
                        .build())
                .build());

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("task_list").description("List all tasks with status summary.")
                        .parameters(FunctionParameters.builder().build())
                        .build())
                .build());

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("task_get").description("Get full details of a task by ID.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of("task_id", Map.of("type", "integer"))))
                                .putAdditionalProperty("required", JsonValue.from(List.of("task_id")))
                                .build())
                        .build())
                .build());

        return tools;
    }

    // -- Tool dispatch map --
    private Map<String, ToolHandler> createHandlers() {
        Map<String, ToolHandler> handlers = new HashMap<>();
        handlers.put("bash", args -> runBash((String) args.get("command")));
        handlers.put("read_file", args -> runRead((String) args.get("path"), (Integer) args.get("limit")));
        handlers.put("write_file", args -> runWrite((String) args.get("path"), (String) args.get("content")));
        handlers.put("edit_file", args -> runEdit((String) args.get("path"), (String) args.get("old_text"), (String) args.get("new_text")));
        handlers.put("task_create", args -> taskManager.create((String) args.get("subject"), (String) args.get("description")));
        handlers.put("task_update", args -> taskManager.update(
                ((Number) args.get("task_id")).intValue(),
                (String) args.get("status"),
                (List<Integer>) args.get("addBlockedBy"),
                (List<Integer>) args.get("addBlocks")));
        handlers.put("task_list", args -> taskManager.listAll());
        handlers.put("task_get", args -> taskManager.get(((Number) args.get("task_id")).intValue()));
        return handlers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String json) {
        Map<String, Object> result = new HashMap<>();
        if (json == null || json.isEmpty()) return result;

        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) {
            json = json.substring(1, json.length() - 1);
            int depth = 0;
            StringBuilder current = new StringBuilder();
            List<String> pairs = new ArrayList<>();
            for (char c : json.toCharArray()) {
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') depth--;
                else if (c == ',' && depth == 0) {
                    pairs.add(current.toString());
                    current = new StringBuilder();
                    continue;
                }
                current.append(c);
            }
            if (current.length() > 0) pairs.add(current.toString());

            for (String pair : pairs) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim().replace("\"", "");
                    String value = kv[1].trim();
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        result.put(key, value.substring(1, value.length() - 1).replace("\\\"", "\""));
                    } else if (value.startsWith("[")) {
                        result.put(key, parseArray(value));
                    } else if (value.matches("-?\\d+")) {
                        result.put(key, Integer.parseInt(value));
                    } else {
                        result.put(key, value);
                    }
                }
            }
        }
        return result;
    }

    private List<Integer> parseArray(String json) {
        List<Integer> result = new ArrayList<>();
        if (!json.startsWith("[") || !json.endsWith("]")) return result;
        json = json.substring(1, json.length() - 1);
        if (json.isEmpty()) return result;
        for (String item : json.split(",")) {
            item = item.trim();
            if (item.matches("-?\\d+")) {
                result.add(Integer.parseInt(item));
            }
        }
        return result;
    }

    @FunctionalInterface
    interface ToolHandler {
        String execute(Map<String, Object> args) throws Exception;
    }
}
