# Lesson 7: Task System (任务系统)

`L00 > L01 > L02 > L03 > L04 > L05 > L06 | [ L07 ] L08 > L09 > L10 > L11 > L12 > L13`

> *"状态在对话之外持久化 -- 因为它在文件系统里。"* -- 任务图不随上下文压缩而消失。

## 问题

上下文压缩（L06）会把对话历史截断，这意味着智能体会*忘掉*之前计划好的工作。TodoWrite（L03）把任务列表放在对话内存里，压缩一次就可能丢失。我们需要一种持久化任务追踪方式，让任务之间还能声明依赖关系。

## 解决方案

```
+------------------+
|   .tasks/        |    文件系统 (持久化)
|   task_1.json    |
|   task_2.json    |
|   task_3.json    |
+--------+---------+
         |
         v
+--------+---------+
|   TaskManager    |    CRUD + 依赖图
|                  |
|  create()        |
|  get()           |
|  update()        |    status: pending -> in_progress -> completed
|  listAll()       |
|  clearDependency()|   完成时自动解锁
+--------+---------+
         |
         v
+--------+---------+
|   Agent Loop     |    通过 tool call 操作任务
|  task_create     |
|  task_update     |
|  task_get        |
|  task_list       |
+------------------+
```

### DAG 依赖图

```
task_1 (Setup DB)
   |
   | blocks
   v
task_2 (Write API)  --- blockedBy: [1] --->  task_2 不可执行
   |                                         直到 task_1 completed
   | blocks
   v
task_3 (Write Tests) --- blockedBy: [2] --->  task_3 不可执行
                                              直到 task_2 completed

当 task_1 标记为 completed:
  -> clearDependency(1) 遍历所有任务
  -> 把 task_1 从 task_2.blockedBy 中移除
  -> task_2 变为可执行 (blockedBy=[])
```

## 工作原理

### 1. 任务以 JSON 文件持久化

每个任务是一个独立的 JSON 文件，存放在 `.tasks/` 目录下：

```
.tasks/
  task_1.json   ->  {"id":1,"subject":"Setup DB","status":"pending","blockedBy":[],"blocks":[2]}
  task_2.json   ->  {"id":2,"subject":"Write API","status":"pending","blockedBy":[1],"blocks":[3]}
  task_3.json   ->  {"id":3,"subject":"Write Tests","status":"pending","blockedBy":[2],"blocks":[]}
```

### 2. TaskManager -- CRUD 与依赖图管理

TaskManager 是一个内部类，负责所有任务操作：

```java
static class TaskManager {
    private final Path dir;
    private int nextId;

    public TaskManager(Path tasksDir) {
        this.dir = tasksDir;
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tasks directory", e);
        }
        this.nextId = maxId() + 1;
    }

    public String create(String subject, String description) {
        Map<String, Object> task = new HashMap<>();
        task.put("id", nextId);
        task.put("subject", subject);
        task.put("description", description != null ? description : "");
        task.put("status", "pending");
        task.put("blockedBy", new ArrayList<>());
        task.put("blocks", new ArrayList<>());
        task.put("owner", "");

        save(task);
        nextId++;
        return mapToJson(task);
    }
}
```

`nextId` 在构造时扫描现有文件计算最大 ID，保证新任务不会和已有任务冲突。

### 3. update() -- 状态流转与依赖解锁

状态只允许三种值：`pending`、`in_progress`、`completed`。当任务标记为 `completed` 时，自动调用 `clearDependency()` 解除对其他任务的阻塞：

```java
public String update(int taskId, String status,
                     List<Integer> addBlockedBy, List<Integer> addBlocks) {
    Map<String, Object> task = load(taskId);

    if (status != null) {
        if (!status.equals("pending") && !status.equals("in_progress")
                && !status.equals("completed")) {
            throw new IllegalArgumentException("Invalid status: " + status);
        }
        task.put("status", status);

        // 完成时自动解锁所有被本任务阻塞的任务
        if (status.equals("completed")) {
            clearDependency(taskId);
        }
    }

    // 支持在更新时添加双向依赖
    if (addBlocks != null) {
        @SuppressWarnings("unchecked")
        List<Integer> blocks = new ArrayList<>((List<Integer>) task.get("blocks"));
        for (Integer blockedId : addBlocks) {
            if (!blocks.contains(blockedId)) {
                blocks.add(blockedId);
            }
            // 双向更新：同时修改被阻塞任务的 blockedBy 列表
            try {
                Map<String, Object> blocked = load(blockedId);
                @SuppressWarnings("unchecked")
                List<Integer> blockedBy = new ArrayList<>(
                        (List<Integer>) blocked.get("blockedBy"));
                if (!blockedBy.contains(taskId)) {
                    blockedBy.add(taskId);
                    blocked.put("blockedBy", blockedBy);
                    save(blocked);
                }
            } catch (Exception ignored) {}
        }
        task.put("blocks", blocks);
    }

    save(task);
    return mapToJson(task);
}
```

### 4. clearDependency() -- 完成后自动解锁

当一个任务完成时，遍历所有任务文件，把已完成任务的 ID 从其他任务的 `blockedBy` 列表中移除：

```java
private void clearDependency(int completedId) {
    try {
        Files.list(dir)
                .filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                .forEach(p -> {
                    try {
                        Map<String, Object> t = parseJsonToMap(
                                new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                        @SuppressWarnings("unchecked")
                        List<Integer> blockedBy = new ArrayList<>(
                                (List<Integer>) t.get("blockedBy"));
                        if (blockedBy.contains(completedId)) {
                            blockedBy.remove(Integer.valueOf(completedId));
                            t.put("blockedBy", blockedBy);
                            save(t);
                        }
                    } catch (Exception ignored) {}
                });
    } catch (Exception ignored) {}
}
```

### 5. listAll() -- 带状态标记的列表

```java
public String listAll() {
    // ...
    for (Map<String, Object> t : tasks) {
        String marker = switch ((String) t.get("status")) {
            case "pending"     -> "[ ]";
            case "in_progress" -> "[>]";
            case "completed"   -> "[x]";
            default            -> "[?]";
        };
        @SuppressWarnings("unchecked")
        List<Integer> blockedBy = (List<Integer>) t.get("blockedBy");
        String blocked = blockedBy != null && !blockedBy.isEmpty()
                ? " (blocked by: " + blockedBy + ")" : "";
        sb.append(marker).append(" #").append(t.get("id")).append(": ")
                .append(t.get("subject")).append(blocked).append("\n");
    }
    return sb.toString().trim();
}
```

输出示例：

```
[x] #1: Setup DB
[>] #2: Write API
[ ] #3: Write Tests (blocked by: [2])
```

### 6. 工具注册 -- Task 工具加入调度表

```java
private Map<String, ToolHandler> createHandlers() {
    Map<String, ToolHandler> handlers = new HashMap<>();
    // 基础工具...
    handlers.put("task_create", args ->
            taskManager.create((String) args.get("subject"),
                    (String) args.get("description")));
    handlers.put("task_update", args ->
            taskManager.update(
                    ((Number) args.get("task_id")).intValue(),
                    (String) args.get("status"),
                    (List<Integer>) args.get("addBlockedBy"),
                    (List<Integer>) args.get("addBlocks")));
    handlers.put("task_list", args -> taskManager.listAll());
    handlers.put("task_get", args ->
            taskManager.get(((Number) args.get("task_id")).intValue()));
    return handlers;
}
```

## 变更一览

| 组件 | 之前 (L06) | 之后 (L07) |
|------|-----------|-----------|
| 任务持久化 | 无 (内存中 TodoWrite) | `.tasks/task_N.json` 文件持久化 |
| 依赖管理 | 无 | `blockedBy` / `blocks` 双向链表 |
| 完成解锁 | 无 | `clearDependency()` 自动遍历解锁 |
| 状态模型 | 无 | `pending -> in_progress -> completed` |
| 新工具 | 无 | `task_create`, `task_update`, `task_get`, `task_list` |
| 压缩安全 | 丢失 | 任务存文件系统，压缩不影响 |

## 试一试

```sh
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson7 --prompt='Plan a REST API project in the trysamples directory with 3 tasks: setup, implementation, testing. Make them depend on each other.'"
```

运行后查看 `.tasks/` 目录：

```sh
ls .tasks/
cat .tasks/task_1.json
```

**源码**: [`Lesson7RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson7RunSimple.java)
