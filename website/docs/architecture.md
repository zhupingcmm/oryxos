---
title: Architecture — Layer-by-Layer Walkthrough
description: How the OryxOS runtime is layered — entry, engine, capability, foundation, external.
---

# Architecture

OryxOS is a single Spring Boot 3.x application on JDK 21, packaged as a single executable fat JAR. The runtime is layered into four internal layers + one external layer.

![OryxOS Architecture](/flow.svg)

---

## Layer 1 — Entry Layer

Three trigger sources, all human- or clock-push, all converge on the same `AgentService.process(Session, String)`:

| Source | Human / Clock | Endpoint | Use case |
|--------|---------------|----------|----------|
| **CLI Channel** | Human-push | `oryxos chat --profile <name>` | Interactive REPL, debugging |
| **REST Web Service** | Human-push | `POST /api/v1/agents/{name}/invoke` | System integration, dashboards |
| **AgentScheduler** | Clock-push | cron in `AGENT.md` schedules | Daily reports, periodic jobs |

The Entry Layer does not contain any business logic — it just packages a `Session` + a `String message` and calls `AgentService`.

## Layer 2 — Engine Layer

The ReAct reasoning engine. **All four components live in `oryxos-core`:**

### `AgentService` — the single entry

```java
public class AgentService {
    public AgentResponse process(Session session, String userMessage) {
        return reactLoop.run(session, userMessage, profile);
    }
}
```

`ReActLoop` doesn't care whether the message came from CLI, REST, or Scheduler — they all land here.

### `ReActLoop` — the loop

```java
for (int i = 0; i < profile.maxIterations(); i++) {
    Prompt prompt = promptBuilder.build(session, profile);
    LlmResponse resp = providerService.call(profile, prompt);
    session.append(resp);
    if (!resp.hasToolCall()) break;
    ToolResult r = toolExecutor.execute(profile, resp.toolCall());
    session.append(r);
}
return session.lastMessage();
```

Tens of lines of Java. The `max_iterations` cap (default 10, profile-overridable) prevents infinite loops.

### `PromptBuilder` — four parts, in order

1. **System prompt** — `AGENT.md` body + Bootstrap files (`AGENTS.md` / `SOUL.md` / `USER.md`) + current date/time.
2. **Memory injection** — relevant entries from `MemoryService.findByKeyword(...)`.
3. **Conversation history** — last `max_history_turns` (default 20), truncated.
4. **Tool list** — function-calling schema for tools the profile can use.

### `ToolExecutor` — gated execution

```java
public ToolResult execute(Profile profile, ToolCall call) {
    sandbox.enforce(toSandboxAction(call));          // ← 1. check policy
    Tool tool = toolRegistry.get(call.name());       // ← 2. resolve
    ToolResult r = tool.execute(call, profileCtx);   // ← 3. run
    audit.persist(profile, call, r);                 // ← 4. record (day-one)
    return r;
}
```

The audit write at step 4 is **not optional** — there is no path that bypasses it.

## Layer 3 — Capability Layer

Three capabilities the engine calls into on every iteration:

### `ProviderService`

- Explicit `name → ChatModel` mapping (no container type scanning).
- Registered at startup from `application.yml` under `oryxos.providers.<name>`.
- `ProviderService.get(name)` returns the `ChatModel`.
- Each call is wrapped: it calls the model **and** writes an `llm_calls` row.

### `MemoryService`

The three-layer facade:

```
MemoryService
  ├─ SessionManager       (in-memory + SQLite per Session)
  └─ LongTermMemoryStore  (interface — 3 backends)
       ├─ MarkdownMemoryStore   (default — .oryxos/memory/MEMORY.md)
       ├─ SqliteMemoryStore     (structured — memory_entries table)
       └─ Mem0MemoryStore       (semantic — self-hosted Mem0)
```

Four contracts of the interface:

1. **No cache** — every read goes to the backing store.
2. **CORE scope is never truncated** — user-critical data lives forever.
3. **Agent chooses scope** — `write(key, value, CORE | ARCHIVE)`.
4. **`recallByKeyword`** is keyword-only, not semantic.

### `ToolRegistry` + `Sandbox` + `NotifyChannelAdapter`

- `ToolRegistry` — name → `OryxTool` map. Auto-discovered at startup via `@Component`.
- `Sandbox` — interface, currently `WhitelistSandbox`. Upgrade path: container, microVM.
- `NotifyChannelAdapter` — outbound push targets. Core stage: `WebhookNotifyAdapter` (WeCom / Feishu / DingTalk).

## Layer 4 — Foundation Layer

The user-editable workspace + audit-grade persistence + secret-safe config:

```
.oryxos/
├── agents/                  # Agent directories (AGENT.md + skills/ + scripts/)
├── memory/
│   └── MEMORY.md            # long-term memory (default Markdown)
├── sessions/                # runtime session data (also mirrored in SQLite)
├── logs/                    # structured JSON logs
├── mcp_servers.yaml         # MCP config
├── AGENTS.md                # Bootstrap: project-wide agent behavior
├── SOUL.md                  # Bootstrap: default agent persona
├── USER.md                  # Bootstrap: user preferences
└── oryxos.db                # SQLite (5 tables)
```

### SQLite — 5 tables, audit-grade

| Table              | Purpose                                                  | Day-one writes? |
| ------------------ | -------------------------------------------------------- | --------------- |
| `sessions`         | Session metadata + JSON conversation history              | ✅              |
| `tool_invocations` | Every tool call (success / error / duration)              | ✅ (audit)      |
| `llm_calls`        | Every LLM call (provider / model / tokens / duration)     | ✅ (cost / audit) |
| `scheduled_tasks`  | Registered cron jobs                                      | ✅              |
| `task_executions`  | Per-run history of scheduled tasks                        | ✅              |

**Caveat**: SQLite's `ALTER TABLE` is limited; `hibernate.ddl-auto=update` won't handle complex migrations. Future schema evolution will need Flyway / Liquibase.

### `ConfigLoader`

- Loads `application.yml`.
- Resolves `${ENV_VAR}` placeholders at load time.
- Loads `.oryxos/mcp_servers.yaml`.
- Loads every `.oryxos/agents/*/AGENT.md` and derives a `Profile`.

## External Layer

These sit **outside** the OryxOS process — OryxOS binds to none of them by default:

- **LLM provider APIs** — DeepSeek, Kimi, Qwen, Zhipu, Doubao, Anthropic, OpenAI, Ollama.
- **MCP servers** — external tool providers (stdio or HTTP transport).
- **Notify targets** — webhook URLs (WeCom, Feishu, DingTalk, Slack, generic webhook).

All external calls go through the capability layer, which means they're sandbox-checked and audited.

## Cross-cutting concerns

### `ProfileContext` (ThreadLocal)

`OryxTool.execute(ToolCall, ProfileContext)` doesn't know which Agent invoked it. `ProfileContext` is a `ThreadLocal` set by `AgentService` before calling `ToolExecutor`, and cleared in `finally`. Tools that need Agent context read it from there.

### Audit hooks

Both `tool_invocations` and `llm_calls` are written inside the same call path that returns the result. There is no async outbox, no fire-and-forget log. SQL truth.

### Virtual threads

JDK 21 virtual threads power the REST API (Spring MVC 6+) and the `AgentScheduler` job pool. Millions of concurrent Agent invocations on a single JVM is feasible for the core stage workloads (the three demos run comfortably on 256MB heap).

## What's not in the architecture

These are intentionally absent from the core stage. They are planned for the extension stage, layered **on top** of the core engine — not replacing it:

- ❌ Multi-tenant routing layer
- ❌ SSO / RBAC filter chain
- ❌ Audit query API + SIEM export
- ❌ Tool Policy (profile-level allow/deny)
- ❌ Web dashboard UI
- ❌ Cluster HA (Nacos / ETCD)
- ❌ Vector memory backend

The interface stays stable. The extension stage is a bean swap.

## Where to go next

| Destination                              | What you'll find                                       |
| ---------------------------------------- | ------------------------------------------------------ |
| [Features](./features)                   | Detailed reference for the 5 core capabilities          |
| [Scenarios](./scenarios)                 | 6 enterprise use cases                                 |
| [For Engineers](./for-engineer)          | Build, deploy, extend                                  |
| [Roadmap](./roadmap)                     | Core stage vs extension stage                          |