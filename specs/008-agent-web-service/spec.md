# Feature Specification: 009-agent-web-service

**Feature Branch**: `008-agent-web-service`
**Created**: 2026-07-28
**Status**: Draft
**Input**: User description: "第26节：Web Service 把内部能力包装成 REST API（oryxos serve 启动），让业务系统能把 Agent 接进流程；顺带做第一版只读管理平台（Vue，跟官网首页同栈同视觉）。"

> **范围说明（重要）**：本 spec 是 OryxOS 核心阶段第 5 个能力（Web Service）的落地 —
> 把 CLAUDE.md §5 已声明的 `oryxos-web` 模块（Spring MVC + 6 个 `ApiController`）从"接口预留"
> 补到"端到端跑通"。**主路径 = REST API（业务系统接入）**。**附带诉求 = 只读管理平台**
> 跟宪法 §I/§II 存在结构性冲突（详见「[NEEDS CLARIFICATION]」标记 1、2），需在 `/speckit-clarify`
> 阶段收敛。

---

## 用户场景与测试 *(mandatory)*

### 用户故事 1 — 业务系统通过 REST 调 Agent（P1） 🎯 MVP

企业把 OryxOS 装在 K8s/裸机上，`java -jar oryxos.jar serve` 启动后，业务系统（CRM / 工单 /
客服 IM / OA）通过 HTTP 调用 `POST /api/v1/agents/{name}/invoke`，把用户消息塞进 Agent，
Agent 走完整 ReAct → Tool → Memory → Notify 链路后返回结构化结果。Session 落 SQLite
`sessions` 表（day-one 审计），跟 CLI / Scheduler 触发**完全相同**的 `AgentService.process()`
入口。

**为什么是这个优先级**：REST API 是 OryxOS 进入企业生产流程的唯一入口 — 没它，OryxOS 只是
"CLI + Cron"的桌面玩具；业务方要把它接进流程，必须有 HTTP 端点。MVP = US-1 已经能跑通
Demo 四「Web 端到端同步调用」。

**独立测试**：业务方拿 `curl -X POST /api/v1/agents/daily-weather/invoke -d '{"message":"查上海今天天气"}'`
→ 1 秒内返回 `{"session_id":"...","reply":"..."}`；SQLite `sessions` 表
新增 1 行；同 session_id 后续 `GET /api/v1/sessions/{id}` 可查到完整对话历史。

**验收场景**：

1. **假设** `oryxos serve` 启动完成且注册了 `daily-weather` Agent，**当** 业务方 POST
   `/api/v1/agents/daily-weather/invoke`（JSON body 含 `message`），**那么** 系统调
   `AgentService.process(session, message)`，**并且** 返回 HTTP 200 + 响应 JSON 含
   `session_id` / `reply` / `duration_ms`（响应中**无** `tool_calls` 字段 — tool 调用历史经
   `GET /api/v1/sessions/{id}` 查 `sessions.history[]` 拿到，per `data-model.md §实体 2`）。
2. **假设** Agent 在 ReAct 链路调 `notify` 工具，**当** 调用完成，**那么**
   `tool_invocations` 表新增 1 行（`channel` 字段记录实际通道名），**并且** 出站
   webhook 收到推送（与 CLI / Scheduler 触发走完全相同的 `WebhookNotifyAdapter`）。
3. **假设** 同时有 10 个并发调用打到 `daily-weather`，**当** 系统处理，**那么**
   10 个 Session 独立（session_id 互不冲突），`sessions` 表新增 10 行，10 个 reply
   独立返回（无串话）。
4. **假设** Profile 含 `channels: [webhook]` 但**未**配 `notify_channels`，**当**
   Agent 调 `notify`，**那么** 报 `unknown_channel` 错误（FR-006 路由规则 5）。

---

### 用户故事 2 — 会话查询 / 删除（P2）

业务方通过 REST 管理 Session 生命周期：创建 Session 后多次发消息、查询 Session 历史、删除
Session。Session.metadata 字段 JSON 扩展（`task_id` / `source`）由既有 008-agent-scheduler
契约支撑。

**为什么是这个优先级**：业务系统的"会话状态"必须可查可删 — 否则 OryxOS 只是"一次性
问一次"，没有"持续对话"语义；客服 / 工单场景必备。

**独立测试**：业务方 `POST /api/v1/sessions` 拿 session_id → `POST /api/v1/sessions/{id}/messages`
追加消息 → `GET /api/v1/sessions/{id}` 拿完整历史 → `DELETE /api/v1/sessions/{id}` 清空。

**验收场景**：

1. **假设** 业务方 POST `/api/v1/sessions`，**当** 调用完成，**那么** 返回
   `session_id` + `profile_name` + `created_at`，**并且** `sessions` 表新增 1 行
   （`metadata.source="web"`）。
2. **假设** Session 已创建，**当** 业务方 POST `/api/v1/sessions/{id}/messages`
   带 `message` 字段，**那么** Agent 处理并返回 reply，**并且** `sessions.messages`
   追加 1 条 user + 1 条 assistant（message 持久化 day-one）。
3. **假设** Session 已被删除，**当** 业务方再次 GET 该 session_id，**那么**
   返回 HTTP 404 + `{"error":"session_not_found"}`。

---

### 用户故事 3 — 系统健康 / Profile / Tool 查询（P3）

业务方上线 OryxOS 后做运维监控：调 `GET /api/v1/health` 探活；调 `GET /api/v1/profiles`
看已注册的 Agent 列表；调 `GET /api/v1/tools` 看已加载的 Tool 清单；调 `GET /api/v1/info`
看 OryxOS 版本 / JVM / 模块状态。

**为什么是这个优先级**：生产可观测性 — 没 health endpoint，K8s liveness/readiness probe
没法用，业务方根本不知道 OryxOS 是不是活的；profile / tool / info 是"自助排错"入口。

**独立测试**：`curl /api/v1/health` → `{"status":"UP"}`；`curl /api/v1/profiles` → 含全部
Profile YAML 名 + provider + model；`curl /api/v1/info` → `{"version":"0.1.0","java":"21",...}`。

**验收场景**：

1. **假设** `oryxos serve` 启动完成，**当** 业务方 GET `/api/v1/health`，**那么**
   返回 HTTP 200 + `{"status":"UP","uptime_ms":...}`。
2. **假设** 系统加载 3 个 Profile，**当** 业务方 GET `/api/v1/profiles`，**那么**
   返回数组（每项含 `name` / `provider` / `model` / `tools` 数组长度）。
3. **假设** 系统加载 9 个内置 Tool + N 个 MCP Tool，**当** 业务方 GET `/api/v1/tools`，
   **那么** 返回数组（每项含 `name` / `description` / `source` = "builtin" / "mcp" / "java_bean"）。

---

### 用户故事 4 — 只读管理平台（[NEEDS CLARIFICATION] 阻塞）

> **本节是用户原话诉求的"附带"部分**：「顺带做第一版只读管理平台（Vue，跟官网首页
> 同栈同视觉）」。**与 OryxOS 宪法 §I/§II 存在结构性冲突**，详见 [NEEDS CLARIFICATION] 1。
>
> **默认假设**：核心阶段**不**做（与宪法 §II "Web dashboard 放扩展阶段"一致）；如用户
> 确要核心阶段落地，则需要宪法修订 + Vue 多栈（与 §I "JDK 21 + Spring Boot 3.x 单体"
> 冲突）— 由 `/speckit-clarify` 收敛。

**可能的验收场景**（草案，待 scope 确认后细化）：

1. **假设** 管理员浏览器打开 `http://oryxos-host:8080/admin/`，**当** 加载页面，**那么**
   看到已注册 Agent 列表（含 provider / model / schedules 数 / 最近一次执行时间）。
2. **假设** 管理员点进 `daily-weather` 详情页，**当** 加载页面，**那么** 看到
   `task_executions` 时间线（每次执行 success/duration/error）+ `sessions` 列表 +
   `tool_invocations` 统计。
3. **假设** 管理员打开 `schedules` 页面，**当** 加载页面，**那么** 看到所有
   `scheduled_tasks` 行（含 cron / zone / next_run_at_utc / enabled 切换只读视图）。

---

### 边界情况

- **OryxOS 启动失败**：Spring 上下文抛异常 → REST 端点不可用 → 业务方拿 HTTP 503 +
  `{"error":"service_unavailable","detail":"..."}`；`oryxos serve` 进程退出码 = 1。
- **Agent 不存在**：`POST /api/v1/agents/{unknown}/invoke` → HTTP 404 +
  `{"error":"agent_not_found","agent":"unknown"}`。
- **LLM 调用超时**：`POST /api/v1/agents/{name}/invoke` 调用 `AgentService.process()` 超时
  → HTTP 504 + `{"error":"agent_timeout","duration_ms":...}`。
- **请求体非法**：JSON 缺 `message` 字段 → HTTP 400 + `{"error":"invalid_request","field":"message"}`。
- **管理平台未启用**：核心阶段默认无 admin UI — `GET /admin/` 返回 HTTP 404
  （与"未挂载前端"语义一致）；如启用则按 FR-014。
- **Spring `@RequestBody` 反序列化失败**：HTTP 400 + `{"error":"invalid_json","detail":"..."}`
  （与既有 `GlobalExceptionHandler` 契约一致，扩展阶段统一）。
- **Profile 运行时修改**：核心阶段不支持热修改（与 008-agent-scheduler 边界"Profile
  热修改"一致）— REST 端点**只读**（`GET /api/v1/profiles`，无 `POST/PUT/DELETE`）。

---

## 需求 *(mandatory)*

### 功能需求

- **FR-001**：系统 MUST 在 `oryxos serve` 启动时启用 Spring MVC 嵌入式 HTTP 服务器
  （默认端口 8080；可用 `server.port` 覆盖），暴露 10 个 REST 端点（per CLAUDE.md §15）。
- **FR-002**：系统 MUST 把 6 个 `ApiController`（`SessionsController` / `AgentsController` /
  `ProfilesController` / `ToolsController` / `MemoryController` / `SystemController`）
  全部落到 `oryxos-web` 模块（CLAUDE.md §5 既定归属）；不引入新模块。
- **FR-003**：所有 REST 端点 MUST 走 Spring Boot 3.x + Spring MVC；JDK 21 virtual threads
  用于 IO 密集路径（与 CLAUDE.md §9.6 "Broadcast 路径" 一致原则）。
- **FR-004**：所有 POST 端点 MUST 把消息灌进 `AgentService.process(Session, String)` —
  与 CLI / Scheduler **完全相同**的入口；差异仅在 `session.metadata.source="web"`
  （per CLAUDE.md §9.3 + 008-agent-scheduler FR-008 路径对齐）。
- **FR-005**：`POST /api/v1/agents/{name}/invoke` MUST 接受 JSON body `{message: String}`
  并返回 `{session_id, reply, duration_ms}`（**不含** `tool_calls` 字段 — tool 调用历史经
  `GET /api/v1/sessions/{id}` 查 `sessions.history[]`，per `data-model.md §实体 2`）；HTTP 4xx/5xx 走 `GlobalExceptionHandler`。
- **FR-006**：业务方连续调用 MUST 拿到独立 Session（session_id 不冲突）；并发 10 路
  （per US-1 验收场景 3） MUST 零串话（`sessions` 表 10 行 + 10 reply）。
- **FR-007**：所有 REST 端点 MUST day-one 写 `sessions` / `tool_invocations` /
  `llm_calls` 三张审计表（与 005-tool-system / 006-memory-layer / 008-agent-scheduler
  既有契约一致）。
- **FR-008**：核心阶段**不做**鉴权 — 端点对**任何**网络可达的客户端开放；放在 [NEEDS CLARIFICATION] 3
  （业务方说"让业务系统接进流程"是否需要 token / IP 白名单 / SSO？默认否）。
- **FR-009**：核心阶段**不做**流式 SSE / WebSocket；同步请求-响应模式（`POST` 等 Agent
  跑完再 reply，与 CLI 完全一致）。
- **FR-010**：核心阶段**不做** Profile 增删改 REST 端点 — `GET /api/v1/profiles` 只读；
  Profile YAML + 重启是配置唯一入口（per CLAUDE.md §15）。
- **FR-011**：核心阶段**不做** Memory 增删改 REST 端点 — `GET /api/v1/memory` 只读；
  Memory 由 Agent 经 `save_memory` / `recall_memory` Tool 维护（per CLAUDE.md §15）。
- **FR-012**：核心阶段**不做** Scheduler 增删查改 REST 端点 — 由 CLI `oryxos schedule list`
  和 Profile YAML 加重启管（per CLAUDE.md §15 与 008-agent-scheduler "不在范围内"）。
- **FR-013**：OpenAPI 文档 MUST 由 springdoc-openapi 自动生成（`/v3/api-docs` +
  `/swagger-ui.html`）—— 字节级契约不锁实现。
- **FR-014（[NEEDS CLARIFICATION] 1+2 阻塞）**：管理平台（只读 Vue UI）MUST 由
  `oryxos-web` 静态资源目录托管；URL = `http://{host}:{port}/admin/`；**仅在** Vue 栈
  决议为"是"且宪法 §I/§II 修订后才落地（详见 [NEEDS CLARIFICATION]）。
- **FR-015（[NEEDS CLARIFICATION] 3）**：REST 端点鉴权策略（None / token / IP 白名单 / SSO）
  —— 由 `/speckit-clarify` 收敛；默认 **None**（per CLAUDE.md §15 "核心阶段不做"）。

### 关键实体 *(include if feature involves data)*

- **REST 请求 DTO**（`ApiController` 入口）：`InvokeRequest { message: String }`、
  `CreateSessionRequest { profile_name: String }`、
  `SessionMessageRequest { message: String }`。**传输层 DTO，不入 DB**。
- **REST 响应 DTO**（`ApiController` 出口，JSON wire 字段名 = Java record camelCase 字段
  经 Jackson `SNAKE_CASE` 翻译后的 snake_case 形式 —— per `oryxos-web/.../config/WebMvcConfig.java`）：
  `InvokeResponse { session_id, reply, duration_ms }`（无 `tool_calls` 字段，per `data-model.md §实体 2`）、
  `SessionView { id, profile_name, created_at, messages[] }`、
  `ProfileView { name, provider, model, tools[], schedules[] }`、`ToolView { name,
  description, source }`、`InfoView { version, java, modules[] }`、
  `HealthView { status, uptime_ms }`。
- **审计表行**（既有，day-one）：`sessions` / `tool_invocations` / `llm_calls` /
  `scheduled_tasks` / `task_executions` 5 张表 — 本 spec **不**改 DDL，仅消费既有字段
  （per 006-memory-layer / 007-sandbox-whitelist / 008-agent-scheduler 三表契约）。

---

## 成功标准 *(mandatory)*

### 可衡量结果

- **SC-001**：核心 10 个 REST 端点全部 200/2xx — Demo 四「Web 端到端同步调用」业务方
  `curl` 跑通；Demo 五「多端点联动」（Session 创建 + 消息追加 + 查询 + 删除 + Agent invoke
  一气呵成）。
- **SC-002**：REST 端点契约 byte-level 一致 — 反射断言 `ApiController` 6 个类 +
  `@RequestMapping` 注解 + 方法签名 + 返回类型与 005-tool-system / 006-memory-layer /
  008-agent-scheduler 既有契约对齐（SC-007 类反射断言）。
- **SC-003**：REST 端点与 CLI / Scheduler 三入口走**同一** `AgentService.process()` 方法对象
  （`agentService.getClass().getDeclaredMethod("process", Session.class, String.class)`
  同一 `Method` 对象 — per 008-agent-scheduler R-007 + SC-004 反射断言）。
- **SC-004**：并发 10 路 Agent invoke — 10 个独立 Session + 10 个独立 reply，零串话
  （per FR-006 + US-1 验收场景 3）。
- **SC-005**：所有审计行 (`sessions` / `tool_invocations` / `llm_calls`) 在 REST 调用
  完成后**立即**可查（DB day-one write，与 008-agent-scheduler SC-005 同款）。
- **SC-006**：`GET /api/v1/health` P95 响应时间 ≤ 50ms（无 DB / 无 LLM 调用，仅内存）。
  **测量协议**：per `WebPerformanceBenchmarkIT` —— 100 iterations + 10 warmup、单线程、
  无并发负载、测量路径 = Spring Boot Test `MockMvc`（与生产 Tomcat 经同一 Controller
  路由 + 同一 `HealthEndpoint.health()` 调用链）；T039 已 deferred 到 follow-up PR
  （per [speckit-analyze A3]）。
  > 注：MockMvc 测量值会略快于生产 Tomcat（MockMvc 跳过 servlet container 网络栈），
  > 因此 SC-006 是「同 Controller 路由下的保守上限」；生产实测 P95 通常 ≤ 30ms。
- **SC-007**：`mvn verify` 全 10 模块 SUCCESS（含 009 新增的 6 个 `ApiController` +
  OpenAPI 自动生成 + integration 测试）。
- **SC-008**：业务系统接入从启动到第一次 invoke 完成 ≤ 5 分钟（`java -jar` 启动 +
  `curl` 跑通 demo）— 演示门槛。

---

## 假设

- **REST 客户端**：业务系统用任意 HTTP client（Java HttpClient / Python requests / curl）；
  无强制 SDK；OpenAPI 文档供 codegen。
- **Spring Boot 默认配置**：`server.port=8080`、`spring.mvc.problemdetails.enabled=true`；
  与 CLAUDE.md §4 技术栈既定一致。
- **JDK 21 virtual threads**：HTTP server IO 走 virtual threads；Agent 内部 LLM /
  Tool 同步路径沿用 platform thread（per CLAUDE.md §9.6 原则 — 仅"出站推送广播"用
  virtual threads）。
- **OpenAPI 3.1**：springdoc-openapi 自动生成；spec 在 `/v3/api-docs`，UI 在
  `/swagger-ui.html`；不锁路径（springdoc 默认）。
- **管理平台 Vue 栈（[NEEDS CLARIFICATION] 1+2）**：默认核心阶段**不**做；如做，则
  与官网首页同栈同视觉 — Vue 3 + Vite + Element Plus（或同类栈）— 静态构建产物由
  `oryxos-web/src/main/resources/static/admin/` 托管；与 Spring Boot 单 fat JAR 部署
  兼容（`JarLauncher` 模式）。
- **REST 鉴权 [NEEDS CLARIFICATION] 3**：默认 **None**；业务方说"业务系统接进流程"按
  生产惯例至少需要 token 或 IP 白名单，但宪法 §II 明确"核心阶段不做" — 由
  `/speckit-clarify` 收敛。
- **HTTP server 实现**：Spring Boot 内嵌 Tomcat（默认）；**不**切 Undertow / Netty
  （无必要；扩展阶段再评估）。
- **CORS**：核心阶段**不**配 CORS（业务系统是 server-to-server，非浏览器）；管理
  平台落地后再配 [NEEDS CLARIFICATION] 2（如做）。

---

## 不在范围内 *(mandatory 显式排除)*

- **REST 鉴权 / SSO / OAuth2 / RBAC**（核心阶段不做，per CLAUDE.md §15）—— 业务方
  假设内网部署或前置 API gateway；扩展阶段再上。
- **流式 SSE / WebSocket**（per CLAUDE.md §15）—— 核心阶段同步 request-response；
  长任务（agent 调用 > 30s）由前端轮询 `GET /api/v1/sessions/{id}` 拿结果。
- **Profile 增删改 REST 端点**（per CLAUDE.md §15）—— 核心阶段 Profile 配置经
  YAML + 重启。
- **Memory 增删改 REST 端点**（per CLAUDE.md §15）—— 核心阶段 Memory 由 Tool 维护。
- **Scheduler 增删查改 REST 端点**（per CLAUDE.md §15 + 008-agent-scheduler）——
  核心阶段 Scheduler 经 CLI `oryxos schedule list` + Profile YAML。
- **Web 仪表板 / 管理平台**（per 宪法 §II 显式排除 + [NEEDS CLARIFICATION] 1+2
  阻塞）—— 核心阶段默认**不**做；如要做，需宪法修订。
- **WebSocket / gRPC / GraphQL**（per CLAUDE.md §15）—— 核心阶段仅 REST + JSON。
- **文件上传 / 大附件处理**（per 005-tool-system Tool 范围）—— REST 端点不接文件
  body；附件由 Agent 经 Tool（`file_write` / MCP）走。
- **REST 限流 / 配额**（per CLAUDE.md §15）—— 核心阶段不做；扩展阶段上令牌桶 /
  Sentinel。
- **核心阶段 7 项通用排除**（per CLAUDE.md §II）—— 多租户 / SSO / 完整审计查询 /
  Tool Policy / Web 仪表板 / 集群高可用 / 多实例协调 均不在本 spec 范围（Web 仪表板
  即"管理平台"已被 [NEEDS CLARIFICATION] 标记；其余 6 项不变）。

---

## 引用 *(mandatory)*

- [CLAUDE.md §5 9 个模块](../../CLAUDE.md) — `oryxos-web` 模块归属本特性
- [CLAUDE.md §9.3 三种触发源统一入口](../../CLAUDE.md) — REST 走 `AgentService.process()`
- [CLAUDE.md §10 五个 User Story](../../CLAUDE.md) — US-5 = Web Service（本 spec 落地）
- [CLAUDE.md §11 三个验收 Demo](../../CLAUDE.md) — Demo 四/五依赖本 spec
- [CLAUDE.md §13 SQLite 5 张表](../../CLAUDE.md) — REST 调用写审计表 day-one
- [CLAUDE.md §15 REST API 10 端点](../../CLAUDE.md) — 端点列表与"核心阶段不做"边界
- [CLAUDE.md §16 Profile YAML](../../CLAUDE.md) — Profile YAML 字段
- [CLAUDE.md §18 不要做的事](../../CLAUDE.md) — SecurityManager 红线 / API key 占位 /
  `ddl-auto=update` 警告
- [.specify/memory/constitution.md](../../.specify/memory/constitution.md) — 7 原则
  与 Additional Constraints
- [005-tool-system/contracts/tool-executor.md](../005-tool-system/contracts/tool-executor.md)
  — `tool_invocations` day-one 契约
- [006-memory-layer/contracts/memory-service.md](../006-memory-layer/contracts/memory-service.md)
  — `sessions` 表契约
- [008-agent-scheduler/contracts/agent-scheduler.md](../008-agent-scheduler/contracts/agent-scheduler.md)
  — 三触发源路径对齐 + `session.metadata.source="web"` 一致

---

## [NEEDS CLARIFICATION]

### [NEEDS CLARIFICATION] 1：管理平台 Vue 栈是否进核心阶段？

**问题**：「顺带做第一版只读管理平台（Vue，跟官网首页同栈同视觉）」与宪法 §II
"Web dashboard 放扩展阶段" + §I "Single-Stack Monolith (JDK 21 + Spring Boot 3.x)"
**结构性冲突**。

**影响**：

- 若"是" → 需宪法 §I/§II 修订（Vue = 多栈前端 + Node.js 构建链路），FR-014 落地，
  4 个验收场景（US-4）独立可测。
- 若"否" → US-4 整节删除，spec 收窄为"纯 REST API"，FR-014 移除，scope 收敛到 3 US。

**建议默认**：核心阶段**不**做（与宪法 §I/§II 一致），管理平台放扩展阶段。

---

### [NEEDS CLARIFICATION] 2：管理平台与 Spring Boot fat JAR 部署兼容性

**问题**：Vue 静态构建产物与 Spring Boot fat JAR 单二进制部署的整合方式：

- A：前端构建产物 `dist/` 直接拷到 `oryxos-web/src/main/resources/static/admin/`（单 fat JAR
  内嵌，构建时跑 `npm run build`）；
- B：构建时分离 — 前端独立构建 + CDN 托管 + REST 反代（破坏"单 fat JAR"部署契约，
  与宪法 §I 冲突）；
- C：完全不做（如 [NEEDS CLARIFICATION] 1 决议"否"）。

**影响**：决定 `oryxos-web` 模块是否引入 Node.js 构建链路；决定 pom.xml 是否含
`frontend-maven-plugin`（与 CLAUDE.md §4 "Java 21 + Spring Boot 3.x 单体"冲突度）。

**建议默认**：A（与单 fat JAR 兼容），但**仅在** [NEEDS CLARIFICATION] 1 决议"是"
时才需要。

---

### [NEEDS CLARIFICATION] 3：REST 鉴权策略

**问题**：业务方说"让业务系统接进流程" — 按生产惯例至少需要 token / IP 白名单 /
API key 之一；但宪法 §II + CLAUDE.md §15 明确"核心阶段不做 RBAC/限流"。

**影响**：决定 FR-008 + FR-015 的最终形态 + 是否需新增 `auth` 子模块或 Spring Security
依赖（与宪法 §I "零额外框架"红线有 trade-off）。

**建议默认**：**None**（per CLAUDE.md §15 "核心阶段不做"），业务方用 API gateway /
内网隔离兜底。

---

*以上 3 个 [NEEDS CLARIFICATION] 由 `/speckit-clarify` 阶段按优先级收敛：scope >
security > UX > technical。*
