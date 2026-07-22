---
title: Features — Detailed Reference
description: Reference for the five core capabilities of OryxOS.
---

# Features

Detailed reference for the five core capabilities of OryxOS.

> This page assumes you've read [Architecture](./architecture). It focuses on the operational reference for each capability.

---

## 1. LLM Provider Routing (`oryxos-provider`)

### Concept

Providers are explicit `name → ChatModel` mappings, registered at startup from `application.yml`. There is no container-type scanning.

### Why explicit

Spring AI's default behaviour is to register each `ChatModel` bean in a `Map<Class<? extends ChatModel>, ChatModel>` keyed by implementation type. When you have two providers of the same type (e.g., two OpenAI-compatible endpoints), the second one collides with the first. OryxOS sidesteps this by using a string name as the key.

### Configuration

```yaml
oryxos:
  providers:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com
      model: deepseek-chat
      temperature: 0.7

    kimi:
      api-key: ${KIMI_API_KEY}
      base-url: https://api.moonshot.cn
      model: moonshot-v1-8k

    qwen:
      api-key: ${QWEN_API_KEY}
      base-url: https://dashscope.aliyuncs.com/compatible-mode
      model: qwen-plus
```

### Reference in AGENT.md

```yaml
provider:
  name: deepseek
  model: deepseek-chat   # optional override of provider default
```

`ProviderService` exposes:

```java
ChatModel get(String name);                    // throws if not registered
void register(String name, ChatModel model);   // startup only
Set<String> names();                           // for GET /api/v1/profiles
```

### Audit

Every `provider.call(...)` writes an `llm_calls` row:

```sql
SELECT created_at, profile_name, provider, model,
       prompt_tokens, completion_tokens, total_tokens, duration_ms
FROM llm_calls
ORDER BY created_at DESC LIMIT 10;
```

---

## 2. ReAct Loop (`oryxos-core`)

### Concept

The Reason+Act engine is **self-implemented**. Spring AI's `Agent` abstraction is **not** used because it auto-executes tools, which causes duplicate invocations.

### Loop shape

```java
for (int i = 0; i < profile.maxIterations(); i++) {
    Prompt prompt = promptBuilder.build(session, profile);
    LlmResponse resp = providerService.call(profile, prompt);
    session.append(resp);
    if (!resp.hasToolCall()) return session.lastMessage();
    ToolResult r = toolExecutor.execute(profile, resp.toolCall());
    session.append(r);
}
throw new MaxIterationsExceeded(profile.maxIterations());
```

### PromptBuilder — 4 parts, in order

| # | Part | Source |
|---|------|--------|
| 1 | System prompt | `AGENT.md` body + Bootstrap (`AGENTS.md` / `SOUL.md` / `USER.md`) + current date/time |
| 2 | Memory injection | `MemoryService.findByKeyword(query)` (top-k) |
| 3 | Conversation history | last `max_history_turns` messages, truncated |
| 4 | Tool list | function-calling schema for profile's `tools` |

### ProfileContext (ThreadLocal)

Tools don't know which Agent invoked them. `ProfileContext` is a ThreadLocal set by `AgentService` and cleared in `finally`:

```java
ProfileContext.set(currentProfile);
try {
    return toolExecutor.execute(profile, resp.toolCall());
} finally {
    ProfileContext.clear();
}
```

### `max_iterations`

Default `10`. Profile-overridable. The cap prevents runaway loops. When the loop exhausts iterations without a final answer, `MaxIterationsExceededException` is thrown and the partial session is persisted.

---

## 3. Three-layer Memory (`oryxos-memory`)

### Concept

`MemoryService` is a unified facade. ReAct only sees `MemoryService` — never the backend directly.

### Three layers

```
MemoryService                ←─── ReAct only sees this
  ├─ SessionManager          ←─── short-term, per Session
  └─ LongTermMemoryStore     ←─── long-term, interface
       ├─ MarkdownMemoryStore
       ├─ SqliteMemoryStore
       └─ Mem0MemoryStore
```

### Interface contract

Four rules, non-negotiable:

1. **No cache** — every `read` goes to the backing store.
2. **CORE scope is never truncated** — user-critical data lives forever.
3. **Agent chooses scope** — `write(key, value, CORE | ARCHIVE)`.
4. **`recallByKeyword`** is keyword-only (no embeddings in core stage).

### Default backend: MarkdownMemoryStore

```
.oryxos/memory/
└── MEMORY.md
```

The file is divided into two sections by the Agent:

```markdown
# MEMORY.md

## CORE
- user.prefers.format = table
- user.timezone = Asia/Shanghai

## ARCHIVE
### 2025-07-21
- tech-digest: <full text>
### 2025-07-20
- github-trending: <top 10 repos>
```

CORE is read on every prompt construction. ARCHIVE is read on explicit `recall` calls.

### Tools for the Agent

The Agent sees two memory tools in its function-calling schema:

- `memory_write(key, value, scope)` — persist to CORE or ARCHIVE.
- `memory_recall(keyword, scope?)` — keyword search, returns matching entries.

### Memory is day-one

`Session` writes to SQLite from US-1. `MEMORY.md` is persisted immediately on every `memory_write`. There is no in-flight state.

---

## 4. Plugin Tools + Sandbox (`oryxos-tool`)

### Concept

Tools are the Agent's hands. `OryxTool` is the interface, registered in `ToolRegistry`. Three extension tiers, same interface.

### Three extension tiers

| Tier | How | Use case |
|------|-----|----------|
| Zero-code | `AGENT.md` + MCP servers | Most cases — model discovers tools dynamically |
| Light-code | Custom MCP server | Cross-language, no Java |
| Heavy-code | `@OryxTool` Java bean | Performance-critical, deep Spring integration |

### Built-in tools (core stage ships 9)

| Tool | Purpose | Sandbox action |
|------|---------|----------------|
| `read_file` | Read a file from `.oryxos/` | `FILE_READ` |
| `write_file` | Write a file under `.oryxos/` | `FILE_WRITE` |
| `shell` | Execute a shell command | `SHELL_COMMAND` |
| `http_get` | GET an HTTP URL | `HTTP_REQUEST` |
| `http_post` | POST to an HTTP URL | `HTTP_REQUEST` |
| `memory_read` | Read from long-term memory | (internal) |
| `memory_write` | Write to long-term memory | (internal) |
| `notify` | Push to outbound channel | (uses channel's URL) |
| `list_agents` | Discover other profiles | (internal) |

### Sandbox

`Sandbox.enforce(SandboxAction)` is called before every tool execution. Core stage: `WhitelistSandbox`.

```java
public class WhitelistSandbox implements Sandbox {
    @Override
    public void enforce(SandboxAction action) {
        if (!whitelist.matches(action)) {
            throw new SandboxViolationException(action);
        }
    }
}
```

Violations throw `SandboxViolationException`, translated to HTTP 403 / CLI exit code 2, and captured in `tool_invocations.error_message`.

### MCP integration

`.oryxos/mcp_servers.yaml`:

```yaml
servers:
  - name: github
    transport: stdio
    command: npx
    args: ["-y", "@modelcontextprotocol/server-github"]
    env:
      GITHUB_TOKEN: ${GITHUB_TOKEN}

  - name: filesystem
    transport: http
    url: http://localhost:3001/mcp
```

Tools discovered from MCP servers are added to the Agent's available tool list. They share the same `OryxTool` interface, so the ReAct loop doesn't know or care that they came from MCP.

---

## 5. REST API (`oryxos-web`)

### 10 production endpoints

```
Sessions
  POST   /api/v1/sessions
  POST   /api/v1/sessions/{id}/messages
  GET    /api/v1/sessions/{id}
  DELETE /api/v1/sessions/{id}

Agent
  POST   /api/v1/agents/{name}/invoke

Discovery
  GET    /api/v1/profiles
  GET    /api/v1/memory
  GET    /api/v1/tools

System
  GET    /api/v1/health
  GET    /api/v1/info
```

### Spring MVC + virtual threads

The REST API runs on Spring MVC 6+ with JDK 21 virtual threads enabled. Spring Boot's `spring.threads.virtual.enabled=true` is set in `application.yml`.

### OpenAPI

springdoc-openapi auto-generates the spec at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`.

### No auth, no rate limit — by design

Core stage endpoints are open. Multi-tenancy, SSO, RBAC, and rate limiting are extension-stage features. **Do not expose core-stage OryxOS to the public internet.**

---

## Cross-cutting: Audit

`tool_invocations` and `llm_calls` tables are written inside the same call path that returns the result. There is no async outbox. SQL truth at all times.

```sql
-- Tool call: who, what, when, how long, success/fail
SELECT * FROM tool_invocations
WHERE created_at > datetime('now', '-1 day')
  AND success = 0;  -- failures only

-- LLM call: cost
SELECT profile_name, provider, model,
       SUM(total_tokens) AS tokens,
       COUNT(*) AS calls
FROM llm_calls
WHERE created_at > datetime('now', '-7 day')
GROUP BY profile_name, provider, model;
```

---

## Where to go next

| Destination                          | What you'll find                                       |
| ------------------------------------ | ------------------------------------------------------ |
| [Scenarios](./scenarios)             | 6 enterprise use cases                                 |
| [Architecture](./architecture)       | Layer-by-layer walkthrough                             |
| [For Engineers](./for-engineer)      | Build, deploy, extend                                  |