# Pinia Stores Contract: 009-agent-admin-console

**Phase**: 1 — Design & Contracts
**Date**: 2026-07-29
**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Data Model**: [data-model.md](../data-model.md) | **API**: [api-endpoints.md](api-endpoints.md) | **Components**: [ui-components.md](ui-components.md)

> 本契约锁定 **6 个 Pinia stores** 的接口（state / actions / getters），
> 是页面与 API 之间的唯一胶水层。所有 store 使用 **Setup Store** 风格（Composition API）。

---

## 1. Store 总览

| Store | 路径 | 状态 | 轮询 | 用途 |
|-------|------|------|------|------|
| dashboard | `src/stores/dashboard.ts` | `stats` | 30s | Dashboard 聚合数据 |
| agents | `src/stores/agents.ts` | `list` + `byName(name)` | 60s | Agent 列表 + 详情 |
| sessions | `src/stores/sessions.ts` | `list` + `byId(id)` | 5s | Session 列表 + 详情 |
| tools | `src/stores/tools.ts` | `list` | 60s | Tool 列表 |
| providers | `src/stores/providers.ts` | `list` | 5min | Provider 列表 |
| schedules | `src/stores/schedules.ts` | `list` | 60s | Schedule 列表（M0 仅读） |

---

## 2. 通用约定

### 2.1 状态结构

```typescript
// 通用加载状态机
type AsyncStatus = 'idle' | 'loading' | 'success' | 'error'

interface AsyncState<T> {
  data: T | null
  status: AsyncStatus
  error: ApiError | null
  last_fetched_at: number | null    // 用于缓存判断
}
```

### 2.2 轮询 Composable

```typescript
// src/lib/polling.ts
import { ref, onUnmounted, watch } from 'vue'

export interface UsePollingOptions {
  interval: number                              // ms
  immediate?: boolean                           // 默认 true（立即执行一次）
  enabled?: Ref<boolean>                        // 可动态开关
  pause_on_hidden?: boolean                     // 默认 true（per FR-016）
}

export function usePolling(
  fn: () => Promise<void>,
  options: UsePollingOptions
): { is_running: Ref<boolean>; trigger: () => void; stop: () => void } {
  // 实现：
  // 1. 启动 setInterval
  // 2. visibilitychange 监听 → hidden 时暂停，visible 时立即 trigger
  // 3. onUnmounted 时清理
}
```

### 2.3 错误处理

每个 store 的 fetch action 在失败时：

1. 设置 `state.error`
2. `status='error'`
3. **不**抛异常（让 UI 用 `ErrorState` 组件渲染）
4. 记录 `console.error` 用于调试

### 2.4 自动生成 Client 的导入

```typescript
// src/api/generated/index.ts 入口
export * from './services/AgentsService'
export * from './services/SessionsService'
export * from './services/ProfilesService'
export * from './services/ToolsService'
export * from './services/StatsService'
export * from './services/ProvidersService'
export * from './services/HealthService'

// 简化导入
import { AgentsService, StatsService } from '@/api/generated'
```

---

## 3. dashboard store

### 3.1 状态

```typescript
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { StatsService } from '@/api/generated'
import type { DashboardStats } from '@/api/generated'
import type { DashboardTiles } from '@/lib/view-models/dashboard'

export const useDashboardStore = defineStore('dashboard', () => {
  // === state ===
  const stats = ref<DashboardStats | null>(null)
  const status = ref<AsyncStatus>('idle')
  const error = ref<ApiError | null>(null)
  const last_fetched_at = ref<number | null>(null)

  // === getters ===
  const tiles = computed<DashboardTiles[]>(() => {
    if (!stats.value) return []
    const s = stats.value.summary

    return [
      {
        label: '24h LLM 调用',
        value: s.llm_calls_24h,
        delta_pct: calcDeltaPct(s.llm_calls_24h, s.llm_calls_yesterday),
        trend: s.llm_calls_24h > s.llm_calls_yesterday ? 'up' : s.llm_calls_24h < s.llm_calls_yesterday ? 'down' : 'flat',
      },
      {
        label: '24h Tool 调用',
        value: s.tool_calls_24h,
        delta_pct: calcDeltaPct(s.tool_calls_24h, s.tool_calls_yesterday),
        trend: s.tool_calls_24h > s.tool_calls_yesterday ? 'up' : 'down',
      },
      {
        label: '活跃 Session',
        value: s.active_sessions,
        delta_pct: calcDeltaPct(s.active_sessions, s.active_sessions_yesterday),
      },
      {
        label: '24h 异常 Session',
        value: s.failed_sessions_24h,
        delta_pct: calcDeltaPct(s.failed_sessions_24h, /* 上周同期 */ 0),
        severity: s.failed_sessions_rate > 0.1 ? 'danger' : s.failed_sessions_rate > 0.05 ? 'warning' : 'ok',
      },
    ]
  })

  const token_trend = computed(() => stats.value?.token_trend_24h ?? [])
  const top_failed = computed(() => stats.value?.top_failed_sessions ?? [])
  const recent_events = computed(() => stats.value?.recent_events ?? [])

  // === actions ===
  async function fetchStats() {
    status.value = 'loading'
    error.value = null
    try {
      stats.value = await StatsService.getDashboardStats()
      status.value = 'success'
      last_fetched_at.value = Date.now()
    } catch (e: any) {
      error.value = extractApiError(e)
      status.value = 'error'
    }
  }

  function reset() {
    stats.value = null
    status.value = 'idle'
    error.value = null
  }

  return {
    // state
    stats, status, error, last_fetched_at,
    // getters
    tiles, token_trend, top_failed, recent_events,
    // actions
    fetchStats, reset,
  }
})
```

### 3.2 在页面中使用

```vue
<script setup lang="ts">
import { useDashboardStore } from '@/stores/dashboard'
import { usePolling } from '@/lib/polling'

const store = useDashboardStore()

usePolling(() => store.fetchStats(), {
  interval: 30_000,
  immediate: true,
})
</script>
```

---

## 4. agents store

### 4.1 状态

```typescript
export const useAgentsStore = defineStore('agents', () => {
  // === state ===
  const list = ref<AsyncState<Agent[]>>({ data: null, status: 'idle', error: null, last_fetched_at: null })
  const stats_by_name = ref<Map<string, AsyncState<AgentStats>>>(new Map())

  // === getters ===
  const agents = computed(() => list.value.data ?? [])

  function getStats(name: string): AsyncState<AgentStats> | undefined {
    return stats_by_name.value.get(name)
  }

  // === actions ===
  async function fetchList() {
    list.value = { ...list.value, status: 'loading', error: null }
    try {
      const profiles = await ProfilesService.getProfiles()
      list.value = {
        data: profiles.profiles,
        status: 'success',
        error: null,
        last_fetched_at: Date.now(),
      }
    } catch (e) {
      list.value = { ...list.value, status: 'error', error: extractApiError(e) }
    }
  }

  async function fetchStats(name: string) {
    stats_by_name.value.set(name, {
      data: stats_by_name.value.get(name)?.data ?? null,
      status: 'loading',
      error: null,
      last_fetched_at: stats_by_name.value.get(name)?.last_fetched_at ?? null,
    })
    try {
      const stats = await StatsService.getAgentStats({ name })
      stats_by_name.value.set(name, {
        data: stats,
        status: 'success',
        error: null,
        last_fetched_at: Date.now(),
      })
    } catch (e) {
      const current = stats_by_name.value.get(name)
      stats_by_name.value.set(name, {
        ...current!,
        status: 'error',
        error: extractApiError(e),
      })
    }
  }

  return {
    list, stats_by_name,
    agents, getStats,
    fetchList, fetchStats,
  }
})
```

---

## 5. sessions store

### 5.1 状态

```typescript
interface SessionFilters {
  agent_name?: string
  source?: SessionSource[]
  status?: SessionStatus[]
  from?: string  // ISO 8601
  to?: string
  keyword?: string
}

export const useSessionsStore = defineStore('sessions', () => {
  // === state ===
  const list = ref<AsyncState<Session[]>>({ data: null, status: 'idle', error: null, last_fetched_at: null })
  const by_id = ref<Map<string, AsyncState<SessionDetailResponse>>>(new Map())
  const filters = ref<SessionFilters>({})

  // === getters ===
  const sessions = computed(() => list.value.data ?? [])
  const filtered = computed(() => applyFilters(sessions.value, filters.value))

  function getDetail(id: string): AsyncState<SessionDetailResponse> | undefined {
    return by_id.value.get(id)
  }

  // === actions ===
  async function fetchList() {
    // 实际 GET /api/v1/sessions?...（需 008 补充列表端点）
    // M0.5 阶段：客户端从聚合 + 单点拉取派生
  }

  async function fetchDetail(id: string) {
    by_id.value.set(id, {
      data: by_id.value.get(id)?.data ?? null,
      status: 'loading',
      error: null,
      last_fetched_at: by_id.value.get(id)?.last_fetched_at ?? null,
    })
    try {
      const detail = await SessionsService.getSession({ id })
      by_id.value.set(id, {
        data: detail,
        status: 'success',
        error: null,
        last_fetched_at: Date.now(),
      })
    } catch (e) {
      const current = by_id.value.get(id)
      by_id.value.set(id, {
        ...current!,
        status: 'error',
        error: extractApiError(e),
      })
    }
  }

  async function invokeAgent(name: string, message: string): Promise<string> {
    const response = await AgentsService.invokeAgent({
      name,
      requestBody: {
        message,
        metadata: { source: 'web' },
      },
    })
    return response.session_id
  }

  function setFilters(new_filters: Partial<SessionFilters>) {
    filters.value = { ...filters.value, ...new_filters }
  }

  function resetFilters() {
    filters.value = {}
  }

  return {
    list, by_id, filters,
    sessions, filtered, getDetail,
    fetchList, fetchDetail, invokeAgent, setFilters, resetFilters,
  }
})
```

### 5.2 URL 同步（per FR-010）

```typescript
// 在 Session 列表页
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const store = useSessionsStore()

// URL → filters
onMounted(() => {
  store.setFilters({
    agent_name: route.query.agent_name as string,
    status: route.query.status?.toString().split(',') as SessionStatus[],
    from: route.query.from as string,
    to: route.query.to as string,
  })
})

// filters → URL（防抖）
watch(
  () => store.filters,
  (new_filters) => {
    router.replace({
      query: {
        agent_name: new_filters.agent_name,
        status: new_filters.status?.join(','),
        from: new_filters.from,
        to: new_filters.to,
      },
    })
  },
  { deep: true, debounce: 300 }
)
```

---

## 6. tools store

### 6.1 状态

```typescript
export const useToolsStore = defineStore('tools', () => {
  const list = ref<AsyncState<Tool[]>>({ data: null, status: 'idle', error: null, last_fetched_at: null })

  const tools = computed(() => list.value.data ?? [])

  // 按来源过滤
  const builtin_tools = computed(() => tools.value.filter(t => t.source === 'builtin'))
  const mcp_tools = computed(() => tools.value.filter(t => t.source === 'mcp'))
  const java_bean_tools = computed(() => tools.value.filter(t => t.source === 'java_bean'))

  async function fetchList() {
    list.value = { ...list.value, status: 'loading', error: null }
    try {
      const resp = await ToolsService.getTools()
      list.value = {
        data: resp.tools,
        status: 'success',
        error: null,
        last_fetched_at: Date.now(),
      }
    } catch (e) {
      list.value = { ...list.value, status: 'error', error: extractApiError(e) }
    }
  }

  return { list, tools, builtin_tools, mcp_tools, java_bean_tools, fetchList }
})
```

---

## 7. providers store

### 7.1 状态

```typescript
export const useProvidersStore = defineStore('providers', () => {
  const list = ref<AsyncState<Provider[]>>({ data: null, status: 'idle', error: null, last_fetched_at: null })

  const providers = computed(() => list.value.data ?? [])
  const healthy_providers = computed(() => providers.value.filter(p => p.status === 'healthy'))
  const degraded_providers = computed(() => providers.value.filter(p => p.status === 'degraded' || p.status === 'down'))

  async function fetchList() {
    list.value = { ...list.value, status: 'loading', error: null }
    try {
      const resp = await ProvidersService.getProviders()
      list.value = {
        data: resp.providers,
        status: 'success',
        error: null,
        last_fetched_at: Date.now(),
      }
    } catch (e) {
      list.value = { ...list.value, status: 'error', error: extractApiError(e) }
    }
  }

  return { list, providers, healthy_providers, degraded_providers, fetchList }
})
```

---

## 8. schedules store（M0 仅读）

### 8.1 状态

```typescript
export const useSchedulesStore = defineStore('schedules', () => {
  const list = ref<AsyncState<Schedule[]>>({ data: null, status: 'idle', error: null, last_fetched_at: null })

  const schedules = computed(() => list.value.data ?? [])

  // M0 阶段：CLI 替代，无 REST 端点
  // M1 阶段：替换为 GET /api/v1/schedules
  async function fetchList() {
    list.value = { ...list.value, status: 'loading', error: null }
    try {
      // 占位：暂时返回空数组，UI 显示 EmptyState 引导用户用 CLI
      // M1: const resp = await SchedulesService.getSchedules()
      list.value = {
        data: [],
        status: 'success',
        error: null,
        last_fetched_at: Date.now(),
      }
    } catch (e) {
      list.value = { ...list.value, status: 'error', error: extractApiError(e) }
    }
  }

  return { list, schedules, fetchList }
})
```

**M0 UI 行为**：

- 页面顶部显示 `⚠ M0 阶段仅读，写操作请用 CLI: oryxos schedule list` 横幅
- 列表可显示**手工录入**的 mock 数据（per mockup `08-schedules.html`）
- 实际数据从 `application.yaml` 的 schedules 段读取（如有）

---

## 9. 通用工具函数

### 9.1 `extractApiError`

```typescript
// src/lib/api-error.ts
import type { ApiError } from '@/api/generated'

export function extractApiError(e: any): ApiError {
  if (e?.response?.data?.error) {
    return e.response.data.error
  }
  if (e?.code === 'ECONNABORTED') {
    return { code: 'TIMEOUT', message: '请求超时' }
  }
  if (e?.message === 'Network Error') {
    return { code: 'NETWORK', message: '网络异常，请检查后端服务' }
  }
  return {
    code: 'UNKNOWN',
    message: e?.message ?? '未知错误',
  }
}
```

### 9.2 `applyFilters`

```typescript
// src/lib/session-filter.ts
export function applyFilters(sessions: Session[], filters: SessionFilters): Session[] {
  return sessions.filter((s) => {
    if (filters.agent_name && s.profile_name !== filters.agent_name) return false
    if (filters.source?.length && !filters.source.includes(s.metadata.source)) return false
    if (filters.status?.length && !filters.status.includes(s.status)) return false
    if (filters.from && new Date(s.started_at) < new Date(filters.from)) return false
    if (filters.to && new Date(s.started_at) > new Date(filters.to)) return false
    if (filters.keyword && !JSON.stringify(s).toLowerCase().includes(filters.keyword.toLowerCase())) return false
    return true
  })
}
```

### 9.3 `calcDeltaPct`

```typescript
export function calcDeltaPct(current: number, yesterday: number): number {
  if (yesterday === 0) return current === 0 ? 0 : 100
  return ((current - yesterday) / yesterday) * 100
}
```

---

## 10. 全局初始化

```typescript
// src/main.ts
import { createApp } from 'vue'
import { createPinia } from 'pinia'

const app = createApp(App)
app.use(createPinia())
```

```typescript
// src/App.vue
<script setup lang="ts">
import { onMounted } from 'vue'
import { useHealthStore } from '@/stores/health'  // 注意：health 不在 6 个 store 中但全局常驻

const health = useHealthStore()
usePolling(() => health.fetch(), { interval: 30_000 })
</script>
```

---

## 11. 与后端 API 的对应关系

| Store | 调用后端 Service | 数据流向 |
|-------|----------------|---------|
| dashboard | `StatsService.getDashboardStats()` | 后端 → store → Dashboard.vue |
| agents | `ProfilesService.getProfiles()` + `StatsService.getAgentStats({ name })` | 后端 → store → Agents/List.vue + Agents/Detail.vue |
| sessions | `SessionsService.getSession({ id })` + `AgentsService.invokeAgent()` | 后端 ↔ store ↔ Sessions/List.vue + Sessions/Detail.vue |
| tools | `ToolsService.getTools()` | 后端 → store → Tools.vue |
| providers | `ProvidersService.getProviders()` | 后端 → store → Providers.vue |
| schedules | （M0 阶段无） | 占位 → store → Schedules.vue |

---

## 12. 测试策略

### 12.1 单元测试（Vitest）

每个 store 至少测试：

| Store | 测试用例 |
|-------|---------|
| dashboard | loading → success 转换 / error 处理 / tiles 计算正确性 |
| agents | fetchList 写入 list / fetchStats 写入 stats_by_name / 按 name 取 stats |
| sessions | filter 应用正确性 / URL 同步 / invokeAgent 返回 session_id |
| tools | 按 source 过滤 getter 正确性 |
| providers | healthy / degraded 分类 |
| schedules | M0 占位返回空数组 |

### 12.2 Mock 后端 API

```typescript
// tests/unit/stores/__mocks__/generated.ts
export const StatsService = {
  getDashboardStats: vi.fn(),
  getAgentStats: vi.fn(),
}
// ...

// 在测试中
vi.mock('@/api/generated', () => ({
  StatsService: {
    getDashboardStats: vi.fn().mockResolvedValue(MOCK_DASHBOARD_STATS),
  },
}))
```

---

## 13. 待办与后续

| 项 | 阶段 | 备注 |
|----|------|------|
| `usePolling` 实现 | M0 实现 | 当前为契约，代码待 `/speckit-implement` 写 |
| `applyFilters` 移到后端 | M1 | M0 阶段前端过滤性能足够（< 1000 行） |
| Pinia 持久化 | M1 | 当前刷新页面丢失 filters（M0 不需要） |
| 乐观更新 | M1 | 当前仅读 |
| WebSocket 替代轮询 | M1 | 当前 5s 轮询 |