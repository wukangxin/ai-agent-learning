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
import com.openai.models.ChatCompletionAssistantMessageParam;
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
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lesson9RunSimple - Agent Teams
 *
 * Persistent named agents with file-based JSONL inboxes. Each teammate runs
 * its own agent loop in a separate thread. Communication via append-only inboxes.
 *
 * Subagent (s04):  spawn -> execute -> return summary -> destroyed
 * Teammate (s09):  spawn -> work -> idle -> work -> ... -> shutdown
 *
 * Key insight: "Teammates that can talk to each other."
 */
@Component
public class Lesson9RunSimple implements RunSimple {

    private static final Logger log = LoggerFactory.getLogger(Lesson9RunSimple.class);
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^([A-Za-z]):[\\\\/](.*)$");

    private static final Set<String> VALID_MSG_TYPES = Set.of(
            "message", "broadcast", "shutdown_request", "shutdown_response", "plan_approval_response"
    );

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
    private Path teamDir;
    private Path inboxDir;
    private MessageBus bus;
    private TeammateManager team;
    private OpenAIClient client;

    @Override
    public void run(String userPrompt) {
        log.info("Starting Lesson9 (Agent Teams) with model: {}", modelName);

        workDir = Paths.get(System.getProperty("user.dir"));
        teamDir = workDir.resolve(".team");
        inboxDir = teamDir.resolve("inbox");

        bus = new MessageBus(inboxDir);
        team = new TeammateManager(teamDir, bus, client, modelName, workDir);

        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
        client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .proxy(proxy)
                .build();

        // Reinitialize team with client
        team = new TeammateManager(teamDir, bus, client, modelName, workDir);

        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(userPrompt).build()));

        List<ChatCompletionTool> tools = createTools();
        Map<String, ToolHandler> handlers = createHandlers();

        String sysPrompt = systemPrompt != null && !systemPrompt.isEmpty()
                ? systemPrompt
                : "You are a team lead at " + workDir + ". Spawn teammates and communicate via inboxes.";

        agentLoop(messages, tools, handlers, sysPrompt);
    }

    private void agentLoop(List<ChatCompletionMessageParam> messages,
                           List<ChatCompletionTool> tools, Map<String, ToolHandler> handlers, String sysPrompt) {
        while (true) {
            // Check lead inbox
            List<Map<String, Object>> inbox = bus.readInbox("lead");
            if (!inbox.isEmpty()) {
                messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder()
                        .content("<inbox>" + listToJson(inbox) + "</inbox>").build()));
                messages.add(ChatCompletionMessageParam.ofAssistant(
                        ChatCompletionAssistantMessageParam.builder().content("Noted inbox messages.").build()));
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

            if (!ChatCompletion.Choice.FinishReason.TOOL_CALLS.equals(choice.finishReason())) {
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

    // -- MessageBus: JSONL inbox per teammate --
    static class MessageBus {
        private final Path dir;

        public MessageBus(Path inboxDir) {
            this.dir = inboxDir;
            try {
                Files.createDirectories(dir);
            } catch (Exception e) {
                Lesson9RunSimple.log.debug("Failed to create directory {}", dir, e);
            }
        }

        public String send(String sender, String to, String content, String msgType, Map<String, Object> extra) {
            if (!VALID_MSG_TYPES.contains(msgType)) {
                return "Error: Invalid type '" + msgType + "'. Valid: " + VALID_MSG_TYPES;
            }

            Map<String, Object> msg = new HashMap<>();
            msg.put("type", msgType);
            msg.put("from", sender);
            msg.put("content", content);
            msg.put("timestamp", System.currentTimeMillis() / 1000.0);
            if (extra != null) msg.putAll(extra);

            Path inboxPath = dir.resolve(to + ".jsonl");
            try {
                Files.write(inboxPath, (mapToJson(msg) + "\n").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return "Sent " + msgType + " to " + to;
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }

        public List<Map<String, Object>> readInbox(String name) {
            Path inboxPath = dir.resolve(name + ".jsonl");
            if (!Files.exists(inboxPath)) return new ArrayList<>();

            try {
                List<String> lines = Files.readAllLines(inboxPath, StandardCharsets.UTF_8);
                Files.write(inboxPath, new byte[0]); // Clear inbox

                List<Map<String, Object>> messages = new ArrayList<>();
                for (String line : lines) {
                    if (!line.trim().isEmpty()) {
                        messages.add(parseJsonToMap(line));
                    }
                }
                return messages;
            } catch (Exception e) {
                return new ArrayList<>();
            }
        }

        public String broadcast(String sender, String content, List<String> teammates) {
            int count = 0;
            for (String name : teammates) {
                if (!name.equals(sender)) {
                    send(sender, name, content, "broadcast", null);
                    count++;
                }
            }
            return "Broadcast to " + count + " teammates";
        }
    }

    // -- TeammateManager: persistent named agents with config.json --
    static class TeammateManager {
        private final Path dir;
        protected final Path configPath;
        protected Map<String, Object> config;
        private final Map<String, Thread> threads = new ConcurrentHashMap<>();
        private final MessageBus bus;
        private final OpenAIClient client;
        private final String model;
        private final Path workDir;

        public TeammateManager(Path teamDir, MessageBus bus, OpenAIClient client, String model, Path workDir) {
            this.dir = teamDir;
            this.bus = bus;
            this.client = client;
            this.model = model;
            this.workDir = workDir;

            try {
                Files.createDirectories(dir);
            } catch (Exception e) {
                Lesson9RunSimple.log.debug("Failed to create directory {}", dir, e);
            }

            this.configPath = dir.resolve("config.json");
            this.config = loadConfig();
        }

        protected Map<String, Object> loadConfig() {
            if (Files.exists(configPath)) {
                try {
                    return parseJsonToMap(new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8));
                } catch (Exception e) {
                    Lesson9RunSimple.log.debug("Failed to load config {}", configPath, e);
                }
            }
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("team_name", "default");
            cfg.put("members", new ArrayList<>());
            return cfg;
        }

        protected void saveConfig() {
            try {
                Files.write(configPath, mapToJson(config).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Lesson9RunSimple.log.debug("Failed to save config {}", configPath, e);
            }
        }

        @SuppressWarnings("unchecked")
        protected Map<String, Object> findMember(String name) {
            List<Map<String, Object>> members = (List<Map<String, Object>>) config.get("members");
            for (Map<String, Object> m : members) {
                if (name.equals(m.get("name"))) return m;
            }
            return null;
        }

        protected void setStatus(String name, String status) {
            Map<String, Object> member = findMember(name);
            if (member != null) {
                member.put("status", status);
                saveConfig();
            }
        }

        @SuppressWarnings("unchecked")
        public String spawn(String name, String role, String prompt) {
            Map<String, Object> member = findMember(name);

            if (member != null) {
                String status = (String) member.get("status");
                if (!status.equals("idle") && !status.equals("shutdown")) {
                    return "Error: '" + name + "' is currently " + status;
                }
                member.put("status", "working");
                member.put("role", role);
            } else {
                member = new HashMap<>();
                member.put("name", name);
                member.put("role", role);
                member.put("status", "working");
                ((List<Map<String, Object>>) config.get("members")).add(member);
            }
            saveConfig();

            Thread thread = new Thread(() -> teammateLoop(name, role, prompt), "teammate-" + name);
            thread.setDaemon(true);
            threads.put(name, thread);
            thread.start();

            return "Spawned '" + name + "' (role: " + role + ")";
        }

        private void teammateLoop(String name, String role, String prompt) {
            String sysPrompt = "You are '" + name + "', role: " + role + ", at " + workDir + ". " +
                    "Use send_message to communicate. Complete your task.";

            List<ChatCompletionMessageParam> messages = new ArrayList<>();
            messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(prompt).build()));

            List<ChatCompletionTool> tools = createTeammateTools();

            for (int i = 0; i < 50; i++) {
                // Read inbox
                List<Map<String, Object>> inbox = bus.readInbox(name);
                for (Map<String, Object> msg : inbox) {
                    messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(mapToJson(msg)).build()));
                }

                try {
                    ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                            .model(ChatModel.of(model))
                            .messages(messages)
                            .tools(tools)
                            .addSystemMessage(sysPrompt)
                            .build();

                    ChatCompletion completion = client.chat().completions().create(params);
                    ChatCompletion.Choice choice = completion.choices().get(0);
                    ChatCompletionMessage assistantMessage = choice.message();

                    messages.add(ChatCompletionMessageParam.ofAssistant(assistantMessage.toParam()));

                    if (!ChatCompletion.Choice.FinishReason.TOOL_CALLS.equals(choice.finishReason())) {
                        break;
                    }

                    if (assistantMessage.toolCalls().isPresent()) {
                        for (ChatCompletionMessageToolCall toolCall : assistantMessage.toolCalls().get()) {
                            String toolName = toolCall.function().name();
                            String arguments = toolCall.function().arguments();
                            Map<String, Object> args = parseArguments(arguments);

                            String output = executeTeammateTool(name, toolName, args);
                            log.info("  [{}] {}: {}", name, toolName, output.length() > 120 ? output.substring(0, 120) : output);

                            messages.add(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                                    .toolCallId(toolCall.id())
                                    .content(output)
                                    .build()));
                        }
                    }
                } catch (Exception e) {
                    break;
                }
            }

            // Set status to idle when done
            Map<String, Object> member = findMember(name);
            if (member != null && !member.get("status").equals("shutdown")) {
                member.put("status", "idle");
                saveConfig();
            }
        }

        private String executeTeammateTool(String sender, String toolName, Map<String, Object> args) {
            switch (toolName) {
                case "bash":
                    return runBash((String) args.get("command"), workDir);
                case "read_file":
                    return runRead((String) args.get("path"), workDir);
                case "write_file":
                    return runWrite((String) args.get("path"), (String) args.get("content"), workDir);
                case "edit_file":
                    return runEdit((String) args.get("path"), (String) args.get("old_text"), (String) args.get("new_text"), workDir);
                case "send_message":
                    return bus.send(sender, (String) args.get("to"), (String) args.get("content"),
                            (String) args.getOrDefault("msg_type", "message"), null);
                case "read_inbox":
                    return listToJson(bus.readInbox(sender));
                default:
                    return "Unknown tool: " + toolName;
            }
        }

        private List<ChatCompletionTool> createTeammateTools() {
            List<ChatCompletionTool> tools = new ArrayList<>();
            tools.add(buildTool("bash", "Run a shell command.", Map.of("command", Map.of("type", "string")), List.of("command")));
            tools.add(buildTool("read_file", "Read file contents.", Map.of("path", Map.of("type", "string")), List.of("path")));
            tools.add(buildTool("write_file", "Write content to file.", Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")), List.of("path", "content")));
            tools.add(buildTool("edit_file", "Replace exact text in file.", Map.of("path", Map.of("type", "string"), "old_text", Map.of("type", "string"), "new_text", Map.of("type", "string")), List.of("path", "old_text", "new_text")));
            tools.add(buildTool("send_message", "Send message to a teammate.", Map.of("to", Map.of("type", "string"), "content", Map.of("type", "string"), "msg_type", Map.of("type", "string")), List.of("to", "content")));
            tools.add(buildTool("read_inbox", "Read and drain your inbox.", Map.of(), List.of()));
            return tools;
        }

        private static ChatCompletionTool buildTool(String name, String description, Map<String, Object> properties, List<String> required) {
            return ChatCompletionTool.builder()
                    .function(FunctionDefinition.builder()
                            .name(name)
                            .description(description)
                            .parameters(FunctionParameters.builder()
                                    .putAdditionalProperty("type", JsonValue.from("object"))
                                    .putAdditionalProperty("properties", JsonValue.from(properties))
                                    .putAdditionalProperty("required", JsonValue.from(required))
                                    .build())
                            .build())
                    .build();
        }

        @SuppressWarnings("unchecked")
        public String listAll() {
            List<Map<String, Object>> members = (List<Map<String, Object>>) config.get("members");
            if (members.isEmpty()) return "No teammates.";

            StringBuilder sb = new StringBuilder("Team: ").append(config.get("team_name")).append("\n");
            for (Map<String, Object> m : members) {
                sb.append("  ").append(m.get("name")).append(" (").append(m.get("role")).append("): ").append(m.get("status")).append("\n");
            }
            return sb.toString().trim();
        }

        @SuppressWarnings("unchecked")
        public List<String> memberNames() {
            List<Map<String, Object>> members = (List<Map<String, Object>>) config.get("members");
            return members.stream().map(m -> (String) m.get("name")).collect(Collectors.toList());
        }
    }

    // -- Tool implementations --
    private static String runBash(String command, Path workDir) {
        String[] dangerous = {"rm -rf /", "sudo", "shutdown", "reboot"};
        for (String d : dangerous) {
            if (command.contains(d)) return "Error: Dangerous command blocked";
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
    static Path resolveWorkspacePath(Path workDir, String inputPath, boolean windows) {
        String normalizedInput = inputPath;
        if (windows) {
            var inputMatcher = WINDOWS_ABSOLUTE_PATH.matcher(inputPath);
            var workDirMatcher = WINDOWS_ABSOLUTE_PATH.matcher(workDir.toString().replace("\\", "/"));
            if (inputMatcher.matches() && workDirMatcher.matches()
                    && inputMatcher.group(1).equalsIgnoreCase(workDirMatcher.group(1))) {
                String workDirRest = workDirMatcher.group(2).replace("\\", "/");
                String inputRest = inputMatcher.group(2).replace("\\", "/");
                String workDirPrefix = workDirRest.endsWith("/") ? workDirRest : workDirRest + "/";

                if (inputRest.equalsIgnoreCase(workDirRest)) {
                    return workDir;
                }
                if (inputRest.regionMatches(true, 0, workDirPrefix, 0, workDirPrefix.length())) {
                    return workDir.resolve(inputRest.substring(workDirPrefix.length()));
                }
            }
        } else {
            var matcher = WINDOWS_ABSOLUTE_PATH.matcher(inputPath);
            if (matcher.matches()) {
                String drive = matcher.group(1).toLowerCase(Locale.ROOT);
                String rest = matcher.group(2).replace("\\", "/");
                normalizedInput = "/mnt/" + drive + "/" + rest;
            }
        }

        Path candidate = Paths.get(normalizedInput);
        return candidate.isAbsolute() ? candidate : workDir.resolve(normalizedInput);
    }

    private static String runRead(String path, Path workDir) {
        try {
            Path p = resolveWorkspacePath(workDir, path, System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")).normalize();
            if (!p.startsWith(workDir)) return "Error: Path escapes workspace";
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            return truncate(String.join("\n", lines), 50000);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private static String runWrite(String path, String content, Path workDir) {
        try {
            Path p = resolveWorkspacePath(workDir, path, System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")).normalize();
            if (!p.startsWith(workDir)) return "Error: Path escapes workspace";
            Files.createDirectories(p.getParent());
            Files.write(p, content.getBytes(StandardCharsets.UTF_8));
            return "Wrote " + content.length() + " bytes";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private static String runEdit(String path, String oldText, String newText, Path workDir) {
        try {
            Path p = resolveWorkspacePath(workDir, path, System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")).normalize();
            if (!p.startsWith(workDir)) return "Error: Path escapes workspace";
            String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            if (!content.contains(oldText)) return "Error: Text not found in " + path;
            content = content.replaceFirst(escapeRegex(oldText), newText);
            Files.write(p, content.getBytes(StandardCharsets.UTF_8));
            return "Edited " + path;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private static String escapeRegex(String s) {
        return s.replace("\\", "\\\\").replace("$", "\\$")
                .replace(".", "\\.").replace("*", "\\*")
                .replace("+", "\\+").replace("?", "\\?")
                .replace("[", "\\[").replace("]", "\\]")
                .replace("(", "\\(").replace(")", "\\)")
                .replace("{", "\\{").replace("}", "\\}")
                .replace("|", "\\|").replace("^", "\\^");
    }

    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private String executeTool(Map<String, ToolHandler> handlers, String toolName, String arguments) {
        ToolHandler handler = handlers.get(toolName);
        if (handler == null) return "Unknown tool: " + toolName;
        try {
            return handler.execute(parseArguments(arguments));
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // -- Tool definitions for lead --
    private List<ChatCompletionTool> createTools() {
        List<ChatCompletionTool> tools = new ArrayList<>();
        tools.add(createTool("bash", "Run a shell command.", Map.of("command", Map.of("type", "string")), List.of("command")));
        tools.add(createTool("read_file", "Read file contents.", Map.of("path", Map.of("type", "string"), "limit", Map.of("type", "integer")), List.of("path")));
        tools.add(createTool("write_file", "Write content to file.", Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")), List.of("path", "content")));
        tools.add(createTool("edit_file", "Replace exact text in file.", Map.of("path", Map.of("type", "string"), "old_text", Map.of("type", "string"), "new_text", Map.of("type", "string")), List.of("path", "old_text", "new_text")));
        tools.add(createTool("spawn_teammate", "Spawn a persistent teammate that runs in its own thread.", Map.of("name", Map.of("type", "string"), "role", Map.of("type", "string"), "prompt", Map.of("type", "string")), List.of("name", "role", "prompt")));
        tools.add(createTool("list_teammates", "List all teammates with name, role, status.", Map.of(), List.of()));
        tools.add(createTool("send_message", "Send a message to a teammate's inbox.", Map.of("to", Map.of("type", "string"), "content", Map.of("type", "string"), "msg_type", Map.of("type", "string")), List.of("to", "content")));
        tools.add(createTool("read_inbox", "Read and drain the lead's inbox.", Map.of(), List.of()));
        tools.add(createTool("broadcast", "Send a message to all teammates.", Map.of("content", Map.of("type", "string")), List.of("content")));
        return tools;
    }

    private ChatCompletionTool createTool(String name, String description, Map<String, Object> properties, List<String> required) {
        return ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name(name)
                        .description(description)
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(properties))
                                .putAdditionalProperty("required", JsonValue.from(required))
                                .build())
                        .build())
                .build();
    }

    private Map<String, ToolHandler> createHandlers() {
        Map<String, ToolHandler> handlers = new HashMap<>();
        handlers.put("bash", args -> runBash((String) args.get("command"), workDir));
        handlers.put("read_file", args -> runRead((String) args.get("path"), workDir));
        handlers.put("write_file", args -> runWrite((String) args.get("path"), (String) args.get("content"), workDir));
        handlers.put("edit_file", args -> runEdit((String) args.get("path"), (String) args.get("old_text"), (String) args.get("new_text"), workDir));
        handlers.put("spawn_teammate", args -> team.spawn((String) args.get("name"), (String) args.get("role"), (String) args.get("prompt")));
        handlers.put("list_teammates", args -> team.listAll());
        handlers.put("send_message", args -> bus.send("lead", (String) args.get("to"), (String) args.get("content"), (String) args.getOrDefault("msg_type", "message"), null));
        handlers.put("read_inbox", args -> listToJson(bus.readInbox("lead")));
        handlers.put("broadcast", args -> bus.broadcast("lead", (String) args.get("content"), team.memberNames()));
        return handlers;
    }

    // -- JSON helpers --
    public static Map<String, Object> parseJsonToMap(String json) {
        Map<String, Object> result = new HashMap<>();
        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) {
            json = json.substring(1, json.length() - 1);
            for (String pair : splitTopLevel(json)) {
                int colonIdx = findTopLevelColon(pair);
                if (colonIdx > 0) {
                    String key = pair.substring(0, colonIdx).trim().replace("\"", "");
                    String value = pair.substring(colonIdx + 1).trim();
                    result.put(key, parseValue(value));
                }
            }
        }
        return result;
    }

    private static List<String> splitTopLevel(String s) {
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

    private static int findTopLevelColon(String s) {
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && inString) { i++; continue; }
            if (c == '"') inString = !inString;
            if (c == ':' && !inString) return i;
        }
        return -1;
    }

    private static Object parseValue(String value) {
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"");
        } else if (value.startsWith("{") && value.endsWith("}")) {
            return parseJsonToMap(value);
        } else if (value.startsWith("[")) {
            List<Object> list = new ArrayList<>();
            if (!value.equals("[]")) {
                String inner = value.substring(1, value.length() - 1);
                for (String item : splitTopLevel(inner)) {
                    item = item.trim();
                    if (!item.isEmpty()) list.add(parseValue(item));
                }
            }
            return list;
        } else if ("true".equals(value)) {
            return true;
        } else if ("false".equals(value)) {
            return false;
        } else if ("null".equals(value)) {
            return null;
        } else if (value.matches("-?\\d+")) {
            return Integer.parseInt(value);
        } else if (value.matches("-?\\d+\\.\\d+([eE][+-]?\\d+)?") || value.matches("-?\\d+[eE][+-]?\\d+")) {
            return Double.parseDouble(value);
        }
        return value;
    }

    public static String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(e.getKey()).append("\":").append(valueToJson(e.getValue()));
        }
        return sb.append("}").toString();
    }

    public static String valueToJson(Object value) {
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
        if (value instanceof Map) return mapToJson((Map<String, Object>) value);
        return "\"" + value.toString() + "\"";
    }

    public static String listToJson(List<Map<String, Object>> list) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map<String, Object> item : list) {
            if (!first) sb.append(",");
            first = false;
            sb.append(mapToJson(item));
        }
        return sb.append("]").toString();
    }

    private static Map<String, Object> parseArguments(String json) {
        return parseJsonToMap(json);
    }

    @FunctionalInterface
    interface ToolHandler {
        String execute(Map<String, Object> args) throws Exception;
    }
}
