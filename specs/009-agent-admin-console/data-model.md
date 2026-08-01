# Data Model: 009-agent-admin-console

**Phase**: 1 — Design & Contracts
**Date**: 2026-07-29
**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md)

> 本数据模型基于 spec §关键实体 + Phase 0 R-003 锁定的 3 个聚合端点契约。
> 前端**仅消费**后端数据，**不**持久化任何业务实体。本节建模的是 **UI 渲染层视图模型（View Model）**，
> 而非新增业务表。

---

## 1. 实体总览

5 个核心实体 + 1 个派生实体（Step）。所有实体都是**只读视图模型**（M0 范围），
由后端 REST 响应 JSON 派生。

```text
┌─────────────┐         ┌──────────────┐         ┌─────────────┐
│   Session   │ 1────N  │     Step     │ N─────1 │     Tool    │
│  (sessions) │─────────┤  (derived)   │─────────│ (registry)  │
└─────────────┘         └──────────────┘         └─────────────┘
       │                                                │
       │ N                                              │ N
       │ 1                                              │ 1
       ▼                                                ▼
┌─────────────┐         ┌──────────────┐         ┌─────────────┐
│    Agent    │         │   Provider   │         │     Step    │
│  (profile)  │         │ (config YAML)│         │ (llm_calls) │
└─────────────┘         └──────────────┘         └─────────────┘
       │
       │ N
       │ 1
       ▼
┌─────────────┐
│  Schedule   │
│ (scheduled_ │
│    tasks)   │
└─────────────┘
```

---

## 2. Session 实体

### 2.1 来源

- 后端表：`sessions`（per `specs/008-agent-web-service` data-model）
- 端点：`POST /api/v1/sessions` / `POST /api/v1/sessions/{id}/messages` /
  `GET /api/v1/sessions/{id}` / `DELETE /api/v1/sessions/{id}`
- 列表：可能由后端新增 `GET /api/v1/sessions` 端点提供（需在 008 US-4 补，
  或由前端从聚合端点 + 单点拉取派生）

### 2.2 字段（视图模型）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | `string` (UUID) | ✅ | 主键 |
| `profile_name` | `string` | ✅ | Agent 名 |
| `metadata.source` | `enum: chat\|api\|scheduler\|web` | ✅ | 触发源；`web` 为管理后台手动触发 |
| `metadata.user_id` | `string?` | — | 用户标识（M0 阶段由前置 gateway 注入） |
| `status` | `enum: pending\|running\|success\|failed\|cancelled` | ✅ | 当前状态 |
| `message_count` | `integer` | ✅ | 对话轮数（user + assistant + tool） |
| `started_at` | `string (ISO 8601 UTC)` | ✅ | 服务端 UTC，前端按 `Intl.DateTimeFormat()` 渲染 |
| `ended_at` | `string?` | — | 结束时间 |
| `duration_ms` | `integer?` | — | 持续毫秒；`running` 状态时为已用时长 |
| `error_code` | `string?` | — | 顶层错误码（如 `TIMEOUT` / `SANDBOX_VIOLATION`） |
| `error_message` | `string?` | — | 不含 stack trace（per `specs/005-tool-system` SC-006） |

### 2.3 验证规则

| 规则 | 说明 |
|------|------|
| `status="failed"` | MUST 同时有 `error_code` + `error_message` |
| `status="success"` \| `"cancelled"` | MUST 同时有 `ended_at` |
| `status="pending"` \| `"running"` | `ended_at` MUST 为 null |
| `message_count >= 0` | 整数 |
| `duration_ms >= 0` | 整数 |
| `id` | MUST match UUID v4 格式 |

### 2.4 状态转换

```text
   ┌────────┐
   │pending │  ← 新建 Session，未开始
   └───┬────┘
       │ start
       ▼
   ┌────────┐
   │running │  ← ReAct 循环进行中
   └───┬────┘
       │
       ├─── normal ──→ ┌─────────┐
       │                │ success │  ← ReAct 完成无错误
       │                └─────────┘
       │
       ├─── error ────→ ┌────────┐
       │                │ failed │
       │                └────────┘
       │
       └─── abort ────→ ┌────────────┐
                        │ cancelled │  ← DELETE 端点触发
                        └────────────┘
```

### 2.5 关系

- `Session 1─N Step`（一个 Session 包含多个执行步骤）
- `Session N─1 Agent`（一个 Session 由一个 Agent 触发）
- `Session 1─1 Provider?`（最近一次 LLM 调用所属 Provider）

---

## 3. Agent（Profile）实体

### 3.1 来源

- 后端：`profile` YAML 文件 + `application.yaml` provider 配置
- 端点：`GET /api/v1/profiles`（per `specs/008-agent-web-service`）+ `GET /api/v1/stats/agents/{name}`（聚合端点）

### 3.2 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | `string` | ✅ | Agent 名（YAML `name`） |
| `description` | `string` | ✅ | YAML `description` |
| `provider` | `string` | ✅ | `application.yaml` provider name（如 `deepseek`） |
| `model` | `string` | ✅ | 模型 ID（如 `deepseek-v3`） |
| `temperature` | `number?` | — | 温度参数 |
| `tools[]` | `string[]` | ✅ | 可用 Tool 名称列表 |
| `skills[]` | `string[]` | — | 引用的 SKILL.md 文件 |
| `mcp_servers[]` | `string[]` | — | 引用的 MCP Server |
| `notify_channels[]` | `object[]` | — | 出站通道配置 |
| `schedules[]` | `object[]` | — | 定时规则 |
| `calls_24h` | `integer` | — | 24h 调用次数（聚合端点） |
| `calls_7d` | `integer` | — | 7d 调用次数 |
| `calls_30d` | `integer` | — | 30d 调用次数 |
| `tokens_24h` | `integer` | — | 24h token 消耗 |
| `tokens_7d` | `integer` | — | 7d token 消耗 |
| `tokens_30d` | `integer` | — | 30d token 消耗 |
| `cost_24h_usd` | `number` | — | 24h 估算费用 |
| `error_rate_24h` | `number` | — | 0-1 浮点 |
| `recent_calls[]` | `object[]` | — | 最近 50 次调用元数据 |
| `tool_distribution[]` | `object[]` | — | 各 Tool 24h 调用次数 |

### 3.3 验证规则

| 规则 | 说明 |
|------|------|
| `name` | MUST match `[a-z0-9-]{3,32}`（per `AgentLoader` 解析约定） |
| `provider` | MUST 在 `application.yaml` 中存在（per §V.4 §5 "显式 provider name → ChatModel 映射"） |
| `model` | MUST 在 `provider.models[]` 中存在 |
| `calls_24h + 7d + 30d` | 7d ≥ 24h；30d ≥ 7d（聚合单调性） |

### 3.4 关系

- `Agent 1─N Session`
- `Agent N─1 Provider`
- `Agent 1─N Tool`（通过 `tools[]`）
- `Agent 1─N Schedule`（通过 `schedules[]`）

---

## 4. Tool 实体

### 4.1 来源

- 后端：`ToolRegistry`（per `specs/005-tool-system`）
- 端点：`GET /api/v1/tools`

### 4.2 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | `string` | ✅ | Tool 名（如 `http_get` / `integration__echo`） |
| `source` | `enum: builtin\|mcp\|java_bean` | ✅ | Tool 来源（per §V.7 Tool 来源审计） |
| `description` | `string` | ✅ | Tool 描述 |
| `schema` | `object?` | — | JSON Schema（parameters） |
| `sandbox_action` | `enum?` | — | `FILE_READ` / `FILE_WRITE` / `SHELL_COMMAND` / `HTTP_REQUEST` |
| `allowed_domains` | `string[]?` | — | 域名白名单（仅 HTTP_REQUEST） |
| `calls_24h` | `integer` | — | 24h 调用次数 |
| `error_rate_24h` | `number` | — | 0-1 浮点 |

### 4.3 验证规则

| 规则 | 说明 |
|------|------|
| `source="builtin"` | FQCN MUST 以 `io.oryxos.tool.` 开头且不包含 `.mcp.` / `.javabean.` |
| `source="mcp"` | FQCN MUST 以 `io.oryxos.tool.mcp.` 开头；`name` 格式 `{server}__{tool}` |
| `source="java_bean"` | FQCN MUST NOT 以 `io.oryxos.tool.` 开头 |
| `sandbox_action="HTTP_REQUEST"` | `allowed_domains` MUST 非空 |
| `name` | MUST unique within `source` |

### 4.4 关系

- `Tool 1─N Step`（一次 Tool 调用产生一个 Step）

---

## 5. Provider 实体

### 5.1 来源

- 后端：`application.yaml` provider 配置 + 运行时 LLM 调用统计
- 端点：`GET /api/v1/providers`（per Phase 0 R-003）

### 5.2 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | `string` | ✅ | Provider 名 |
| `status` | `enum: healthy\|degraded\|down` | ✅ | 健康状态 |
| `models[]` | `string[]` | ✅ | 支持模型 ID |
| `calls_24h` | `integer` | ✅ | 24h 调用次数 |
| `tokens_24h` | `integer` | ✅ | 24h token 消耗 |
| `cost_24h_usd` | `number` | ✅ | 24h 估算费用 |
| `error_rate_24h` | `number` | ✅ | 0-1 浮点 |
| `p50_latency_ms` | `integer` | ✅ | P50 延迟 |
| `p95_latency_ms` | `integer` | ✅ | P95 延迟 |
| `calls_trend_24h[]` | `object[]` | ✅ | 24 小时桶时序（24 entries） |

### 5.3 验证规则

| 规则 | 说明 |
|------|------|
| `status="healthy"` | `error_rate_24h < 0.05` |
| `status="degraded"` | `0.05 ≤ error_rate_24h < 0.20` |
| `status="down"` | `error_rate_24h ≥ 0.20` 或最近 5min 无成功调用 |
| `calls_trend_24h.length === 24` | 24 hour buckets |
| `p95_latency_ms ≥ p50_latency_ms` | 单调性 |
| `name` | MUST 与 `application.yaml` `spring.ai.<provider>.name` 一致 |

### 5.4 关系

- `Provider 1─N Agent`（一个 Provider 可被多个 Agent 引用）
- `Provider 1─N Step`（每次 LLM 调用关联到 Provider）

---

## 6. Step 实体（派生）

### 6.1 来源

- 派生：`sessions` 对话历史 JOIN `tool_invocations` JOIN `llm_calls` 三表
- 端点：`GET /api/v1/sessions/{id}`（响应包含内嵌的 steps[]）

### 6.2 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | `string` | ✅ | Step ID（UUID） |
| `session_id` | `string` | ✅ | 所属 Session |
| `sequence` | `integer` | ✅ | 步骤序号（从 1 开始） |
| `type` | `enum: user_input\|llm_call\|tool_call\|notify\|final_answer` | ✅ | 5 类 Step |
| `timestamp` | `string (ISO 8601 UTC)` | ✅ | 时间戳 |
| `duration_ms` | `integer` | ✅ | 步骤执行耗时 |
| `success` | `boolean` | ✅ | 是否成功 |
| `summary` | `string` | ✅ | 摘要（用于折叠态显示） |
| `details` | `object` | ✅ | 完整详情（展开态显示） |
| `error_message` | `string?` | — | 失败时填充（per §V.7 SC-006 不含 stack trace） |

### 6.3 5 类 Step 详情结构

#### user_input

```json
{
  "role": "user",
  "content": "<text>",
  "content_preview": "<前 200 字符>"
}
```

#### llm_call

```json
{
  "provider": "deepseek",
  "model": "deepseek-v3",
  "input_tokens": 1234,
  "output_tokens": 567,
  "tool_calls_requested": ["http_get", "notify"],
  "response_preview": "<前 200 字符>"
}
```

#### tool_call

```json
{
  "tool_name": "http_get",
  "args": { "url": "https://wttr.in/Shanghai" },
  "args_preview": "<前 200 字符>",
  "result_preview": "<前 200 字符>",
  "sandbox_action": "HTTP_REQUEST",
  "sandbox_decision": "allowed" | "blocked: domain not whitelisted",
  "sandbox_allowed_domains": ["api.deepseek.com", "wttr.in", "api.github.com"]
}
```

#### notify

```json
{
  "channel": "default",
  "content_preview": "<前 200 字符>",
  "status_code": 200,
  "endpoint": "https://qyapi.weixin.qq.com/..."
}
```

#### final_answer

```json
{
  "content_preview": "<前 200 字符>",
  "is_error_fallback": false
}
```

### 6.4 验证规则

| 规则 | 说明 |
|------|------|
| `sequence` | MUST 单调递增，从 1 开始 |
| `type="tool_call"` | `details.tool_name` MUST 非空 |
| `type="tool_call" && sandbox_action="HTTP_REQUEST"` | `details.sandbox_decision` MUST 非空 |
| `success=false` | MUST 同时有 `error_message` |
| `content_preview` | MUST ≤ 200 字符（防泄露） |

### 6.5 关系

- `Step N─1 Session`
- `Step N─1 Tool?`（仅 `tool_call` 类型）
- `Step N─1 Provider?`（仅 `llm_call` 类型）

---

## 7. Schedule 实体

### 7.1 来源

- 后端表：`scheduled_tasks`（per `specs/008-agent-scheduler`）
- 端点：M0 阶段无 REST 端点，使用 CLI `oryxos schedule list`（per §V.8 mockup 说明）；
  M1 阶段新增 `GET /api/v1/schedules`

### 7.2 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `task_id` | `string` | ✅ | 任务 ID |
| `profile_name` | `string` | ✅ | 关联 Agent |
| `cron` | `string` | ✅ | cron 表达式 |
| `zone` | `string` | ✅ | 时区（如 `Asia/Shanghai`） |
| `message` | `string` | ✅ | 触发时发给 Agent 的消息 |
| `enabled` | `boolean` | ✅ | 是否启用 |
| `last_run_at` | `string?` | — | 上次执行时间 |
| `next_run_at` | `string?` | — | 下次执行时间 |

### 7.3 验证规则

| 规则 | 说明 |
|------|------|
| `cron` | MUST match 标准 5 段 cron（per §V.9 cron 表达式） |
| `zone` | MUST 是合法 IANA 时区（如 `Asia/Shanghai`、`UTC`） |
| `enabled=false` | `last_run_at` 保留；`next_run_at` 为 null |

---

## 8. Dashboard 聚合实体

### 8.1 来源

- 端点：`GET /api/v1/stats/dashboard`
- 缓存：5 秒 TTL（per Phase 0 R-003 §3.1）

### 8.2 字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `summary.llm_calls_24h` | `integer` | 24h LLM 调用总数 |
| `summary.llm_calls_yesterday` | `integer` | 昨日同期（用于 ▲ 比较） |
| `summary.tool_calls_24h` | `integer` | 24h Tool 调用总数 |
| `summary.tool_calls_yesterday` | `integer` | 昨日同期 |
| `summary.active_sessions` | `integer` | 当前活跃 Session（status=running） |
| `summary.active_sessions_yesterday` | `integer` | 昨日同期 |
| `summary.failed_sessions_24h` | `integer` | 24h 失败 Session 数 |
| `summary.failed_sessions_rate` | `number` | 失败率（0-1） |
| `token_trend_24h[]` | `object[]` | 24 个 hour bucket（per Phase 0 R-003） |
| `top_failed_sessions[]` | `object[]` | Top 5 失败 Session |
| `recent_events[]` | `object[]` | 最近 5 个 Session |

### 8.3 验证规则

| 规则 | 说明 |
|------|------|
| `summary.*` | MUST ≥ 0 |
| `token_trend_24h.length === 24` | 24 hour buckets |
| `top_failed_sessions.length ≤ 5` | 最多 5 条 |
| `recent_events.length ≤ 5` | 最多 5 条 |

---

## 9. 关系矩阵

| 实体 1 | 关系 | 实体 2 | 基数 | 备注 |
|--------|------|--------|------|------|
| Session | 拥有 | Step | 1─N | `session.id = step.session_id` |
| Session | 属于 | Agent | N─1 | `session.profile_name = agent.name` |
| Session | 最近 LLM 关联 | Provider | N─1 | 派生字段，最近一次 llm_call.provider |
| Agent | 配置 | Provider | N─1 | `agent.provider = provider.name` |
| Agent | 引用 | Tool | N─N | `agent.tools[]` ↔ `tool.name` |
| Agent | 配置 | Schedule | 1─N | `agent.schedules[]` |
| Tool | 触发 | Step | 1─N | `step.type=tool_call → step.details.tool_name = tool.name` |
| Provider | 触发 | Step | 1─N | `step.type=llm_call → step.details.provider = provider.name` |

---

## 10. 数据生命周期

| 实体 | 来源表 | 保留策略 | 备注 |
|------|--------|---------|------|
| Session | `sessions` | 永久（per §V.13） | 审计 day-one |
| Step | `sessions.history` + `tool_invocations` + `llm_calls` | 同 Session | 三表 JOIN 派生 |
| Agent | `.oryxos/profiles/*.yaml` + `application.yaml` | 配置驱动 | 重启后重读 |
| Tool | `ToolRegistry` 内存 + YAML | 配置驱动 | 重启后重注册 |
| Provider | `application.yaml` | 配置驱动 | 重启后重读 |
| Schedule | `scheduled_tasks` | 永久 | 审计 day-one |

---

## 11. 类型导出（TypeScript 视角）

> 自动生成的 `src/api/generated/models/` 应包含以下类型（per Phase 0 R-002）：

```typescript
// 自动生成，不手写
type SessionStatus = 'pending' | 'running' | 'success' | 'failed' | 'cancelled'
type SessionSource = 'chat' | 'api' | 'scheduler' | 'web'
type ToolSource = 'builtin' | 'mcp' | 'java_bean'
type ProviderStatus = 'healthy' | 'degraded' | 'down'
type StepType = 'user_input' | 'llm_call' | 'tool_call' | 'notify' | 'final_answer'

interface Session { /* per §2.2 */ }
interface Agent { /* per §3.2 */ }
interface Tool { /* per §4.2 */ }
interface Provider { /* per §5.2 */ }
interface Step { /* per §6.2 + §6.3 */ }
interface Schedule { /* per §7.2 */ }
interface DashboardStats { /* per §8.2 */ }
```

**前端手写视图模型**（在 `src/lib/view-models/`，与自动生成类型解耦）：

```typescript
// src/lib/view-models/session.ts
export interface SessionWithDerived extends Session {
  display_started_at: string       // 相对时间（「3 分钟前」）
  is_recent_failure: boolean       // 24h 内失败
}

// src/lib/view-models/dashboard.ts
export interface DashboardTiles {
  llm_calls: { value: number; delta_pct: number; trend: 'up' | 'down' | 'flat' }
  tool_calls: { value: number; delta_pct: number; trend: 'up' | 'down' | 'flat' }
  active_sessions: { value: number; delta_pct: number }
  failed_sessions: { value: number; delta_pct: number; severity: 'warning' | 'danger' | 'ok' }
}
```

---

## 12. 索引与查询模式

> 不新增索引；复用 `specs/008-agent-web-service` 既有索引。
> 高频查询：

| 查询 | 表 | 索引假设 |
|------|-----|---------|
| Session 列表 | `sessions` | `idx_sessions_started_at` |
| Session 详情 + Steps | `sessions` + `tool_invocations` + `llm_calls` | `idx_tool_invocations_session_id` / `idx_llm_calls_session_id` |
| Dashboard 聚合 | 三表 + `task_executions` | 既有索引覆盖 |
| Provider 列表 | `llm_calls` + `application.yaml` | `idx_llm_calls_provider_time` |

---

## 13. 数据完整性约束

| 约束 | 层级 | 说明 |
|------|------|------|
| Session 不孤立 | 后端 | `profile_name` MUST 引用已注册 Agent |
| Step 不孤立 | 后端 | `session_id` MUST 引用已存在 Session |
| Tool 来源审计 | 后端 | `tool_invocations.source` MUST 符合 §V.7 规则 |
| Provider name 一致 | 后端 | `llm_calls.provider` MUST 匹配 `application.yaml` |
| 时区一致性 | 前端 | 所有时间戳服务端 UTC + ISO 8601；前端按 `Intl.DateTimeFormat()` 渲染 |

---

## 14. 验收映射

| 验收 | 数据模型覆盖 |
|------|------------|
| SC-001 5 分钟定位失败 | Session.status + Step.error_message + Tool.sandbox_decision |
| SC-002 成本可视化 | Provider.tokens_24h / cost_24h_usd + Dashboard.token_trend_24h |
| SC-003 审计导出 | Session + Step + Tool_invocations（per FR-013 / FR-014） |
| SC-004 手动触发闭环 | Session.metadata.source="web" |
| SC-005 5 秒首屏 | Dashboard 聚合端点 5 秒 TTL + 静态资源 gzip |
| SC-006 视觉一致 | 调色板锁定（per FR-020） |
| SC-007 可达性 | Step.sequence 单调 + `aria-label`（per FR-018） |