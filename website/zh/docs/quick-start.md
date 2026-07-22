---
title: 快速开始 —— 跑通三个 Demo
description: 10 分钟内本地跑通 OryxOS 的三个端到端 Demo。
---

# 快速开始

本指南带你跑通 OryxOS 的三个端到端 Demo。三个 Demo 走的是同一条 `AgentService` 链路——只是触发源不同。

> ⏱️ 预计耗时：**5–10 分钟**，前提是你有 JDK 21 和一个 DeepSeek/Kimi API key。

---

## 前置条件

- **JDK 21+**
- **Maven 3.9+**
- 至少一个 Provider API key —— 推荐 DeepSeek（`DEEPSEEK_API_KEY`）
- 可选：一个 IM webhook URL（企业微信 / 飞书 / 钉钉）作为 `WEATHER_NOTIFY_URL`

---

## 第 1 步 —— 从源码构建

```bash
git clone https://github.com/oryxos/oryxos.git
cd oryxos
mvn -pl oryxos-boot -am clean package -DskipTests
```

输出：`oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar`。

---

## 第 2 步 —— 配置环境变量

```bash
export DEEPSEEK_API_KEY=sk-...你的 key...
export WEATHER_NOTIFY_URL=https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...   # 可选
```

密钥通过 `${ENV_VAR}` 占位，在 `application.yml` 和 `AGENT.md` 加载时解析。

---

## 第 3 步 —— 初始化工作区

```bash
java -jar oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar init
```

在当前目录创建 `.oryxos/`：

```
.oryxos/
├── agents/             # 空 —— 在这里丢 Agent 目录
├── memory/
│   └── MEMORY.md       # 长期记忆（默认 Markdown）
├── sessions/           # 运行时 session 数据
├── logs/               # 结构化 JSON 日志
├── AGENTS.md           # 项目级 agent 行为
├── SOUL.md             # 默认 agent 人格
├── USER.md             # 用户偏好
└── oryxos.db           # SQLite（5 张审计表）
```

---

## 第 4 步 —— 放入 Demo Agent

仓库在 `examples/agents/` 下自带三个 Demo Agent。复制过来：

```bash
cp -r examples/agents/daily-weather      .oryxos/agents/
cp -r examples/agents/daily-tech-digest  .oryxos/agents/
cp -r examples/agents/daily-github       .oryxos/agents/
```

每个就是一个带 `AGENT.md` 的目录（可选 `skills/` / `scripts/`）。

---

## 第 5 步 —— 启动 gateway

```bash
java -jar oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar gateway
```

`gateway` 模式**同时**跑三种触发源：

- CLI channel（交互式 REPL）
- :8080 REST API
- `AgentScheduler`（`AGENT.md` schedules 里的 cron）

应该看到：

```
[oryxos] started gateway
[oryxos] CLI channel:    ready
[oryxos] REST API:       http://localhost:8080
[oryxos] AgentScheduler: 3 schedules registered
[oryxos] Profiles:       daily-weather, daily-tech-digest, daily-github
```

---

## Demo 1 —— 每日天气（HTTP + Notify + Scheduler）

每日天气 Agent：

1. 每天早上 08:00 自动触发（cron `0 0 8 * * *`，时区 `Asia/Shanghai`）。
2. 调 `http_get` 拉上海天气（白名单域名）。
3. LLM 生成穿搭建议。
4. 通过 `notify` 推到 IM webhook。

**手动触发**（跟定时跑同一条 `AgentService` 链路）：

```bash
# CLI
oryxos chat --profile daily-weather

# REST
curl -X POST http://localhost:8080/api/v1/agents/daily-weather/invoke \
  -H "Content-Type: application/json" \
  -d '{"message":"今天上海天气怎么样？"}'
```

**能力覆盖**：LLM Provider + ReAct 循环 + HTTP Tool + NotifyTools + Sandbox + Scheduler。

---

## Demo 2 —— 每日科技日报（Memory + MCP + read_file）

每日科技日报 Agent：

1. 每天早上 09:00 自动触发。
2. 通过 `read_file` 读 `skills/source-list.md` 和 `skills/digest-format.md`（渐进式披露）。
3. 调新闻 MCP server 取新鲜内容。
4. 从 `MEMORY.md` 回忆用户偏好（比如"喜欢中文"）。
5. 写日报，推送到同一个 notify 渠道。

**手动触发**：

```bash
oryxos chat --profile daily-tech-digest
```

**能力覆盖**：Memory + MCP + read_file 按需 + NotifyTools + Scheduler。

---

## Demo 3 —— 每日 GitHub 日报（Shell + Sandbox + Memory）

每日 GitHub 日报 Agent：

1. 每天早上 09:30 自动触发。
2. 通过 `shell` 跑 `scripts/github_trending.py` 取 GitHub trending。
3. 解析 JSON 输出，LLM 总结。
4. 把日报存进 `MEMORY.md`，第二天可延续。
5. 推到同一个 notify 渠道。

**手动触发**：

```bash
oryxos chat --profile daily-github
```

**能力覆盖**：Shell Tool + 脚本沙箱边界 + Memory + NotifyTools + Scheduler。

---

## 验证审计表

每次 tool 调用、每次 LLM 调用都进 SQLite：

```bash
sqlite3 .oryxos/oryxos.db

sqlite> SELECT created_at, profile_name, tool_name, success
        FROM tool_invocations
        ORDER BY created_at DESC LIMIT 10;

sqlite> SELECT created_at, profile_name, provider, model, total_tokens, duration_ms
        FROM llm_calls
        ORDER BY created_at DESC LIMIT 10;
```

这就是审计地基：SQL 回放任意历史调用。

---

## 清理

```bash
# 停 gateway
Ctrl-C

# 看 session
sqlite3 .oryxos/oryxos.db "SELECT id, profile_name, created_at FROM sessions ORDER BY created_at DESC LIMIT 5;"
```

---

## 下一步

| 目标                                       | 看到什么                                          |
| ------------------------------------------ | ------------------------------------------------- |
| [给工程师](./for-engineer)                 | 构建、部署、扩展                                  |
| [给 Agent](./for-agent)                    | 不写 Java 定义自己的 Agent                        |
| [系统架构](./architecture)                 | 运行时怎么执行你的 Agent                          |
| [功能特性](./features)                     | 5 大核心能力的详细参考                            |
| [使用场景](./scenarios)                    | 6 个企业级场景                                    |