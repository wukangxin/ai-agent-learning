# Lesson 12: Worktree + Task Isolation (Worktree 任务隔离)

`L00 > L01 > L02 > L03 > L04 > L05 > L06 | L07 > L08 > L09 > L10 > L11 > [ L12 ] L13`

> *"按目录隔离，按任务 ID 协调。"* -- 控制面在 `.tasks/`，执行面在 `.worktrees/`。

## 问题

多个队友在同一个目录下工作时会互相干扰：一个队友的文件修改可能破坏另一个队友的工作。Git worktree 提供了目录级别的隔离 -- 每个 worktree 是一个独立的工作目录，有自己的分支。我们需要把任务和 worktree 绑定起来，让每个任务在独立的目录中执行。

## 解决方案

```
控制面 (.tasks/)                        执行面 (.worktrees/)
+------------------+                   +------------------+
|  task_1.json     |                   |  fix-auth/       |
|    status: "in_progress"             |    (git worktree) |
|    worktree: "fix-auth"   ---------> |    branch: wt/fix-auth
|    owner: "alice"|       |           |    独立工作目录   |
+------------------+       |           +------------------+
                           |
+------------------+       |           +------------------+
|  task_2.json     |       |           |  add-tests/      |
|    status: "pending"     |           |    (git worktree) |
|    worktree: ""  |       |           |    branch: wt/add-tests
|    owner: ""     |       |           |                  |
+------------------+                   +------------------+
                                       |                  |
                                       |  index.json      |
                                       |  events.jsonl    |
                                       +------------------+

Worktree-任务绑定:
  task_1 <--> fix-auth     (通过 task_id 字段关联)
  task_2 <--> (未绑定)

事件流 (events.jsonl):
  {"event":"worktree.create.before","ts":1700000000,"task":{"id":1},"worktree":{"name":"fix-auth"}}
  {"event":"worktree.create.after","ts":1700000001,"task":{"id":1},"worktree":{"name":"fix-auth","status":"active"}}
  {"event":"task.completed","ts":1700000100,"task":{"id":1},"worktree":{"name":"fix-auth"}}
  {"event":"worktree.remove.after","ts":1700000101,"task":{"id":1},"worktree":{"name":"fix-auth","status":"removed"}}
```

## 工作原理

### 1. EventBus -- append-only 事件流

所有生命周期事件追加到 `events.jsonl`，形成不可变审计日志：

```java
static class EventBus {
    private final Path path;

    public EventBus(Path path) {
        this.path = path;
        try {
            Files.createDirectories(path.getParent());
            Files.createFile(path);
        } catch (Exception ignored) {}
    }

    public void emit(String event, Map<String, Object> task,
                     Map<String, Object> worktree, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("ts", System.currentTimeMillis() / 1000.0);
        payload.put("task", task != null ? task : Map.of());
        payload.put("worktree", worktree != null ? worktree : Map.of());
        if (error != null) payload.put("error", error);
        try {
            Files.write(path,
                    (Lesson9RunSimple.mapToJson(payload) + "\n")
                            .getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    public String listRecent(int limit) {
        try {
            List<String> lines = Files.readAllLines(
                    path, StandardCharsets.UTF_8);
            List<Map<String, Object>> items = lines.subList(
                            Math.max(0, lines.size() - limit),
                            lines.size())
                    .stream()
                    .map(Lesson9RunSimple::parseJsonToMap)
                    .collect(Collectors.toList());
            return Lesson9RunSimple.listToJson(items);
        } catch (Exception e) { return "[]"; }
    }
}
```

### 2. TaskManager -- 增加 worktree 绑定字段

L12 的 TaskManager 在 L07 基础上增加了 `worktree` 字段和绑定/解绑操作：

```java
static class TaskManager {
    public String create(String subject, String description) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", nextId);
        task.put("subject", subject);
        task.put("description", description != null ? description : "");
        task.put("status", "pending");
        task.put("owner", "");
        task.put("worktree", "");        // 新增: worktree 绑定
        task.put("blockedBy", new ArrayList<>());
        task.put("created_at", System.currentTimeMillis() / 1000.0);
        task.put("updated_at", System.currentTimeMillis() / 1000.0);
        save(task);
        nextId++;
        return Lesson9RunSimple.mapToJson(task);
    }

    public String bindWorktree(int taskId, String worktree,
                               String owner) {
        Map<String, Object> task = load(taskId);
        task.put("worktree", worktree);
        if (owner != null && !owner.isEmpty())
            task.put("owner", owner);
        if ("pending".equals(task.get("status")))
            task.put("status", "in_progress");  // 绑定时自动开始
        task.put("updated_at",
                System.currentTimeMillis() / 1000.0);
        save(task);
        return Lesson9RunSimple.mapToJson(task);
    }

    public String unbindWorktree(int taskId) {
        Map<String, Object> task = load(taskId);
        task.put("worktree", "");
        task.put("updated_at",
                System.currentTimeMillis() / 1000.0);
        save(task);
        return Lesson9RunSimple.mapToJson(task);
    }
}
```

### 3. WorktreeManager -- Git Worktree 生命周期

管理 `.worktrees/` 目录下的 git worktree，通过 `index.json` 追踪所有 worktree 状态：

```java
static class WorktreeManager {
    private final Path repoRoot, dir, indexPath;
    private final TaskManager tasks;
    private final EventBus events;
    private final boolean gitAvailable;

    public WorktreeManager(Path repoRoot, TaskManager tasks,
                           EventBus events) {
        this.repoRoot = repoRoot;
        this.tasks = tasks;
        this.events = events;
        this.dir = repoRoot.resolve(".worktrees");
        try { Files.createDirectories(dir); }
        catch (Exception ignored) {}

        this.indexPath = dir.resolve("index.json");
        if (!Files.exists(indexPath)) {
            try {
                Files.write(indexPath,
                        "{\"worktrees\":[]}"
                                .getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }
        this.gitAvailable = isGitRepo();
    }
}
```

### 4. create() -- 创建 Worktree 并绑定任务

```java
@SuppressWarnings("unchecked")
public String create(String name, Integer taskId,
                     String baseRef) {
    // 验证名字合法性
    if (!Pattern.matches("[A-Za-z0-9._-]{1,40}", name))
        return "Error: Invalid worktree name";
    if (find(name) != null)
        return "Error: Worktree '" + name + "' already exists";
    if (taskId != null && !tasks.exists(taskId))
        return "Error: Task " + taskId + " not found";

    Path path = dir.resolve(name);
    String branch = "wt/" + name;

    // 发射 before 事件
    events.emit("worktree.create.before",
            taskId != null ? Map.of("id", taskId) : Map.of(),
            Map.of("name", name,
                    "base_ref", baseRef != null ? baseRef : "HEAD"),
            null);

    // 执行 git worktree add
    String result = runGit(List.of("git", "worktree", "add",
            "-b", branch, path.toString(),
            baseRef != null ? baseRef : "HEAD"));

    if (result.startsWith("Error:")) {
        events.emit("worktree.create.failed", /* ... */);
        return result;
    }

    // 更新 index.json
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("name", name);
    entry.put("path", path.toString());
    entry.put("branch", branch);
    entry.put("task_id", taskId);
    entry.put("status", "active");
    entry.put("created_at",
            System.currentTimeMillis() / 1000.0);

    Map<String, Object> idx = loadIndex();
    ((List<Map<String, Object>>) idx.get("worktrees")).add(entry);
    saveIndex(idx);

    // 绑定任务
    if (taskId != null)
        tasks.bindWorktree(taskId, name, null);

    // 发射 after 事件
    events.emit("worktree.create.after", /* ... */);
    return Lesson9RunSimple.mapToJson(entry);
}
```

### 5. remove() -- 移除 Worktree 并可选完成任务

```java
@SuppressWarnings("unchecked")
public String remove(String name, boolean force,
                     boolean completeTask) {
    Map<String, Object> wt = find(name);
    if (wt == null)
        return "Error: Unknown worktree '" + name + "'";

    events.emit("worktree.remove.before", /* ... */);

    // 执行 git worktree remove
    List<String> args = new ArrayList<>(
            List.of("git", "worktree", "remove"));
    if (force) args.add("--force");
    args.add((String) wt.get("path"));

    String result = runGit(args);
    if (result.startsWith("Error:")) {
        events.emit("worktree.remove.failed", /* ... */);
        return result;
    }

    // 如果 completeTask=true, 自动完成关联任务
    if (completeTask && wt.get("task_id") != null) {
        int taskId = ((Number) wt.get("task_id")).intValue();
        tasks.update(taskId, "completed", null);
        tasks.unbindWorktree(taskId);
        events.emit("task.completed", /* ... */);
    }

    // 更新 index 状态为 "removed"
    Map<String, Object> idx = loadIndex();
    for (Map<String, Object> item :
            (List<Map<String, Object>>) idx.get("worktrees")) {
        if (name.equals(item.get("name"))) {
            item.put("status", "removed");
            item.put("removed_at",
                    System.currentTimeMillis() / 1000.0);
        }
    }
    saveIndex(idx);

    events.emit("worktree.remove.after", /* ... */);
    return "Removed worktree '" + name + "'";
}
```

### 6. run() -- 在 Worktree 目录内执行命令

```java
public String run(String name, String command) {
    Map<String, Object> wt = find(name);
    if (wt == null)
        return "Error: Unknown worktree '" + name + "'";
    Path p = Paths.get((String) wt.get("path"));
    if (!Files.exists(p))
        return "Error: Worktree path missing: " + p;

    try {
        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-NoProfile",
                "-Command", command);
        pb.directory(p.toFile());  // 在 worktree 目录中执行
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        // ...
    } catch (Exception e) {
        return "Error: " + e.getMessage();
    }
}
```

### 7. 双状态机

任务和 Worktree 各有独立的状态机，通过 `task_id` 关联：

```
任务状态机:
  pending ---------> in_progress ---------> completed
    |                    ^                      |
    | bindWorktree()     |                      | remove(completeTask=true)
    +--------------------+                      |
                                                v
                                           unbindWorktree()

Worktree 状态机:
  (不存在) --create()--> active --remove()--> removed
                           |
                           | keep()
                           v
                         kept

两个状态机通过 task_id 关联:
  task.worktree = "fix-auth"  <--->  worktree.task_id = 1

绑定时同步:
  create(name, taskId) --> task.status = "in_progress"
  remove(completeTask=true) --> task.status = "completed"
```

### 8. 工具列表

```java
// 任务工具
tools.add(createTool("task_create", ...));
tools.add(createTool("task_list", ...));
tools.add(createTool("task_get", ...));
tools.add(createTool("task_update", ...));
tools.add(createTool("task_bind_worktree", ...));

// Worktree 工具
tools.add(createTool("worktree_create",
        "Create a git worktree.",
        Map.of("name", Map.of("type", "string"),
                "task_id", Map.of("type", "integer"),
                "base_ref", Map.of("type", "string")),
        List.of("name")));
tools.add(createTool("worktree_list", ...));
tools.add(createTool("worktree_status", ...));
tools.add(createTool("worktree_run",
        "Run a shell command in a named worktree directory.",
        ...));
tools.add(createTool("worktree_remove", ...));
tools.add(createTool("worktree_keep", ...));
tools.add(createTool("worktree_events",
        "List recent worktree/task lifecycle events.", ...));
```

## 变更一览

| 组件 | 之前 (L11) | 之后 (L12) |
|------|-----------|-----------|
| 工作隔离 | 所有任务共享同一目录 | 每个任务一个 git worktree |
| 控制面 | `.tasks/` | `.tasks/` (增加 worktree 字段) |
| 执行面 | 无 | `.worktrees/` + `index.json` |
| 事件追踪 | 无 | `events.jsonl` append-only 审计日志 |
| 任务绑定 | 无 | `task_id <-> worktree name` 双向关联 |
| 状态机 | 单一 (任务) | 双状态机 (任务 + Worktree) |
| 新工具 | 无 | `worktree_create`, `worktree_list`, `worktree_status`, `worktree_run`, `worktree_remove`, `worktree_keep`, `worktree_events`, `task_bind_worktree` |

## 试一试

```sh
cd ai-agent-learning
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson12 --prompt='Create a task for fixing auth bugs. Create a worktree for it and run the tests inside the worktree.'"
```

运行后查看目录结构：

```sh
cat .worktrees/index.json
cat .worktrees/events.jsonl
ls .tasks/
```

**源码**: [`Lesson12RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson12RunSimple.java)
