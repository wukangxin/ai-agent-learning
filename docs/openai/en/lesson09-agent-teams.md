# Lesson 9: Agent Teams

```
L00 > L01 > L02 > L03 > L04 > L05 > L06 > L07 > L08 > [ L09 ] > L10 > L11 > L12 > L13
```

> "Teammates that can talk to each other."

---

## The Problem

A subagent (Lesson 4) is ephemeral: spawn, execute, return a summary, destroyed. It cannot persist between tasks, cannot communicate with other agents, and cannot maintain context across multiple assignments.

```
  Subagent (Lesson 4):

    Lead: "Do X" --> [spawn] --> [execute] --> [summary] --> [destroyed]

    No memory. No communication. No persistence.
```

For complex projects, you need **persistent agents** that:
- Stay alive across multiple assignments
- Talk to each other (not just to the lead)
- Have their own identity and role

## The Solution

A **team** of named agents, each running its own agent loop in a separate thread. Communication happens through **file-based JSONL inboxes** -- append-only message files that any agent can write to and the recipient reads and clears.

```
  Teammate lifecycle:

    spawn("alice", "frontend dev", "Build the UI")
       |
       v
    [working] --- agent loop with own tools ---+
       |                                        |
       v                                        |
    [idle] <-- no more tool calls               |
       |                                        |
       v (new message arrives)                  |
    [working] --- resumes ---                   |
       |                                        |
       v                                        |
    [shutdown]                                  |

  vs Subagent:

    spawn --> execute --> return --> destroyed
```

### Architecture

```
  .team/
    |
    +-- config.json          <-- team roster (name, role, status)
    |
    +-- inbox/
          |
          +-- lead.jsonl     <-- lead's inbox
          +-- alice.jsonl    <-- alice's inbox
          +-- bob.jsonl      <-- bob's inbox

  Each .jsonl file is append-only. Reader clears after reading.
```

## How It Works

### TeammateManager: Config-Based Roster

The team roster lives in `config.json`:

```json
{
  "team_name": "default",
  "members": [
    {"name": "alice", "role": "frontend dev", "status": "working"},
    {"name": "bob",   "role": "backend dev",  "status": "idle"}
  ]
}
```

Spawning a teammate creates a new thread with its own agent loop:

```java
static class TeammateManager {
    private final Map<String, Thread> threads = new ConcurrentHashMap<>();
    private final MessageBus bus;
    private final OpenAIClient client;

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
            ((List<Map<String, Object>>) config.get("members"))
                .add(member);
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
}
```

### MessageBus: JSONL Append-Only Inboxes

Each teammate has a `.jsonl` file. Sending appends a line; reading drains the file.

```java
static class MessageBus {
    private final Path dir;

    public String send(String sender, String to, String content,
                       String msgType, Map<String, Object> extra) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", msgType);
        msg.put("from", sender);
        msg.put("content", content);
        msg.put("timestamp", System.currentTimeMillis() / 1000.0);
        if (extra != null) msg.putAll(extra);

        Path inboxPath = dir.resolve(to + ".jsonl");
        Files.write(inboxPath,
            (mapToJson(msg) + "\n").getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        return "Sent " + msgType + " to " + to;
    }

    public List<Map<String, Object>> readInbox(String name) {
        Path inboxPath = dir.resolve(name + ".jsonl");
        if (!Files.exists(inboxPath)) return new ArrayList<>();

        List<String> lines = Files.readAllLines(inboxPath);
        Files.write(inboxPath, new byte[0]); // Clear inbox

        List<Map<String, Object>> messages = new ArrayList<>();
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                messages.add(parseJsonToMap(line));
            }
        }
        return messages;
    }

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
}
```

A message in `alice.jsonl` looks like:

```json
{"type":"message","from":"lead","content":"Please implement the login page","timestamp":1711234567.89}
{"type":"broadcast","from":"lead","content":"Sprint planning at 2pm","timestamp":1711234600.0}
```

### Teammate Agent Loop

Each teammate runs its own mini agent loop, checking its inbox at the top of each iteration:

```java
private void teammateLoop(String name, String role, String prompt) {
    String sysPrompt = "You are '" + name + "', role: " + role
        + ", at " + workDir
        + ". Use send_message to communicate. Complete your task.";

    List<ChatCompletionMessageParam> messages = new ArrayList<>();
    messages.add(ofUser(prompt));

    for (int i = 0; i < 50; i++) {
        // Read inbox -- inject any new messages
        List<Map<String, Object>> inbox = bus.readInbox(name);
        for (Map<String, Object> msg : inbox) {
            messages.add(ofUser(mapToJson(msg)));
        }

        ChatCompletion completion = client.chat().completions()
            .create(ChatCompletionCreateParams.builder()
                .model(ChatModel.of(model))
                .messages(messages)
                .tools(createTeammateTools())
                .addSystemMessage(sysPrompt)
                .build());

        // ... process tool calls or break on stop

        if (choice.finishReason() != FinishReason.TOOL_CALLS) break;
    }

    // Set status to idle when done
    Map<String, Object> member = findMember(name);
    if (member != null && !member.get("status").equals("shutdown")) {
        member.put("status", "idle");
        saveConfig();
    }
}
```

### Teammate Tools vs Lead Tools

Teammates get a subset of tools plus messaging:

| Tool | Lead | Teammate |
|------|:----:|:--------:|
| `bash` | Yes | Yes |
| `read_file` | Yes | Yes |
| `write_file` | Yes | Yes |
| `edit_file` | Yes | Yes |
| `spawn_teammate` | Yes | No |
| `list_teammates` | Yes | No |
| `send_message` | Yes | Yes |
| `read_inbox` | Yes | Yes |
| `broadcast` | Yes | No |

### Lead Inbox Integration

The lead's agent loop also checks its inbox before each LLM call:

```java
private void agentLoop(...) {
    while (true) {
        // Check lead inbox for messages from teammates
        List<Map<String, Object>> inbox = bus.readInbox("lead");
        if (!inbox.isEmpty()) {
            messages.add(ofUser("<inbox>" + listToJson(inbox) + "</inbox>"));
            messages.add(ofAssistant("Noted inbox messages."));
        }

        // Normal LLM call...
    }
}
```

---

## What Changed (from Lesson 8)

| Aspect | Lesson 8 (Background Tasks) | Lesson 9 (Agent Teams) |
|--------|---------------------------|----------------------|
| Concurrency unit | Background command | Full agent loop in thread |
| Communication | Notification queue (one-way) | Bidirectional JSONL inboxes |
| Persistence | In-memory task map | `config.json` roster + inbox files |
| Identity | Anonymous task_id | Named agent with role |
| Lifecycle | run -> complete | spawn -> working -> idle -> shutdown |
| New inner classes | `BackgroundManager` | `TeammateManager`, `MessageBus` |
| New tools | `background_run` | `spawn_teammate`, `send_message`, `broadcast`, `list_teammates`, `read_inbox` |

---

## Try It

1. Run `Lesson9RunSimple` with: `"Create a team with alice (frontend) and bob (backend). Alice should build a login form in trysamples/index.html, Bob should build a login API config in trysamples/server.conf. They should coordinate via messages."`
2. Watch `.team/config.json` update with member statuses.
3. Check `.team/inbox/` to see JSONL messages flowing between agents.
4. Observe alice and bob communicating without the lead as intermediary.

---

**Source**: [`Lesson9RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson9RunSimple.java)
