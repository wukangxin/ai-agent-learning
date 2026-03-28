# Lesson 7: Task System

```
L00 > L01 > L02 > L03 > L04 > L05 > L06 > [ L07 ] > L08 > L09 > L10 > L11 > L12 > L13
```

> "State that survives compression -- because it's outside the conversation."

---

## The Problem

The agent loses track of multi-step work when the conversation gets long and context is compressed. In-memory state (like `TodoWrite` from Lesson 3) vanishes when the context window is trimmed.

```
  Round 1: "Create 5 files"
  Round 2: Agent creates file 1, 2
  Round 3: Agent creates file 3
  ~~~ context compression ~~~
  Round 4: Agent forgets files 4 and 5 exist
```

## The Solution

Persist tasks as **individual JSON files** on disk in a `.tasks/` directory. Each task has a dependency graph (`blockedBy` / `blocks`) that forms a **DAG** (Directed Acyclic Graph). Because the state lives on the filesystem, it survives any context compression.

```
                .tasks/
                  |
    +-------------+-------------+
    |             |             |
 task_1.json  task_2.json  task_3.json
 [pending]    [pending]    [pending]
              blockedBy:[1]  blockedBy:[1,2]


  Task DAG:

    #1 Setup DB schema
       |
       +--blocks--> #2 Write migrations
       |                 |
       +--blocks-------> #3 Add API endpoints
                              blockedBy: [1, 2]

  Complete #1 --> auto-clears #1 from #2.blockedBy and #3.blockedBy
  Complete #2 --> auto-clears #2 from #3.blockedBy
  Now #3.blockedBy is [] --> #3 is unblocked
```

## How It Works

### TaskManager: CRUD with JSON Persistence

The `TaskManager` inner class manages the full lifecycle. Each task is a standalone JSON file:

```java
static class TaskManager {
    private final Path dir;
    private int nextId;

    public TaskManager(Path tasksDir) {
        this.dir = tasksDir;
        Files.createDirectories(dir);
        this.nextId = maxId() + 1;
    }

    public String create(String subject, String description) {
        Map<String, Object> task = new HashMap<>();
        task.put("id", nextId);
        task.put("subject", subject);
        task.put("description", description != null ? description : "");
        task.put("status", "pending");
        task.put("blockedBy", new ArrayList<>());
        task.put("blocks", new ArrayList<>());
        task.put("owner", "");

        save(task);
        nextId++;
        return mapToJson(task);
    }
}
```

A task file (`task_1.json`) looks like:

```json
{
  "id": 1,
  "subject": "Setup DB schema",
  "description": "Create tables for users, orders",
  "status": "pending",
  "blockedBy": [],
  "blocks": [2, 3],
  "owner": ""
}
```

### Dependency Resolution on Completion

The key mechanism: when a task is marked `completed`, the `TaskManager` walks **all** other task files and removes the completed task ID from their `blockedBy` lists. This automatically unblocks downstream tasks.

```java
public String update(int taskId, String status,
                     List<Integer> addBlockedBy, List<Integer> addBlocks) {
    Map<String, Object> task = load(taskId);

    if (status != null) {
        task.put("status", status);

        // When completed, remove this ID from all other tasks' blockedBy
        if (status.equals("completed")) {
            clearDependency(taskId);
        }
    }

    // Bidirectional: adding blocks also updates the blocked tasks' blockedBy
    if (addBlocks != null) {
        for (Integer blockedId : addBlocks) {
            Map<String, Object> blocked = load(blockedId);
            List<Integer> blockedBy = (List<Integer>) blocked.get("blockedBy");
            if (!blockedBy.contains(taskId)) {
                blockedBy.add(taskId);
                save(blocked);
            }
        }
    }

    save(task);
    return mapToJson(task);
}

private void clearDependency(int completedId) {
    Files.list(dir)
        .filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
        .forEach(p -> {
            Map<String, Object> t = parseJsonToMap(Files.readString(p));
            List<Integer> blockedBy = (List<Integer>) t.get("blockedBy");
            if (blockedBy.contains(completedId)) {
                blockedBy.remove(Integer.valueOf(completedId));
                save(t);
            }
        });
}
```

### Tool Definitions

Four task tools are registered with the LLM:

```java
tools.add(buildTool("task_create",
    "Create a new task.",
    Map.of("subject", Map.of("type", "string"),
           "description", Map.of("type", "string")),
    List.of("subject")));

tools.add(buildTool("task_update",
    "Update a task's status or dependencies.",
    Map.of("task_id", Map.of("type", "integer"),
           "status", Map.of("type", "string", "enum",
               List.of("pending", "in_progress", "completed")),
           "addBlockedBy", Map.of("type", "array",
               "items", Map.of("type", "integer")),
           "addBlocks", Map.of("type", "array",
               "items", Map.of("type", "integer"))),
    List.of("task_id")));

tools.add(buildTool("task_list",
    "List all tasks with status summary.",
    Map.of(), List.of()));

tools.add(buildTool("task_get",
    "Get full details of a task by ID.",
    Map.of("task_id", Map.of("type", "integer")),
    List.of("task_id")));
```

### List Output

The `listAll()` method renders a compact status board:

```
[ ] #1: Setup DB schema
[ ] #2: Write migrations (blocked by: [1])
[ ] #3: Add API endpoints (blocked by: [1, 2])
[>] #4: Write unit tests
[x] #5: Setup CI pipeline
```

Status markers: `[ ]` pending, `[>]` in_progress, `[x]` completed.

---

## What Changed (from Lesson 6)

| Aspect | Lesson 6 (Context Compression) | Lesson 7 (Task System) |
|--------|-------------------------------|----------------------|
| State storage | In conversation memory | JSON files on disk |
| Survives compression | No | Yes |
| Dependency tracking | None | DAG with blockedBy/blocks |
| Multi-step planning | Manual in-context | Structured task graph |
| New tools | `compress` | `task_create`, `task_update`, `task_list`, `task_get` |
| New inner class | -- | `TaskManager` |

---

## Try It

1. Run `Lesson7RunSimple` with the prompt: `"Plan and implement a REST API in the trysamples directory with 3 endpoints. Create tasks for each step."`
2. Observe `.tasks/` directory populating with JSON files.
3. Check that completing a prerequisite task auto-clears `blockedBy` in downstream tasks.
4. Trigger context compression (Lesson 6) and verify the agent can still read its task board from disk.

---

**Source**: [`Lesson7RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson7RunSimple.java)
