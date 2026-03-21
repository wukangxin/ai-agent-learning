# Lesson 6: Context Compact (上下文压缩)

`L00 > L01 > L02 > L03 > L04 > L05 > [ L06 ] | L07 > L08 > L09 > L10 > L11 > L12 > L13`

> *"智能体能有策略地遗忘, 然后永远工作下去。"* -- 上下文窗口不是天花板, 是可以滑动的窗户。

## 问题

每个 LLM 都有上下文窗口限制 (128K tokens 等)。长任务中, 消息会不断累积:
- 工具结果 (文件内容、命令输出) 占大量 token
- 旧的中间结果已经没用了, 但还在占空间
- 最终触及窗口限制, API 调用失败

没有压缩机制的智能体有**寿命上限**。

## 解决方案

```
+---------------------------------------------------------------+
|                    三层压缩管线                                 |
+---------------------------------------------------------------+
|                                                                |
|  Layer 1: micro_compact (每轮静默)                              |
|  +---------------------------------------------------------+  |
|  | 旧工具结果 -> "[已处理: read_file pom.xml, 2.3KB]"       |  |
|  | 保留最近 N 条结果完整                                     |  |
|  +---------------------------------------------------------+  |
|                          |                                     |
|  Layer 2: auto_compact (超阈值自动触发)                         |
|  +---------------------------------------------------------+  |
|  | 1. 保存完整 transcript 到磁盘                             |  |
|  | 2. 用 LLM 总结对话                                       |  |
|  | 3. 替换所有消息为: [摘要] + "Understood. Continuing."     |  |
|  +---------------------------------------------------------+  |
|                          |                                     |
|  Layer 3: compact 工具 (模型主动调用)                           |
|  +---------------------------------------------------------+  |
|  | 模型觉得上下文太杂, 主动触发压缩                          |  |
|  | 效果同 auto_compact                                      |  |
|  +---------------------------------------------------------+  |
|                                                                |
+---------------------------------------------------------------+
```

## 工作原理

### 1. Token 估算

```java
private static final int THRESHOLD = 50000;  // 自动压缩阈值

// 粗略估算: ~4 个字符约等于 1 个 token
private int estimateTokens(List<ChatCompletionMessageParam> messages) {
    return messages.toString().length() / 4;
}
```

为什么用粗略估算而不是精确的 tokenizer? 因为:
- 精确 tokenizer 需要额外依赖
- 差 2x 也没关系 -- 压缩是"防爆"机制, 早一点晚一点触发都可以
- 速度快, 每轮都能调用

### 2. Layer 1: micro_compact (静默压缩)

```java
private void microCompact(List<ChatCompletionMessageParam> messages) {
    // 保留最近 KEEP_RECENT 条工具结果完整
    // 将更早的工具结果替换为占位符
    //
    // 原始: "package ai.agent.learning...\n public class Lesson0..."  (2000 chars)
    // 压缩后: "[已处理: read_file Lesson0RunSimple.java, 2.0KB]"     (50 chars)
}
```

micro_compact 的特点:
- **每轮都执行**, 在 LLM 调用之前
- **静默的**: 不产生新消息, 只修改已有消息
- **保留近期**: 最近的工具结果保持完整, 模型可能还需要引用

### 3. Layer 2: auto_compact (自动压缩)

```java
private List<ChatCompletionMessageParam> autoCompact(
        List<ChatCompletionMessageParam> messages) {

    // Step 1: 保存完整 transcript 到磁盘 (可恢复)
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

    // Step 2: 用 LLM 总结对话
    String conversationText = messages.toString();
    if (conversationText.length() > 80000) {
        conversationText = conversationText.substring(0, 80000);
    }

    ChatCompletionCreateParams summaryParams = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(modelName))
            .addUserMessage(
                "Summarize this conversation for continuity. Include:\n" +
                "1) What was accomplished\n" +
                "2) Current state\n" +
                "3) Key decisions made\n" +
                "Be concise but preserve critical details.\n\n" +
                conversationText)
            .build();

    ChatCompletion summaryCompletion = client.chat().completions()
            .create(summaryParams);
    String summary = summaryCompletion.choices().get(0)
            .message().content().orElse("(no summary)");

    // Step 3: 替换所有消息
    List<ChatCompletionMessageParam> compressed = new ArrayList<>();
    compressed.add(ChatCompletionMessageParam.ofUser(
        ChatCompletionUserMessageParam.builder().content(
            "[Conversation compressed. Transcript: " +
            transcriptPath.getFileName() + "]\n\n" + summary).build()));
    compressed.add(ChatCompletionMessageParam.ofAssistant(
        ChatCompletionAssistantMessageParam.builder().content(
            "Understood. I have the context from the summary. Continuing."
        ).build()));

    return compressed;
}
```

auto_compact 的关键:
- **先持久化**: 完整对话保存到 `.transcripts/` 目录, 永远可以回看
- **LLM 总结**: 用模型自身来决定哪些信息重要, 比规则更灵活
- **极致压缩**: 整个对话变成 2 条消息 (摘要 + 确认)

### 4. Layer 3: compact 工具 (手动触发)

```java
// 工具定义
tools.add(ChatCompletionTool.builder()
        .function(FunctionDefinition.builder()
                .name("compact")
                .description("Trigger manual conversation compression.")
                .parameters(FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                "focus", Map.of("type", "string",
                                        "description",
                                        "What to preserve in the summary"))))
                        .build())
                .build())
        .build());

// 处理函数
handlers.put("compact", args -> "Manual compression requested.");
```

```java
// 在循环中检测 compact 调用
if (manualCompact) {
    log.info("[manual compact]");
    List<ChatCompletionMessageParam> compressed = autoCompact(messages);
    messages.clear();
    messages.addAll(compressed);
}
```

手动 compact 的场景: 模型发现自己在做一个很不同的子任务, 之前的上下文反而是干扰, 主动清理。

### 5. 循环中的集成

```java
private void agentLoop(List<ChatCompletionMessageParam> messages,
                       List<ChatCompletionTool> tools,
                       Map<String, ToolHandler> handlers) {
    while (true) {
        // Layer 1: 每轮静默压缩
        microCompact(messages);

        // Layer 2: 超阈值自动压缩
        if (estimateTokens(messages) > THRESHOLD) {
            log.info("[auto_compact triggered]");
            List<ChatCompletionMessageParam> compressed = autoCompact(messages);
            messages.clear();
            messages.addAll(compressed);
        }

        // 正常的 LLM 调用...
        ChatCompletion completion = client.chat().completions()
                .create(paramsBuilder.build());

        // ... 工具执行 ...

        // Layer 3: 手动 compact
        if (manualCompact) {
            List<ChatCompletionMessageParam> compressed = autoCompact(messages);
            messages.clear();
            messages.addAll(compressed);
        }
    }
}
```

### Transcript 持久化

```
.transcripts/
  transcript_1711234567.jsonl    <-- 第一次压缩前的完整对话
  transcript_1711234890.jsonl    <-- 第二次压缩前的完整对话
  ...
```

每次压缩都会保存完整的对话记录。这意味着:
- **零信息丢失**: 压缩后的摘要可能遗漏细节, 但完整记录永远在磁盘上
- **可审计**: 可以回溯智能体的完整推理过程
- **可恢复**: 如果需要, 可以从 transcript 重建对话

## 变更内容

| 组件          | 之前 (L05)         | 之后 (L06)                        |
|---------------|--------------------|------------------------------------|
| 上下文管理    | 无限增长           | 三层压缩管线                       |
| micro_compact | (无)               | 每轮静默替换旧工具结果             |
| auto_compact  | (无)               | 超阈值自动: 持久化 + 总结 + 替换  |
| 手动 compact  | (无)               | `compact` 工具, 模型主动调用      |
| 持久化        | (无)               | `.transcripts/` JSONL 文件        |
| 智能体寿命    | 受上下文窗口限制   | 理论上无限                         |

## 试一试

```sh
cd ai-agent-learning
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson6 --prompt='逐个分析项目中的所有 Java 文件, 为每个文件写详细的代码审查报告'"
```

这个任务会产生大量上下文 (多个文件的内容 + 多个报告)。观察:
1. micro_compact 如何静默替换旧的文件内容
2. auto_compact 何时触发, 日志中会显示 `[auto_compact triggered]`
3. transcript 文件是否被保存到 `.transcripts/` 目录

**源码**: [`Lesson6RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson6RunSimple.java)
