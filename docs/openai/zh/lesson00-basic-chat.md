# Lesson 0: Basic Chat (基础对话)

`[ L00 ] L01 > L02 > L03 > L04 > L05 > L06 | L07 > L08 > L09 > L10 > L11 > L12 > L13`

> *"发一个 prompt, 收一个回复"* -- 与 LLM 最简单的交互。

## 问题

构建智能体之前, 得先能和模型对话。OpenAI Java SDK 处理了 HTTP、认证和序列化, 但你仍需理解请求/响应结构: 模型选择、系统提示注入和响应提取。

## 解决方案

```
+--------+      +-------+      +----------+
|  User  | ---> |  LLM  | ---> | Response |
| prompt |      |       |      |  text    |
+--------+      +-------+      +----------+

没有循环。没有工具。一个请求, 一个响应。
```

## 工作原理

1. 配置客户端 -- API Key、Base URL 和可选代理。

```java
Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));

OpenAIClient client = OpenAIOkHttpClient.builder()
        .apiKey(apiKey)
        .baseUrl(baseUrl)
        .proxy(proxy)
        .build();
```

`OpenAIOkHttpClient` 的 builder 封装了所有 HTTP 管道。代理是可选的 -- 如果可以直连 API 则不需要。

2. 构建请求 -- 模型、系统消息和用户消息。

```java
ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
        .model(ChatModel.of(modelName));

if (systemPrompt != null && !systemPrompt.isEmpty()) {
    paramsBuilder.addSystemMessage(systemPrompt);
}
paramsBuilder.addUserMessage(userPrompt);
```

`ChatModel.of(modelName)` 接受任意模型字符串 -- `"gpt-4o"`、`"gpt-4o-mini"` 或自定义部署名。系统消息设定角色人设; 用户消息是真正的 prompt。

3. 发送请求并提取响应。

```java
ChatCompletion completion = client.chat().completions().create(paramsBuilder.build());
String response = completion.choices().get(0).message().content().orElse("No response");
```

`content()` 返回 `Optional<String>`, 因为模型可能返回工具调用而不是文本 (我们将在 Lesson 1 中看到)。这里我们直接解包。

没有循环, 没有工具, 没有消息累积。这是所有后续课程的基线。

## 变更内容

| 组件          | 之前       | 之后                           |
|---------------|------------|--------------------------------|
| LLM 调用      | (无)       | 单次请求/响应                  |
| 客户端        | (无)       | OpenAI Java SDK + 代理         |
| 消息          | (无)       | 系统消息 + 用户消息            |
| 控制流        | (无)       | 线性 (无循环)                  |

## 试一试

```sh
cd ai-agent-learning
mvn spring-boot:run -pl openai -Dspring-boot.run.arguments="--lesson=lesson0 --prompt='你好, 你能做什么?'"
```

**源码**: [`Lesson0RunSimple.java`](../../openai/src/main/java/ai/agent/learning/lesson/Lesson0RunSimple.java)
