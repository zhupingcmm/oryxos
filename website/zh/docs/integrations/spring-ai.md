---
title: Spring AI 集成
description: OryxOS 怎么用 Spring AI——以及不用的部分。
---

# Spring AI 集成

OryxOS 基于 Spring AI Alibaba。我们用 Spring AI 不跟我们架构冲突的部分——**故意不**用会冲突的部分。

---

## OryxOS 用 Spring AI 的什么

### 1. Provider 抽象

Spring AI 给了一个跨厂商统一的 `ChatModel` 接口：

```java
public interface ChatModel {
    ChatResponse call(Prompt prompt);
}
```

`DeepSeekChatModel`、`QwenChatModel`、`KimiChatModel`、`DashScopeChatModel` 等都实现这个。运行时一致。

我们把每个注册成 Spring bean，包到我们的 `ProviderService`：

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

`ProviderService.register("deepseek", deepseekChatModel())` 在启动时调用。之后 profile 按字符串名查找。

### 2. 协议转换

Spring AI 处理线协议转换：

- OpenAI 兼容（多数中国厂商——DeepSeek、Kimi、Qwen、智谱、豆包）
- Anthropic Messages API
- DashScope 原生
- Ollama 原生

没有 Spring AI 我们得写四个 HTTP client 和四个 JSON parser。有了 Spring AI 我们只配 `base-url` + `api-key` + `model`。

### 3. `@Tool` schema 生成

Spring AI 的 `@Tool` 注解给 LLM 生成 function-calling JSON schema。OryxOS 用这个填 prompt 里的 tool 列表：

```java
@Tool(description = "GET 一个白名单 URL")
String httpGet(@P("url") String url) { ... }
```

Schema 作为 `tools: [...]` 进 prompt。LLM 响应 `tool_calls`，我们的 `ToolExecutor` 跑，结果回到 session。

---

## OryxOS 不用 Spring AI 的什么

### ❌ Agent 抽象

Spring AI 有 `AgentExecutor`、`FunctionCallingAgent` 等。它们会自动执行 tool 调用。

**为什么不用**：它们导致**重复 tool 调用**。Agent 调 tool，我们的 `ToolExecutor` 也调 tool，LLM 看到两次结果。审计日志只捕获两次里的一次（我们的），系统看起来是坏的。

**我们的替代**：`oryxos-core/ReActLoop` 自实现，几十行 Java。Tool 调度完全归 `ToolExecutor`。

### ❌ 自动 tool 执行

Spring AI 的 `ChatModel` 可以配 tool callback resolver 自动执行 tool 调用。**这是同一问题，更严重**——自动执行跑 tool 加上我们的 `ToolExecutor` 跑 tool。

**修复**：不配自动执行。`ChatModel` 只生成 `tool_calls`，我们的 `ReActLoop` 解析它们，调 `ToolExecutor.execute(...)`。

### ❌ 内置对话记忆

Spring AI 有 `ChatMemory` / `MessageWindowChatMemory`。我们不用，因为：

- 不写 `sessions` SQLite 表（无审计）。
- 跟我们的 `MemoryService` 三层门面不集成。
- 不支持 CORE / ARCHIVE scope 分裂。

**我们的替代**：`oryxos-core/SessionManager`（SQLite 支撑）+ `oryxos-memory/MemoryService`（三层门面）。

### ❌ 内置 RAG / 向量记忆

Spring AI 有 `VectorStore` 集成（PgVector、Chroma、Qdrant 等）。向量记忆**不在核心阶段**——见宪法。

---

## 自动装配

依赖 `oryxos-boot` 时，Spring AI starter 在 classpath 上。OryxOS 声明每个 provider 的 `ChatModel` `@Bean`，但**不**声明任何 `AgentExecutor` 或 tool callback resolver。

启动后验证一下 Spring 上下文：

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

`toolCallbacks: []` 和 `agents: []`——Spring AI 的自动执行是关的。只有我们的 `ReActLoop` 调 tool。

---

## Spring AI Alibaba 呢？

Spring AI Alibaba 是 Spring AI 的扩展，加了 DashScope 原生模型（Qwen 等）和中国 AI 官方扩展。OryxOS 用它：

- `DashScopeChatModel`（Qwen、通义等）
- `DeepSeekChatModel`（Spring AI Alibaba 的 DeepSeek 连接器）

Kimi（月之暗面）没有官方 Spring AI Alibaba 连接器，用自定义 adapter。

---

## 下一步

| 目标                                                         | 看到什么                                          |
| ------------------------------------------------------------ | ------------------------------------------------- |
| [MCP 集成](./mcp)                                            | Model Context Protocol 客户端/服务端              |
| [LangChain4j 集成](./langchain4j)                            | 社区阶段替代方案                                  |
| [Java SDK](../sdk/java)                                      | 编程式 API                                         |
| [给工程师](../for-engineer)                                  | 构建、部署、扩展                                  |