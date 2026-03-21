# Lesson 2: Tool Use (工具使用)

`L00 > L01 > [ L02 ] L03 > L04 > L05 > L06 | L07 > L08 > L09 > L10 > L11 > L12 > L13`

> *"循环根本没变。我只是加了工具。"* -- 扩展能力不需要改架构。

## 问题

Lesson 1 只有一个 `bash` 工具。模型要读文件得 `cat`, 要写文件得 `echo >`, 要编辑得用 `sed` -- 这些命令容易出错, 且模型经常搞混转义。我们需要**专用工具**, 让模型用结构化参数而不是拼 shell 命令。

## 解决方案

```
+--------+      +---------+      +-----------+
|  User  | ---> |  Agent  | ---> |  Tool     |
| prompt |      |  Loop   |      |  Dispatch |
+--------+      |         |      |  Map      |
                | (不变)   |      +-----------+
                |         |           |
                +---------+      +----+----+----+----+
                                 |    |    |    |    |
                                bash read write edit ...
```

关键洞察: **智能体循环完全不变**。你只需要:
1. 往 `tools` 数组里加新的 JSON Schema 定义
2. 往 dispatch map 里加新的处理函数

## 工作原理

### 1. Dispatch Map 模式

```java
// 函数式接口 -- 所有工具统一签名
@FunctionalInterface
interface ToolHandler {
    String execute(Map<String, Object> args) throws Exception;
}

// Dispatch map: 工具名 -> 处理函数
private Map<String, ToolHandler> createHandlers() {
    Map<String, ToolHandler> handlers = new HashMap<>();
    handlers.put("bash",      args -> runBash((String) args.get("command")));
    handlers.put("read_file", args -> runRead((String) args.get("path"), (Integer) args.get("limit")));
    handlers.put("write_file",args -> runWrite((String) args.get("path"), (String) args.get("content")));
    handlers.put("edit_file", args -> runEdit((String) args.get("path"),
                                              (String) args.get("old_text"),
                                              (String) args.get("new_text")));
    return handlers;
}
```

这个模式的好处: 加新工具只需要两步 -- 定义 Schema + 注册 handler。循环代码零修改。

### 2. 工具定义 (JSON Schema)

```java
private List<ChatCompletionTool> createTools() {
    List<ChatCompletionTool> tools = new ArrayList<>();

    // bash: 执行 shell 命令
    tools.add(ChatCompletionTool.builder()
            .function(FunctionDefinition.builder()
                    .name("bash")
                    .description("Run a shell command.")
                    .parameters(FunctionParameters.builder()
                            .putAdditionalProperty("type", JsonValue.from("object"))
                            .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                    "command", Map.of("type", "string"))))
                            .putAdditionalProperty("required", JsonValue.from(List.of("command")))
                            .build())
                    .build())
            .build());

    // read_file: 读取文件内容
    tools.add(ChatCompletionTool.builder()
            .function(FunctionDefinition.builder()
                    .name("read_file")
                    .description("Read file contents.")
                    .parameters(FunctionParameters.builder()
                            .putAdditionalProperty("type", JsonValue.from("object"))
                            .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                    "path", Map.of("type", "string"),
                                    "limit", Map.of("type", "integer"))))
                            .putAdditionalProperty("required", JsonValue.from(List.of("path")))
                            .build())
                    .build())
            .build());

    // write_file: 写入文件
    tools.add(ChatCompletionTool.builder()
            .function(FunctionDefinition.builder()
                    .name("write_file")
                    .description("Write content to file.")
                    .parameters(FunctionParameters.builder()
                            .putAdditionalProperty("type", JsonValue.from("object"))
                            .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                    "path", Map.of("type", "string"),
                                    "content", Map.of("type", "string"))))
                            .putAdditionalProperty("required", JsonValue.from(List.of("path", "content")))
                            .build())
                    .build())
            .build());

    // edit_file: 精确文本替换
    tools.add(ChatCompletionTool.builder()
            .function(FunctionDefinition.builder()
                    .name("edit_file")
                    .description("Replace exact text in file.")
                    .parameters(FunctionParameters.builder()
                            .putAdditionalProperty("type", JsonValue.from("object"))
                            .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                    "path", Map.of("type", "string"),
                                    "old_text", Map.of("type", "string"),
                                    "new_text", Map.of("type", "string"))))
                            .putAdditionalProperty("required", JsonValue.from(
                                    List.of("path", "old_text", "new_text")))
                            .build())
                    .build())
            .build());

    return tools;
}
```

### 3. 统一调度 (在循环内)

```java
// 循环体内的工具调度 -- 和 Lesson 1 结构完全一样
for (ChatCompletionMessageToolCall toolCall : assistantMessage.toolCalls().get()) {
    String toolName = toolCall.function().name();
    String arguments = toolCall.function().arguments();

    // 一行调度, 替代 if-else 链
    String output = executeTool(handlers, toolName, arguments);

    messages.add(ChatCompletionMessageParam.ofTool(
        ChatCompletionToolMessageParam.builder()
            .toolCallId(toolCall.id())
            .content(output)
            .build()));
}
```

### 4. 安全措施: 路径沙箱

```java
private Path safePath(String p) {
    Path path = workDir.resolve(p).normalize();
    if (!path.startsWith(workDir)) {
        throw new IllegalArgumentException("Path escapes workspace: " + p);
    }
    return path;
}
```

所有文件操作都通过 `safePath()` 过滤, 防止模型通过 `../../etc/passwd` 之类的路径逃逸工作区。

### 5. edit_file: 精确替换

```java
private String runEdit(String path, String oldText, String newText) {
    Path fp = safePath(path);
    String content = new String(Files.readAllBytes(fp), StandardCharsets.UTF_8);
    if (!content.contains(oldText)) {
        return "Error: Text not found in " + path;
    }
    content = content.replaceFirst(escapeRegex(oldText), newText);
    Files.write(fp, content.getBytes(StandardCharsets.UTF_8));
    return "Edited " + path;
}
```

`edit_file` 比 `write_file` 更安全 -- 它只替换匹配的部分, 不会覆盖整个文件。模型发送精确的 `old_text` 和 `new_text`, 避免意外修改。

## 变更内容

| 组件          | 之前 (L01)         | 之后 (L02)                        |
|---------------|--------------------|------------------------------------|
| 工具数量      | 1 (bash)           | 4 (bash, read, write, edit)       |
| 调度方式      | 硬编码 if          | `Map<String, ToolHandler>`        |
| 文件操作      | 通过 bash 间接     | 专用工具 + 路径沙箱               |
| 循环代码      | 不变               | 不变                               |

## 试一试

```sh
cd ai-agent-learning
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson2 --prompt='读取 pom.xml, 找到 Java 版本配置, 然后创建一个 summary.txt 总结项目依赖'"
```

观察模型如何自主选择合适的工具: `read_file` 读取, 推理内容, `write_file` 写入总结。

**源码**: [`Lesson2RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson2RunSimple.java)
