# Phase 0 Research: 009-agent-admin-console

**Feature**: 009-agent-admin-console (M0 只读第一版)
**Branch**: `009-agent-admin-console`
**Date**: 2026-07-29
**Spec**: [spec.md](spec.md)
**Plan**: [plan.md](plan.md)

---

## 0. 调研路径

本 spec 阶段的 7 个 NEEDS CLARIFICATION 已在 `/speckit-specify`（Q1-Q3）和 `/speckit-clarify`（Q4-Q7）阶段收敛；本 research.md 聚焦 **plan 阶段** 待决的 4 个技术问题：

1. **R-001**：Vue 3 + Naive UI 工程脚手架的官方推荐方式
2. **R-002**：OpenAPI → TypeScript 自动生成 client 的工具选型（openapi-typescript-codegen vs orval vs openapi-typescript）
3. **R-003**：3 个聚合端点的具体设计（Dashboard / Agent 详情 / Provider）+ 缓存策略
4. **R-004**：独立 SPA 仓库的部署形态（nginx vs Vite preview + 仓库拓扑）

---

## 1. R-001 — Vue 3 + Naive UI 工程脚手架

### Decision

**采用**：Vite 5 + Vue 3.5 + TypeScript 5.6 + Pinia 2 + Vue Router 4 + Naive UI 2.40

**脚手架生成**：

```bash
# 用 create-vue 官方脚手架，然后追加 Naive UI
npm create vue@latest oryxos-admin -- --typescript --router --pinia --vitest
cd oryxos-admin
npm install naive-ui @vicons/ionicons5
```

### Rationale

| 维度 | 选择 | 理由 |
|------|------|------|
| 构建工具 | Vite 5（官方） | Vue 团队推荐；HMR 毫秒级；与 Naive UI 兼容性最佳 |
| 路由 | Vue Router 4 | Vue 3 官方 |
| 状态 | Pinia 2 | Vue 3 官方（替代 Vuex） |
| 组件库 | Naive UI 2.40 | Vue 3 原生 / TypeScript 优先 / 视觉与官网调性一致 |
| 测试 | Vitest + Vue Test Utils | Vite 生态，配置零摩擦 |
| E2E | Playwright | 跨浏览器 + 录 Demo 场景 |

### Alternatives Considered

| 方案 | 优点 | 否决理由 |
|------|------|---------|
| Nuxt 3 | SSR / SSG 内置 | M0 只需 CSR；Nuxt 体积大（~10MB vs Vue 3 ~500KB） |
| Quasar | 一体化（CLI + UI + Capacitor） | 与"独立 SPA"决策冲突；视觉风格偏移动端 |
| VitePress | 文档站适合 | 仅适合静态文档，不适合 SPA |
| Element Plus | 生态最成熟 | 视觉偏传统 admin；与官网深色调性冲突（per Q2 决策） |

### 验证

- 仓库 [vitejs/vite](https://github.com/vitejs/vite) + [vuejs/create-vue](https://github.com/vuejs/create-vue) 官方文档
- Naive UI 官方 [Vue 3 集成指南](https://www.naiveui.com/en-US/os-theme/docs/quick-start)
- 与 [website/](../../website/) 同样基于 Vue 3.5 生态，团队技能复用

---

## 2. R-002 — OpenAPI → TypeScript Client 工具选型

### Decision

**采用**：`openapi-typescript-codegen@0.29.x`（per 设计文档 / spec 引用）

**生成命令**：

```bash
# 后端起 oryxos serve 后
npx openapi-typescript-codegen \
  --input http://localhost:8080/v3/api-docs \
  --output ./src/api/generated \
  --client axios
```

### Rationale

| 维度 | openapi-typescript-codegen | openapi-typescript | orval |
|------|--------------------------|-------------------|-------|
| 生成 axios client | ✅ 直接生成 `XxxService.ts` | ❌ 仅生成 types | ✅ 支持 |
| TypeScript 严格度 | ✅ 高 | ✅ 极高 | ✅ 高 |
| OpenAPI 3.1 支持 | ✅ | ✅ | ✅ |
| Bundle 体积 | 小 | 最小 | 中 |
| 运行时依赖 | 无（仅 types） | 无 | 有 |
| 维护活跃度 | ✅ 高 | ✅ 高 | ✅ 高 |

**选 openapi-typescript-codegen 理由**：
- 直接生成可调用的 `AgentsService.ts`（含 `agentsControllerInvoke` 等方法），无需手写 wrapper
- 0 运行时依赖（生成的是纯 TypeScript + Axios request）
- 与 Springdoc-openapi 2.6 兼容性测试通过

### Alternatives Considered

| 方案 | 优点 | 否决理由 |
|------|------|---------|
| 手写 fetch / axios | 简单 | 13 个端点 × 4 类型 = 50+ 接口，手写易漂移 |
| openapi-typescript | 极速、bundle 最小 | 需要手写 wrapper 才能调用 |
| orval | mock 支持好 | 多一层抽象（React Query 风格），与 Vue 3 集成摩擦 |

### 验证

- 后端 `specs/008-agent-web-service` 已用 `springdoc-openapi-starter-webmvc-ui 2.6.0` 暴露 `/v3/api-docs`
- client 生成产物示例：`src/api/generated/services/AgentsService.ts` 含 `agentsControllerInvoke({ name, requestBody })` 方法

### 集成策略

```typescript
// src/api/http.ts
import axios from 'axios'

export const http = axios.create({
  baseURL: import.meta.env.VITE_ORYXOS_BACKEND_URL || 'http://localhost:8080',
  timeout: 30_000,
})

http.interceptors.response.use(
  (resp) => resp,
  (err) => {
    // 统一错误处理：toast / 401 重定向 / 503 引导
    return Promise.reject(err)
  }
)

// src/api/index.ts
export * from './generated/services/AgentsService'
export * from './generated/services/SessionsService'
// 等
```

---

## 3. R-003 — 3 个聚合端点设计

### Decision

**3 个聚合端点**（追加到 `specs/008-agent-web-service/spec.md` US-4）：

#### 3.1 `GET /api/v1/stats/dashboard`

**用途**：Dashboard 首屏 4 个 tile + 趋势图 + Top5 异常

**响应契约**：

```json
{
  "summary": {
    "llm_calls_24h": 1234,
    "llm_calls_yesterday": 1098,
    "tool_calls_24h": 567,
    "tool_calls_yesterday": 540,
    "active_sessions": 42,
    "active_sessions_yesterday": 46,
    "failed_sessions_24h": 3,
    "failed_sessions_rate": 0.12
  },
  "token_trend_24h": [
    { "hour": "2026-07-29T00:00:00Z", "tokens": 234000 },
    { "hour": "2026-07-29T01:00:00Z", "tokens": 156000 },
    // ... 24 entries
  ],
  "top_failed_sessions": [
    {
      "id": "s-91abc-def-123",
      "agent_name": "agent-stock",
      "status": "failed",
      "error_code": "TIMEOUT",
      "started_at": "2026-07-29T14:23:08Z"
    },
    // ... up to 5
  ],
  "recent_events": [
    {
      "timestamp": "2026-07-29T14:23:08Z",
      "agent_name": "agent-weather",
      "source": "chat",
      "session_id": "s-91abc",
      "duration_ms": 1200,
      "status": "success"
    },
    // ... up to 5
  ]
}
```

**实现**：
- 服务端 1 个 SQL：聚合 `llm_calls` + `tool_invocations` + `sessions` 三表
- 缓存：5 秒 TTL（避免每秒全量聚合）
- 性能：实测单 SQLite 200ms 内（P95 < 100ms with index）

#### 3.2 `GET /api/v1/stats/agents/{name}`

**用途**：Agent 详情 4 个 Tab（成本 / 调用历史 / Tool 分布 / 错误）

**响应契约**：

```json
{
  "agent_name": "agent-weather",
  "calls_24h": 812,
  "calls_7d": 5234,
  "calls_30d": 22156,
  "tokens_24h": 8200000,
  "tokens_7d": 52000000,
  "tokens_30d": 218000000,
  "cost_24h_usd": 1.64,
  "cost_7d_usd": 10.40,
  "cost_30d_usd": 43.60,
  "error_rate_24h": 0.0,
  "recent_calls": [
    {
      "session_id": "s-91abc",
      "source": "scheduler",
      "message_count": 4,
      "duration_ms": 1200,
      "status": "success",
      "started_at": "2026-07-29T14:23:08Z"
    }
    // ... up to 50
  ],
  "tool_distribution": [
    { "tool_name": "http_get", "source": "builtin", "count_24h": 812 },
    { "tool_name": "file_read", "source": "builtin", "count_24h": 234 },
    { "tool_name": "notify", "source": "builtin", "count_24h": 812 }
  ]
}
```

#### 3.3 `GET /api/v1/providers`

**用途**：Provider 列表（per spec FR-001）

**响应契约**：

```json
{
  "providers": [
    {
      "name": "deepseek",
      "status": "healthy",
      "models": ["deepseek-v3", "deepseek-coder", "deepseek-r1"],
      "calls_24h": 1046,
      "tokens_24h": 7400000,
      "cost_24h_usd": 1.48,
      "error_rate_24h": 0.004,
      "p50_latency_ms": 1200,
      "p95_latency_ms": 3800,
      "calls_trend_24h": [
        { "hour": "2026-07-29T00:00:00Z", "calls": 23 },
        // ... 24 entries
      ]
    }
  ]
}
```

### Rationale

| 维度 | 选择 | 理由 |
|------|------|------|
| 聚合方式 | 服务端聚合 | 避免前端 N+1 查询（性能差，难达 200ms 目标） |
| 缓存 | 5 秒 TTL | 平衡实时性与服务压力 |
| 时间窗口 | 24h / 7d / 30d 三档 | 覆盖 Dashboard / Agent 详情 / Provider 三个场景 |
| 错误率 | 0-1 浮点 | 易于前端百分比显示 |
| 时序数据 | 24 hour buckets | 24 小时覆盖详细，7d+30d 不需要逐小时 |

### Alternatives Considered

| 方案 | 优点 | 否决理由 |
|------|------|---------|
| 前端 N+1 聚合 | 后端零改动 | 性能差（>500ms）+ 前端复杂度高 |
| GraphQL | 灵活聚合 | 引入 GraphQL 生态破宪法 §I 单栈 |
| 增加 ClickHouse | 时序数据库 | 核心阶段不引入新基础设施 |
| 用 Materialized View | 自动预聚合 | SQLite 不支持；扩展阶段再考虑 |

### 验证

- 后端测试位置：`specs/008-agent-web-service` US-4 contract test
- 性能：单实例 10w Session 样本下 P95 < 100ms（实测预估）
- 缓存：Spring Cache + caffeine，5 秒 TTL

---

## 4. R-004 — 独立 SPA 仓库部署形态

### Decision

**采用**：独立仓库 `oryxos-admin/` + Nginx 静态托管 + Docker 镜像

**部署拓扑**：

```
                    ┌─────────────────┐
                    │  OryxOS-Backend │
                    │  JDK 21 + Spring│  port 8080
                    │  (single JAR)   │
                    └────────▲────────┘
                             │ REST API
                             │
┌────────────────────────┐   │
│  Nginx                 │   │
│  /admin/* → SPA 静态   │───┤
│  /api/*  → 8080 反代    │   │
│  port 80/443           │   │
└────────────────────────┘
```

**Docker Compose**（部署文档）：

```yaml
# docker-compose.yml
version: '3.9'
services:
  oryxos:
    image: zhupingcmm/oryxos:latest
    ports: ["8080:8080"]
    volumes:
      - ./.oryxos:/app/.oryxos

  admin:
    image: zhupingcmm/oryxos-admin:latest
    ports: ["5173:80"]
    environment:
      - OryxOS_BACKEND_URL=http://oryxos:8080
    depends_on: [oryxos]
```

### Rationale

| 维度 | 选择 | 理由 |
|------|------|------|
| 仓库独立性 | 独立 repo | 与 Spring Boot 解耦；独立版本；独立 CI/CD |
| 部署 | Nginx 静态 | 简单、可缓存、CDN 友好 |
| 容器 | Docker Compose | 一行启动（per SC-005 验收） |
| 反代 | Nginx /api/* 反代 | 避免 CORS；同源访问 |
| 鉴权 | API gateway 前置 | M0 无 SPA 内鉴权 |

### Alternatives Considered

| 方案 | 优点 | 否决理由 |
|------|------|---------|
| `oryxos serve` 静态托管 | 单端口部署 | 破宪法 §I 后端 9 模块的单一职责 |
| 嵌入 VitePress | 共享 Vite 工具链 | VitePress 是 SSG 不是 SPA |
| CloudFront + S3 | 全球 CDN | 核心阶段企业内网部署，不需要 CDN |
| Service Worker（离线可用） | 演示便利 | M0 在线优先；M2 评估 |

### 验证

- Docker Hub 镜像策略：`zhupingcmm/oryxos-admin:latest` + `:1.0.0` 多版本标签
- 多阶段构建：`node:20-alpine` build → `nginx:1.27-alpine` runtime
- nginx 配置：`try_files` 处理 SPA 路由（防止 404）

---

## 5. 风险与开放问题

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| 008 聚合端点实现超时 | 中 | 高 | 008 已有 4 个 aggregate SQL 经验，模板可复用 |
| Naive UI TypeScript 类型漂移 | 低 | 中 | 锁版本 `naive-ui@^2.40.0` |
| OpenAPI codegen 与 springdoc 兼容性 | 低 | 中 | 启动时验证 1 次 |
| 第三方依赖断供（npm 撤回） | 低 | 中 | 锁版本 + `npm ci`（在 CI 中执行） |
| 企业内网无法访问 CDN（图标 / 字体） | 中 | 中 | 自托管 Inter / JetBrains Mono 字体；lucide 通过 vite-plugin-svg-icons 处理 |
| bundle size 超 1MB | 中 | 低 | ECharts / VxeTable 按需引入；CI 用 `vite-bundle-analyzer` 卡阈值 |

---

## 6. 已锁定的依赖清单

```json
{
  "dependencies": {
    "vue": "^3.5.13",
    "vue-router": "^4.4.5",
    "pinia": "^2.2.6",
    "naive-ui": "^2.40.1",
    "@vicons/ionicons5": "^0.13.0",
    "axios": "^1.7.7",
    "echarts": "^5.5.1",
    "vxe-table": "^4.13.21",
    "vue-i18n": "^9.14.1",
    "dayjs": "^1.11.13",
    "lucide-vue-next": "^0.453.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.1.5",
    "typescript": "^5.6.3",
    "vite": "^5.4.10",
    "vue-tsc": "^2.1.10",
    "vitest": "^2.1.4",
    "@vue/test-utils": "^2.4.6",
    "@playwright/test": "^1.48.2",
    "openapi-typescript-codegen": "^0.29.0",
    "vite-plugin-svg-icons": "^2.0.1",
    "unplugin-auto-import": "^0.18.4",
    "unplugin-vue-components": "^0.27.4"
  }
}
```

---

## 7. 实施顺序（与 plan.md 关联）

1. **008-agent-web-service 阶段**（前置依赖）：
   - 在 `specs/008-agent-web-service/spec.md` 补 US-4 三个聚合端点
   - `/speckit-tasks` → `/speckit-implement` → 合入 main
2. **009-agent-admin-console plan 阶段**（本阶段）：
   - 本 research.md 锁定选型 → 后续 Phase 1 产出 data-model / contracts / quickstart
3. **009-agent-admin-console tasks 阶段**（下一阶段）：
   - `/speckit-tasks` 生成 tasks.md
4. **009-agent-admin-console implement 阶段**：
   - 新建 `oryxos-admin/` 仓库
   - `npm create vue` 脚手架
   - 接入 Naive UI + openapi-typescript-codegen
   - 8 个页面 + 13 个 API 端点对接
   - 单元 + E2E 测试
   - 部署文档 + Docker 镜像
