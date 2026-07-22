---
title: 给 Agent —— AGENT.md 手册
description: 怎么写文件定义一个 Agent，不用 Java。
---

# 给 Agent

你是 AI Agent。这是你的 `AGENT.md` 手册。

> 本页讲 Agent 定义格式。运行时怎么执行见 [给工程师](./for-engineer)。

## 你面对的问题

你需要知道三件事：

1. **我的任务是什么？** —— 系统提示词。
2. **我能调哪些工具？** —— Function calling schema。
3. **我怎么跑？** —— schedule / channel / settings。

OryxOS 三件事都通过一份 `AGENT.md` 给你：YAML frontmatter + Markdown 正文。

## 目录结构

一个 Agent = 一个目录。放在 `.oryxos/agents/<name>/` 下：

```
.oryxos/agents/daily-weather/
├── AGENT.md            # ★ 必填 —— profile（frontmatter）+ 系统提示词（正文）
├── skills/             # 可选 —— 子指令，按需读取
│   └── weather-format.md
├── scripts/            # 可选 —— 通过 shell tool 按需运行
│   └── historical.py
└── REFERENCE.md        # 可选 —— 词汇表 / 风格指南，按需读取
```

`AgentLoader` 启动时扫这个目录。子文件**不**预加载到系统提示词——你通过内置 `read_file` 按需取。

## AGENT.md 解剖

```markdown
---
name: daily-weather              # 必填 —— profile 名，全局唯一
description: 推送天气 + 穿搭建议  # 必填 —— 一句话摘要

provider:
  name: deepseek                 # 必填 —— 必须跟 application.yml 里的配置项匹配
  model: deepseek-chat           # 可选 —— 不填用 provider 默认
  temperature: 0.7               # 可选 —— 不填用 provider 默认

tools:                           # 可选 —— 限制可用 tool（默认全开）
  - http_get
  - notify
  - read_file
  - shell

skills:                          # 可选 —— 可读的 skills/*.md 文件清单
  - weather-format

mcp_servers:                     # 可选 —— 要挂载的 MCP server 名
  - github

channels:                        # 可选 —— 入站 channel（CLI 默认开）
  - name: cli
  - name: web

notify_channels:                 # 可选 —— 出站推送目标
  - type: webhook
    config:
      url: ${WEATHER_NOTIFY_URL}

bootstrap:                       # 可选 —— 注入 system prompt 的 Bootstrap 文件
  - AGENTS.md
  - SOUL.md
  - USER.md

schedules:                       # 可选 —— AgentScheduler 触发规则
  - id: morning-weather
    cron: "0 0 8 * * *"
    zone: Asia/Shanghai
    message: "查一下今天上海的天气，生成穿搭建议并推送"

settings:
  max_iterations: 10             # 默认 10 —— ReAct 循环上限
  max_history_turns: 20          # 默认 20 —— 对话历史截断
---

# Daily Weather Agent

你是一个每日天气助手...

1. 通过 `http_get` 查上海今天天气。
2. 生成简洁的穿搭建议。
3. 通过 `notify` 推送到群。
```

## Frontmatter 字段

| 字段                     | 必填 | 默认                              | 说明                                          |
| ------------------------ | ---- | --------------------------------- | --------------------------------------------- |
| `name`                   | ✅   | ——                                | 唯一 profile 名，必须跟目录名匹配              |
| `description`            | ✅   | ——                                | 一句话摘要                                     |
| `provider.name`          | ✅   | ——                                | 必须跟 `application.yml` 里 `oryxos.providers` 的某项匹配 |
| `provider.model`         | ❌   | provider 默认                     |                                               |
| `provider.temperature`   | ❌   | provider 默认                     |                                               |
| `tools`                  | ❌   | 全部启用                          | 子集白名单                                     |
| `skills`                 | ❌   | 无                                | `skills/*.md` 文件名清单                       |
| `mcp_servers`            | ❌   | 无                                | 来自 `.oryxos/mcp_servers.yaml` 的 server 名  |
| `channels`               | ❌   | `[cli, web]`                      | 入站 channel                                  |
| `notify_channels`        | ❌   | 无                                | 出站推送目标                                  |
| `bootstrap`              | ❌   | `[AGENTS.md, SOUL.md, USER.md]`   | 来自 `.oryxos/` 根                              |
| `schedules`              | ❌   | 无                                | `AgentScheduler` 的 cron 规则                  |
| `settings.max_iterations` | ❌  | `10`                              | ReAct 循环上限                                  |
| `settings.max_history_turns` | ❌ | `20`                            | 对话历史截断                                   |

**敏感字段**（`api_key`、`webhook.url` 等）用 `${ENV_VAR}` 占位，加载时从环境变量解析。**绝不硬编码密钥。**

## Skills（渐进式披露）

子指令在 `skills/*.md`。**不**预加载到 system prompt。你用 `read_file` 按需取。

例子：

```
.oryxos/agents/daily-tech-digest/
├── AGENT.md
└── skills/
    ├── digest-format.md     # 格式规则
    └── source-list.md       # 信源清单
```

在 `AGENT.md` 正文里这样写：

```markdown
写日报时：
1. 读 `skills/digest-format.md` 看格式规则。
2. 读 `skills/source-list.md` 看今天取哪些源。
3. 整合成日报。
```

Model 会调 `read_file` 取每个文件再继续。System prompt 保持精简。

## Scripts（沙箱信任边界）

脚本在 `scripts/`。通过 `shell` tool 运行。**脚本跑 Python 子进程能做任何事——绕开 `http_get` 的 URL 白名单。**

> ⚠️ **信任边界**：装一个带 `scripts/` 的 Agent = 信任这个 Agent 的作者不会偷偷外发数据。核心阶段不隔离脚本执行——容器 / microVM 隔离是扩展阶段的事。

```python
# scripts/github_trending.py
import urllib.request
import json

# 这里绕开 http_get 白名单 —— Agent 作者必须可信
with urllib.request.urlopen("https://api.github.com/trending") as r:
    data = json.loads(r.read())
print(json.dumps(data[:10]))
```

## 参考资料

`REFERENCE.md` 只在你读它时加载。放词汇表 / 风格指南 / 任何大块材料，Model 用到再查。

## Memory

跨对话记忆有三种方式：

1. **Session 记忆** —— 自动，跟着当前 Session 走。
2. **长期记忆（`MEMORY.md`）** —— 调 `memory_write` / `memory_read` 工具持久化。
3. **可插拔后端** —— `MarkdownMemoryStore`（默认）、`SqliteMemoryStore`、`Mem0MemoryStore`。

需要长期保留的偏好写到 CORE scope。大块结果归档到 ARCHIVE scope。

```text
memory.write("user.prefers.format", "table", scope=CORE)
memory.write("archive.2025-07-21.digest", "<完整日报>", scope=ARCHIVE)
```

`recallByKeyword` 工具搜两个 scope。

## Bootstrap 文件

`.oryxos/` 根目录有三个文件会影响你的 system prompt：

| 文件         | 用途                                       |
| ------------ | ------------------------------------------ |
| `AGENTS.md`  | 项目级 agent 行为——所有 Agent 共享的规则    |
| `SOUL.md`    | 默认 agent 人格 / 语气                     |
| `USER.md`    | 用户偏好——人喜欢什么                       |

启动时加载一次，附加到你的 system prompt。通过 `bootstrap` frontmatter 字段按 Agent 定制。

## 生命周期

1. **启动** —— `AgentLoader` 读你的 `AGENT.md`，派生 `Profile`，注册。
2. **触发** —— 消息到达（CLI / REST / scheduler）。
3. **ReAct 循环** —— 运行时调你（LLM）最多 `max_iterations` 次，中间穿插 tool 结果。
4. **响应** —— 最终答案返回到触发源（终端打印 / HTTP 响应 / 推送到 notify 渠道）。
5. **持久化** —— session 写入 SQLite。下次接着聊。

## 下一步

| 目标                                       | 看到什么                                          |
| ------------------------------------------ | ------------------------------------------------- |
| [给工程师](./for-engineer)                 | 构建、部署、扩展                                  |
| [快速开始](./quick-start)                  | 跑通三个 Demo                                      |
| [系统架构](./architecture)                 | 运行时怎么执行你的 AGENT.md                       |
| [七条原则](./constitution)                 | 七条不可改的宪法原则                              |