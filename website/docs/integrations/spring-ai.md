---
title: Spring AI Integration
description: How OryxOS uses Spring AI — and what it doesn't.
---

# Spring AI Integration

OryxOS is built on Spring AI Alibaba. We use Spring AI for the parts that don't conflict with our architecture — and we **deliberately don't use** the parts that would.

---

## What OryxOS uses from Spring AI

### 1. Provider abstraction

Spring AI gives us a uniform `ChatModel` interface across vendors:

```java
public interface ChatModel {
    ChatResponse call(Prompt prompt);
}
```

`DeepSeekChatModel`, `QwenChatModel`, `KimiChatModel`, `DashScopeChatModel`, etc. all implement this. The runtime is identical.

We register each as a Spring bean and wrap it in our `ProviderService`:

```java
@Bean(name = "deepseekChatModel")
ChatModel deepseekChatModel(OryxosDeepSeekProperties props) {
    return DeepSeekChatModel.builder()
        .apiKey(props.getApiKey())
        .model(props.getModel())
        .temperature(props.getTemperature())
        .build();
}

@Component
public class DeepSeekProviderInitializer implements ProviderInitializer {
    @Override public String name() { return "deepseek"; }
    @Override public ChatModel create(ChatModel model) { return model; }
}
```

`ProviderService.register("deepseek", deepseekChatModel())` is called at startup. From then on, profiles look up by string name.

### 2. Protocol conversion

Spring AI handles wire-format conversion:

- OpenAI-compatible (most Chinese vendors — DeepSeek, Kimi, Qwen, Zhipu, Doubao)
- Anthropic Messages API
- DashScope native
- Ollama native

Without Spring AI, we'd be writing four HTTP clients and four JSON parsers. With it, we just configure `base-url` + `api-key` + `model`.

### 3. `@Tool` schema generation

Spring AI's `@Tool` annotation generates the function-calling JSON schema for the LLM. OryxOS uses this to populate the tool list in the prompt:

```java
@Tool(description = "GET a whitelisted URL")
String httpGet(@P("url") String url) { ... }
```

The schema goes into the prompt as a `tools: [...]` array. The LLM responds with `tool_calls`, our `ToolExecutor` runs them, and the result goes back into the session.

---

## What OryxOS does NOT use from Spring AI

### ❌ Agent abstraction

Spring AI has `AgentExecutor`, `FunctionCallingAgent`, and several others. They auto-execute tool calls.

**Why we don't use them**: They cause **duplicate tool invocations**. The Agent calls the tool, then our `ToolExecutor` also calls the tool, then the LLM sees the result twice. The audit log captures only one of the two calls (ours), so the system looks broken in audit reports.

**Our replacement**: `ReActLoop` in `oryxos-core`. Self-implemented, ~tens of lines of Java. Tool scheduling is fully owned by `ToolExecutor`.

### ❌ Auto tool execution

Spring AI's `ChatModel` can be configured with a tool callback resolver that auto-executes tool calls. **This is the same problem as above, even worse** — the auto-executor runs the tool AND our `ToolExecutor` runs it.

**Our fix**: we don't configure auto-execution. The `ChatModel` only generates `tool_calls`; our `ReActLoop` parses them and calls `ToolExecutor.execute(...)`.

### ❌ Built-in conversation memory

Spring AI has `ChatMemory` / `MessageWindowChatMemory` for conversation history. We don't use it because:

- It doesn't write to `sessions` SQLite table (no audit).
- It doesn't integrate with our `MemoryService` three-layer facade.
- It doesn't support the CORE / ARCHIVE scope split.

**Our replacement**: `SessionManager` in `oryxos-core` (SQLite-backed) + `MemoryService` in `oryxos-memory` (three-layer facade).

### ❌ Built-in RAG / vector memory

Spring AI has `VectorStore` integrations (PgVector, Chroma, Qdrant, etc.). Vector memory is **not in the core stage** — see the constitution.

---

## Auto-configuration

When you depend on `oryxos-boot`, the Spring AI starter is on the classpath. OryxOS declares `@Bean` instances of `ChatModel` for each configured provider but does NOT declare any `AgentExecutor` or tool callback resolvers.

To verify, after starting the gateway, inspect the Spring context:

```bash
$ curl http://localhost:8080/api/v1/info | jq

{
  "springAi": {
    "version": "1.0.0",
    "chatModels": ["deepseek", "kimi", "qwen"],
    "toolCallbacks": [],
    "agents": []
  },
  "oryxos": {
    "version": "1.0.0-SNAPSHOT",
    "tools": 9,
    "profiles": 3,
    "scheduledJobs": 3
  }
}
```

`toolCallbacks: []` and `agents: []` — Spring AI's auto-execution is off. Only our `ReActLoop` invokes tools.

---

## What about Spring AI Alibaba?

Spring AI Alibaba extends Spring AI with DashScope-native models (Qwen, etc.) and is the official Chinese AI extension. OryxOS uses it for:

- `DashScopeChatModel` for Qwen, Tongyi, etc.
- `DeepSeekChatModel` (Spring AI Alibaba's connector for DeepSeek)

For Kimi (Moonshot), we use a custom adapter because there's no first-party Spring AI Alibaba connector.

---

## Where to go next

| Destination                                                  | What you'll find                                       |
| ------------------------------------------------------------ | ------------------------------------------------------ |
| [MCP integration](./mcp)                                     | Model Context Protocol client/server                  |
| [LangChain4j integration](./langchain4j)                     | Community-stage alternative                            |
| [Java SDK](../sdk/java)                                       | Programmatic API                                       |
| [For Engineers](../for-engineer)                             | Build, deploy, extend                                  |