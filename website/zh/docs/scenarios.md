---
title: 使用场景 —— 6 个企业级案例
description: OryxOS 在企业里的 6 个具体用法。
---

# 使用场景

OryxOS 在企业里的 6 个具体用法。每个都描述 Agent 目录形态、触发源、覆盖的能力。

---

## 场景 01 —— 定时日报

**触发源**：`AgentScheduler` cron，每天早上 08:00。

**Agent 形态**：裸 `AGENT.md`，没有 `skills/`，没有 `scripts/`。

**流程**：

1. `AgentScheduler` 08:00 用配置的 `message` 触发。
2. Agent 调 `http_get` 取今天的数据（比如天气 / 新闻 / GitHub trending）。
3. Agent 用 LLM 起草摘要。
4. Agent 调 `notify` 推到团队 IM webhook。

**覆盖能力**：LLM + ReAct + HTTP Tool + NotifyTools + Sandbox + Scheduler。

**`AGENT.md` 例子**：

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
你是一个每日天气助手。每天早上：
1. 通过 `http_get` 查上海今天天气。
2. 生成简洁的穿搭建议。
3. 通过 `notify` 推送到群。
```

---

## 场景 02 —— 内部知识库问答

**触发源**：用户查询，CLI 或 REST。

**Agent 形态**：`AGENT.md` + `skills/` 子指令 + 可选 `REFERENCE.md`。

**流程**：

1. 用户提问（CLI `oryxos chat` 或 REST `POST /agents/{name}/invoke`）。
2. Agent 按需用 `read_file` 查 `skills/` 文件（渐进式披露）。
3. Agent 用 LLM 整合本地材料出答案。
4. 可选：把这次问答存进 `MEMORY.md`（CORE scope）备查。

**覆盖能力**：ReAct + Memory + read_file 按需 + Sandbox。

**为什么渐进式披露**：200 页的 `REFERENCE.md` 装不进 system prompt。按需加载，成本低，答案有据。

---

## 场景 03 —— 多 Agent 编排

**触发源**：用户查询，CLI 或 REST。

**Agent 形态**：一个编排 Agent + 多个子 Agent + `mcp_servers` 指向它们。

**流程**：

1. 编排 Agent 收到复杂请求。
2. 编排 Agent 通过 MCP 调子 Agent（每个子 Agent 是各自的 `AGENT.md`）。
3. 子 Agent 返回结果。
4. 编排 Agent 汇总，给出统一答复。

**覆盖能力**：ReAct + MCP 客户端 + Tool Registry + Memory（通过 MEMORY.md 跨 Agent）。

---

## 场景 04 —— 客服 + FAQ 兜底

**触发源**：客户通过 REST 提问。

**Agent 形态**：`AGENT.md` + `skills/faq.md`（FAQ 清单）+ `skills/escalation-rules.md`。

**流程**：

1. Agent 按需用 `read_file` 读 FAQ。
2. 问题匹配 FAQ 条目 → 直接回答。
3. 不匹配 → Agent 调 `notify` 升级到人工队列（或通过 HTTP 生成工单）。
4. 交互记入 `sessions`，事后审计。

**覆盖能力**：ReAct + read_file + HTTP / Notify + 审计。

---

## 场景 05 —— 长任务带进度汇报

**触发源**：定时任务（每小时 cron）或手动 REST。

**Agent 形态**：`AGENT.md` + `scripts/run_pipeline.py` + schedule 条目。

**流程**：

1. Agent 触发（定时或手动）。
2. Agent 通过 `shell` 跑 `scripts/run_pipeline.py`（沙箱：SHELL_COMMAND）。
3. 脚本可能跑几分钟；shell tool 等它。
4. Agent 读脚本输出，汇总，调 `notify` 推进度。
5. 检测到错误就写进 `MEMORY.md`（ARCHIVE scope）。

**覆盖能力**：Shell Tool + Sandbox + Memory + Notify + Scheduler。

> ⚠️ 脚本能自己发网络请求——绕开 `http_get` 的 URL 白名单。信任 Agent 作者。

---

## 场景 06 —— 审计驱动的合规报表

**触发源**：对 `tool_invocations` 和 `llm_calls` 的 SQL 查询（不调 Agent）。

**流程**：

这不是 Agent 场景——是后台对审计表的直接 SQL 查询。合规员直接跑 SQL：

```sql
-- 过去 24 小时所有失败的 tool 调用
SELECT created_at, profile_name, tool_name, error_message
FROM tool_invocations
WHERE created_at > datetime('now', '-1 day')
  AND success = 0;

-- 过去一周每个 profile 的 LLM 总花费
SELECT profile_name, provider, model,
       COUNT(*) AS calls,
       SUM(total_tokens) AS tokens
FROM llm_calls
WHERE created_at > datetime('now', '-7 day')
GROUP BY profile_name, provider, model;
```

**为什么重要**：合规问"上周二发生了什么"。OryxOS 一条 SQL 答。不需要日志收集管道。

---

## 场景里没有的东西

这些是**扩展阶段**特性，核心阶段场景不覆盖：

- ❌ 跨 Agent 多租户隔离
- ❌ 跨租户审计
- ❌ SSO 网关的 Agent 调用
- ❌ 每租户限流
- ❌ LLM token 实时 SSE 流

---

## 下一步

| 目标                                       | 看到什么                                          |
| ------------------------------------------ | ------------------------------------------------- |
| [快速开始](./quick-start)                  | 本地跑通三个 Demo                                  |
| [给工程师](./for-engineer)                 | 构建、部署、扩展                                  |
| [系统架构](./architecture)                 | 分层详解                                          |