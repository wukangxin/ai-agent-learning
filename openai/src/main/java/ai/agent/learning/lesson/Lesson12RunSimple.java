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
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lesson12RunSimple - Worktree + Task Isolation
 *
 * Directory-level isolation for parallel task execution.
 * Tasks are the control plane and worktrees are the execution plane.
 *
 * Key insight: "Isolate by directory, coordinate by task ID."
 */
@Component
public class Lesson12RunSimple implements RunSimple {

    private static final Logger log = LoggerFactory.getLogger(Lesson12RunSimple.class);

    @Value("${openai.api-key}") private String apiKey;
    @Value("${openai.base-url}") private String baseUrl;
    @Value("${openai.model}") private String modelName;
    @Value("${openai.system-prompt}") private String systemPrompt;
    @Value("${proxy.host}") private String proxyHost;
    @Value("${proxy.port}") private int proxyPort;

    private Path workDir, repoRoot, tasksDir, worktreesDir;
    private OpenAIClient client;
    private TaskManager tasks;
    private WorktreeManager worktrees;
    private EventBus events;
    private boolean gitAvailable;

    @Override
    public void run(String userPrompt) {
        log.info("Starting Lesson12 (Worktree Task Isolation) with model: {}", modelName);

        workDir = Paths.get(System.getProperty("user.dir"));
        repoRoot = detectRepoRoot(workDir);
        if (repoRoot == null) repoRoot = workDir;

        log.info("Repo root for s12: {}", repoRoot);

        tasksDir = repoRoot.resolve(".tasks");
        worktreesDir = repoRoot.resolve(".worktrees");

        client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey).baseUrl(baseUrl)
                .proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                        new java.net.InetSocketAddress(proxyHost, proxyPort)))
                .build();

        tasks = new TaskManager(tasksDir);
        events = new EventBus(worktreesDir.resolve("events.jsonl"));
        worktrees = new WorktreeManager(repoRoot, tasks, events);

        gitAvailable = worktrees.isGitAvailable();
        if (!gitAvailable) log.info("Note: Not in a git repo. worktree_* tools will return errors.");

        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().content(userPrompt).build()));

        String sysPrompt = systemPrompt != null && !systemPrompt.isEmpty()
                ? systemPrompt
                : "You are a coding agent at " + workDir + ". Use task + worktree tools for multi-task work.";

        agentLoop(messages, sysPrompt);
    }

    private Path detectRepoRoot(Path cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--show-toplevel");
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (p.waitFor(10, TimeUnit.SECONDS)) {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null) {
                        Path root = Paths.get(line.trim());
                        return Files.exists(root) ? root : null;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void agentLoop(List<ChatCompletionMessageParam> messages, String sysPrompt) {
        while (true) {
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
                    log.info("Tool: {} -> {}", tc.function().name(), output.length() > 200 ? output.substring(0, 200) : output);
                    messages.add(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                            .toolCallId(tc.id()).content(output).build()));
                }
            }
        }
    }

    // -- EventBus: append-only lifecycle events --
    static class EventBus {
        private final Path path;

        public EventBus(Path path) {
            this.path = path;
            try { Files.createDirectories(path.getParent()); Files.createFile(path); }
            catch (Exception ignored) {}
        }

        public void emit(String event, Map<String, Object> task, Map<String, Object> worktree, String error) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", event);
            payload.put("ts", System.currentTimeMillis() / 1000.0);
            payload.put("task", task != null ? task : Map.of());
            payload.put("worktree", worktree != null ? worktree : Map.of());
            if (error != null) payload.put("error", error);
            try {
                Files.write(path, (Lesson9RunSimple.mapToJson(payload) + "\n").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception ignored) {}
        }

        public String listRecent(int limit) {
            try {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                List<Map<String, Object>> items = lines.subList(Math.max(0, lines.size() - limit), lines.size())
                        .stream().map(Lesson9RunSimple::parseJsonToMap).collect(Collectors.toList());
                return Lesson9RunSimple.listToJson(items);
            } catch (Exception e) { return "[]"; }
        }
    }

    // -- TaskManager with worktree binding --
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
            task.put("owner", "");
            task.put("worktree", "");
            task.put("blockedBy", new ArrayList<>());
            task.put("created_at", System.currentTimeMillis() / 1000.0);
            task.put("updated_at", System.currentTimeMillis() / 1000.0);
            save(task);
            nextId++;
            return Lesson9RunSimple.mapToJson(task);
        }

        public String get(int taskId) { return Lesson9RunSimple.mapToJson(load(taskId)); }

        public boolean exists(int taskId) { return Files.exists(dir.resolve("task_" + taskId + ".json")); }

        public String update(int taskId, String status, String owner) {
            Map<String, Object> task = load(taskId);
            if (status != null) task.put("status", status);
            if (owner != null) task.put("owner", owner);
            task.put("updated_at", System.currentTimeMillis() / 1000.0);
            save(task);
            return Lesson9RunSimple.mapToJson(task);
        }

        public String bindWorktree(int taskId, String worktree, String owner) {
            Map<String, Object> task = load(taskId);
            task.put("worktree", worktree);
            if (owner != null && !owner.isEmpty()) task.put("owner", owner);
            if ("pending".equals(task.get("status"))) task.put("status", "in_progress");
            task.put("updated_at", System.currentTimeMillis() / 1000.0);
            save(task);
            return Lesson9RunSimple.mapToJson(task);
        }

        public String unbindWorktree(int taskId) {
            Map<String, Object> task = load(taskId);
            task.put("worktree", "");
            task.put("updated_at", System.currentTimeMillis() / 1000.0);
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
                String marker = switch ((String) t.get("status")) {
                    case "pending" -> "[ ]";
                    case "in_progress" -> "[>]";
                    case "completed" -> "[x]";
                    default -> "[?]";
                };
                String owner = t.get("owner") != null && !t.get("owner").toString().isEmpty()
                        ? " owner=" + t.get("owner") : "";
                String wt = t.get("worktree") != null && !t.get("worktree").toString().isEmpty()
                        ? " wt=" + t.get("worktree") : "";
                sb.append(marker).append(" #").append(t.get("id")).append(": ").append(t.get("subject"))
                        .append(owner).append(wt).append("\n");
            }
            return sb.toString().trim();
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
            try { Files.write(dir.resolve("task_" + task.get("id") + ".json"),
                    Lesson9RunSimple.mapToJson(task).getBytes(StandardCharsets.UTF_8)); }
            catch (Exception ignored) {}
        }
    }

    // -- WorktreeManager --
    static class WorktreeManager {
        private final Path repoRoot, dir, indexPath;
        private final TaskManager tasks;
        private final EventBus events;
        private final boolean gitAvailable;

        public WorktreeManager(Path repoRoot, TaskManager tasks, EventBus events) {
            this.repoRoot = repoRoot;
            this.tasks = tasks;
            this.events = events;
            this.dir = repoRoot.resolve(".worktrees");
            try { Files.createDirectories(dir); } catch (Exception ignored) {}
            this.indexPath = dir.resolve("index.json");
            if (!Files.exists(indexPath)) {
                try { Files.write(indexPath, "{\"worktrees\":[]}".getBytes(StandardCharsets.UTF_8)); }
                catch (Exception ignored) {}
            }
            this.gitAvailable = isGitRepo();
        }

        public boolean isGitAvailable() { return gitAvailable; }

        private boolean isGitRepo() {
            try {
                ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--is-inside-work-tree");
                pb.directory(repoRoot.toFile());
                return pb.start().waitFor(10, TimeUnit.SECONDS) && pb.start().exitValue() == 0;
            } catch (Exception e) { return false; }
        }

        private String runGit(List<String> args) {
            if (!gitAvailable) return "Error: Not in a git repository.";
            try {
                ProcessBuilder pb = new ProcessBuilder(args);
                pb.directory(repoRoot.toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                if (!p.waitFor(120, TimeUnit.SECONDS)) return "Error: Timeout";
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String out = reader.lines().collect(Collectors.joining("\n")).trim();
                    return p.exitValue() != 0 ? "Error: " + out : out;
                }
            } catch (Exception e) { return "Error: " + e.getMessage(); }
        }

        @SuppressWarnings("unchecked")
        public String create(String name, Integer taskId, String baseRef) {
            if (!Pattern.matches("[A-Za-z0-9._-]{1,40}", name)) return "Error: Invalid worktree name";
            if (find(name) != null) return "Error: Worktree '" + name + "' already exists";
            if (taskId != null && !tasks.exists(taskId)) return "Error: Task " + taskId + " not found";

            Path path = dir.resolve(name);
            String branch = "wt/" + name;
            events.emit("worktree.create.before", taskId != null ? Map.of("id", taskId) : Map.of(),
                    Map.of("name", name, "base_ref", baseRef != null ? baseRef : "HEAD"), null);

            String result = runGit(List.of("git", "worktree", "add", "-b", branch, path.toString(),
                    baseRef != null ? baseRef : "HEAD"));
            if (result.startsWith("Error:")) {
                events.emit("worktree.create.failed", taskId != null ? Map.of("id", taskId) : Map.of(),
                        Map.of("name", name), result);
                return result;
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("path", path.toString());
            entry.put("branch", branch);
            entry.put("task_id", taskId);
            entry.put("status", "active");
            entry.put("created_at", System.currentTimeMillis() / 1000.0);

            Map<String, Object> idx = loadIndex();
            ((List<Map<String, Object>>) idx.get("worktrees")).add(entry);
            saveIndex(idx);

            if (taskId != null) tasks.bindWorktree(taskId, name, null);

            events.emit("worktree.create.after", taskId != null ? Map.of("id", taskId) : Map.of(),
                    Map.of("name", name, "path", path.toString(), "branch", branch, "status", "active"), null);
            return Lesson9RunSimple.mapToJson(entry);
        }

        @SuppressWarnings("unchecked")
        public String listAll() {
            List<Map<String, Object>> wts = (List<Map<String, Object>>) loadIndex().get("worktrees");
            if (wts.isEmpty()) return "No worktrees in index.";
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> wt : wts) {
                String suffix = wt.get("task_id") != null ? " task=" + wt.get("task_id") : "";
                sb.append("[").append(wt.get("status")).append("] ").append(wt.get("name"))
                        .append(" -> ").append(wt.get("path")).append(" (").append(wt.get("branch")).append(")")
                        .append(suffix).append("\n");
            }
            return sb.toString().trim();
        }

        public String status(String name) {
            Map<String, Object> wt = find(name);
            if (wt == null) return "Error: Unknown worktree '" + name + "'";
            Path p = Paths.get((String) wt.get("path"));
            if (!Files.exists(p)) return "Error: Worktree path missing: " + p;
            try {
                ProcessBuilder pb = new ProcessBuilder("git", "status", "--short", "--branch");
                pb.directory(p.toFile());
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
                    String out = reader.lines().collect(Collectors.joining("\n")).trim();
                    return out.isEmpty() ? "Clean worktree" : out;
                }
            } catch (Exception e) { return "Error: " + e.getMessage(); }
        }

        public String run(String name, String command) {
            String[] dangerous = {"rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"};
            for (String d : dangerous) if (command.contains(d)) return "Error: Dangerous command blocked";

            Map<String, Object> wt = find(name);
            if (wt == null) return "Error: Unknown worktree '" + name + "'";
            Path p = Paths.get((String) wt.get("path"));
            if (!Files.exists(p)) return "Error: Worktree path missing: " + p;

            try {
                ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", command);
                pb.directory(p.toFile());
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                if (!proc.waitFor(300, TimeUnit.SECONDS)) return "Error: Timeout (300s)";
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
                    String out = reader.lines().collect(Collectors.joining("\n")).trim();
                    return out.isEmpty() ? "(no output)" : (out.length() > 50000 ? out.substring(0, 50000) : out);
                }
            } catch (Exception e) { return "Error: " + e.getMessage(); }
        }

        @SuppressWarnings("unchecked")
        public String remove(String name, boolean force, boolean completeTask) {
            Map<String, Object> wt = find(name);
            if (wt == null) return "Error: Unknown worktree '" + name + "'";

            events.emit("worktree.remove.before",
                    wt.get("task_id") != null ? Map.of("id", wt.get("task_id")) : Map.of(),
                    Map.of("name", name, "path", wt.get("path")), null);

            List<String> args = new ArrayList<>(List.of("git", "worktree", "remove"));
            if (force) args.add("--force");
            args.add((String) wt.get("path"));

            String result = runGit(args);
            if (result.startsWith("Error:")) {
                events.emit("worktree.remove.failed",
                        wt.get("task_id") != null ? Map.of("id", wt.get("task_id")) : Map.of(),
                        Map.of("name", name), result);
                return result;
            }

            if (completeTask && wt.get("task_id") != null) {
                int taskId = ((Number) wt.get("task_id")).intValue();
                tasks.update(taskId, "completed", null);
                tasks.unbindWorktree(taskId);
                events.emit("task.completed", Map.of("id", taskId, "status", "completed"), Map.of("name", name), null);
            }

            Map<String, Object> idx = loadIndex();
            for (Map<String, Object> item : (List<Map<String, Object>>) idx.get("worktrees")) {
                if (name.equals(item.get("name"))) {
                    item.put("status", "removed");
                    item.put("removed_at", System.currentTimeMillis() / 1000.0);
                }
            }
            saveIndex(idx);

            events.emit("worktree.remove.after",
                    wt.get("task_id") != null ? Map.of("id", wt.get("task_id")) : Map.of(),
                    Map.of("name", name, "status", "removed"), null);
            return "Removed worktree '" + name + "'";
        }

        @SuppressWarnings("unchecked")
        public String keep(String name) {
            Map<String, Object> wt = find(name);
            if (wt == null) return "Error: Unknown worktree '" + name + "'";

            Map<String, Object> idx = loadIndex();
            for (Map<String, Object> item : (List<Map<String, Object>>) idx.get("worktrees")) {
                if (name.equals(item.get("name"))) {
                    item.put("status", "kept");
                    item.put("kept_at", System.currentTimeMillis() / 1000.0);
                }
            }
            saveIndex(idx);

            events.emit("worktree.keep",
                    wt.get("task_id") != null ? Map.of("id", wt.get("task_id")) : Map.of(),
                    Map.of("name", name, "status", "kept"), null);
            return Lesson9RunSimple.mapToJson(wt);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> find(String name) {
            for (Map<String, Object> wt : (List<Map<String, Object>>) loadIndex().get("worktrees")) {
                if (name.equals(wt.get("name"))) return wt;
            }
            return null;
        }

        private Map<String, Object> loadIndex() {
            try { return Lesson9RunSimple.parseJsonToMap(new String(Files.readAllBytes(indexPath), StandardCharsets.UTF_8)); }
            catch (Exception e) { return Map.of("worktrees", new ArrayList<>()); }
        }

        private void saveIndex(Map<String, Object> idx) {
            try { Files.write(indexPath, Lesson9RunSimple.mapToJson(idx).getBytes(StandardCharsets.UTF_8)); }
            catch (Exception ignored) {}
        }
    }

    // -- Tool execution --
    private String executeTool(String name, String args) {
        Map<String, Object> a = Lesson9RunSimple.parseJsonToMap(args);
        try {
            return switch (name) {
                case "bash" -> Lesson10RunSimple.runBash((String) a.get("command"), workDir);
                case "read_file" -> Lesson10RunSimple.runRead((String) a.get("path"), workDir);
                case "write_file" -> Lesson10RunSimple.runWrite((String) a.get("path"), (String) a.get("content"), workDir);
                case "edit_file" -> Lesson10RunSimple.runEdit((String) a.get("path"), (String) a.get("old_text"), (String) a.get("new_text"), workDir);
                case "task_create" -> tasks.create((String) a.get("subject"), (String) a.get("description"));
                case "task_list" -> tasks.listAll();
                case "task_get" -> tasks.get(((Number) a.get("task_id")).intValue());
                case "task_update" -> tasks.update(((Number) a.get("task_id")).intValue(), (String) a.get("status"), (String) a.get("owner"));
                case "task_bind_worktree" -> tasks.bindWorktree(((Number) a.get("task_id")).intValue(), (String) a.get("worktree"), (String) a.get("owner"));
                case "worktree_create" -> worktrees.create((String) a.get("name"),
                        a.get("task_id") != null ? ((Number) a.get("task_id")).intValue() : null,
                        (String) a.get("base_ref"));
                case "worktree_list" -> worktrees.listAll();
                case "worktree_status" -> worktrees.status((String) a.get("name"));
                case "worktree_run" -> worktrees.run((String) a.get("name"), (String) a.get("command"));
                case "worktree_keep" -> worktrees.keep((String) a.get("name"));
                case "worktree_remove" -> worktrees.remove((String) a.get("name"),
                        Boolean.TRUE.equals(a.get("force")), Boolean.TRUE.equals(a.get("complete_task")));
                case "worktree_events" -> events.listRecent(a.get("limit") != null ? ((Number) a.get("limit")).intValue() : 20);
                default -> "Unknown tool: " + name;
            };
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private List<ChatCompletionTool> createTools() {
        List<ChatCompletionTool> tools = new ArrayList<>();
        tools.add(createTool("bash", "Run a shell command.", Map.of("command", Map.of("type", "string")), List.of("command")));
        tools.add(createTool("read_file", "Read file contents.", Map.of("path", Map.of("type", "string")), List.of("path")));
        tools.add(createTool("write_file", "Write content to file.", Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")), List.of("path", "content")));
        tools.add(createTool("edit_file", "Replace exact text in file.", Map.of("path", Map.of("type", "string"), "old_text", Map.of("type", "string"), "new_text", Map.of("type", "string")), List.of("path", "old_text", "new_text")));
        tools.add(createTool("task_create", "Create a new task on the shared task board.", Map.of("subject", Map.of("type", "string"), "description", Map.of("type", "string")), List.of("subject")));
        tools.add(createTool("task_list", "List all tasks.", Map.of(), List.of()));
        tools.add(createTool("task_get", "Get task details by ID.", Map.of("task_id", Map.of("type", "integer")), List.of("task_id")));
        tools.add(createTool("task_update", "Update task status or owner.", Map.of("task_id", Map.of("type", "integer"), "status", Map.of("type", "string"), "owner", Map.of("type", "string")), List.of("task_id")));
        tools.add(createTool("task_bind_worktree", "Bind a task to a worktree name.", Map.of("task_id", Map.of("type", "integer"), "worktree", Map.of("type", "string"), "owner", Map.of("type", "string")), List.of("task_id", "worktree")));
        tools.add(createTool("worktree_create", "Create a git worktree.", Map.of("name", Map.of("type", "string"), "task_id", Map.of("type", "integer"), "base_ref", Map.of("type", "string")), List.of("name")));
        tools.add(createTool("worktree_list", "List worktrees.", Map.of(), List.of()));
        tools.add(createTool("worktree_status", "Show git status for one worktree.", Map.of("name", Map.of("type", "string")), List.of("name")));
        tools.add(createTool("worktree_run", "Run a shell command in a named worktree directory.", Map.of("name", Map.of("type", "string"), "command", Map.of("type", "string")), List.of("name", "command")));
        tools.add(createTool("worktree_remove", "Remove a worktree.", Map.of("name", Map.of("type", "string"), "force", Map.of("type", "boolean"), "complete_task", Map.of("type", "boolean")), List.of("name")));
        tools.add(createTool("worktree_keep", "Mark a worktree as kept.", Map.of("name", Map.of("type", "string")), List.of("name")));
        tools.add(createTool("worktree_events", "List recent worktree/task lifecycle events.", Map.of("limit", Map.of("type", "integer")), List.of()));
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
