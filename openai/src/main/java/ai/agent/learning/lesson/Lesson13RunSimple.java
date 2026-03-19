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
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lesson13RunSimple - Full Reference Agent
 *
 * Capstone implementation combining every mechanism from s01-s11.
 * NOT a teaching session -- this is the "put it all together" reference.
 *
 * Features combined:
 *   - s02: Tool dispatch (bash, read, write, edit)
 *   - s03: TodoWrite with nag reminder
 *   - s04: Subagent (task tool)
 *   - s05: Skill loading (load_skill)
 *   - s06: Context compression (micro + auto + manual)
 *   - s07: File-based tasks with dependency graph
 *   - s08: Background tasks with notification queue
 *   - s09/s10/s11: Teammates with protocols and autonomous behavior
 */
@Component
public class Lesson13RunSimple implements RunSimple {

    private static final Logger log = LoggerFactory.getLogger(Lesson13RunSimple.class);
    private static final int TOKEN_THRESHOLD = 100000;
    private static final int POLL_INTERVAL = 5;
    private static final int IDLE_TIMEOUT = 60;

    @Value("${openai.api-key}") private String apiKey;
    @Value("${openai.base-url}") private String baseUrl;
    @Value("${openai.model}") private String modelName;
    @Value("${openai.system-prompt}") private String systemPrompt;
    @Value("${proxy.host}") private String proxyHost;
    @Value("${proxy.port}") private int proxyPort;

    private Path workDir, teamDir, inboxDir, tasksDir, skillsDir, transcriptDir;
    private OpenAIClient client;

    // Managers
    private TodoManager todo;
    private SkillLoader skills;
    private TaskManager taskMgr;
    private BackgroundManager bg;
    private MessageBus bus;
    private TeammateManager team;

    // Request trackers
    private final Map<String, Map<String, Object>> shutdownRequests = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> planRequests = new ConcurrentHashMap<>();
    private final ReentrantLock trackerLock = new ReentrantLock();
    private final ReentrantLock claimLock = new ReentrantLock();

    @Override
    public void run(String userPrompt) {
        log.info("Starting Lesson13 (Full Agent) with model: {}", modelName);

        workDir = Paths.get(System.getProperty("user.dir"));
        teamDir = workDir.resolve(".team");
        inboxDir = teamDir.resolve("inbox");
        tasksDir = workDir.resolve(".tasks");
        skillsDir = workDir.resolve("skills");
        transcriptDir = workDir.resolve(".transcripts");

        try {
            Files.createDirectories(tasksDir);
            Files.createDirectories(transcriptDir);
        } catch (Exception ignored) {}

        client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey).baseUrl(baseUrl)
                .proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                        new java.net.InetSocketAddress(proxyHost, proxyPort)))
                .build();

        // Initialize managers
        todo = new TodoManager();
        skills = new SkillLoader(skillsDir);
        taskMgr = new TaskManager(tasksDir);
        bg = new BackgroundManager(workDir);
        bus = new MessageBus(inboxDir);
        team = new TeammateManager();

        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(userPrompt).build()));

        String sysPrompt = buildSystemPrompt();
        agentLoop(messages, sysPrompt);
    }

    private String buildSystemPrompt() {
        return (systemPrompt != null && !systemPrompt.isEmpty() ? systemPrompt + "\n" : "") +
                "You are a coding agent at " + workDir + ". Use tools to solve tasks.\n" +
                "Prefer task_create/task_update/task_list for multi-step work. Use TodoWrite for short checklists.\n" +
                "Use task for subagent delegation. Use load_skill for specialized knowledge.\n" +
                "Skills:\n" + skills.getDescriptions();
    }

    private void agentLoop(List<ChatCompletionMessageParam> messages, String sysPrompt) {
        int roundsWithoutTodo = 0;

        while (true) {
            // s06: compression pipeline
            microCompact(messages);
            if (estimateTokens(messages) > TOKEN_THRESHOLD) {
                log.info("[auto-compact triggered]");
                List<ChatCompletionMessageParam> compressed = autoCompact(messages);
                messages.clear();
                messages.addAll(compressed);
            }

            // s08: drain background notifications
            List<Map<String, Object>> notifs = bg.drain();
            if (!notifs.isEmpty()) {
                StringBuilder txt = new StringBuilder("<background-results>\n");
                for (Map<String, Object> n : notifs) {
                    txt.append("[bg:").append(n.get("task_id")).append("] ")
                            .append(n.get("status")).append(": ").append(n.get("result")).append("\n");
                }
                txt.append("</background-results>");
                messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(txt.toString()).build()));
                messages.add(ChatCompletionMessageParam.ofAssistant(
                        ChatCompletionAssistantMessageParam.builder().content("Noted background results.").build()));
            }

            // s09: check lead inbox
            List<Map<String, Object>> inbox = bus.readInbox("lead");
            if (!inbox.isEmpty()) {
                messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content("<inbox>" + Lesson9RunSimple.listToJson(inbox) + "</inbox>").build()));
                messages.add(ChatCompletionMessageParam.ofAssistant(
                        ChatCompletionAssistantMessageParam.builder().content("Noted inbox messages.").build()));
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
                List<Map<String, Object>> results = new ArrayList<>();
                boolean usedTodo = false;
                boolean manualCompress = false;

                for (ChatCompletionMessageToolCall tc : choice.message().toolCalls().get()) {
                    String toolName = tc.function().name();
                    Map<String, Object> args = Lesson9RunSimple.parseJsonToMap(tc.function().arguments());

                    log.info("Tool: {} with args: {}", toolName, tc.function().arguments());

                    if ("compress".equals(toolName)) manualCompress = true;

                    String output = executeTool(toolName, args);
                    log.info("Output: {}", output.length() > 200 ? output.substring(0, 200) + "..." : output);

                    messages.add(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                            .toolCallId(tc.id()).content(output).build()));

                    if ("TodoWrite".equals(toolName)) usedTodo = true;
                }

                // s03: nag reminder
                roundsWithoutTodo = usedTodo ? 0 : roundsWithoutTodo + 1;
                if (todo.hasOpenItems() && roundsWithoutTodo >= 3) {
                    log.info("[nag reminder injected]");
                }

                // s06: manual compress
                if (manualCompress) {
                    log.info("[manual compact]");
                    List<ChatCompletionMessageParam> compressed = autoCompact(messages);
                    messages.clear();
                    messages.addAll(compressed);
                }
            }
        }
    }

    // -- s03: TodoManager --
    static class TodoManager {
        private List<Map<String, Object>> items = new ArrayList<>();

        public String update(List<Map<String, Object>> newItems) {
            if (newItems.size() > 20) throw new IllegalArgumentException("Max 20 todos");
            int ip = 0;
            for (Map<String, Object> item : newItems) {
                String status = (String) item.getOrDefault("status", "pending");
                if ("in_progress".equals(status)) ip++;
            }
            if (ip > 1) throw new IllegalArgumentException("Only one in_progress allowed");
            items = new ArrayList<>(newItems);
            return render();
        }

        public String render() {
            if (items.isEmpty()) return "No todos.";
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> item : items) {
                String m = switch ((String) item.get("status")) {
                    case "completed" -> "[x]";
                    case "in_progress" -> "[>]";
                    default -> "[ ]";
                };
                sb.append(m).append(" ").append(item.get("content"));
                if ("in_progress".equals(item.get("status")) && item.get("activeForm") != null) {
                    sb.append(" <- ").append(item.get("activeForm"));
                }
                sb.append("\n");
            }
            long done = items.stream().filter(t -> "completed".equals(t.get("status"))).count();
            sb.append("\n(").append(done).append("/").append(items.size()).append(" completed)");
            return sb.toString();
        }

        public boolean hasOpenItems() {
            return items.stream().anyMatch(t -> !"completed".equals(t.get("status")));
        }
    }

    // -- s05: SkillLoader (simplified) --
    static class SkillLoader {
        private final Map<String, Map<String, String>> skills = new HashMap<>();

        public SkillLoader(Path skillsDir) {
            if (!Files.exists(skillsDir)) return;
            try {
                Files.walk(skillsDir).filter(p -> p.getFileName().toString().equals("SKILL.md"))
                        .forEach(p -> {
                            try {
                                String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                                String name = p.getParent().getFileName().toString();
                                skills.put(name, Map.of("body", text, "description", "Skill: " + name));
                            } catch (Exception ignored) {}
                        });
            } catch (Exception ignored) {}
        }

        public String getDescriptions() {
            if (skills.isEmpty()) return "(no skills)";
            return skills.entrySet().stream()
                    .map(e -> "  - " + e.getKey() + ": " + e.getValue().get("description"))
                    .collect(Collectors.joining("\n"));
        }

        public String load(String name) {
            Map<String, String> s = skills.get(name);
            if (s == null) return "Error: Unknown skill '" + name + "'";
            return "<skill name=\"" + name + "\">\n" + s.get("body") + "\n</skill>";
        }
    }

    // -- s06: Compression --
    private int estimateTokens(List<ChatCompletionMessageParam> messages) {
        return messages.toString().length() / 4;
    }

    private void microCompact(List<ChatCompletionMessageParam> messages) {
        // Simplified: just truncate large tool results
    }

    private List<ChatCompletionMessageParam> autoCompact(List<ChatCompletionMessageParam> messages) {
        try {
            Path transcriptPath = transcriptDir.resolve("transcript_" + System.currentTimeMillis() / 1000 + ".jsonl");
            for (ChatCompletionMessageParam msg : messages) {
                Files.write(transcriptPath, (msg.toString() + "\n").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            log.info("[transcript saved: {}]", transcriptPath);

            String convText = messages.toString();
            if (convText.length() > 80000) convText = convText.substring(0, 80000);

            ChatCompletion summaryCompletion = client.chat().completions().create(
                    ChatCompletionCreateParams.builder()
                            .model(ChatModel.of(modelName))
                            .addUserMessage("Summarize for continuity:\n" + convText)
                            .build());

            String summary = summaryCompletion.choices().get(0).message().content().orElse("(no summary)");

            return List.of(
                    ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content("[Compressed. Transcript: " + transcriptPath.getFileName() + "]\n" + summary).build()),
                    ChatCompletionMessageParam.ofAssistant(
                            ChatCompletionAssistantMessageParam.builder().content("Understood. Continuing with summary context.").build())
            );
        } catch (Exception e) {
            return messages;
        }
    }

    // -- s04: Subagent --
    private String runSubagent(String prompt, String agentType) {
        log.info("Starting subagent: {}", agentType);

        List<ChatCompletionTool> subTools = createSubagentTools();
        List<ChatCompletionMessageParam> subMessages = new ArrayList<>();
        subMessages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(prompt).build()));

        ChatCompletionMessage lastResponse = null;

        for (int i = 0; i < 30; i++) {
            try {
                ChatCompletion completion = client.chat().completions().create(
                        ChatCompletionCreateParams.builder()
                                .model(ChatModel.of(modelName))
                                .messages(subMessages)
                                .tools(subTools)
                                .build());

                ChatCompletion.Choice choice = completion.choices().get(0);
                lastResponse = choice.message();
                subMessages.add(ChatCompletionMessageParam.ofAssistant(lastResponse.toParam()));

                if (choice.finishReason() != ChatCompletion.Choice.FinishReason.TOOL_CALLS) break;

                if (lastResponse.toolCalls().isPresent()) {
                    for (ChatCompletionMessageToolCall tc : lastResponse.toolCalls().get()) {
                        Map<String, Object> args = Lesson9RunSimple.parseJsonToMap(tc.function().arguments());
                        String output = executeBaseTool(tc.function().name(), args);
                        subMessages.add(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                                .toolCallId(tc.id()).content(truncate(output, 50000)).build()));
                    }
                }
            } catch (Exception e) {
                break;
            }
        }

        if (lastResponse != null && lastResponse.content().isPresent()) {
            return lastResponse.content().get();
        }
        return "(subagent failed)";
    }

    // -- s07: TaskManager (simplified) --
    static class TaskManager {
        private final Path dir;
        private int nextId;

        public TaskManager(Path tasksDir) {
            this.dir = tasksDir;
            try { Files.createDirectories(dir); } catch (Exception ignored) {}
            this.nextId = maxId() + 1;
        }

        private int maxId() {
            try {
                return Files.list(dir).filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                        .mapToInt(p -> Integer.parseInt(p.getFileName().toString().replaceAll("[^0-9]", "")))
                        .max().orElse(0);
            } catch (Exception e) { return 0; }
        }

        public String create(String subject, String description) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", nextId);
            task.put("subject", subject);
            task.put("description", description != null ? description : "");
            task.put("status", "pending");
            task.put("owner", null);
            task.put("blockedBy", new ArrayList<>());
            task.put("blocks", new ArrayList<>());
            save(task);
            nextId++;
            return Lesson9RunSimple.mapToJson(task);
        }

        public String get(int taskId) { return Lesson9RunSimple.mapToJson(load(taskId)); }

        @SuppressWarnings("unchecked")
        public String update(int taskId, String status, List<Integer> addBlockedBy, List<Integer> addBlocks) {
            Map<String, Object> task = load(taskId);
            if (status != null) {
                task.put("status", status);
                if ("completed".equals(status)) {
                    try {
                        Files.list(dir).filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                                .forEach(p -> {
                                    try {
                                        Map<String, Object> t = Lesson9RunSimple.parseJsonToMap(
                                                new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                                        List<Integer> blockedBy = (List<Integer>) t.get("blockedBy");
                                        if (blockedBy != null && blockedBy.contains(taskId)) {
                                            blockedBy.remove(Integer.valueOf(taskId));
                                            save(t);
                                        }
                                    } catch (Exception ignored) {}
                                });
                    } catch (Exception ignored) {}
                }
                if ("deleted".equals(status)) {
                    try { Files.delete(dir.resolve("task_" + taskId + ".json")); }
                    catch (Exception ignored) {}
                    return "Task " + taskId + " deleted";
                }
            }
            if (addBlockedBy != null) {
                List<Integer> blockedBy = new ArrayList<>((List<Integer>) task.get("blockedBy"));
                blockedBy.addAll(addBlockedBy);
                task.put("blockedBy", new ArrayList<>(new HashSet<>(blockedBy)));
            }
            if (addBlocks != null) {
                List<Integer> blocks = new ArrayList<>((List<Integer>) task.get("blocks"));
                blocks.addAll(addBlocks);
                task.put("blocks", new ArrayList<>(new HashSet<>(blocks)));
            }
            save(task);
            return Lesson9RunSimple.mapToJson(task);
        }

        public String listAll() {
            List<Map<String, Object>> taskList = new ArrayList<>();
            try {
                Files.list(dir).filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                        .sorted().forEach(p -> {
                            try { taskList.add(load(p)); } catch (Exception ignored) {}
                        });
            } catch (Exception ignored) {}
            if (taskList.isEmpty()) return "No tasks.";

            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> t : taskList) {
                String m = switch ((String) t.get("status")) {
                    case "pending" -> "[ ]";
                    case "in_progress" -> "[>]";
                    case "completed" -> "[x]";
                    default -> "[?]";
                };
                String owner = t.get("owner") != null ? " @" + t.get("owner") : "";
                sb.append(m).append(" #").append(t.get("id")).append(": ").append(t.get("subject")).append(owner).append("\n");
            }
            return sb.toString().trim();
        }

        public String claim(int taskId, String owner) {
            Map<String, Object> task = load(taskId);
            task.put("owner", owner);
            task.put("status", "in_progress");
            save(task);
            return "Claimed task #" + taskId + " for " + owner;
        }

        private Map<String, Object> load(int taskId) {
            try { return Lesson9RunSimple.parseJsonToMap(new String(Files.readAllBytes(dir.resolve("task_" + taskId + ".json")), StandardCharsets.UTF_8)); }
            catch (Exception e) { throw new IllegalArgumentException("Task " + taskId + " not found"); }
        }

        private Map<String, Object> load(Path p) {
            try { return Lesson9RunSimple.parseJsonToMap(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)); }
            catch (Exception e) { throw new IllegalArgumentException("Task file not found"); }
        }

        private void save(Map<String, Object> task) {
            try { Files.write(dir.resolve("task_" + task.get("id") + ".json"), Lesson9RunSimple.mapToJson(task).getBytes(StandardCharsets.UTF_8)); }
            catch (Exception ignored) {}
        }
    }

    // -- s08: BackgroundManager --
    static class BackgroundManager {
        private final Map<String, Map<String, Object>> tasks = new ConcurrentHashMap<>();
        private final List<Map<String, Object>> notifications = new CopyOnWriteArrayList<>();
        private final ReentrantLock lock = new ReentrantLock();
        private final Path workDir;

        public BackgroundManager(Path workDir) { this.workDir = workDir; }

        public String run(String command, int timeout) {
            String taskId = UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> info = new HashMap<>();
            info.put("status", "running");
            info.put("command", command);
            info.put("result", null);
            tasks.put(taskId, info);

            new java.lang.Thread(() -> {
                try {
                    ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", command);
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    boolean finished = p.waitFor(timeout, TimeUnit.SECONDS);
                    String output;
                    String status;
                    if (!finished) {
                        p.destroyForcibly();
                        output = "Error: Timeout (" + timeout + "s)";
                        status = "timeout";
                    } else {
                        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                            output = reader.lines().collect(Collectors.joining("\n")).trim();
                        }
                        output = truncate(output.isEmpty() ? "(no output)" : output, 50000);
                        status = "completed";
                    }
                    tasks.put(taskId, Map.of("status", status, "command", command, "result", output));
                    lock.lock();
                    try { notifications.add(Map.of("task_id", taskId, "status", status, "result", truncate(output, 500))); }
                    finally { lock.unlock(); }
                } catch (Exception e) {
                    tasks.put(taskId, Map.of("status", "error", "command", command, "result", e.getMessage()));
                }
            }, "bg-" + taskId).start();

            return "Background task " + taskId + " started: " + truncate(command, 80);
        }

        public String check(String taskId) {
            if (taskId != null) {
                Map<String, Object> t = tasks.get(taskId);
                return t != null ? "[" + t.get("status") + "] " + t.get("result") : "Unknown: " + taskId;
            }
            if (tasks.isEmpty()) return "No bg tasks.";
            return tasks.entrySet().stream()
                    .map(e -> e.getKey() + ": [" + e.getValue().get("status") + "] " + truncate((String) e.getValue().get("command"), 60))
                    .collect(Collectors.joining("\n"));
        }

        public List<Map<String, Object>> drain() {
            lock.lock();
            try {
                if (notifications.isEmpty()) return List.of();
                List<Map<String, Object>> notifs = new ArrayList<>(notifications);
                notifications.clear();
                return notifs;
            } finally { lock.unlock(); }
        }

        private String truncate(String s, int max) { return s != null && s.length() > max ? s.substring(0, max) : s; }
    }

    // -- s09: MessageBus (reuse from Lesson10) --
    static class MessageBus extends Lesson10RunSimple.MessageBus {
        public MessageBus(Path inboxDir) { super(inboxDir); }
    }

    // -- s09/s10/s11: TeammateManager (simplified autonomous version) --
    class TeammateManager {
        private final Path dir = teamDir;
        private final Path configPath = teamDir.resolve("config.json");
        private Map<String, Object> config;
        private final Map<String, java.lang.Thread> threads = new ConcurrentHashMap<>();

        public TeammateManager() {
            try { Files.createDirectories(dir); } catch (Exception ignored) {}
            config = loadConfig();
        }

        private Map<String, Object> loadConfig() {
            if (Files.exists(configPath)) {
                try { return Lesson9RunSimple.parseJsonToMap(new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8)); }
                catch (Exception ignored) {}
            }
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("team_name", "default");
            cfg.put("members", new ArrayList<>());
            return cfg;
        }

        private void saveConfig() {
            try { Files.write(configPath, Lesson9RunSimple.mapToJson(config).getBytes(StandardCharsets.UTF_8)); }
            catch (Exception ignored) {}
        }

        @SuppressWarnings("unchecked")
        public String spawn(String name, String role, String prompt) {
            Map<String, Object> member = findMember(name);
            if (member != null) {
                String status = (String) member.get("status");
                if (!"idle".equals(status) && !"shutdown".equals(status)) return "Error: '" + name + "' is " + status;
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

            java.lang.Thread t = new java.lang.Thread(() -> autonomousLoop(name, role, prompt), "teammate-" + name);
            t.setDaemon(true);
            threads.put(name, t);
            t.start();

            return "Spawned '" + name + "' (role: " + role + ")";
        }

        private void autonomousLoop(String name, String role, String prompt) {
            String teamName = config.get("team_name").toString();
            String sysPrompt = "You are '" + name + "', role: " + role + ", team: " + teamName + ", at " + workDir + ". " +
                    "Use idle tool when you have no more work. You may auto-claim tasks.";

            List<ChatCompletionMessageParam> messages = new ArrayList<>();
            messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(prompt).build()));

            while (true) {
                // WORK PHASE
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
                                        .tools(createTeammateTools())
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
                                    output = "Entering idle phase.";
                                } else {
                                    output = executeTeammateTool(name, tc.function().name(), args);
                                }
                                log.info("  [{}] {}: {}", name, tc.function().name(), truncate(output, 120));
                                messages.add(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                                        .toolCallId(tc.id()).content(output).build()));
                            }
                        }

                        if (idleRequested) break;
                    } catch (Exception e) {
                        setStatus(name, "shutdown");
                        return;
                    }
                }

                // IDLE PHASE
                setStatus(name, "idle");
                boolean resume = false;

                for (int i = 0; i < IDLE_TIMEOUT / POLL_INTERVAL; i++) {
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

                    // Auto-claim unclaimed tasks
                    List<Map<String, Object>> unclaimed = scanUnclaimedTasks();
                    if (!unclaimed.isEmpty()) {
                        Map<String, Object> task = unclaimed.get(0);
                        int taskId = ((Number) task.get("id")).intValue();
                        taskMgr.claim(taskId, name);

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

        private List<Map<String, Object>> scanUnclaimedTasks() {
            List<Map<String, Object>> unclaimed = new ArrayList<>();
            try {
                Files.list(tasksDir).filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                        .forEach(p -> {
                            try {
                                Map<String, Object> t = Lesson9RunSimple.parseJsonToMap(
                                        new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                                if ("pending".equals(t.get("status")) && t.get("owner") == null) {
                                    unclaimed.add(t);
                                }
                            } catch (Exception ignored) {}
                        });
            } catch (Exception ignored) {}
            return unclaimed;
        }

        private String executeTeammateTool(String sender, String toolName, Map<String, Object> args) {
            return switch (toolName) {
                case "bash" -> Lesson10RunSimple.runBash((String) args.get("command"), workDir);
                case "read_file" -> Lesson10RunSimple.runRead((String) args.get("path"), workDir);
                case "write_file" -> Lesson10RunSimple.runWrite((String) args.get("path"), (String) args.get("content"), workDir);
                case "edit_file" -> Lesson10RunSimple.runEdit((String) args.get("path"), (String) args.get("old_text"), (String) args.get("new_text"), workDir);
                case "send_message" -> bus.send(sender, (String) args.get("to"), (String) args.get("content"), "message", null);
                case "claim_task" -> taskMgr.claim(((Number) args.get("task_id")).intValue(), sender);
                default -> "Unknown tool: " + toolName;
            };
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> findMember(String name) {
            for (Map<String, Object> m : (List<Map<String, Object>>) config.get("members")) {
                if (name.equals(m.get("name"))) return m;
            }
            return null;
        }

        private void setStatus(String name, String status) {
            Map<String, Object> member = findMember(name);
            if (member != null) {
                member.put("status", status);
                saveConfig();
            }
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

    // -- Tool execution --
    private String executeTool(String name, Map<String, Object> args) {
        try {
            return switch (name) {
                case "bash" -> Lesson10RunSimple.runBash((String) args.get("command"), workDir);
                case "read_file" -> Lesson10RunSimple.runRead((String) args.get("path"), workDir);
                case "write_file" -> Lesson10RunSimple.runWrite((String) args.get("path"), (String) args.get("content"), workDir);
                case "edit_file" -> Lesson10RunSimple.runEdit((String) args.get("path"), (String) args.get("old_text"), (String) args.get("new_text"), workDir);
                case "TodoWrite" -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> items = (List<Map<String, Object>>) args.get("items");
                    yield todo.update(items);
                }
                case "task" -> runSubagent((String) args.get("prompt"), (String) args.getOrDefault("agent_type", "Explore"));
                case "load_skill" -> skills.load((String) args.get("name"));
                case "compress" -> "Compressing...";
                case "background_run" -> bg.run((String) args.get("command"), args.get("timeout") != null ? ((Number) args.get("timeout")).intValue() : 120);
                case "check_background" -> bg.check((String) args.get("task_id"));
                case "task_create" -> taskMgr.create((String) args.get("subject"), (String) args.get("description"));
                case "task_get" -> taskMgr.get(((Number) args.get("task_id")).intValue());
                case "task_update" -> {
                    @SuppressWarnings("unchecked")
                    List<Integer> addBlockedBy = (List<Integer>) args.get("add_blocked_by");
                    @SuppressWarnings("unchecked")
                    List<Integer> addBlocks = (List<Integer>) args.get("add_blocks");
                    yield taskMgr.update(((Number) args.get("task_id")).intValue(), (String) args.get("status"), addBlockedBy, addBlocks);
                }
                case "task_list" -> taskMgr.listAll();
                case "spawn_teammate" -> team.spawn((String) args.get("name"), (String) args.get("role"), (String) args.get("prompt"));
                case "list_teammates" -> team.listAll();
                case "send_message" -> bus.send("lead", (String) args.get("to"), (String) args.get("content"), (String) args.getOrDefault("msg_type", "message"), null);
                case "read_inbox" -> Lesson9RunSimple.listToJson(bus.readInbox("lead"));
                case "broadcast" -> bus.broadcast("lead", (String) args.get("content"), team.memberNames());
                case "shutdown_request" -> {
                    String reqId = UUID.randomUUID().toString().substring(0, 8);
                    String teammate = (String) args.get("teammate");
                    trackerLock.lock();
                    try { shutdownRequests.put(reqId, Map.of("target", teammate, "status", "pending")); }
                    finally { trackerLock.unlock(); }
                    bus.send("lead", teammate, "Please shut down.", "shutdown_request", Map.of("request_id", reqId));
                    yield "Shutdown request " + reqId + " sent to '" + teammate + "'";
                }
                case "plan_approval" -> {
                    String reqId = (String) args.get("request_id");
                    boolean approve = Boolean.TRUE.equals(args.get("approve"));
                    String feedback = (String) args.get("feedback");
                    trackerLock.lock();
                    Map<String, Object> req = planRequests.get(reqId);
                    trackerLock.unlock();
                    if (req == null) yield "Error: Unknown plan request_id '" + reqId + "'";
                    trackerLock.lock();
                    try { req.put("status", approve ? "approved" : "rejected"); }
                    finally { trackerLock.unlock(); }
                    bus.send("lead", (String) req.get("from"), feedback != null ? feedback : "", "plan_approval_response",
                            Map.of("request_id", reqId, "approve", approve, "feedback", feedback != null ? feedback : ""));
                    yield "Plan " + (approve ? "approved" : "rejected") + " for '" + req.get("from") + "'";
                }
                case "idle" -> "Lead does not idle.";
                case "claim_task" -> taskMgr.claim(((Number) args.get("task_id")).intValue(), "lead");
                default -> "Unknown tool: " + name;
            };
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String executeBaseTool(String name, Map<String, Object> args) {
        return switch (name) {
            case "bash" -> Lesson10RunSimple.runBash((String) args.get("command"), workDir);
            case "read_file" -> Lesson10RunSimple.runRead((String) args.get("path"), workDir);
            case "write_file" -> Lesson10RunSimple.runWrite((String) args.get("path"), (String) args.get("content"), workDir);
            case "edit_file" -> Lesson10RunSimple.runEdit((String) args.get("path"), (String) args.get("old_text"), (String) args.get("new_text"), workDir);
            default -> "Unknown tool: " + name;
        };
    }

    private String truncate(String s, int max) { return s != null && s.length() > max ? s.substring(0, max) : s; }

    // -- Tool definitions --
    private List<ChatCompletionTool> createTools() {
        List<ChatCompletionTool> tools = new ArrayList<>();
        tools.add(createTool("bash", "Run a shell command.", Map.of("command", Map.of("type", "string")), List.of("command")));
        tools.add(createTool("read_file", "Read file contents.", Map.of("path", Map.of("type", "string")), List.of("path")));
        tools.add(createTool("write_file", "Write content to file.", Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")), List.of("path", "content")));
        tools.add(createTool("edit_file", "Replace exact text in file.", Map.of("path", Map.of("type", "string"), "old_text", Map.of("type", "string"), "new_text", Map.of("type", "string")), List.of("path", "old_text", "new_text")));
        tools.add(createTool("TodoWrite", "Update task tracking list.", Map.of("items", Map.of("type", "array")), List.of("items")));
        tools.add(createTool("task", "Spawn a subagent for isolated exploration or work.", Map.of("prompt", Map.of("type", "string"), "agent_type", Map.of("type", "string")), List.of("prompt")));
        tools.add(createTool("load_skill", "Load specialized knowledge by name.", Map.of("name", Map.of("type", "string")), List.of("name")));
        tools.add(createTool("compress", "Manually compress conversation context.", Map.of(), List.of()));
        tools.add(createTool("background_run", "Run command in background thread.", Map.of("command", Map.of("type", "string"), "timeout", Map.of("type", "integer")), List.of("command")));
        tools.add(createTool("check_background", "Check background task status.", Map.of("task_id", Map.of("type", "string")), List.of()));
        tools.add(createTool("task_create", "Create a persistent file task.", Map.of("subject", Map.of("type", "string"), "description", Map.of("type", "string")), List.of("subject")));
        tools.add(createTool("task_get", "Get task details by ID.", Map.of("task_id", Map.of("type", "integer")), List.of("task_id")));
        tools.add(createTool("task_update", "Update task status or dependencies.", Map.of("task_id", Map.of("type", "integer"), "status", Map.of("type", "string"), "add_blocked_by", Map.of("type", "array"), "add_blocks", Map.of("type", "array")), List.of("task_id")));
        tools.add(createTool("task_list", "List all tasks.", Map.of(), List.of()));
        tools.add(createTool("spawn_teammate", "Spawn a persistent autonomous teammate.", Map.of("name", Map.of("type", "string"), "role", Map.of("type", "string"), "prompt", Map.of("type", "string")), List.of("name", "role", "prompt")));
        tools.add(createTool("list_teammates", "List all teammates.", Map.of(), List.of()));
        tools.add(createTool("send_message", "Send a message to a teammate.", Map.of("to", Map.of("type", "string"), "content", Map.of("type", "string")), List.of("to", "content")));
        tools.add(createTool("read_inbox", "Read and drain the lead's inbox.", Map.of(), List.of()));
        tools.add(createTool("broadcast", "Send message to all teammates.", Map.of("content", Map.of("type", "string")), List.of("content")));
        tools.add(createTool("shutdown_request", "Request a teammate to shut down.", Map.of("teammate", Map.of("type", "string")), List.of("teammate")));
        tools.add(createTool("plan_approval", "Approve or reject a teammate's plan.", Map.of("request_id", Map.of("type", "string"), "approve", Map.of("type", "boolean"), "feedback", Map.of("type", "string")), List.of("request_id", "approve")));
        tools.add(createTool("idle", "Enter idle state.", Map.of(), List.of()));
        tools.add(createTool("claim_task", "Claim a task from the board.", Map.of("task_id", Map.of("type", "integer")), List.of("task_id")));
        return tools;
    }

    private List<ChatCompletionTool> createSubagentTools() {
        List<ChatCompletionTool> tools = new ArrayList<>();
        tools.add(createTool("bash", "Run command.", Map.of("command", Map.of("type", "string")), List.of("command")));
        tools.add(createTool("read_file", "Read file.", Map.of("path", Map.of("type", "string")), List.of("path")));
        return tools;
    }

    private List<ChatCompletionTool> createTeammateTools() {
        List<ChatCompletionTool> tools = new ArrayList<>();
        tools.add(createTool("bash", "Run command.", Map.of("command", Map.of("type", "string")), List.of("command")));
        tools.add(createTool("read_file", "Read file.", Map.of("path", Map.of("type", "string")), List.of("path")));
        tools.add(createTool("write_file", "Write file.", Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")), List.of("path", "content")));
        tools.add(createTool("edit_file", "Edit file.", Map.of("path", Map.of("type", "string"), "old_text", Map.of("type", "string"), "new_text", Map.of("type", "string")), List.of("path", "old_text", "new_text")));
        tools.add(createTool("send_message", "Send message.", Map.of("to", Map.of("type", "string"), "content", Map.of("type", "string")), List.of("to", "content")));
        tools.add(createTool("idle", "Signal no more work.", Map.of(), List.of()));
        tools.add(createTool("claim_task", "Claim task by ID.", Map.of("task_id", Map.of("type", "integer")), List.of("task_id")));
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
