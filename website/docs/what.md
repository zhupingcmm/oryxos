---
title: What is OryxOS
description: OryxOS — Enterprise Agent OS written in Java on Spring Boot.
---

# What is OryxOS

**OryxOS** is an enterprise-grade **Agent Operating System** built in Java on Spring Boot. Install it on your own Kubernetes cluster or servers, run multiple business Agents (operations, customer service, HR, sales, knowledge management) on top of it, and share a single set of capabilities: LLM provider routing, ReAct reasoning loop, three-layer memory, plugin tools with sandboxing, and a REST API for system integration.

**Data never leaves your infrastructure. No cloud lock-in. Open source under MIT.**

## Two foundational problems

Every enterprise multi-agent system hits the same two problems, regardless of language or framework.

**Problem 1: Agents cannot run inside the existing stack.**

Banks, governments, telcos, energy companies — the IT backbone is Java. Python- and Node-based Agent frameworks — however advanced — cannot plug into Nacos, Sentinel, SkyWalking, Arthas, Prometheus+Grafana. The surrounding enterprise infrastructure is complete, but the Agent layer is missing.

**Problem 2: Agents cannot be audited or kept private.**

Public-cloud SaaS ships your prompts to vendor domains. Every tool call and every LLM call must be traceable for compliance. "Trust us, we have logs" is not enough — you need SQL-queryable audit tables from day one.

**OryxOS solves both.**

## How OryxOS solves them

### Java-native runtime kernel

OryxOS is a single Spring Boot 3.x application on JDK 21, packaged as a single executable fat JAR. It slots into existing Java infrastructure without rework. GraalVM Native Image support is on the extension-stage roadmap.

### Audit-grade persistence from day one

Two audit tables — `tool_invocations` and `llm_calls` — are written from the first user story. Every tool invocation records success/error/duration; every LLM call records provider/model/tokens/duration. Compliance teams can replay any past call with a single SQL query.

### Zero-code agent definition

Business users define agents by writing files, not Java code:

```
.oryxos/agents/<name>/
├── AGENT.md            # frontmatter = profile, body = task instructions
├── skills/             # optional sub-instructions (read on-demand)
└── scripts/            # optional scripts (run via shell tool)
```

The model fetches `skills/` and `scripts/` on demand via built-in tools — no preloading. This is progressive disclosure inside one agent.

### App-layer sandbox

Tools do not bypass policy. Every file/shell/HTTP call passes through `Sandbox.enforce(...)` against an application-layer whitelist. Failure throws `SandboxViolationException` and lands in the audit log. (The extension stage upgrades to container / microVM isolation, but the interface stays the same.)

## Core concepts

### Profile

A `Profile` is a named, declarative description of an Agent — provider, model, available tools, schedules, notify channels. Profiles live in `.oryxos/agents/<name>/AGENT.md` (frontmatter = profile, body = system prompt).

### Session

A `Session` is one ongoing conversation with one Agent. Sessions are persisted to SQLite and survive restart. They include the full message history plus the `MAX_ITERATIONS` cap from the Profile.

### Memory

The `MemoryService` is a three-layer facade:

- **Session memory** — short-term, lives with the Session.
- **Long-term memory** — `MEMORY.md` file, Markdown by default.
- **Pluggable backends** — `MarkdownMemoryStore` (default), `SqliteMemoryStore`, `Mem0MemoryStore` (semantic).

### Tool

Three tiers, same `OryxTool` interface:

- **Zero-code** — `AGENT.md` + MCP servers (the model discovers tools dynamically).
- **Light-code** — custom MCP server, no Java.
- **Heavy-code** — `@OryxTool`-annotated Java bean.

### Sandbox

`Sandbox.enforce(SandboxAction)` — checked at every `ToolExecutor.execute(...)`. Implementation in core stage is `WhitelistSandbox` (path/pattern matching). Upgrade path: container → microVM, interface unchanged.

## Comparison

| Dimension         | **OryxOS**                                  | OpenClaw                | Hermes Agent           | Dify / Coze          |
| ----------------- | ------------------------------------------- | ----------------------- | ---------------------- | -------------------- |
| Language          | **Java**                                    | Node.js                 | Python                 | Python / TS          |
| Target            | **Regulated enterprise**                    | Consumer / small team   | Team / small org       | Business users       |
| Deployment        | **Single binary, on-prem**                  | On-prem                 | On-prem                | Cloud-hosted SaaS    |
| Audit trail       | **Built-in (day-one DB writes)**            | ❌ (CVE-prone)         | Partial                | ✅ (SaaS)           |
| Ecosystem fit     | **Java/Spring/Cloud-native**                | JS/TS                   | Python data stack      | Cross-platform       |
| Java AI framework | Built on Spring AI Alibaba                  | N/A                     | LangChain              | LangChain            |
| MCP support       | Client (core) + Server (extension)          | ✅                     | ✅                    | ✅                  |
| Product form      | **Runtime kernel + config**                 | Runtime                 | Runtime                | Visual workflow      |

## Design principles

**The runtime kernel is the foundation, not the product.** The differentiated governance layer (multi-tenancy, SSO, full audit, Tool Policy, Web dashboard) is the end state, built on top of the core kernel. Don't confuse the two stages.

**Audit is day-one, not retroactive.** `tool_invocations` and `llm_calls` are written from US-1, not added at the end. Compliance asks "show me what happened last Tuesday" — you answer with SQL, not log scraping.

**One directory = one Agent.** `AGENT.md` + optional `skills/` + optional `scripts/` + optional `REFERENCE.md`. The model reads files on demand. We borrow progressive disclosure from Anthropic Agent Skills but interpret it as "one agent = one directory."

**One engine, three trigger sources.** CLI (human-push), REST API (human-push), `AgentScheduler` (clock-push) all converge on `AgentService.process(Session, String)`. The ReAct loop doesn't care who started it.

## What OryxOS is not

- ❌ Not a SaaS. You run it on your own infrastructure.
- ❌ Not a framework. It's a runtime. You define Agents in YAML, not in Java.
- ❌ Not a multi-tenant product (yet). Core stage is single-tenant; multi-tenancy is in the extension stage.
- ❌ Not a replacement for HTTP/gRPC between always-online services.
- ❌ Not a data pipeline or event log.

## Where to go next

| Destination                                | What you'll find                                                        |
| ------------------------------------------ | ----------------------------------------------------------------------- |
| [For Engineers](./for-engineer)           | Build the runtime from source; understand the 9 Maven modules            |
| [For Agents](./for-agent)                  | The "AGENT.md" manual — how to define an agent without writing Java     |
| [Quick Start](./quick-start)               | Run the three demos locally in under 10 minutes                          |
| [Architecture](./architecture)             | Layer-by-layer walkthrough of the runtime                                |
| [Features](./features)                     | Detailed reference for the 5 core capabilities                            |
| [Scenarios](./scenarios)                   | 6 enterprise use cases with working code                                |
| [Roadmap](./roadmap)                       | Core stage vs extension stage vs community stage                          |