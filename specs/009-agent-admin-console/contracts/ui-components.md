# UI Components Contract: 009-agent-admin-console

**Phase**: 1 — Design & Contracts
**Date**: 2026-07-29
**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Data Model**: [data-model.md](../data-model.md) | **API**: [api-endpoints.md](api-endpoints.md)

> 本契约锁定 **10 通用 + 3 业务 + 3 图表 + 3 布局** = **19 个 Vue 组件**的契约。
> 组件**统一**通过 Naive UI 包裹 + TypeScript 强类型 props/emits。

---

## 1. 组件目录

```text
src/components/
├── layout/                # 布局组件（3 个）
│   ├── Sidebar.vue
│   ├── PageHeader.vue
│   └── HealthIndicator.vue
├── common/                # 通用展示组件（10 个）
│   ├── StatTile.vue
│   ├── EventStream.vue
│   ├── StatusBadge.vue
│   ├── SourceBadge.vue
│   ├── RelativeTime.vue
│   ├── TokenCount.vue
│   ├── EmptyState.vue
│   ├── ErrorState.vue
│   ├── FilterBar.vue
│   └── CopyButton.vue
├── business/              # 业务组件（3 个）
│   ├── TimelineCard.vue       # Session 详情核心
│   ├── ToolSchemaDrawer.vue
│   └── AgentInvocationForm.vue
└── charts/                # 图表组件（3 个）
    ├── Sparkline.vue
    ├── TimeSeriesChart.vue
    └── PieDistribution.vue
```

---

## 2. 布局组件（3）

### 2.1 Sidebar

**用途**：左侧导航（per mockup `index.html` 侧边栏）。

```typescript
// Props
interface SidebarProps {
  collapsed?: boolean   // 默认 false
}

// Emits
interface SidebarEmits {
  (e: 'navigate', path: string): void
}

// 内部
interface NavItem {
  path: string
  label: string
  icon: string          // Lucide 图标名
  badge?: number        // 可选计数（如待处理失败 Session）
}

// 路由表（硬编码）
const NAV_ITEMS: NavItem[] = [
  { path: '/dashboard',       label: 'Dashboard',       icon: 'LayoutDashboard' },
  { path: '/agents',          label: 'Agents',          icon: 'Bot' },
  { path: '/sessions',        label: 'Sessions',        icon: 'MessageSquare' },
  { path: '/tools',           label: 'Tools',           icon: 'Wrench' },
  { path: '/providers',       label: 'Providers',       icon: 'Zap' },
  { path: '/schedules',       label: 'Schedules',       icon: 'Clock' },
]
```

**验收**：键盘 Tab 顺序合理（per FR-017）。

---

### 2.2 PageHeader

**用途**：页面标题 + 副标题 + 右侧操作区。

```typescript
interface PageHeaderProps {
  title: string
  subtitle?: string
  // 右侧操作区 slot
}
```

**模板**：

```vue
<PageHeader title="📊 Dashboard" subtitle="实时数据 · 自动刷新 30s">
  <template #actions>
    <NButton type="primary">🔄 刷新</NButton>
  </template>
</PageHeader>
```

---

### 2.3 HealthIndicator

**用途**：**FR-015**——顶部导航常驻健康徽章。

```typescript
interface HealthIndicatorProps {
  status: 'UP' | 'DOWN' | 'DEGRADED' | null
  version?: string
}
```

**行为**：

- `UP`：绿色 + 静态 dot
- `DEGRADED`：黄色 + 静态 dot
- `DOWN`：红色 + 1 Hz 闪烁（per FR-015）
- `null`：灰色 + "连接中…"

**aria-label**：

- `UP` → `aria-label="服务正常"`
- `DOWN` → `aria-label="服务异常"`

---

## 3. 通用组件（10）

### 3.1 StatTile

**用途**：Dashboard 4 个统计卡片（per mockup `01-dashboard.html`）。

```typescript
interface StatTileProps {
  label: string                                // 标签（如 "24h LLM 调用"）
  value: number | string                       // 主值（如 1234 / "1.2K"）
  delta_pct?: number                           // 同比百分比（如 12.4 表示 +12.4%）
  trend?: 'up' | 'down' | 'flat'               // 趋势方向
  severity?: 'ok' | 'warning' | 'danger'       // 严重程度（决定颜色）
  icon?: string                                // Lucide 图标名
  loading?: boolean
}
```

**渲染规则**：

- `severity="danger"` + `delta_pct > 0` → 红色 + ▲
- `severity="warning"` → 黄色
- `severity="ok"` → 默认紫青双色
- `loading=true` → `<NSkeleton>` 占位

---

### 3.2 EventStream

**用途**：Dashboard 最近事件列表（per mockup 01-dashboard "Recent Events"）。

```typescript
interface EventStreamItem {
  timestamp: string                            // ISO 8601
  agent_name: string
  source: 'chat' | 'api' | 'scheduler' | 'web'
  session_id: string
  duration_ms: number
  status: 'success' | 'failed' | 'running'
}

interface EventStreamProps {
  items: EventStreamItem[]
  max_items?: number                           // 默认 5
  loading?: boolean
}
```

**事件**：

```typescript
interface EventStreamEmits {
  (e: 'click', session_id: string): void       // 点击跳转 Session 详情
}
```

---

### 3.3 StatusBadge

**用途**：状态徽章（成功 / 失败 / 进行中 / 取消）。

```typescript
type Status =
  | 'success'
  | 'failed'
  | 'running'
  | 'pending'
  | 'cancelled'
  | 'healthy'
  | 'degraded'
  | 'down'

interface StatusBadgeProps {
  status: Status
  label?: string                               // 自定义文本；默认按 status 映射
  pulse?: boolean                              // 1Hz 闪烁（用于 'running' / 'down'）
}
```

**颜色映射**：

| status | 颜色 | 文本 |
|--------|------|------|
| `success` / `healthy` | 绿色 | `● 成功` / `● 健康` |
| `failed` / `down` | 红色 | `● 失败` / `● 离线` |
| `running` | 蓝色 + 闪烁 | `● 进行中` |
| `pending` | 灰色 | `○ 等待中` |
| `cancelled` | 灰色 | `◐ 已取消` |
| `degraded` | 黄色 | `● 部分异常` |

**aria-label**（per FR-017）：`aria-label="${label}（状态：${status}）"`。

---

### 3.4 SourceBadge

**用途**：触发源徽章（per mockup 多处使用）。

```typescript
type Source = 'chat' | 'api' | 'scheduler' | 'web' | 'builtin' | 'mcp' | 'java_bean'

interface SourceBadgeProps {
  source: Source
}
```

**颜色映射**：

| source | 颜色 | 文本 |
|--------|------|------|
| `chat` | 紫色 | `💬 chat` |
| `api` | 青色 | `🔌 api` |
| `scheduler` | 橙色 | `⏰ scheduler` |
| `web` | 粉色 | `🌐 web` |
| `builtin` | 灰色 | `builtin` |
| `mcp` | 蓝色 | `mcp` |
| `java_bean` | 绿色 | `java_bean` |

---

### 3.5 RelativeTime

**用途**：相对时间显示（如 "3 分钟前"）。

```typescript
interface RelativeTimeProps {
  iso: string                                  // ISO 8601 UTC
  refresh_interval?: number                     // 自动刷新间隔（ms），默认 30_000
  absolute?: boolean                           // true 时显示绝对时间（hover tooltip）
}
```

**实现**：

```typescript
// src/lib/format.ts
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/zh-cn'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

export function formatRelative(iso: string): string {
  return dayjs(iso).fromNow()
  // "3 分钟前" / "2 小时前" / "昨天" / "3 天前"
}
```

---

### 3.6 TokenCount

**用途**：Token 计数（自动 K/M 格式化 + 估算美元费用）。

```typescript
interface TokenCountProps {
  tokens: number
  cost_usd?: number                            // 可选估算费用
  show_cost?: boolean                          // 默认 true
}

interface TokenCountEmits {
  // 无
}
```

**格式化**：

```typescript
export function formatTokenCount(tokens: number): string {
  if (tokens >= 1_000_000) return `${(tokens / 1_000_000).toFixed(1)}M`
  if (tokens >= 1_000) return `${(tokens / 1_000).toFixed(1)}K`
  return tokens.toString()
}
```

---

### 3.7 EmptyState

**用途**：空态展示（数据为空时）。

```typescript
interface EmptyStateProps {
  icon?: string                                // Lucide 图标名，默认 'Inbox'
  title: string                                // 主标题
  hint?: string                                 // 提示文案
  action?: { label: string; onClick: () => void }  // 可选 CTA 按钮
}
```

**示例**（per FR 边界情况 - 数据稀缺）：

```vue
<EmptyState
  icon="Database"
  title="暂无数据"
  hint="运行 Demo 数据生成器可快速填充示例 Session"
  :action="{ label: '生成示例数据', onClick: generateDemoData }"
/>
```

---

### 3.8 ErrorState

**用途**：错误态展示（HTTP 错误时）。

```typescript
interface ErrorStateProps {
  error: ApiError | Error | { code?: string; message: string }
  retry?: () => void
}
```

**渲染规则**：

- 提取 `error.code` + `error.message`
- 显示友好文案（中文）
- 提供 "重试" 按钮（如有 `retry` 回调）

---

### 3.9 FilterBar

**用途**：列表过滤栏（per FR-010 / mockup `04-sessions-list.html`）。

```typescript
interface FilterField {
  key: string
  label: string
  type: 'text' | 'select' | 'daterange' | 'multi-select'
  options?: Array<{ label: string; value: string }>
  placeholder?: string
}

interface FilterBarProps {
  fields: FilterField[]
  modelValue: Record<string, any>               // 当前过滤值（双向绑定）
}

interface FilterBarEmits {
  (e: 'update:modelValue', value: Record<string, any>): void
  (e: 'reset'): void
}
```

**行为**：

- 输入变化后 **300ms debounce** → 同步到 `modelValue`
- 父组件监听 `update:modelValue` → 同步到 URL query string（per FR-010）

---

### 3.10 CopyButton

**用途**：一键复制（per US-3 验收 3「复制调用 ID」）。

```typescript
interface CopyButtonProps {
  text: string
  label?: string                               // 按钮文本，默认 '复制'
  size?: 'tiny' | 'small' | 'medium'
}
```

**实现**：使用 `navigator.clipboard.writeText()`，失败时回退 `document.execCommand('copy')`。

---

## 4. 业务组件（3）

### 4.1 TimelineCard **【核心】**

**用途**：Session 详情 5 类 Step 卡片（per FR-008）。

```typescript
interface TimelineCardProps {
  step: Step                                    // 来自 data-model.md §6.2
  default_collapsed?: boolean                   // 默认折叠，仅显示 summary
}

interface TimelineCardEmits {
  (e: 'expand', step_id: string): void         // 展开事件（埋点用）
}
```

**5 类渲染分支**：

```typescript
type StepType = 'user_input' | 'llm_call' | 'tool_call' | 'notify' | 'final_answer'

const RENDERERS: Record<StepType, (step: Step) => VNode> = {
  user_input: renderUserInput,    // 显示 content_preview + 「查看完整」按钮
  llm_call: renderLlmCall,        // 显示 provider/model + tokens + tool_calls_requested
  tool_call: renderToolCall,      // 显示 args + sandbox 决策（红色 if blocked）
  notify: renderNotify,           // 显示 channel + status_code + content_preview
  final_answer: renderFinalAnswer, // 显示 content_preview + is_error_fallback
}
```

**失败态**（per FR-009）：

```typescript
if (!step.success) {
  // 整张卡片左侧加 4px 红色边框
  // 显示 error_message（不含 stack trace）
  // 顶部加 ⚠ 图标
}
```

**a11y**（per FR-018）：

```html
<article
  :aria-label="`Step ${step.sequence} of type ${step.type}`"
  :aria-expanded="!is_collapsed"
>
  <header>
    <span :class="`step-type-${step.type}`">{{ stepLabel(step.type) }}</span>
    <RelativeTime :iso="step.timestamp" />
  </header>
  <main>...</main>
</article>
```

---

### 4.2 ToolSchemaDrawer

**用途**：Tool 列表点击展开 Schema 抽屉（per mockup `06-tools.html` 「Tool Schema 示例」）。

```typescript
interface ToolSchemaDrawerProps {
  tool: Tool
  show: boolean
}

interface ToolSchemaDrawerEmits {
  (e: 'update:show', show: boolean): void
}
```

**抽屉内容**：

- Tool 名 + 来源徽章
- 描述
- JSON Schema（语法高亮，可用 `shiki` 或 `prismjs`）
- Sandbox 配置（action + allowed_domains）

---

### 4.3 AgentInvocationForm

**用途**：**US-4**——手动触发测试表单。

```typescript
interface AgentInvocationFormProps {
  agent_name: string
  show: boolean
}

interface AgentInvocationFormEmits {
  (e: 'update:show', show: boolean): void
  (e: 'submit', session_id: string): void       // 成功后发射 session_id 用于跳转
}
```

**表单字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | textarea | ✅ | 测试消息 |
| `profile_override` | switch + select | — | M1 阶段（M0 仅占位 disabled） |

**提交行为**：

1. 禁用按钮 + 显示 `<NSpin>`
2. `POST /api/v1/agents/{name}/invoke`
3. 成功 → `emit('submit', response.session_id)` → 父组件跳转
4. 失败 → 显示错误 toast，保留表单内容，**不**关闭抽屉

**超时**：> 30s 显示 `504 TIMEOUT` 错误（per spec US-4 验收 3）。

---

## 5. 图表组件（3）

### 5.1 Sparkline

**用途**：Dashboard / Provider 卡片 24h 调用时序图（per mockup `01-dashboard.html`）。

```typescript
interface SparklineProps {
  data: number[]                               // 24 entries
  height?: number                               // 默认 80px
  color?: string                                // 默认 var(--color-accent)
  show_axes?: boolean                           // 默认 false
}
```

**实现**：

- 使用 ECharts（per Phase 0 R-001 锁定的依赖）
- 类型：`line`，无坐标轴，无网格线，无 tooltip
- 仅显示轮廓（per mockup 风格）

---

### 5.2 TimeSeriesChart

**用途**：Dashboard 24h Token 消耗趋势图（per mockup `01-dashboard.html`）。

```typescript
interface TimeSeriesChartProps {
  data: Array<{ hour: string; tokens: number }>  // 24 entries
  height?: number                                 // 默认 240px
  show_legend?: boolean
  show_tooltip?: boolean                          // 默认 true（per spec US-2 验收 1）
}

interface TimeSeriesChartEmits {
  (e: 'hover', point: { hour: string; tokens: number }): void
}
```

**特性**：

- ECharts line chart + area gradient
- Hover 显示「该小时 token 数 + 估算美元费用」（per US-2 验收 1）
- X 轴：每 6 小时一个 tick（00:00 / 06:00 / 12:00 / 18:00 / 24:00）
- Y 轴：自动 scale，K/M 格式化

---

### 5.3 PieDistribution

**用途**：Agent 详情「成本」Tab 按 Provider 分解饼图（per US-2 验收 3）。

```typescript
interface PieDistributionProps {
  data: Array<{ name: string; value: number }>
  height?: number                                 // 默认 240px
  show_legend?: boolean                           // 默认 true
}
```

**实现**：ECharts pie chart + 调色板（与 §V.9.7 调色板一致）。

---

## 6. 组件样式规范

### 6.1 调色板（per FR-004 / FR-020）

| CSS 变量 | 用途 |
|---------|------|
| `--color-bg` | 页面背景 `#0f1115` |
| `--color-surface` | 卡片背景 `#161922` |
| `--color-surface-elevated` | 弹层背景 `#1d2230` |
| `--color-accent` | 主色 `#7c5cff` |
| `--color-secondary` | 次色 `#00d4ff` |
| `--color-text-primary` | 主文本 `#e6e9ef` |
| `--color-text-secondary` | 次文本 `#9ca3af` |
| `--color-text-muted` | 弱文本 `#6b7280` |
| `--color-success` | 成功 `#34d399` |
| `--color-warning` | 警告 `#fbbf24` |
| `--color-danger` | 错误 `#f87171` |
| `--color-border` | 边框 `#2a2f3a` |

### 6.2 字体

- 主字体：Inter（sans-serif）
- 代码：JetBrains Mono
- 大小：base 14px，title 18px / 22px / 28px

### 6.3 间距

- 卡片 padding：24px
- 卡片间距：16px
- 组件内部间距：12px / 16px / 24px 三档

---

## 7. 组件使用示例

### 7.1 Dashboard 首屏

```vue
<template>
  <PageHeader title="📊 Dashboard" subtitle="实时数据 · 自动刷新 30s">
    <template #actions>
      <NButton @click="refresh">🔄 刷新</NButton>
    </template>
  </PageHeader>

  <div class="stat-grid">
    <StatTile
      v-for="tile in tiles"
      :key="tile.label"
      v-bind="tile"
    />
  </div>

  <NCard title="24h Token 消耗趋势">
    <TimeSeriesChart :data="store.tokenTrend" />
  </NCard>

  <NCard title="Top 5 异常 Session">
    <EventStream :items="store.topFailed" @click="goSession" />
  </NCard>
</template>
```

### 7.2 Session 详情

```vue
<template>
  <PageHeader :title="`Session ${session.id}`" :subtitle="subtitle">
    <template #actions>
      <CopyButton :text="session.id" label="复制 Session ID" />
    </template>
  </PageHeader>

  <TimelineCard
    v-for="step in session.steps"
    :key="step.id"
    :step="step"
    :default_collapsed="step.sequence !== latestSequence"
    @expand="trackExpand"
  />
</template>
```

---

## 8. a11y 检查清单

每个组件 MUST 通过：

| 检查 | 工具 | 必填 |
|------|------|------|
| 键盘可达 | Tab 键手动测 | ✅ |
| `aria-label` | axe-core 自动测 | ✅ |
| 颜色对比度 ≥ 4.5:1 | axe-core 自动测 | ✅ |
| 屏幕阅读器 | NVDA / VoiceOver 手动测 | US-5 验收 3 个核心页面 |
| 焦点指示 | 视觉确认 | ✅ |

---

## 9. 单元测试覆盖

| 组件 | 测试文件 | 测试用例数（最低） |
|------|---------|-------------------|
| StatTile | `tests/unit/components/StatTile.spec.ts` | 6（loading / 4 severity / delta） |
| StatusBadge | `StatusBadge.spec.ts` | 6（5 状态 + aria-label） |
| SourceBadge | `SourceBadge.spec.ts` | 7（7 source） |
| TimelineCard | `TimelineCard.spec.ts` | 10（5 类型 + 失败态 + 折叠） |
| FilterBar | `FilterBar.spec.ts` | 5（debounce / reset / URL 同步） |
| RelativeTime | `RelativeTime.spec.ts` | 3（基本 / refresh / absolute） |
| 其余 | 一一对应 | 各 ≥ 3 |

**覆盖率目标**：核心组件 ≥ 70%（per NFR-003）。

---

## 10. E2E 测试覆盖（per NFR-003）

| 场景 | Playwright 文件 | 测试步骤 |
|------|----------------|---------|
| Demo 一（每日天气） | `tests/e2e/demo-weather.spec.ts` | 进入 Dashboard → 看 LLM 调用数 → 进 Providers → 看 deepseek 调用次数 |
| Demo 二（每日科技日报） | `tests/e2e/demo-news.spec.ts` | 进 Sessions 列表 → 过滤 `agent-news` → 看跨 Session 记忆 |
| Demo 三（每日 GitHub 日报） | `tests/e2e/demo-github.spec.ts` | 进 Agent 详情 → 看 `agent-github` 24h token + 错误率 |

---

## 11. 组件依赖矩阵

| 组件 | Naive UI 依赖 | 第三方依赖 |
|------|--------------|-----------|
| Sidebar | `NLayout` `NMenu` | vue-router |
| PageHeader | `NPageHeader` | — |
| HealthIndicator | `NBadge` `NSpin` | — |
| StatTile | `NSkeleton` | — |
| EventStream | `NList` `NThing` | — |
| StatusBadge | `NTag` | — |
| SourceBadge | `NTag` | — |
| RelativeTime | — | dayjs |
| TokenCount | `NStatistic` | dayjs |
| EmptyState | `NEmpty` | — |
| ErrorState | `NAlert` | — |
| FilterBar | `NInput` `NSelect` `NDatePicker` | — |
| CopyButton | `NButton` | — |
| **TimelineCard** | `NCard` `NCollapse` `NDrawer` | shiki (syntax highlight) |
| ToolSchemaDrawer | `NDrawer` | shiki |
| AgentInvocationForm | `NForm` `NInput` `NButton` `NSpin` | — |
| Sparkline | — | echarts |
| TimeSeriesChart | — | echarts |
| PieDistribution | — | echarts |

---

## 12. 待办与后续

| 项 | 阶段 | 备注 |
|----|------|------|
| Theme toggle（深色 / 明亮） | per US-5 验收 3 | 实现 theme store + Naive UI `darkTheme` 切换 |
| Drag-and-drop 排序 | M1 | 当前按时间倒序硬编码 |
| 全屏模式 | M1 | F11 快捷键 |
| 多语言 i18n | M2 | 当前仅中文（per A-008） |