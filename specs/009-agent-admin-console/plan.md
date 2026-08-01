# Implementation Plan: 009-agent-admin-console

**Branch**: `009-agent-admin-console` | **Date**: 2026-07-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/speckit-specify` + `/speckit-clarify 2026-07-29`
**Source**: User invocation of `/speckit-plan`

## Summary

**目标**：M0 阶段交付只读第一版 OryxOS Agent 管理后台 —— 8 个独立 HTML mockup（spec 阶段产物）
+ 1 个独立 SPA `oryxos-admin/`（M0 实现阶段产物），零后端改动接入 008-agent-web-service 10 个 REST
端点 + 3 个聚合端点（per Q1 决策 A）。

**核心决策**（per `/speckit-clarify` 2026-07-29）：

- **后端聚合端点**：新增 3 个聚合端点（`/stats/dashboard` / `/stats/agents/{name}` / `/providers`），
  全部追加到 `specs/008-agent-web-service/spec.md` US-4。本 spec 启动 plan 阶段前 008 必须先合入。
- **UI 库**：Vue 3.5 + Naive UI（Vue 3 原生 / TypeScript 优先 / 视觉与官网调性一致）。
- **部署**：独立 SPA `oryxos-admin/`（独立仓库 / 独立部署 / 后端零改动）。
- **样式**：Tailwind CSS（CND）+ Naive UI 共存（Tailwind 用 utility class，Naive UI 走组件库）。
- **HTTP 客户端**：Axios + 自动生成的 TypeScript client（基于 springdoc-openapi 3.1 契约）。
- **测试**：Vitest（单元）+ Playwright（E2E，3 个 Demo 场景）。
- **状态**：Pinia 2（官方推荐）。
- **构建**：Vite 5。
- **国际化**：MVP 仅中文（与 spec §A-008 一致）。

**技术路径**：

1. spec 阶段产物（已完成）：`specs/009-agent-admin-console/mockups/` 8 个 HTML + 1 个入口
2. M0 实现阶段（plan 阶段任务）：
   - 008-agent-web-service 先补 3 个聚合端点 → 合入
   - 新建 `oryxos-admin/` 独立仓库（用 git init + .gitignore）
   - Vue 3 + Naive UI + Vite + Pinia 工程脚手架
   - 8 个页面 + 10 个 REST 端点 + 3 个聚合端点对接
   - 单元测试 + E2E 测试
   - README + 部署文档

## Technical Context

**Language/Version**:
- **前端**：TypeScript 5.6 + Vue 3.5（Composition API + `<script setup>`）
- **构建**：Vite 5.4
- **测试**：Vitest 2.1 + Vue Test Utils 2.4 + Playwright 1.48

**Primary Dependencies**:
- `vue@3.5.x` + `vue-router@4.x` + `pinia@2.x`
- `naive-ui@2.40.x`（TypeScript 优先的 Vue 3 组件库）
- `axios@1.7.x` + `openapi-typescript-codegen@0.29.x`（自动生成 API client）
- `echarts@5.5.x`（按需引入）
- `vxe-table@4.x`（大数据量表格）
- `vue-i18n@9.x`（M2 接入）
- `dayjs@1.11.x`（时间处理）
- `lucide-vue-next@0.4xx`（图标）

**Storage**:
- **前端**：无（仅消费后端 API）
- **后端**：复用 `specs/008-agent-web-service` 既有 5 张表（不新增）

**Testing**:
- **单元**：Vitest + Vue Test Utils（覆盖率 ≥ 70% per NFR-003）
- **E2E**：Playwright（3 个 Demo 场景：天气 / 科技日报 / GitHub 日报）
- **类型**：TypeScript strict mode + `vue-tsc` 类型检查

**Target Platform**:
- **运行时**：现代浏览器（Chrome / Edge / Firefox / Safari 最新两个大版本）
- **桌面**：≥ 1280px×800（per FR-002）
- **构建产物**：Vite 静态构建（`dist/`）+ 部署到 nginx / Vite preview
- **Node.js**：≥ 18.0.0（与 Vite / Vitest 要求一致）

**Project Type**:
- **独立 SPA**（per Q3 决策 A）— 独立仓库 `oryxos-admin/`
- 非 9 个 Maven 后端模块之一（不破宪法 §I）

**Performance Goals**:
- **首屏 LCP** ≤ 2s（4G 网络，per NFR-001）
- **Session 详情切换** ≤ 200ms（per NFR-001）
- **轮询查询 P95** ≤ 500ms（per NFR-001）
- **bundle size** ≤ 1MB gzipped（Naive UI 按需引入 + ECharts 按需引入后）

**Constraints**:
- **零后端改动**（per FR-006 / A-001）—— 所有数据 from 008-agent-web-service 既有 10 个 REST 端点
- **聚合端点**（per Q1 决策 A）：先在 008-agent-web-service US-4 补 3 个聚合端点，再启动 009
- **M0 只读**（per spec §A-006）—— 无写操作、无鉴权、无移动端
- **桌面 1280px+**（per FR-002 / FR-022）
- **不可破宪法 §I** —— 不新增 9 个 Maven 后端模块之一（独立仓库独立栈）

**Scale/Scope**:
- **前端**：8 个核心页面 + 10 个通用组件 + 3 个业务组件
- **代码量**：约 5,000-8,000 行 TypeScript（包括测试）
- **测试**：≥ 70% 单元覆盖 + 3 个 E2E 场景
- **数据量**：单实例日均 ≤ 10w Session / ≤ 100w Tool 调用（per A-005）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 状态 | 备注 |
|------|------|------|
| **§I. Single-Stack Monolith** | ✅ PASS | M0 实现是独立 SPA 仓库，**不**算 9 个 Maven 后端模块；后端 9 模块零改动 |
| **§II. Core-Stage Scope Discipline** | ⚠️ DEFERRED | 「Web dashboard」明确在扩展阶段列表；走 Q1 决策 A：M0 只读部分经 owner 批准纳入核心阶段 |
| **§III. Self-Implemented ReAct Loop** | ✅ N/A | 不涉及 ReAct 改动 |
| **§IV. Spring AI Used at Half-Strength** | ✅ N/A | 不涉及 Spring AI 改动 |
| **§V. Three-Tier Plugin Tooling** | ✅ N/A | 不涉及 Tool 改动 |
| **§VI. SQLite + Day-One Audit** | ✅ N/A | 仅消费现有 5 张表，不写入 |
| **§VII. Demo-First Delivery** | ✅ PASS | 验收必经 E2E 跑通 3 个 Demo（per NFR-003） |

**已识别的宪法冲突**：

| 冲突 | 解决路径 |
|------|---------|
| §II 显式延后 "Web dashboard" | 走 Q1 决策 A：M0 只读部分经 owner 批准纳入核心阶段；详细管理后台仍属扩展阶段 |
| 启动 plan 阶段前 008 须先合入 3 个聚合端点 | 依赖路径：A → 合入 008 → 启动 009 plan |

**复杂度追踪**：

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 独立 SPA `oryxos-admin/`（不在 9 模块内） | 宪法 §I 约束「9 模块」与「管理后台为 SPA」的架构冲突 | 嵌入 `oryxos-web` 模块会破 §I 单模块职责 + 邦定 springdoc-openapi 版本 |
| 静态 HTML mockup 已在 spec 阶段交付 | 提前视觉验证降低 M0 返工 | 跳过 mockup 直接做 Vue 3 实现会增加 2-3 倍返工 |
| 3 个聚合端点需在 008 补 | Dashboard / Agent 详情 / Provider 列表需统计聚合，前端 N+1 自聚合性能差 | 前端 N+1 自聚合（200ms 目标不可达） |

## Project Structure

### Documentation (this feature)

```text
specs/009-agent-admin-console/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── api-endpoints.md # 10 REST + 3 聚合端点契约
│   ├── ui-components.md # 10 通用组件契约
│   └── store-api.md     # Pinia store 契约
├── quickstart.md        # Phase 1 output
├── checklists/
│   └── requirements.md  # /speckit-specify 阶段产物
├── mockups/             # /speckit-clarify 阶段产物（per FR-019 ~ FR-022）
│   ├── index.html
│   ├── 01-dashboard.html ... 08-schedules.html
│   └── assets/
└── spec.md              # /speckit-specify 阶段产物
```

### Source Code (repository root)

**M0 实现新增仓库**（独立 git repo，**不在** 当前 Java 仓库内）：

```text
oryxos-admin/                          # 新建独立仓库
├── package.json
├── vite.config.ts
├── tsconfig.json
├── index.html
├── public/
├── src/
│   ├── main.ts
│   ├── App.vue
│   ├── router/
│   │   └── index.ts
│   ├── stores/                        # Pinia
│   │   ├── sessions.ts
│   │   ├── agents.ts
│   │   ├── tools.ts
│   │   ├── providers.ts
│   │   ├── schedules.ts
│   │   └── dashboard.ts
│   ├── api/
│   │   ├── generated/                 # 自动生成（openapi-typescript-codegen）
│   │   ├── http.ts                    # Axios 实例 + 拦截器
│   │   └── types.ts                   # 共享类型
│   ├── pages/
│   │   ├── Dashboard.vue
│   │   ├── agents/
│   │   │   ├── List.vue
│   │   │   └── Detail.vue
│   │   ├── sessions/
│   │   │   ├── List.vue
│   │   │   └── Detail.vue
│   │   ├── tools/
│   │   │   └── List.vue
│   │   ├── providers/
│   │   │   └── List.vue
│   │   └── schedules/
│   │       └── List.vue
│   ├── components/
│   │   ├── layout/
│   │   │   ├── Sidebar.vue
│   │   │   ├── PageHeader.vue
│   │   │   └── HealthIndicator.vue
│   │   ├── common/
│   │   │   ├── StatTile.vue
│   │   │   ├── EventStream.vue
│   │   │   ├── StatusBadge.vue
│   │   │   ├── SourceBadge.vue
│   │   │   ├── RelativeTime.vue
│   │   │   ├── TokenCount.vue
│   │   │   ├── EmptyState.vue
│   │   │   ├── ErrorState.vue
│   │   │   ├── FilterBar.vue
│   │   │   └── CopyButton.vue
│   │   ├── business/
│   │   │   ├── TimelineCard.vue       # Session 详情核心
│   │   │   ├── ToolSchemaDrawer.vue
│   │   │   └── AgentInvocationForm.vue
│   │   └── charts/
│   │       ├── Sparkline.vue
│   │       └── TimeSeriesChart.vue
│   ├── lib/
│   │   ├── polling.ts                 # usePolling composable
│   │   ├── format.ts                  # formatDate / formatToken / formatCost
│   │   ├── api-client.ts              # 生成代码 wrapper
│   │   └── theme.ts                   # 调色板常量
│   └── styles/
│       ├── tokens.css                 # CSS 变量
│       └── global.css
├── tests/
│   ├── unit/                          # Vitest
│   │   ├── components/
│   │   ├── stores/
│   │   └── lib/
│   └── e2e/                           # Playwright
│       ├── demo-weather.spec.ts
│       ├── demo-news.spec.ts
│       └── demo-github.spec.ts
├── .env.example                       # OryxOS_BACKEND_URL=http://localhost:8080
└── README.md
```

**Structure Decision**：
- M0 实现仓库独立（`oryxos-admin/`），与 Spring Boot 后端解耦
- 后端 9 模块 + 3 个聚合端点（在 008 内）已存在
- spec 产物保留在 `specs/009-agent-admin-console/`（含 mockups/）作为视觉基线

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 独立 SPA `oryxos-admin/` 仓库（不在 9 模块内） | 宪法 §I 约束「9 模块」必须存在；M0 管理后台是 SPA 架构（与 9 个 Java 模块本质不同） | 嵌入 `oryxos-web` 模块会破 §I 单一架构 + 邦定 Spring 容器 |
| 静态 HTML mockup spec 阶段交付 | 提前视觉验证降 M0 返工；按 FR-019 ~ FR-022 spec 锁定 | 跳过 mockup 直接做 Vue 3 实现会增加 2-3 倍返工（设计漂移） |
| 3 个聚合端点需 008 先合入 | Dashboard / Provider 等需聚合统计，前端 N+1 自聚合 P95 > 500ms 不达标 | 前端 N+1 自聚合（200ms 目标不可达） |
| M0 不引入移动端 / 鉴权 / 写端点 | 宪法 §II 显式约束 + M0 MVP 范围最小化 | 提前做会模糊 M0 与 M1 边界 |

---

## Done When

- [x] Plan workflow executed
- [x] Technical Context filled
- [x] Constitution Check section filled (1 deferred: §II 经 owner 批准后启动)
- [x] Phase 0: research.md — 已生成
- [x] Phase 1: data-model.md — 已生成
- [x] Phase 1: contracts/api-endpoints.md — 已生成
- [x] Phase 1: contracts/ui-components.md — 已生成
- [x] Phase 1: contracts/store-api.md — 已生成
- [x] Phase 1: quickstart.md — 已生成
- [x] Re-evaluate Constitution Check post-design — 维持 §II DEFERRED；其余 N/A 不变

### Post-Design Constitution Check 复核

| 原则 | 状态（plan 阶段） | 复核结果（post-design） |
|------|------------------|---------------------|
| **§I. Single-Stack Monolith** | ✅ PASS | ✅ PASS — 前端 SPA 独立仓库**不**计入 9 模块 |
| **§II. Core-Stage Scope Discipline** | ⚠️ DEFERRED | ⚠️ DEFERRED — M0 只读部分需 owner 批准（per Q1 决策 A） |
| **§III. Self-Implemented ReAct Loop** | ✅ N/A | ✅ N/A |
| **§IV. Spring AI Used at Half-Strength** | ✅ N/A | ✅ N/A |
| **§V. Three-Tier Plugin Tooling** | ✅ N/A | ✅ N/A |
| **§VI. SQLite + Day-One Audit** | ✅ N/A | ✅ N/A — 仅消费 5 张表，不写入 |
| **§VII. Demo-First Delivery** | ✅ PASS | ✅ PASS — quickstart 验证 7 SC + 20 项清单含 3 Demo |

**新增发现**（post-design）：

| 项 | 状态 | 备注 |
|----|------|------|
| §VI 仅消费约束 | ✅ 强化 | data-model §13 + api-endpoints §3.5 + store-api §11 三层契约均强调**只读** |
| §IV Spring AI half-strength | ✅ 不引入 | 前端不直接调 LLM；唯一 LLM 调用走 `/agents/{name}/invoke`（per FR-011） |
| §V Tool 来源审计 | ✅ 一致 | api-endpoints `/api/v1/tools` 契约明确 `source` 枚举与 §V.7 规则对应 |

### Post-Execution Hooks

`.specify/extensions.yml` **未注册** `after_plan` 钩子（已在文件末尾以注释形式预留）：
```yaml
# after_plan:       stage plan.md + research.md + data-model.md + contracts/ + quickstart.md
```
按 speckit-plan skill 规则：未注册则**静默跳过**。建议 `/speckit-implement` 阶段启用后补 `git add`。
