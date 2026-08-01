# API Endpoints Contract: 009-agent-admin-console

**Phase**: 1 — Design & Contracts
**Date**: 2026-07-29
**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Data Model**: [data-model.md](../data-model.md) | **Research**: [research.md](../research.md)

> 本契约锁定管理后台所需的 **13 个 REST 端点**：
> - **10 个**继承自 [specs/008-agent-web-service](../008-agent-web-service/spec.md)（基线 Web Service）
> - **3 个**聚合端点（per Phase 0 R-003）—— **追加**到 008-agent-web-service US-4
>
> 自动生成产物：`src/api/generated/services/XxxService.ts`（per Phase 0 R-002）

---

## 1. 端点矩阵

| # | 方法 | 路径 | 用途 | 来源 | M0 用法 |
|---|------|------|------|------|--------|
| 1 | POST | `/api/v1/sessions` | 创建 Session | 008 既有 | US-4 手动触发间接 |
| 2 | POST | `/api/v1/sessions/{id}/messages` | 发送消息 | 008 既有 | US-4 手动触发间接 |
| 3 | GET | `/api/v1/sessions/{id}` | Session 详情 | 008 既有 | **US-1 主路径** |
| 4 | DELETE | `/api/v1/sessions/{id}` | 删除 Session | 008 既有 | M0 仅文档，不暴露 UI |
| 5 | POST | `/api/v1/agents/{name}/invoke` | 触发 Agent | 008 既有 | **US-4 手动触发** |
| 6 | GET | `/api/v1/profiles` | Agent 列表 | 008 既有 | US-2 主路径 |
| 7 | GET | `/api/v1/memory` | 长期记忆 | 008 既有 | M1 阶段 |
| 8 | GET | `/api/v1/tools` | Tool 列表 | 008 既有 | **US-2 主路径** |
| 9 | GET | `/api/v1/health` | 健康检查 | 008 既有 | **FR-015 顶部导航** |
| 10 | GET | `/api/v1/info` | 服务元信息 | 008 既有 | 页脚版本号 |
| 11 | GET | `/api/v1/stats/dashboard` | Dashboard 聚合 | **008 US-4 新增** | **US-2 主路径** |
| 12 | GET | `/api/v1/stats/agents/{name}` | Agent 详情聚合 | **008 US-4 新增** | **US-2 主路径** |
| 13 | GET | `/api/v1/providers` | Provider 列表 | **008 US-4 新增** | **US-2 主路径** |

---

## 2. 端点契约详情

### 2.1 POST /api/v1/sessions

**用途**：创建 Session（US-4 手动触发流程的第 1 步，由前端间接调用；US-4 暴露的 UI 入口实际走 `/api/v1/agents/{name}/invoke`）。

**请求**：

```typescript
interface CreateSessionRequest {
  profile_name: string
  metadata?: Record<string, unknown>
}
```

**响应 201**：

```typescript
interface Session {
  id: string
  profile_name: string
  metadata: { source: 'chat' | 'api' | 'scheduler' | 'web'; user_id?: string }
  status: 'pending' | 'running' | 'success' | 'failed' | 'cancelled'
  message_count: number
  started_at: string  // ISO 8601 UTC
  ended_at: string | null
  duration_ms: number | null
  error_code: string | null
  error_message: string | null
}
```

**错误**：

| 状态码 | 含义 |
|--------|------|
| 400 | `profile_name` 不存在 |
| 500 | 服务端异常 |

---

### 2.2 POST /api/v1/sessions/{id}/messages

**用途**：发送消息（US-4 间接使用）。

**请求**：

```typescript
interface SendMessageRequest {
  content: string
  // 可选：tool override / prompt override（M1 阶段）
}
```

**响应 202**（异步触发 ReAct 循环）：

```typescript
interface SendMessageResponse {
  session_id: string
  accepted: true
}
```

**错误**：400 / 404（session 不存在）/ 409（session 已结束）。

---

### 2.3 GET /api/v1/sessions/{id}

**用途**：**US-1 主路径**—— Session 详情 + 内嵌 Steps。

**响应 200**：

```typescript
interface SessionDetailResponse extends Session {
  steps: Step[]
}

interface Step {
  id: string
  session_id: string
  sequence: number        // 1-based
  type: 'user_input' | 'llm_call' | 'tool_call' | 'notify' | 'final_answer'
  timestamp: string       // ISO 8601 UTC
  duration_ms: number
  success: boolean
  summary: string         // ≤ 80 字符
  details: UserInputDetails | LlmCallDetails | ToolCallDetails | NotifyDetails | FinalAnswerDetails
  error_message: string | null
}

// 5 类 details（per data-model.md §6.3）
interface UserInputDetails {
  role: 'user'
  content: string
  content_preview: string  // ≤ 200 字符
}

interface LlmCallDetails {
  provider: string
  model: string
  input_tokens: number
  output_tokens: number
  tool_calls_requested: string[]
  response_preview: string
}

interface ToolCallDetails {
  tool_name: string
  args: Record<string, unknown>
  args_preview: string     // ≤ 200 字符
  result_preview: string
  sandbox_action: 'FILE_READ' | 'FILE_WRITE' | 'SHELL_COMMAND' | 'HTTP_REQUEST' | null
  sandbox_decision: 'allowed' | `blocked: ${string}` | null
  sandbox_allowed_domains: string[]
}

interface NotifyDetails {
  channel: string
  content_preview: string
  status_code: number | null
  endpoint: string
}

interface FinalAnswerDetails {
  content_preview: string
  is_error_fallback: boolean
}
```

**错误**：404（session 不存在）。

**轮询频率**：5s（per FR-007）。`status=running` 时前端会自动轮询。

---

### 2.4 DELETE /api/v1/sessions/{id}

**用途**：删除 Session（M0 仅文档，UI 不暴露删除按钮；扩展阶段用于清理）。

**响应 204**：No Content。

**错误**：404 / 409（session 仍在运行）。

---

### 2.5 POST /api/v1/agents/{name}/invoke

**用途**：**US-4 主路径**——手动触发 Agent（与 CLI / Scheduler 走完全相同的 `AgentService.process()` 入口）。

**请求**：

```typescript
interface InvokeAgentRequest {
  message: string
  metadata?: {
    source?: 'web'  // MUST 设为 'web'，标记管理后台触发
    user_id?: string
  }
}
```

**响应 202**：

```typescript
interface InvokeAgentResponse {
  session_id: string   // 用于跳转详情
  status: 'pending'
}
```

**错误**：

| 状态码 | 含义 |
|--------|------|
| 404 | Agent 不存在 |
| 400 | `message` 为空 |
| 503 | Provider 不可用 |
| 504 | 调用超时（> 30s） |

**审计**：写入 `sessions.metadata.source="web"` + `tool_invocations.source="builtin"`（per §V.7）。

---

### 2.6 GET /api/v1/profiles

**用途**：Agent 列表。

**响应 200**：

```typescript
interface ProfilesResponse {
  profiles: Agent[]
}

interface Agent {
  name: string
  description: string
  provider: string
  model: string
  temperature: number | null
  tools: string[]
  skills: string[]
  mcp_servers: string[]
  notify_channels: NotifyChannel[]
  schedules: Schedule[]
}

interface NotifyChannel {
  type: string  // 'webhook' | 'email' 等
  config: Record<string, unknown>
}

interface Schedule {
  id: string
  cron: string
  zone: string
  message: string
}
```

**注意**：此端点**不**返回运行时统计（calls_24h 等），运行时统计走 `/stats/agents/{name}`。

---

### 2.7 GET /api/v1/memory

**用途**：长期记忆查询（M1 阶段，本 spec 仅占位）。

**M0 行为**：端点存在但前端不展示 UI；保留供未来扩展。

---

### 2.8 GET /api/v1/tools

**用途**：Tool 列表（含来源审计）。

**响应 200**：

```typescript
interface ToolsResponse {
  tools: Tool[]
}

interface Tool {
  name: string
  source: 'builtin' | 'mcp' | 'java_bean'
  description: string
  schema: Record<string, unknown> | null    // JSON Schema
  sandbox_action: 'FILE_READ' | 'FILE_WRITE' | 'SHELL_COMMAND' | 'HTTP_REQUEST' | null
  allowed_domains: string[]
  calls_24h: number        // 服务端聚合
  error_rate_24h: number   // 0-1 浮点
}
```

---

### 2.9 GET /api/v1/health

**用途**：**FR-015**——顶部导航健康状态徽章。

**响应 200**：

```typescript
interface HealthResponse {
  status: 'UP' | 'DOWN' | 'DEGRADED'
  components: {
    database: 'UP' | 'DOWN'
    llm_providers: Record<string, 'UP' | 'DOWN' | 'DEGRADED'>
    scheduler: 'UP' | 'DOWN'
  }
  version: string  // 服务端版本（如 '0.1.0'）
}
```

**轮询频率**：30s（per FR-007）。

---

### 2.10 GET /api/v1/info

**用途**：服务元信息（页脚版本号）。

**响应 200**：

```typescript
interface InfoResponse {
  version: string
  build_time: string  // ISO 8601
  java_version: string
  profiles_count: number
  tools_count: number
  agents_dir: string  // 如 '.oryxos/agents'
}
```

---

### 2.11 GET /api/v1/stats/dashboard **【新增】**

**用途**：Dashboard 首屏聚合。

**响应 200**（per Phase 0 R-003 §3.1）：

```typescript
interface DashboardStats {
  summary: {
    llm_calls_24h: number
    llm_calls_yesterday: number
    tool_calls_24h: number
    tool_calls_yesterday: number
    active_sessions: number
    active_sessions_yesterday: number
    failed_sessions_24h: number
    failed_sessions_rate: number  // 0-1 浮点
  }
  token_trend_24h: Array<{
    hour: string         // ISO 8601 hour bucket
    tokens: number       // prompt + completion 总和
  }>                    // length === 24
  top_failed_sessions: Array<{
    id: string
    agent_name: string
    status: 'failed'
    error_code: string
    started_at: string
  }>                    // length ≤ 5
  recent_events: Array<{
    timestamp: string
    agent_name: string
    source: 'chat' | 'api' | 'scheduler' | 'web'
    session_id: string
    duration_ms: number
    status: 'success' | 'failed' | 'running'
  }>                    // length ≤ 5
}
```

**轮询频率**：30s（per FR-007）。

**缓存**：服务端 5 秒 TTL。

---

### 2.12 GET /api/v1/stats/agents/{name} **【新增】**

**用途**：Agent 详情聚合（US-2 主路径）。

**响应 200**（per Phase 0 R-003 §3.2）：

```typescript
interface AgentStats {
  agent_name: string
  calls_24h: number
  calls_7d: number
  calls_30d: number
  tokens_24h: number
  tokens_7d: number
  tokens_30d: number
  cost_24h_usd: number
  cost_7d_usd: number
  cost_30d_usd: number
  error_rate_24h: number       // 0-1 浮点
  recent_calls: Array<{        // 最近 50 条
    session_id: string
    source: 'chat' | 'api' | 'scheduler' | 'web'
    message_count: number
    duration_ms: number
    status: 'success' | 'failed' | 'running'
    started_at: string
  }>
  tool_distribution: Array<{   // 24h 各 Tool 调用次数
    tool_name: string
    source: 'builtin' | 'mcp' | 'java_bean'
    count_24h: number
  }>
}
```

**轮询频率**：60s（per FR-007）。

---

### 2.13 GET /api/v1/providers **【新增】**

**用途**：Provider 列表（US-2 主路径）。

**响应 200**（per Phase 0 R-003 §3.3）：

```typescript
interface ProvidersResponse {
  providers: Provider[]
}

interface Provider {
  name: string
  status: 'healthy' | 'degraded' | 'down'
  models: string[]
  calls_24h: number
  tokens_24h: number
  cost_24h_usd: number
  error_rate_24h: number       // 0-1 浮点
  p50_latency_ms: number
  p95_latency_ms: number
  calls_trend_24h: Array<{     // 24 hour buckets
    hour: string
    calls: number
  }>                          // length === 24
}
```

**轮询频率**：5min（per FR-007）。

---

## 3. 通用约定

### 3.1 错误响应

```typescript
interface ApiError {
  code: string         // 机器可读，如 'PROFILE_NOT_FOUND'
  message: string      // 人类可读（中文）
  details?: unknown    // 可选补充
}

interface ApiErrorResponse {
  error: ApiError
  timestamp: string
  path: string
}
```

| HTTP 状态 | code 前缀 | 示例 |
|-----------|-----------|------|
| 400 | `BAD_REQUEST` | `BAD_REQUEST_INVALID_ARG` |
| 404 | `NOT_FOUND` | `PROFILE_NOT_FOUND` / `SESSION_NOT_FOUND` / `TOOL_NOT_FOUND` |
| 409 | `CONFLICT` | `SESSION_ALREADY_ENDED` |
| 500 | `INTERNAL` | `INTERNAL_STORAGE_ERROR` |
| 503 | `UNAVAILABLE` | `PROVIDER_UNAVAILABLE` |
| 504 | `TIMEOUT` | `INVOKE_TIMEOUT` |

### 3.2 分页

仅 Session 列表需分页（per FR-010 / FR-013）。约定：

```typescript
interface PageResponse<T> {
  items: T[]
  total: number
  page: number          // 1-based
  page_size: number
}

interface PageQuery {
  page?: number        // 默认 1
  page_size?: number   // 默认 50，最大 200
}
```

### 3.3 时间约定

- **服务端**：所有时间戳 UTC + ISO 8601 字符串
- **前端**：`new Date(iso).toLocaleString()`（按浏览器时区）
- **不允许** epoch 毫秒数 / 自定义格式

### 3.4 缓存头

| 端点 | Cache-Control |
|------|---------------|
| `/api/v1/health` | `no-cache` |
| `/api/v1/info` | `max-age=3600` |
| `/api/v1/stats/dashboard` | `max-age=5` |
| `/api/v1/stats/agents/{name}` | `max-age=10` |
| `/api/v1/providers` | `max-age=60` |
| `/api/v1/sessions/{id}` | `no-cache`（强一致） |
| 其他 | `no-cache` |

### 3.5 鉴权

M0 阶段**无 SPA 内鉴权**（per A-006）。鉴权由前置 API gateway 处理；
M0 前端 HTTP 客户端**不带** Authorization header（由 Nginx / Spring Cloud Gateway 注入）。

### 3.6 CORS

M0 部署形态：管理后台与后端同源（Nginx 反代或 Docker 内部 DNS），**不需要** CORS。
如未来拆分部署，需在网关层处理 CORS。

---

## 4. 端点 → 页面映射

| 端点 | Dashboard | Agents | Sessions | Tools | Providers | Schedules |
|------|:---------:|:------:|:--------:|:-----:|:---------:|:---------:|
| `/health` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `/info` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `/sessions` (POST) | — | US-4 触发 | — | — | — | — |
| `/sessions/{id}/messages` | — | US-4 触发 | — | — | — | — |
| `/sessions/{id}` (GET) | 详情跳转 | — | 列表 + 详情 | — | — | — |
| `/sessions/{id}` (DELETE) | — | — | M0 不暴露 | — | — | — |
| `/agents/{name}/invoke` | — | **US-4 主** | 跳转详情 | — | — | — |
| `/profiles` | — | 列表 + 详情 | — | — | — | — |
| `/memory` | M1 占位 | M1 占位 | M1 占位 | M1 占位 | M1 占位 | M1 占位 |
| `/tools` | — | — | — | 列表 | — | — |
| `/stats/dashboard` | **首屏主** | — | — | — | — | — |
| `/stats/agents/{name}` | — | **详情主** | — | — | — | — |
| `/providers` | — | — | — | — | **列表主** | — |

---

## 5. openapi-typescript-codegen 集成

### 5.1 生成命令

```bash
# 后端服务运行后（默认 http://localhost:8080）
npx openapi-typescript-codegen \
  --input http://localhost:8080/v3/api-docs \
  --output ./src/api/generated \
  --client axios \
  --useUnionTypes
```

### 5.2 生成产物结构

```text
src/api/generated/
├── index.ts                       # 入口
├── models/                        # 类型定义
│   ├── Session.ts
│   ├── Agent.ts
│   ├── Tool.ts
│   ├── Provider.ts
│   ├── Step.ts
│   ├── DashboardStats.ts
│   ├── AgentStats.ts
│   ├── HealthResponse.ts
│   └── ... (共 ~15 个 model 文件)
└── services/                      # Service 类（可调用方法）
    ├── SessionsService.ts
    ├── AgentsService.ts
    ├── ProfilesService.ts
    ├── ToolsService.ts
    ├── StatsService.ts           # 包含 dashboard + agents/{name}
    ├── ProvidersService.ts       # 包含 providers 端点
    └── HealthService.ts
```

### 5.3 使用示例

```typescript
// src/stores/dashboard.ts
import { StatsService } from '@/api/generated'

export const useDashboardStore = defineStore('dashboard', () => {
  const stats = ref<DashboardStats | null>(null)

  async function fetchStats() {
    const response = await StatsService.getDashboardStats()
    stats.value = response
  }

  return { stats, fetchStats }
})
```

### 5.4 自动生成代码的提交策略

**提交**：`src/api/generated/` 进 git（团队协作时保证类型一致）
**重新生成**：CI 中执行 `npm run gen:api`（自动）；本地手改需 `git diff` 后提交

---

## 6. 错误处理约定

### 6.1 Axios 拦截器

```typescript
// src/api/http.ts
import axios from 'axios'
import { message } from '@/lib/naive-ui'  // Naive UI 的全局 message

export const http = axios.create({
  baseURL: import.meta.env.VITE_ORYXOS_BACKEND_URL || 'http://localhost:8080',
  timeout: 30_000,
})

http.interceptors.response.use(
  (resp) => resp,
  (err) => {
    const status = err.response?.status
    const apiError = err.response?.data?.error

    if (status === 404) {
      // 不弹 toast，由页面 ErrorState 组件渲染
    } else if (status === 503) {
      message.error(`Provider 不可用：${apiError?.message ?? '未知'}`)
    } else if (status === 504) {
      message.warning('调用超时，请稍后重试')
    } else if (status >= 500) {
      message.error(`服务异常：${apiError?.code ?? 'INTERNAL'}`)
    } else {
      message.warning(apiError?.message ?? '请求失败')
    }

    return Promise.reject(err)
  }
)
```

### 6.2 页面级错误态

每个页面 MUST 处理三种状态：

| 状态 | 渲染 | 触发 |
|------|------|------|
| Loading | `<Spin>` 或骨架屏 | 数据 fetch 中 |
| Error | `<ErrorState :retry="refetch" />` | HTTP 4xx / 5xx |
| Empty | `<EmptyState :hint="..." />` | 数据为空数组 |

---

## 7. 请求防抖与节流

| 场景 | 策略 |
|------|------|
| 搜索框（Filter Bar） | `debounce 300ms` |
| URL 同步（过滤条件） | `router.replace({ query })`，无防抖 |
| 健康检查轮询 | `setInterval(30_000)` + `visibilitychange` 暂停 |
| Session 详情轮询 | `setInterval(5_000)` + `visibilitychange` 暂停 + 立即刷新 |
| 手动触发按钮 | `disabled` 状态防重 |
| 大批量导出 | 后端流式（per FR-013 / FR-014） |

---

## 8. 类型导出一致性检查

| 检查项 | 校验方式 |
|--------|---------|
| 自动生成类型 + 手写视图模型不冲突 | `tsc --noEmit` 通过 |
| 后端字段变更 → 前端 CI 失败 | `npm run gen:api` 必须 re-run |
| 路径参数命名一致（`{id}` / `{name}`） | OpenAPI 契约 |

---

## 9. 待办与后续

| 项 | 阶段 | 备注 |
|----|------|------|
| 后端补充 `/api/v1/sessions?agent_name=&status=&from=&to=` 列表端点 | 008 US-4 | 当前 008 仅有单点 GET；列表依赖前端从聚合端点派生或新增端点 |
| `/api/v1/schedules` 列表端点 | M1 | M0 阶段用 CLI 替代 |
| `/api/v1/sessions/{id}/export` CSV 流式端点 | M0.5 | FR-013 / FR-014；M0 用前端聚合导出（< 1000 行足够） |
| WebSocket 流式更新 | M1 | 当前用 5s 轮询 |

---

## 10. 验收映射

| 验收 | 端点覆盖 |
|------|---------|
| SC-001 5 分钟定位失败 | `/sessions/{id}`（GET）返回 5 步 Step |
| SC-002 成本可视化 | `/stats/dashboard` + `/providers` + `/stats/agents/{name}` |
| SC-003 审计导出 | `/sessions` 列表 + 客户端聚合 |
| SC-004 手动触发闭环 | `/agents/{name}/invoke`（POST）→ 跳 `/sessions/{id}` |
| SC-005 5 秒首屏 | `/stats/dashboard`（5s TTL）+ `/health` |
| SC-006 视觉一致 | FR-004 调色板锁定 |
| SC-007 可达性 | FR-017 / FR-018 |