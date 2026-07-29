# 研究文档：009-agent-web-service

**生成日期**：2026-07-28
**目的**：把 spec 中未敲定的实现选型 + 集成模式收敛为可落地的技术决策，供 plan.md 引用
**关联**：[spec.md](spec.md) / [plan.md](plan.md)

---

## R-001：HTTP 框架选型（Spring MVC vs WebFlux）

### 决策
**采用 Spring MVC（同步阻塞）+ JDK 21 virtual threads 包装**。

### 依据
- **既有技术栈一致性**：Spring Boot 3.3.5 + Spring AI Alibaba 1.0.0-M6 + 全 Java 21 同步 API（[CLAUDE.md §4](../../CLAUDE.md)）；Spring MVC 是 Spring Boot 默认，无需引入额外栈。
- **Agent 调用模型同步性**：`AgentService.process(Session, String)` 是同步阻塞调用（per 008-agent-scheduler）；WebFlux 强制 reactive 编程模型会传染到业务代码（`Mono<LoopResult>`），与既有同步模型不兼容。
- **JDK 21 virtual threads 弥补并发**：Spring Boot 3.2+ 已支持 `spring.threads.virtual.enabled=true`（Tomcat 11 + Jakarta Servlet 6.0）—— IO 密集路径自动走 virtual thread，CPU 密集路径（LLM 调用解析、JSON 序列化）走 platform thread。
- **错误处理简单**：`@ControllerAdvice` + `GlobalExceptionHandler` 直接 `@ExceptionHandler` 写同步 try/catch 风格；WebFlux 需 `onErrorResume` reactive chain。
- **Demo 友好**：业务方 `curl` 同步请求-响应最直观；不需要 stream / SSE / chunked encoding。

### 已考虑但放弃的备选
| 备选 | 否决理由 |
|------|---------|
| **Spring WebFlux** | 强制 reactive 模型，与同步 `AgentService.process()` 不兼容；需引入 `Reactor` / `Mono` / `Flux` 传染全栈 |
| **Jersey (JAX-RS)** | Spring 生态外栈；与 `@Component` / `@Autowired` 集成差；失去 `@SpringBootTest` + `@WebMvcTest` 测试便利 |
| **Helidon / Quarkus / Micronaut** | 整栈替换 Spring；与既有 005 / 006 / 007 / 008 四个 spring-boot module 不兼容 |

---

## R-002：OpenAPI 文档生成（springdoc-openapi）

### 决策
**采用 `springdoc-openapi-starter-webmvc-ui`（v2.x）自动生成 OpenAPI 3.1 文档**。

### 依据
- **零代码侵入**：`@RestController` 已有注解（`@GetMapping` / `@PostMapping` / `@RequestBody` / `@PathVariable`）→ springdoc 自动反射生成 OpenAPI YAML；不需要额外注解。
- **Spring Boot 3.x 兼容**：springdoc-openapi 2.x 是 Spring Boot 3.x 官方推荐；1.x 已 EOL。
- **Swagger UI 内嵌**：自带 `/swagger-ui.html` —— 业务方开箱可用；零配置。
- **spec 端点**：`/v3/api-docs` 暴露 OpenAPI YAML/JSON 字节级契约；集成测试可断言契约。

### 已考虑但放弃的备选
| 备选 | 否决理由 |
|------|---------|
| **手写 OpenAPI YAML** | 维护成本高；6 个 controller × 10 个端点 × 10+ 字段 → 易漂移；springdoc 自动同步代码注释更稳 |
| **springfox** | 仅支持 Spring Boot 2.x；Spring Boot 3.x 不可用 |
| **Knife4j** | springdoc 的中国 fork + UI 增强；核心 OpenAPI 生成能力与 springdoc 同源；引入额外依赖不必要 |

### 集成模式
```xml
<!-- oryxos-boot/pom.xml 加 1 个依赖 -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.6.0</version>
</dependency>
```

零配置即生效：
- `GET /v3/api-docs` → OpenAPI 3.1 YAML
- `GET /swagger-ui.html` → Swagger UI HTML

---

## R-003：NEEDS CLARIFICATION 1 — 管理平台 Vue 栈是否进核心阶段

### 决策
**核心阶段不做管理平台**（与 spec.md 默认假设一致 + 宪法 §II 显式排除）。

### 依据
- **宪法 §II 显式排除**：core-stage scope discipline 列出 7 项扩展阶段排除项，"Web dashboard" 在其中；spec.md 提示的 Vue 平台正属于此项。
- **宪法 §I "Single-Stack Monolith"**：JDK 21 + Spring Boot 3.x 单体应用；引入 Vue = 多栈前端 + Node.js 构建链路 + `frontend-maven-plugin`，破坏"单 fat JAR 部署"契约（与 [CLAUDE.md §4](../../CLAUDE.md) "打包 Maven 多模块 → fat JAR → java -jar 启动" 不兼容）。
- **核心阶段 5 项验收 Demo 不依赖管理平台**：CLAUDE.md §11 列出的"每日天气 / 每日科技日报 / 每日 GitHub 日报"三个 Demo 都是 CLI / Scheduler / Web 触发 + Notify 推送闭环；UI 只读监控不是 Demo 必要条件。
- **扩展阶段更自然**：管理平台是企业 IT 运维诉求，不是 OryxOS Agent OS 内核地基；放扩展阶段（如 010-admin-platform），可拆 `oryxos-admin` 模块或独立 `oryxos-admin-ui` Node 项目，避免现在污染核心 Maven 多模块。

### 影响
- US-4 整节**不在本 spec 落地**（spec.md 已用 [NEEDS CLARIFICATION] 1 显式阻塞）。
- FR-014（管理平台静态资源托管）**不**作为本 spec 的 FR；扩展阶段新建 spec。
- `oryxos-web/src/main/resources/static/admin/` 目录**不**创建；`GET /admin/` 返回 404（与 spec.md 边界情况"管理平台未启用"对齐）。

### 后续动作
如用户后续决议"核心阶段做管理平台"：
1. 修订宪法 §I（允许多栈）+ §II（移除 Web dashboard 排除）；
2. 新建 spec 010-admin-platform（独立编号）；
3. 加 `oryxos-web` Vue 子项目 + `frontend-maven-plugin`；
4. CI/CD 加 Node.js 构建链路。

---

## R-004：NEEDS CLARIFICATION 2 — Vue 与 Spring Boot fat JAR 整合方式

### 决策
**N/A（与 R-003 决议一致：核心阶段不做管理平台 → 无需 Vue 构建链路）**。

### 依据
- 本 NEEDS CLARIFICATION 仅在 R-003 决议"是"时才有意义。
- R-003 决议"否" → 本 NC 自动 N/A。
- 假设未来扩展阶段决议"是"——建议方案（spec.md 已给）：Vue 静态构建产物 `dist/` 直接拷到 `oryxos-web/src/main/resources/static/admin/`，配 `frontend-maven-plugin` 在 `mvn package` 阶段跑 `npm run build`；保留单 fat JAR 部署契约。

---

## R-005：NEEDS CLARIFICATION 3 — REST 鉴权策略

### 决策
**核心阶段 None（端点对任何网络可达的客户端开放）**。

### 依据
- **宪法 §II "Core-Stage Scope Discipline"** 显式排除 "SSO / authentication" 放扩展阶段。
- **CLAUDE.md §15 "REST API"** 节末段列"核心阶段不做：认证、流式 SSE、WebSocket、RBAC、限流"；REST 鉴权在排除项中。
- **生产惯例**：业务方内网部署 OryxOS + 前置 API gateway（Nginx / Spring Cloud Gateway / Kong / APISIX）；gateway 层做 token / IP 白名单 / OAuth2；OryxOS 内部信任 gateway 透传的请求。
- **替代方案 trade-off**：Spring Security 引入 ~6 个依赖 + `@EnableWebSecurity` 配置层；与宪法 §I "零额外框架"红线 trade-off（虽然 Spring Security 仍属 Spring 生态，但额外配置层增加维护成本）。
- **可选 token 校验（核心阶段可选）：** 如有强需求可在 `application.yaml` 配 `oryxos.web.api-key=<token>` + `GlobalExceptionHandler` 拦截；与 spec.md FR-008 + FR-015 一致。

### 影响
- spec.md FR-008 + FR-015 按"None"实施；`GlobalExceptionHandler` 不做 401/403 拦截。
- 集成测试只覆盖 HTTP 200/400/404/500/503/504；不覆盖 401/403。
- 业务方接入文档强调"OryxOS REST 端点**必须**通过 API gateway 暴露，不直接暴露到公网"。

---

## R-006：REST 端点契约对齐（与 CLAUDE.md §15 一致）

### 决策
**10 个 REST 端点严格对齐 [CLAUDE.md §15](../../CLAUDE.md) 已声明清单**。

### 依据
CLAUDE.md §15 已列 10 端点：

```
会话管理   POST   /api/v1/sessions
         POST   /api/v1/sessions/{id}/messages
         GET    /api/v1/sessions/{id}
         DELETE /api/v1/sessions/{id}
Agent     POST   /api/v1/agents/{name}/invoke
查询     GET    /api/v1/profiles
         GET    /api/v1/memory
         GET    /api/v1/tools
系统     GET    /api/v1/health
         GET    /api/v1/info
```

**不增加**新端点（即使技术上 `PUT` / `PATCH` / `OPTIONS` 是 Spring MVC 默认能力）—— 与 CLAUDE.md §15 "核心阶段不做 Profile 的 create/update" 等显式排除项一致。

### Controller 拆分（6 个 ApiController）
- `SessionsController` (4 端点) — Session CRUD
- `AgentsController` (1 端点) — Agent invoke
- `ProfilesController` (1 端点) — Profile list 只读
- `MemoryController` (1 端点) — Memory 只读
- `ToolsController` (1 端点) — Tools list
- `SystemController` (2 端点) — Health + Info

共 6 Controller × 10 endpoints；与 spec.md FR-002 一致。

---

## R-007：错误响应格式（GlobalExceptionHandler 字节级契约）

### 决策
**统一 JSON 错误响应 shape：`{"error": "<code>", "detail": "<message>", "field"?: "<field>"}`**。

### 依据
- 与既有 005-tool-system / 006-memory-layer / 008-agent-scheduler 错误响应契约一致（per 005 FR-008 + spec.md FR-005 隐含）。
- 业务方 client 用 `error` 字段做程序化分支（HTTP 4xx/5xx），`detail` 做日志/用户提示，`field` 做表单错误定位。
- HTTP 状态码 → `error` code 映射：

| 异常 | HTTP | error code |
|------|------|------------|
| `MethodArgumentNotValidException` (字段校验失败) | 400 | `invalid_request` |
| `HttpMessageNotReadableException` (JSON 反序列化失败) | 400 | `invalid_json` |
| `NoHandlerFoundException` / `AgentNotFoundException` | 404 | `agent_not_found` / `session_not_found` |
| `MethodArgumentTypeMismatchException` (路径参数类型错) | 400 | `invalid_path_param` |
| `AgentTimeoutException` | 504 | `agent_timeout` |
| `Exception` (兜底) | 500 | `internal_error` |
| Spring 启动失败 | 503 | `service_unavailable` |

### 与 spec.md 一致性
- spec.md US-1 验收场景 1 → `InvokeResponse` 正常返回
- spec.md US-1 验收场景 4 → `unknown_channel`（来自 Agent 内部 Tool 调用，不是 REST 层错误）— 由 `tool_invocations` 审计行记录
- spec.md 边界情况 → 7 条已对齐 R-007 错误码

---

## R-008：Session 来源标记（与 008-agent-scheduler 对齐）

### 决策
**REST 触发 → `session.metadata.source="web"`**（与 CLI "cli" / Scheduler "scheduler" 三选一）。

### 依据
- 008-agent-scheduler data-model.md §实体 4 已固化：`metadata.source` 三选一 `"cli"` / `"web"` / `"scheduler"`，枚举值字节级一致。
- SessionFactoryImpl 已实现 `create(profileName, taskId)` 写 metadata JSON；REST 入口走 `create(profileName, null)` + post-set metadata.source="web"。
- 集成测试断言：3 个 trigger 入口产生的 Session 在 `sessions.metadata.source` 字段取值枚举互斥。

### 影响
- spec.md FR-004 实施：`SessionsController.create()` 调 `sessionFactory.create(profileName, null)` → 注入 `metadata.source="web"`。
- spec.md SC-003 反射断言：CLI / Web / Scheduler 三入口走 `AgentService.process(Session, String)` 同一 `Method` 对象。

---

## R-009：性能基线（与 spec.md SC-006 对齐）

### 决策
- `GET /api/v1/health` P95 ≤ 50ms（仅内存访问）
- `POST /api/v1/agents/{name}/invoke` P95 ≤ 30s（含 Agent 全链路 ReAct 10 轮迭代）
- 并发 10 路 invoke → 零串话（US-1 验收场景 3）

### 依据
- `health` 仅读内存字段（`Instant.now()` - `startupInstant`），无 DB / 无 LLM；JDK 21 virtual threads 下 P95 应 < 10ms，留 5x 余量。
- `invoke` 全链路性能 = `AgentService.process()` 性能（per 008-agent-scheduler 性能基线）；不在 REST 层加额外开销。
- 并发：JDK 21 virtual threads 单实例默认即可支撑 100+ 并发 invoke；与 SC-004 "10 路" 留 10x 余量。

### 性能测试位置
`oryxos-web/src/test/java/io/oryxos/web/perf/WebPerformanceBenchmarkIT.java`：
- `healthLatencyP95` — 100 iterations + 10 warmup → P95 ≤ 50ms
- `invokeLatencyP95` — 用 `FakeAgentService` mock（per 008 测试模式）→ P95 测 REST 层 overhead ≤ 100ms
- `concurrentInvokeNoSessionMixing` — 10 并发 → 10 独立 session_id + 10 reply

---

## 引用

- [spec.md](spec.md) — 4 User Story + 15 FR + 8 SC + 3 [NEEDS CLARIFICATION]
- [CLAUDE.md §4 技术栈](../../CLAUDE.md) — JDK 21 + Spring Boot 3.x
- [CLAUDE.md §5 9 个模块](../../CLAUDE.md) — `oryxos-web` 模块归属
- [CLAUDE.md §9.3 三种触发源统一入口](../../CLAUDE.md) — REST 走 `AgentService.process()`
- [CLAUDE.md §15 REST API 10 端点](../../CLAUDE.md) — 端点清单
- [CLAUDE.md §18 不要做的事](../../CLAUDE.md) — SecurityManager / API key 占位 / `ddl-auto=update`
- [.specify/memory/constitution.md §I/§II](../../.specify/memory/constitution.md) — 单体应用 + 5 核心能力
- [008-agent-scheduler/contracts/agent-scheduler.md](../008-agent-scheduler/contracts/agent-scheduler.md) — `metadata.source` 三选一契约
- [005-tool-system/contracts/tool-executor.md](../005-tool-system/contracts/tool-executor.md) — `tool_invocations` 审计契约