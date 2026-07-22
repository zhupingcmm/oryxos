---
title: LangChain4j Integration
description: Community-stage alternative — LangChain4j as an Agent framework.
---

# LangChain4j Integration

> ⚠️ **Status**: Community stage. Not officially supported in the core or extension stages.

[LangChain4j](https://github.com/langchain4j/langchain4j) is the Java port of LangChain. It provides Agents, Tools, Memory, and RAG primitives for JVM languages.

OryxOS is **not** built on LangChain4j — we built directly on Spring AI + a self-implemented ReAct loop. This page documents how to **integrate** LangChain4j Agents with OryxOS, for users who have existing LangChain4j codebases.

---

## Why we don't use LangChain4j internally

- We use Spring AI Alibaba for LLM provider abstraction (more native Java/Spring integration).
- Our ReAct loop is self-implemented (~tens of lines of Java).
- Our `MemoryService` is a three-layer facade that doesn't fit LangChain4j's `ChatMemory` model.
- Our audit tables (`tool_invocations`, `llm_calls`) are written from the same code path as the call — no event listener patching required.

For new projects, use the [Spring AI integration](./spring-ai) instead.

---

## When to use this integration

You have an existing LangChain4j codebase and want to:

1. Run an OryxOS Agent from a LangChain4j tool (call out from LangChain4j to OryxOS).
2. Run a LangChain4j Agent from OryxOS (call out from OryxOS to LangChain4j via a custom Tool).

Both directions are supported.

---

## Calling OryxOS from LangChain4j

Add a custom LangChain4j tool that wraps the OryxOS REST API:

```java
@Tool("Invoke an OryxOS Agent")
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

The LangChain4j Agent can now call any OryxOS Agent by name.

## Calling LangChain4j from OryxOS

Implement a custom `OryxTool` that wraps a LangChain4j Agent:

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
            .description("Call a LangChain4j Agent")
            .parameter("input", "string", "The input to the Agent", true)
            .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ProfileContext ctx) {
        sandbox.enforce(ActionType.HTTP_REQUEST, "internal://langchain");   // logical
        String input = call.arg("input");
        String response = langChainAgent.chat(input);
        return ToolResult.ok(response);
    }
}
```

The OryxOS Agent can now call your LangChain4j Agent by name.

---

## Memory compatibility

LangChain4j has its own `ChatMemory` implementations. They don't write to OryxOS's `sessions` SQLite table.

If you want OryxOS to audit LangChain4j calls:

1. Wrap every LangChain4j invocation in a custom tool (see above).
2. The tool's `execute(...)` method goes through `ToolExecutor` → audit row written automatically.
3. The result goes back into the OryxOS Session, also audited.

The LangChain4j Agent's internal state (its own `ChatMemory`) is **not** audited by OryxOS. If you need that, replicate the conversation to OryxOS as a Session.

---

## When this will become official

Community stage, after the extension stage ships. If you'd like to drive it, open an issue.

---

## Where to go next

| Destination                                                  | What you'll find                                       |
| ------------------------------------------------------------ | ------------------------------------------------------ |
| [Spring AI integration](./spring-ai)                          | Official integration (recommended for new projects)    |
| [MCP integration](./mcp)                                     | Model Context Protocol client/server                   |
| [Java SDK](../sdk/java)                                       | Programmatic API                                       |