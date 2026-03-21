# Lesson 4: Subagents

`L00 > L01 > L02 > L03 > [ L04 ] L05 > L06 | L07 > L08 > L09 > L10 > L11 > L12 > L13`

> *"Process isolation gives context isolation for free."* -- a child agent gets a fresh message list, does its work, and returns only a summary.

## Problem

As tasks grow more complex, the parent agent's context window fills up with intermediate results from exploration. If the agent reads 50 files to understand a codebase, all 50 file contents sit in the message history, consuming tokens and distracting the model from the actual task.

The solution: delegate exploration to a **subagent** with its own empty message list. The subagent does the work, then returns a short summary. The parent never sees the raw data -- only the conclusions.

Three design decisions:

1. **Fresh messages** -- the child starts with `messages = []`, not a copy of the parent's history.
2. **Filtered tools** -- the child gets base tools (`bash`, `read_file`, `write_file`, `edit_file`) but NOT the `task` tool. This prevents infinite recursive spawning.
3. **Summary-only return** -- only the child's final text response returns to the parent. All intermediate tool calls and results are discarded.

## Solution

```
+---------+     +-------+     +-----------+
| Parent  | --> |  LLM  | --> | task tool |
| agent   |     |       |     +-----+-----+
+---------+     +---^---+           |
                    |               |  spawn
                    |         +-----v-----------+
                    |         |   Subagent      |
                    |         |   messages=[]   |
                    |         |   tools=[base]  |
                    |         |   (own loop)    |
                    |         +-----+-----------+
                    |               |
                    |          summary only
                    |               |
                    +---------------+
                  parent continues with
                  compact summary, not
                  raw exploration data

          Filesystem is shared. Context is not.
```

## How It Works

1. The parent has a `task` tool that the child does not.

```java
private List<ChatCompletionTool> createParentTools() {
    List<ChatCompletionTool> tools = createChildTools();  // base tools

    // Add task tool for subagent spawning
    tools.add(ChatCompletionTool.builder()
            .function(FunctionDefinition.builder()
                    .name("task")
                    .description("Spawn a subagent with fresh context. "
                            + "It shares the filesystem but not conversation history.")
                    .parameters(FunctionParameters.builder()
                            .putAdditionalProperty("type", JsonValue.from("object"))
                            .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                    "prompt", Map.of("type", "string"),
                                    "description", Map.of("type", "string",
                                            "description", "Short description of the task")
                            )))
                            .putAdditionalProperty("required", JsonValue.from(List.of("prompt")))
                            .build())
                    .build())
            .build());

    return tools;
}
```

The child tools are a strict subset:

```java
private List<ChatCompletionTool> createChildTools() {
    List<ChatCompletionTool> tools = new ArrayList<>();
    tools.add(createBashTool());
    tools.add(createReadFileTool());
    tools.add(createWriteFileTool());
    tools.add(createEditFileTool());
    return tools;  // No task tool -- cannot spawn grandchildren
}
```

This asymmetry is intentional. If the child could spawn its own subagents, you would get uncontrolled recursion. The parent delegates; the child executes.

2. The subagent runs its own agent loop with fresh context.

```java
private String runSubagent(String prompt) {
    log.info("Starting subagent with prompt: {}",
            prompt.substring(0, Math.min(80, prompt.length())));

    // Fresh messages -- no parent history
    List<ChatCompletionMessageParam> subMessages = new ArrayList<>();
    subMessages.add(ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder().content(prompt).build()));

    List<ChatCompletionTool> childTools = createChildTools();
    Map<String, ToolHandler> childHandlers = createChildHandlers();
    String subagentSystem = "You are a coding subagent at " + workDir
            + ". Complete the given task, then summarize your findings.";

    ChatCompletionMessage lastResponse = null;

    for (int i = 0; i < 30; i++) {  // safety limit
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(ChatModel.of(modelName))
                .messages(subMessages)
                .tools(childTools)
                .addSystemMessage(subagentSystem)
                .build();

        ChatCompletion completion = client.chat().completions().create(params);
        ChatCompletion.Choice choice = completion.choices().get(0);
        ChatCompletionMessage assistantMessage = choice.message();
        lastResponse = assistantMessage;

        subMessages.add(ChatCompletionMessageParam.ofAssistant(assistantMessage.toParam()));

        if (choice.finishReason() != ChatCompletion.Choice.FinishReason.TOOL_CALLS) {
            break;  // Child is done
        }

        // Execute child tool calls...
    }

    // Only the final text returns to the parent
    if (lastResponse != null && lastResponse.content().isPresent()) {
        return lastResponse.content().get();
    }
    return "(no summary)";
}
```

Three things to notice:

- **`subMessages` starts empty** -- the child knows nothing about the parent's conversation.
- **Safety limit of 30 iterations** -- prevents runaway children.
- **Only `lastResponse.content()` returns** -- all intermediate tool calls, file reads, bash outputs are discarded. The parent gets a compact summary.

3. The parent handler wires it all together.

```java
private Map<String, ToolHandler> createParentHandlers() {
    Map<String, ToolHandler> handlers = createChildHandlers();
    handlers.put("task", args -> {
        String prompt = (String) args.get("prompt");
        String desc = args.get("description") != null
                ? (String) args.get("description") : "subtask";
        log.info("> task ({}): {}",
                desc, prompt.substring(0, Math.min(80, prompt.length())));
        return runSubagent(prompt);
    });
    return handlers;
}
```

From the parent's perspective, `task` is just another tool. It sends a prompt, gets a string back. The fact that an entire agent loop ran inside that string is invisible.

4. The parent's system prompt encourages delegation.

```java
String sysPrompt = "You are a coding agent at " + workDir
        + ". Use the task tool to delegate exploration or subtasks.";
```

Without this hint, the model tends to do everything itself. With it, the model learns to delegate exploration (reading many files) and keep its own context clean for decision-making.

## What Changed

| Component     | Lesson 3                | Lesson 4                          |
|---------------|-------------------------|-----------------------------------|
| Tools         | 5 (base + todo)         | Parent: 5 (base + task), Child: 4 (base only) |
| Context       | Single shared list      | Parent and child have separate message lists |
| Information flow | Everything in one context | Summary-only return from child |
| Recursion     | (none)                  | Prevented (child has no `task` tool) |
| Token usage   | Grows with exploration  | Child tokens are discarded        |
| Loop          | Same                    | Two loops (parent + child)        |

## Try It

```sh
cd ai-agent-learning
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson4 --prompt='Explore this project and summarize the architecture. Use the task tool to delegate file exploration.'"
```

**Source**: [`Lesson4RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson4RunSimple.java)
