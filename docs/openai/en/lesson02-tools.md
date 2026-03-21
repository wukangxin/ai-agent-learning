# Lesson 2: Tool Use

`L00 > L01 > [ L02 ] L03 > L04 > L05 > L06 | L07 > L08 > L09 > L10 > L11 > L12 > L13`

> *"The loop didn't change at all. I just added tools."* -- scaling from one tool to many is a dispatch problem, not a loop problem.

## Problem

In Lesson 1, the agent had a single tool: `bash`. But a real agent needs to read files, write files, edit files, and more. Adding each tool directly into the loop creates a growing if/else chain that becomes unmaintainable. We need a pattern that separates tool *definition* from tool *dispatch*.

The second problem: if the model can run any bash command, it can read `/etc/passwd` or write to `/usr/bin`. We need path sandboxing.

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
                    |    | .get(toolName)  | |
                    |    | .execute(args)  | |
                    |    +-----+-----------+ |
                    |          |             |
                    +----------+        +----v----+
                   tool_result          |  done   |
                   added to messages    |  (text) |
                                        +---------+

The loop is identical to L01. Only the dispatch map changed.
```

## How It Works

1. Define multiple tools as a list.

```java
private List<ChatCompletionTool> createTools() {
    List<ChatCompletionTool> tools = new ArrayList<>();

    tools.add(ChatCompletionTool.builder()
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
            .build());

    tools.add(ChatCompletionTool.builder()
            .function(FunctionDefinition.builder()
                    .name("read_file")
                    .description("Read file contents.")
                    .parameters(FunctionParameters.builder()
                            .putAdditionalProperty("type", JsonValue.from("object"))
                            .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                    "path", Map.of("type", "string"),
                                    "limit", Map.of("type", "integer")
                            )))
                            .putAdditionalProperty("required", JsonValue.from(List.of("path")))
                            .build())
                    .build())
            .build());

    // ... write_file, edit_file follow the same pattern
    return tools;
}
```

Each tool is a `ChatCompletionTool` with a `FunctionDefinition`. The model sees all of them and picks which one to call. The list is passed to `.tools(tools)` on the params builder.

2. Create a dispatch map instead of an if/else chain.

```java
@FunctionalInterface
interface ToolHandler {
    String execute(Map<String, Object> args) throws Exception;
}

private Map<String, ToolHandler> createHandlers() {
    Map<String, ToolHandler> handlers = new HashMap<>();
    handlers.put("bash",      args -> runBash((String) args.get("command")));
    handlers.put("read_file", args -> runRead((String) args.get("path"), (Integer) args.get("limit")));
    handlers.put("write_file",args -> runWrite((String) args.get("path"), (String) args.get("content")));
    handlers.put("edit_file", args -> runEdit((String) args.get("path"), (String) args.get("old_text"),
                                              (String) args.get("new_text")));
    return handlers;
}
```

The `ToolHandler` functional interface is the key abstraction. Each handler is a lambda that takes parsed arguments and returns a string result. Adding a new tool means adding one entry to the map and one tool definition to the list -- the loop never changes.

3. Dispatch by name inside the loop.

```java
String output = executeTool(handlers, toolName, arguments);

// ...

private String executeTool(Map<String, ToolHandler> handlers, String toolName, String arguments) {
    ToolHandler handler = handlers.get(toolName);
    if (handler == null) {
        return "Unknown tool: " + toolName;
    }
    try {
        Map<String, Object> args = parseArguments(arguments);
        return handler.execute(args);
    } catch (Exception e) {
        return "Error: " + e.getMessage();
    }
}
```

Unknown tools return an error string rather than crashing. The model reads the error and adjusts.

4. Path sandboxing: prevent filesystem escape.

```java
private Path safePath(String p) {
    Path path = workDir.resolve(p).normalize();
    if (!path.startsWith(workDir)) {
        throw new IllegalArgumentException("Path escapes workspace: " + p);
    }
    return path;
}
```

Every file tool (`read_file`, `write_file`, `edit_file`) calls `safePath()` first. If the model tries `../../etc/passwd`, the normalized path won't start with `workDir` and the operation is rejected. This is a critical security boundary.

5. The edit tool uses exact string matching, not regex.

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

The model sends the exact text to find and the exact text to replace it with. This is safer and more predictable than regex-based editing. The `escapeRegex` method ensures special characters in the old text don't break `replaceFirst`.

## What Changed

| Component     | Lesson 1                | Lesson 2                          |
|---------------|-------------------------|-----------------------------------|
| Tools         | 1 (`bash`)              | 4 (`bash`, `read_file`, `write_file`, `edit_file`) |
| Dispatch      | `if ("bash".equals(...))` | `handlers.get(toolName)` map    |
| File access   | Unrestricted via bash   | Sandboxed via `safePath()`        |
| Loop          | Same                    | Same (unchanged)                  |
| Architecture  | Monolithic              | Handler pattern (open for extension) |

## Try It

```sh
cd ai-agent-learning
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson2 --prompt='Read the pom.xml file and tell me what dependencies this project uses.'"
```

**Source**: [`Lesson2RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson2RunSimple.java)
