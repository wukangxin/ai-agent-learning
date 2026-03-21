# Lesson 8: Background Tasks (后台任务)

`L00 > L01 > L02 > L03 > L04 > L05 > L06 | L07 > [ L08 ] L09 > L10 > L11 > L12 > L13`

> *"发射即忘 -- 智能体不会在命令执行时阻塞。"* -- 长时间运行的命令放到守护线程中执行。

## 问题

有些命令需要运行很长时间（编译、测试、部署），如果用同步的 `bash` 工具，智能体会一直等待直到命令结束，浪费了做其他工作的时间。我们需要一种"发射即忘"机制：命令立即返回一个 task_id，智能体继续工作，结果在后台完成后自动注入对话。

## 解决方案

```
+------------+     background_run      +-------------------+
|   Agent    | ----------------------> | BackgroundManager |
|   Loop     |     "mvn test"          |                   |
|            | <------ task_id ------- |  ConcurrentHashMap|
+-----+------+                        |  (tasks)          |
      |                               +-------+-----------+
      |                                       |
      |  每次 LLM 调用前                       |  守护线程执行命令
      |  drainNotifications()                 |  ProcessBuilder
      |                                       |
      v                                       v
+-----+------+                        +-------+-----------+
| <background |                        | notificationQueue |
|  -results>  |                        |  CopyOnWriteArray |
| 注入对话     |  <-- drain + clear -- |  List             |
+--------------+                       +-------------------+

时序:
  t=0   agent 调用 background_run("mvn test")  --> 返回 task_id="a1b2c3d4"
  t=0   agent 继续处理其他工具调用
  t=45  后台线程完成 mvn test
  t=45  结果压入 notificationQueue
  t=46  下一次 LLM 调用前, drainNotifications()
  t=46  结果作为 <background-results> 注入对话
```

## 工作原理

### 1. BackgroundManager -- 守护线程 + 通知队列

核心数据结构：`ConcurrentHashMap` 存任务状态，`CopyOnWriteArrayList` 作为线程安全通知队列，`ReentrantLock` 保护排空操作的原子性。

```java
static class BackgroundManager {
    private final Map<String, TaskInfo> tasks = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> notificationQueue
            = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Path workDir;

    public BackgroundManager(Path workDir) {
        this.workDir = workDir;
    }
}
```

### 2. run() -- 发射即忘

生成一个 8 字符的随机 task_id，在守护线程中执行命令，立即返回 task_id 给智能体：

```java
public String run(String command) {
    String taskId = UUID.randomUUID().toString().substring(0, 8);
    tasks.put(taskId, new TaskInfo("running", command, null));

    Thread thread = new Thread(
            () -> execute(taskId, command), "bg-" + taskId);
    thread.setDaemon(true);  // 守护线程：主线程退出时自动终止
    thread.start();

    return "Background task " + taskId + " started: "
            + truncate(command, 80);
}
```

关键点：`setDaemon(true)` 确保后台线程不会阻止 JVM 关闭。

### 3. execute() -- 线程内执行与结果投递

命令在独立线程中通过 `ProcessBuilder` 执行。完成后，结果压入通知队列：

```java
private void execute(String taskId, String command) {
    try {
        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-Command", command);
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
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.lines()
                        .collect(Collectors.joining("\n")).trim();
            }
            output = truncate(
                    output.isEmpty() ? "(no output)" : output, 50000);
            status = "completed";
        }

        tasks.get(taskId).status = status;
        tasks.get(taskId).result = output;

        // 线程安全地压入通知队列
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
        // 异常也压入通知队列
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
```

### 4. drainNotifications() -- 排空通知队列

每次 LLM 调用前，Agent Loop 排空通知队列，获取所有已完成的后台结果：

```java
public List<Map<String, Object>> drainNotifications() {
    lock.lock();
    try {
        if (notificationQueue.isEmpty()) return null;
        List<Map<String, Object>> notifs
                = new ArrayList<>(notificationQueue);
        notificationQueue.clear();
        return notifs;
    } finally {
        lock.unlock();
    }
}
```

`ReentrantLock` 保证 drain 操作是原子的：不会在读取过程中被新的通知插入。

### 5. Agent Loop 集成 -- 每次 LLM 调用前排空

```java
private void agentLoop(...) {
    while (true) {
        // 排空后台通知并注入为对话消息
        List<Map<String, Object>> notifs
                = backgroundManager.drainNotifications();
        if (notifs != null && !notifs.isEmpty()) {
            StringBuilder notifText
                    = new StringBuilder("<background-results>\n");
            for (Map<String, Object> n : notifs) {
                notifText.append("[bg:").append(n.get("task_id"))
                        .append("] ").append(n.get("status"))
                        .append(": ").append(n.get("result"))
                        .append("\n");
            }
            notifText.append("</background-results>");

            // 用 user+assistant 消息对注入, 不打断对话流
            messages.add(ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                            .content(notifText.toString()).build()));
            messages.add(ChatCompletionMessageParam.ofAssistant(
                    ChatCompletionAssistantMessageParam.builder()
                            .content("Noted background results.")
                            .build()));
        }

        // 正常 LLM 调用继续...
    }
}
```

### 6. check() -- 主动查询任务状态

智能体也可以通过 `check_background` 工具主动查询某个后台任务的状态：

```java
public String check(String taskId) {
    if (taskId != null) {
        TaskInfo t = tasks.get(taskId);
        if (t == null) return "Error: Unknown task " + taskId;
        return "[" + t.status + "] " + truncate(t.command, 60)
                + "\n" + (t.result != null ? t.result : "(running)");
    }
    // 无参数时列出所有后台任务
    if (tasks.isEmpty()) return "No background tasks.";
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, TaskInfo> e : tasks.entrySet()) {
        sb.append(e.getKey()).append(": [")
                .append(e.getValue().status).append("] ")
                .append(truncate(e.getValue().command, 60))
                .append("\n");
    }
    return sb.toString().trim();
}
```

### 7. TaskInfo -- volatile 字段保证可见性

```java
static class TaskInfo {
    volatile String status;   // 写线程: 后台线程, 读线程: 主线程
    final String command;     // 不可变
    volatile String result;   // 同上

    TaskInfo(String status, String command, String result) {
        this.status = status;
        this.command = command;
        this.result = result;
    }
}
```

`volatile` 保证后台线程写入的状态对主线程立即可见，无需额外同步。

## 变更一览

| 组件 | 之前 (L07) | 之后 (L08) |
|------|-----------|-----------|
| 命令执行 | 同步阻塞 (bash) | 同步 (bash) + 异步 (background_run) |
| 后台线程 | 无 | 守护线程 (`setDaemon(true)`) |
| 通知机制 | 无 | `CopyOnWriteArrayList` + `ReentrantLock` |
| 结果注入 | 无 | 每次 LLM 调用前 `drainNotifications()` |
| 新工具 | 无 | `background_run`, `check_background` |
| 线程安全 | 不需要 | `ConcurrentHashMap` + `volatile` + `ReentrantLock` |

## 试一试

```sh
cd ai-agent-learning
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson8 --prompt='Run mvn test in background, then while it runs, read the pom.xml file.'"
```

观察日志中 `background_run` 立即返回 task_id，随后在某次 LLM 调用前出现 `<background-results>` 通知。

**源码**: [`Lesson8RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson8RunSimple.java)
