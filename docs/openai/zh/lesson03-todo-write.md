# Lesson 3: TodoWrite (待办写入)

`L00 > L01 > L02 > [ L03 ] L04 > L05 > L06 | L07 > L08 > L09 > L10 > L11 > L12 > L13`

> *"智能体能追踪自己的进度 -- 而且我能看到。"* -- 可观测性不是 debug 手段, 是产品功能。

## 问题

Lesson 2 的智能体能执行多步任务, 但你**看不到它在干什么**。对于长任务:
- 用户不知道进度 (已完成 3/7 步?)
- 模型自己也会忘记计划 (上下文越长, 注意力越分散)
- 没有办法发现模型"跑偏"了

我们需要一个结构化的进度追踪机制。

## 解决方案

```
+--------+      +---------+      +-----------+
|  User  | ---> |  Agent  | ---> |  Tools    |
| prompt |      |  Loop   |      +-----------+
+--------+      |         |           |
                |         |      +----+----+----+----+----+
  +----------+  |         |      |    |    |    |    |    |
  | Todo     |<-+ nag     |     bash read write edit todo
  | Manager  |  | reminder|
  | [ ] #1   |  |         |
  | [>] #2   |  +---------+
  | [x] #3   |
  +----------+

约束: 同时只能有一个 in_progress 任务。
```

## 工作原理

### 1. TodoManager: 结构化状态

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
            String id = item.get("id").toString();
            String text = item.get("text").toString().trim();
            String status = item.get("status").toString().toLowerCase();

            // 状态验证: 只允许三种状态
            if (!status.equals("pending") && !status.equals("in_progress")
                    && !status.equals("completed")) {
                throw new IllegalArgumentException(
                    "Item " + id + ": invalid status '" + status + "'");
            }

            // 关键约束: 同时只能一个 in_progress
            if (status.equals("in_progress")) inProgressCount++;

            validated.add(new TodoItem(id, text, status));
        }

        if (inProgressCount > 1) {
            throw new IllegalArgumentException(
                "Only one task can be in_progress at a time");
        }

        items = validated;
        return render();
    }
}
```

为什么限制只有一个 `in_progress`? 因为模型是单线程推理的。允许多个并行任务会让模型困惑, 导致它跳来跳去而不是专注完成一个。

### 2. Todo 渲染

```java
public String render() {
    if (items.isEmpty()) return "No todos.";

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

    long done = items.stream()
        .filter(t -> t.status.equals("completed")).count();
    sb.append("\n(").append(done).append("/")
      .append(items.size()).append(" completed)");
    return sb.toString();
}
```

渲染输出示例:
```
[ ] #1: 读取项目结构
[>] #2: 分析 pom.xml 依赖
[ ] #3: 编写总结文档

(0/3 completed)
```

### 3. Todo 工具定义

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
                                                                "enum", List.of("pending",
                                                                    "in_progress", "completed"))
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

注意 `enum` 约束 -- 模型只能输出 `pending`、`in_progress`、`completed` 三种状态。JSON Schema 的约束在模型端就生效了。

### 4. Nag Reminder (催促提醒)

```java
private void agentLoop(...) {
    int roundsSinceTodo = 0;

    while (true) {
        // ... 正常的循环逻辑 ...

        if (assistantMessage.toolCalls().isPresent()) {
            boolean usedTodo = false;

            for (ChatCompletionMessageToolCall toolCall : ...) {
                // ... 执行工具 ...
                if ("todo".equals(toolCall.function().name())) {
                    usedTodo = true;
                }
            }

            // 催促注入: 如果模型连续 3 轮没更新 todo, 提醒它
            roundsSinceTodo = usedTodo ? 0 : roundsSinceTodo + 1;
            if (roundsSinceTodo >= 3) {
                // 注入提醒消息
                results.add(0, Map.of(
                    "type", "text",
                    "text", "<reminder>Update your todos.</reminder>"
                ));
            }
        }
    }
}
```

为什么需要 nag? 因为模型会"忘记"更新进度。它沉浸在具体任务中, 忽略了元层面的进度追踪。催促机制是一个轻量的干预: 不改变模型行为, 只是提醒它维护状态。

## 变更内容

| 组件          | 之前 (L02)         | 之后 (L03)                        |
|---------------|--------------------|------------------------------------|
| 工具数量      | 4                  | 5 (+todo)                         |
| 状态追踪      | (无)               | TodoManager 结构化状态             |
| 可观测性      | 只有日志           | 渲染进度条 + 完成计数              |
| 约束          | (无)               | 单 in_progress + 最多 20 条       |
| 干预机制      | (无)               | Nag reminder (3 轮无更新触发)     |

## 试一试

```sh
cd ai-agent-learning
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson3 --prompt='分析项目结构, 列出所有 Java 文件并为每个文件写一句话总结'"
```

观察模型如何:
1. 先创建待办列表 (规划)
2. 逐个将任务标记为 `in_progress` (执行)
3. 完成后标记为 `completed` (收尾)
4. 如果忘记更新, 被催促后补上

**源码**: [`Lesson3RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson3RunSimple.java)
