# Lesson 13: Full Reference Agent

```
L00 > L01 > L02 > L03 > L04 > L05 > L06 > L07 > L08 > L09 > L10 > L11 > L12 > [ L13 ]
```

> "Every mechanism, one agent."

---

## What This Is

This is **not** a teaching lesson. It is the capstone reference implementation that combines **every mechanism from Lessons 1-12** into a single, production-ready agent. Use it as:

- A reference for how all components integrate
- A starting point for building real agents
- A test harness for verifying all subsystems work together

## Architecture Overview

```
+====================================================================+
|                        Lesson13RunSimple                            |
|                                                                     |
|  +-------------------+    +-------------------+    +--------------+ |
|  |   Agent Loop      |    |   Compression     |    |  Skill       | |
|  |   (L01/L02)       |    |   Pipeline (L06)  |    |  Loader (L05)| |
|  |                    |    |                   |    |              | |
|  |  while(true):      |    |  micro-compact    |    |  SKILL.md   | |
|  |   drain bg notifs  |    |  auto-compact     |    |  files in   | |
|  |   check inbox      |    |  manual compress  |    |  skills/    | |
|  |   LLM call         |    |  transcript save  |    +--------------+ |
|  |   tool dispatch    |    +-------------------+                    |
|  +-------------------+                                              |
|                                                                     |
|  +-------------------+    +-------------------+    +--------------+ |
|  |   TodoManager     |    |   TaskManager     |    |  Background  | |
|  |   (L03)           |    |   (L07)           |    |  Manager(L08)| |
|  |                    |    |                   |    |              | |
|  |  in-memory list    |    |  .tasks/*.json    |    |  daemon      | |
|  |  nag reminder      |    |  blockedBy/blocks |    |  threads     | |
|  |  max 1 in_progress |    |  claim by owner   |    |  notif queue | |
|  +-------------------+    +-------------------+    +--------------+ |
|                                                                     |
|  +-------------------+    +-------------------+    +--------------+ |
|  |   Subagent        |    |   MessageBus      |    |  Teammate    | |
|  |   (L04)           |    |   (L09)           |    |  Manager     | |
|  |                    |    |                   |    |  (L09-L11)   | |
|  |  spawn -> run ->   |    |  .team/inbox/     |    |              | |
|  |  return summary    |    |  *.jsonl files    |    |  config.json | |
|  |  own tool set      |    |  append + drain   |    |  autonomous  | |
|  +-------------------+    +-------------------+    |  idle/poll   | |
|                                                    |  protocols   | |
|                                                    +--------------+ |
+====================================================================+
```

## Component Integration

### Initialization: All Managers Created Together

```java
@Override
public void run(String userPrompt) {
    workDir = Paths.get(System.getProperty("user.dir"));
    teamDir = workDir.resolve(".team");
    inboxDir = teamDir.resolve("inbox");
    tasksDir = workDir.resolve(".tasks");
    skillsDir = workDir.resolve("skills");
    transcriptDir = workDir.resolve(".transcripts");

    client = OpenAIOkHttpClient.builder()
        .apiKey(apiKey).baseUrl(baseUrl)
        .proxy(new Proxy(Proxy.Type.HTTP,
            new InetSocketAddress(proxyHost, proxyPort)))
        .build();

    // Initialize ALL managers
    todo = new TodoManager();           // L03
    skills = new SkillLoader(skillsDir); // L05
    taskMgr = new TaskManager(tasksDir); // L07
    bg = new BackgroundManager(workDir); // L08
    bus = new MessageBus(inboxDir);      // L09
    team = new TeammateManager();        // L09-L11

    String sysPrompt = buildSystemPrompt();
    agentLoop(messages, sysPrompt);
}
```

### The Agent Loop: Everything Converges

The main loop integrates all subsystems in a specific order:

```java
private void agentLoop(List<ChatCompletionMessageParam> messages,
                       String sysPrompt) {
    int roundsWithoutTodo = 0;

    while (true) {
        // 1. L06: Compression pipeline
        microCompact(messages);
        if (estimateTokens(messages) > TOKEN_THRESHOLD) {
            List<ChatCompletionMessageParam> compressed =
                autoCompact(messages);
            messages.clear();
            messages.addAll(compressed);
        }

        // 2. L08: Drain background notifications
        List<Map<String, Object>> notifs = bg.drain();
        if (!notifs.isEmpty()) {
            messages.add(ofUser("<background-results>\n" + /* ... */));
            messages.add(ofAssistant("Noted background results."));
        }

        // 3. L09: Check lead inbox
        List<Map<String, Object>> inbox = bus.readInbox("lead");
        if (!inbox.isEmpty()) {
            messages.add(ofUser("<inbox>" + listToJson(inbox) + "</inbox>"));
            messages.add(ofAssistant("Noted inbox messages."));
        }

        // 4. LLM call with ALL tools registered
        ChatCompletion completion = client.chat().completions()
            .create(ChatCompletionCreateParams.builder()
                .model(ChatModel.of(modelName))
                .messages(messages)
                .tools(createTools())  // all tools from all lessons
                .addSystemMessage(sysPrompt)
                .build());

        // 5. Tool dispatch
        for (ChatCompletionMessageToolCall tc : toolCalls) {
            String output = executeTool(toolName, args);
            // ...
        }

        // 6. L03: Nag reminder for TodoWrite
        roundsWithoutTodo = usedTodo ? 0 : roundsWithoutTodo + 1;
        if (todo.hasOpenItems() && roundsWithoutTodo >= 3) {
            log.info("[nag reminder injected]");
        }

        // 7. L06: Manual compression if requested
        if (manualCompress) {
            messages.clear();
            messages.addAll(autoCompact(messages));
        }
    }
}
```

### Inner Class: TodoManager (L03)

In-memory checklist with nag reminders:

```java
static class TodoManager {
    private List<Map<String, Object>> items = new ArrayList<>();

    public String update(List<Map<String, Object>> newItems) {
        if (newItems.size() > 20)
            throw new IllegalArgumentException("Max 20 todos");

        int ip = 0;
        for (Map<String, Object> item : newItems) {
            if ("in_progress".equals(item.get("status"))) ip++;
        }
        if (ip > 1)
            throw new IllegalArgumentException(
                "Only one in_progress allowed");

        items = new ArrayList<>(newItems);
        return render();
    }

    public boolean hasOpenItems() {
        return items.stream()
            .anyMatch(t -> !"completed".equals(t.get("status")));
    }
}
```

### Inner Class: SkillLoader (L05)

Loads `SKILL.md` files from the `skills/` directory and injects them into the system prompt:

```java
static class SkillLoader {
    private final Map<String, Map<String, String>> skills = new HashMap<>();

    public SkillLoader(Path skillsDir) {
        Files.walk(skillsDir)
            .filter(p -> p.getFileName().toString().equals("SKILL.md"))
            .forEach(p -> {
                String text = Files.readString(p);
                String name = p.getParent().getFileName().toString();
                skills.put(name, Map.of(
                    "body", text,
                    "description", "Skill: " + name));
            });
    }

    public String load(String name) {
        Map<String, String> s = skills.get(name);
        if (s == null) return "Error: Unknown skill '" + name + "'";
        return "<skill name=\"" + name + "\">\n"
            + s.get("body") + "\n</skill>";
    }

    public String getDescriptions() {
        return skills.entrySet().stream()
            .map(e -> "  - " + e.getKey() + ": "
                + e.getValue().get("description"))
            .collect(Collectors.joining("\n"));
    }
}
```

### Inner Class: TaskManager (L07)

File-based task graph with dependency resolution and owner-based claiming:

```java
static class TaskManager {
    public String create(String subject, String description) { /* ... */ }
    public String get(int taskId) { /* ... */ }
    public String update(int taskId, String status,
        List<Integer> addBlockedBy, List<Integer> addBlocks) { /* ... */ }
    public String listAll() { /* ... */ }
    public String claim(int taskId, String owner) { /* ... */ }
}
```

### Inner Class: BackgroundManager (L08)

Daemon threads with notification queue:

```java
static class BackgroundManager {
    private final Map<String, Map<String, Object>> tasks =
        new ConcurrentHashMap<>();
    private final List<Map<String, Object>> notifications =
        new CopyOnWriteArrayList<>();

    public String run(String command, int timeout) { /* ... */ }
    public String check(String taskId) { /* ... */ }
    public List<Map<String, Object>> drain() { /* ... */ }
}
```

### Inner Class: MessageBus (L09)

Extends the Lesson 10 MessageBus for JSONL inboxes:

```java
static class MessageBus extends Lesson10RunSimple.MessageBus {
    public MessageBus(Path inboxDir) { super(inboxDir); }
}
```

### Inner Class: TeammateManager (L09-L11)

Full autonomous teammates with protocols:

```java
class TeammateManager {
    public String spawn(String name, String role, String prompt) {
        // Creates thread with autonomousLoop
    }

    private void autonomousLoop(String name, String role, String prompt) {
        while (true) {
            // WORK PHASE: agent loop with LLM calls
            // IDLE PHASE: poll inbox + task board
            // Shutdown on timeout or shutdown_request
        }
    }
}
```

### Subagent Delegation (L04)

The `task` tool spawns a sub-agent with its own conversation and limited tools:

```java
private String runSubagent(String prompt, String agentType) {
    List<ChatCompletionTool> subTools = createSubagentTools();
    List<ChatCompletionMessageParam> subMessages = new ArrayList<>();
    subMessages.add(ofUser(prompt));

    for (int i = 0; i < 30; i++) {
        ChatCompletion completion = client.chat().completions()
            .create(ChatCompletionCreateParams.builder()
                .model(ChatModel.of(modelName))
                .messages(subMessages)
                .tools(subTools)
                .build());

        // Process tool calls or break on stop
        if (finishReason != TOOL_CALLS) break;
    }

    return lastResponse.content().orElse("(subagent failed)");
}
```

### Compression Pipeline (L06)

Three compression strategies working together:

```java
// 1. Micro-compact: truncate old tool results to save context
private void microCompact(List<ChatCompletionMessageParam> messages) {
    // Tool results beyond the last 6 messages get truncated to 200 chars
}

// 2. Auto-compact: triggered when tokens exceed threshold
private List<ChatCompletionMessageParam> autoCompact(
        List<ChatCompletionMessageParam> messages) {
    // Save transcript to .transcripts/
    // Ask LLM to summarize conversation
    // Replace messages with summary
}

// 3. Manual: agent calls "compress" tool explicitly
if ("compress".equals(toolName)) manualCompress = true;
```

### System Prompt: Skills Integration

The system prompt dynamically includes skill descriptions:

```java
private String buildSystemPrompt() {
    return systemPrompt + "\n"
        + "You are a coding agent at " + workDir + ".\n"
        + "Use tools to solve tasks.\n"
        + "Prefer task_create/task_update/task_list for multi-step work.\n"
        + "Use TodoWrite for short checklists.\n"
        + "Use task for subagent delegation.\n"
        + "Use load_skill for specialized knowledge.\n"
        + "Skills:\n" + skills.getDescriptions();
}
```

## Full Tool Registry

All tools from all lessons, registered in one `createTools()` method:

| Source | Tool | Description |
|--------|------|-------------|
| L02 | `bash` | Run a shell command |
| L02 | `read_file` | Read file contents |
| L02 | `write_file` | Write content to file |
| L02 | `edit_file` | Replace exact text in file |
| L03 | `TodoWrite` | Update the todo checklist |
| L04 | `task` | Delegate to a subagent |
| L05 | `load_skill` | Load a skill by name |
| L06 | `compress` | Manually trigger context compression |
| L07 | `task_create` | Create a task on the board |
| L07 | `task_update` | Update task status/dependencies |
| L07 | `task_list` | List all tasks |
| L07 | `task_get` | Get task details by ID |
| L08 | `background_run` | Run command in background thread |
| L08 | `check_background` | Check background task status |
| L09 | `spawn_teammate` | Spawn an autonomous teammate |
| L09 | `list_teammates` | List team members |
| L09 | `send_message` | Send message to a teammate |
| L09 | `read_inbox` | Read and drain the lead inbox |
| L09 | `broadcast` | Broadcast to all teammates |
| L10 | `shutdown_request` | Request teammate shutdown |
| L10 | `plan_approval` | Approve/reject a teammate's plan |

## Directory Structure at Runtime

```
  project/
    |
    +-- .tasks/                    <-- L07: Task JSON files
    |     +-- task_1.json
    |     +-- task_2.json
    |
    +-- .team/                     <-- L09: Team config + inboxes
    |     +-- config.json
    |     +-- inbox/
    |           +-- lead.jsonl
    |           +-- alice.jsonl
    |
    +-- .transcripts/              <-- L06: Compressed transcripts
    |     +-- transcript_17112345.jsonl
    |
    +-- skills/                    <-- L05: Skill definitions
          +-- git/
          |     +-- SKILL.md
          +-- docker/
                +-- SKILL.md
```

## Data Flow

```
  User prompt
       |
       v
  +-- Agent Loop ------------------------------------------------+
  |                                                               |
  |  1. microCompact(messages)          [L06]                     |
  |  2. autoCompact if over threshold   [L06]                     |
  |  3. bg.drain() -> inject notifs     [L08]                     |
  |  4. bus.readInbox("lead") -> inject [L09]                     |
  |  5. LLM call with all tools                                  |
  |  6. Tool dispatch:                                            |
  |     +-- bash/read/write/edit        [L02]                     |
  |     +-- TodoWrite + nag check       [L03]                     |
  |     +-- task (subagent)             [L04]                     |
  |     +-- load_skill                  [L05]                     |
  |     +-- compress                    [L06]                     |
  |     +-- task_create/update/list/get [L07]                     |
  |     +-- background_run/check        [L08]                     |
  |     +-- spawn/send/broadcast/inbox  [L09]                     |
  |     +-- shutdown_request/plan       [L10]                     |
  |  7. Nag reminder if no TodoWrite    [L03]                     |
  |  8. Manual compress if requested    [L06]                     |
  |                                                               |
  +---------------------------------------------------------------+
       |
       v
  Assistant response
```

---

## What Changed (from Lesson 12)

| Aspect | Lesson 12 (Worktree Isolation) | Lesson 13 (Full Agent) |
|--------|-------------------------------|----------------------|
| Scope | Single mechanism (worktrees) | All mechanisms combined |
| Managers | WorktreeManager, EventBus | TodoManager, SkillLoader, TaskManager, BackgroundManager, MessageBus, TeammateManager |
| Tools | 16 tools | 21+ tools from all lessons |
| Compression | Not included | Full pipeline (micro + auto + manual) |
| Subagents | Not included | Subagent delegation via `task` tool |
| Skills | Not included | `SkillLoader` with `SKILL.md` files |
| TodoWrite | Not included | In-memory checklist with nag reminders |
| System prompt | Static | Dynamic with skill descriptions |

---

## Try It

1. Run `Lesson13RunSimple` with a complex prompt: `"Plan a REST API project in the trysamples directory. Create tasks for each component. Spawn alice and bob to work on different parts. Use background tasks for builds."`
2. Observe all subsystems working together:
   - Tasks appear in `.tasks/`
   - Teammates communicate via `.team/inbox/`
   - Background builds run in daemon threads
   - Context compression triggers when the conversation grows
3. Check `.transcripts/` for saved conversation history after compression.
4. Add a `skills/` directory with `SKILL.md` files and use `load_skill` to inject domain knowledge.

---

**Source**: [`Lesson13RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson13RunSimple.java)
