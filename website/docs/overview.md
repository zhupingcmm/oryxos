---
title: Overview — Design Rationale
description: Why OryxOS exists, what it solves, and how it's positioned.
---

# Overview

## What is OryxOS

OryxOS is an enterprise-grade **Agent OS** runtime kernel written in **Java** on **Spring Boot**. It runs on the customer's own Kubernetes cluster or servers, hosts multiple business Agents (operations, customer service, HR, sales, knowledge management), and shares a single set of capabilities: LLM provider routing, a self-implemented ReAct reasoning loop, three-layer memory, plugin tools with sandboxing, and a REST API for system integration.

It is positioned for **regulated enterprises** (banks, government, telecom, energy, healthcare) where:

- The surrounding IT infrastructure is Java (Nacos, Sentinel, SkyWalking, Arthas, Prometheus+Grafana).
- Data must stay on-premises.
- Every LLM and tool call must be auditable.
- The Agent layer cannot be cloud SaaS.

## Two foundational problems

### Problem 1 — Agents cannot run inside the existing stack

The Agent OS space has two strong incumbents: **OpenClaw** (Node.js, consumer-focused) and **Hermes Agent** (Python, team-focused). Both validate the model works. But neither targets the Java segment — and that segment is the largest in regulated enterprise IT.

OryxOS fills the Java gap. It brings proven Agent OS design into the Java/Spring ecosystem, where the surrounding enterprise infrastructure is already complete.

### Problem 2 — Agents cannot be audited or kept private

Public-cloud SaaS ships your prompts to vendor domains. Even when logs are available, they're opaque to your SIEM, your compliance team, your data residency audit. Trust based on "we have logs" is not enough.

OryxOS writes `tool_invocations` and `llm_calls` to **your own SQLite database** from day one. SQL-queryable, copy-friendly, joins against your other audit tables.

## How OryxOS solves them

### Java-native runtime

- JDK 21 + Spring Boot 3.x. Single fat JAR.
- Plugs into existing Nacos / Sentinel / SkyWalking / Prometheus infrastructure.
- Virtual threads for high concurrency.

### Self-implemented ReAct loop

- The reason+act engine is ~tens of lines of Java in `ReActLoop`.
- Spring AI's Agent abstraction is **not** used (it auto-executes tools, leading to duplicate calls).
- Spring AI is used for: Provider abstraction, protocol conversion, `@Tool` schema generation. That's it.

### Audit from day one

```sql
-- Who called what tool when?
SELECT created_at, profile_name, tool_name, success, duration_ms
FROM tool_invocations
WHERE created_at > datetime('now', '-1 day');

-- What did the LLM see and how much did it cost?
SELECT created_at, profile_name, provider, model, prompt_tokens, completion_tokens, total_tokens
FROM llm_calls
WHERE created_at > datetime('now', '-1 day');
```

These tables are populated by `ToolExecutor` and `ProviderService` — there is no way to "forget to log."

### App-layer sandbox

`Sandbox.enforce(SandboxAction)` is called before every tool execution. The core-stage implementation is `WhitelistSandbox` (path / URL pattern matching). Violations throw `SandboxViolationException`, captured by the global exception handler and the audit log.

The extension stage upgrades to container (namespace + cgroups + seccomp) and microVM (Firecracker / Kata / gVisor). **The interface stays the same** — the upgrade is a bean swap.

### Zero-code agent definition

```
.oryxos/agents/daily-weather/
├── AGENT.md            # frontmatter = profile, body = system prompt
├── skills/             # optional sub-instructions (read on demand)
└── scripts/            # optional scripts (run via shell tool)
```

Business users define agents by writing files. The model fetches `skills/` and `scripts/` on demand via built-in tools. This is progressive disclosure inside one agent.

## Comparison

| | **OryxOS** | OpenClaw | Hermes Agent | Dify / Coze |
|---|---|---|---|---|
| Language | **Java** | Node.js | Python | Python / TS |
| Target | **Regulated enterprise** | Consumer / small team | Team / small org | Business users |
| Deployment | **Single binary, on-prem** | On-prem | On-prem | Cloud-hosted SaaS |
| Audit trail | **Built-in (day-one DB writes)** | ❌ (CVE-prone) | Partial | ✅ (SaaS) |
| Ecosystem fit | **Java/Spring/Cloud-native** | JS/TS | Python data stack | Cross-platform |
| Java AI framework | Built on Spring AI Alibaba | N/A | LangChain | LangChain |
| MCP support | Client (core) + Server (extension) | ✅ | ✅ | ✅ |
| Product form | **Runtime kernel + config** | Runtime | Runtime | Visual workflow |

**Key positioning**: *Frameworks give you code; orchestrators give you flows; OryxOS gives you the runtime that hosts your agents — auditable, private, Java-native.*

## Design principles

1. **Runtime kernel, not product.** The core stage ships the runtime kernel — a foundation, not a complete enterprise product. The differentiated governance layer (multi-tenancy, SSO, full audit, Tool Policy, Web dashboard) is the end state, built on top.
2. **Audit is day-one, not retroactive.** `tool_invocations` and `llm_calls` are written from US-1, not added at the end.
3. **One directory = one Agent.** `AGENT.md` + optional `skills/` + optional `scripts/` + optional `REFERENCE.md`. Progressive disclosure.
4. **One engine, three trigger sources.** CLI / REST / Scheduler all converge on `AgentService.process(Session, String)`.
5. **Java 21 features only.** No reflection-based hacks for older JDKs.
6. **Spring AI used at half-strength.** Provider + protocol conversion + `@Tool` schema only. No Agent abstraction, no auto tool execution.
7. **Tool-related code in one module.** `oryxos-tool` is not split.

## What's in the core stage

| Capability        | Module              | Demo                                                  |
| ----------------- | ------------------- | ----------------------------------------------------- |
| LLM Provider      | `oryxos-provider`   | (shared across all demos)                             |
| ReAct Loop        | `oryxos-core`       | Demo 1 (Daily Weather)                                |
| Memory            | `oryxos-memory`     | Demo 2 (Daily Tech Digest)                            |
| Tool + Sandbox    | `oryxos-tool`       | Demo 1 (HTTP), Demo 2 (MCP), Demo 3 (Shell)          |
| REST API          | `oryxos-web`        | All demos (manual trigger via REST)                   |
| Scheduler         | `oryxos-core`       | All demos (cron-trigger)                              |

## What's not in the core stage

These are intentional gaps. They are **planned for the extension stage** — do not start implementing them:

- ❌ Authentication / SSO / RBAC
- ❌ Multi-tenancy
- ❌ Profile create / update via API
- ❌ Agent create / update via API
- ❌ Streaming SSE
- ❌ Vector memory (LanceDB Java, pgvector, JVector)
- ❌ Adaptive routing (fallback, hedge racing, circuit breaker)
- ❌ Cluster HA (Nacos / ETCD)
- ❌ Web dashboard
- ❌ Tool Policy (profile-level allow/deny)

## Where to go next

| Destination                          | What you'll find                                       |
| ------------------------------------ | ------------------------------------------------------ |
| [Architecture](./architecture)       | Layer-by-layer walkthrough                             |
| [Features](./features)               | Detailed reference for the 5 core capabilities          |
| [Scenarios](./scenarios)             | 6 enterprise use cases                                 |
| [Roadmap](./roadmap)                 | Core stage → Extension stage → Community stage         |
| [Constitution](./constitution)       | The seven non-negotiable principles                    |