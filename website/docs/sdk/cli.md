---
title: CLI — Command Reference
description: The 12 OryxOS CLI sub-commands.
---

# CLI

The `oryxos` CLI is built on [Picocli](https://picocli.info). It exposes 12 sub-commands across 3 groups: Lifecycle, Profile, Discovery.

```bash
oryxos --version
# OryxOS 1.0.0-SNAPSHOT

oryxos --help
```

---

## Lifecycle commands

### `oryxos init`

Initialize `.oryxos/` in the current directory. Creates the workspace layout (agents / memory / sessions / logs / bootstrap files). Does not start the Spring context.

```bash
$ oryxos init

.oryxos/
├── agents/             # drop AGENT.md directories here
├── memory/MEMORY.md
├── sessions/
├── logs/
├── AGENTS.md           # project-wide agent behavior
├── SOUL.md             # default agent persona
├── USER.md             # user preferences
└── oryxos.db           # SQLite
```

### `oryxos status`

Show runtime status — Profiles loaded, active sessions, scheduled jobs.

```bash
$ oryxos status

Profiles:
  - daily-weather         (provider: deepseek)
  - daily-tech-digest     (provider: kimi)
  - daily-github          (provider: deepseek)

Sessions: 3 active, 47 total
Scheduled jobs:
  - daily-weather     next: 2025-07-23 08:00:00 (Asia/Shanghai)
  - daily-tech-digest next: 2025-07-23 09:00:00 (Asia/Shanghai)
  - daily-github      next: 2025-07-23 09:30:00 (Asia/Shanghai)
```

### `oryxos chat`

Interactive REPL with one Profile.

```bash
$ oryxos chat --profile daily-weather

[oryxos] chatting with profile 'daily-weather'
[oryxos] provider: deepseek (deepseek-chat)

you> 查一下今天上海的天气
[agent] ▸ tool call: http_get("https://api.weather.example.com/shanghai")
[agent] 上海今天多云，气温 26-32°C ...
[agent] 穿搭建议：轻薄长袖 + 防晒 ...

you> 推送给我
[agent] ▸ tool call: notify("...outfit advice...")
[agent] 已推送到团队群

you> :quit
[oryxos] goodbye
```

### `oryxos serve`

Run REST API only. No CLI, no scheduler.

```bash
$ oryxos serve --port 8080
[oryxos] REST API listening on :8080
```

### `oryxos gateway`

Run everything: CLI + REST + Scheduler. This is the default "production" mode.

```bash
$ orxoys gateway
[oryxos] started gateway
[oryxos] CLI channel:    ready
[oryxos] REST API:       http://localhost:8080
[oryxos] AgentScheduler: 3 schedules registered
```

---

## Profile commands

### `oryxos profile list`

List loaded Profiles. Does not require Spring context — scans `.oryxos/agents/` directly.

```bash
$ oryxos profile list

daily-weather
  description: Push daily weather and outfit advice to the team
  provider:     deepseek (deepseek-chat)
  tools:        http_get, notify

daily-tech-digest
  description: Daily tech news digest with user preferences
  provider:     kimi (moonshot-v1-8k)
  tools:        read_file, notify, memory_*
```

### `oryxos profile create`

Scaffold a new `AGENT.md` directory with a template.

```bash
$ oryxos profile create my-new-agent
[oryxos] created .oryxos/agents/my-new-agent/AGENT.md
[oryxos] edit it, then restart the gateway to load it
```

### `oryxos profile show`

Print a Profile's resolved YAML (after `${ENV_VAR}` expansion).

```bash
$ oryxos profile show daily-weather

name:        daily-weather
description: Push daily weather and outfit advice to the team
provider:
  name:     deepseek
  model:    deepseek-chat
  temperature: 0.7
tools:
  - http_get
  - notify
notify_channels:
  - type: webhook
    config:
      url: https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc123
schedules:
  - id: morning-weather
    cron: "0 0 8 * * *"
    zone: Asia/Shanghai
settings:
  max_iterations: 10
  max_history_turns: 20
```

### `oryxos profile delete`

Remove a Profile directory.

```bash
$ oryxos profile delete my-new-agent
[oryxos] removed .oryxos/agents/my-new-agent
[oryxos] restart the gateway to apply
```

---

## Discovery commands

### `oryxos provider list`

List registered Providers (from `application.yml`).

```bash
$ oryxos provider list

deepseek
  base-url: https://api.deepseek.com
  model:    deepseek-chat

kimi
  base-url: https://api.moonshot.cn
  model:    moonshot-v1-8k
```

### `oryxos tool list`

List registered Tools (built-in + MCP).

```bash
$ oryxos tool list

built-in:
  - read_file      Read a file under .oryxos/
  - write_file     Write a file under .oryxos/
  - shell          Execute a shell command (sandboxed)
  - http_get       GET an HTTP URL (whitelisted)
  - http_post      POST to an HTTP URL (whitelisted)
  - memory_read    Read from long-term memory
  - memory_write   Write to long-term memory
  - notify         Push to outbound channel
  - list_agents    Discover other profiles

mcp:
  - github.list_repos     List GitHub repos
  - github.search_code    Search GitHub code
```

### `oryxos session list`

List persisted Sessions.

```bash
$ oryxos session list

id        profile            created             messages
abc123    daily-weather      2025-07-22 08:00    12
def456    daily-tech-digest  2025-07-22 09:00    8
ghi789    daily-github       2025-07-22 09:30    15
```

Use `GET /api/v1/sessions/{id}` to fetch full conversation history.

---

## Environment variables

| Variable          | Purpose                                      |
| ----------------- | -------------------------------------------- |
| `DEEPSEEK_API_KEY` | DeepSeek API key                            |
| `KIMI_API_KEY`     | Kimi / Moonshot API key                     |
| `QWEN_API_KEY`     | Qwen / DashScope API key                    |
| `WEATHER_NOTIFY_URL` | Webhook URL for the Daily Weather agent   |
| `ORYXOS_HOME`      | Override the workspace root (default `.`)   |

---

## Where to go next

| Destination                                                  | What you'll find                                       |
| ------------------------------------------------------------ | ------------------------------------------------------ |
| [Java SDK](./java)                                            | Programmatic API                                       |
| [Spring Boot Starter](./spring-boot-starter)                  | Auto-configuration                                     |
| [For Engineers](../for-engineer)                             | Build, deploy, extend                                  |