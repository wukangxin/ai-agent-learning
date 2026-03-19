package ai.agent.learning.lesson;

import ai.agent.learning.base.RunSimple;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.ChatCompletion;
import com.openai.models.ChatCompletionCreateParams;
import com.openai.models.ChatCompletionAssistantMessageParam;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Lesson8RunSimple - Background Tasks
 *
 * Run commands in background threads. A notification queue is drained
 * before each LLM call to deliver results.
 *
 * Key insight: "Fire and forget -- the agent doesn't block while the command runs."
 */
@Component
public class Lesson8RunSimple implements RunSimple {

    private static final Logger log = LoggerFactory.getLogger(Lesson8RunSimple.class);

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
    private BackgroundManager backgroundManager;

    @Override
    public void run(String userPrompt) {
        log.info("Starting Lesson8 (Background Tasks) with model: {}", modelName);

        workDir = Paths.get(System.getProperty("user.dir"));
        backgroundManager = new BackgroundManager(workDir);

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
                : "You are a coding agent at " + workDir + ". Use background_run for long-running commands.";

        agentLoop(client, messages, tools, handlers, sysPrompt);
    }

    private void agentLoop(OpenAIClient client, List<ChatCompletionMessageParam> messages,
                           List<ChatCompletionTool> tools, Map<String, ToolHandler> handlers, String sysPrompt) {
        while (true) {
            // Drain background notifications and inject as system message before LLM call
            List<Map<String, Object>> notifs = backgroundManager.drainNotifications();
            if (notifs != null && !notifs.isEmpty()) {
                StringBuilder notifText = new StringBuilder("<background-results>\n");
                for (Map<String, Object> n : notifs) {
                    notifText.append("[bg:").append(n.get("task_id")).append("] ")
                            .append(n.get("status")).append(": ")
                            .append(n.get("result")).append("\n");
                }
                notifText.append("</background-results>");

                messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(notifText.toString()).build()));
                messages.add(ChatCompletionMessageParam.ofAssistant(
                        ChatCompletionAssistantMessageParam.builder().content("Noted background results.").build()));
            }

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

    // -- BackgroundManager: threaded execution + notification queue --
    static class BackgroundManager {
        private final Map<String, TaskInfo> tasks = new ConcurrentHashMap<>();
        private final List<Map<String, Object>> notificationQueue = new CopyOnWriteArrayList<>();
        private final ReentrantLock lock = new ReentrantLock();
        private final Path workDir;

        public BackgroundManager(Path workDir) {
            this.workDir = workDir;
        }

        public String run(String command) {
            String taskId = UUID.randomUUID().toString().substring(0, 8);
            tasks.put(taskId, new TaskInfo("running", command, null));

            Thread thread = new Thread(() -> execute(taskId, command), "bg-" + taskId);
            thread.setDaemon(true);
            thread.start();

            return "Background task " + taskId + " started: " + truncate(command, 80);
        }

        private void execute(String taskId, String command) {
            try {
                ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", command);
                pb.redirectErrorStream(true);
                Process process = pb.start();

                boolean finished = process.waitFor(300, TimeUnit.SECONDS);
                String output;
                String status;

                if (!finished) {
                    process.destroyForcibly();
                    output = "Error: Timeout (300s)";
                    status = "timeout";
                } else {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        output = reader.lines().collect(Collectors.joining("\n")).trim();
                    }
                    output = truncate(output.isEmpty() ? "(no output)" : output, 50000);
                    status = "completed";
                }

                tasks.get(taskId).status = status;
                tasks.get(taskId).result = output;

                lock.lock();
                try {
                    notificationQueue.add(Map.of(
                            "task_id", taskId,
                            "status", status,
                            "command", truncate(command, 80),
                            "result", truncate(output, 500)
                    ));
                } finally {
                    lock.unlock();
                }

            } catch (Exception e) {
                tasks.get(taskId).status = "error";
                tasks.get(taskId).result = "Error: " + e.getMessage();

                lock.lock();
                try {
                    notificationQueue.add(Map.of(
                            "task_id", taskId,
                            "status", "error",
                            "command", truncate(command, 80),
                            "result", "Error: " + e.getMessage()
                    ));
                } finally {
                    lock.unlock();
                }
            }
        }

        public String check(String taskId) {
            if (taskId != null) {
                TaskInfo t = tasks.get(taskId);
                if (t == null) return "Error: Unknown task " + taskId;
                return "[" + t.status + "] " + truncate(t.command, 60) + "\n" +
                        (t.result != null ? t.result : "(running)");
            }

            if (tasks.isEmpty()) return "No background tasks.";
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, TaskInfo> e : tasks.entrySet()) {
                sb.append(e.getKey()).append(": [").append(e.getValue().status).append("] ")
                        .append(truncate(e.getValue().command, 60)).append("\n");
            }
            return sb.toString().trim();
        }

        public List<Map<String, Object>> drainNotifications() {
            lock.lock();
            try {
                if (notificationQueue.isEmpty()) return null;
                List<Map<String, Object>> notifs = new ArrayList<>(notificationQueue);
                notificationQueue.clear();
                return notifs;
            } finally {
                lock.unlock();
            }
        }

        private String truncate(String s, int maxLen) {
            return s != null && s.length() > maxLen ? s.substring(0, maxLen) : s;
        }
    }

    static class TaskInfo {
        volatile String status;
        final String command;
        volatile String result;

        TaskInfo(String status, String command, String result) {
            this.status = status;
            this.command = command;
            this.result = result;
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
                        .name("bash").description("Run a shell command (blocking).")
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
                        .name("background_run").description("Run command in background thread. Returns task_id immediately.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of("command", Map.of("type", "string"))))
                                .putAdditionalProperty("required", JsonValue.from(List.of("command")))
                                .build())
                        .build())
                .build());

        tools.add(ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("check_background").description("Check background task status. Omit task_id to list all.")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of("task_id", Map.of("type", "string"))))
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
        handlers.put("background_run", args -> backgroundManager.run((String) args.get("command")));
        handlers.put("check_background", args -> backgroundManager.check((String) args.get("task_id")));
        return handlers;
    }

    private Map<String, Object> parseArguments(String json) {
        Map<String, Object> result = new HashMap<>();
        if (json == null || json.isEmpty()) return result;

        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) {
            json = json.substring(1, json.length() - 1);
            String[] pairs = json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            for (String pair : pairs) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim().replace("\"", "");
                    String value = kv[1].trim();
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        result.put(key, value.substring(1, value.length() - 1).replace("\\\"", "\""));
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

    @FunctionalInterface
    interface ToolHandler {
        String execute(Map<String, Object> args) throws Exception;
    }
}
