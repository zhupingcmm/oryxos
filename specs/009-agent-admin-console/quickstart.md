# Quickstart: 009-agent-admin-console

**Phase**: 1 — Design & Contracts
**Date**: 2026-07-29
**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Data Model**: [data-model.md](data-model.md) | **API**: [contracts/api-endpoints.md](contracts/api-endpoints.md) | **UI**: [contracts/ui-components.md](contracts/ui-components.md) | **Stores**: [contracts/store-api.md](contracts/store-api.md)

> 本文档是 **M0 实现的端到端验证指南**：从环境准备到 7 个 SC（成功标准）全部跑通，
> 全程跑一遍即可交付。**不**包含实现代码——代码归 `/speckit-implement` 阶段。

---

## 0. 前置依赖

> ⚠️ **关键依赖**：本 spec 启动 `/speckit-tasks` 之前，`specs/008-agent-web-service/spec.md` 必须先合入 **3 个聚合端点**（per Phase 0 R-003）。
> 依赖链：A → 合入 008 → 启动 009 plan → tasks → implement。

| 依赖 | 版本 | 用途 |
|------|------|------|
| Node.js | ≥ 18.0.0 | Vite / Vitest 运行时 |
| pnpm | ≥ 8.0.0（或 npm ≥ 9） | 包管理 |
| OryxOS 后端 | ≥ v0.1.0（含 008 + 3 聚合端点） | API 提供方 |
| Docker（可选） | ≥ 24.0 | Docker Compose 一行启动 |
| 现代浏览器 | Chrome / Edge / Firefox / Safari 最新两个大版本 | 运行时 |

---

## 1. 环境准备

### 1.1 克隆后端仓库

```bash
git clone https://github.com/zhupingcmm/oryxos.git
cd oryxos
```

### 1.2 启动 OryxOS 后端（含 3 个聚合端点）

```bash
# 后端必须含 008 + US-4 三个聚合端点
./mvnw -pl oryxos-boot spring-boot:run

# 验证后端在 8080 端口
curl http://localhost:8080/api/v1/health
# 预期：{"status":"UP","components":{...}}
```

### 1.3 验证 3 个聚合端点存在

```bash
# 1. Dashboard 聚合
curl http://localhost:8080/api/v1/stats/dashboard | jq .
# 预期：{ "summary": {...}, "token_trend_24h": [...], ... }

# 2. Agent 详情聚合
curl http://localhost:8080/api/v1/stats/agents/agent-weather | jq .
# 预期：{ "agent_name": "agent-weather", "calls_24h": ..., ... }

# 3. Provider 列表
curl http://localhost:8080/api/v1/providers | jq .
# 预期：{ "providers": [...] }
```

### 1.4 启动 Demo Agent 数据

```bash
# 触发每日天气 Demo（确保有数据可看）
oryxos chat --profile agent-weather --message "查询上海天气"

# 触发每日 GitHub 日报 Demo
oryxos chat --profile agent-github --message "生成今日日报"

# 触发每日科技日报 Demo
oryxos chat --profile agent-news --message "今日科技热点"
```

### 1.5 创建前端仓库

```bash
# 在上级目录
cd ..

# 用 create-vue 脚手架生成
npm create vue@latest oryxos-admin -- --typescript --router --pinia --vitest

cd oryxos-admin

# 安装 Naive UI + 其他依赖
npm install naive-ui @vicons/ionicons5 axios echarts vxe-table dayjs lucide-vue-next

# 安装开发依赖
npm install -D openapi-typescript-codegen @playwright/test vite-plugin-svg-icons
```

---

## 2. 启动前端

### 2.1 配置环境变量

```bash
# .env.development
VITE_ORYXOS_BACKEND_URL=http://localhost:8080
```

### 2.2 自动生成 API client

```bash
# 后端在 8080 运行后
npm run gen:api
# 在 package.json scripts 中定义：
# "gen:api": "openapi-typescript-codegen --input http://localhost:8080/v3/api-docs --output ./src/api/generated --client axios --useUnionTypes"
```

### 2.3 启动开发服务器

```bash
npm run dev

# 浏览器访问
# http://localhost:5173
# 预期：5 秒内看到 Dashboard 首屏（per SC-005）
```

### 2.4 验证生产构建

```bash
npm run build
# 预期：dist/ 目录生成，bundle size ≤ 1MB gzipped（per NFR-001）

npm run preview
# 浏览器访问 http://localhost:4173
```

---

## 3. 跑通 7 个 SC

### SC-001（M0 排错时效）：5 分钟定位失败根因

**前置**：触发一次故意失败的天气查询（从 `wttr.in` 域名白名单移除 `wttr.in`）。

```bash
# 步骤 1：触发失败（修改 SandboxProperties.http.allowed-domains 后重启后端）
# 后端重启后
curl -X POST http://localhost:8080/api/v1/agents/agent-weather/invoke \
  -H "Content-Type: application/json" \
  -d '{"message":"查询上海天气"}'
# 预期返回：{"session_id":"s-...", "status":"pending"}
```

**前端验证**：

1. 打开 `http://localhost:5173/sessions`
2. 过滤 `agent-weather` + `failed`
3. 找到刚创建的失败 Session
4. 点开 Session 详情
5. **计时**：从打开 Dashboard 到看到 `error_message="SandboxViolationException: domain not whitelisted"`
6. **预期**：≤ 5 分钟（误差 ± 30 秒）

**成功条件**：5 步时间线展示完整 + Tool Call 卡片显示 `sandbox_decision="blocked: domain not whitelisted"`。

---

### SC-002（成本可视化）：30 秒算 token 消耗 + 费用

**前置**：连续 7 天每天触发 5 次 `daily-weather`。

**前端验证**：

1. 打开 Dashboard
2. 找到「24h Token 消耗趋势」图
3. **计时**：从打开页面到鼠标悬停某点看到 token 数 + 估算美元费用
4. **预期**：≤ 30 秒
5. 进入 Provider 列表（`/providers`），记录 `deepseek` 的 `tokens_24h`
6. 进入 Agent 详情（`/agents/agent-weather`），切到「成本」Tab，记录 token 分解
7. **后端对照**：
   ```bash
   curl http://localhost:8080/api/v1/providers | jq '.providers[] | select(.name=="deepseek")'
   # 对比 tokens_24h 数值
   ```

**成功条件**：前端显示值与后端 SQL 真实聚合误差 ≤ 5%。

---

### SC-003（审计完整）：CSV 导出 100% 命中

**前置**：跑出 ≥ 100 条 Session（涵盖 chat / scheduler / api 三种触发源）。

**前端验证**：

1. 进入 Session 列表
2. 设置过滤：时间段 + Tool 名 + 关键字
3. 点击「导出 CSV」按钮
4. 下载文件，用 Excel / VSCode 打开
5. **检查**：
   - 列数 ≥ 7 列：`session_id` / `agent_name` / `source` / `status` / `message_count` / `started_at` / `duration_ms`
   - 中文显示正常（UTF-8 BOM 生效）
   - 行数 ≥ 100（即使数据量不足也要保证格式正确，per FR-014）
6. **后端对照**：
   ```bash
   sqlite3 oryxos.db "SELECT COUNT(*) FROM tool_invocations WHERE tool_name='notify'"
   # 对比 CSV 中 notify 相关行数
   ```

**成功条件**：CSV 100% 命中 `tool_invocations` 表的 notify 工具调用行（无遗漏）。

---

### SC-004（手动触发闭环）：≤ 60 秒看到新 prompt 生效

**前置**：修改 `agent-weather` 的 `prompt` 字段。

```bash
# 编辑 .oryxos/agents/agent-weather/AGENT.md
# 修改 prompt 段（如：「请用上海话回答」）
# 保存
```

**前端验证**：

1. 进入 Agent 详情 `http://localhost:5173/agents/agent-weather`
2. 点击「手动触发测试」按钮
3. 在弹框输入「查询上海天气」
4. 提交
5. **计时**：从修改 YAML 到看到新 prompt 生效
6. **预期**：≤ 60 秒（含 Profile 加载 + 新 Session 创建）
7. 跳转到新 Session 详情
8. 检查 Session 元数据 `metadata.source="web"`

**成功条件**：新 Session 详情显示「请用上海话回答」的输出。

---

### SC-005（部署可用）：5 秒首屏 + 控制台 0 error

**前端验证**：

```bash
# 三种环境分别验证
# Linux / macOS / Windows 11

# 1. 启动生产构建
docker-compose up -d

# 2. 浏览器访问
# http://localhost:5173
```

**计时**：从浏览器访问到看到 Dashboard 首屏。

**控制台检查**：

- F12 → Console 面板
- **预期**：0 error 0 warning

**成功条件**：5 秒内首屏 + 0 error。

---

### SC-006（视觉一致）：盲测 5 名用户至少 4 人识别

**非自动化测试**：

1. 准备官网截图（`website/` 首页）
2. 准备管理后台 Dashboard 截图
3. 给 5 名用户展示（盲测，**不告知**两者关系）
4. 询问：「这两个界面是否属于同一产品？」
5. **预期**：≥ 4 人回答「是」

**客观辅助**：

- 调色板对比：两个界面的 `--color-bg` / `--color-accent` 等关键色值应一致
- 字体对比：均为 Inter + JetBrains Mono

**成功条件**：盲测通过。

---

### SC-007（可达性）：NVDA / VoiceOver 完整操作 8 页

**测试工具**：

| OS | 屏幕阅读器 |
|----|----------|
| Windows 11 | NVDA（免费） |
| macOS 14 | VoiceOver（内置） |
| Linux | Orca |

**测试步骤**：

1. 启动 NVDA / VoiceOver
2. 依次访问 8 个核心页面
3. 完成每个页面的关键操作（如过滤 Session、点击 Step 卡片、触发 Agent）
4. **记录**是否能听到所有交互元素的 `aria-label`

**预期**：

- 100% 关键操作可完成
- 状态徽章均配 `aria-label`（不靠颜色单一传达，per FR-017）
- Session 详情 5 步 Step 卡片可顺序阅读（per FR-018）

**成功条件**：盲测通过。

---

## 4. 自动化测试

### 4.1 单元测试（Vitest）

```bash
npm run test:unit

# 预期：覆盖率 ≥ 70%（per NFR-003）
# 重点组件：StatTile / TimelineCard / SessionList / FilterBar / useDashboardStore
```

### 4.2 E2E 测试（Playwright）

```bash
# 安装浏览器
npx playwright install

# 跑 3 个 Demo 场景
npm run test:e2e

# 预期：
# - tests/e2e/demo-weather.spec.ts 通过
# - tests/e2e/demo-news.spec.ts 通过
# - tests/e2e/demo-github.spec.ts 通过
```

**E2E 用例模板**：

```typescript
// tests/e2e/demo-weather.spec.ts
import { test, expect } from '@playwright/test'

test('Demo 一：每日天气', async ({ page }) => {
  await page.goto('http://localhost:5173/dashboard')

  // 验证 4 个统计卡片渲染
  await expect(page.getByText('24h LLM 调用')).toBeVisible()
  await expect(page.getByText('24h Tool 调用')).toBeVisible()
  await expect(page.getByText('活跃 Session')).toBeVisible()
  await expect(page.getByText('24h 异常 Session')).toBeVisible()

  // 验证趋势图渲染
  await expect(page.locator('canvas')).toBeVisible()

  // 跳转 Provider
  await page.click('text=Providers')
  await expect(page).toHaveURL(/.*\/providers/)
  await expect(page.getByText('deepseek')).toBeVisible()
})
```

### 4.3 类型检查 + Lint

```bash
npm run type-check     # vue-tsc --noEmit
npm run lint           # eslint
```

**预期**：0 error。

---

## 5. 部署验证

### 5.1 Docker Compose 一行启动

```yaml
# docker-compose.yml（提交到 oryxos-admin/ 仓库根）
version: '3.9'
services:
  oryxos-admin:
    build: .
    ports:
      - "5173:80"
    environment:
      - BACKEND_URL=http://host.docker.internal:8080  # macOS / Windows
      # 或 - BACKEND_URL=http://172.17.0.1:8080  # Linux
    extra_hosts:
      - "host.docker.internal:host-gateway"  # Linux 需要
```

```bash
docker-compose up -d

# 验证
curl http://localhost:5173/
# 预期：返回 index.html

curl http://localhost:5173/assets/index-xxx.js
# 预期：返回 JS bundle
```

### 5.2 Nginx 反代部署

```nginx
# /etc/nginx/conf.d/oryxos-admin.conf
server {
  listen 80;
  server_name admin.oryxos.local;

  root /var/www/oryxos-admin;
  index index.html;

  # SPA 路由 fallback
  location / {
    try_files $uri $uri/ /index.html;
  }

  # API 反代（避免 CORS）
  location /api/ {
    proxy_pass http://oryxos-backend:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
  }

  # 静态资源缓存
  location /assets/ {
    expires 1y;
    add_header Cache-Control "public, immutable";
  }
}
```

```bash
# 重新加载 Nginx
nginx -s reload

# 验证
curl http://admin.oryxos.local/
```

---

## 6. 故障排查

### 6.1 启动后白屏

**症状**：浏览器访问 5173 显示白屏，F12 控制台报错。

**排查**：

```bash
# 1. 检查后端是否在 8080 运行
curl http://localhost:8080/api/v1/health

# 2. 检查 .env 配置
cat .env.development
# 预期：VITE_ORYXOS_BACKEND_URL=http://localhost:8080

# 3. 检查生成的 API client 是否最新
ls src/api/generated/services/
# 预期：StatsService.ts ProvidersService.ts 等

# 4. 检查 TypeScript 编译
npm run type-check
```

### 6.2 CORS 错误

**症状**：浏览器控制台报 `Access to XMLHttpRequest at '...' from origin '...' has been blocked by CORS policy`。

**原因**：管理后台与后端**未**同源。

**解决**：

- **方案 A**（推荐）：用 Nginx 反代（同源）
- **方案 B**：在 Spring Boot 配置 CORS（仅开发环境）

```java
// 仅开发环境
@Bean
public WebMvcConfigurer corsConfigurer() {
    return new WebMvcConfigurer() {
        @Override
        public void addCorsMappings(CorsRegistry registry) {
            registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "DELETE", "PUT");
        }
    };
}
```

### 6.3 bundle size 超 1MB

**症状**：`npm run build` 警告 bundle size 超阈值。

**优化**：

```typescript
// vite.config.ts
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { NaiveUiResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [NaiveUiResolver()] }),
    Components({ resolvers: [NaiveUiResolver()] }),
  ],
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          'naive-ui': ['naive-ui'],
          'echarts': ['echarts'],
          'vue-vendor': ['vue', 'vue-router', 'pinia'],
        },
      },
    },
    chunkSizeWarningLimit: 800,  // 800KB 单 chunk 警告
  },
})
```

### 6.4 轮询导致后端压力

**症状**：后端日志显示 `/api/v1/stats/dashboard` 调用频繁。

**原因**：前端 30s 轮询 + 后端 5s TTL 缓存，理论 QPS = 用户数 / 30。

**缓解**：

- 单实例 ≤ 50 用户：无需额外优化
- 单实例 > 50 用户：考虑 WebSocket（M1 阶段）

---

## 7. 验证清单（交付前必跑）

| # | 步骤 | 预期 | 通过 |
|---|------|------|------|
| 1 | 后端启动 + 3 个聚合端点 | curl 返回 200 | ☐ |
| 2 | 前端 `npm install` 无 error | 0 error | ☐ |
| 3 | `npm run gen:api` 生成 client | `src/api/generated/` 有 13 个 model | ☐ |
| 4 | `npm run dev` 启动开发服务器 | http://localhost:5173 渲染 | ☐ |
| 5 | Dashboard 首屏渲染 | 4 个 StatTile + 趋势图 + 列表 | ☐ |
| 6 | Session 列表过滤 | URL query 同步 | ☐ |
| 7 | Session 详情时间线 | 5 类 Step 卡片 + Sandbox 决策 | ☐ |
| 8 | Agent 详情成本 Tab | 24h/7d/30d token + 饼图 | ☐ |
| 9 | Provider 列表 | 3 个 Provider + 24h 时序图 | ☐ |
| 10 | Tool 列表 + Schema 抽屉 | 13 Tool + 来源徽章 + Schema 展开 | ☐ |
| 11 | Schedule 列表（M0 占位） | 横幅 + 空态 | ☐ |
| 12 | 手动触发 Agent | ≤ 60 秒看到新 Session | ☐ |
| 13 | CSV 导出 | UTF-8 BOM + ≥ 7 列 | ☐ |
| 14 | `npm run test:unit` 通过 | 覆盖率 ≥ 70% | ☐ |
| 15 | `npm run test:e2e` 通过 | 3 个 Demo 场景 | ☐ |
| 16 | `npm run build` 通过 | bundle size ≤ 1MB | ☐ |
| 17 | `npm run preview` 生产构建 | 5 秒首屏 + 0 console error | ☐ |
| 18 | Docker Compose 启动 | http://localhost:5173 渲染 | ☐ |
| 19 | 屏幕阅读器测试 | NVDA / VoiceOver 通过 | ☐ |
| 20 | 盲测视觉一致性 | ≥ 4/5 用户识别 | ☐ |

---

## 8. 相关链接

- **Spec**：[spec.md](spec.md)
- **Plan**：[plan.md](plan.md)
- **Research**：[research.md](research.md)
- **Data Model**：[data-model.md](data-model.md)
- **API Contract**：[contracts/api-endpoints.md](contracts/api-endpoints.md)
- **UI Contract**：[contracts/ui-components.md](contracts/ui-components.md)
- **Store Contract**：[contracts/store-api.md](contracts/store-api.md)
- **基线 Web Service spec**：[specs/008-agent-web-service/spec.md](../008-agent-web-service/spec.md)
- **设计文档**：[docs/class/Agent-管理后台-UI-设计文档.md](../../docs/class/Agent-管理后台-UI-设计文档.md)
- **项目宪法**：[.specify/memory/constitution.md](../../.specify/memory/constitution.md)
- **项目主记忆**：[CLAUDE.md](../../CLAUDE.md)

---

## 9. 下一步

通过本指南全部 7 个 SC + 验证清单 20 项后，本 spec 进入交付状态。

后续动作：

```bash
# 1. 提交 plan 阶段产物
git add specs/009-agent-admin-console/
git commit -m "docs(009): plan + research + data-model + contracts + quickstart"

# 2. 启动 tasks 阶段
# 用户调用 /speckit-tasks

# 3. 启动 implement 阶段
# 用户调用 /speckit-implement（创建 oryxos-admin/ 仓库 + 写代码）
```