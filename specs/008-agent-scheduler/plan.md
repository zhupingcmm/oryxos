# Implementation Plan: 008-agent-scheduler

**Branch**: `008-agent-scheduler` | **Date**: 2026-07-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/008-agent-scheduler/spec.md`

---

## Summary

把 OryxOS 第三种触发源（钟推 / Scheduler）从 [CLAUDE.md §5](../../CLAUDE.md) 已声明的
`AgentScheduler`（位于 `oryxos-core`）"接口预留"补到"端到端跑通"——Profile YAML 解析
、cron 调度、`task_executions` 审计、并发去重。**零新模块**，**零 ReAct/Tool/Provider
改动**；钟推与 CLI / Web 共用 `AgentService.process(Session, String)` 同一入口
（[CLAUDE.md §9.3](../../CLAUDE.md) 三种触发源统一契约）。三个验收 Demo（[CLAUDE.md §11](../../CLAUDE.md)）
全部依赖本特性；本 plan 落地后 Demo 从"手跑 CLI"升级为"系统钟推"。

**技术方案**（详见 [research.md](research.md)）：

+ Cron 解析：`com.cronutils:cron-utils` v9.x（R-001）
+ 调度器：`ScheduledExecutorService` 单线程 + 每 task 单 `Future`（R-002）
+ DST：JDK 21 `ZonedDateTime` + IANA tzdata 原生支持（R-003）
+ 并发去重：in-process `AtomicReference<Future<?>>` + SQLite 行锁兜底（R-004）
+ 性能基线：100 条 schedule 注册 ≤ 2s（R-005）
+ 审计：`task_executions.error_message` 字节级对齐 007-sandbox-whitelist FR-007（R-006）
+ 路径对齐：Scheduler / CLI / Web 三入口共享 `AgentService.process()` 同一方法对象（R-007）

---

## Technical Context

**Language/Version**: Java 21（[CLAUDE.md §4](../../CLAUDE.md) 技术栈既定）

**Primary Dependencies**（新增 1 个）：

+ `com.cronutils:cron-utils:9.x`（cron 解析 + 时区）
+ 既有：`spring-boot:3.x` / `spring-data-jpa` / `sqlite-jdbc` / `jdk:21`（[CLAUDE.md §4](../../CLAUDE.md)）

**Storage**: SQLite（既有，5 张表已落地）—— 新增实现层在 `scheduled_tasks` / `task_executions`
2 表（[CLAUDE.md §13](../../CLAUDE.md) day-one 已声明）

**Testing**: JUnit 5 + Mockito + Spring Boot Test + WireMock（与既有测试栈一致）

**Target Platform**: Linux server / macOS / Windows（既有跨平台 JDK 21）

**Project Type**: Maven 多模块（9 模块既定，[CLAUDE.md §5](../../CLAUDE.md)）

**Performance Goals**:

+ 100 条 schedule 注册 P95 ≤ 2s（SC-003）
+ 单次 cron 计算 P95 ≤ 50μs（[contracts/agent-scheduler.md §9](contracts/agent-scheduler.md)）
+ 100 条 schedule JPA upsert P95 ≤ 1s（同上）
+ 单次 `task_executions` 写库 P95 ≤ 100ms（同上）
+ 手动补跑触发到 `AgentService.process()` 入参 P95 ≤ 100ms（同上）

**Constraints**:

+ 9 Maven 模块不动（宪法 §I）
+ `AgentService.process(Session, String)` 接口签名不变（CLAUDE.md §9.3）
+ `ReActLoop` / `PromptBuilder` / `ToolExecutor` 3 个核心类零改动（SC-008 + 宪法 §III）
+ `Sandbox` 5 契约面字节级不变（[007-sandbox-whitelist/contracts/sandbox-whitelist.md](../007-sandbox-whitelist/contracts/sandbox-whitelist.md) + 宪法 §VII）
+ `task_executions.error_message` ≤ 2 KB；不含 stack trace（SC-006 + 007 FR-007）

**Scale/Scope**:

+ MVP：1 条 schedule 端到端跑通（每日天气 Demo）
+ 目标：100 条 schedule / Agent × Profile × N（企业规模化）
+ 单实例运行（核心阶段；多实例集群放扩展阶段）

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### §I — Single-Stack Monolith (JDK 21 + Spring Boot 3.x)

**合规**：本特性全部改动在既有 9 个模块内——

+ `AgentScheduler` / `CronEvaluator` / `TaskExecutionRecorder`：**`oryxos-core`**（CLAUDE.md §5 既定归属）
+ `ScheduleStore` / `ScheduledTaskRecord`：**`oryxos-storage`**（与 `SessionRepository` / `ToolInvocationRepository` 同模块）
+ `ScheduleListCli`：**`oryxos-cli`**（与 `profile list` / `agent list` 同模式）
+ 测试：`oryxos-core` / `oryxos-storage` / `oryxos-cli` / `oryxos-boot`（既有测试模块）

✅ **零新模块**。

### §II — Core-Stage Scope Discipline (五大核心能力优先)

**合规**：本特性是第五个能力（Web Service）落地的前提"地基补完"——`AgentScheduler`
已在 [CLAUDE.md §5](../../CLAUDE.md) 声明。`spec.md` §不在范围内 显式排除 7 项扩展阶段项
（Scheduler REST / 多实例集群 / 热加载 / 历史补跑 / 可视化仪表板 / 自定义时区偏移 +
宪法 §II 7 项通用排除）。

✅ **不越界**。

### §III — Self-Implemented ReAct Loop

**合规**：`AgentScheduler` 钟推触发**只**调 `AgentService.process(Session, String)`
（既有入口）；`ReActLoop` / `PromptBuilder` / `ToolExecutor` 3 个核心类**零改动**
（[spec.md SC-008](spec.md) `git diff` 断言）。

✅ **不破坏 ReAct 自实现**。

### §IV — Spring AI Used at Half-Strength (禁用自动 tool 执行)

**合规**：本特性不引入 Spring AI 任何抽象；调度器是 OryxOS 自实现的 `ScheduledExecutorService`
包装，与 Spring AI `@Tool` 自动执行链路**完全隔离**。

✅ **不与 Spring AI 耦合**。

### §V — Three-Tier Plugin Tooling

**合规**：不动 Tool 模块归属；调度器走 `AgentService.process()` 时 Tool 调用走既有
`ToolExecutor` 路径（既有的 005-tool-system 契约）。

✅ **Tool 边界不变**。

### §VI — SQLite + MEMORY.md with Day-One Audit Persistence

**合规**：

+ `scheduled_tasks` / `task_executions` 2 表是 [CLAUDE.md §13](../../CLAUDE.md) day-one 既有
  声明；DDL 不动（避免 [CLAUDE.md §18](../../CLAUDE.md) "不要依赖 `ddl-auto=update`" 风险）
+ `task_executions.error_message` 不含 stack trace，字节级对齐 007-sandbox-whitelist FR-007
+ 调度审计 day-one 写库（spec FR-005）

✅ **审计 day-one 落地**。

### §VII — Demo-First Delivery (跑通优先于完美)

**合规**：

+ 三个 Demo（每日天气 / 每日科技日报 / 每日 GitHub 日报）钟推跑通 = SC-001
+ [quickstart.md S2.2](quickstart.md) 显式列出三个 Demo 钟推验收
+ fail-closed 默认（FR-009/011/012/013）

✅ **三个 Demo 钟推验收**。

### Additional Constraints ([CLAUDE.md §18](../../CLAUDE.md))

| 约束 | 验证 |
| --- | --- |
| ❌ SecurityManager | ✅ 不引入；调度器用 JDK 21 `ScheduledExecutorService` |
| ❌ Profile YAML 硬编码 API key | ✅ `${ENV_VAR}` 占位（既有约定） |
| ❌ `ddl-auto=update` 演进 | ✅ 不动 DDL；只补实现层 |
| ❌ ChatModel 容器类型扫描 | ✅ 不动 Provider 抽象 |
| ❌ Session 与 long-term Memory 合并 | ✅ 不动 MemoryService |
| ❌ 非 JDK 21 特性 | ✅ 用 JDK 21 records / sealed / virtual threads（核心阶段 platform threads；扩展阶段可切 virtual threads） |

### Implementation Order ([constitution.md](../../.specify/memory/constitution.md) §6)

本特性是 US-1~US-4 之外的"补完"——**不是**新 US；按依赖顺序属于 [CLAUDE.md §10](../../CLAUDE.md)
"US-1 → US-2 → {US-3 ∥ US-4} → US-5" 完成后的**地基补完**。建议**在 US-5（Web Service）之前完成**，
否则三个 Demo 无法钟推。

---

## Project Structure

### Documentation (this feature)

```text
specs/008-agent-scheduler/
├── spec.md              # /speckit-specify 输出（已落盘）
├── plan.md              # 本文（/speckit-plan 输出）
├── research.md          # Phase 0 输出
├── data-model.md        # Phase 1 输出
├── quickstart.md        # Phase 1 输出
├── contracts/
│   └── agent-scheduler.md  # Phase 1 输出
├── checklists/
│   └── requirements.md  # /speckit-specify 输出（16/16 PASS）
└── tasks.md             # /speckit-tasks 输出（next stage — 不在本 plan 创建）
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxos/core/scheduler/
├── AgentScheduler.java              # interface（contracts §3）
├── AgentSchedulerImpl.java          # 实现（ScheduledExecutorService 单线程）
├── CronEvaluator.java               # interface（contracts §5）
├── CronEvaluatorImpl.java           # 实现（cron-utils + ZoneId）
├── TaskExecutionRecorder.java       # interface（contracts §6）
├── TaskExecutionRecorderImpl.java   # 实现（JPA insert）
├── Schedule.java                    # 启动期临时对象（Profile YAML 解析结果）
└── ScheduleBootstrap.java           # @Component 启动钩子（@PostConstruct 调 bootstrap）

oryxos-core/src/test/java/io/oryxos/core/scheduler/
├── AgentSchedulerTest.java          # 单元测试（mock ScheduleExecutorService）
├── AgentSchedulerApiCompatibilityTest.java  # 接口字节级断言
├── SchedulerEndToEndIT.java         # S2 集成测试
├── SchedulerConcurrencyDedupIT.java # S3 并发去重
├── SchedulerTimezoneIT.java         # S4 时区 + DST
├── SchedulerPerformanceBenchmarkIT.java  # SC-003 + contracts §9
└── SchedulerErrorMessageIT.java     # S3.3 errorMessage 无 stack trace

oryxos-storage/src/main/java/io/oryxos/storage/scheduler/
├── ScheduleStore.java               # interface（contracts §4）
├── ScheduleStoreImpl.java           # 实现（JPA + SQLite WAL）
└── ScheduledTaskRecord.java         # JPA @Entity（scheduled_tasks 表）

oryxos-storage/src/main/java/io/oryxos/storage/taskexecutions/
├── TaskExecutionRecord.java         # JPA @Entity（task_executions 表）
└── TaskExecutionRepository.java     # Spring Data JPA repository

oryxos-cli/src/main/java/io/oryxos/cli/schedule/
├── ScheduleListCommand.java         # Picocli command（contracts §7）
└── ScheduleListFormatter.java       # 输出格式（table）

oryxos-boot/src/main/java/io/oryxos/boot/scheduler/
└── SchedulerAutoConfig.java         # @Configuration（把 5 个接口 @Bean 拼装）

oryxos-core/src/main/resources/META-INF/spring.factories
                                       # ScheduleBootstrap 的 @ComponentScan 自动发现
```

**Structure Decision**: 多文件小类，每个 public interface 一个文件 + 实现一个文件 +
测试一个文件；这是 OryxOS 既有 005-tool-system / 007-sandbox-whitelist 落地模式
（[CLAUDE.md §5 边界澄清](../../CLAUDE.md)）。不引入新模块；不拆 `scheduler-tools` /
`scheduler-mvc` 等子模块——Tool 相关代码全归 `oryxos-tool` 的同等原则应用到调度器
（Scheduler 相关代码归 `oryxos-core` + `oryxos-storage` + `oryxos-cli` + `oryxos-boot` 4 个
既有模块）。

---

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无 Constitution 违规；此节留空。

---

## Phase 1 后重新评估 Constitution Check

| 原则 | 评估结果 |
| --- | --- |
| §I | ✅ 实施分布在 4 个既有模块，零新模块 |
| §II | ✅ spec.md §不在范围内 7 项显式排除 |
| §III | ✅ 调度器入口为 `AgentService.process()`，ReAct 3 个核心类零改动（SC-008） |
| §IV | ✅ 零 Spring AI 依赖 |
| §V | ✅ Tool 边界不变 |
| §VI | ✅ `scheduled_tasks` / `task_executions` 写库 day-one（FR-005）；errorMessage 字节级对齐 007 契约 |
| §VII | ✅ 三个 Demo 钟推验收（SC-001） |

**Constitution Check 7/7 通过**，可进入 `/speckit-tasks`。

---

## 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
| --- | --- | --- | --- |
| cron-utils 库依赖冲突 | LOW | MED | 锁定 v9.x；mvn dependency:tree 验证零传递冲突 |
| SQLite WAL 模式下并发写 | LOW | LOW | 启用 `journal_mode=WAL`；测试覆盖并发写场景 |
| AgentService.process 抛异常导致 task_executions 写库失败 | MED | HIGH | 用 `try { ... } finally { recorder.record(...) }` 包裹；recorder 异常**不**冒泡（避免二次失败） |
| Schedule 注册失败影响整个启动 | MED | HIGH | 每条 schedule 单独 try/catch；一条失败不阻塞其他 schedule |
| 时区 DST 边界 case | LOW | MED | 用 `ZoneId.of("America/New_York")` + cron-utils 测试覆盖 2026-03-08 切换日 |
| 性能：100 条 schedule 启动超时 | LOW | LOW | 单线程 `ScheduledExecutorService` 实测 ≤ 2s；预留扩展阶段切 4 线程 |
| `cron-utils` 兼容 JDK 21 | LOW | MED | 验证 v9.x release notes + 在 JDK 21 下跑通 mvn test |

---

## 引用

+ [spec.md](spec.md) — 13 FR + 8 SC + 4 User Story
+ [research.md](research.md) — R-001 cron-utils / R-002 单线程 / R-003 DST / R-004 并发去重 / R-005 性能 / R-006 审计契约 / R-007 路径对齐
+ [data-model.md](data-model.md) — 4 实体 + 关系图 + 写入契约
+ [contracts/agent-scheduler.md](contracts/agent-scheduler.md) — 5 接口面字节级契约
+ [quickstart.md](quickstart.md) — 4 场景 16 子场景端到端验收
+ [checklists/requirements.md](checklists/requirements.md) — 16/16 规格质量 PASS
+ [.specify/memory/constitution.md](../../.specify/memory/constitution.md) — 7 原则
+ [CLAUDE.md](../../CLAUDE.md) — §5 9 模块 + §9.3 三触发源统一 + §11 三个 Demo + §13 SQLite 5 表 + §16 Profile YAML + §18 不要做的事
+ [005-tool-system/contracts/tool-executor.md](../005-tool-system/contracts/tool-executor.md) — `tool_invocations` 契约
+ [006-memory-layer/contracts/memory-service.md](../006-memory-layer/contracts/memory-service.md) — `sessions` 表契约
+ [007-sandbox-whitelist/contracts/sandbox-whitelist.md](../007-sandbox-whitelist/contracts/sandbox-whitelist.md) — errorMessage 字节级对齐
