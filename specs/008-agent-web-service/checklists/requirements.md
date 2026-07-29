# 规格质量清单：Web Service（009-agent-web-service）

**目的**：在进入 `/speckit-plan` 或 `/speckit-tasks` 之前，验证规格说明书的完整性与质量
**创建日期**：2026-07-28
**Feature**：[specs/008-agent-web-service/spec.md](../spec.md)

## 内容质量

- [x] CHK001 文档内不含实现细节（语言、框架、API）—— 主叙述用"业务系统 / 管理员 / 审计员"等业务语言；技术名词（`AgentService` / `WebhookNotifyAdapter` / `springdoc-openapi`）仅在 FR / SC / 实体字段引用处作为契约标识出现；JDK 21 / Spring Boot 3.x 依赖在"假设"节说明
- [x] CHK002 聚焦用户价值与业务需求 —— 4 个用户故事全部从"业务方能用什么端点 / 管理员能否看 / 怎么鉴权"出发：US-1 REST 调 Agent / US-2 会话管理 / US-3 系统查询 / US-4 管理平台
- [x] CHK003 面向非技术干系人可读 —— Given/When/Then 验收场景不堆叠框架术语；技术名词作为契约词汇不深入实现
- [x] CHK004 必填章节全部完成 —— 用户场景与测试 / 需求 / 关键实体 / 成功标准 / 假设 / 不在范围内 / 边界情况 / 引用 八节齐全

## 需求完整性

- [x] CHK005 `[NEEDS CLARIFICATION]` 标记 3 个且 ≤ 3 —— 用户原话包含的"Vue 管理平台"与宪法 §I/§II 存在结构性冲突，已用 3 个 NEEDS_CLARIFICATION 标记按优先级收敛（scope > security > UX > technical）
- [x] CHK006 需求可测试且无歧义 —— 15 条 FR 全部带 MUST/SHOULD 关键字 + 单一可断言行为；3 条受 [NEEDS CLARIFICATION] 阻塞的 FR（FR-008/014/015）显式标记
- [x] CHK007 成功标准可衡量 —— 8 条 SC 含具体指标（10 个 REST 端点 / 并发 10 路 / health P95 ≤ 50ms / 5 分钟 demo 门槛）
- [x] CHK008 成功标准与技术无关 —— SC 描述"业务/运维可观察的现象"（Demo 跑通 / 零串话 / 审计立即可查 / 启动 ≤ 5 分钟），不堆叠 `Spring MVC` / `virtual threads` / `OpenAPI` 等框架名
- [x] CHK009 所有验收场景定义完整 —— 3 个主用户故事共 10 个 Given/When/Then（US-1×4 / US-2×3 / US-3×3）；US-4 受 [NEEDS CLARIFICATION] 阻塞，仅给草案
- [x] CHK010 边界情况已识别 —— 7 条边界情况（启动失败 / Agent 不存在 / LLM 超时 / 请求体非法 / 管理平台未启用 / JSON 反序列化失败 / Profile 热修改），覆盖 IO 异常、字符处理、并发、配置修改
- [x] CHK011 范围边界清晰 —— "不在范围内"节列出 9 项排除项（含鉴权 / SSE / Profile REST / Memory REST / Scheduler REST / Web 仪表板 / WebSocket / 文件上传 / 限流），每条说明宪法或 CLAUDE.md 依据；7 项扩展阶段排除再次显式引用
- [x] CHK012 依赖与假设已识别 —— "假设"节列出 8 条，覆盖 REST 客户端 / Spring Boot 默认 / JDK 21 virtual threads / OpenAPI / Vue 栈（受 NC1 阻塞）/ 鉴权（受 NC3 阻塞）/ HTTP server 实现 / CORS

## Feature 就绪度

- [x] CHK013 所有 FR 有清晰验收标准 —— FR-001 → US-1 验收场景 1 / FR-002 → 架构 6 个 controller / FR-003 → JDK 21 virtual threads / FR-004 → US-1 + 008-agent-scheduler FR-008 路径对齐 / FR-005 → US-1 验收场景 1 / FR-006 → US-1 验收场景 3 / FR-007 → SC-005 / FR-008 → [NC3] / FR-009 → 同步 request-response / FR-010/011/012 → "不在范围内" / FR-013 → springdoc 默认 / FR-014 → [NC1+2] / FR-015 → [NC3]
- [x] CHK014 用户场景覆盖主流程 —— 4 个用户故事覆盖 MVP（US-1 P1 REST 调 Agent）+ 业务扩展（US-2 P2 会话管理）+ 可观测性（US-3 P3 系统查询）+ 治理层诉求（US-4 P3 管理平台 [NC 阻塞]）；MVP = US-1
- [x] CHK015 Feature 满足 SC 中定义的可衡量结果 —— SC-001 10 端点 200 / SC-002 byte-level 反射断言 / SC-003 与 CLI/Scheduler 同 Method 对象 / SC-004 10 并发零串话 / SC-005 DB day-one / SC-006 health P95 / SC-007 mvn verify / SC-008 5 分钟 demo
- [x] CHK016 无实现细节泄露到规格 —— 通篇无 `Tomcat` / `Undertow` / `Netty` / `@RestController` / `@RequestMapping` / `DispatcherServlet` 等具体栈术语；类名仅作为契约标识（`ApiController` / `AgentService.process()` / `GlobalExceptionHandler`）出现；HTTP server 实现 / 鉴权实现 / 构建链路只描述行为不描述实现

## 备注

- 检查项依据 [.specify/templates/checklist-template.md](../../../.specify/templates/checklist-template.md) 的"规格质量"维度，对应 `/speckit-specify` 流程中的"Specification Quality Validation"步骤
- 本 spec 是 OryxOS 核心阶段第 5 个能力（Web Service）的落地 —— [CLAUDE.md §5](../../CLAUDE.md) 已声明 `oryxos-web` 模块但未落地实现；本 spec 不引入新模块，把"接口预留"补到"端到端跑通"
- **关键冲突点**：用户原话「顺带做第一版只读管理平台（Vue，跟官网首页同栈同视觉）」与宪法 §I "Single-Stack Monolith" + §II "Web dashboard 放扩展阶段" 结构性冲突。spec 已用 [NEEDS CLARIFICATION] 1+2 标记 + 在 US-4 / FR-014 处显式阻塞；如要落地需先修订宪法再实施
- **与既有模块的边界**：6 个 `ApiController` + `GlobalExceptionHandler` 全部归 `oryxos-web`（CLAUDE.md §5 既定）；OpenAPI 自动生成由 `oryxos-boot` 加 springdoc 依赖；测试在 `oryxos-web/src/test/`（`@WebMvcTest` 模式）
- **与 008-agent-scheduler 契约对齐**：REST 触发走同一 `AgentService.process(Session, String)` 方法对象（SC-003 反射断言）；`session.metadata.source="web"` 与 `"cli"` / `"scheduler"` 取值枚举三选一完全相同（per 008 data-model.md 实体 4 + FR-008 路径对齐）
- **与 005-tool-system / 006-memory-layer / 007-sandbox-whitelist 三契约对齐**：`tool_invocations` / `llm_calls` / `sessions` 三表 day-one 写库；`error_message` 字节级对齐 007 FR-007；OpenAPI 由 springdoc 自动生成不锁路径
- **进入 plan 阶段需额外检查**：
  - `oryxos-web` 模块当前在 [CLAUDE.md §5](../../CLAUDE.md) 是空壳 → 落地时建包 + 6 controller + DTO + integration test
  - `oryxos-boot` pom.xml 需加 springdoc-openapi-starter-webmvc-ui 依赖（与 §I "JDK 21 + Spring Boot 3.x 单体" 一致；非 Vue 链路）
  - 若 [NEEDS CLARIFICATION] 1 决议"是" → `oryxos-web` 引入 Node.js 构建链路（vue-admin 子模块或独立目录），pom.xml 加 `frontend-maven-plugin` —— 与 §I 单体冲突度大，需宪法修订