# Lesson 0: Basic Chat

`[ L00 ] L01 > L02 > L03 > L04 > L05 > L06 | L07 > L08 > L09 > L10 > L11 > L12 > L13`

> *"Send one prompt, get one response"* -- the simplest possible interaction with an LLM.

## Problem

Before building an agent, you need to talk to the model. The OpenAI Java SDK handles HTTP, auth, and serialization -- but you still need to understand the request/response structure: model selection, system prompt injection, and response extraction.

## Solution

```
+--------+      +-------+      +----------+
|  User  | ---> |  LLM  | ---> | Response |
| prompt |      |       |      |  text    |
+--------+      +-------+      +----------+

No loop. No tools. One request, one response.
```

## How It Works

1. Configure the client with API key, base URL, and optional proxy.

```java
Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));

OpenAIClient client = OpenAIOkHttpClient.builder()
        .apiKey(apiKey)
        .baseUrl(baseUrl)
        .proxy(proxy)
        .build();
```

The `OpenAIOkHttpClient` builder handles all the HTTP plumbing. The proxy is optional -- remove it if you have direct access to the API.

2. Build the request with model, system message, and user message.

```java
ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
        .model(ChatModel.of(modelName));

if (systemPrompt != null && !systemPrompt.isEmpty()) {
    paramsBuilder.addSystemMessage(systemPrompt);
}
paramsBuilder.addUserMessage(userPrompt);
```

`ChatModel.of(modelName)` accepts any model string -- `"gpt-4o"`, `"gpt-4o-mini"`, or a custom deployment name. The system message sets the persona; the user message is the actual prompt.

3. Send and extract the response.

```java
ChatCompletion completion = client.chat().completions().create(paramsBuilder.build());
String response = completion.choices().get(0).message().content().orElse("No response");
```

`content()` returns `Optional<String>` because the model might return a tool call instead of text (we will see that in Lesson 1). For now, we just unwrap it.

No loop, no tools, no message accumulation. This is the baseline everything else builds on.

## What Changed

| Component     | Before     | After                          |
|---------------|------------|--------------------------------|
| LLM call      | (none)     | Single request/response        |
| Client        | (none)     | OpenAI Java SDK with proxy     |
| Messages      | (none)     | System + user message          |
| Control flow  | (none)     | Linear (no loop)               |

## Try It

```sh
cd ai-agent-learning
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson0 --prompt='Hello, what can you do?'"
```

**Source**: [`Lesson0RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson0RunSimple.java)
