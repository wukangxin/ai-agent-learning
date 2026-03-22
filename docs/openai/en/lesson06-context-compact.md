# Lesson 6: Context Compact

`L00 > L01 > L02 > L03 > L04 > L05 > [ L06 ] L07 > L08 > L09 > L10 > L11 > L12 > L13`

> *"The agent can forget strategically and keep working forever."* -- three layers of compression ensure the conversation never hits the context window limit.

## Problem

Every agent loop iteration adds messages: user prompts, assistant responses, tool calls, tool results. A long session reading dozens of files can accumulate 100,000+ tokens. When the context window fills up, the API returns an error and the agent dies mid-task.

The problem is not just size -- it is relevance. Tool results from 30 steps ago are rarely useful. The model needs recent context to work, but old context to remember what it was doing. Compression must be:

1. **Automatic** -- the agent should not need to decide when to compress.
2. **Lossless for recent context** -- the last few tool results must remain intact.
3. **Persistent** -- the full transcript must be saved to disk before compression, so nothing is truly lost.

## Solution

```
Three layers, each with increasing impact:

Layer 1: micro_compact (every turn, silent)
+--------------------------------------------------+
| Before each LLM call:                            |
| - Tool results beyond the last 6 messages get    |
|   truncated to 200 chars + "... [truncated]"    |
| - Recent tool results stay intact                |
| - No LLM call needed                             |
+--------------------------------------------------+
        |
        v  still too big?
Layer 2: auto_compact (threshold-based)
+--------------------------------------------------+
| When estimateTokens(messages) > THRESHOLD:       |
| 1. Save full transcript to .transcripts/         |
| 2. Ask LLM to summarize the conversation         |
| 3. Replace all messages with:                    |
|    [summary] + "Understood. Continuing."         |
+--------------------------------------------------+
        |
        v  model can also trigger manually
Layer 3: compact tool (model-initiated)
+--------------------------------------------------+
| Model calls compact(focus="...") when it feels   |
| the context is getting noisy.                    |
| Same mechanism as auto_compact.                   |
+--------------------------------------------------+
```

## How It Works

1. Token estimation: a rough heuristic to avoid calling the tokenizer.

```java
private static final int THRESHOLD = 50000;  // ~200K chars / 4

private int estimateTokens(List<ChatCompletionMessageParam> messages) {
    return messages.toString().length() / 4;
}
```

This is intentionally imprecise. The ratio of ~4 characters per token is a rough average for English text and code. It is good enough for triggering compression -- we do not need exact counts.

2. Layer 1: `micro_compact` runs before every LLM call.

```java
private void microCompact(List<ChatCompletionMessageParam> messages) {
    // Truncate tool results older than the last 6 messages to 200 chars
    int cutoff = Math.max(0, messages.size() - 6);
    for (int i = 0; i < cutoff; i++) {
        // if message is a tool result with content > 200 chars, truncate it
    }
}
```

The idea: tool results from many steps ago are unlikely to matter. Truncate their content to 200 characters to save tokens. The message structure (role, tool_call_id) is preserved so the conversation remains valid. Only the `content` field is shortened.

This layer is **silent** -- the model never knows it happened. It runs every turn and prevents gradual bloat.

3. Layer 2: `auto_compact` triggers when the token estimate exceeds the threshold.

```java
private List<ChatCompletionMessageParam> autoCompact(
        List<ChatCompletionMessageParam> messages) {
    // Step 1: Save full transcript to disk (nothing is lost)
    Files.createDirectories(transcriptDir);
    Path transcriptPath = transcriptDir.resolve(
            "transcript_" + Instant.now().getEpochSecond() + ".jsonl");

    StringBuilder transcriptContent = new StringBuilder();
    for (ChatCompletionMessageParam msg : messages) {
        transcriptContent.append(msg.toString()).append("\n");
    }
    Files.write(transcriptPath,
            transcriptContent.toString().getBytes(StandardCharsets.UTF_8));
    log.info("[transcript saved: {}]", transcriptPath);

    // Step 2: Ask the LLM to summarize
    ChatCompletionCreateParams summaryParams = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(modelName))
            .addUserMessage("Summarize this conversation for continuity. Include:\n"
                    + "1) What was accomplished\n"
                    + "2) Current state\n"
                    + "3) Key decisions made\n"
                    + "Be concise but preserve critical details.\n\n"
                    + conversationText)
            .build();

    ChatCompletion summaryCompletion = client.chat().completions().create(summaryParams);
    String summary = summaryCompletion.choices().get(0).message().content()
            .orElse("(no summary)");

    // Step 3: Replace all messages with compressed summary
    List<ChatCompletionMessageParam> compressed = new ArrayList<>();
    compressed.add(ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder().content(
                    "[Conversation compressed. Transcript: "
                    + transcriptPath.getFileName() + "]\n\n" + summary).build()));
    compressed.add(ChatCompletionMessageParam.ofAssistant(
            ChatCompletionAssistantMessageParam.builder().content(
                    "Understood. I have the context from the summary. Continuing.")
                    .build()));

    return compressed;
}
```

Three critical steps:

- **Save first**: The full transcript goes to `.transcripts/transcript_<epoch>.jsonl` before any compression. This is the safety net -- if the summary loses important details, the raw data is on disk.
- **LLM summarization**: A separate LLM call (not the main agent loop) produces the summary. The prompt asks for what was accomplished, current state, and key decisions.
- **Two-message replacement**: The entire conversation is replaced with a user message (the summary) and an assistant acknowledgment. This resets the context to ~500 tokens.

4. The agent loop integrates all three layers.

```java
private void agentLoop(List<ChatCompletionMessageParam> messages,
                       List<ChatCompletionTool> tools,
                       Map<String, ToolHandler> handlers) {
    while (true) {
        // Layer 1: micro_compact before each LLM call
        microCompact(messages);

        // Layer 2: auto_compact if token estimate exceeds threshold
        if (estimateTokens(messages) > THRESHOLD) {
            log.info("[auto_compact triggered]");
            List<ChatCompletionMessageParam> compressed = autoCompact(messages);
            messages.clear();
            messages.addAll(compressed);
        }

        // Normal LLM call
        ChatCompletion completion = client.chat().completions().create(/* ... */);
        // ... process response, handle tool calls ...

        // Layer 3: manual compact triggered by the compact tool
        if (manualCompact) {
            log.info("[manual compact]");
            List<ChatCompletionMessageParam> compressed = autoCompact(messages);
            messages.clear();
            messages.addAll(compressed);
        }
    }
}
```

The three layers work together:

- **micro_compact** prevents gradual growth (every turn).
- **auto_compact** handles sudden growth (threshold-based).
- **compact tool** lets the model decide when context is getting noisy (model-initiated).

5. The `compact` tool lets the model trigger compression explicitly.

```java
tools.add(ChatCompletionTool.builder()
        .function(FunctionDefinition.builder()
                .name("compact")
                .description("Trigger manual conversation compression.")
                .parameters(FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                "focus", Map.of("type", "string",
                                        "description", "What to preserve in the summary")
                        )))
                        .build())
                .build())
        .build());

handlers.put("compact", args -> "Manual compression requested.");
```

When the model calls `compact(focus="preserve the test results")`, the handler returns a placeholder string, and a flag triggers the same `autoCompact` mechanism after the tool call batch completes.

6. Transcript persistence: nothing is truly lost.

```
.transcripts/
  transcript_1711234567.jsonl     <-- first compression
  transcript_1711235000.jsonl     <-- second compression
  transcript_1711235500.jsonl     <-- third compression
```

Each file contains the full message history at the time of compression, one message per line. If you need to debug what the agent did 3 compressions ago, the raw data is there.

## What Changed

| Component     | Lesson 5                | Lesson 6                          |
|---------------|-------------------------|-----------------------------------|
| Context growth | Unbounded              | Three-layer compression           |
| Token management | (none)               | `estimateTokens()` + threshold    |
| Old tool results | Kept forever          | micro_compact replaces with placeholder |
| Full transcript | In memory only        | Persisted to `.transcripts/`      |
| Compression   | (none)                  | LLM-generated summary            |
| Model control | (none)                  | `compact` tool for manual trigger |
| Session length | Limited by context window | Effectively unlimited           |
| Loop          | Same                    | Same (+ compression hooks)        |

## Try It

```sh
cd ai-agent-learning
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson6 --prompt='Read every Java file in this project and summarize what each one does. There are many files so manage your context carefully.'"
```

After the run, check the saved transcripts:

```sh
ls -la .transcripts/
```

**Source**: [`Lesson6RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson6RunSimple.java)
