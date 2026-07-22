---
title: Quick Start — Run the Three Demos
description: Run the three daily-running demos locally in under 10 minutes.
---

# Quick Start

This guide walks through OryxOS's three end-to-end demos. All three exercise the same `AgentService` chain — they differ only in trigger source.

> ⏱️ Estimated time: **5–10 minutes** if you have JDK 21 and a DeepSeek/Kimi API key.

---

## Prerequisites

- **JDK 21+**
- **Maven 3.9+**
- At least one provider API key — DeepSeek recommended for the demos (`DEEPSEEK_API_KEY`)
- Optional: an IM webhook URL (WeCom / Feishu / DingTalk) for `WEATHER_NOTIFY_URL`

---

## Step 1 — Build from source

```bash
git clone https://github.com/oryxos/oryxos.git
cd oryxos
mvn -pl oryxos-boot -am clean package -DskipTests
```

The output is `oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar`.

---

## Step 2 — Configure environment

```bash
export DEEPSEEK_API_KEY=sk-...your-key...
export WEATHER_NOTIFY_URL=https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...   # optional
```

Secrets are loaded via `${ENV_VAR}` placeholders in `application.yml` and `AGENT.md`.

---

## Step 3 — Initialize the workspace

```bash
java -jar oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar init
```

This creates `.oryxos/` in the current directory:

```
.oryxos/
├── agents/             # empty — drop Agent directories here
├── memory/
│   └── MEMORY.md       # long-term memory (Markdown default)
├── sessions/           # runtime session data
├── logs/               # structured JSON logs
├── AGENTS.md           # project-wide agent behavior
├── SOUL.md             # default agent persona
├── USER.md             # user preferences
└── oryxos.db           # SQLite (5 audit tables)
```

---

## Step 4 — Drop in the demo Agents

The repo ships three demo Agents under `examples/agents/`. Copy them:

```bash
cp -r examples/agents/daily-weather      .oryxos/agents/
cp -r examples/agents/daily-tech-digest  .oryxos/agents/
cp -r examples/agents/daily-github       .oryxos/agents/
```

Each is a directory with `AGENT.md` (and optional `skills/`, `scripts/`).

---

## Step 5 — Launch the gateway

```bash
java -jar oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar gateway
```

`gateway` mode runs **all three trigger sources simultaneously**:

- CLI channel (interactive REPL)
- REST API on `:8080`
- `AgentScheduler` (cron jobs defined in `AGENT.md` schedules)

You should see:

```
[oryxos] started gateway
[oryxos] CLI channel:    ready
[oryxos] REST API:       http://localhost:8080
[oryxos] AgentScheduler: 3 schedules registered
[oryxos] Profiles:       daily-weather, daily-tech-digest, daily-github
```

---

## Demo 1 — Daily Weather (HTTP + Notify + Scheduler)

The Daily Weather Agent:

1. Fires every morning at 08:00 (cron `0 0 8 * * *`, zone `Asia/Shanghai`).
2. Calls `http_get` to fetch Shanghai weather (whitelisted domain).
3. Generates outfit advice via the LLM.
4. Pushes the advice to the IM webhook via `notify`.

**Manual trigger** (same `AgentService` chain as the scheduled run):

```bash
# CLI
oryxos chat --profile daily-weather

# REST
curl -X POST http://localhost:8080/api/v1/agents/daily-weather/invoke \
  -H "Content-Type: application/json" \
  -d '{"message":"今天上海天气怎么样？"}'
```

**Capabilities exercised**: LLM Provider + ReAct loop + HTTP Tool + NotifyTools + Sandbox + Scheduler.

---

## Demo 2 — Daily Tech Digest (Memory + MCP + read_file)

The Daily Tech Digest Agent:

1. Fires every morning at 09:00.
2. Reads `skills/source-list.md` and `skills/digest-format.md` via `read_file` (progressive disclosure).
3. Calls news MCP servers for fresh content.
4. Recalls user preferences from `MEMORY.md` (e.g., "prefer Chinese").
5. Drafts a digest, pushes to the same notify channel.

**Manual trigger**:

```bash
oryxos chat --profile daily-tech-digest
```

**Capabilities exercised**: Memory + MCP + read_file on-demand + NotifyTools + Scheduler.

---

## Demo 3 — Daily GitHub Digest (Shell + Sandbox + Memory)

The Daily GitHub Digest Agent:

1. Fires every morning at 09:30.
2. Runs `scripts/github_trending.py` via `shell` to fetch GitHub trending.
3. Parses the JSON output, summarizes via LLM.
4. Saves digest to `MEMORY.md` for next-day continuity.
5. Pushes to the same notify channel.

**Manual trigger**:

```bash
oryxos chat --profile daily-github
```

**Capabilities exercised**: Shell Tool + script sandbox boundary + Memory + NotifyTools + Scheduler.

---

## Verify the audit tables

Every tool call and every LLM call lands in SQLite:

```bash
sqlite3 .oryxos/oryxos.db

sqlite> SELECT created_at, profile_name, tool_name, success
        FROM tool_invocations
        ORDER BY created_at DESC LIMIT 10;

sqlite> SELECT created_at, profile_name, provider, model, total_tokens, duration_ms
        FROM llm_calls
        ORDER BY created_at DESC LIMIT 10;
```

This is the audit-grade foundation: replay any past call with SQL.

---

## Cleanup

```bash
# Stop the gateway
Ctrl-C

# Inspect a session
sqlite3 .oryxos/oryxos.db "SELECT id, profile_name, created_at FROM sessions ORDER BY created_at DESC LIMIT 5;"
```

---

## Next steps

| Destination                              | What you'll find                                         |
| ---------------------------------------- | -------------------------------------------------------- |
| [For Engineers](./for-engineer)          | Build, deploy, extend                                    |
| [For Agents](./for-agent)                | Define your own Agent without writing Java              |
| [Architecture](./architecture)           | How the runtime executes your Agent                     |
| [Features](./features)                   | Detailed reference for the 5 core capabilities            |
| [Scenarios](./scenarios)                 | 6 enterprise use cases                                   |