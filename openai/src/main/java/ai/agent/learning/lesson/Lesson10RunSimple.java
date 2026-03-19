package ai.agent.learning.lesson;

import ai.agent.learning.base.RunSimple;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.*;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Lesson10RunSimple - Team Protocols
 *
 * Shutdown protocol and plan approval protocol, both using the same
 * request_id correlation pattern. Builds on s09's team messaging.
 *
 * Key insight: "Same request_id correlation pattern, two domains."
 */
@Component
public class Lesson10RunSimple implements RunSimple {

    private static final Logger log = LoggerFactory.getLogger(Lesson10RunSimple.class);

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

    // Request trackers
    private final Map<String, Map<String, Object>> shutdownRequests = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> planRequests = new ConcurrentHashMap<>();
    private final ReentrantLock trackerLock = new ReentrantLock();

    @Override
    public void run(String userPrompt) {
        log.info("Starting Lesson10 (Team Protocols) with model: {}", modelName);

        workDir = Paths.get(System.getProperty("user.dir"));
        teamDir = workDir.resolve(".team");
        inboxDir = teamDir.resolve("inbox");

        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
        client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .proxy(proxy)
                .build();

        bus = new MessageBus(inboxDir);
        team = new TeammateManager(teamDir, bus, client, modelName, workDir, shutdownRequests, planRequests, trackerLock);

        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(userPrompt).build()));

        List<ChatCompletionTool> tools = createTools();
        Map<String, ToolHandler> handlers = createHandlers();

        String sysPrompt = systemPrompt != null && !systemPrompt.isEmpty()
                ? systemPrompt
                : "You are a team lead at " + workDir + ". Manage teammates with shutdown and plan approval protocols.";

        agentLoop(messages, tools, handlers, sysPrompt);
    }

    private void agentLoop(List<ChatCompletionMessageParam> messages,
                           List<ChatCompletionTool> tools, Map<String, ToolHandler> handlers, String sysPrompt) {
        while (true) {
            List<Map<String, Object>> inbox = bus.readInbox("lead");
            if (!inbox.isEmpty()) {
                messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content("<inbox>" + Lesson9RunSimple.listToJson(inbox) + "</inbox>").build()));
                messages.add(ChatCompletionMessageParam.ofAssistant(ChatCompletionAssistantMessageParam.builder().content("Noted inbox messages.").build()));
            }

            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model(ChatModel.of(modelName))
                    .messages(messages)
                    .tools(tools)
                    .addSystemMessage(sysPrompt)
                    .build();

            ChatCompletion completion = client.chat().completions().create(params);
            ChatCompletion.Choice choice = completion.choices().get(0);
            ChatCompletionMessage assistantMessage = choice.message();

            messages.add(ChatCompletionMessageParam.ofAssistant(assistantMessage.toParam()));

            if (choice.finishReason() != ChatCompletion.Choice.FinishReason.TOOL_CALLS) {
                assistantMessage.content().ifPresent(content -> log.info("Assistant: {}", content));
                break;
            }

            if (assistantMessage.toolCalls().isPresent()) {
                for (ChatCompletionMessageToolCall toolCall : assistantMessage.toolCalls().get()) {
                    String toolName = toolCall.function().name();
                    String arguments = toolCall.function().arguments();
                    log.info("Tool call: {} with args: {}", toolName, arguments);

                    String output = executeTool(handlers, toolName, arguments);
                    log.info("Output (truncated): {}", truncate(output, 200));

                    messages.add(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                            .toolCallId(toolCall.id())
                            .content(output)
                            .build()));
                }
            }
        }
    }

    // -- Shutdown protocol handler --
    private String handleShutdownRequest(String teammate) {
        String reqId = UUID.randomUUID().toString().substring(0, 8);

        trackerLock.lock();
        try {
            shutdownRequests.put(reqId, Map.of("target", teammate, "status", "pending"));
        } finally {
            trackerLock.unlock();
        }

        bus.send("lead", teammate, "Please shut down gracefully.", "shutdown_request",
                Map.of("request_id", reqId));

        return "Shutdown request " + reqId + " sent to '" + teammate + "' (status: pending)";
    }

    // -- Plan approval handler --
    private String handlePlanReview(String requestId, boolean approve, String feedback) {
        trackerLock.lock();
        Map<String, Object> req;
        try {
            req = planRequests.get(requestId);
        } finally {
            trackerLock.unlock();
        }

        if (req == null) return "Error: Unknown plan request_id '" + requestId + "'";

        String status = approve ? "approved" : "rejected";
        trackerLock.lock();
        try {
            req.put("status", status);
        } finally {
            trackerLock.unlock();
        }

        bus.send("lead", (String) req.get("from"), feedback != null ? feedback : "", "plan_approval_response",
                Map.of("request_id", requestId, "approve", approve, "feedback", feedback != null ? feedback : ""));

        return "Plan " + status + " for '" + req.get("from") + "'";
    }

    private String checkShutdownStatus(String requestId) {
        trackerLock.lock();
        try {
            Map<String, Object> req = shutdownRequests.get(requestId);
            return req != null ? Lesson9RunSimple.mapToJson(req) : "{\"error\": \"not found\"}";
        } finally {
            trackerLock.unlock();
        }
    }

    // -- MessageBus and TeammateManager (simplified, extends Lesson9) --
    static class MessageBus {
        private final Path dir;

        public MessageBus(Path inboxDir) {
            this.dir = inboxDir;
            try { Files.createDirectories(dir); } catch (Exception ignored) {}
        }

        public String send(String sender, String to, String content, String msgType, Map<String, Object> extra) {
            if (!VALID_MSG_TYPES.contains(msgType)) return "Error: Invalid type '" + msgType + "'";

            Map<String, Object> msg = new HashMap<>();
            msg.put("type", msgType);
            msg.put("from", sender);
            msg.put("content", content);
            msg.put("timestamp", System.currentTimeMillis() / 1000.0);
            if (extra != null) msg.putAll(extra);

            try {
                Path inboxPath = dir.resolve(to + ".jsonl");
                Files.write(inboxPath, (Lesson9RunSimple.mapToJson(msg) + "\n").getBytes(StandardCharsets.UTF_8),
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
                Files.write(inboxPath, new byte[0]);

                List<Map<String, Object>> messages = new ArrayList<>();
                for (String line : lines) {
                    if (!line.trim().isEmpty()) messages.add(Lesson9RunSimple.parseJsonToMap(line));
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

    static class TeammateManager {
        private final Path dir;
        protected final Path configPath;
        protected Map<String, Object> config;
        private final Map<String, java.lang.Thread> threads = new ConcurrentHashMap<>();
        private final MessageBus bus;
        private final OpenAIClient client;
        private final String model;
        private final Path workDir;
        private final Map<String, Map<String, Object>> shutdownRequests;
        private final Map<String, Map<String, Object>> planRequests;
        private final ReentrantLock trackerLock;

        public TeammateManager(Path teamDir, MessageBus bus, OpenAIClient client, String model, Path workDir,
                               Map<String, Map<String, Object>> shutdownRequests,
                               Map<String, Map<String, Object>> planRequests,
                               ReentrantLock trackerLock) {
            this.dir = teamDir;
            this.bus = bus;
            this.client = client;
            this.model = model;
            this.workDir = workDir;
            this.shutdownRequests = shutdownRequests;
            this.planRequests = planRequests;
            this.trackerLock = trackerLock;

            try { Files.createDirectories(dir); } catch (Exception ignored) {}
            this.configPath = dir.resolve("config.json");
            this.config = loadConfig();
        }

        protected Map<String, Object> loadConfig() {
            if (Files.exists(configPath)) {
                try {
                    return Lesson9RunSimple.parseJsonToMap(new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            }
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("team_name", "default");
            cfg.put("members", new ArrayList<>());
            return cfg;
        }

        protected void saveConfig() {
            try { Files.write(configPath, Lesson9RunSimple.mapToJson(config).getBytes(StandardCharsets.UTF_8)); }
            catch (Exception ignored) {}
        }

        @SuppressWarnings("unchecked")
        protected Map<String, Object> findMember(String name) {
            for (Map<String, Object> m : (List<Map<String, Object>>) config.get("members")) {
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

            java.lang.Thread thread = new java.lang.Thread(() -> teammateLoop(name, role, prompt), "teammate-" + name);
            thread.setDaemon(true);
            threads.put(name, thread);
            thread.start();

            return "Spawned '" + name + "' (role: " + role + ")";
        }

        private void teammateLoop(String name, String role, String prompt) {
            String sysPrompt = "You are '" + name + "', role: " + role + ", at " + workDir + ". " +
                    "Submit plans via plan_approval before major work. Respond to shutdown_request with shutdown_response.";

            List<ChatCompletionMessageParam> messages = new ArrayList<>();
            messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(prompt).build()));

            List<ChatCompletionTool> tools = createTeammateTools();
            boolean shouldExit = false;

            for (int i = 0; i < 50; i++) {
                List<Map<String, Object>> inbox = bus.readInbox(name);
                for (Map<String, Object> msg : inbox) {
                    messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(Lesson9RunSimple.mapToJson(msg)).build()));
                }

                if (shouldExit) break;

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

                    if (choice.finishReason() != ChatCompletion.Choice.FinishReason.TOOL_CALLS) break;

                    if (assistantMessage.toolCalls().isPresent()) {
                        for (ChatCompletionMessageToolCall toolCall : assistantMessage.toolCalls().get()) {
                            String toolName = toolCall.function().name();
                            Map<String, Object> args = Lesson9RunSimple.parseJsonToMap(toolCall.function().arguments());

                            String output = executeTeammateTool(name, toolName, args);
                            log.info("  [{}] {}: {}", name, toolName, truncate(output, 120));

                            messages.add(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                                    .toolCallId(toolCall.id())
                                    .content(output)
                                    .build()));

                            if (toolName.equals("shutdown_response") && Boolean.TRUE.equals(args.get("approve"))) {
                                shouldExit = true;
                            }
                        }
                    }
                } catch (Exception e) {
                    break;
                }
            }

            Map<String, Object> member = findMember(name);
            if (member != null) {
                member.put("status", shouldExit ? "shutdown" : "idle");
                saveConfig();
            }
        }

        private String executeTeammateTool(String sender, String toolName, Map<String, Object> args) {
            switch (toolName) {
                case "bash": return runBash((String) args.get("command"), workDir);
                case "read_file": return runRead((String) args.get("path"), workDir);
                case "write_file": return runWrite((String) args.get("path"), (String) args.get("content"), workDir);
                case "edit_file": return runEdit((String) args.get("path"), (String) args.get("old_text"), (String) args.get("new_text"), workDir);
                case "send_message": return bus.send(sender, (String) args.get("to"), (String) args.get("content"), (String) args.getOrDefault("msg_type", "message"), null);
                case "read_inbox": return Lesson9RunSimple.listToJson(bus.readInbox(sender));
                case "shutdown_response": {
                    String reqId = (String) args.get("request_id");
                    boolean approve = Boolean.TRUE.equals(args.get("approve"));
                    trackerLock.lock();
                    try {
                        if (shutdownRequests.containsKey(reqId)) {
                            shutdownRequests.get(reqId).put("status", approve ? "approved" : "rejected");
                        }
                    } finally {
                        trackerLock.unlock();
                    }
                    bus.send(sender, "lead", (String) args.getOrDefault("reason", ""), "shutdown_response",
                            Map.of("request_id", reqId, "approve", approve));
                    return "Shutdown " + (approve ? "approved" : "rejected");
                }
                case "plan_approval": {
                    String planText = (String) args.get("plan");
                    String reqId = UUID.randomUUID().toString().substring(0, 8);
                    trackerLock.lock();
                    try {
                        planRequests.put(reqId, Map.of("from", sender, "plan", planText, "status", "pending"));
                    } finally {
                        trackerLock.unlock();
                    }
                    bus.send(sender, "lead", planText, "plan_approval_response",
                            Map.of("request_id", reqId, "plan", planText));
                    return "Plan submitted (request_id=" + reqId + "). Waiting for lead approval.";
                }
                default: return "Unknown tool: " + toolName;
            }
        }

        protected List<ChatCompletionTool> createTeammateTools() {
            List<ChatCompletionTool> tools = new ArrayList<>();
            tools.add(buildTool("bash", "Run a shell command.", Map.of("command", Map.of("type", "string")), List.of("command")));
            tools.add(buildTool("read_file", "Read file contents.", Map.of("path", Map.of("type", "string")), List.of("path")));
            tools.add(buildTool("write_file", "Write content to file.", Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")), List.of("path", "content")));
            tools.add(buildTool("edit_file", "Replace exact text in file.", Map.of("path", Map.of("type", "string"), "old_text", Map.of("type", "string"), "new_text", Map.of("type", "string")), List.of("path", "old_text", "new_text")));
            tools.add(buildTool("send_message", "Send message to a teammate.", Map.of("to", Map.of("type", "string"), "content", Map.of("type", "string"), "msg_type", Map.of("type", "string")), List.of("to", "content")));
            tools.add(buildTool("read_inbox", "Read and drain your inbox.", Map.of(), List.of()));
            tools.add(buildTool("shutdown_response", "Respond to a shutdown request.", Map.of("request_id", Map.of("type", "string"), "approve", Map.of("type", "boolean"), "reason", Map.of("type", "string")), List.of("request_id", "approve")));
            tools.add(buildTool("plan_approval", "Submit a plan for lead approval.", Map.of("plan", Map.of("type", "string")), List.of("plan")));
            return tools;
        }

        protected static ChatCompletionTool buildTool(String name, String description, Map<String, Object> properties, List<String> required) {
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
            return ((List<Map<String, Object>>) config.get("members")).stream()
                    .map(m -> (String) m.get("name")).collect(Collectors.toList());
        }
    }

    // -- Base tools (same as previous lessons) --
    public static String runBash(String command, Path workDir) {
        for (String d : List.of("rm -rf /", "sudo", "shutdown", "reboot")) {
            if (command.contains(d)) return "Error: Dangerous command blocked";
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            if (!process.waitFor(120, TimeUnit.SECONDS)) { process.destroyForcibly(); return "Error: Timeout"; }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String output = reader.lines().collect(Collectors.joining("\n")).trim();
                return output.isEmpty() ? "(no output)" : truncate(output, 50000);
            }
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    public static String runRead(String path, Path workDir) {
        try {
            Path p = workDir.resolve(path).normalize();
            if (!p.startsWith(workDir)) return "Error: Path escapes workspace";
            return truncate(String.join("\n", Files.readAllLines(p, StandardCharsets.UTF_8)), 50000);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    public static String runWrite(String path, String content, Path workDir) {
        try {
            Path p = workDir.resolve(path).normalize();
            if (!p.startsWith(workDir)) return "Error: Path escapes workspace";
            Files.createDirectories(p.getParent());
            Files.write(p, content.getBytes(StandardCharsets.UTF_8));
            return "Wrote " + content.length() + " bytes";
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    public static String runEdit(String path, String oldText, String newText, Path workDir) {
        try {
            Path p = workDir.resolve(path).normalize();
            if (!p.startsWith(workDir)) return "Error: Path escapes workspace";
            String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            if (!content.contains(oldText)) return "Error: Text not found";
            Files.write(p, content.replaceFirst(oldText.replace("$", "\\$").replace(".", "\\."), newText).getBytes(StandardCharsets.UTF_8));
            return "Edited " + path;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String truncate(String s, int maxLen) { return s != null && s.length() > maxLen ? s.substring(0, maxLen) : s; }

    private String executeTool(Map<String, ToolHandler> handlers, String toolName, String arguments) {
        ToolHandler handler = handlers.get(toolName);
        if (handler == null) return "Unknown tool: " + toolName;
        try { return handler.execute(Lesson9RunSimple.parseJsonToMap(arguments)); }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    // -- Tool definitions --
    private List<ChatCompletionTool> createTools() {
        List<ChatCompletionTool> tools = new ArrayList<>();
        tools.add(createTool("bash", "Run a shell command.", Map.of("command", Map.of("type", "string")), List.of("command")));
        tools.add(createTool("read_file", "Read file contents.", Map.of("path", Map.of("type", "string"), "limit", Map.of("type", "integer")), List.of("path")));
        tools.add(createTool("write_file", "Write content to file.", Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")), List.of("path", "content")));
        tools.add(createTool("edit_file", "Replace exact text in file.", Map.of("path", Map.of("type", "string"), "old_text", Map.of("type", "string"), "new_text", Map.of("type", "string")), List.of("path", "old_text", "new_text")));
        tools.add(createTool("spawn_teammate", "Spawn a persistent teammate.", Map.of("name", Map.of("type", "string"), "role", Map.of("type", "string"), "prompt", Map.of("type", "string")), List.of("name", "role", "prompt")));
        tools.add(createTool("list_teammates", "List all teammates.", Map.of(), List.of()));
        tools.add(createTool("send_message", "Send a message to a teammate.", Map.of("to", Map.of("type", "string"), "content", Map.of("type", "string"), "msg_type", Map.of("type", "string")), List.of("to", "content")));
        tools.add(createTool("read_inbox", "Read and drain the lead's inbox.", Map.of(), List.of()));
        tools.add(createTool("broadcast", "Send a message to all teammates.", Map.of("content", Map.of("type", "string")), List.of("content")));
        tools.add(createTool("shutdown_request", "Request a teammate to shut down gracefully.", Map.of("teammate", Map.of("type", "string")), List.of("teammate")));
        tools.add(createTool("shutdown_response", "Check the status of a shutdown request by request_id.", Map.of("request_id", Map.of("type", "string")), List.of("request_id")));
        tools.add(createTool("plan_approval", "Approve or reject a teammate's plan.", Map.of("request_id", Map.of("type", "string"), "approve", Map.of("type", "boolean"), "feedback", Map.of("type", "string")), List.of("request_id", "approve")));
        return tools;
    }

    private ChatCompletionTool createTool(String name, String description, Map<String, Object> properties, List<String> required) {
        return ChatCompletionTool.builder()
                .function(FunctionDefinition.builder().name(name).description(description)
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
        handlers.put("read_inbox", args -> Lesson9RunSimple.listToJson(bus.readInbox("lead")));
        handlers.put("broadcast", args -> bus.broadcast("lead", (String) args.get("content"), team.memberNames()));
        handlers.put("shutdown_request", args -> handleShutdownRequest((String) args.get("teammate")));
        handlers.put("shutdown_response", args -> checkShutdownStatus((String) args.get("request_id")));
        handlers.put("plan_approval", args -> handlePlanReview((String) args.get("request_id"), Boolean.TRUE.equals(args.get("approve")), (String) args.get("feedback")));
        return handlers;
    }

    @FunctionalInterface
    interface ToolHandler { String execute(Map<String, Object> args) throws Exception; }
}
