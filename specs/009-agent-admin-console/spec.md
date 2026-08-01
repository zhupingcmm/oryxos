# Feature Specification: 009-agent-admin-console

**Feature Branch**: `009-agent-admin-console`
**Created**: 2026-07-29
**Status**: Draft
**Input**: User description: "OryxOS Agent 管理后台 UI"

> **阶段定位（重要）**：本 spec 命中宪法 §II 明确延后的 "Web dashboard" 能力，**属扩展阶段**（M0 只读第一版）。
> 与宪法 §I/§II 存在结构性冲突 —— **走方案 A：先在 `specs/008-agent-web-service/spec.md` 追加 3 个聚合端点**（`/stats/dashboard` / `/stats/agents/{name}` / `/providers`），随后本 spec 以"零后端改动"接入。
> 配套设计文档：[docs/class/Agent-管理后台-UI-设计文档.md](../../docs/class/Agent-管理后台-UI-设计文档.md)
> 配套基线 spec：[specs/008-agent-web-service/spec.md](../008-agent-web-service/spec.md)（US-4 显式阻塞）
>
> **澄清结论（`/speckit-clarify` 收敛 2026-07-29）**：
>
> | 编号 | 问题 | 决策 |
> |------|------|------|
> | Q1 | 后端聚合端点归属 | **A**：追加到 `008-agent-web-service` US-4；本 spec 启动前必须先在 008 补 3 个聚合端点 |
> | Q2 | UI 库选型 | **A**：Naive UI（Vue 3 原生 / TypeScript 优先 / 视觉与官网接近） |
> | Q3 | 部署形态 | **A**：独立 SPA `oryxos-admin/`（独立仓库 / 独立部署 / 后端零改动） |
> | Q4 | HTML mockup 格式 | **A**：静态 HTML + Tailwind CSS（浏览器直接打开 / 工期最短 / 与 SPA 决策一致） |
> | Q5 | HTML mockup 覆盖范围 | **A**：全部 8 页 MVP（DASHBOARD / Agents×2 / Sessions×2 / Tools / Providers / Schedules） |
> | Q6 | HTML mockup 位置 | **A**：`specs/009-agent-admin-console/mockups/`（与 spec.md 同级） |
> | Q7 | HTML mockup 交互级别 | **B**：半静态（折叠 / 状态徽章 / hover / CSS 动画；少量 vanilla JS 或 Alpine.js） |
>
> **额外澄清产物**：4 个新增 mockup 相关 FR（FR-019 ~ FR-022），位于 [§功能需求 §Mockup 产物（FR-019 ~ FR-022）](#mockup-产物fr-019--fr-022)。

---

## Clarifications

### Session 2026-07-29

- Q4：HTML mockup 格式 → A：静态 HTML + Tailwind CSS（浏览器直接打开 / 工期最短 / 与 SPA 决策一致）
- Q5：HTML mockup 覆盖范围 → A：全部 8 页 MVP（DASHBOARD / Agents×2 / Sessions×2 / Tools / Providers / Schedules）
- Q6：HTML mockup 位置 → A：`specs/009-agent-admin-console/mockups/`（与 spec.md 同级）
- Q7：HTML mockup 交互级别 → B：半静态（折叠 / 状态徽章 / hover / CSS 动画；少量 vanilla JS 或 Alpine.js）

---

## 用户场景与测试 *(mandatory)*

### 用户故事 1 — 平台运维 5 分钟内定位一次 Agent 失败根因（P1） 🎯 MVP

平台运维同学收到业务方投诉："daily-weather 这个 Agent 上午 10 点那次调用失败"。
打开 OryxOS 管理后台 → 进入 Dashboard → 看到「24h 异常 Session 数 3 ⚠ ↑12%」→
点击 Session 列表 → 过滤 `daily-weather` + 状态 `failed` + 时间区间 09:00-11:00 →
找到 s-91abc 这一行 → 点开看 Session 详情 → 看到 5 步时间线：User Input → LLM Call
→ `http_get` Tool → 失败 (`SandboxViolationException: domain not whitelisted`) →
Final Answer 是错误兜底回复 → **5 分钟内** 锁定根因是 `wttr.in` 不在白名单。

**为什么是这个优先级**：管理后台的核心价值 = 让 Ops 不用 ssh 上服务器 grep 日志，
不用让业务方贴 50KB 错误栈。**这是产品存在的前提**——没它，OryxOS 跟其他 Agent 框架
没区别。

**独立测试**：在 Demo 一（每日天气）环境下，故意把 `wttr.in` 从
`SandboxProperties.http.allowed-domains` 移除 → 触发失败 → 通过管理后台 5 步时间线
定位到 `SandboxViolationException` → **耗时不超过 5 分钟**（验收 SC-001）。

**验收场景**：

1. **假设** Ops 打开管理后台首屏（Dashboard），**当** 页面加载，**那么** 系统显示
   4 个统计卡片（24h LLM 调用数 / 24h Tool 调用数 / 活跃 Session 数 / 异常 Session 数）
   + 最近 24h Token 消耗趋势图 + Top 5 异常 Session 列表，**并且** 「异常 Session」
   卡片颜色为红且百分比异常上升时附带 ▲ 标记。
2. **假设** Ops 在 Session 列表输入框过滤 `daily-weather` + 状态 `failed`，**当** 提交，
   **那么** 列表只显示满足条件的 Session，**并且** 每行至少包含 session_id（截断显示 +
   hover 完整 ID）、Agent 名、触发源（chat / api / scheduler 三色徽章）、消息数、
   状态徽章、开始时间（相对时间：「3 分钟前」）。
3. **假设** Ops 点开一条失败 Session 详情，**当** 页面渲染，**那么** 系统按时间顺序
   展示 5 类 Step 卡片：User Input / LLM Call / Tool Call / Notify / Final Answer，
   **并且** 失败 Step 卡片整张标红 + 显示 `error_message`（无 stack trace，per
   `specs/005-tool-system`），**并且** Tool Call 卡片显示 Sandbox 是否通过 + 失败原因。
4. **假设** Session 状态为「进行中」（Agent 还在跑），**当** Ops 停留，**那么** 页面
   每 5 秒自动刷新，新出现的 Step 卡片从顶部插入并伴随轻微闪烁动画。

---

### 用户故事 2 — 业务方看 LLM 成本与调用趋势（P1） 🎯 MVP

Agent 业务方每周一上午打开管理后台看「上周花了多少钱」「哪个 Agent 最费」「哪个
Provider 延迟最高」。不需要 ssh、不需要 SQL，直接看 Dashboard + Provider 列表 +
Agent 详情三页数据。

**为什么是这个优先级**：成本透明 = 企业愿意付钱用的前提。LLM 按 token 计费，没可视
化就只能"月底账单惊人"。**OryxOS 跟同行的差异化卖点之一**。

**独立测试**：连续 7 天每天触发 5 次 `daily-weather` → 第 8 天打开 Dashboard → 看到
「24h Token 消耗趋势」折线图 + 「24h LLM 调用 → 35 次」卡 → 进 `/providers` 看到
deepseek 21 次 / kimi 14 次 → 进 `daily-weather` 详情 → 看到 token 分解 →
**判断 deepseek 比 kimi 便宜 60%**（验收 SC-002）。

**验收场景**：

1. **假设** 业务方打开 Dashboard，**当** 页面加载，**那么** 系统显示 24h Token 消耗
   趋势图（按小时聚合，至少 24 个数据点），**并且** 鼠标悬停某点显示「该小时 token
   数 + 估算美元费用」。
2. **假设** 业务方进入 Provider 列表，**当** 页面加载，**那么** 每个 Provider 卡片
   显示：Provider 名 + 状态徽章（健康 / 异常）+ 24h 调用数 + 24h tokens + 错误率 +
   P50/P95 延迟，**并且** 24h 调用时序图显示最近 24h 调用量分布。
3. **假设** 业务方进入 Agent 详情，**当** 切换到「成本」Tab，**那么** 显示该 Agent
   24h/7d/30d 三档时间窗口的 token 消耗 + 按 Provider 分解的饼图。
4. **假设** Agent 24h 错误率超过 10%，**当** 业务方查看 Agent 列表，**那么** 该 Agent
   行的错误率列数字标红 + 显示 ⚠ 图标。

---

### 用户故事 3 — 审计 / 合规可自助查询（P2）

审计 / 合规同学接到合规要求："调取 2026-06-01 到 2026-06-30 期间 user-123 在
notify 渠道的所有出站内容。" 不需要 ssh、不需要让开发跑 SQL 脚本，直接在管理后台
按时间段 + Tool 名 + 关键字 过滤，导出 CSV / JSON。

**为什么是这个优先级**：合规审计是企业级 Agent OS 的硬性需求。OryxOS 已具备
day-one 审计地基（5 张表，见宪法 §VI），管理后台只是"开窗"——但**没这个窗口，
审计同学要靠 dev 跑 SQL**，业务方就不会真部署到生产。

**独立测试**：在 Demo 跑出 100 条 Session（涵盖 chat / scheduler / api 三种触发源）→
审计同学在管理后台按时间段过滤 → 选 notify 工具 → 导出 CSV → 拿到的字段
至少包含 `session_id` / `agent_name` / `tool_name` / `channel` / `timestamp` /
`status_code` / `payload_size`（验收 SC-003）。

**验收场景**：

1. **假设** 审计同学进入 Session 列表，**当** 设置时间段 + Tool 名 + 关键字三个过滤
   条件，**那么** 列表只显示匹配的 Session，**并且** URL 参数同步（刷新页面后过滤
   条件保留，方便分享链接）。
2. **假设** 审计同学在 Session 列表点击「导出 CSV」，**当** 提交，**那么** 浏览器
   下载一个文件，**并且** 至少有 1000 行（即使数据量不足也要保证格式正确），CSV
   列与 `tool_invocations` 表字段对应。
3. **假设** 审计同学进入 Session 详情，**当** 点击 Tool Call 卡片，**那么** 抽屉
   显示完整 args + result + sandbox 决策记录 + 错误堆栈（仅审计角色可见），**并且**
   「复制调用 ID」按钮可一键复制。

---

### 用户故事 4 — 手动触发 Agent 测试（P2）

Agent 开发者（业务方）改完 Profile YAML 后，想立刻验证 Profile 加载正确、Agent
能跑通。在 Agent 详情页点「手动触发测试」→ 弹框输入测试消息 → 系统创建新 Session
+ 跑 Agent → 跳转到 Session 详情看结果。**这条路径与 CLI `oryxos chat` 完全相同**，
都进 `AgentService.process(session, message)`。

**为什么是这个优先级**：开发者体验（DX）—— 改 Profile → 跑 → 看结果 这条链路
应该是 30 秒闭环。现在必须重启服务 + 用 curl 或 CLI，DX 极差。

**独立测试**：业务方改 `daily-weather` 的 `prompt` 字段 → 在管理后台点「手动触发」
→ 输入「查上海天气」→ 5 秒内跳到 Session 详情 → 看到新 prompt 生效（验收 SC-004）。

**验收场景**：

1. **假设** 业务方在 Agent 详情页，**当** 点击「手动触发测试」，**那么** 弹出输入框
   （含 message 字段 + 可选 Profile 覆盖开关），**并且** 提交后禁用按钮 + 显示
   loading 状态（避免重复提交）。
2. **假设** Agent 跑成功，**当** 流程结束，**那么** 页面跳转至新建 Session 详情，
   Session 元数据 `metadata.source="web"` + `metadata.user_id=<当前用户>`。
3. **假设** Agent 跑超时（> 30 秒），**当** 等待结束，**那么** 用户看到「调用超时」
   错误提示，**并且** 系统已记录 timeout 审计行（per `specs/008-agent-web-service`
   US-3 错误处理约定）。

---

### 用户故事 5 — 视觉规范与运维可观测（P3）

整套管理后台视觉风格与官网（[website/](../../website/)）一致：深色为主、紫青双色、
Inter 字体、Lucide 图标。基础可观测性：访问独立 SPA 仓库 `oryxos-admin/` 的入口
地址即可用，**不需要** 单独安装或编译。

**为什么是这个优先级**：视觉一致性是体验基础，但**不能阻塞功能性交付**——M0 MVP
可以接受"先跑通功能再精修视觉"。

**独立测试**：本地 `docker run` 一行启动（Nginx 或类似静态服务器）→ 访问
`http://localhost:5173` → 5 秒内看到 Dashboard 首屏（验收 SC-005）。

**验收场景**：

1. **假设** Ops 在 Linux / macOS / Windows 11 任一环境，**当** 按 README 步骤启动，
   **那么** 浏览器 5 秒内看到 Dashboard 首屏，**并且** 控制台无 error。
2. **假设** Dashboard 在 1280px×800 分辨率下，**当** 浏览器渲染，**那么** 4 个统计
   卡片 + 趋势图 + 列表全部无横向滚动条。
3. **假设** 深色 / 明亮主题切换，**当** 用户点击切换按钮，**那么** 切换时间 < 200ms
   且颜色过渡平滑（无 flash），**并且** 选择持久化到 localStorage。

---

### 边界情况

- **会话超大（≥ 500 条消息）**：Session 详情页懒加载，每次滚动到底部加载 50 条；
  不渲染全量消息。
- **数据稀缺（< 10 个 Session）**：Dashboard 空态显示「运行 Demo 数据生成器」
  的引导，而非空白。
- **后端不可达**：所有页面统一错误占位，显示「OryxOS 服务未启动，请检查
  `oryxos serve` 状态」+ 重试按钮。
- **权限不足**（扩展阶段 M1 接入 SSO 后）：未登录跳 /login，无权限访问页面显示
  403 错误页。
- **跨时区**：所有时间戳服务端 UTC 存储 + 返回 ISO 8601 字符串；前端按浏览器
  `Intl.DateTimeFormat()` 渲染。**不**做手动时区选择（M0 阶段）。
- **超大 Prompt 泄露防护**：Session 详情的 Prompt 列表默认折叠 + 摘要前 200 字符；
  「复制完整 Prompt」按钮仅审计角色可见。
- **高频轮询流量**：浏览器切到后台时自动暂停所有轮询（`visibilitychange` 事件），
  切回前台恢复。

---

## 需求 *(mandatory)*

### 功能性需求

#### 页面渲染与导航

- **FR-001**：管理后台 MUST 提供 8 个核心页面：DASHBOARD / Agents（列表 + 详情） /
  Sessions（列表 + 详情） / Tools / Providers / Schedules，**且** 部署为独立 SPA
  `oryxos-admin/`（决策 A3，独立仓库 / 独立部署 / 后端零改动）。
- **FR-002**：管理后台 MUST 支持 1280px×800 或更大分辨率桌面端；M0 不支持移动端。
- **FR-003**：所有页面 MUST 在平均 100Mbps 网络下首屏 LCP ≤ 2 秒（per SC-005）。
- **FR-004**：所有页面 MUST 严格使用深色为主 + 紫青双色调色板；与 `[website/](../../website/)` 视觉风格一致（具体色值由 plan 阶段锁定）。

#### 数据访问（仅消费，不绕过）

- **FR-005**：管理后台 MUST 仅通过 REST 端点访问数据，**不得**直接连 SQLite 或读日志文件。
- **FR-006**：管理后台 MUST 复用 `specs/008-agent-web-service/spec.md` 定义的 10 个 REST 端点，**且不得要求后端改动**（除非走 Q1 决策 A 授权，把聚合端点追加到 008-agent-web-service US-4）。
- **FR-007**:管理后台 MUST 按各数据时效性采用对应轮询频率：Session 详情 / 列表 5s、
  Dashboard tile 30s、Provider 列表 5min、Tool 列表 60s、Agent 列表 60s。

#### 排错与上下文

- **FR-008**:Session 详情 MUST 按时间顺序展示 5 类 Step 卡片：User Input / LLM Call /
  Tool Call / Notify / Final Answer，**且** 每张卡片 MUST 显示执行耗时、关键参数
  （含 args 摘要）、返回结果摘要、错误信息（若有）。
- **FR-009**：失败 Step 卡片 MUST 整张标红 + 显示 `error_message`（per `specs/005-tool-system`
  SC-006 不含 stack trace），**且** Tool Call 卡片 MUST 显示 Sandbox 决策记录。
- **FR-010**:Session 列表 MUST 支持按 Agent 名 / 触发源 / 状态 / 时间段过滤，**且** 过滤
  条件 MUST 同步到 URL query string（刷新保留、可分享）。

#### 手动触发

- **FR-011**:Agent 详情 MUST 提供「手动触发测试」按钮，**且** 提交后 MUST 复用
  `POST /api/v1/agents/{name}/invoke` 端点（与 CLI / Scheduler 走完全相同的
  `AgentService.process()` 入口）。
- **FR-012**:手动触发 MUST 显示 loading 状态 + 失败错误提示（不闪退），**且** 成功后
  MUST 跳转至新建 Session 详情。

#### 审计与导出

- **FR-013**:Session 列表 MUST 提供「导出 CSV」按钮，**且** 导出的 CSV MUST 至少包含
  `session_id` / `agent_name` / `source` / `status` / `message_count` / `started_at` /
  `duration_ms` 七列。
- **FR-014**:导出 CSV MUST 至少能导出 1000 行（即使数据量不足也要保证格式正确），**且**
  字段编码为 UTF-8 BOM（防止 Excel 打开中文乱码）。

#### 可观测性

- **FR-015**:管理后台 MUST 提供 `/api/v1/health` 状态徽章（顶部导航常驻），**且**
  异常时 MUST 显示红色 + 1 Hz 闪烁。
- **FR-016**:浏览器切到后台时 MUST 自动暂停所有轮询（`visibilitychange` 事件），
  切回前台 MUST 恢复，**且** MUST 立即触发一次刷新（避免用户切回看到旧数据）。

#### 可达性

- **FR-017**:所有交互元素 MUST 键盘可达（Tab 顺序合理），**且** 状态徽章 MUST 配
  `aria-label`（不靠颜色单一传达）。
- **FR-018**:Session 详情页 MUST 支持屏幕阅读器（NVDA / VoiceOver）顺序阅读 5 类
  Step 卡片。

#### Mockup 产物（FR-019 ~ FR-022）

> 本组 FR 描述 **spec 阶段** 交付的 HTML mockup 产物（per clarification Q4-Q7）。
> mockup 本身**不是** M0 最终实现 —— M0 实现（在独立 SPA `oryxos-admin/` 中）会
> 按 spec / mockup 重新写代码。本组 FR 目的是**锁定 spec 阶段的视觉交付物**。

- **FR-019**：mockup MUST 由 8 个独立 HTML 文件组成，分别对应 8 个核心页面：
  `01-dashboard.html` / `02-agents-list.html` / `03-agent-detail.html` /
  `04-sessions-list.html` / `05-session-detail.html` / `06-tools.html` /
  `07-providers.html` / `08-schedules.html`（命名按 Sort 顺序），**且** 全部位于
  `specs/009-agent-admin-console/mockups/`。
- **FR-020**：mockup MUST 使用 Tailwind CSS（CDN 引入 `<script src="https://cdn.tailwindcss.com">`），
  **且** MUST 严格遵循调色板：背景 `#0f1115` / 表面 `#161922` / 主色 `#7c5cff` /
  次色 `#00d4ff` / 文本 `#e6e9ef` / 错误 `#f87171` / 字体 Inter（sans）+ JetBrains Mono（code）。
- **FR-021**：mockup MUST 在 Session 详情页中实现折叠交互（点击 Step 卡片展开 / 折叠
  Prompt 摘要 + Tool Args），**且** 在 Agent 列表 / Session 列表实现 hover 状态
  + 行点击高亮，**且** 失败状态 MUST 用红色边框 + ⚠ 图标（per FR-009）。
  交互 MUST 用 vanilla JS 或 Alpine.js（CDN）实现，**不得**引入 Vue 3 / React /
  任何 SPA 框架。
- **FR-022**：mockup MUST 通过 `mockups/index.html` 入口页提供 8 个页面的导航链接
  + 缩略图，**且** 浏览器双击任一 HTML 文件 MUST 直接渲染（零依赖、零构建）。
  mockup **不**要求响应不同 viewport（仅桌面 1280px+），**但** 浏览器窗口 ≥ 1280px
  时 MUST 无横向滚动条。

### 关键实体（围绕核心 5 张表）

- **Session**：业务会话的逻辑实体，源自 `sessions` 表（per `specs/008-agent-web-service`
  data-model）；关注字段：`id` / `profile_name` / `metadata.source` / `status` /
  `message_count` / `started_at` / `duration_ms`。
- **Agent（Profile）**：已注册的 Agent 逻辑实体，源自 `profile` YAML 文件 + 运行时
  元数据；关注字段：`name` / `provider` / `model` / `tools[]` / `状态` /
  `24h 调用数` / `错误率`。
- **Tool**：已加载的工具逻辑实体，源自 `ToolRegistry` 注册表；关注字段：`name` /
  `description` / `source`（builtin / mcp / java_bean）/ `24h 调用数` / `错误率`。
- **Provider**：LLM 提供方逻辑实体，源自 `application.yaml`；关注字段：`name` /
  `model` / `状态` / `24h 调用数` / `24h tokens` / `错误率` / `P50/P95 延迟`。
- **Step（Session 详情项）**：Session 中的单步执行节点，源自 `sessions` 对话历史 +
  `tool_invocations` + `llm_calls` 三表 JOIN 派生；MUST 建模为不可变 UI 实体（无
  写操作，仅渲染）。

### 非功能性需求

- **NFR-001**（性能）：首屏 LCP ≤ 2s（4G 网络）；Session 详情切换 ≤ 200ms；轮询查询
  P95 ≤ 500ms。
- **NFR-002**（可移植）：Windows 11 / macOS 14 / Ubuntu 22.04+ 三个主流 OS 均可按
  README 步骤启动。
- **NFR-003**（可测试）：单元测试覆盖 ≥ 70% 核心组件（`StatTile` / `TimelineCard` /
  `SessionList` 等）；E2E（Playwright）覆盖 3 个 Demo 场景（天气 / 科技日报 / GitHub 日报）。
- **NFR-004**（依赖）：管理后台 MUST 零后端改动接入；若确需新增聚合端点，**必须**经
  `/speckit-clarify` Q1 决策（A: 追加到 008-agent-web-service US-4）授权后落地，
  本 spec 不持有后端改动任务。

---

## 成功标准 *(mandatory)*

### 可度量结果

- **SC-001**（M0 排错时效）：Ops 在 100 条 Session 样本中，**5 分钟内**
  通过管理后台 Session 详情 5 步时间线定位任意一条失败 Session 的根因（误差
  ± 30 秒）。
- **SC-002**（成本可视化）：业务方打开 Dashboard 后，**30 秒内** 能算出任意
  Provider 24h token 消耗 + 估算费用，**且** 估算值与后端 SQL 真实聚合误差
  ≤ 5%。
- **SC-003**（审计完整）：审计导出 CSV 后，**100% 命中** `tool_invocations` 表的
  notify 工具调用行（无遗漏）。
- **SC-004**（手动触发闭环）：开发者改 Profile YAML → 通过管理后台手动触发 → 看到
  新 prompt 生效，**总耗时 ≤ 60 秒**（含 Profile 加载 + 新 Session 创建）。
- **SC-005**（部署可用）：按 README 步骤启动后，**5 秒内** 浏览器看到 Dashboard
  首屏，**且** 控制台 0 error 0 warning。
- **SC-006**（视觉一致）：盲测 5 名用户，**至少 4 人** 能识别「管理后台与官网同
  一产品」（视觉一致性）。
- **SC-007**（可达性）：用 NVDA / VoiceOver 完整操作 8 个核心页面，**100% 关键操作
  可完成**。

---

## 假设

- **A-001**（后端独立）：管理后台是**纯前端** SPA，**不**修改 `oryxos-web` /
  `oryxos-boot` 等任何后端模块代码（新端点追加走 Q1 决策 A：008-agent-web-service US-4）。
- **A-002**（数据已就位）：5 张审计表（`sessions` / `tool_invocations` / `llm_calls` /
  `scheduled_tasks` / `task_executions`）已在 008-agent-scheduler + 008-agent-web-service
  阶段全量落地，管理后台**只读**消费。
- **A-003**（用户网络）：企业内部用户，部署环境均在企业内网（≥ 100Mbps，可访问
  `localhost:8080`），不假设公网低带宽。
- **A-004**（浏览器）：仅承诺现代浏览器（Chrome / Edge / Firefox / Safari 最新两个
  大版本），不承诺 IE / 旧 Edge 兼容。
- **A-005**（数据量级）：单实例日均 ≤ 10w Session / ≤ 100w Tool 调用；超出此量级
  走扩展阶段 M2 多租户 + 集群（不在本 spec 范围）。
- **A-006**（鉴权）：M0 阶段**无**任何鉴权 — 部署在企业内部，前置 API gateway
  （Nginx / Spring Cloud Gateway）做 SSO 透传；M1 接入 OIDC / SSO（per 设计文档
  §10）。
- **A-007**（单租户）：M0 阶段**单租户**；M2 走多租户（per 设计文档 §10）。
- **A-008**（语言）：M0 阶段文档 + UI 文案均为中文；M2 接入英文 i18n（per 设计文档
  §12）。

---

## 决策记录（/speckit-clarify 收敛）

> 3 个 [NEEDS CLARIFICATION] 已于 2026-07-29 收敛。

### Q1 — 后端聚合端点归属（scope） → **A**

**决策**：追加到 `008-agent-web-service` US-4；本 spec 启动前先在
`specs/008-agent-web-service/spec.md` 补 3 个聚合端点（`/stats/dashboard` /
`/stats/agents/{name}` / `/providers`）。

**含义**：
- 保持宪法 §I 单模块职责 + 后端契约稳定
- 本 spec 落地全部走 FR-006 "零后端改动"约束
- 008-agent-web-service US-4 须先合入 → 009 才进入 `/speckit-plan`

### Q2 — UI 库选型（UX） → **A**

**决策**：Naive UI（Vue 3 原生 / TypeScript 优先 / 视觉风格与官网接近 /
体积中等 ~300KB）。

**含义**：
- 与 [website/](../../website/) 视觉调性一致（同为深色 + 紫青双色）
- 完整 TypeScript 类型（与 auto-generated API client 配合好）
- 排除 Element Plus（视觉偏传统 admin，与官网调性差）
- 排除自研（工期 +2 周，不可接受）

### Q3 — 部署形态（scope / security） → **A**

**决策**：独立 SPA `oryxos-admin/`（独立仓库 / 独立部署 / 后端零改动）。

**含义**：
- SPA 与 `oryxos serve` 完全解耦，部署在 nginx / Vite preview 即可
- 后端零改动（满足 A-001 + FR-006）
- 鉴权由前置 API gateway 处理（M0 无 SPA 内鉴权）
- 排除 `oryxos serve` 静态托管（破例 A-001）
- 嵌入官网子目录（不能用 SPA，受限于 VitePress）

---

## 宪法合规自检（pre-emptive）

> 此段不是 spec 模板的 mandatory 部分，但用于证明本 spec 满足宪法 §II 显式约束。

- **§I (Single-Stack Monolith)**：✅ 后端 9 模块不动；新增 SPA 独立仓库**不**算新增 9 模块之一（独立栈，独立部署）。
- **§II (Core-Stage Scope Discipline)**：⚠️ 「Web dashboard」在延伸阶段列表中 →
  需经 Q1 决策 A 显式授权并附 amend 提案（「Web dashboard 仅 M0 只读部分纳入核心阶段，完整管理后台仍属扩展阶段」）。
- **§III (Self-Implemented ReAct Loop)**：✅ 不涉及 ReAct 改动。
- **§IV (Spring AI Used at Half-Strength)**：✅ 不涉及 Spring AI 改动。
- **§V (Three-Tier Plugin Tooling)**：✅ 不涉及 Tool 改动。
- **§VI (SQLite + MEMORY.md with Day-One Audit Persistence)**：✅ 仅读取 5 张表，不写入。
- **§VII (Demo-First Delivery)**：✅ M0 验收必须 E2E 跑通 3 个 Demo（天气 / 科技日报 / GitHub 日报）。

---

## 关联文档

- 设计文档：[docs/class/Agent-管理后台-UI-设计文档.md](../../docs/class/Agent-管理后台-UI-设计文档.md)
- 基线 Web Service spec：[specs/008-agent-web-service/spec.md](../008-agent-web-service/spec.md)
- 基线 AgentScheduler spec：[specs/008-agent-scheduler/spec.md](../008-agent-scheduler/spec.md)
- 项目宪法：[.specify/memory/constitution.md](../../.specify/memory/constitution.md)
- 项目主记忆：[CLAUDE.md](../../CLAUDE.md)

---

## Done When

- [ ] spec.md 已写入 `specs/009-agent-admin-console/spec.md` 并通过质量清单
- [x] 7 个 [NEEDS CLARIFICATION] 已全部收敛（Q1-Q3 在 `/speckit-specify` 阶段；Q4-Q7 在 `/speckit-clarify` 阶段）
- [ ] 宪法 §II amend 提案被 owner 批准（Q1 决策 A：006-agent-admin-console M0 只读部分纳入核心阶段）
- [ ] 准备进入 `/speckit-plan` 阶段
