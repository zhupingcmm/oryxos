# Tasks: 009-agent-admin-console

**Input**: Design documents from `/specs/009-agent-admin-console/`
**Branch**: `009-agent-admin-console` | **Date**: 2026-07-29
**Prerequisites**: plan.md ✅ | spec.md ✅ | research.md ✅ | data-model.md ✅ | contracts/ ✅ | quickstart.md ✅

**Tests**: Vitest (单元) + Playwright (E2E) — **纳入任务**（per NFR-003）

---

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4, US5)
- **路径约定**：实现落在**新建独立仓库 `oryxos-admin/`**（per Phase 0 R-004），不在当前 Java 仓库内
- 文件路径以 `oryxos-admin/` 为根

---

## Phase 1: Setup（共享基础设施）

**目的**：项目初始化（独立仓库 + 工程脚手架 + 基础配置）

**⚠️ 前置依赖**：`specs/008-agent-web-service/spec.md` US-4 三个聚合端点必须**先合入 main**。任务 T001-T005 是环境准备，可与 008 聚合端点实现并行；任务 T006-T010 依赖 008 合入。

- [ ] T001 在 `d:\code\java\oryxos\..\oryxos-admin\` 新建独立 git 仓库（`git init` + `.gitignore`）
- [ ] T002 用 create-vue 脚手架生成 TypeScript + Router + Pinia + Vitest 项目：`oryxos-admin/package.json` / `oryxos-admin/vite.config.ts` / `oryxos-admin/tsconfig.json` / `oryxos-admin/index.html`
- [ ] T003 [P] 安装运行时依赖：`naive-ui@^2.40` / `@vicons/ionicons5@^0.13` / `axios@^1.7` / `echarts@^5.5` / `vxe-table@^4.13` / `vue-i18n@^9` / `dayjs@^1.11` / `lucide-vue-next@^0.453`（写入 `oryxos-admin/package.json`）
- [ ] T004 [P] 安装开发依赖：`openapi-typescript-codegen@^0.29` / `@playwright/test@^1.48` / `vite-plugin-svg-icons@^2.0` / `unplugin-auto-import@^0.18` / `unplugin-vue-components@^0.27` / `eslint` / `prettier`（写入 `oryxos-admin/package.json`）
- [ ] T005 [P] 配置 ESLint + Prettier + TypeScript strict mode（`oryxos-admin/.eslintrc.cjs` / `oryxos-admin/.prettierrc` / `oryxos-admin/tsconfig.json` strict: true）
- [ ] T006 在 `oryxos-admin/package.json` 添加 npm scripts：`dev` / `build` / `preview` / `test:unit` / `test:e2e` / `gen:api` / `type-check` / `lint`
- [ ] T007 [P] 创建环境变量模板 `oryxos-admin/.env.example`（`VITE_ORYXOS_BACKEND_URL=http://localhost:8080`）+ `.env.development` + `.env.production`
- [ ] T008 [P] 创建调色板 tokens `oryxos-admin/src/styles/tokens.css`（per contracts/ui-components.md §6.1：背景 `#0f1115` / 表面 `#161922` / 主色 `#7c5cff` / 次色 `#00d4ff` / 文本 `#e6e9ef` / 错误 `#f87171` 等 13 个 CSS 变量）
- [ ] T009 [P] 创建全局样式 `oryxos-admin/src/styles/global.css`（重置 + 字体 Inter + JetBrains Mono + 滚动条样式 + focus 可见）
- [ ] T010 [P] 编写 README：`oryxos-admin/README.md`（含快速启动 + 部署 + 截图占位）
- [ ] T011 [P] 配置 `oryxos-admin/.gitignore`（`node_modules/` / `dist/` / `.env.local` / `coverage/` / `.playwright/`）
- [ ] T012 [P] 配置 `oryxos-admin/.dockerignore` + `oryxos-admin/Dockerfile`（多阶段：node:20-alpine build → nginx:1.27-alpine runtime）
- [ ] T013 [P] 创建 `oryxos-admin/docker-compose.yml`（admin + oryxos-backend，含 BACKEND_URL 环境变量 + depends_on）

**Checkpoint**: Setup 完成 — 项目结构就绪，可启动 npm install + npm run dev。

---

## Phase 2: Foundational（阻塞前置）

**目的**：核心基础设施 — **所有** user story 都依赖的组件与可复用代码

**⚠️ CRITICAL**: 所有 user story（US-1 ~ US-5）必须**先**完成本阶段

- [ ] T014 创建 Vue Router 配置 `oryxos-admin/src/router/index.ts`（6 路由：dashboard / agents / agents/:name / sessions / sessions/:id / tools / providers / schedules；每路由配 meta.title）
- [ ] T015 创建 Axios 实例 + 拦截器 `oryxos-admin/src/api/http.ts`（per contracts/api-endpoints.md §6.1：baseURL + timeout 30s + 错误 toast 拦截 + 401 重定向预留）
- [ ] T016 [P] 创建 API 客户端生成脚本 `oryxos-admin/scripts/gen-api.sh` + npm `gen:api`（per Phase 0 R-002：openapi-typescript-codegen --input http://localhost:8080/v3/api-docs --output ./src/api/generated --client axios --useUnionTypes）
- [ ] T017 [P] 生成 API 客户端到 `oryxos-admin/src/api/generated/`（依赖 T006 `gen:api` script；含 models/ + services/；**先**确认 008 聚合端点已合入 main）
- [ ] T018 [P] 创建共享类型 `oryxos-admin/src/api/types.ts`（导出 AsyncState< T> / AsyncStatus / ApiError 包装）
- [ ] T019 [P] 创建 API 错误提取工具 `oryxos-admin/src/lib/api-error.ts`（per contracts/store-api.md §9.1：extractApiError 处理 404/503/504/timeout/network）
- [ ] T020 [P] 创建格式化工具 `oryxos-admin/src/lib/format.ts`（formatRelative / formatTokenCount / formatCost / formatDurationMs + dayjs locale zh-cn）
- [ ] T021 [P] 创建 Session 过滤工具 `oryxos-admin/src/lib/session-filter.ts`（per contracts/store-api.md §9.2：applyFilters）
- [ ] T022 创建 usePolling composable `oryxos-admin/src/lib/polling.ts`（per contracts/store-api.md §2.2：setInterval + visibilitychange 暂停 + 立即刷新 + onUnmounted 清理）
- [ ] T023 [P] 创建主题常量 `oryxos-admin/src/lib/theme.ts`（Naive UI darkTheme overrides + 调色板映射）
- [ ] T024 创建 main.ts + App.vue（`oryxos-admin/src/main.ts`：createApp + Pinia + Router + naive-ui；`oryxos-admin/src/App.vue`：路由出口 + 全局布局）
- [ ] T025 [P] 创建布局组件 `oryxos-admin/src/components/layout/Sidebar.vue`（per contracts/ui-components.md §2.1：6 项导航 + collapsed prop + Lucide 图标）
- [ ] T026 [P] 创建布局组件 `oryxos-admin/src/components/layout/PageHeader.vue`（per §2.2：title + subtitle + actions slot）
- [ ] T027 [P] 创建布局组件 `oryxos-admin/src/components/layout/HealthIndicator.vue`（per §2.3：status 三色 + DOWN 1Hz 闪烁 + aria-label）
- [ ] T028 创建全局 health store `oryxos-admin/src/stores/health.ts`（调用 HealthService.getHealth + 30s 轮询 + status / version）
- [ ] T029 [P] 创建通用组件 `oryxos-admin/src/components/common/StatTile.vue`（per §3.1：label + value + delta_pct + trend + severity）
- [ ] T030 [P] 创建通用组件 `oryxos-admin/src/components/common/EventStream.vue`（per §3.2：items + max_items + click event）
- [ ] T031 [P] 创建通用组件 `oryxos-admin/src/components/common/StatusBadge.vue`（per §3.3：8 种 Status + pulse + aria-label）
- [ ] T032 [P] 创建通用组件 `oryxos-admin/src/components/common/SourceBadge.vue`（per §3.4：7 种 Source + 颜色映射）
- [ ] T033 [P] 创建通用组件 `oryxos-admin/src/components/common/RelativeTime.vue`（per §3.5：iso + refresh_interval + 30s 自动刷新）
- [ ] T034 [P] 创建通用组件 `oryxos-admin/src/components/common/TokenCount.vue`（per §3.6：tokens + cost_usd + K/M 格式化）
- [ ] T035 [P] 创建通用组件 `oryxos-admin/src/components/common/EmptyState.vue`（per §3.7：icon + title + hint + action）
- [ ] T036 [P] 创建通用组件 `oryxos-admin/src/components/common/ErrorState.vue`（per §3.8：error prop + retry callback）
- [ ] T037 [P] 创建通用组件 `oryxos-admin/src/components/common/FilterBar.vue`（per §3.9：fields + modelValue + debounce 300ms + emit update:modelValue + reset）
- [ ] T038 [P] 创建通用组件 `oryxos-admin/src/components/common/CopyButton.vue`（per §3.10：text + navigator.clipboard + 失败回退）

**Checkpoint**: Foundational 完成 — 路由 / HTTP / 通用组件就绪，可并行启动 US-1 / US-2 / US-4 / US-5。

---

## Phase 3: User Story 1 — Ops 5 分钟内定位 Agent 失败根因（P1）🎯 MVP

**Goal**: Session 详情 5 步时间线 + Sandbox 决策可见，Ops 无需 ssh 即可排错

**Independent Test**: per quickstart SC-001 — 故意触发 `wttr.in` 白名单失败的天气查询 → 通过 Session 详情 5 分钟内看到 `SandboxViolationException: domain not whitelisted`（per FR-008 / FR-009）

### Tests for User Story 1（OPTIONAL — NFR-003 强制）

> **NOTE**: 测试先写，**确认失败**后再实现

- [ ] T039 [P] [US1] 单元测试：`oryxos-admin/tests/unit/components/TimelineCard.spec.ts`（5 类型 × 失败态 × 折叠展开 = ≥ 10 用例）
- [ ] T040 [P] [US1] 单元测试：`oryxos-admin/tests/unit/components/StatusBadge.spec.ts`（8 状态 + aria-label = ≥ 9 用例）
- [ ] T041 [P] [US1] 单元测试：`oryxos-admin/tests/unit/stores/sessions.spec.ts`（fetchDetail loading→success + error 处理 + invokeAgent 返回 session_id）
- [ ] T042 [P] [US1] 单元测试：`oryxos-admin/tests/unit/lib/session-filter.spec.ts`（applyFilters 各字段过滤 + 关键字搜索 ≥ 8 用例）
- [ ] T043 [P] [US1] E2E 测试：`oryxos-admin/tests/e2e/session-debug.spec.ts`（Playwright：故意失败 → 进 Session 详情 → 看到 SandboxViolationException → 计时 < 5 分钟）

### Implementation for User Story 1

- [ ] T044 [P] [US1] 创建 Session store `oryxos-admin/src/stores/sessions.ts`（per contracts/store-api.md §5：list + by_id Map + filters + fetchList / fetchDetail / invokeAgent / setFilters / resetFilters）
- [ ] T045 [P] [US1] 创建 business 组件 `oryxos-admin/src/components/business/TimelineCard.vue`（per §4.1：5 类 Step 渲染分支 + 失败态红框 + 折叠 + aria-label）
- [ ] T046 [US1] 创建 Session 列表页 `oryxos-admin/src/pages/sessions/List.vue`（filter bar + 数据表 + SourceBadge + StatusBadge + RelativeTime + EmptyState；URL query 同步 per FR-010）
- [ ] T047 [US1] 创建 Session 详情页 `oryxos-admin/src/pages/sessions/Detail.vue`（5 步 TimelineCard 序列 + 5s 轮询 + visibilitychange 暂停 + 新 Step 闪烁动画 per FR-008）
- [ ] T048 [US1] Session 详情页集成 usePolling（每 5 秒重新拉取 `GET /api/v1/sessions/{id}`，仅 status=running 时启用；切回 visible 立即刷新 per FR-016）
- [ ] T049 [US1] 实现 CSV 导出逻辑 `oryxos-admin/src/lib/export-csv.ts`（per FR-013 / FR-014：7 列 + UTF-8 BOM + Blob 下载 + ≥ 1000 行格式正确）
- [ ] T050 [US1] Session 列表页添加「导出 CSV」按钮 + 调用 export-csv（FR-013 / FR-014）

**Checkpoint**: US-1 完成 — 可演示 5 分钟排错闭环（per SC-001）。

---

## Phase 4: User Story 2 — 业务方看 LLM 成本与调用趋势（P1）🎯 MVP

**Goal**: Dashboard 聚合 tile + Provider 列表 + Agent 详情成本分解

**Independent Test**: per quickstart SC-002 — 7 天 Demo 数据 → Dashboard 30 秒内看到 token 趋势 + Provider 列表 + Agent 详情 token 分解；误差 ≤ 5%（per FR-003 / US-2 验收）

### Tests for User Story 2

- [ ] T051 [P] [US2] 单元测试：`oryxos-admin/tests/unit/components/Sparkline.spec.ts`（24 数据点渲染 + 高度 props）
- [ ] T052 [P] [US2] 单元测试：`oryxos-admin/tests/unit/components/TimeSeriesChart.spec.ts`（24h token 渲染 + hover tooltip + K/M 格式化）
- [ ] T053 [P] [US2] 单元测试：`oryxos-admin/tests/unit/components/PieDistribution.spec.ts`（Provider 饼图数据 + legend）
- [ ] T054 [P] [US2] 单元测试：`oryxos-admin/tests/unit/stores/dashboard.spec.ts`（fetchStats loading→success + tiles 计算正确性 + error 处理）
- [ ] T055 [P] [US2] 单元测试：`oryxos-admin/tests/unit/stores/agents.spec.ts`（fetchList + fetchStats(name) 写入 stats_by_name Map）
- [ ] T056 [P] [US2] 单元测试：`oryxos-admin/tests/unit/stores/providers.spec.ts`（fetchList + healthy/degraded 分类）
- [ ] T057 [P] [US2] E2E 测试：`oryxos-admin/tests/e2e/demo-weather.spec.ts`（Playwright：Dashboard 4 tile → 进 Providers → 看 deepseek 调用 → 时序图可见）

### Implementation for User Story 2

- [ ] T058 [P] [US2] 创建 chart 组件 `oryxos-admin/src/components/charts/Sparkline.vue`（per §5.1：ECharts line + 无坐标轴 + color props + 24 数据点）
- [ ] T059 [P] [US2] 创建 chart 组件 `oryxos-admin/src/components/charts/TimeSeriesChart.vue`（per §5.2：24h token + area gradient + hover tooltip + X 轴 4 tick）
- [ ] T060 [P] [US2] 创建 chart 组件 `oryxos-admin/src/components/charts/PieDistribution.vue`（per §5.3：Provider 饼图 + legend）
- [ ] T061 [US2] 创建 dashboard store `oryxos-admin/src/stores/dashboard.ts`（per contracts/store-api.md §3：stats + tiles computed + fetchStats + reset + 30s 轮询）
- [ ] T062 [US2] 创建 agents store `oryxos-admin/src/stores/agents.ts`（per §4：list + stats_by_name + fetchList + fetchStats(name)）
- [ ] T063 [US2] 创建 providers store `oryxos-admin/src/stores/providers.ts`（per §7：list + healthy/degraded + fetchList + 5min 轮询）
- [ ] T064 [US2] 创建 Dashboard 页 `oryxos-admin/src/pages/Dashboard.vue`（PageHeader + 4 StatTile + TimeSeriesChart + Top 5 EventStream + Recent Events）
- [ ] T065 [US2] 创建 Agent 列表页 `oryxos-admin/src/pages/agents/List.vue`（数据表 + calls_24h + error_rate_24h + SourceBadge 风格状态 + 错误率 > 10% 标红 per US-2 验收 4）
- [ ] T066 [US2] 创建 Agent 详情页 `oryxos-admin/src/pages/agents/Detail.vue`（Profile YAML 展示 + 4 Tab：调用 / 成本 / Tool 分布 / 错误；PieDistribution 在成本 Tab）
- [ ] T067 [US2] 创建 Provider 列表页 `oryxos-admin/src/pages/providers/List.vue`（3 Provider 卡片 + Sparkline 24h 时序 + StatusBadge + 6 stat tile）

**Checkpoint**: US-2 完成 — 可演示成本可视化（per SC-002）。

---

## Phase 5: User Story 3 — 审计 / 合规可自助查询（P2）

**Goal**: Session 列表过滤 + Tool Schema 抽屉 + 复制按钮 + 完整字段导出

**Independent Test**: per quickstart SC-003 — 100 条 Session 样本 → 过滤 + 导出 CSV → 100% 命中 `tool_invocations` notify 行（per FR-013 / FR-014）

### Tests for User Story 3

- [ ] T068 [P] [US3] 单元测试：`oryxos-admin/tests/unit/components/FilterBar.spec.ts`（fields 渲染 + 300ms debounce + update:modelValue emit + reset）
- [ ] T069 [P] [US3] 单元测试：`oryxos-admin/tests/unit/components/ToolSchemaDrawer.vue`（show 控制 + Tool schema 渲染 + 关闭 emit）
- [ ] T070 [P] [US3] 单元测试：`oryxos-admin/tests/unit/lib/export-csv.spec.ts`（7 列 + UTF-8 BOM + Blob URL 下载 + 空数据也生成正确格式）
- [ ] T071 [P] [US3] E2E 测试：`oryxos-admin/tests/e2e/audit-export.spec.ts`（Playwright：进 Sessions → 设置过滤 → 导出 CSV → 验证文件 ≥ 7 列 + BOM）

### Implementation for User Story 3

- [ ] T072 [P] [US3] 创建 business 组件 `oryxos-admin/src/components/business/ToolSchemaDrawer.vue`（per §4.2：NDrawer + Tool 信息 + JSON Schema 语法高亮 + Sandbox 配置）
- [ ] T073 [US3] 创建 tools store `oryxos-admin/src/stores/tools.ts`（per contracts/store-api.md §6：list + builtin/mcp/java_bean 过滤 + 60s 轮询）
- [ ] T074 [US3] 创建 Tool 列表页 `oryxos-admin/src/pages/tools/List.vue`（搜索框 + source filter + 数据表 + 行点击展开 Schema 抽屉）
- [ ] T075 [US3] 扩展 Session 列表过滤能力（在 T046 基础上扩展）：时间段（date range）+ Tool 名（multi-select）+ 关键字（text）= 5 过滤条件同步 URL
- [ ] T076 [US3] Session 详情 Tool Call 卡片添加「复制调用 ID」按钮（per US-3 验收 3）
- [ ] T077 [US3] CSV 导出扩展：除 sessions 元数据外，包含 `tool_invocations` 字段（channel / tool_name / status_code）以覆盖 notify 审计

**Checkpoint**: US-3 完成 — 审计导出 + Tool 详情可见（per SC-003）。

---

## Phase 6: User Story 4 — 手动触发 Agent 测试（P2）

**Goal**: Agent 详情页「手动触发测试」按钮 + 表单 + 跳转新 Session

**Independent Test**: per quickstart SC-004 — 改 Profile YAML prompt → 60 秒内通过 UI 触发 → 看到新 prompt 生效（per FR-011 / FR-012）

### Tests for User Story 4

- [ ] T078 [P] [US4] 单元测试：`oryxos-admin/tests/unit/components/AgentInvocationForm.spec.ts`（message 必填 + submit disable + 成功后 emit session_id + 失败保留表单）
- [ ] T079 [P] [US4] E2E 测试：`oryxos-admin/tests/e2e/manual-invoke.spec.ts`（Playwright：改 prompt → 点手动触发 → 输入消息 → 跳详情 → 看到新 prompt 生效 + 计时 < 60s）

### Implementation for User Story 4

- [ ] T080 [P] [US4] 创建 business 组件 `oryxos-admin/src/components/business/AgentInvocationForm.vue`（per §4.3：NForm + message textarea + submit button + NSpin + 错误 toast + emit submit(session_id)）
- [ ] T081 [US4] Agent 详情页添加「手动触发测试」按钮（per FR-011：在 T066 基础上扩展；点击打开 AgentInvocationForm 抽屉）
- [ ] T082 [US4] 实现 invoke 流程：`AgentInvocationForm` → `sessions.invokeAgent(name, message)` → emit session_id → 父组件 `router.push('/sessions/' + session_id)`（per FR-012 跳转）

**Checkpoint**: US-4 完成 — 可演示手动触发闭环（per SC-004）。

---

## Phase 7: User Story 5 — 视觉规范与运维可观测（P3）

**Goal**: 部署可用 + 视觉与官网一致 + 可达性 + 主题切换

**Independent Test**: per quickstart SC-005 / SC-006 / SC-007 — 5 秒首屏 + 盲测 ≥ 4/5 通过 + NVDA/VoiceOver 8 页可达

### Tests for User Story 5

- [ ] T083 [P] [US5] E2E 测试：`oryxos-admin/tests/e2e/first-screen.spec.ts`（Playwright：访问首页 → 5 秒内看到 Dashboard 4 tile + Console 0 error）
- [ ] T084 [P] [US5] E2E 测试：`oryxos-admin/tests/e2e/demo-news.spec.ts`（Playwright：跨 Session 记忆 — 进 Sessions 过滤 `agent-news` → 看多 Session 关联）
- [ ] T085 [P] [US5] E2E 测试：`oryxos-admin/tests/e2e/demo-github.spec.ts`（Playwright：Agent 详情 → `agent-github` 24h token + 错误率可见）
- [ ] T086 [P] [US5] 可达性测试：`oryxos-admin/tests/e2e/a11y.spec.ts`（Playwright + axe-core：8 页 a11y 扫描 + 关键操作可键盘完成）

### Implementation for User Story 5

- [ ] T087 [US5] 创建 Schedule 页 `oryxos-admin/src/pages/schedules/List.vue`（M0 仅读：横幅说明 + 空态引导 CLI + 数据表占位 + cron 人读释义表 per mockup 08-schedules.html）
- [ ] T088 [US5] 创建 Schedule store 占位 `oryxos-admin/src/stores/schedules.ts`（per contracts/store-api.md §8：返回空数组 + 提示 M1 接入）
- [ ] T089 [US5] 实现 Theme toggle 组件（per US-5 验收 3：darkTheme 切换 + localStorage 持久化 + < 200ms 平滑过渡）
- [ ] T090 [US5] 创建 schedules 路由注册（per T014 路由配置中追加 `/schedules`）
- [ ] T091 [US5] 全局 a11y 审查：所有交互元素 Tab 顺序 + aria-label + 颜色对比度（per FR-017 / FR-018；用 axe-core CLI 扫描）
- [ ] T092 [US5] 屏幕阅读器手动测试（NVDA + VoiceOver）— 记录 8 页测试结果到 `oryxos-admin/docs/a11y-test-report.md`

**Checkpoint**: US-5 完成 — 视觉一致 + 部署可用 + 可达性（per SC-005 / SC-006 / SC-007）。

---

## Phase 8: Polish & Cross-Cutting Concerns

**目的**: 跨所有 user story 的改进

- [ ] T093 [P] 性能优化：ECharts 按需引入 + VxeTable tree-shaking（per NFR-001 bundle ≤ 1MB）
- [ ] T094 [P] 性能优化：所有 store 列表分页懒加载（避免一次性渲染 1w Session）
- [ ] T095 [P] 添加 Lighthouse CI 配置 `oryxos-admin/.lighthouserc.json`（性能 / 可达性 / 最佳实践 阈值卡死）
- [ ] T096 [P] 安全加固：前端不存任何敏感信息（API key 不入 SPA per FR-006 + A-006）；CSP header 配置 `oryxos-admin/public/_headers`
- [ ] T097 [P] 部署文档：`oryxos-admin/docs/deployment.md`（Docker Compose + 手动 Nginx + 反代配置 + 健康检查）
- [ ] T098 [P] 部署文档：`oryxos-admin/docs/configuration.md`（VITE_ORYXOS_BACKEND_URL 环境变量 + 多环境 .env 切换）
- [ ] T099 [P] 用户文档：`oryxos-admin/docs/user-guide.md`（8 页功能说明 + 截图占位 + FAQ）
- [ ] T100 [P] 开发者文档：`oryxos-admin/docs/architecture.md`（store 架构 + 组件分层 + OpenAPI 生成流程）
- [ ] T101 代码清理 + 重构：删除 dead code + 统一 import 顺序（prettier 强制）
- [ ] T102 跑 quickstart 端到端验证：per quickstart.md §7 验证清单 20 项全过
- [ ] T103 [P] 提交 plan 阶段产物 + 完整代码到独立仓库（git add . + commit "feat(009): initial M0 implementation"）
- [ ] T104 [P] 创建 GitHub Actions CI 配置 `oryxos-admin/.github/workflows/ci.yml`（lint + type-check + unit + e2e + build + bundle-size 检查）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖 — T001-T013 立即可启动；T006-T010 涉及 008 聚合端点的 script 需 008 合入后才验证
- **Foundational (Phase 2)**: 依赖 Phase 1（T014-T038 必须 Setup 完成）
- **User Stories (Phase 3-7)**: 依赖 Phase 2
- **Polish (Phase 8)**: 依赖所有期望的 user story 完成

### User Story Dependencies

| Story | 依赖 | 备注 |
|-------|------|------|
| US-1 (P1) | Phase 2 完整 | 无其他 story 依赖；MVP 核心 |
| US-2 (P1) | Phase 2 完整 | 无 US-1 依赖（独立 Dashboard / Provider / Agent 详情） |
| US-3 (P2) | Phase 2 + US-1 列表页 | 扩展 Session 列表过滤 + CSV 导出（基于 US-1 的 sessions store） |
| US-4 (P2) | Phase 2 + US-1 sessions store | 复用 `invokeAgent` action |
| US-5 (P3) | Phase 2 完整 | 主要补 schedules 页 + 可达性 |

### 推荐执行顺序（MVP First）

```text
Phase 1 (Setup)
    ↓
Phase 2 (Foundational) ─── 同时 008 聚合端点合入 main
    ↓
Phase 3 (US-1 P1) ── MVP 演示 ── SC-001 通过
    ↓
Phase 4 (US-2 P1) ── MVP 演示 ── SC-002 通过
    ↓  (US-3 / US-4 / US-5 可并行启动)
Phase 5 (US-3 P2) + Phase 6 (US-4 P2) + Phase 7 (US-5 P3)
    ↓
Phase 8 (Polish)
```

### Parallel Opportunities（per user story）

**US-1 内并行**：
```bash
# 同时启动：
- T039 + T040 + T041 + T042 + T043（5 测试任务并行）
- T044 sessions store + T045 TimelineCard（2 文件独立）
- 后续 T046 + T047 串行（页面）
```

**US-2 内并行**：
```bash
# 同时启动：
- T051 + T052 + T053（chart 测试并行）
- T058 + T059 + T060（3 chart 组件并行）
- T061 dashboard store + T062 agents store + T063 providers store（3 store 并行）
- 后续 T064 + T065 + T066 + T067 串行
```

**US-3 内并行**：
```bash
# 同时启动：
- T068 + T069 + T070（3 测试并行）
- T072 ToolSchemaDrawer + T073 tools store（2 文件独立）
- 后续 T074 + T075 + T076 + T077 串行
```

**US-4 / US-5 内并行**（同上模式）。

---

## Implementation Strategy

### MVP First (US-1 + US-2 Only)

1. **Phase 1**: Setup（T001-T013）
2. **Phase 2**: Foundational（T014-T038）
3. **Phase 3**: US-1（T039-T050）— 排错 MVP
4. **Phase 4**: US-2（T051-T067）— 成本 MVP
5. **STOP and VALIDATE**: 跑 quickstart SC-001 + SC-002（per §7 验证清单 #5 #6 #8）
6. Deploy/Demo（per SC-005：Docker Compose 一行启动 + 5 秒首屏）

### Incremental Delivery

1. Setup + Foundational → Foundation ready（npm install + npm run dev 通过）
2. **+ US-1** → Test 排错 → Deploy（MVP #1）
3. **+ US-2** → Test 成本 → Deploy（MVP #2，per SC-005 完整交付）
4. **+ US-3** → Test 审计 → Deploy
5. **+ US-4** → Test 手动触发 → Deploy
6. **+ US-5** → Test 视觉/可达 → Deploy（完整 M0）

### Parallel Team Strategy（多人并行）

```text
Phase 1 + Phase 2（一起完成）

Phase 3-7 并行：
- Dev A: US-1（排错）
- Dev B: US-2（成本）
- Dev C: US-3（审计）
- Dev D: US-4（手动触发）
- Dev E: US-5（视觉/可达）

最后：
- Dev A: Phase 8 Polish
```

---

## Notes

- **[P] 任务**：不同文件 / 无依赖
- **[Story] 标签**：将任务映射到 user story（per spec.md P1/P2/P3）
- 每个 user story 应**独立**可完成、可测试、可交付
- 测试先写（per NFR-003），确认失败后再实现
- 每个任务或逻辑组提交一次 commit（per CLAUDE.md §17）
- 任何 checkpoint 可停下来独立验证 story
- **避免**：
  - 模糊任务（如 "完善代码"）
  - 同文件并发（产生 merge conflict）
  - 跨 story 依赖（破坏独立性）
- **里程碑**：
  - Setup 完成 → npm install + npm run dev 通过
  - Foundational 完成 → npm run type-check 通过 + 路由可达
  - US-1 完成 → SC-001 通过
  - US-2 完成 → SC-002 通过
  - **MVP**（US-1 + US-2）→ SC-001 / SC-002 / SC-005 通过 + Docker 一行启动
  - 全部完成 → SC-003 / SC-004 / SC-006 / SC-007 通过

---

## Task Count Summary

| Phase | 任务数 | 用户故事 |
|-------|--------|---------|
| Phase 1 Setup | 13 | — |
| Phase 2 Foundational | 25 | — |
| Phase 3 US-1 | 12 | US-1 (P1) |
| Phase 4 US-2 | 17 | US-2 (P1) |
| Phase 5 US-3 | 10 | US-3 (P2) |
| Phase 6 US-4 | 5 | US-4 (P2) |
| Phase 7 US-5 | 10 | US-5 (P3) |
| Phase 8 Polish | 12 | — |
| **总计** | **104** | **5 US** |

**测试任务**：27（13 Vitest + 7 Playwright + 1 axe-core + 1 lighthouse + 5 单元 helper）

**MVP 范围**：Phase 1 + Phase 2 + Phase 3 (US-1) + Phase 4 (US-2) = **67 任务**

---

## Done When

- [ ] tasks.md 已写入 `specs/009-agent-admin-console/tasks.md`
- [ ] 全部 104 任务按 [ID] [P?] [Story] 格式
- [ ] 用户故事独立可测试
- [ ] MVP 范围明确（US-1 + US-2）
- [ ] Extension hooks dispatched or skipped