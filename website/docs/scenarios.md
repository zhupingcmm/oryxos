---
title: Scenarios — Six Enterprise Use Cases
description: Six concrete use cases for OryxOS in the enterprise.
---

# Scenarios

Six concrete enterprise use cases for OryxOS. Each one describes the Agent directory shape, the trigger source, and the capabilities exercised.

---

## Scenario 01 — Scheduled daily reports

**Trigger**: `AgentScheduler` cron, every morning at 08:00.

**Agent shape**: bare `AGENT.md`, no `skills/`, no `scripts/`.

**Flow**:

1. `AgentScheduler` fires at 08:00 with the configured `message`.
2. Agent calls `http_get` to fetch today's data (e.g., weather, news, GitHub trending).
3. Agent drafts a summary via LLM.
4. Agent calls `notify` to push to the team IM webhook.

**Capabilities exercised**: LLM + ReAct + HTTP Tool + NotifyTools + Sandbox + Scheduler.

**Example `AGENT.md`:**

```markdown
---
name: daily-weather
provider:
  name: deepseek
  model: deepseek-chat
tools: [http_get, notify]
notify_channels:
  - type: webhook
    config:
      url: ${WEATHER_NOTIFY_URL}
schedules:
  - id: morning-weather
    cron: "0 0 8 * * *"
    zone: Asia/Shanghai
    message: "查一下今天上海的天气，生成穿搭建议并推送"
settings:
  max_iterations: 10
---

# Daily Weather Agent
You are a daily weather assistant. Each morning:
1. Fetch today's weather for Shanghai via `http_get`.
2. Generate concise outfit advice.
3. Push the advice to the team channel via `notify`.
```

---

## Scenario 02 — Internal knowledge base Q&A

**Trigger**: User query, CLI or REST.

**Agent shape**: `AGENT.md` + `skills/` sub-instructions + optional `REFERENCE.md`.

**Flow**:

1. User asks a question (CLI `oryxos chat` or REST `POST /agents/{name}/invoke`).
2. Agent consults `skills/` files via `read_file` (progressive disclosure).
3. Agent composes an answer using LLM, grounded in the local material.
4. Optionally writes the Q&A pair to `MEMORY.md` (CORE scope) for future reference.

**Capabilities exercised**: ReAct + Memory + read_file on-demand + Sandbox.

**Why progressive disclosure**: A 200-page `REFERENCE.md` cannot fit in the system prompt. Loading it on demand keeps the cost low and the answer grounded.

---

## Scenario 03 — Multi-Agent orchestration

**Trigger**: User query, CLI or REST.

**Agent shape**: One orchestrator Agent + several sub-Agents + `mcp_servers` entries pointing to them.

**Flow**:

1. Orchestrator Agent receives a complex request.
2. Orchestrator calls sub-Agents via MCP (each sub-Agent is its own `AGENT.md`).
3. Sub-Agents return their results.
4. Orchestrator aggregates and emits one unified answer.

**Capabilities exercised**: ReAct + MCP client + Tool Registry + Memory (cross-Agent via MEMORY.md).

---

## Scenario 04 — Customer service with FAQ fallback

**Trigger**: Inbound question from a customer via REST.

**Agent shape**: `AGENT.md` + `skills/faq.md` (curated FAQ list) + `skills/escalation-rules.md`.

**Flow**:

1. Agent reads the FAQ via `read_file` on demand.
2. If the question matches a FAQ entry → answer directly.
3. If not → Agent calls `notify` to escalate to a human queue (or generate a support ticket via HTTP).
4. The interaction is logged to `sessions` for later review.

**Capabilities exercised**: ReAct + read_file + HTTP / Notify + audit.

---

## Scenario 05 — Long-running task with progress reporting

**Trigger**: Scheduled job (cron every hour) or manual REST.

**Agent shape**: `AGENT.md` + `scripts/run_pipeline.py` + scheduled entry.

**Flow**:

1. Agent fires (scheduled or manual).
2. Agent runs `scripts/run_pipeline.py` via `shell` (sandbox: SHELL_COMMAND).
3. The script may run for minutes; the shell tool waits for it.
4. Agent reads the script's output, summarizes, and pushes a progress update via `notify`.
5. If errors are detected, Agent writes them to `MEMORY.md` (ARCHIVE scope).

**Capabilities exercised**: Shell Tool + Sandbox + Memory + Notify + Scheduler.

> ⚠️ Scripts can make their own network calls — they bypass `http_get`'s URL whitelist. Trust the Agent author.

---

## Scenario 06 — Audit-driven compliance reporting

**Trigger**: SQL query against `tool_invocations` and `llm_calls` (no Agent invocation).

**Flow**:

This is not an Agent scenario — it's a backend query against the audit tables. A compliance officer runs SQL directly:

```sql
-- All tool calls in the last 24 hours that failed
SELECT created_at, profile_name, tool_name, error_message
FROM tool_invocations
WHERE created_at > datetime('now', '-1 day')
  AND success = 0;

-- Total LLM spend per profile over the last week
SELECT profile_name, provider, model,
       COUNT(*) AS calls,
       SUM(total_tokens) AS tokens
FROM llm_calls
WHERE created_at > datetime('now', '-7 day')
GROUP BY profile_name, provider, model;
```

**Why this matters**: Compliance asks "show me everything that happened last Tuesday." OryxOS answers with one SQL query against `oryxos.db`. No log scraping, no log shipping pipeline.

---

## What's not in scenarios

These are **extension-stage** features and are not covered by core-stage scenarios:

- ❌ Multi-tenant isolation across Agents
- ❌ Cross-tenant audit
- ❌ SSO-gated Agent invocation
- ❌ Per-tenant rate limits
- ❌ Real-time SSE streaming of LLM tokens

---

## Where to go next

| Destination                              | What you'll find                                       |
| ---------------------------------------- | ------------------------------------------------------ |
| [Quick Start](./quick-start)            | Run three demo Agents locally                          |
| [For Engineers](./for-engineer)          | Build, deploy, extend                                  |
| [Architecture](./architecture)           | Layer-by-layer walkthrough                             |