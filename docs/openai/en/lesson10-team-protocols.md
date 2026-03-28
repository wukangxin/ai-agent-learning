# Lesson 10: Team Protocols

```
L00 > L01 > L02 > L03 > L04 > L05 > L06 > L07 > L08 > L09 > [ L10 ] > L11 > L12 > L13
```

> "Same request_id correlation pattern, two domains."

---

## The Problem

In Lesson 9, teammates communicate freely but there is no structured coordination. Two critical scenarios are unhandled:

1. **Shutdown**: The lead cannot gracefully shut down a teammate. Killing the thread might leave work half-done.
2. **Plan approval**: A teammate might go off-track on a large task. There is no checkpoint before major work begins.

Both scenarios need the same pattern: a **request/response handshake** with a correlation ID.

```
  Without protocols:

    Lead: "Hey alice, please stop"
    Alice: (maybe sees it, maybe doesn't, no confirmation)
    Lead: (no idea if alice actually stopped)

  With protocols:

    Lead: shutdown_request(request_id="a1b2") --> alice
    Alice: shutdown_response(request_id="a1b2", approve=true) --> lead
    Lead: (knows for certain alice acknowledged shutdown)
```

## The Solution

Two protocols built on the same **request_id correlation** pattern, backed by a shared FSM (finite state machine):

```
  Shutdown Protocol:

    Lead                              Teammate
      |                                  |
      |-- shutdown_request(req_id) ----->|
      |   state: PENDING                 |
      |                                  |-- (finishes current work)
      |<-- shutdown_response(req_id) ----|
      |   state: APPROVED/REJECTED       |
      |                                  |-- (exits loop if approved)


  Plan Approval Protocol:

    Teammate                           Lead
      |                                  |
      |-- plan_approval(plan, req_id) -->|
      |   state: PENDING                 |
      |                                  |-- (reviews plan)
      |<-- plan_approval_response -------|
      |   req_id, approve, feedback      |
      |   state: APPROVED/REJECTED       |
      |                                  |
      |-- (proceeds if approved)         |
      |-- (revises if rejected)          |
```

### Shared FSM

Both protocols use the same state machine:

```
    +----------+
    |          |
    v          |
  PENDING -----+----> APPROVED
    |
    +----------------> REJECTED
```

The `request_id` is the correlation key between request and response.

## How It Works

### Request Trackers

Two concurrent maps track open requests, protected by a shared lock:

```java
private final Map<String, Map<String, Object>> shutdownRequests =
    new ConcurrentHashMap<>();
private final Map<String, Map<String, Object>> planRequests =
    new ConcurrentHashMap<>();
private final ReentrantLock trackerLock = new ReentrantLock();
```

### Shutdown Protocol -- Lead Side

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

### Shutdown Protocol -- Teammate Side

The teammate sees the `shutdown_request` in its inbox and responds with a tool call:

```java
case "shutdown_response": {
    String reqId = (String) args.get("request_id");
    boolean approve = Boolean.TRUE.equals(args.get("approve"));

    trackerLock.lock();
    try {
        if (shutdownRequests.containsKey(reqId)) {
            shutdownRequests.get(reqId).put("status",
                approve ? "approved" : "rejected");
        }
    } finally {
        trackerLock.unlock();
    }

    bus.send(sender, "lead",
        (String) args.getOrDefault("reason", ""),
        "shutdown_response",
        Map.of("request_id", reqId, "approve", approve));

    return "Shutdown " + (approve ? "approved" : "rejected");
}
```

If approved, the teammate's loop sets `shouldExit = true` and breaks:

```java
if (toolName.equals("shutdown_response")
        && Boolean.TRUE.equals(args.get("approve"))) {
    shouldExit = true;
}

// After the loop:
member.put("status", shouldExit ? "shutdown" : "idle");
saveConfig();
```

### Plan Approval Protocol -- Teammate Side

A teammate submits a plan before starting major work:

```java
case "plan_approval": {
    String planText = (String) args.get("plan");
    String reqId = UUID.randomUUID().toString().substring(0, 8);

    trackerLock.lock();
    try {
        planRequests.put(reqId, Map.of(
            "from", sender,
            "plan", planText,
            "status", "pending"));
    } finally {
        trackerLock.unlock();
    }

    bus.send(sender, "lead", planText,
        "plan_approval_response",
        Map.of("request_id", reqId, "plan", planText));

    return "Plan submitted (request_id=" + reqId
        + "). Waiting for lead approval.";
}
```

### Plan Approval Protocol -- Lead Side

The lead reviews and approves or rejects:

```java
private String handlePlanReview(String requestId,
        boolean approve, String feedback) {
    Map<String, Object> req = planRequests.get(requestId);
    if (req == null)
        return "Error: Unknown plan request_id '" + requestId + "'";

    String status = approve ? "approved" : "rejected";
    req.put("status", status);

    bus.send("lead", (String) req.get("from"),
        feedback != null ? feedback : "",
        "plan_approval_response",
        Map.of("request_id", requestId,
               "approve", approve,
               "feedback", feedback != null ? feedback : ""));

    return "Plan " + status + " for '" + req.get("from") + "'";
}
```

### Valid Message Types

Both protocols use the existing `MessageBus` with validated message types:

```java
private static final Set<String> VALID_MSG_TYPES = Set.of(
    "message",
    "broadcast",
    "shutdown_request",
    "shutdown_response",
    "plan_approval_response"
);
```

### Extended Teammate Tools

Teammates now have two additional protocol tools:

```java
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
```

---

## What Changed (from Lesson 9)

| Aspect | Lesson 9 (Agent Teams) | Lesson 10 (Team Protocols) |
|--------|----------------------|--------------------------|
| Shutdown | Kill thread / hope for the best | Graceful request/response handshake |
| Plan review | None | Submit plan, wait for approval |
| Coordination pattern | Free-form messages only | Structured protocols with `request_id` |
| State tracking | Just status in config.json | `ConcurrentHashMap` trackers per protocol |
| FSM | None | `pending -> approved/rejected` |
| New lead tools | -- | `shutdown_request`, `plan_approval` |
| New teammate tools | -- | `shutdown_response`, `plan_approval` |

---

## Try It

1. Run `Lesson10RunSimple` with: `"Spawn alice as a frontend dev. Ask her to build a dashboard in the trysamples directory. Then request her shutdown after she submits a plan."`
2. Watch the plan approval flow: alice submits a plan, the lead approves or rejects it.
3. Watch the shutdown handshake: lead sends `shutdown_request`, alice responds with `shutdown_response`.
4. Verify `config.json` shows alice's status transition: `working -> idle -> shutdown`.

---

**Source**: [`Lesson10RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson10RunSimple.java)
