---
title: LangChain4j 集成
description: 社区阶段替代——LangChain4j 当 Agent 框架。
---

# LangChain4j 集成

> ⚠️ **状态**：社区阶段。核心阶段和扩展阶段都不官方支持。

[LangChain4j](https://github.com/langchain4j/langchain4j) 是 LangChain 的 Java 移植。给 JVM 语言提供 Agent、Tool、Memory、RAG 原语。

OryxOS **不**基于 LangChain4j——我们直接基于 Spring AI + 自实现 ReAct 循环。本页讲怎么**集成** LangChain4j Agent 到 OryxOS，给已经有 LangChain4j 代码库的用户。

---

## 为什么内部不用 LangChain4j

- 我们用 Spring AI Alibaba 做 LLM provider 抽象（更原生的 Java/Spring 集成）。
- 我们的 ReAct 循环自实现（几十行 Java）。
- 我们的 `MemoryService` 是三层门面，跟 LangChain4j 的 `ChatMemory` 模型不匹配。
- 我们的审计表（`tool_invocations`、`llm_calls`）由调用路径本身写入——不用事件监听器补丁。

新项目用 [Spring AI 集成](./spring-ai)。

---

## 什么时候用这个集成

你已经有 LangChain4j 代码库，想：

1. 从 LangChain4j 跑 OryxOS Agent（从 LangChain4j 调出到 OryxOS）。
2. 从 OryxOS 跑 LangChain4j Agent（从 OryxOS 调出到 LangChain4j，通过自定义 Tool）。

两个方向都支持。

---

## 从 LangChain4j 调 OryxOS

加一个 LangChain4j 自定义 tool，包 OryxOS REST API：

```java
@Tool("调一个 OryxOS Agent")
public String invokeOryxosAgent(
    @P("agentName") String agentName,
    @P("message") String message
) {
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:8080/api/v1/agents/" + agentName + "/invoke"))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString("{\"message\":\"" + message + "\"}"))
        .build();
    try {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, BodyHandlers.ofString());
        return resp.body();
    } catch (Exception e) {
        return "error: " + e.getMessage();
    }
}
```

LangChain4j Agent 现在能按名字调任何 OryxOS Agent。

## 从 OryxOS 调 LangChain4j

实现一个自定义 `OryxTool`，包 LangChain4j Agent：

```java
@Component
public class LangChain4jTool implements OryxTool {
    private final Assistant langChainAgent;

    public LangChain4jTool(Assistant agent) {
        this.langChainAgent = agent;
    }

    @Override
    public String name() { return "langchain_agent"; }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
            .name(name())
            .description("调一个 LangChain4j Agent")
            .parameter("input", "string", "Agent 的输入", true)
            .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ProfileContext ctx) {
        sandbox.enforce(ActionType.HTTP_REQUEST, "internal://langchain");   // 逻辑占位
        String input = call.arg("input");
        String response = langChainAgent.chat(input);
        return ToolResult.ok(response);
    }
}
```

OryxOS Agent 现在能按名字调你的 LangChain4j Agent。

---

## 记忆兼容性

LangChain4j 有自己的 `ChatMemory` 实现。它们不写 OryxOS 的 `sessions` SQLite 表。

如果你要 OryxOS 审计 LangChain4j 调用：

1. 把每次 LangChain4j 调用包到自定义 tool 里（见上）。
2. tool 的 `execute(...)` 走 `ToolExecutor` → 自动写审计行。
3. 结果回到 OryxOS Session，也被审计。

LangChain4j Agent 内部状态（它自己的 `ChatMemory`）**不**被 OryxOS 审计。如果要，把对话复制成 OryxOS Session。

---

## 什么时候官方化

扩展阶段之后，社区阶段。如果你想推动，开 issue。

---

## 下一步

| 目标                                                         | 看到什么                                          |
| ------------------------------------------------------------ | ------------------------------------------------- |
| [Spring AI 集成](./spring-ai)                                 | 官方集成（新项目推荐）                             |
| [MCP 集成](./mcp)                                            | Model Context Protocol 客户端/服务端              |
| [Java SDK](../sdk/java)                                      | 编程式 API                                         |