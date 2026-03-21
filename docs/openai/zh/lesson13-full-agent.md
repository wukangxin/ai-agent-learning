# Lesson 13: Full Reference Agent (完整参考智能体)

`L00 > L01 > L02 > L03 > L04 > L05 > L06 | L07 > L08 > L09 > L10 > L11 > L12 > [ L13 ]`

> *"不是一堂新课 -- 是把 L01 到 L12 的所有机制组装在一起。"* -- 终极参考实现。

## 问题

前 12 课每一课都聚焦于单一机制。但在真实场景中，智能体需要**同时**运用所有这些机制：调度工具、追踪进度、压缩上下文、分派子任务、管理后台进程、协调团队协作。这节课不引入新概念，而是展示它们如何整合为一个完整的系统。

## 全景架构图

```
+----------------------------------------------------------------------+
|                        Lesson13RunSimple                             |
|                        (Full Reference Agent)                        |
+----------------------------------------------------------------------+
|                                                                      |
|  +------------------------+  +-----------------------+               |
|  | System Prompt Builder  |  |   Agent Loop          |               |
|  | - 技能描述注入          |  |   - 微压缩 (L06)      |               |
|  | - 工作目录注入          |  |   - 自动压缩 (L06)    |               |
|  +------------------------+  |   - 排空后台通知 (L08) |               |
|                              |   - 排空收件箱 (L09)   |               |
|                              |   - Nag 提醒 (L03)    |               |
|                              |   - 手动压缩 (L06)    |               |
|                              +-----------+-----------+               |
|                                          |                           |
|  +---------------------------------------+---------------------------+
|  |                    Tool Dispatch (L02)                            |
|  |  bash | read_file | write_file | edit_file                       |
|  |  TodoWrite (L03) | task (L04) | load_skill (L05) | compress (L06)|
|  |  task_create | task_update | task_list | task_get (L07)           |
|  |  background_run | check_background (L08)                         |
|  |  spawn_teammate | send_message | read_inbox | broadcast (L09)    |
|  |  shutdown_request | plan_approval (L10)                           |
|  |  claim_task (L11)                                                 |
|  +-------------------------------------------------------------------+
|                                                                      |
|  +-------------------+  +--------------+  +-----------------------+  |
|  |   TodoManager     |  | SkillLoader  |  |   TaskManager         |  |
|  |   (L03)           |  | (L05)        |  |   (L07)               |  |
|  |   - items[]       |  | - skills/    |  |   - .tasks/           |  |
|  |   - render()      |  | - names.json |  |   - CRUD + deps       |  |
|  |   - nag reminder  |  | - load body  |  |   - clearDependency() |  |
|  +-------------------+  +--------------+  +-----------------------+  |
|                                                                      |
|  +-------------------+  +------------------+  +-------------------+  |
|  | BackgroundManager |  |   MessageBus     |  | TeammateManager   |  |
|  | (L08)             |  |   (L09)          |  | (L09+L10+L11)     |  |
|  | - daemon threads  |  |   - JSONL inbox  |  | - config.json     |  |
|  | - notif queue     |  |   - append-only  |  | - autonomous loop |  |
|  | - drain before    |  |   - read+clear   |  | - protocols       |  |
|  |   each LLM call   |  |   - broadcast    |  | - idle polling    |  |
|  +-------------------+  +------------------+  +-------------------+  |
+----------------------------------------------------------------------+
```

## 组件整合详解

### 1. 初始化 -- 创建所有管理器

所有管理器在 `run()` 中统一初始化：

```java
@Override
public void run(String userPrompt) {
    log.info("Starting Lesson13 (Full Agent) with model: {}",
            modelName);

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
            .proxy(new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(proxyHost, proxyPort)))
            .build();

    // 初始化所有管理器
    todo = new TodoManager();          // L03
    skills = new SkillLoader(skillsDir);  // L05
    taskMgr = new TaskManager(tasksDir);  // L07
    bg = new BackgroundManager(workDir);  // L08
    bus = new MessageBus(inboxDir);       // L09
    team = new TeammateManager();         // L09+L10+L11

    List<ChatCompletionMessageParam> messages = new ArrayList<>();
    messages.add(ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder()
                    .content(userPrompt).build()));

    String sysPrompt = buildSystemPrompt();
    agentLoop(messages, sysPrompt);
}
```

### 2. System Prompt -- 技能清单注入 (L05)

```java
private String buildSystemPrompt() {
    return (systemPrompt != null && !systemPrompt.isEmpty()
            ? systemPrompt + "\n" : "")
            + "You are a coding agent at " + workDir
            + ". Use tools to solve tasks.\n"
            + "Prefer task_create/task_update/task_list "
            + "for multi-step work. "
            + "Use TodoWrite for short checklists.\n"
            + "Use task for subagent delegation. "
            + "Use load_skill for specialized knowledge.\n"
            + "Skills:\n" + skills.getDescriptions();
}
```

### 3. Agent Loop -- 六层整合

每一轮 LLM 调用前，按顺序执行六个步骤：

```java
private void agentLoop(List<ChatCompletionMessageParam> messages,
                       String sysPrompt) {
    int roundsWithoutTodo = 0;

    while (true) {
        // [1] L06: 微压缩 -- 截断过长的工具输出
        microCompact(messages);

        // [2] L06: 自动压缩 -- token 超阈值时触发
        if (estimateTokens(messages) > TOKEN_THRESHOLD) {
            log.info("[auto-compact triggered]");
            List<ChatCompletionMessageParam> compressed
                    = autoCompact(messages);
            messages.clear();
            messages.addAll(compressed);
        }

        // [3] L08: 排空后台任务通知
        List<Map<String, Object>> notifs = bg.drain();
        if (!notifs.isEmpty()) {
            StringBuilder txt = new StringBuilder(
                    "<background-results>\n");
            for (Map<String, Object> n : notifs) {
                txt.append("[bg:").append(n.get("task_id"))
                        .append("] ").append(n.get("status"))
                        .append(": ").append(n.get("result"))
                        .append("\n");
            }
            txt.append("</background-results>");
            messages.add(/* user message with txt */);
            messages.add(/* assistant ack */);
        }

        // [4] L09: 排空 Lead 收件箱
        List<Map<String, Object>> inbox = bus.readInbox("lead");
        if (!inbox.isEmpty()) {
            messages.add(/* inbox messages */);
            messages.add(/* assistant ack */);
        }

        // [5] LLM 调用
        ChatCompletion completion = client.chat().completions()
                .create(/* params */);

        // [6] 工具调用处理 + L03 Nag 提醒
        if (/* tool calls */) {
            boolean usedTodo = false;
            boolean manualCompress = false;

            for (/* each tool call */) {
                if ("compress".equals(toolName))
                    manualCompress = true;
                if ("TodoWrite".equals(toolName))
                    usedTodo = true;

                String output = executeTool(toolName, args);
                // ...
            }

            // L03: 如果连续 3 轮未更新 Todo, 注入提醒
            roundsWithoutTodo = usedTodo ? 0
                    : roundsWithoutTodo + 1;
            if (todo.hasOpenItems() && roundsWithoutTodo >= 3) {
                log.info("[nag reminder injected]");
            }

            // L06: 手动压缩
            if (manualCompress) {
                log.info("[manual compact]");
                List<ChatCompletionMessageParam> compressed
                        = autoCompact(messages);
                messages.clear();
                messages.addAll(compressed);
            }
        }
    }
}
```

### 4. TodoManager (L03) -- 短清单追踪

```java
static class TodoManager {
    private List<Map<String, Object>> items = new ArrayList<>();

    public String update(List<Map<String, Object>> newItems) {
        if (newItems.size() > 20)
            throw new IllegalArgumentException("Max 20 todos");
        int ip = 0;
        for (Map<String, Object> item : newItems) {
            String status = (String) item.getOrDefault(
                    "status", "pending");
            if ("in_progress".equals(status)) ip++;
        }
        if (ip > 1)
            throw new IllegalArgumentException(
                    "Only one in_progress allowed");
        items = new ArrayList<>(newItems);
        return render();
    }

    public String render() {
        if (items.isEmpty()) return "No todos.";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> item : items) {
            String m = switch ((String) item.get("status")) {
                case "completed"   -> "[x]";
                case "in_progress" -> "[>]";
                default            -> "[ ]";
            };
            sb.append(m).append(" ").append(item.get("content"));
            if ("in_progress".equals(item.get("status"))
                    && item.get("activeForm") != null) {
                sb.append(" <- ").append(item.get("activeForm"));
            }
            sb.append("\n");
        }
        long done = items.stream()
                .filter(t -> "completed".equals(t.get("status")))
                .count();
        sb.append("\n(").append(done).append("/")
                .append(items.size()).append(" completed)");
        return sb.toString();
    }

    public boolean hasOpenItems() {
        return items.stream().anyMatch(
                t -> !"completed".equals(t.get("status")));
    }
}
```

### 5. SkillLoader (L05) -- 两层技能注入

```java
static class SkillLoader {
    private final Path dir;
    private Map<String, String> names; // name -> short description

    public SkillLoader(Path skillsDir) {
        this.dir = skillsDir;
        this.names = loadNames();
    }

    public String getDescriptions() {
        if (names.isEmpty()) return "(no skills available)";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : names.entrySet()) {
            sb.append("  - ").append(e.getKey())
                    .append(": ").append(e.getValue())
                    .append("\n");
        }
        return sb.toString();
    }

    public String load(String name) {
        Path bodyPath = dir.resolve(name + ".md");
        try {
            return new String(Files.readAllBytes(bodyPath),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "Error: Skill '" + name + "' not found";
        }
    }
}
```

### 6. BackgroundManager (L08) -- 异步执行

```java
static class BackgroundManager {
    private final Map<String, TaskInfo> tasks
            = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> queue
            = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();

    public String run(String command) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        tasks.put(id, new TaskInfo("running", command, null));

        Thread t = new Thread(() -> execute(id, command),
                "bg-" + id);
        t.setDaemon(true);
        t.start();

        return "Background task " + id + " started";
    }

    public List<Map<String, Object>> drain() {
        lock.lock();
        try {
            if (queue.isEmpty()) return List.of();
            List<Map<String, Object>> result
                    = new ArrayList<>(queue);
            queue.clear();
            return result;
        } finally {
            lock.unlock();
        }
    }
}
```

### 7. MessageBus (L09) + TeammateManager (L09+L10+L11)

TeammateManager 整合了三课的功能：

```java
class TeammateManager {
    // L09: 基本 spawn/list/config 管理
    // L10: 协议支持 (shutdown_request/response, plan_approval)
    // L11: 自治循环 (Work/Idle 交替, 任务看板轮询, 身份重注入)

    private void autonomousLoop(String name, String role,
                                String prompt) {
        while (true) {
            // WORK 阶段: 正常 agent loop
            for (int i = 0; i < 50; i++) {
                // 检查收件箱 (L09)
                // 处理关机请求 (L10)
                // LLM 调用 + 工具处理
                // idle 信号检测 (L11)
            }

            // IDLE 阶段: 轮询任务看板 (L11)
            setStatus(name, "idle");
            for (int i = 0; i < polls; i++) {
                TimeUnit.SECONDS.sleep(POLL_INTERVAL);

                // 检查收件箱
                // 扫描未认领任务
                // 身份重注入 (L11)
                // 自动认领 (L11)
            }
        }
    }
}
```

### 8. 完整工具清单

```java
// L02: 基础工具
"bash"              // 同步命令执行
"read_file"         // 读取文件
"write_file"        // 写入文件
"edit_file"         // 替换文件内容

// L03: 进度追踪
"TodoWrite"         // 更新待办清单

// L04: 子智能体
"task"              // 分派子任务

// L05: 技能加载
"load_skill"        // 按需加载技能详情

// L06: 上下文管理
"compress"          // 手动触发压缩

// L07: 任务管理
"task_create"       // 创建任务
"task_update"       // 更新任务状态/依赖
"task_list"         // 列出所有任务
"task_get"          // 获取任务详情

// L08: 后台执行
"background_run"    // 后台执行命令
"check_background"  // 查询后台任务状态

// L09: 团队协作
"spawn_teammate"    // 创建队友
"list_teammates"    // 列出队友
"send_message"      // 发送消息
"read_inbox"        // 读取收件箱
"broadcast"         // 群发消息

// L10: 团队协议
"shutdown_request"  // 请求队友关机
"plan_approval"     // 审批计划

// L11: 自治
"claim_task"        // 认领任务
```

### 9. 压缩管线 (L06)

三层压缩在 Agent Loop 中的位置：

```
每一轮:
  [1] microCompact()    -- 截断 >1500 字符的工具输出为前500+后500
  [2] autoCompact()     -- token > 100000 时, LLM 自动摘要
  [3] 手动 compress     -- 智能体主动调用 compress 工具

压缩后保留:
  - 系统提示 (含技能列表)
  - 最后 10 条消息 (近期上下文)
  - LLM 生成的摘要 (压缩产物)
```

## 组件交互全景

```
用户提示
    |
    v
+---+---+  system prompt  +--+--+  skills/names.json
| Agent | <--------------- |Skill|
| Loop  |  (含技能列表)     |Loader|
+---+---+                 +-----+
    |
    | tool calls
    v
+---+-----+------+------+------+------+------+------+
| bash    | read | write| edit |Todo  |task  |skill |
| (L02)   | (L02)| (L02)|(L02)|(L03) |(L04) |(L05) |
+---------+------+------+------+------+------+------+
|compress |task_ |task_ |task_ |task_ |bg_run|bg_chk|
| (L06)   |creat |updat |list  |get   |(L08) |(L08) |
|         |(L07) |(L07) |(L07) |(L07) |      |      |
+---------+------+------+------+------+------+------+
|spawn   |send  |read  |broad |shut  |plan  |claim |
|teammate|msg   |inbox |cast  |down  |approv|task  |
|(L09)   |(L09) |(L09) |(L09) |(L10) |(L10) |(L11) |
+---------+------+------+------+------+------+------+
    |         |         |         |         |
    v         v         v         v         v
 +------+ +------+ +-------+ +------+ +-------+
 |.tasks| |.team/| |.team/ | |skills| |.trans-|
 |      | |config| |inbox/ | |/     | |cripts/|
 | JSON | |.json | |JSONL  | | .md  | | .txt  |
 +------+ +------+ +-------+ +------+ +-------+
```

## 变更一览

| 组件 | 来源课程 | 在 L13 中的角色 |
|------|---------|----------------|
| Tool Dispatch | L02 | bash, read, write, edit 基础工具 |
| TodoManager | L03 | 短清单追踪 + nag 提醒 |
| Subagent | L04 | `task` 工具分派子任务 |
| SkillLoader | L05 | 两层技能注入 (名称 + 按需加载) |
| Context Compression | L06 | 微压缩 + 自动压缩 + 手动压缩 |
| TaskManager | L07 | 文件持久化任务 + 依赖图 |
| BackgroundManager | L08 | 守护线程 + 通知队列 |
| MessageBus | L09 | JSONL append-only 收件箱 |
| TeammateManager | L09+L10+L11 | 队友管理 + 协议 + 自治循环 |
| Protocols | L10 | 关机握手 + 计划审批 |
| Autonomous Loop | L11 | Work/Idle 交替 + 任务看板轮询 |

## 试一试

```sh
cd ai-agent-learning
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson13 --prompt='Create a multi-step project: 1) Setup a Spring Boot config file, 2) Write a REST controller, 3) Write unit tests. Use task management to track progress and spawn a teammate to help with testing.'"
```

这将展示完整智能体的所有能力：任务管理、团队协作、后台执行、技能加载、上下文压缩。

**源码**: [`Lesson13RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson13RunSimple.java)
