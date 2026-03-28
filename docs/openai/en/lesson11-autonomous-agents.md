# Lesson 11: Autonomous Agents

```
L00 > L01 > L02 > L03 > L04 > L05 > L06 > L07 > L08 > L09 > L10 > [ L11 ] > L12 > L13
```

> "The agent finds work itself."

---

## The Problem

In Lessons 9-10, teammates are reactive: they execute the prompt they were spawned with, then go idle. If new tasks appear on the task board (Lesson 7), nobody picks them up unless the lead explicitly assigns work. The lead becomes a bottleneck.

```
  Lesson 10 flow:

    Lead: spawn("alice", "dev", "Build feature X")
    Alice: [working] --> [idle]           <-- done, now what?
    Lead: "Hey alice, do feature Y"       <-- lead must intervene
    Alice: [working] --> [idle]
    Lead: "Hey alice, do feature Z"       <-- every. single. time.
```

## The Solution

Autonomous agents with a **work/idle cycle**. When a teammate finishes its current work, it enters an **idle phase** where it polls for:

1. New messages in its inbox
2. Unclaimed tasks on the shared task board

If it finds work, it auto-claims the task, re-enters the work phase, and continues. If nothing appears within the timeout, it shuts down.

```
  Autonomous lifecycle:

    spawn
      |
      v
    +============+
    | WORK PHASE |<---------------------------+
    +============+                            |
      |                                       |
      | (calls "idle" tool or finishes)       |
      v                                       |
    +============+                            |
    | IDLE PHASE |  poll every 5s:            |
    +============+  - check inbox             |
      |     |       - scan unclaimed tasks    |
      |     |                                 |
      |     +--- found work: auto-claim ------+
      |
      +--- timeout (60s): shutdown
```

## How It Works

### The Autonomous Loop

The key structural change: the teammate loop is now a **nested loop**. The outer loop alternates between work and idle phases:

```java
private void autonomousLoop(String name, String role, String prompt) {
    String teamName = config.get("team_name").toString();
    String sysPrompt = "You are '" + name + "', role: " + role
        + ", team: " + teamName + ", at " + workDir
        + ". Use idle tool when you have no more work."
        + " You will auto-claim new tasks.";

    List<ChatCompletionMessageParam> messages = new ArrayList<>();
    messages.add(ofUser(prompt));

    while (true) {
        // -- WORK PHASE --
        for (int i = 0; i < 50; i++) {
            // Check inbox for shutdown requests
            List<Map<String, Object>> inbox = bus.readInbox(name);
            for (Map<String, Object> msg : inbox) {
                if ("shutdown_request".equals(msg.get("type"))) {
                    setStatus(name, "shutdown");
                    return;
                }
                messages.add(ofUser(mapToJson(msg)));
            }

            ChatCompletion completion = client.chat().completions()
                .create(/* ... with tools including "idle" */);

            // If agent calls "idle" tool, break to idle phase
            boolean idleRequested = false;
            for (ChatCompletionMessageToolCall tc : toolCalls) {
                if ("idle".equals(tc.function().name())) {
                    idleRequested = true;
                }
            }
            if (idleRequested) break;
        }

        // -- IDLE PHASE --
        setStatus(name, "idle");
        boolean resume = pollForWork(name, role, teamName, messages);

        if (!resume) {
            setStatus(name, "shutdown");
            return;
        }
        setStatus(name, "working");
    }
}
```

### Idle Phase: Task Board Polling

The idle phase polls every `POLL_INTERVAL` seconds (default: 5) for up to `IDLE_TIMEOUT` seconds (default: 60):

```java
// -- IDLE PHASE: poll for inbox and unclaimed tasks --
setStatus(name, "idle");
boolean resume = false;
int polls = IDLE_TIMEOUT / POLL_INTERVAL;  // 60/5 = 12 polls

for (int i = 0; i < polls; i++) {
    TimeUnit.SECONDS.sleep(POLL_INTERVAL);

    // Check inbox first
    List<Map<String, Object>> inbox = bus.readInbox(name);
    if (!inbox.isEmpty()) {
        for (Map<String, Object> msg : inbox) {
            if ("shutdown_request".equals(msg.get("type"))) {
                setStatus(name, "shutdown");
                return;
            }
            messages.add(ofUser(mapToJson(msg)));
        }
        resume = true;
        break;
    }

    // Then check task board for unclaimed tasks
    List<Map<String, Object>> unclaimed = scanUnclaimedTasks();
    if (!unclaimed.isEmpty()) {
        Map<String, Object> task = unclaimed.get(0);
        int taskId = ((Number) task.get("id")).intValue();
        claimTask(taskId, name);

        // Inject the task as a new user message
        messages.add(ofUser("<auto-claimed>Task #" + taskId
            + ": " + task.get("subject") + "\n"
            + task.getOrDefault("description", "")
            + "</auto-claimed>"));
        messages.add(ofAssistant(
            "Claimed task #" + taskId + ". Working on it."));
        resume = true;
        break;
    }
}

if (!resume) {
    setStatus(name, "shutdown");  // Timed out, no work found
    return;
}
```

### Auto-Claiming Unclaimed Tasks

Task scanning uses the file-based task system from Lesson 7. A task is claimable when it is `pending`, has no owner, and has an empty `blockedBy` list:

```java
private List<Map<String, Object>> scanUnclaimedTasks() {
    List<Map<String, Object>> unclaimed = new ArrayList<>();
    Files.list(tasksDir)
        .filter(p -> p.getFileName().toString()
            .matches("task_\\d+\\.json"))
        .forEach(p -> {
            Map<String, Object> t = parseJsonToMap(readFile(p));
            if ("pending".equals(t.get("status"))
                    && t.get("owner") == null
                    && (t.get("blockedBy") == null
                        || ((List<?>) t.get("blockedBy")).isEmpty())) {
                unclaimed.add(t);
            }
        });
    return unclaimed;
}

private String claimTask(int taskId, String owner) {
    claimLock.lock();
    try {
        Path p = tasksDir.resolve("task_" + taskId + ".json");
        Map<String, Object> task = parseJsonToMap(readFile(p));
        task.put("owner", owner);
        task.put("status", "in_progress");
        Files.write(p, mapToJson(task).getBytes(StandardCharsets.UTF_8));
        return "Claimed task #" + taskId + " for " + owner;
    } finally {
        claimLock.unlock();
    }
}
```

The `claimLock` prevents two teammates from claiming the same task simultaneously.

### Identity Re-Injection After Context Compression

When context has been compressed, the agent's conversation history may be very short, losing its identity. The autonomous loop detects this and re-injects identity:

```java
// Identity re-injection for compressed contexts
if (messages.size() <= 3) {
    messages.add(0, ofUser(
        "<identity>You are '" + name + "', role: " + role
        + ", team: " + teamName + ".</identity>"));
    messages.add(1, ofAssistant(
        "I am " + name + ". Continuing."));
}
```

This ensures the agent remembers **who it is** even after heavy compression.

### The Idle Tool

A new tool signals the agent has nothing left to do:

```java
tools.add(createTool("idle",
    "Signal that you have no more work.",
    Map.of(), List.of()));

tools.add(createTool("claim_task",
    "Claim a task from the task board by ID.",
    Map.of("task_id", Map.of("type", "integer")),
    List.of("task_id")));
```

---

## What Changed (from Lesson 10)

| Aspect | Lesson 10 (Team Protocols) | Lesson 11 (Autonomous Agents) |
|--------|--------------------------|------------------------------|
| Work assignment | Lead manually assigns | Agent self-assigns from task board |
| Idle behavior | Goes idle, stays idle | Polls for new work, auto-claims |
| Identity persistence | Lost on compression | Re-injected when context is short |
| Agent lifecycle | spawn -> work -> idle -> shutdown | spawn -> work <-> idle -> timeout/shutdown |
| Task claiming | Not supported | `claimLock` prevents race conditions |
| New tools | -- | `idle`, `claim_task` |
| Constants | -- | `POLL_INTERVAL=5`, `IDLE_TIMEOUT=60` |

---

## Try It

1. Run `Lesson11RunSimple` with: `"Create 3 tasks on the task board (all output files should go to the trysamples directory): write tests, fix bugs, update docs. Then spawn alice as a developer."`
2. Watch alice auto-claim task #1, complete it, enter idle, then auto-claim task #2.
3. Add a new task while alice is idle -- she should pick it up within 5 seconds.
4. If no new tasks appear within 60 seconds of idle, alice shuts down automatically.

---

**Source**: [`Lesson11RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson11RunSimple.java)
