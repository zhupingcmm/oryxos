---
title: Constitution — The Seven Principles
description: Seven non-negotiable principles that govern OryxOS development.
---

# Constitution

Seven non-negotiable principles that govern OryxOS development. AI coding agents must follow these without exception. They are **not** subject to unilateral modification by individual contributors — changes require maintainer review.

> Source: [`docs/AiProgrammingGuide.md` §3.2](https://github.com/oryxos/oryxos/blob/main/docs/AiProgrammingGuide.md) in the project repo.

---

## Principle 1 — Single Java/Spring Boot runtime

OryxOS is a **JDK 21 + Spring Boot 3.x** single-application, packaged as a Maven multi-module project (9 modules). One binary to deploy, one process to monitor.

- ❌ No microservice explosion — the runtime is one JVM.
- ❌ No polyglot persistence in the core stage — SQLite only.
- ❌ No alternative deployment forms (Docker Swarm, Nomad) in the core stage — K8s + fat JAR.

Extension stage may add multi-node HA via Nacos / ETCD, but the core is single-binary.

---

## Principle 2 — Five core capabilities first

The 5 core capabilities (Provider / ReAct / Memory / Tool / REST) **must be complete and demonstrable** before any extension-stage work begins.

The extension stage (multi-tenancy, SSO, full audit, Tool Policy, Web dashboard) is layered **on top** of the core kernel. Do not start implementing extension-stage features during the core stage.

---

## Principle 3 — Self-implemented ReAct loop

The Reason+Act engine is self-implemented in `oryxos-core/ReActLoop`. It is **not** delegated to Spring AI's `AgentExecutor` / `FunctionCallingAgent`.

Reason: Spring AI's Agent abstraction auto-executes tool calls. Combined with our `ToolExecutor`, every tool gets invoked twice. Self-implementation is the only fix.

---

## Principle 4 — Spring AI used at half-strength

Spring AI is used **only** for:

- ✅ Provider abstraction (multiple LLM vendors)
- ✅ Protocol conversion (Anthropic / OpenAI / DashScope wire formats)
- ✅ `@Tool` schema generation (function-calling payload)

It is **not** used for:

- ❌ Agent abstraction
- ❌ Auto tool execution
- ❌ Built-in ReAct loop

Disable Spring AI's auto tool execution in the configuration. There is no path that bypasses `ToolExecutor`.

---

## Principle 5 — Three-tier Tool extension

Tools are extended via three tiers, all using the same `OryxTool` interface:

- **Zero-code** — `AGENT.md` + MCP servers (preferred for most cases)
- **Light-code** — custom MCP server (cross-language, no Java)
- **Heavy-code** — `@OryxTool` Java bean (performance-critical)

**Tool-related code lives in `oryxos-tool`** — do not split into multiple modules (`builtin`, `skill`, `mcp`, etc.). One module, one concern.

---

## Principle 6 — SQLite + MEMORY.md file storage

The core stage uses:

- **SQLite** for structured data (sessions, tool_invocations, llm_calls, scheduled_tasks, task_executions)
- **`MEMORY.md` file** for long-term memory (default MarkdownMemoryStore)

`tool_invocations` and `llm_calls` are written to SQLite **from day one** — this is the audit-grade foundation. There is no "we'll add logging later" path. The audit tables are populated by `ToolExecutor` and `ProviderService` directly.

Vector memory is **not** in the core stage. Pluggable vector backends (LanceDB Java, pgvector, JVector) are an extension-stage feature.

> ⚠️ **SQLite caveat**: `ALTER TABLE` is limited. `hibernate.ddl-auto=update` will not handle complex migrations. Future schema evolution will need Flyway / Liquibase.

---

## Principle 7 — Runnable demo after every user story

Every user story ends with a **runnable demo** that exercises the new capability end-to-end. **Demo-runnable > code-perfect.**

After each user story, run `/speckit.analyze` to verify no drift from the constitution. This is mandatory — drift prevention is more important than velocity.

---

## Three common pitfalls

These are the bugs AI agents most often introduce. Avoid them.

### Pitfall 1 — Enable Spring AI's auto tool execution

If you flip the auto-execution switch, every tool gets invoked twice — once by Spring AI, once by `ToolExecutor`. The first call has no audit row. The second call has no schema. The system is broken.

**Symptom**: tool appears in the response payload twice.

**Fix**: keep auto-execution off. Tool scheduling is fully owned by `ReActLoop` + `ToolExecutor`.

### Pitfall 2 — Provider by container type scanning

If you use `Map<Class<? extends ChatModel>, ChatModel>` keyed by implementation type, two providers of the same type (e.g., two OpenAI-compatible endpoints) collide. The second one shadows the first.

**Fix**: maintain an explicit `Map<String name, ChatModel>` and `ProviderService.get(String name)`. The profile's `provider.name` is the lookup key.

### Pitfall 3 — Audit logs only, no DB writes

If `tool_invocations` and `llm_calls` are written only to log files (and not to the SQLite tables), compliance teams cannot query them. Log scraping is not SQL.

**Fix**: write to the SQLite tables from the same call path that returns the result. There is no async outbox. No "we'll backfill later."

---

## What's not in the constitution

These are intentionally absent from the core stage constitution. They are planned for the extension stage:

- Authentication / SSO / RBAC
- Multi-tenancy
- Profile create / update via API
- Agent create / update via API
- Streaming SSE responses
- Vector memory
- Adaptive routing
- Cluster HA
- Web dashboard
- Tool Policy

The interface stays stable. The extension stage is a bean swap.

---

## Modifying the constitution

The constitution is **not** subject to unilateral modification. Changes require:

1. A GitHub issue with the proposed change + rationale.
2. Discussion in maintainer review.
3. A merged PR to `docs/AiProgrammingGuide.md` §3.2.

AI coding agents **must not** modify the constitution on their own.

---

## Where to go next

| Destination                              | What you'll find                                       |
| ---------------------------------------- | ------------------------------------------------------ |
| [Architecture](./architecture)           | Layer-by-layer walkthrough                             |
| [Features](./features)                   | Detailed reference for the 5 core capabilities          |
| [Roadmap](./roadmap)                     | Core / Extension / Community stages                    |