# Lesson 1: The Agent Loop (智能体循环)

`L00 > [ L01 ] L02 > L03 > L04 > L05 > L06 | L07 > L08 > L09 > L10 > L11 > L12 > L13`

> *"一个 while 循环 + tool_call 检测 -- 这就是全部了。"* -- 智能体的本质比你想象的简单。

## 问题

语言模型能推理代码、规划步骤, 但它**碰不到真实世界**。它不能执行命令、读写文件、调用 API。Lesson 0 的单次请求/响应模式不够 -- 我们需要让模型**反复行动**直到任务完成。

## 解决方案

```
                  +----------+
                  |  用户    |
                  | prompt   |
                  +----+-----+
                       |
                       v
              +--------+--------+
              |                 |
              |   智能体循环    |<-----------+
              |   (while true)  |            |
              |                 |            |
              +--------+--------+            |
                       |                     |
                       v                     |
              +--------+--------+            |
              |   发送消息      |            |
              |   给 LLM       |            |
              +--------+--------+            |
                       |                     |
                       v                     |
              +--------+--------+    YES     |
              | finish_reason   +------------+
              | == TOOL_CALLS?  |  执行工具
              +--------+--------+  结果加入消息
                       | NO
                       v
              +--------+--------+
              |   返回最终      |
              |   文本响应      |
              +--------+--------+
```

核心洞察: 智能体 = `while` 循环 + `finish_reason` 检查。模型说 "我要调用工具", 你执行工具, 把结果喂回去; 模型说 "我说完了", 你退出循环。

## 工作原理

### 1. 定义工具 (JSON Schema)

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

工具定义用 JSON Schema 描述参数。模型不会执行工具 -- 它只是输出结构化的 JSON 调用请求。

### 2. 智能体循环

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

    // 把助手回复加入历史
    messages.add(ChatCompletionMessageParam.ofAssistant(assistantMessage.toParam()));

    // 检查 finish_reason -- 这是循环的退出条件
    if (choice.finishReason() != ChatCompletion.Choice.FinishReason.TOOL_CALLS) {
        assistantMessage.content().ifPresent(content -> log.info("Assistant: {}", content));
        break;  // 模型说完了, 退出
    }

    // 模型要求调用工具 -- 执行并反馈
    if (assistantMessage.toolCalls().isPresent()) {
        for (ChatCompletionMessageToolCall toolCall : assistantMessage.toolCalls().get()) {
            String command = extractCommand(toolCall.function().arguments());
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

关键点:
- **`messages` 列表是状态**: 每一轮的助手回复和工具结果都追加进去, 模型看到完整对话历史。
- **`finish_reason` 是退出信号**: `TOOL_CALLS` 表示模型还想继续, 其他值 (`STOP`) 表示结束。
- **`toolCallId` 必须匹配**: 工具结果通过 `toolCallId` 关联到对应的工具调用。

### 3. 工具执行 (沙箱)

```java
private String runBash(String command) {
    // 危险命令拦截
    String[] dangerous = {"rm -rf /", "sudo", "shutdown", "reboot"};
    for (String d : dangerous) {
        if (command.contains(d)) return "Error: Dangerous command blocked";
    }

    ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", command);
    pb.redirectErrorStream(true);
    Process process = pb.start();

    boolean finished = process.waitFor(120, TimeUnit.SECONDS);
    if (!finished) {
        process.destroyForcibly();
        return "Error: Timeout (120s)";
    }

    try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream()))) {
        return reader.lines().collect(Collectors.joining("\n")).trim();
    }
}
```

注意超时和危险命令过滤 -- 这是最基础的安全措施。生产环境需要更严格的沙箱。

## 变更内容

| 组件          | 之前 (L00)         | 之后 (L01)                        |
|---------------|--------------------|------------------------------------|
| 控制流        | 线性 (无循环)      | `while(true)` + `finish_reason`   |
| 工具          | (无)               | `bash` (shell 执行)               |
| 消息          | 单次请求           | 累积式对话历史                     |
| 退出条件      | 立即返回           | `finish_reason != TOOL_CALLS`     |

## 试一试

```sh
cd ai-agent-learning
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson1 --prompt='列出当前目录的文件, 然后统计 Java 文件数量'"
```

观察输出: 模型会自主决定执行哪些命令, 自行循环多次, 直到完成任务。

**源码**: [`Lesson1RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson1RunSimple.java)
