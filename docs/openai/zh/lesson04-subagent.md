# Lesson 4: Subagents (子智能体)

`L00 > L01 > L02 > L03 > [ L04 ] L05 > L06 | L07 > L08 > L09 > L10 > L11 > L12 > L13`

> *"进程隔离 = 上下文隔离。免费的。"* -- 子智能体的精髓是独立的 messages 列表。

## 问题

随着任务变复杂, 单个智能体的上下文窗口会被塞满。探索一个大型代码库可能需要读几十个文件, 但这些细节对最终答案毫无用处 -- 它们只是中间过程。问题不只是 token 数量, 更是**注意力稀释**: 上下文越长, 模型越容易忽略关键信息。

## 解决方案

```
+--------+      +-------------------+
|  User  | ---> |  Parent Agent     |
| prompt |      |  tools: bash,     |
+--------+      |  read, write,     |
                |  edit, task       |  <-- task 工具触发子智能体
                +--------+----------+
                         |
              task("分析 src/ 目录结构")
                         |
                         v
                +--------+----------+
                |  Child Agent      |
                |  messages = []    |  <-- 全新的消息列表!
                |  tools: bash,     |
                |  read, write,     |
                |  edit             |  <-- 没有 task 工具 (防止递归)
                +--------+----------+
                         |
                    返回纯文本摘要
                         |
                         v
                +--------+----------+
                |  Parent 继续      |
                |  (只看到摘要)      |
                +-------------------+
```

三个关键设计决策:
1. **独立 `messages = []`**: 子智能体有全新的消息列表, 不污染父级上下文
2. **工具分离**: 子智能体没有 `task` 工具, 防止无限递归
3. **摘要返回**: 只有最终文本回到父级, 中间过程全部丢弃

## 工作原理

### 1. 父/子工具分离

```java
// 子工具: 基础工具, 没有 task
private List<ChatCompletionTool> createChildTools() {
    List<ChatCompletionTool> tools = new ArrayList<>();
    tools.add(createBashTool());
    tools.add(createReadFileTool());
    tools.add(createWriteFileTool());
    tools.add(createEditFileTool());
    return tools;
}

// 父工具: 基础工具 + task 调度器
private List<ChatCompletionTool> createParentTools() {
    List<ChatCompletionTool> tools = createChildTools();  // 继承子工具

    // 加上 task 工具
    tools.add(ChatCompletionTool.builder()
            .function(FunctionDefinition.builder()
                    .name("task")
                    .description("Spawn a subagent with fresh context. " +
                                 "It shares the filesystem but not conversation history.")
                    .parameters(FunctionParameters.builder()
                            .putAdditionalProperty("type", JsonValue.from("object"))
                            .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                    "prompt", Map.of("type", "string"),
                                    "description", Map.of("type", "string",
                                            "description", "Short description of the task"))))
                            .putAdditionalProperty("required", JsonValue.from(List.of("prompt")))
                            .build())
                    .build())
            .build());

    return tools;
}
```

### 2. 子智能体实现

```java
private String runSubagent(String prompt) {
    log.info("Starting subagent with prompt: {}",
             prompt.substring(0, Math.min(80, prompt.length())));

    // 关键: 全新的消息列表
    List<ChatCompletionMessageParam> subMessages = new ArrayList<>();
    subMessages.add(ChatCompletionMessageParam.ofUser(
        ChatCompletionUserMessageParam.builder().content(prompt).build()));

    List<ChatCompletionTool> childTools = createChildTools();
    Map<String, ToolHandler> childHandlers = createChildHandlers();
    String subagentSystem = "You are a coding subagent at " + workDir +
                            ". Complete the given task, then summarize your findings.";

    ChatCompletionMessage lastResponse = null;

    // 子智能体自己的循环 (有安全上限)
    for (int i = 0; i < 30; i++) {
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

        subMessages.add(ChatCompletionMessageParam.ofAssistant(
            assistantMessage.toParam()));

        if (choice.finishReason() != ChatCompletion.Choice.FinishReason.TOOL_CALLS) {
            break;  // 子智能体完成
        }

        // 执行子智能体的工具调用
        if (assistantMessage.toolCalls().isPresent()) {
            for (ChatCompletionMessageToolCall toolCall : assistantMessage.toolCalls().get()) {
                String output = executeTool(childHandlers,
                    toolCall.function().name(), toolCall.function().arguments());

                subMessages.add(ChatCompletionMessageParam.ofTool(
                    ChatCompletionToolMessageParam.builder()
                        .toolCallId(toolCall.id())
                        .content(truncate(output, 50000))
                        .build()));
            }
        }
    }

    // 只返回最终文本 -- 子智能体的 subMessages 被丢弃
    if (lastResponse != null && lastResponse.content().isPresent()) {
        return lastResponse.content().get();
    }
    return "(no summary)";
}
```

### 3. 父级调度

```java
private Map<String, ToolHandler> createParentHandlers() {
    Map<String, ToolHandler> handlers = createChildHandlers();
    handlers.put("task", args -> {
        String prompt = (String) args.get("prompt");
        String desc = args.get("description") != null
            ? (String) args.get("description") : "subtask";
        log.info("> task ({}): {}", desc,
                 prompt.substring(0, Math.min(80, prompt.length())));
        return runSubagent(prompt);  // 调用子智能体
    });
    return handlers;
}
```

### 上下文隔离的效果

```
父智能体上下文:
  user: "重构整个项目的错误处理"
  assistant: [调用 task: "分析 src/ 目录的错误处理模式"]
  tool_result: "项目使用 try-catch 模式, 主要在 3 个位置..."  <-- 只有摘要!
  assistant: [调用 task: "修改 ServiceA.java 的错误处理"]
  tool_result: "已将 ServiceA 的 catch 块改为统一异常处理..."
  assistant: "重构完成。修改了以下文件..."

子智能体上下文 (第一个): 读了 15 个文件, 执行了 20 次工具调用 -- 全部丢弃
子智能体上下文 (第二个): 读了 3 个文件, 编辑了 1 个 -- 全部丢弃
```

父级只看到精炼的摘要, 上下文保持干净。

## 变更内容

| 组件          | 之前 (L03)         | 之后 (L04)                        |
|---------------|--------------------|------------------------------------|
| 架构          | 单智能体           | 父/子智能体                       |
| 上下文        | 共享               | 隔离 (独立 messages)              |
| 工具集        | 统一               | 父/子分离 (子无 task)             |
| 返回值        | (不适用)           | 仅文本摘要                         |
| 安全限制      | (无)               | 30 轮循环上限 + 无递归            |

## 试一试

```sh
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson4 --prompt='分析项目中每个 Lesson 文件的功能, 在 trysamples 目录下为每个写一句话总结'"
```

观察日志中带 `[subagent]` 前缀的行 -- 这是子智能体在独立工作。父智能体只看到最终摘要。

**源码**: [`Lesson4RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson4RunSimple.java)
