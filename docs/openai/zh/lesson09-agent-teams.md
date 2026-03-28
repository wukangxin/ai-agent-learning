# Lesson 9: Agent Teams (智能体团队)

`L00 > L01 > L02 > L03 > L04 > L05 > L06 | L07 > L08 > [ L09 ] L10 > L11 > L12 > L13`

> *"能互相交流的队友。"* -- 从一次性子智能体到持久命名队友。

## 问题

子智能体（L04）是"发射即忘"的：spawn -> execute -> return summary -> destroyed。它们没有身份，不能互相通信，完成任务后就消失了。我们需要持久的、有名字的队友，它们可以：
- 存活在整个会话周期中
- 在独立线程中运行各自的 agent loop
- 通过文件系统收件箱互相发送消息

## 解决方案

```
子智能体 (L04):  spawn -> execute -> return summary -> destroyed
队友 (L09):      spawn -> working -> idle -> working -> ... -> shutdown

+------------------+                        +------------------+
|   Lead Agent     |                        |   Teammate "A"   |
|   (主线程)        |   send_message         |   (独立线程)      |
|                  | -----> inbox/A.jsonl    |                  |
|                  |                        |  自己的 agent loop |
|                  | <----- inbox/lead.jsonl |                  |
+--------+---------+                        +--------+---------+
         |                                           |
         |  spawn_teammate                           |
         |  list_teammates                           |
         |  broadcast                                |
         v                                           v
+--------+---------+                        +--------+---------+
|  TeammateManager |                        |  TeammateManager |
|  config.json     |                        |  (共享同一个)      |
|  members: [      |                        |                  |
|    {name, role,  |                        |                  |
|     status}      |                        |                  |
|  ]               |                        |                  |
+------------------+                        +------------------+

.team/
  config.json          <-- 团队名册 (name, role, status)
  inbox/
    lead.jsonl         <-- 主智能体的收件箱
    frontend.jsonl     <-- 队友 "frontend" 的收件箱
    backend.jsonl      <-- 队友 "backend" 的收件箱
```

## 工作原理

### 1. MessageBus -- JSONL append-only 收件箱

每个队友有一个 `.jsonl` 文件作为收件箱。消息以 JSON 行追加写入，读取时清空：

```java
static class MessageBus {
    private final Path dir;

    public MessageBus(Path inboxDir) {
        this.dir = inboxDir;
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {}
    }

    public String send(String sender, String to, String content,
                       String msgType, Map<String, Object> extra) {
        if (!VALID_MSG_TYPES.contains(msgType)) {
            return "Error: Invalid type '" + msgType
                    + "'. Valid: " + VALID_MSG_TYPES;
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", msgType);
        msg.put("from", sender);
        msg.put("content", content);
        msg.put("timestamp", System.currentTimeMillis() / 1000.0);
        if (extra != null) msg.putAll(extra);

        Path inboxPath = dir.resolve(to + ".jsonl");
        try {
            Files.write(inboxPath,
                    (mapToJson(msg) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            return "Sent " + msgType + " to " + to;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
```

关键设计：
- **append-only** -- 用 `StandardOpenOption.APPEND` 追加写入，多线程写入安全
- **读后清空** -- `readInbox()` 读取所有行后清空文件，避免重复处理

### 2. readInbox() -- 读取并清空

```java
public List<Map<String, Object>> readInbox(String name) {
    Path inboxPath = dir.resolve(name + ".jsonl");
    if (!Files.exists(inboxPath)) return new ArrayList<>();

    try {
        List<String> lines = Files.readAllLines(
                inboxPath, StandardCharsets.UTF_8);
        Files.write(inboxPath, new byte[0]);  // 清空文件

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
```

### 3. broadcast() -- 群发消息

```java
public String broadcast(String sender, String content,
                        List<String> teammates) {
    int count = 0;
    for (String name : teammates) {
        if (!name.equals(sender)) {
            send(sender, name, content, "broadcast", null);
            count++;
        }
    }
    return "Broadcast to " + count + " teammates";
}
```

### 4. TeammateManager -- 持久化配置与生命周期

团队名册存放在 `config.json` 中：

```java
static class TeammateManager {
    private final Path dir;
    protected final Path configPath;
    protected Map<String, Object> config;
    private final Map<String, Thread> threads = new ConcurrentHashMap<>();
    private final MessageBus bus;
    private final OpenAIClient client;
    private final String model;
    private final Path workDir;

    protected Map<String, Object> loadConfig() {
        if (Files.exists(configPath)) {
            try {
                return parseJsonToMap(new String(
                        Files.readAllBytes(configPath),
                        StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("team_name", "default");
        cfg.put("members", new ArrayList<>());
        return cfg;
    }
}
```

config.json 示例：

```json
{
  "team_name": "default",
  "members": [
    {"name": "frontend", "role": "UI developer", "status": "working"},
    {"name": "backend", "role": "API developer", "status": "idle"}
  ]
}
```

### 5. spawn() -- 创建队友并启动线程

```java
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

    Thread thread = new Thread(
            () -> teammateLoop(name, role, prompt),
            "teammate-" + name);
    thread.setDaemon(true);
    threads.put(name, thread);
    thread.start();

    return "Spawned '" + name + "' (role: " + role + ")";
}
```

关键行为：
- 如果队友已存在且处于 `idle` 或 `shutdown` 状态，可以重新激活
- 如果队友正在 `working`，拒绝重复创建
- 每个队友在**独立守护线程**中运行自己的 agent loop

### 6. teammateLoop() -- 每个队友的独立 Agent Loop

```java
private void teammateLoop(String name, String role, String prompt) {
    String sysPrompt = "You are '" + name + "', role: " + role
            + ", at " + workDir + ". "
            + "Use send_message to communicate. Complete your task.";

    List<ChatCompletionMessageParam> messages = new ArrayList<>();
    messages.add(ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder()
                    .content(prompt).build()));

    List<ChatCompletionTool> tools = createTeammateTools();

    for (int i = 0; i < 50; i++) {
        // 读取收件箱, 新消息作为 user message 注入
        List<Map<String, Object>> inbox = bus.readInbox(name);
        for (Map<String, Object> msg : inbox) {
            messages.add(ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                            .content(mapToJson(msg)).build()));
        }

        try {
            ChatCompletion completion = client.chat().completions()
                    .create(ChatCompletionCreateParams.builder()
                            .model(ChatModel.of(model))
                            .messages(messages)
                            .tools(tools)
                            .addSystemMessage(sysPrompt)
                            .build());

            ChatCompletion.Choice choice = completion.choices().get(0);
            messages.add(ChatCompletionMessageParam.ofAssistant(
                    choice.message().toParam()));

            if (choice.finishReason()
                    != ChatCompletion.Choice.FinishReason.TOOL_CALLS) {
                break;  // 无工具调用, 任务完成
            }

            // 处理工具调用...
        } catch (Exception e) {
            break;
        }
    }

    // 工作完成, 设置为 idle 状态
    Map<String, Object> member = findMember(name);
    if (member != null && !member.get("status").equals("shutdown")) {
        member.put("status", "idle");
        saveConfig();
    }
}
```

### 7. 队友生命周期状态图

```
                spawn(name, role, prompt)
                        |
                        v
              +--------------------+
              |      working       |  <-- 队友线程运行中
              +----+----------+----+
                   |          |
        LLM 完成   |          | 无更多工作
        (无工具调用) |          |
                   v          v
              +--------------------+
              |       idle         |  <-- 线程结束, 可被重新 spawn
              +----+----------+----+
                   |          |
        重新 spawn  |          | 外部请求关闭
                   v          v
              +----+----+  +------+
              | working |  |shutdown|
              +---------+  +-------+
```

### 8. 消息类型

```java
private static final Set<String> VALID_MSG_TYPES = Set.of(
        "message",              // 普通消息
        "broadcast",            // 群发
        "shutdown_request",     // 关机请求 (L10)
        "shutdown_response",    // 关机响应 (L10)
        "plan_approval_response" // 计划审批 (L10)
);
```

## 变更一览

| 组件 | 之前 (L08) | 之后 (L09) |
|------|-----------|-----------|
| 多智能体 | 无 (单智能体) | TeammateManager + 命名队友 |
| 通信 | 无 | MessageBus + JSONL append-only 收件箱 |
| 线程模型 | 只有后台任务线程 | 每个队友一个独立线程 |
| 配置持久化 | 无 | `config.json` 团队名册 |
| 生命周期 | 无 | `working -> idle -> shutdown` |
| 新工具 | 无 | `spawn_teammate`, `list_teammates`, `send_message`, `read_inbox`, `broadcast` |

## 试一试

```sh
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson9 --prompt='Spawn a frontend teammate and a backend teammate. Ask frontend to create trysamples/index.html and backend to create trysamples/server.conf.'"
```

运行后查看 `.team/` 目录：

```sh
cat .team/config.json
ls .team/inbox/
```

**源码**: [`Lesson9RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson9RunSimple.java)
