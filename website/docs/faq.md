---
title: FAQ
description: Frequently asked questions about OryxOS.
---

# FAQ

## General

### What is OryxOS?

OryxOS is an enterprise Agent Operating System runtime kernel written in Java on Spring Boot. It runs multiple business Agents on a customer's own infrastructure, sharing a single set of capabilities: LLM provider routing, ReAct reasoning, three-layer memory, plugin tools, and a REST API. See [What is OryxOS](./what).

### Who is OryxOS for?

Regulated enterprises (banks, government, telecom, energy, healthcare) where:

- The IT backbone is Java.
- Data must stay on-premises.
- Every LLM and tool call must be auditable.

If you're a consumer or small team, OpenClaw or Hermes Agent is probably a better fit.

### What stage is OryxOS in?

**Core Stage** — building the runtime kernel (5 capabilities + 3 demos). 4 weeks × 3 hours of focused development. The extension stage (multi-tenancy, SSO, full audit, Tool Policy, Web dashboard) comes after.

### How is OryxOS licensed?

MIT. See [LICENSE](https://github.com/oryxos/oryxos/blob/main/LICENSE).

---

## Architecture

### Why Java and not Python?

Java is the IT backbone of regulated enterprises — Nacos, Sentinel, SkyWalking, Arthas, Prometheus+Grafana. The Agent layer has to plug into this stack. Python- or Node-based Agent frameworks cannot.

### Why Spring AI if you don't use its Agent abstraction?

Spring AI is used at half-strength:

- ✅ Provider abstraction (multiple LLM vendors)
- ✅ Protocol conversion (Anthropic / OpenAI / DashScope wire formats)
- ✅ `@Tool` schema generation (function-calling payload)

It is **not** used for:

- ❌ Agent abstraction (we self-implement `ReActLoop`)
- ❌ Auto tool execution (it causes duplicate invocations)

### Why a self-implemented ReAct loop?

Spring AI's `AgentExecutor` / `FunctionCallingAgent` auto-executes tool calls. Combined with our `ToolExecutor` that also calls tools, every tool gets invoked twice. The fix is to disable Spring AI's auto execution and call tools only from our `ToolExecutor`. This is one of the seven non-negotiable constitution principles.

### Why SQLite and not Postgres?

For the core stage, SQLite is sufficient:

- Single binary deployment (no DB server)
- 5 tables, all small
- SQL-queryable audit

Postgres is the extension-stage choice for multi-tenant deployments.

### Why is `tool_invocations` written to the DB and not just logged?

Day-one compliance. Compliance asks "show me what happened last Tuesday" — you answer with one SQL query. Logs are not searchable by `profile_name = 'daily-weather' AND tool_name = 'http_get' AND success = 0`. SQL is.

---

## Operations

### How do I add a new Provider?

1. Add config under `oryxos.providers.<name>` in `application.yml`.
2. Implement `ProviderInitializer` (interface) or use a built-in adapter.
3. Reference `provider.name` in `AGENT.md`.

See [For Engineers](./for-engineer#add-a-provider).

### How do I add a new Tool?

Implement `OryxTool`, annotate with `@Component`. It's auto-discovered. See [Features](./features#4-plugin-tools--sandbox-oryxos-tool).

### How do I add a new Notify Channel?

Implement `NotifyChannelAdapter`, annotate with `@Component`. Reference via `notify_channels[].type`. See [For Engineers](./for-engineer#add-a-notify-channel).

### Can I update Profiles at runtime?

**No** — in the core stage, Profiles are file-based and read once at startup. To apply changes, restart the gateway. Runtime Profile CRUD via REST is an extension-stage feature.

### Can I create Agents via REST?

**No** — same reason. Drop an `AGENT.md` into `.oryxos/agents/<name>/` and restart.

### What's the audit retention policy?

There isn't one in the core stage. Tables grow indefinitely. The extension stage adds a retention job (e.g., archive after 90 days to cold storage). For now, plan your disk.

---

## Security

### Is OryxOS safe to expose to the public internet?

**No.** The core stage has no authentication, no authorization, no rate limiting. Run it on an internal network only. Multi-tenancy, SSO, RBAC are extension-stage features.

### What about API key leaks?

API keys are never hardcoded in `AGENT.md` or `application.yml`. Use `${ENV_VAR}` placeholders and resolve from environment variables at load time.

### What about scripts escaping the sandbox?

They can. Scripts run in subprocesses and have their own network/filesystem access. Installing an Agent with `scripts/` means **trusting the Agent's author**. The core stage does not isolate scripts — that's container / microVM in the extension stage.

---

## Comparison

### OryxOS vs OpenClaw?

OpenClaw is Node.js, consumer-focused. OryxOS is Java, regulated-enterprise-focused. OpenClaw is single-tenant, no audit. OryxOS has day-one audit tables.

### OryxOS vs Hermes Agent?

Hermes Agent is Python, team-focused. Same broad category but different language ecosystem and target market.

### OryxOS vs Dify / Coze?

Dify and Coze are visual workflow builders, cloud-hosted SaaS. OryxOS is a runtime kernel, on-prem. Dify/Coze target business users who don't code; OryxOS targets Java teams who want full control.

### OryxOS vs LangChain?

LangChain is a Python framework. OryxOS is a Java runtime that uses Spring AI Alibaba (which is loosely inspired by LangChain patterns but Java-native).

---

## Roadmap

### When will multi-tenancy ship?

Extension stage, after core stage completes. See [Roadmap](./roadmap).

### Will there be a Python SDK?

Community stage. The Java SDK (Spring Boot Starter) is the only first-party one in the core and extension stages.

### Will there be vector memory?

Extension stage. Backends TBD: LanceDB Java, pgvector, JVector.

---

## Where to go next

| Destination                              | What you'll find                                       |
| ---------------------------------------- | ------------------------------------------------------ |
| [Quick Start](./quick-start)            | Run three demo Agents locally                          |
| [Architecture](./architecture)           | Layer-by-layer walkthrough                             |
| [Roadmap](./roadmap)                     | Core / Extension / Community stages                    |