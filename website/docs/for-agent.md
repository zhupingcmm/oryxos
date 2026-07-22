---
title: For Agent — AGENT.md Manual
description: How to define an Agent by writing files. No Java required.
---

# For Agent

You are an AI Agent. This is your `AGENT.md` manual.

> This page describes the Agent definition format. For how the runtime executes it — see [For Engineers](./for-engineer).

## The problem you face

You need to know three things:

1. **What is my job?** — the system prompt.
2. **What tools can I call?** — function calling schema.
3. **How do I run?** — schedule, channel, settings.

OryxOS gives you all three via a single `AGENT.md` file with YAML frontmatter + Markdown body.

## Directory layout

One agent = one directory. Place it under `.oryxos/agents/<name>/`:

```
.oryxos/agents/daily-weather/
├── AGENT.md            # ★ required — profile (frontmatter) + system prompt (body)
├── skills/             # optional — sub-instructions, read on demand
│   └── weather-format.md
├── scripts/            # optional — run via shell tool on demand
│   └── historical.py
└── REFERENCE.md        # optional — glossary / style guide, read on demand
```

The `AgentLoader` scans this directory at startup. Sub-files are not preloaded into the system prompt — you fetch them on demand via the built-in `read_file` tool.

## AGENT.md anatomy

```markdown
---
name: daily-weather              # required — profile name, must be unique
description: Push weather + outfit advice   # required — short summary

provider:
  name: deepseek                 # required — must match an entry in application.yml
  model: deepseek-chat           # optional — provider default if omitted
  temperature: 0.7               # optional — provider default if omitted

tools:                           # optional — restrict available tools (else all enabled)
  - http_get
  - notify
  - read_file
  - shell

skills:                          # optional — list skills/*.md files to be loadable
  - weather-format

mcp_servers:                     # optional — MCP server names to attach
  - github

channels:                        # optional — inbound channels (CLI is always on)
  - name: cli
  - name: web

notify_channels:                 # optional — outbound push targets
  - type: webhook
    config:
      url: ${WEATHER_NOTIFY_URL}

bootstrap:                       # optional — Bootstrap files loaded into system prompt
  - AGENTS.md
  - SOUL.md
  - USER.md

schedules:                       # optional — AgentScheduler triggers
  - id: morning-weather
    cron: "0 0 8 * * *"
    zone: Asia/Shanghai
    message: "查一下今天上海的天气，生成穿搭建议并推送"

settings:
  max_iterations: 10             # default 10 — cap the ReAct loop
  max_history_turns: 20          # default 20 — history truncation
---

# Daily Weather Agent

You are a daily weather assistant...

1. Fetch today's weather for Shanghai via `http_get`.
2. Generate concise outfit advice.
3. Push the advice to the team channel via `notify`.
```

## Frontmatter fields

| Field | Required | Default | Notes |
|-------|----------|---------|-------|
| `name` | ✅ | — | Unique profile name. Must match the directory name. |
| `description` | ✅ | — | One-line summary. |
| `provider.name` | ✅ | — | Must match an entry in `application.yml` under `oryxos.providers`. |
| `provider.model` | ❌ | provider default | |
| `provider.temperature` | ❌ | provider default | |
| `tools` | ❌ | all enabled | Restrict to a subset (whitelist). |
| `skills` | ❌ | none | List of `skills/*.md` filenames. |
| `mcp_servers` | ❌ | none | Names from `.oryxos/mcp_servers.yaml`. |
| `channels` | ❌ | `[cli, web]` | Inbound channels. |
| `notify_channels` | ❌ | none | Outbound push targets. |
| `bootstrap` | ❌ | `[AGENTS.md, SOUL.md, USER.md]` | Files from `.oryxos/` root. |
| `schedules` | ❌ | none | Cron triggers for `AgentScheduler`. |
| `settings.max_iterations` | ❌ | `10` | ReAct loop cap. |
| `settings.max_history_turns` | ❌ | `20` | Conversation history truncation. |

**Sensitive fields** like `api_key`, `webhook.url`, etc. use `${ENV_VAR}` placeholders. They're resolved at load time from environment variables. Never hardcode secrets.

## Skills (progressive disclosure)

Sub-instructions live in `skills/*.md`. They are **not** preloaded into the system prompt. You fetch them on demand with `read_file`.

Example:

```
.oryxos/agents/daily-tech-digest/
├── AGENT.md
└── skills/
    ├── digest-format.md     # formatting rules
    └── source-list.md       # news sources to consult
```

In your `AGENT.md` body, you say:

```markdown
When writing the digest:
1. Read `skills/digest-format.md` for formatting rules.
2. Read `skills/source-list.md` for today's sources.
3. Compose the digest.
```

The model will call `read_file` to fetch each one before proceeding. This keeps the system prompt small.

## Scripts (sandbox boundary)

Scripts live in `scripts/`. They run via the `shell` tool. **Scripts run Python subprocesses that can do anything** — they bypass `http_get`'s URL whitelist.

> ⚠️ **Trust boundary**: Installing an Agent with `scripts/` means you trust the Agent's author to not exfiltrate data. The core stage does not isolate script execution — that's container / microVM in the extension stage.

```python
# scripts/github_trending.py
import urllib.request
import json

# This call bypasses http_get's whitelist — the agent author must be trusted.
with urllib.request.urlopen("https://api.github.com/trending") as r:
    data = json.loads(r.read())
print(json.dumps(data[:10]))
```

## Reference material

`REFERENCE.md` is loaded only when you read it. Use it for glossaries, style guides, or any large corpus the model should consult but not memorize.

## Memory

You have three ways to remember across conversations:

1. **Session memory** — automatic, lives with the current Session.
2. **Long-term memory (`MEMORY.md`)** — call `memory_write` / `memory_read` tools to persist.
3. **Pluggable backends** — `MarkdownMemoryStore` (default), `SqliteMemoryStore`, `Mem0MemoryStore`.

Write memorable preferences to core scope. Archive bulky results to archive scope.

```text
memory.write("user.prefers.format", "table", scope=CORE)
memory.write("archive.2025-07-21.digest", "<full digest>", scope=ARCHIVE)
```

The `recallByKeyword` tool searches both scopes.

## Bootstrap files

Three files in `.oryxos/` root affect your system prompt:

| File | Purpose |
|------|---------|
| `AGENTS.md` | Project-wide agent behavior — shared rules across all Agents. |
| `SOUL.md`   | Default agent persona / voice. |
| `USER.md`   | User preferences — what the human likes. |

These are loaded once at startup and appended to your system prompt. Customize per-Agent via the `bootstrap` frontmatter field.

## Lifecycle

1. **Startup** — `AgentLoader` reads your `AGENT.md`, derives a `Profile`, registers it.
2. **Trigger** — a message arrives (CLI / REST / scheduler).
3. **ReAct loop** — the runtime calls you (LLM) up to `max_iterations` times, with tool results in between.
4. **Response** — your final answer is returned to the trigger source (printed to terminal, HTTP response, pushed to notify channel, etc.).
5. **Persist** — the session is written to SQLite. Next conversation continues from where you left off.

## What's next

| Destination                          | What you'll find                                       |
| ------------------------------------ | ------------------------------------------------------ |
| [For Engineers](./for-engineer)      | Build, deploy, integrate                               |
| [Quick Start](./quick-start)          | Run the three demos                                    |
| [Architecture](./architecture)       | How the runtime executes your AGENT.md                 |
| [Constitution](./constitution)       | The seven non-negotiable principles                   |