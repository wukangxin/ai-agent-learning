# Lesson 10: Team Protocols (团队协议)

`L00 > L01 > L02 > L03 > L04 > L05 > L06 | L07 > L08 > L09 > [ L10 ] L11 > L12 > L13`

> *"同一个 request_id 关联模式，两个域。"* -- 关机协议和计划审批共享同一种协议结构。

## 问题

L09 的队友可以发消息，但缺少结构化的协调协议。两个核心问题：

1. **安全关机** -- Lead 不能直接杀死线程，需要让队友优雅地完成当前工作后退出
2. **计划审批** -- 队友在执行重大操作前需要 Lead 审批，避免盲目执行

这两个需求看似不同，但都可以用同一种模式解决：**请求-响应 + request_id 关联**。

## 解决方案

```
关机协议:

Lead                              Teammate "A"
  |                                    |
  |-- shutdown_request(req_id) ------->|
  |   type: "shutdown_request"         |
  |   request_id: "abc12345"           |
  |                                    | (完成当前工作)
  |<-- shutdown_response(req_id) ------|
  |   type: "shutdown_response"        |
  |   request_id: "abc12345"           |
  |   approve: true                    |
  |                                    X (线程退出)


计划审批协议:

Teammate "A"                       Lead
  |                                    |
  |-- plan_approval(plan) ------------>|
  |   type: "plan_approval_response"   |
  |   request_id: "def67890"           |
  |   plan: "Refactor auth module"     |
  |                                    | (审查计划)
  |<-- plan_approval_response ---------|
  |   request_id: "def67890"           |
  |   approve: true/false              |
  |   feedback: "approved" / "需要..."  |
  |                                    |
  | (根据审批结果继续或停止)              |
```

### 共享 FSM (有限状态机)

```
两个协议共享相同的状态机:

  +----------+     request      +----------+     response     +----------+
  |          | ---------------> |          | ---------------> |          |
  | (start)  |                  | pending  |                  | approved |
  |          |                  |          |   or             |    or    |
  +----------+                  +----+-----+                  | rejected |
                                     |                        +----------+
                                     |  request_id 关联
                                     |  两端都持有
                                     v
                              shutdownRequests  (Map<String, Map>)
                              planRequests      (Map<String, Map>)
```

## 工作原理

### 1. 请求追踪器 -- ConcurrentHashMap + ReentrantLock

两种协议的请求都存放在线程安全的 Map 中，用 `request_id` 作为键：

```java
// 请求追踪器
private final Map<String, Map<String, Object>> shutdownRequests
        = new ConcurrentHashMap<>();
private final Map<String, Map<String, Object>> planRequests
        = new ConcurrentHashMap<>();
private final ReentrantLock trackerLock = new ReentrantLock();
```

### 2. 关机协议 -- Lead 端发起

Lead 生成一个 `request_id`，记录到 `shutdownRequests`，然后通过 MessageBus 发送关机请求：

```java
private String handleShutdownRequest(String teammate) {
    String reqId = UUID.randomUUID().toString().substring(0, 8);

    trackerLock.lock();
    try {
        shutdownRequests.put(reqId,
                Map.of("target", teammate, "status", "pending"));
    } finally {
        trackerLock.unlock();
    }

    bus.send("lead", teammate,
            "Please shut down gracefully.",
            "shutdown_request",
            Map.of("request_id", reqId));

    return "Shutdown request " + reqId
            + " sent to '" + teammate + "' (status: pending)";
}
```

### 3. 关机协议 -- Teammate 端响应

队友在工具调用中收到 `shutdown_request` 消息，通过 `shutdown_response` 工具回复：

```java
case "shutdown_response": {
    String reqId = (String) args.get("request_id");
    boolean approve = Boolean.TRUE.equals(args.get("approve"));

    // 更新请求追踪器
    trackerLock.lock();
    try {
        if (shutdownRequests.containsKey(reqId)) {
            shutdownRequests.get(reqId).put("status",
                    approve ? "approved" : "rejected");
        }
    } finally {
        trackerLock.unlock();
    }

    // 发回响应
    bus.send(sender, "lead",
            (String) args.getOrDefault("reason", ""),
            "shutdown_response",
            Map.of("request_id", reqId, "approve", approve));

    return "Shutdown " + (approve ? "approved" : "rejected");
}
```

如果 `approve=true`，队友的 agent loop 设置 `shouldExit=true`，在下一轮退出：

```java
if (toolName.equals("shutdown_response")
        && Boolean.TRUE.equals(args.get("approve"))) {
    shouldExit = true;
}

// ...

// 循环结束后
Map<String, Object> member = findMember(name);
if (member != null) {
    member.put("status", shouldExit ? "shutdown" : "idle");
    saveConfig();
}
```

### 4. 计划审批协议 -- Teammate 端发起

队友在执行重大操作前，通过 `plan_approval` 工具提交计划：

```java
case "plan_approval": {
    String planText = (String) args.get("plan");
    String reqId = UUID.randomUUID().toString().substring(0, 8);

    // 记录到请求追踪器
    trackerLock.lock();
    try {
        planRequests.put(reqId, Map.of(
                "from", sender,
                "plan", planText,
                "status", "pending"));
    } finally {
        trackerLock.unlock();
    }

    // 发送给 Lead
    bus.send(sender, "lead", planText,
            "plan_approval_response",
            Map.of("request_id", reqId, "plan", planText));

    return "Plan submitted (request_id=" + reqId
            + "). Waiting for lead approval.";
}
```

### 5. 计划审批协议 -- Lead 端审核

Lead 从收件箱中看到计划请求，通过 `plan_approval` 工具（Lead 版）做出审批：

```java
private String handlePlanReview(String requestId,
                                boolean approve, String feedback) {
    trackerLock.lock();
    Map<String, Object> req;
    try {
        req = planRequests.get(requestId);
    } finally {
        trackerLock.unlock();
    }

    if (req == null)
        return "Error: Unknown plan request_id '"
                + requestId + "'";

    String status = approve ? "approved" : "rejected";
    trackerLock.lock();
    try {
        req.put("status", status);
    } finally {
        trackerLock.unlock();
    }

    bus.send("lead", (String) req.get("from"),
            feedback != null ? feedback : "",
            "plan_approval_response",
            Map.of("request_id", requestId,
                    "approve", approve,
                    "feedback", feedback != null ? feedback : ""));

    return "Plan " + status + " for '" + req.get("from") + "'";
}
```

### 6. 队友的工具集扩展

队友现在额外拥有 `shutdown_response` 和 `plan_approval` 两个工具：

```java
protected List<ChatCompletionTool> createTeammateTools() {
    List<ChatCompletionTool> tools = new ArrayList<>();
    // ... 基础工具 (bash, read_file, etc.) ...
    tools.add(buildTool("shutdown_response",
            "Respond to a shutdown request.",
            Map.of("request_id", Map.of("type", "string"),
                    "approve", Map.of("type", "boolean"),
                    "reason", Map.of("type", "string")),
            List.of("request_id", "approve")));
    tools.add(buildTool("plan_approval",
            "Submit a plan for lead approval.",
            Map.of("plan", Map.of("type", "string")),
            List.of("plan")));
    return tools;
}
```

### 7. Lead 的工具集扩展

Lead 除了 L09 的工具，新增了协议相关的三个工具：

```java
// 发起关机请求
tools.add(createTool("shutdown_request",
        "Request a teammate to shut down gracefully.",
        Map.of("teammate", Map.of("type", "string")),
        List.of("teammate")));
// 查询关机状态
tools.add(createTool("shutdown_response",
        "Check the status of a shutdown request by request_id.",
        Map.of("request_id", Map.of("type", "string")),
        List.of("request_id")));
// 审批计划
tools.add(createTool("plan_approval",
        "Approve or reject a teammate's plan.",
        Map.of("request_id", Map.of("type", "string"),
                "approve", Map.of("type", "boolean"),
                "feedback", Map.of("type", "string")),
        List.of("request_id", "approve")));
```

## 变更一览

| 组件 | 之前 (L09) | 之后 (L10) |
|------|-----------|-----------|
| 关机 | 无 (线程自然结束) | 关机请求/响应握手协议 |
| 计划审批 | 无 | 计划提交 -> 审批/拒绝 |
| 请求关联 | 无 | `request_id` 追踪 (ConcurrentHashMap) |
| 状态机 | `working -> idle` | `pending -> approved/rejected` (共享 FSM) |
| 线程安全 | 基本 | `ReentrantLock` 保护追踪器 |
| Lead 新工具 | 无 | `shutdown_request`, `shutdown_response` (查询), `plan_approval` |
| Teammate 新工具 | 无 | `shutdown_response` (应答), `plan_approval` (提交) |

## 试一试

```sh
cd ai-agent-learning
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson10 --prompt='Spawn a worker teammate. Send it a task, then request it to shut down gracefully.'"
```

观察日志中的 `shutdown_request` 和 `shutdown_response` 消息交换。

**源码**: [`Lesson10RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson10RunSimple.java)
