# Implementation Plan: 009-agent-web-service

**Branch**: `008-agent-web-service` | **Date**: 2026-07-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/008-agent-web-service/spec.md`
**Source**: User invocation of `/speckit-specify` with "第26节：Web Service 把内部能力包装成 REST API（oryxos serve 启动），让业务系统能把 Agent 接进流程；顺带做第一版只读管理平台（Vue，跟官网首页同栈同视觉）。"

## Summary

将 OryxOS 内部能力（Agent 调用 / 会话管理 / Profile / Tool / Memory / 系统健康）包装为 10 个 REST 端点
（per [CLAUDE.md §15](../../CLAUDE.md)），让业务系统能够通过 HTTP 把 Agent 接入业务流程。

**核心决策**：

- **HTTP 栈**：Spring MVC（同步阻塞）+ JDK 21 virtual threads 包装 —— 不引入 WebFlux / 第三方栈
- **OpenAPI**：springdoc-openapi 自动生成零侵入
- **管理平台**：核心阶段不做（与宪法 §I/§II 冲突，已用 [NEEDS CLARIFICATION] 1 显式阻塞）
- **鉴权**：核心阶段 None（业务方前置 API gateway 负责）
- **审计**：复用既有 5 表 + 扩展 `sessions.metadata.source="web"` 取值枚举

**技术路径**：在已声明但未落地的 [CLAUDE.md §5 `oryxos-web` 模块](../../CLAUDE.md) 中实现 6 个 `ApiController` + `GlobalExceptionHandler` + 10 个 DTO；`oryxos-boot` 加 springdoc-openapi 依赖；新增 integration test 覆盖 10 端点。

## Technical Context

**Language/Version**: Java 21（per [CLAUDE.md §4](../../CLAUDE.md) JDK 21 + Spring Boot 3.x）

**Primary Dependencies**:

- Spring Boot 3.3.5 + Spring MVC + Spring Data JPA（per pom.xml 既有）
- `springdoc-openapi-starter-webmvc-ui` 2.6.0（**新增**，自动生成 OpenAPI 3.1）
- `spring-boot-starter-actuator`（**新增**，health endpoint 复用）
- JDK 21 virtual threads（`spring.threads.virtual.enabled=true`）

**Storage**: SQLite（既有 5 表 day-one；不新增表） + 文件 `MEMORY.md`（既有）

**Testing**: JUnit 5 + Spring Boot Test + MockMvc + TestRestTemplate + `@SpringBootTest` + `@WebMvcTest`

**Target Platform**: Linux server / Windows server / macOS（JDK 21 跨平台；Java 字节码不依赖 OS）

**Project Type**: library + cli + web-service（per CLAUDE.md §5 — `oryxos-web` 是 9 模块之一）

**Performance Goals**:

- `GET /api/v1/health` P95 ≤ 50ms（per spec SC-006；仅内存访问）
- `POST /api/v1/agents/{name}/invoke` P95 ≤ 30s（per research.md R-009；含 Agent 全链路 ReAct 10 轮）
- 并发 10 路 invoke 零串话（per spec SC-004；JDK 21 virtual threads 支撑 100+ 并发）

**Constraints**:

- JDK 21 强制（不得用 preview features）
- 单 fat JAR 部署（per CLAUDE.md §4 + 宪法 §I）
- 不引入 Vue 栈（per 宪法 §I/§II + research.md R-003）
- 审计 day-one（per 宪法 §VI + research.md R-007）
- `error_message` 不含 stack trace（per 007-sandbox-whitelist 契约）
- SQLite UTF-8 编码（per CLAUDE.md §18 坑 #4）

**Scale/Scope**:

- 10 REST 端点（per CLAUDE.md §15 + spec FR-002）
- 6 个 ApiController + GlobalExceptionHandler
- 10 个 DTO record
- 9 个集成测试类
- 1 个性能基准测试类

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 评估 | 说明 |
| --- | --- | --- | --- |
| §I | Single-Stack Monolith (JDK 21 + Spring Boot 3.x) | ✅ PASS | 全 Java 21 + Spring Boot 3.3.5；不引入 Node.js / Vue；单 fat JAR 部署契约保持 |
| §II | Core-Stage Scope Discipline | ✅ PASS | 仅实现能力 5（Web Service）；SSO / Web dashboard / Tool Policy / 集群高可用 / 多租户 全部按宪法排除；FR-008/014/015 受 [NEEDS CLARIFICATION] 阻塞 |
| §III | Self-Implemented ReAct Loop | ✅ PASS | REST 入口调 `AgentService.process(Session, String)`（既有实现），不引入新 ReAct 框架；与 008-agent-scheduler 共用同一 Java 方法对象（per SC-003 反射断言） |
| §IV | Spring AI Used at Half-Strength | ✅ PASS | 仅用 Spring AI Provider 抽象 + 协议转换 + `@Tool` schema 生成；不引入 Spring AI Agent 抽象；Tool 调度由既有 `ReActLoop` + `DefaultToolExecutor` 控制 |
| §V | Three-Tier Plugin Tooling | ✅ PASS | Tool **抽象**（`OryxTool` / `ToolRegistry` / `ToolSchemaProvider`）归 `oryxos-core`；Tool **实现**（9 个内置 + Notify + Sandbox + MCP）归 `oryxos-tool`；不引入新 Tool 模块；REST 端点不直接调 Tool，仅调 AgentService |
| §VI | SQLite + MEMORY.md with Day-One Audit Persistence | ✅ PASS | 复用既有 5 表 + `MEMORY.md`；不新增表；REST 触发写入 `sessions`（`metadata.source="web"`）+ `tool_invocations` + `llm_calls` + `task_executions` 四表；与 008 契约字节级对齐 |
| §VII | Demo-First Delivery | ✅ PASS | spec.md 已声明 Demo 四（Web Service 同步调用）+ Demo 五（多端点联动）；quickstart.md §验收场景 1/2/3 + §验收场景 7 覆盖 5 分钟门槛 + Demo 闭环；与 CLAUDE.md §11 三个既有 Demo 一致 |

**Additional Constraints 评估**：

| 约束 | 评估 | 说明 |
| --- | --- | --- |
| 不用 `SecurityManager` | ✅ N/A | Web 层不需要 Sandbox；Sandbox 仅 Tool 调用层使用（005-tool-system / 007-sandbox-whitelist 已落地） |
| 不硬编码 API key | ✅ PASS | Profile YAML `${ENV_VAR}` 占位既有契约；REST 不暴露 API key（仅 `GET /api/v1/info` 暴露 `version` / `javaVersion`） |
| 不依赖 `ddl-auto=update` | ✅ PASS | 本 spec 不新增表；既有 5 表 DDL 由 006/008 锁定 |
| 不按容器类型区分 Provider | ✅ N/A | REST 层不涉及 Provider；Provider 选择在 `AgentService.process()` 既有路径上 |
| 不混淆 Session / LongTerm Memory | ✅ PASS | REST 端点 2-5 操作 Session（`/api/v1/sessions`），端点 7 查询 LongTerm Memory 元数据（`/api/v1/memory`）；二者接口明确分离 |
| 不用非 JDK 21 特性 | ✅ PASS | DTO 用 Java 21 record + pattern matching + sequenced collections；不用任何 preview feature |

**结论**：7 条原则 + 6 条附加约束全部 PASS。无 violation；Complexity Tracking 表留空。

### Post-Design Re-evaluation（Phase 1 后）

Phase 1 产出 4 份设计文档（[research.md](research.md) / [data-model.md](data-model.md) / [contracts/web-api.md](contracts/web-api.md) / [quickstart.md](quickstart.md)）后再次审查：

| # | 原则 | 复审结论 | 关键变更说明 |
| --- | --- | --- | --- |
| §I | Single-Stack Monolith | ✅ PASS | 4 份设计文档零次提及 Node.js / npm / 前端构建；仅 Java 21 + Spring Boot + springdoc + actuator 4 个 Maven 依赖 |
| §II | Core-Stage Scope Discipline | ✅ PASS | data-model.md §实体 9/10 不暴露完整 Profile YAML / Memory 内容（与 [CLAUDE.md §15](../../CLAUDE.md) "核心阶段不做" 排除项对齐）；contracts/web-api.md 10 端点严格对齐 §15 清单 |
| §III | Self-Implemented ReAct Loop | ✅ PASS | contracts/web-api.md 端点 1 显式声明 `Method.getDeclaringClass() == AgentService.class` 反射断言（per SC-003）；不引入新 ReAct 框架 |
| §IV | Spring AI Used at Half-Strength | ✅ PASS | research.md R-001 拒绝 WebFlux（防止 reactive 传染）；零 Spring AI Agent 抽象依赖 |
| §V | Three-Tier Plugin Tooling | ✅ PASS | data-model.md §实体 6 `ToolDto.source` 字段枚举（builtin/mcp/java_bean）与 `tool_invocations.source` 字节级对齐；Tool 抽象仍在 `oryxos-core`，Tool 仍在 `oryxos-tool` |
| §VI | Day-One Audit | ✅ PASS | data-model.md §实体关系图明确"REST 触发写入 4 表"（sessions / tool_invocations / llm_calls / task_executions）；`task_executions.trigger_source="web"` 扩展 008 三选一契约 |
| §VII | Demo-First Delivery | ✅ PASS | quickstart.md §5 分钟启动 + §验收场景 7 覆盖 Demo 四 + Demo 五；与 [CLAUDE.md §11](../../CLAUDE.md) 一致 |

**附加约束复审**：

- 不用 `SecurityManager`：✅ N/A
- 不硬编码 API key：✅ PASS（`InfoDto` 仅暴露 `version` / `javaVersion`，不含 `DEEPSEEK_API_KEY` 等敏感字段）
- 不依赖 `ddl-auto=update`：✅ PASS（data-model.md §"不在本 spec 范围" 显式声明不新增表）
- 不按容器类型区分 Provider：✅ N/A
- 不混淆 Session / LongTerm Memory：✅ PASS（端点 2-5 操作 Session，端点 7 仅返回 Memory 元数据 backend + 大小）
- 不用非 JDK 21 特性：✅ PASS（DTO 全用 Java 21 record）

**Phase 1 后新增契约完整性**：

| 新增契约 | 出处 | 与既有契约一致性 |
| --- | --- | --- |
| `sessions.metadata.source="web"` | data-model.md §实体关系图 | ✅ 扩展 008 契约 [cli/web/scheduler] 三选一，新增第 3 值 |
| `task_executions.trigger_source="web"` | data-model.md §实体关系图修订 | ✅ 与 008 三选一 [cli/web/scheduler] 字节级对齐 |
| `tool_invocations.source` (read via `ToolDto.source`) | data-model.md §实体 6 | ✅ 与 005 [builtin/mcp/java_bean] 三选一对齐 |
| `ErrorResponse` 不含 stack trace | data-model.md §实体 10 | ✅ 与 007 契约对齐 |

**结论**：Phase 1 设计未引入新 violation；所有 7 条原则 + 6 条附加约束仍然 PASS；Complexity Tracking 表留空。

## Project Structure

### Documentation (this feature)

```text
specs/008-agent-web-service/
├── plan.md              # 本文件（/speckit-plan 输出）
├── spec.md              # 用户原始需求 + 4 US + 15 FR + 8 SC
├── research.md          # Phase 0 输出（9 条决策 R-001..R-009）
├── data-model.md        # Phase 1a 输出（10 个 DTO + 5 表复用契约）
├── contracts/
│   └── web-api.md       # Phase 1b 输出（10 端点字节级契约）
├── quickstart.md        # Phase 1c 输出（5 分钟启动 + 7 个验收场景）
└── checklists/
    └── requirements.md  # 规格质量清单（16/16 PASS）
```

### Source Code (repository root)

```text
oryxos-web/                                       # [CLAUDE.md §5 9 模块之一]
├── pom.xml                                        # 新增 springdoc-openapi + actuator 依赖
└── src/
    ├── main/
    │   ├── java/io/oryxos/web/
    │   │   ├── controller/
    │   │   │   ├── AgentsController.java          # 端点 1（POST /api/v1/agents/{name}/invoke）
    │   │   │   ├── SessionsController.java        # 端点 2-5
    │   │   │   ├── ProfilesController.java        # 端点 6
    │   │   │   ├── MemoryController.java          # 端点 7
    │   │   │   ├── ToolsController.java           # 端点 8
    │   │   │   └── SystemController.java          # 端点 9-10
    │   │   ├── dto/                                # 10 个 record DTO（per data-model.md）
    │   │   │   ├── InvokeRequest.java
    │   │   │   ├── InvokeResponse.java
    │   │   │   ├── SessionDto.java
    │   │   │   ├── MessageDto.java
    │   │   │   ├── ProfileDto.java
    │   │   │   ├── ToolDto.java
    │   │   │   ├── MemoryDto.java
    │   │   │   ├── HealthDto.java
    │   │   │   ├── InfoDto.java
    │   │   │   └── ErrorResponse.java
    │   │   └── exception/
    │   │       ├── GlobalExceptionHandler.java    # @ControllerAdvice 统一错误响应
    │   │       ├── AgentNotFoundException.java
    │   │       ├── SessionNotFoundException.java
    │   │       └── AgentTimeoutException.java
    │   └── resources/
    │       └── application-web.yaml               # Web 模块独立配置（可选）
    └── test/
        ├── java/io/oryxos/web/
        │   ├── controller/                          # 6 个 @WebMvcTest IT
        │   │   ├── AgentsControllerIT.java
        │   │   ├── SessionsControllerIT.java
        │   │   ├── ProfilesControllerIT.java
        │   │   ├── MemoryControllerIT.java
        │   │   ├── ToolsControllerIT.java
        │   │   └── SystemControllerIT.java
        │   ├── exception/
        │   │   └── GlobalExceptionHandlerIT.java
        │   ├── contract/
        │   │   └── OpenApiContractIT.java          # 校验 /v3/api-docs.yaml
        │   ├── e2e/
        │   │   └── WebServiceEndToEndIT.java       # @SpringBootTest + TestRestTemplate
        │   └── perf/
        │           └── WebPerformanceBenchmarkIT.java  # health P95 ≤ 50ms

oryxos-boot/                                      # [CLAUDE.md §5 启动模块]
└── pom.xml                                        # 新增 springdoc-openapi + actuator 依赖（与 oryxos-web 共享）
```

### Source Code Reuse（不修改）

| 模块 | 复用点 |
| --- | --- |
| `oryxos-core` | `AgentService.process(Session, String)` —— REST 入口唯一调用路径（per 宪法 §III + research.md R-008） |
| `oryxos-core` | `SessionFactory.create(profileName, null)` + post-set `metadata.source="web"` |
| `oryxos-core` | `ToolRegistry.list()` —— 端点 8 数据源 |
| `oryxos-storage` | 5 个 `Repository` —— sessions / tool_invocations / llm_calls / task_executions |
| `oryxos-memory` | `MemoryService.summary()` —— 端点 7 数据源 |
| `008-agent-scheduler` | `TaskExecutionRecorder.record(session, "web")` —— 手动补跑审计（per data-model.md §实体关系图修订） |

**Structure Decision**: 单 Maven 模块扩展（`oryxos-web`），不引入新模块；与 [CLAUDE.md §5 9 个模块](../../CLAUDE.md) 边界一致；与宪法 §I "Single-Stack Monolith" 一致。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无 violation；本节留空。

---

## 实施阶段预览（per /speckit-tasks 后续）

> **注**：本表"估计任务数"列为 pre-tasks-generation 粗估；实际 [tasks.md](tasks.md) §任务统计给出精确数字（**43 任务**，含 23 个 [P] 并行机会）。粗估与实测偏差源于 TDD 测试任务独立计数（粗估按"Controller × 集成测试"打包，实测每个 IT 类与契约测试独立成行）。

| 阶段 | 内容 | 估计任务数 | 实测（per tasks.md） |
| --- | --- | --- | --- |
| Phase 1: Setup | `oryxos-web/pom.xml` 加依赖 + `oryxos-boot/pom.xml` 加依赖 | 2 | 4 |
| Phase 2: Foundational | 10 个 DTO record + 4 个 Exception 类 + `GlobalExceptionHandler` + 4 个 config/util | 6 | 6 |
| Phase 3: US-1 (P1) | `AgentsController` + 集成测试 + byte-level 反射 + 审计断言 | 4 | 7（3 测试 + 4 实现） |
| Phase 4: US-2 (P2) | `SessionsController` + 集成测试 + 软删除契约 + UUID 校验 | 5 | 9（3 测试 + 6 实现） |
| Phase 5: US-3 (P3) | `ProfilesController` / `MemoryController` / `ToolsController` / `SystemController` + 集成测试 | 8 | 10（4 测试 + 6 实现） |
| Phase 6: Polish | `OpenApiContractIT` + `WebServiceEndToEndIT` + `WebPerformanceBenchmarkIT` + Demo 验证 + 范围说明 | 6 | 7 |
| **合计** | | **31** | **43** |

MVP 范围 = Phase 1 + 2 + 3（US-1 业务系统能调 Agent）—— 17 个实测任务（约 3-5 天实施工作量）；与 spec.md MVP 定义一致。

## 引用

- [spec.md](spec.md) — 用户原始需求 + 4 US + 15 FR + 8 SC + 3 [NEEDS CLARIFICATION]
- [research.md](research.md) — 9 条技术决策 R-001..R-009
- [data-model.md](data-model.md) — 10 个 DTO + 5 表复用契约
- [contracts/web-api.md](contracts/web-api.md) — 10 端点字节级契约
- [quickstart.md](quickstart.md) — 5 分钟启动 + 7 个验收场景
- [checklists/requirements.md](checklists/requirements.md) — 16/16 规格质量 PASS
- [CLAUDE.md](../../CLAUDE.md) §4/§5/§11/§15/§18 — 9 模块 / 11 Demo / 15 REST 端点 / 18 不要做的事
- [.specify/memory/constitution.md](../../.specify/memory/constitution.md) — 7 条原则 + 6 条附加约束
- [008-agent-scheduler/data-model.md](../008-agent-scheduler/data-model.md) — `metadata.source` 三选一契约
- [008-agent-scheduler/contracts/agent-scheduler.md](../008-agent-scheduler/contracts/agent-scheduler.md) — `task_executions.trigger_source` 三选一契约
- [005-tool-system/contracts/tool-executor.md](../005-tool-system/contracts/tool-executor.md) — `tool_invocations.source` 枚举契约
- [006-memory-layer/data-model.md](../006-memory-layer/data-model.md) — `sessions` 表 schema
- [007-sandbox-whitelist/contracts/sandbox.md](../007-sandbox-whitelist/contracts/sandbox.md) — error_message 不含 stack trace 契约
