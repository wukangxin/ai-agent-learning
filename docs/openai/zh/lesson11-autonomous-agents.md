# Lesson 11: Autonomous Agents (自治智能体)

`L00 > L01 > L02 > L03 > L04 > L05 > L06 | L07 > L08 > L09 > L10 > [ L11 ] L12 > L13`

> *"智能体自己找活干。"* -- 从被动接收任务到主动轮询任务看板。

## 问题

L10 的队友只能被动接收任务 -- Lead 必须通过 `send_message` 明确分配工作。如果 Lead 忙或者不在线，队友就闲着。我们需要队友具备自治能力：

1. 工作完成后进入空闲状态，定期轮询任务看板
2. 自动认领未分配的任务
3. 上下文压缩后能重新注入身份信息

## 解决方案

```
+----------------------------------------------+
|              Autonomous Loop                 |
|                                              |
|   +------------------+    +---------------+  |
|   |   WORK 阶段       |    |  IDLE 阶段   |  |
|   |                   |    |              |  |
|   |  正常 agent loop  |    |  每5秒轮询:  |  |
|   |  处理工具调用     | -> |  1.检查收件箱|  |
|   |  直到:            |    |  2.扫描任务板|  |
|   |  - LLM 无工具调用 |    |  3.自动认领  |  |
|   |  - 调用 idle 工具 |    |              |  |
|   |                  |    |  直到:        |  |
|   +------------------+    |  - 有新消息   |  |
|           ^               |  - 有可认领任务| |
|           |               |  - 超时(60s)  |  |
|           +---------------+               |  |
|              (有新工作)                   |  |
+----------------------------------------------+

任务看板 (.tasks/):
  task_1.json  { status: "completed", owner: "alice" }
  task_2.json  { status: "pending",   owner: null    }  <-- 可认领!
  task_3.json  { status: "pending",   owner: null, blockedBy: [2] }  <-- 被阻塞
```

## 工作原理

### 1. 自治循环 -- Work 与 Idle 两个阶段

队友的主循环不再是简单的 for 循环，而是一个无限循环，在 Work 和 Idle 之间切换：

```java
private void autonomousLoop(String name, String role, String prompt) {
    String teamName = config.get("team_name").toString();
    String sysPrompt = "You are '" + name + "', role: " + role
            + ", team: " + teamName + ", at " + workDir + ". "
            + "Use idle tool when you have no more work. "
            + "You will auto-claim new tasks.";

    List<ChatCompletionMessageParam> messages = new ArrayList<>();
    messages.add(ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder()
                    .content(prompt).build()));

    while (true) {
        // -- WORK 阶段 --
        for (int i = 0; i < 50; i++) {
            // 检查收件箱 (包括关机请求)
            List<Map<String, Object>> inbox = bus.readInbox(name);
            for (Map<String, Object> msg : inbox) {
                if ("shutdown_request".equals(msg.get("type"))) {
                    setStatus(name, "shutdown");
                    return;  // 立即退出
                }
                messages.add(/* msg as user message */);
            }

            // 正常 LLM 调用 + 工具处理
            // 如果 LLM 调用了 "idle" 工具, break 进入 IDLE 阶段
        }

        // -- IDLE 阶段 --
        setStatus(name, "idle");
        boolean resume = idlePoll(name, role, teamName, messages);

        if (!resume) {
            setStatus(name, "shutdown");
            return;  // 超时, 自动关闭
        }
        setStatus(name, "working");
        // 回到 WORK 阶段
    }
}
```

### 2. idle 工具 -- 智能体主动进入空闲

智能体在完成当前工作后，调用 `idle` 工具表示"我没有更多工作了"：

```java
if ("idle".equals(tc.function().name())) {
    idleRequested = true;
    output = "Entering idle phase. Will poll for new tasks.";
}
```

这不是一个真正的外部工具，而是一个信号，告诉循环切换到 IDLE 阶段。

### 3. scanUnclaimedTasks() -- 扫描任务看板

IDLE 阶段定期扫描 `.tasks/` 目录，寻找满足以下条件的任务：
- `status == "pending"`
- `owner == null`（未被认领）
- `blockedBy` 为空（无阻塞依赖）

```java
private List<Map<String, Object>> scanUnclaimedTasks() {
    List<Map<String, Object>> unclaimed = new ArrayList<>();
    try {
        Files.list(tasksDir)
                .filter(p -> p.getFileName().toString()
                        .matches("task_\\d+\\.json"))
                .forEach(p -> {
                    try {
                        Map<String, Object> t = Lesson9RunSimple
                                .parseJsonToMap(new String(
                                        Files.readAllBytes(p),
                                        StandardCharsets.UTF_8));
                        if ("pending".equals(t.get("status"))
                                && t.get("owner") == null
                                && (t.get("blockedBy") == null
                                    || ((List<?>) t.get("blockedBy"))
                                            .isEmpty())) {
                            unclaimed.add(t);
                        }
                    } catch (Exception ignored) {}
                });
    } catch (Exception ignored) {}
    return unclaimed;
}
```

### 4. claimTask() -- 原子认领

认领操作用 `ReentrantLock` 保护，防止多个队友同时认领同一个任务：

```java
private String claimTask(int taskId, String owner) {
    claimLock.lock();
    try {
        Path p = tasksDir.resolve("task_" + taskId + ".json");
        if (!Files.exists(p))
            return "Error: Task " + taskId + " not found";

        Map<String, Object> task = Lesson9RunSimple.parseJsonToMap(
                new String(Files.readAllBytes(p),
                        StandardCharsets.UTF_8));
        task.put("owner", owner);
        task.put("status", "in_progress");
        Files.write(p, Lesson9RunSimple.mapToJson(task)
                .getBytes(StandardCharsets.UTF_8));

        return "Claimed task #" + taskId + " for " + owner;
    } catch (Exception e) {
        return "Error: " + e.getMessage();
    } finally {
        claimLock.unlock();
    }
}
```

### 5. IDLE 阶段轮询逻辑

每 5 秒检查一次，持续 60 秒。如果发现新消息或可认领任务，回到 Work 阶段：

```java
setStatus(name, "idle");
boolean resume = false;
int polls = IDLE_TIMEOUT / POLL_INTERVAL;  // 60/5 = 12 次

for (int i = 0; i < polls; i++) {
    try {
        TimeUnit.SECONDS.sleep(POLL_INTERVAL);
    } catch (Exception ignored) {}

    // 1. 检查收件箱
    List<Map<String, Object>> inbox = bus.readInbox(name);
    if (!inbox.isEmpty()) {
        for (Map<String, Object> msg : inbox) {
            if ("shutdown_request".equals(msg.get("type"))) {
                setStatus(name, "shutdown");
                return;
            }
            messages.add(/* msg */);
        }
        resume = true;
        break;
    }

    // 2. 扫描未认领任务
    List<Map<String, Object>> unclaimed = scanUnclaimedTasks();
    if (!unclaimed.isEmpty()) {
        Map<String, Object> task = unclaimed.get(0);
        int taskId = ((Number) task.get("id")).intValue();
        claimTask(taskId, name);

        // 3. 身份重注入 (见下文)
        // 4. 任务注入为 auto-claimed 消息
        messages.add(/* auto-claimed message */);

        resume = true;
        break;
    }
}

if (!resume) {
    // 60 秒无新工作, 自动关闭
    setStatus(name, "shutdown");
    return;
}
```

### 6. 身份重注入 -- 压缩后恢复身份

上下文压缩（L06）会截断对话历史，导致队友"忘记"自己是谁。解决方案是在消息较少时（说明刚压缩过），注入一个身份消息对：

```java
// 如果消息很少, 说明上下文可能被压缩过
if (messages.size() <= 3) {
    messages.add(0, ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder().content(
                    "<identity>You are '" + name
                    + "', role: " + role
                    + ", team: " + teamName
                    + ".</identity>").build()));
    messages.add(1, ChatCompletionMessageParam.ofAssistant(
            ChatCompletionAssistantMessageParam.builder()
                    .content("I am " + name + ". Continuing.")
                    .build()));
}
```

身份块 (`<identity>`) 插入到对话最前面，确保 LLM 在第一个消息就知道自己的角色。

### 7. 自动认领后的消息注入

```java
messages.add(ChatCompletionMessageParam.ofUser(
        ChatCompletionUserMessageParam.builder().content(
                "<auto-claimed>Task #" + taskId + ": "
                + task.get("subject") + "\n"
                + task.getOrDefault("description", "")
                + "</auto-claimed>").build()));
messages.add(ChatCompletionMessageParam.ofAssistant(
        ChatCompletionAssistantMessageParam.builder()
                .content("Claimed task #" + taskId
                        + ". Working on it.").build()));
```

### 8. 完整阶段图

```
                     spawn(name, role, prompt)
                              |
                              v
+--------+   WORK   +--------+--------+
| start  | -------> |   working       |
+--------+          |  (agent loop)   |
                    |                 |
                    | idle() 或完成    |
                    v                 |
             +------+------+         |
             |    idle      |         |
             |  (轮询 5s/次) |         |
             |              |         |
             +--+---+---+--+         |
                |   |   |            |
   新消息       |   | 超时 |           |
   或新任务     |   |   60s|           |
                |   |   |            |
                v   |   v            |
           working  | shutdown       |
           (回到顶部)|               |
                    v                |
              +-----------+          |
              | shutdown  |          |
              | (线程退出) |  <-------+
              +-----------+   shutdown_request
```

## 变更一览

| 组件 | 之前 (L10) | 之后 (L11) |
|------|-----------|-----------|
| 任务分配 | Lead 主动分配 | 队友自动认领 |
| 空闲处理 | 线程结束 | IDLE 阶段轮询 (5s/次, 60s 超时) |
| 任务扫描 | 无 | `scanUnclaimedTasks()` 扫描 `.tasks/` |
| 任务认领 | 无 | `claimTask()` + `ReentrantLock` 原子操作 |
| 身份管理 | 无 | 压缩后 `<identity>` 块重注入 |
| 新工具 | 无 | `idle` (信号), `claim_task` |
| 循环模型 | 单次 for 循环 | 无限 Work/Idle 交替循环 |

## 试一试

```sh
cd ai-agent-learning
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson11 --prompt='Create 3 tasks on the board. Spawn an autonomous worker. Watch it claim and complete tasks on its own.'"
```

观察日志中队友进入 idle 状态后自动认领任务并恢复工作。

**源码**: [`Lesson11RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson11RunSimple.java)
