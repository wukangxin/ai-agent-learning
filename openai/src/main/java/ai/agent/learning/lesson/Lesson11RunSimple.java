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

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Lesson11RunSimple - Autonomous Agents
 *
 * Idle cycle with task board polling, auto-claiming unclaimed tasks, and
 * identity re-injection after context compression.
 *
 * Key insight: "The agent finds work itself."
 */
@Component
public class Lesson11RunSimple implements RunSimple {

    private static final Logger log = LoggerFactory.getLogger(Lesson11RunSimple.class);
    private static final int POLL_INTERVAL = 5;
    private static final int IDLE_TIMEOUT = 60;

    @Value("${openai.api-key}") private String apiKey;
    @Value("${openai.base-url}") private String baseUrl;
    @Value("${openai.model}") private String modelName;
    @Value("${openai.system-prompt}") private String systemPrompt;
    @Value("${proxy.host}") private String proxyHost;
    @Value("${proxy.port}") private int proxyPort;

    private Path workDir, teamDir, inboxDir, tasksDir;
    private OpenAIClient client;
    private Lesson10RunSimple.MessageBus bus;
    private TeammateManager team;

    // Request trackers
    private final Map<String, Map<String, Object>> shutdownRequests = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> planRequests = new ConcurrentHashMap<>();
    private final ReentrantLock trackerLock = new ReentrantLock();
    private final ReentrantLock claimLock = new ReentrantLock();

    @Override
    public void run(String userPrompt) {
        log.info("Starting Lesson11 (Autonomous Agents) with model: {}", modelName);

        workDir = Paths.get(System.getProperty("user.dir"));
        teamDir = workDir.resolve(".team");
        inboxDir = teamDir.resolve("inbox");
        tasksDir = workDir.resolve(".tasks");

        try { Files.createDirectories(tasksDir); } catch (Exception ignored) {}

        client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey).baseUrl(baseUrl)
                .proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                        new java.net.InetSocketAddress(proxyHost, proxyPort)))
                .build();

        bus = new Lesson10RunSimple.MessageBus(inboxDir);
        team = new TeammateManager();

        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(userPrompt).build()));

        String sysPrompt = systemPrompt != null && !systemPrompt.isEmpty()
                ? systemPrompt : "You are a team lead at " + workDir + ". Teammates are autonomous.";

        agentLoop(messages, sysPrompt);
    }

    private void agentLoop(List<ChatCompletionMessageParam> messages, String sysPrompt) {
        while (true) {
            List<Map<String, Object>> inbox = bus.readInbox("lead");
            if (!inbox.isEmpty()) {
                messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content("<inbox>" + Lesson9RunSimple.listToJson(inbox) + "</inbox>").build()));
                messages.add(ChatCompletionMessageParam.ofAssistant(ChatCompletionAssistantMessageParam.builder().content("Noted inbox messages.").build()));
            }

            ChatCompletion completion = client.chat().completions().create(
                    ChatCompletionCreateParams.builder()
                            .model(ChatModel.of(modelName))
                            .messages(messages)
                            .tools(createTools())
                            .addSystemMessage(sysPrompt)
                            .build());

            ChatCompletion.Choice choice = completion.choices().get(0);
            messages.add(ChatCompletionMessageParam.ofAssistant(choice.message().toParam()));

            if (choice.finishReason() != ChatCompletion.Choice.FinishReason.TOOL_CALLS) {
                choice.message().content().ifPresent(c -> log.info("Assistant: {}", c));
                break;
            }

            if (choice.message().toolCalls().isPresent()) {
                for (ChatCompletionMessageToolCall tc : choice.message().toolCalls().get()) {
                    String output = executeTool(tc.function().name(), tc.function().arguments());
                    messages.add(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                            .toolCallId(tc.id()).content(output).build()));
                }
            }
        }
    }

    // -- Task board operations --
    private List<Map<String, Object>> scanUnclaimedTasks() {
        List<Map<String, Object>> unclaimed = new ArrayList<>();
        try {
            Files.list(tasksDir).filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                    .forEach(p -> {
                        try {
                            Map<String, Object> t = Lesson9RunSimple.parseJsonToMap(
                                    new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                            if ("pending".equals(t.get("status")) && t.get("owner") == null &&
                                    (t.get("blockedBy") == null || ((List<?>) t.get("blockedBy")).isEmpty())) {
                                unclaimed.add(t);
                            }
                        } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
        return unclaimed;
    }

    private String claimTask(int taskId, String owner) {
        claimLock.lock();
        try {
            Path p = tasksDir.resolve("task_" + taskId + ".json");
            if (!Files.exists(p)) return "Error: Task " + taskId + " not found";
            Map<String, Object> task = Lesson9RunSimple.parseJsonToMap(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            task.put("owner", owner);
            task.put("status", "in_progress");
            Files.write(p, Lesson9RunSimple.mapToJson(task).getBytes(StandardCharsets.UTF_8));
            return "Claimed task #" + taskId + " for " + owner;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
        finally { claimLock.unlock(); }
    }

    private Map<String, Object> makeIdentityBlock(String name, String role, String teamName) {
        return Map.of("role", "user", "content",
                "<identity>You are '" + name + "', role: " + role + ", team: " + teamName + ". Continue your work.</identity>");
    }

    // -- TeammateManager with autonomous idle polling --
    class TeammateManager extends Lesson10RunSimple.TeammateManager {
        TeammateManager() {
            super(teamDir, bus, client, modelName, workDir, shutdownRequests, planRequests, trackerLock);
        }

        // Override spawn to use autonomous loop
        @Override
        @SuppressWarnings("unchecked")
        public String spawn(String name, String role, String prompt) {
            Map<String, Object> member = findMember(name);
            if (member != null) {
                String status = (String) member.get("status");
                if (!status.equals("idle") && !status.equals("shutdown"))
                    return "Error: '" + name + "' is currently " + status;
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

            new java.lang.Thread(() -> autonomousLoop(name, role, prompt), "teammate-" + name).start();
            return "Spawned '" + name + "' (role: " + role + ")";
        }

        private void autonomousLoop(String name, String role, String prompt) {
            String teamName = config.get("team_name").toString();
            String sysPrompt = "You are '" + name + "', role: " + role + ", team: " + teamName + ", at " + workDir + ". " +
                    "Use idle tool when you have no more work. You will auto-claim new tasks.";

            List<ChatCompletionMessageParam> messages = new ArrayList<>();
            messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(prompt).build()));

            while (true) {
                // -- WORK PHASE --
                for (int i = 0; i < 50; i++) {
                    List<Map<String, Object>> inbox = bus.readInbox(name);
                    for (Map<String, Object> msg : inbox) {
                        if ("shutdown_request".equals(msg.get("type"))) {
                            setStatus(name, "shutdown");
                            return;
                        }
                        messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(Lesson9RunSimple.mapToJson(msg)).build()));
                    }

                    try {
                        ChatCompletion completion = client.chat().completions().create(
                                ChatCompletionCreateParams.builder()
                                        .model(ChatModel.of(modelName))
                                        .messages(messages)
                                        .tools(createTeammateTools(name))
                                        .addSystemMessage(sysPrompt)
                                        .build());

                        ChatCompletion.Choice choice = completion.choices().get(0);
                        messages.add(ChatCompletionMessageParam.ofAssistant(choice.message().toParam()));

                        if (choice.finishReason() != ChatCompletion.Choice.FinishReason.TOOL_CALLS) break;

                        boolean idleRequested = false;
                        if (choice.message().toolCalls().isPresent()) {
                            for (ChatCompletionMessageToolCall tc : choice.message().toolCalls().get()) {
                                Map<String, Object> args = Lesson9RunSimple.parseJsonToMap(tc.function().arguments());
                                String output;

                                if ("idle".equals(tc.function().name())) {
                                    idleRequested = true;
                                    output = "Entering idle phase. Will poll for new tasks.";
                                } else {
                                    output = executeTeammateTool(name, tc.function().name(), args);
                                }

                                log.info("  [{}] {}: {}", name, tc.function().name(), output.length() > 120 ? output.substring(0, 120) : output);
                                messages.add(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                                        .toolCallId(tc.id()).content(output).build()));
                            }
                        }

                        if (idleRequested) break;
                    } catch (Exception e) {
                        setStatus(name, "idle");
                        return;
                    }
                }

                // -- IDLE PHASE: poll for inbox messages and unclaimed tasks --
                setStatus(name, "idle");
                boolean resume = false;
                int polls = IDLE_TIMEOUT / POLL_INTERVAL;

                for (int i = 0; i < polls; i++) {
                    try { TimeUnit.SECONDS.sleep(POLL_INTERVAL); } catch (Exception ignored) {}

                    List<Map<String, Object>> inbox = bus.readInbox(name);
                    if (!inbox.isEmpty()) {
                        for (Map<String, Object> msg : inbox) {
                            if ("shutdown_request".equals(msg.get("type"))) {
                                setStatus(name, "shutdown");
                                return;
                            }
                            messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(Lesson9RunSimple.mapToJson(msg)).build()));
                        }
                        resume = true;
                        break;
                    }

                    List<Map<String, Object>> unclaimed = scanUnclaimedTasks();
                    if (!unclaimed.isEmpty()) {
                        Map<String, Object> task = unclaimed.get(0);
                        int taskId = ((Number) task.get("id")).intValue();
                        claimTask(taskId, name);

                        // Identity re-injection for compressed contexts
                        if (messages.size() <= 3) {
                            messages.add(0, ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(
                                    "<identity>You are '" + name + "', role: " + role + ", team: " + teamName + ".</identity>").build()));
                            messages.add(1, ChatCompletionMessageParam.ofAssistant(
                                    ChatCompletionAssistantMessageParam.builder().content("I am " + name + ". Continuing.").build()));
                        }

                        messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(
                                "<auto-claimed>Task #" + taskId + ": " + task.get("subject") + "\n" +
                                        task.getOrDefault("description", "") + "</auto-claimed>").build()));
                        messages.add(ChatCompletionMessageParam.ofAssistant(
                                ChatCompletionAssistantMessageParam.builder().content("Claimed task #" + taskId + ". Working on it.").build()));
                        resume = true;
                        break;
                    }
                }

                if (!resume) {
                    setStatus(name, "shutdown");
                    return;
                }
                setStatus(name, "working");
            }
        }

        private List<ChatCompletionTool> createTeammateTools(String name) {
            List<ChatCompletionTool> tools = new ArrayList<>();
            tools.add(createTool("bash", "Run a shell command.", Map.of("command", Map.of("type", "string")), List.of("command")));
            tools.add(createTool("read_file", "Read file contents.", Map.of("path", Map.of("type", "string")), List.of("path")));
            tools.add(createTool("write_file", "Write content to file.", Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")), List.of("path", "content")));
            tools.add(createTool("edit_file", "Replace exact text in file.", Map.of("path", Map.of("type", "string"), "old_text", Map.of("type", "string"), "new_text", Map.of("type", "string")), List.of("path", "old_text", "new_text")));
            tools.add(createTool("send_message", "Send message to a teammate.", Map.of("to", Map.of("type", "string"), "content", Map.of("type", "string")), List.of("to", "content")));
            tools.add(createTool("read_inbox", "Read and drain your inbox.", Map.of(), List.of()));
            tools.add(createTool("shutdown_response", "Respond to a shutdown request.", Map.of("request_id", Map.of("type", "string"), "approve", Map.of("type", "boolean")), List.of("request_id", "approve")));
            tools.add(createTool("plan_approval", "Submit a plan for lead approval.", Map.of("plan", Map.of("type", "string")), List.of("plan")));
            tools.add(createTool("idle", "Signal that you have no more work.", Map.of(), List.of()));
            tools.add(createTool("claim_task", "Claim a task from the task board by ID.", Map.of("task_id", Map.of("type", "integer")), List.of("task_id")));
            return tools;
        }

        private String executeTeammateTool(String name, String toolName, Map<String, Object> args) {
            switch (toolName) {
                case "bash": return Lesson10RunSimple.runBash((String) args.get("command"), workDir);
                case "read_file": return Lesson10RunSimple.runRead((String) args.get("path"), workDir);
                case "write_file": return Lesson10RunSimple.runWrite((String) args.get("path"), (String) args.get("content"), workDir);
                case "edit_file": return Lesson10RunSimple.runEdit((String) args.get("path"), (String) args.get("old_text"), (String) args.get("new_text"), workDir);
                case "send_message": return bus.send(name, (String) args.get("to"), (String) args.get("content"), (String) args.getOrDefault("msg_type", "message"), null);
                case "read_inbox": return Lesson9RunSimple.listToJson(bus.readInbox(name));
                case "claim_task": return claimTask(((Number) args.get("task_id")).intValue(), name);
                default: return "Unknown tool: " + toolName;
            }
        }
    }

    // -- Tool execution for lead --
    private String executeTool(String name, String args) {
        Map<String, Object> a = Lesson9RunSimple.parseJsonToMap(args);
        switch (name) {
            case "bash": return Lesson10RunSimple.runBash((String) a.get("command"), workDir);
            case "read_file": return Lesson10RunSimple.runRead((String) a.get("path"), workDir);
            case "write_file": return Lesson10RunSimple.runWrite((String) a.get("path"), (String) a.get("content"), workDir);
            case "edit_file": return Lesson10RunSimple.runEdit((String) a.get("path"), (String) a.get("old_text"), (String) a.get("new_text"), workDir);
            case "spawn_teammate": return team.spawn((String) a.get("name"), (String) a.get("role"), (String) a.get("prompt"));
            case "list_teammates": return team.listAll();
            case "send_message": return bus.send("lead", (String) a.get("to"), (String) a.get("content"), (String) a.getOrDefault("msg_type", "message"), null);
            case "read_inbox": return Lesson9RunSimple.listToJson(bus.readInbox("lead"));
            case "broadcast": return bus.broadcast("lead", (String) a.get("content"), team.memberNames());
            case "shutdown_request": {
                String reqId = UUID.randomUUID().toString().substring(0, 8);
                String teammate = (String) a.get("teammate");
                trackerLock.lock();
                try { shutdownRequests.put(reqId, Map.of("target", teammate, "status", "pending")); }
                finally { trackerLock.unlock(); }
                bus.send("lead", teammate, "Please shut down gracefully.", "shutdown_request", Map.of("request_id", reqId));
                return "Shutdown request " + reqId + " sent to '" + teammate + "'";
            }
            case "plan_approval": {
                String reqId = (String) a.get("request_id");
                boolean approve = Boolean.TRUE.equals(a.get("approve"));
                String feedback = (String) a.get("feedback");
                trackerLock.lock();
                Map<String, Object> req = planRequests.get(reqId);
                trackerLock.unlock();
                if (req == null) return "Error: Unknown plan request_id '" + reqId + "'";
                trackerLock.lock();
                try { req.put("status", approve ? "approved" : "rejected"); }
                finally { trackerLock.unlock(); }
                bus.send("lead", (String) req.get("from"), feedback != null ? feedback : "", "plan_approval_response",
                        Map.of("request_id", reqId, "approve", approve, "feedback", feedback != null ? feedback : ""));
                return "Plan " + (approve ? "approved" : "rejected") + " for '" + req.get("from") + "'";
            }
            case "claim_task": return claimTask(((Number) a.get("task_id")).intValue(), "lead");
            default: return "Unknown tool: " + name;
        }
    }

    private List<ChatCompletionTool> createTools() {
        List<ChatCompletionTool> tools = new ArrayList<>();
        tools.add(createTool("bash", "Run a shell command.", Map.of("command", Map.of("type", "string")), List.of("command")));
        tools.add(createTool("read_file", "Read file contents.", Map.of("path", Map.of("type", "string")), List.of("path")));
        tools.add(createTool("write_file", "Write content to file.", Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")), List.of("path", "content")));
        tools.add(createTool("edit_file", "Replace exact text in file.", Map.of("path", Map.of("type", "string"), "old_text", Map.of("type", "string"), "new_text", Map.of("type", "string")), List.of("path", "old_text", "new_text")));
        tools.add(createTool("spawn_teammate", "Spawn an autonomous teammate.", Map.of("name", Map.of("type", "string"), "role", Map.of("type", "string"), "prompt", Map.of("type", "string")), List.of("name", "role", "prompt")));
        tools.add(createTool("list_teammates", "List all teammates.", Map.of(), List.of()));
        tools.add(createTool("send_message", "Send a message to a teammate.", Map.of("to", Map.of("type", "string"), "content", Map.of("type", "string")), List.of("to", "content")));
        tools.add(createTool("read_inbox", "Read and drain the lead's inbox.", Map.of(), List.of()));
        tools.add(createTool("broadcast", "Send a message to all teammates.", Map.of("content", Map.of("type", "string")), List.of("content")));
        tools.add(createTool("shutdown_request", "Request a teammate to shut down.", Map.of("teammate", Map.of("type", "string")), List.of("teammate")));
        tools.add(createTool("shutdown_response", "Check shutdown request status.", Map.of("request_id", Map.of("type", "string")), List.of("request_id")));
        tools.add(createTool("plan_approval", "Approve or reject a teammate's plan.", Map.of("request_id", Map.of("type", "string"), "approve", Map.of("type", "boolean"), "feedback", Map.of("type", "string")), List.of("request_id", "approve")));
        tools.add(createTool("idle", "Enter idle state (for lead).", Map.of(), List.of()));
        tools.add(createTool("claim_task", "Claim a task from the board by ID.", Map.of("task_id", Map.of("type", "integer")), List.of("task_id")));
        return tools;
    }

    private ChatCompletionTool createTool(String name, String desc, Map<String, Object> props, List<String> req) {
        return ChatCompletionTool.builder().function(FunctionDefinition.builder().name(name).description(desc)
                .parameters(FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(props))
                        .putAdditionalProperty("required", JsonValue.from(req))
                        .build()).build()).build();
    }
}
