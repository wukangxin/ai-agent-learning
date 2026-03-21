# Lesson 1: The Agent Loop

`L00 > [ L01 ] L02 > L03 > L04 > L05 > L06 | L07 > L08 > L09 > L10 > L11 > L12 > L13`

> *"One loop & tool_call is all you need"* -- the core mechanism that turns a chat completion into an agent.

## Problem

An LLM alone cannot touch the real world. It can only produce text. To read files, run commands, or change anything, you need a loop that:

1. Sends the conversation to the model.
2. Checks if the model wants to call a tool.
3. Executes the tool and feeds the result back.
4. Repeats until the model is done (returns text, not a tool call).

Without this loop, the model is a single-shot oracle. With it, the model becomes an agent.

## Solution

```
+--------+      +-------+      +-------------+
|  User  | ---> |  LLM  | ---> | tool_calls? |
| prompt |      |       |      +------+------+
+--------+      +---^---+             |
                    |           yes    |    no
                    |          +------+------+
                    |          |             |
                    |    +-----v-----+  +----v----+
                    |    | execute   |  |  done   |
                    |    | tool      |  |  (text) |
                    |    +-----+-----+  +---------+
                    |          |
                    +----------+
                   tool_result added
                    to messages
```

## How It Works

1. Define a tool with a JSON schema so the model knows what it can call.

```java
ChatCompletionTool bashTool = ChatCompletionTool.builder()
        .function(FunctionDefinition.builder()
                .name("bash")
                .description("Run a shell command.")
                .parameters(FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                "command", Map.of("type", "string")
                        )))
                        .putAdditionalProperty("required", JsonValue.from(List.of("command")))
                        .build())
                .build())
        .build();
```

The `FunctionParameters` builder uses `putAdditionalProperty` to construct the JSON Schema that the model reads to understand the tool's interface.

2. Start the agent loop. Send messages + tools to the model, then check `finishReason`.

```java
List<ChatCompletionMessageParam> messages = new ArrayList<>();
messages.add(ChatCompletionMessageParam.ofUser(
        ChatCompletionUserMessageParam.builder().content(userPrompt).build()));

while (true) {
    ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(modelName))
            .messages(messages)
            .addTool(bashTool)
            .build();

    ChatCompletion completion = client.chat().completions().create(params);
    ChatCompletion.Choice choice = completion.choices().get(0);
    ChatCompletionMessage assistantMessage = choice.message();

    // Always add the assistant's response to message history
    messages.add(ChatCompletionMessageParam.ofAssistant(assistantMessage.toParam()));

    // Exit condition: model returned text, not a tool call
    if (choice.finishReason() != ChatCompletion.Choice.FinishReason.TOOL_CALLS) {
        assistantMessage.content().ifPresent(content -> log.info("Assistant: {}", content));
        break;
    }

    // ... handle tool calls (next step)
}
```

The key insight: **`finishReason` is the only branching point.** If it is `TOOL_CALLS`, loop. If it is anything else (`STOP`, `LENGTH`, etc.), break.

3. Execute the tool call and feed the result back as a tool message.

```java
if (assistantMessage.toolCalls().isPresent()) {
    for (ChatCompletionMessageToolCall toolCall : assistantMessage.toolCalls().get()) {
        ChatCompletionMessageToolCall.Function function = toolCall.function();
        if ("bash".equals(function.name())) {
            String command = extractCommand(function.arguments());
            String output = runBash(command);

            messages.add(ChatCompletionMessageParam.ofTool(
                    ChatCompletionToolMessageParam.builder()
                            .toolCallId(toolCall.id())
                            .content(output)
                            .build()));
        }
    }
}
```

Three critical details:

- **`toolCallId`**: Every tool result must reference the `id` from the tool call. The API rejects orphaned results.
- **`content`**: The tool output is plain text. The model reads it on the next loop iteration.
- **Message accumulation**: Every assistant message and every tool result gets appended to `messages`. The model sees the full conversation history each time.

4. Safety: block dangerous commands before execution.

```java
private String runBash(String command) {
    String[] dangerous = {"rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"};
    for (String d : dangerous) {
        if (command.contains(d)) {
            return "Error: Dangerous command blocked";
        }
    }
    ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
    // ... execute and return output
}
```

## What Changed

| Component     | Lesson 0           | Lesson 1                          |
|---------------|--------------------|-----------------------------------|
| LLM call      | Single shot        | Inside `while(true)` loop         |
| Tools         | (none)             | `bash` tool with JSON schema      |
| Finish reason | Ignored            | Checked every iteration           |
| Messages      | Static             | Accumulating list                 |
| Control flow  | Linear             | Loop until `finishReason != TOOL_CALLS` |

## Try It

```sh
cd ai-agent-learning
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson1 --prompt='List the files in the current directory and tell me what you see.'"
```

**Source**: [`Lesson1RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson1RunSimple.java)
