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
 * Lesson3RunSimple - TodoWrite
 *
 * The model tracks its own progress via a TodoManager. A nag reminder
 * forces it to keep updating when it forgets.
 *
 * Key insight: "The agent can track its own progress -- and I can see it."
 */
@Component
public class Lesson3RunSimple implements RunSimple {

    private static final Logger log = LoggerFactory.getLogger(Lesson3RunSimple.class);

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
    private TodoManager todoManager;

    @Override
    public void run(String userPrompt) {
        log.info("Starting Lesson3 (TodoWrite) with model: {}", modelName);

        workDir = Paths.get(System.getProperty("user.dir"));
        todoManager = new TodoManager();

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

        agentLoop(client, messages, tools, handlers);
    }

    private void agentLoop(OpenAIClient client, List<ChatCompletionMessageParam> messages,
                           List<ChatCompletionTool> tools, Map<String, ToolHandler> handlers) {
        int roundsSinceTodo = 0;

        while (true) {
            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .model(ChatModel.of(modelName))
                    .messages(messages)
                    .tools(tools);

            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                paramsBuilder.addSystemMessage(systemPrompt);
            }

            ChatCompletion completion = client.chat().completions().create(paramsBuilder.build());
            ChatCompletion.Choice choice = completion.choices().get(0);
            ChatCompletionMessage assistantMessage = choice.message();

            messages.add(ChatCompletionMessageParam.ofAssistant(assistantMessage.toParam()));

            if (choice.finishReason() != ChatCompletion.Choice.FinishReason.TOOL_CALLS) {
                assistantMessage.content().ifPresent(content -> log.info("Assistant: {}", content));
                break;
            }

            if (assistantMessage.toolCalls().isPresent()) {
                List<Map<String, Object>> results = new ArrayList<>();
                boolean usedTodo = false;

                for (ChatCompletionMessageToolCall toolCall : assistantMessage.toolCalls().get()) {
                    ChatCompletionMessageToolCall.Function function = toolCall.function();
                    String toolName = function.name();
                    String arguments = function.arguments();

                    log.info("Tool call: {} with args: {}", toolName, arguments);

                    String output = executeTool(handlers, toolName, arguments);
                    log.info("Output (truncated): {}", output.length() > 200 ? output.substring(0, 200) : output);

                    results.add(Map.of(
                            "type", "tool_result",
                            "tool_use_id", toolCall.id(),
                            "content", output
                    ));

                    if ("todo".equals(toolName)) {
                        usedTodo = true;
                    }
                }

                // Nag reminder injection
                roundsSinceTodo = usedTodo ? 0 : roundsSinceTodo + 1;
                if (roundsSinceTodo >= 3) {
                    results.add(0, Map.of(
                            "type", "text",
                            "text", "<reminder>Update your todos.</reminder>"
                    ));
                }

                // Add results as user message (simplified for OpenAI)
                StringBuilder resultsContent = new StringBuilder();
                for (Map<String, Object> result : results) {
                    resultsContent.append(result.get("content")).append("\n");
                }
                messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(resultsContent.toString()).build()));
            }
        }
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

    // -- TodoManager: structured state the LLM writes to --
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
                lines.add("... (" + (lines.size() - limit) + " more lines)");
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
        return s.replace("\\", "\\\\")
                .replace("$", "\\$")
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

    // -- Tool definitions --
    private List<ChatCompletionTool> createTools() {
        List<ChatCompletionTool> tools = new ArrayList<>();

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("bash")
                        .description("Run a shell command.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of("command", Map.of("type", "string"))))
                                .putAdditionalProperty("required", JsonValue.from(List.of("command")))
                                .build())
                        .build())
                .build());

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("read_file")
                        .description("Read file contents.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                        "path", Map.of("type", "string"),
                                        "limit", Map.of("type", "integer")
                                )))
                                .putAdditionalProperty("required", JsonValue.from(List.of("path")))
                                .build())
                        .build())
                .build());

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("write_file")
                        .description("Write content to file.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                        "path", Map.of("type", "string"),
                                        "content", Map.of("type", "string")
                                )))
                                .putAdditionalProperty("required", JsonValue.from(List.of("path", "content")))
                                .build())
                        .build())
                .build());

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("edit_file")
                        .description("Replace exact text in file.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                        "path", Map.of("type", "string"),
                                        "old_text", Map.of("type", "string"),
                                        "new_text", Map.of("type", "string")
                                )))
                                .putAdditionalProperty("required", JsonValue.from(List.of("path", "old_text", "new_text")))
                                .build())
                        .build())
                .build());

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("todo")
                        .description("Update task list. Track progress on multi-step tasks.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                        "items", Map.of(
                                                "type", "array",
                                                "items", Map.of(
                                                        "type", "object",
                                                        "properties", Map.of(
                                                                "id", Map.of("type", "string"),
                                                                "text", Map.of("type", "string"),
                                                                "status", Map.of("type", "string", "enum", List.of("pending", "in_progress", "completed"))
                                                        ),
                                                        "required", List.of("id", "text", "status")
                                                )
                                        )
                                )))
                                .putAdditionalProperty("required", JsonValue.from(List.of("items")))
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
        handlers.put("todo", args -> {
            Object itemsObj = args.get("items");
            if (itemsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
                return todoManager.update(items);
            }
            return "Error: items must be a list";
        });
        return handlers;
    }

    // Simple JSON argument parser
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String json) {
        Map<String, Object> result = new HashMap<>();
        if (json == null || json.isEmpty()) return result;

        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) {
            json = json.substring(1, json.length() - 1);
            // Handle nested structures
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
                        // Parse array
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

    private List<Map<String, Object>> parseArray(String json) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!json.startsWith("[") || !json.endsWith("]")) return result;

        json = json.substring(1, json.length() - 1);
        // Split by commas at depth 0
        List<String> items = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : json.toCharArray()) {
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                items.add(current.toString());
                current = new StringBuilder();
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) items.add(current.toString());

        for (String item : items) {
            if (item.trim().startsWith("{")) {
                result.add(parseArguments(item.trim()));
            }
        }
        return result;
    }

    @FunctionalInterface
    interface ToolHandler {
        String execute(Map<String, Object> args) throws Exception;
    }
}
