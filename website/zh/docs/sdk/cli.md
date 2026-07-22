---
title: CLI —— 命令参考
description: 12 个 OryxOS CLI 子命令。
---

# CLI

`oryxos` CLI 基于 [Picocli](https://picocli.info)。12 个子命令分 3 组：Lifecycle、Profile、Discovery。

```bash
oryxos --version
# OryxOS 1.0.0-SNAPSHOT

oryxos --help
```

---

## Lifecycle 命令

### `oryxos init`

在当前目录初始化 `.oryxos/`。创建工作区骨架（agents / memory / sessions / logs / bootstrap 文件）。不启动 Spring 上下文。

```bash
$ oryxos init

.oryxos/
├── agents/             # 在这里丢 AGENT.md 目录
├── memory/MEMORY.md
├── sessions/
├── logs/
├── AGENTS.md           # 项目级 agent 行为
├── SOUL.md             # 默认 agent 人格
├── USER.md             # 用户偏好
└── oryxos.db           # SQLite
```

### `oryxos status`

运行时状态——已加载 Profiles、活跃 sessions、定时任务。

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

跟一个 Profile 交互式 REPL。

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

只跑 REST API。无 CLI，无 Scheduler。

```bash
$ oryxos serve --port 8080
[oryxos] REST API listening on :8080
```

### `oryxos gateway`

跑一切：CLI + REST + Scheduler。这是默认的"生产"模式。

```bash
$ oryxos gateway
[oryxos] started gateway
[oryxos] CLI channel:    ready
[oryxos] REST API:       http://localhost:8080
[oryxos] AgentScheduler: 3 schedules registered
```

---

## Profile 命令

### `oryxos profile list`

列出已加载 Profiles。不需要 Spring 上下文——直接扫 `.oryxos/agents/`。

```bash
$ oryxos profile list

daily-weather
  description: 推送每日天气和穿搭建议到群里
  provider:     deepseek (deepseek-chat)
  tools:        http_get, notify

daily-tech-digest
  description: 每日科技新闻日报，按用户偏好
  provider:     kimi (moonshot-v1-8k)
  tools:        read_file, notify, memory_*
```

### `oryxos profile create`

脚手架一个新 `AGENT.md` 目录，带模板。

```bash
$ oryxos profile create my-new-agent
[oryxos] created .oryxos/agents/my-new-agent/AGENT.md
[oryxos] 编辑它，然后重启 gateway 加载
```

### `oryxos profile show`

打印一个 Profile 解析后的 YAML（`${ENV_VAR}` 展开后）。

```bash
$ oryxos profile show daily-weather

name:        daily-weather
description: 推送每日天气和穿搭建议到群里
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

删一个 Profile 目录。

```bash
$ oryxos profile delete my-new-agent
[oryxos] removed .oryxos/agents/my-new-agent
[oryxos] 重启 gateway 生效
```

---

## Discovery 命令

### `oryxos provider list`

列出已注册 Providers（来自 `application.yml`）。

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

列出已注册 Tools（内置 + MCP）。

```bash
$ oryxos tool list

built-in:
  - read_file      读 .oryxos/ 下的文件
  - write_file     写文件到 .oryxos/
  - shell          跑 shell 命令（沙箱化）
  - http_get       GET 一个 HTTP URL（白名单）
  - http_post      POST 到一个 HTTP URL（白名单）
  - memory_read    读长期记忆
  - memory_write   写长期记忆
  - notify         推到出站渠道
  - list_agents    发现其他 profile

mcp:
  - github.list_repos     列 GitHub repos
  - github.search_code    搜 GitHub 代码
```

### `oryxos session list`

列出持久化 Sessions。

```bash
$ oryxos session list

id        profile            created             messages
abc123    daily-weather      2025-07-22 08:00    12
def456    daily-tech-digest  2025-07-22 09:00    8
ghi789    daily-github       2025-07-22 09:30    15
```

用 `GET /api/v1/sessions/{id}` 拉完整对话历史。

---

## 环境变量

| 变量                   | 用途                                          |
| ---------------------- | --------------------------------------------- |
| `DEEPSEEK_API_KEY`     | DeepSeek API key                              |
| `KIMI_API_KEY`         | Kimi / 月之暗面 API key                       |
| `QWEN_API_KEY`         | Qwen / DashScope API key                      |
| `WEATHER_NOTIFY_URL`   | 每日天气 Agent 的 webhook URL                 |
| `ORYXOS_HOME`          | 覆盖工作区根（默认 `.`）                       |

---

## 下一步

| 目标                                                         | 看到什么                                          |
| ------------------------------------------------------------ | ------------------------------------------------- |
| [Java SDK](./java)                                           | 编程式 API                                         |
| [Spring Boot Starter](./spring-boot-starter)                  | 自动装配                                          |
| [给工程师](../for-engineer)                                  | 构建、部署、扩展                                  |