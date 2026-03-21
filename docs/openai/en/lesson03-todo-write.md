# Lesson 3: TodoWrite

`L00 > L01 > L02 > [ L03 ] L04 > L05 > L06 | L07 > L08 > L09 > L10 > L11 > L12 > L13`

> *"The agent can track its own progress -- and I can see it."* -- structured state the model writes to, the human reads from.

## Problem

When an agent tackles a multi-step task, you cannot tell what it is doing. Is it stuck? Did it skip a step? Is it almost done? Without explicit progress tracking, the agent is a black box.

The model needs a way to write structured state that:

1. **You can observe** -- see what is done, what is in progress, what is pending.
2. **The model maintains** -- it updates the list as it works.
3. **Enforces discipline** -- only one task can be "in progress" at a time (forces sequential focus).
4. **Nags when forgotten** -- if the model forgets to update, a reminder is injected.

## Solution

```
+--------+      +-------+      +-------------+
|  User  | ---> |  LLM  | ---> | tool_calls? |
| prompt |      |       |      +------+------+
+--------+      +---^---+             |
                    |           yes    |    no
                    |          +------+------+
                    |          |             |
                    |    +-----v-----------+ |
                    |    | TOOL_HANDLERS   | |
                    |    | + todo tool     | |
                    |    +-----+-----------+ |
                    |          |             |
                    |    +-----v-----------+ |
                    |    | TodoManager     | |
                    |    | (validates &    | |
                    |    |  renders state) | |
                    |    +-----+-----------+ |
                    |          |             |
                    |    +-----v-----------+ |
                    |    | nag reminder?   | |
                    |    | (inject if 3+   | |
                    |    |  rounds w/o     | |
                    |    |  todo update)   | |
                    |    +-----------------+ |
                    |          |             |
                    +----------+        +----v----+
                   tool_result          |  done   |
                                        +---------+
```

## How It Works

1. The `TodoManager` is a server-side data structure that validates and renders todo state.

```java
static class TodoManager {
    private List<TodoItem> items = new ArrayList<>();

    public String update(List<Map<String, Object>> newItems) {
        if (newItems.size() > 20) {
            throw new IllegalArgumentException("Max 20 todos allowed");
        }

        List<TodoItem> validated = new ArrayList<>();
        int inProgressCount = 0;

        for (int i = 0; i < newItems.size(); i++) {
            Map<String, Object> item = newItems.get(i);
            String text = item.get("text").toString().trim();
            String status = item.get("status").toString().toLowerCase();
            String id = item.get("id") != null ? item.get("id").toString() : String.valueOf(i + 1);

            if (!status.equals("pending") && !status.equals("in_progress")
                    && !status.equals("completed")) {
                throw new IllegalArgumentException("Item " + id + ": invalid status '" + status + "'");
            }
            if (status.equals("in_progress")) {
                inProgressCount++;
            }

            validated.add(new TodoItem(id, text, status));
        }

        if (inProgressCount > 1) {
            throw new IllegalArgumentException("Only one task can be in_progress at a time");
        }

        items = validated;
        return render();
    }
}
```

Key constraints enforced by the server, not the model:

- **Max 20 items** -- prevents unbounded growth.
- **Three valid statuses** -- `pending`, `in_progress`, `completed`.
- **One `in_progress` at a time** -- forces the model to finish one thing before starting another. This is the most important constraint. Without it, models tend to start everything and finish nothing.

2. The render method produces a human-readable checklist.

```java
public String render() {
    StringBuilder sb = new StringBuilder();
    for (TodoItem item : items) {
        String marker = switch (item.status) {
            case "pending"     -> "[ ]";
            case "in_progress" -> "[>]";
            case "completed"   -> "[x]";
            default            -> "[?]";
        };
        sb.append(marker).append(" #").append(item.id)
          .append(": ").append(item.text).append("\n");
    }
    long done = items.stream().filter(t -> t.status.equals("completed")).count();
    sb.append("\n(").append(done).append("/").append(items.size()).append(" completed)");
    return sb.toString();
}
```

Output looks like:

```
[x] #1: Read the project structure
[>] #2: Analyze pom.xml dependencies
[ ] #3: Write summary report

(1/3 completed)
```

This output goes back to the model as the tool result, so the model sees its own progress on every update.

3. The `todo` tool is defined with a structured schema.

```java
tools.add(ChatCompletionTool.builder()
        .function(FunctionDefinition.builder()
                .name("todo")
                .description("Update task list. Track progress on multi-step tasks.")
                .parameters(FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                "items", Map.of(
                                        "type", "array",
                                        "items", Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        "id", Map.of("type", "string"),
                                                        "text", Map.of("type", "string"),
                                                        "status", Map.of("type", "string",
                                                                "enum", List.of("pending", "in_progress", "completed"))
                                                ),
                                                "required", List.of("id", "text", "status")
                                        )
                                )
                        )))
                        .putAdditionalProperty("required", JsonValue.from(List.of("items")))
                        .build())
                .build())
        .build());
```

The schema uses `enum` for status values -- the model sees the valid options and rarely sends an invalid one. The entire items array is replaced on each call (full-state replacement, not delta updates). This is simpler and less error-prone.

4. Nag reminder injection: if the model goes 3+ rounds without calling the `todo` tool, a reminder is injected.

```java
int roundsSinceTodo = 0;

// Inside the loop, after processing tool calls:
roundsSinceTodo = usedTodo ? 0 : roundsSinceTodo + 1;
if (roundsSinceTodo >= 3) {
    // Inject reminder into the next tool result
    results.add(0, Map.of(
            "type", "text",
            "text", "<reminder>Update your todos.</reminder>"
    ));
}
```

This is a **system-level nudge** that works because the model reads tool results. The `<reminder>` tag signals that this is metadata, not user content. Models reliably respond by calling the `todo` tool.

5. The handler connects the tool to the TodoManager.

```java
handlers.put("todo", args -> {
    Object itemsObj = args.get("items");
    if (itemsObj instanceof List) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
        return todoManager.update(items);
    }
    return "Error: items must be a list";
});
```

## What Changed

| Component     | Lesson 2                | Lesson 3                          |
|---------------|-------------------------|-----------------------------------|
| Tools         | 4 (bash, read, write, edit) | 5 (+ `todo`)                  |
| State         | (none)                  | `TodoManager` with validation     |
| Observability | Log output only         | Structured checklist              |
| Constraints   | Path sandboxing         | + one `in_progress` at a time     |
| Nudging       | (none)                  | Nag reminder after 3 idle rounds  |
| Loop          | Same                    | Same (+ nag injection point)      |

## Try It

```sh
cd ai-agent-learning
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson3 --prompt='Analyze this project structure. Create todos for each step.'"
```

**Source**: [`Lesson3RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson3RunSimple.java)
