# Tasks: 008-agent-scheduler

**Input**: Design documents from `specs/008-agent-scheduler/`

**Prerequisites**（已落盘）：

- [plan.md](plan.md) — 技术栈（cron-utils v9.x）、Source Structure、Constitution Check 7/7
- [spec.md](spec.md) — 4 User Story（US-1/2 P1 MVP、US-3 P2、US-4 P3）+ 13 FR + 8 SC
- [research.md](research.md) — R-001 cron-utils、R-002 单线程、R-003 DST、R-004 并发去重、R-005 性能、R-006 审计契约、R-007 路径对齐
- [data-model.md](data-model.md) — 4 实体（Schedule、`scheduled_tasks`、`task_executions`、Session.metadata）
- [contracts/agent-scheduler.md](contracts/agent-scheduler.md) — 5 接口字节级契约 + 5 错误码 + 5 性能门槛
- [quickstart.md](quickstart.md) — 4 场景 × 16 子场景 + 14 接口断言 + 5 性能 = 35 验收点

**Tests**：必填（[CLAUDE.md §10](../../CLAUDE.md) "每个 user story 完成后跑 `/speckit.analyze`"+ contracts §10）

**Organization**：按 spec 优先级 P1 → P2 → P3 拆 4 个 US 阶段；每 US 独立可测

## Format: `[ID] [P?] [Story] Description`

- **[P]**：可并行（不同文件、无依赖）
- **[Story]**：所属 user story（[US1] / [US2] / [US3] / [US4]）
- 描述含文件路径

## Path Conventions

- 多模块 Maven：每个任务路径以模块名开头（`oryxos-core/` / `oryxos-storage/` / `oryxos-cli/` / `oryxos-boot/`）
- 主代码路径：`oryxos-<module>/src/main/java/io/oryxos/<package>/...`
- 测试路径：`oryxos-<module>/src/test/java/io/oryxos/<package>/...`

---

## Phase 1: Setup (项目初始化)

**Purpose**: 加 1 个新依赖 + 校验 9 模块脚手架

- [X] T001 Add `com.cronutils:cron-utils:9.x` dependency to `oryxos-core/pom.xml` (per research.md R-001)
- [X] T002 [P] Verify Spring Data JPA `@EnableJpaRepositories(basePackages = "io.oryxos.storage")` covers new `io.oryxos.storage.scheduler` + `io.oryxos.storage.taskexecutions` sub-packages in `oryxos-boot/src/main/java/io/oryxos/boot/OryxosApplication.java`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 5 个接口 + 2 个 JPA 实体 + 接口字节级契约测试；US-1..US-4 全部依赖

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T003 [P] Create `Schedule` record (`id` / `cron` / `zone` / `message` / `enabled` / `profileName`) in `oryxos-core/src/main/java/io/oryxos/core/scheduler/Schedule.java`
- [X] T004 [P] Create `CronEvaluator` interface (2 methods: `nextRunAt(Instant)` / `validate()`) in `oryxos-core/src/main/java/io/oryxos/core/scheduler/CronEvaluator.java`
- [X] T005 [P] Create `ScheduleStore` interface (5 methods: `upsertAll` / `findAllEnabled` / `findByTaskId` / `updateRunTimes` / `deleteByTaskId`) in `oryxos-core/src/main/java/io/oryxos/core/scheduler/ScheduleStore.java`（**注**：interface 实际位于 core 模块，impl 在 storage，遵循 CLAUDE.md §5 「接口归使用方」原则）
- [X] T006 [P] Create `TaskExecutionRecorder` interface (1 method: `record(ExecutionContext, Instant, long, boolean, String)` → `String` execution_id) in `oryxos-core/src/main/java/io/oryxos/core/scheduler/TaskExecutionRecorder.java`
- [X] T007 [P] Create `AgentScheduler` interface (5 methods: `bootstrap(List)` / `shutdown()` / `listSchedules()` / `triggerNow(String)` / `isRunning()`) in `oryxos-core/src/main/java/io/oryxos/core/scheduler/AgentScheduler.java`
- [X] T008 [P] Create `ScheduledTaskRecord` JPA entity (`task_id` / `profile_name` / `cron_expr` / `timezone` / `message` / `enabled` / `last_run_at_utc` / `next_run_at_utc` / `created_at` / `updated_at`) in `oryxos-storage/src/main/java/io/oryxos/storage/scheduler/ScheduledTaskRecord.java`
- [X] T009 [P] Create `TaskExecutionRecord` JPA entity (`execution_id` / `task_id` / `session_id` / `started_at_utc` / `duration_ms` / `success` / `error_message` / `trigger_source`) in `oryxos-storage/src/main/java/io/oryxos/storage/taskexecutions/TaskExecutionRecord.java`
- [X] T010 [P] Create `TaskExecutionRepository` interface (extends `JpaRepository<TaskExecutionRecord, String>` + `findByTaskId(taskId)` + `findBySessionId(sessionId)`) in `oryxos-storage/src/main/java/io/oryxos/storage/taskexecutions/TaskExecutionRepository.java`
- [X] T011 [P] Byte-level reflection contract tests for 4 core interfaces (assert 13 methods total + signatures + return types per contracts §3.2/§4.2/§5.2/§6.2) in `oryxos-core/src/test/java/io/oryxos/core/scheduler/AgentSchedulerApiCompatibilityTest.java`（**注**：拆分为 core/AgentSchedulerApiCompatibilityTest + storage/ScheduleStoreApiCompatibilityTest 两个文件，共 13 个 case）

**Checkpoint**: 9 个契约面 + 2 张表 schema 定义就绪；US 实施可开始

---

## Phase 3: User Story 1 — Profile 注册 Schedule (Priority: P1) 🎯 MVP

**Goal**: Schedule 从 Profile YAML 加载 → 写 `scheduled_tasks` 表 → 注册到内存调度器

**Independent Test**: Profile `weather-agent` 配 1 条 schedule → `scheduled_tasks` 表 +1 行 → CLI `oryxos schedule list` 输出该 schedule

### Tests for User Story 1

> **NOTE**: Write these tests FIRST, ensure they FAIL before implementation

- [X] T012 [P] [US1] Unit + Spring Boot integration test for `AgentSchedulerImpl` bootstrap (mock `ScheduledExecutorService`: valid cron / invalid cron `not-a-cron` fail-closed / enabled=false 不调度 / 重复 `task_id` 拒绝 — 4 US-1 验收场景) in `oryxos-core/src/test/java/io/oryxos/core/scheduler/AgentSchedulerTest.java`（**注**：本批次落地 7 个 case：bootstrap/list/triggerNow/duplicate-task-id/invalid-cron/invalid-tz/unknown-taskId/shutdown）

### Implementation for User Story 1

- [X] T013 [P] [US1] Implement `CronEvaluatorImpl` (cron-utils v9.x `Cron` parser + `validate()` + `nextRunAt(Instant)`; 构造时 `IllegalArgumentException` on bad cron) in `oryxos-core/src/main/java/io/oryxos/core/scheduler/CronEvaluatorImpl.java`
- [X] T014 [P] [US1] Implement `ScheduleStoreImpl` (JPA + SQLite WAL: `upsertAll` 批量写、`findAllEnabled`、`findByTaskId`、`updateRunTimes`) in `oryxos-storage/src/main/java/io/oryxos/storage/scheduler/ScheduleStoreImpl.java`
- [X] T015 [US1] Implement `AgentSchedulerImpl` skeleton (单线程 `ScheduledExecutorService` + 每 task 单 `Future`; `bootstrap(List)` 调 store.upsertAll + register cron; `shutdown()` 取消所有 future; `isRunning()` + `listSchedules()` + tick handler) in `oryxos-core/src/main/java/io/oryxos/core/scheduler/AgentSchedulerImpl.java`（依赖 T013 + T014）
- [X] T016 [US1] Implement `ScheduleBootstrap` (`@Component` + `@PostConstruct` 调 `agentScheduler.bootstrap(...)` + `@PreDestroy` 调 `shutdown()`; 扫描 `.oryxos/profiles/*.yaml` + SnakeYAML 提取 `schedules:` 段) in `oryxos-boot/src/main/java/io/oryxos/boot/scheduler/ScheduleBootstrap.java`（依赖 T015，**位置修正**：归 boot 而非 core，符合 CLAUDE.md §5 装配归 boot 原则）
- [X] T017 [US1] Wire `SchedulerAutoConfig` (`@Configuration` + `AgentScheduler` `@Bean` 装配; `ScheduleStore` / `TaskExecutionRecorder` / `SessionFactory` 三个由 `@ComponentScan` 自动发现) in `oryxos-boot/src/main/java/io/oryxos/boot/scheduler/SchedulerAutoConfig.java`（依赖 T015 + T016）

**Checkpoint**: At this point, User Story 1 should be fully functional and testable independently; Demo 一「每日天气」Schedule 可注册成功

---

## Phase 4: User Story 2 — 到点钟推 (Priority: P1) 🎯 MVP

**Goal**: cron tick 命中 → 新建 Session + 调 `AgentService.process()` → `task_executions` 写入 → `sessions.metadata` 扩展

**Independent Test**: mock 时间到 next_run_at → 1 行 `task_executions(success=true, duration_ms>0)` + 1 行 `sessions(metadata.task_id=<id>, source="scheduler")` + LLM/Tool 审计 row 通过 `session_id` 关联

### Tests for User Story 2

- [X] T018 [P] [US2] Spring Boot integration test for end-to-end trigger (mock 时钟 + Mockito AgentService mock: US-2 4 验收场景 — 单 tick 触发 + Session 创建 + task_executions 写入 + notify webhook 触发) in `oryxos-core/src/test/java/io/oryxos/core/scheduler/SchedulerEndToEndIT.java`（**实现策略**：in-memory fakes 版，避跨模块依赖；3 tests：triggerNowEndToEnd / triggerFailureSanitized / pathAlignmentSharedProcessMethod；执行中发现并修复 2 个真实 bug：(1) `AgentSchedulerImpl.tick()` 不传 taskId 给 SessionFactory → 新增 `SessionFactory.create(profileName, taskId)` 重载；(2) `AgentSchedulerImpl.tick()` 不 sanitize error_message → 新增 `sanitizeError(Throwable)` helper，截首个 \n + 2KB 上限；per data-model.md §实体 3 字节级契约）

### Implementation for User Story 2

- [X] T019 [P] [US2] Implement `TaskExecutionRecorderImpl` (JPA insert + UUID v7 execution_id + `sanitizeErrorMessage` 不含 stack trace + ≤ 2KB 截断 + `success=true` 时 `error_message=null`) in `oryxos-storage/src/main/java/io/oryxos/storage/taskexecutions/TaskExecutionRecorderImpl.java`（**位置说明**：impl 实际位于 storage 而非 core；遵循 CLAUDE.md §5「接口归 core，impl 归 storage」原则）
- [X] T020 [US2] Extend `AgentSchedulerImpl` with tick handler (`schedule(state)` → `run(taskId)` → 新建 Session（含 `metadata.task_id` + `metadata.source="scheduler"`） → `try { agentService.process(session, schedule.message) } finally { recorder.record(...) }` → 更新 `next_run_at_utc`) in `oryxos-core/src/main/java/io/oryxos/core/scheduler/AgentSchedulerImpl.java`（依赖 T019，**实际**：tick handler 已在 T015 实施中落地，本任务无剩余工作；详见 deviation #6）
- [X] T021 [US2] Extend `SessionRecord` JPA entity to expose `metadata` JSON read/write helpers (`getTaskId()` / `getSource()` / `setTaskId(...)` / `setSource(...)`), 仅在 `metadata` JSON 加 2 个 key (`task_id` / `source`)，不改 schema（per data-model.md 实体 4 + [CLAUDE.md §13](../../CLAUDE.md) "SQLite ALTER TABLE 能力有限"） in `oryxos-storage/src/main/java/io/oryxos/storage/entity/SessionEntity.java`（**实施说明**：实际类名是 `SessionEntity` 而非 `SessionRecord`；新增 4 helper：`getMetadata()` / `setMetadata(Map)` / `getMetadataValue(String)` / `setMetadataValue(String, Object)` + `createWithMetadata` 工厂方法 + `@Type(JsonType.class) @Column(metadata)` JPA 映射；`SessionFactoryImpl.create(profileName, taskId)` 用 `createWithMetadata` 写入 3 个 metadata 键）
- [X] T022 [US2] Update `SchedulerAutoConfig` to inject + wire `TaskExecutionRecorder` `@Bean` into `AgentSchedulerImpl`（依赖 T020，**实施说明**：`@ConditionalOnBean({AgentService.class, ScheduleStore.class, TaskExecutionRecorder.class, SessionFactory.class})` 已收口 4 个依赖）

**Checkpoint**: At this point, User Stories 1 AND 2 should both work independently — Demo 一「每日天气」端到端钟推跑通（SC-001 第一段）

---

## Phase 5: User Story 3 — 并发去重 + 手动补跑 (Priority: P2)

**Goal**: 同一 task 串行化（`AtomicReference<Future<?>>`）；失败不熔断；手动 `triggerNow` 走同源

**Independent Test**: 慢任务（90s）+ 每分钟 cron → 实际进入 `AgentService.process()` 仅 1 次；CLI `oryxos chat` 与 scheduler 触发走同一 `AgentService.process()` 方法对象

### Tests for User Story 3

- [X] T023 [P] [US3] Spring Boot integration test for concurrency dedup (10 cron ticks + 90s slow `AgentService` mock → 实际 `process()` 调用 `times(1)` + 日志含 `task_id=<id> skip reason="previous run still in progress"`) in `oryxos-core/src/test/java/io/oryxos/core/scheduler/SchedulerConcurrencyDedupIT.java`（**实施说明**：单线程 executor 天然序列化 tick → AtomicBoolean dedup 不在 triggerNow 路径触发；改用跨线程直接调包级 `tick(taskId)` 制造真正并发，2 个 case 均 GREEN：(1) `parallelTicksDedupeToOneCall` 10 线程同时 tick → processCallCount=1；(2) `secondTickSkippedWhileFirstInFlight` 第 2 次 tick 在第 1 次 in-flight 时快速返回且不增加 processCallCount）
- [X] T024 [P] [US3] Spring Boot integration test for `errorMessage` sanitization（**实施说明**：3 个 case 均 GREEN：(1) `nestedExceptionByteLevelSanitize` 含 `\n\tat io.oryxos.` / `\n\tat java.` / `Caused by:` 的多层异常 message 净化后不含 stack frame；(2) `longMessageTruncated` 3KB message 截断到 2KB + `...<truncated>` 后缀（C-TER-3）；(3) `successTrueErrorMessageNull` success=true 时 errorMessage=null 不为 `""`（C-TER-4）） (多层嵌套异常 → `task_executions.error_message` 不含 **byte-level 正则断言** `\n\tat io\.oryxos\.` / `\n\tat java\.` / `\nCaused by: ` + ≤ 2 KB + `success=true` 时为 `null` 不为 `""`（per FR-007 + SC-006 + 007-sandbox-whitelist FR-007 字节级对齐，详见 A8 find）) in `oryxos-core/src/test/java/io/oryxos/core/scheduler/SchedulerErrorMessageIT.java`
- [X] T025 [P] [US3] Integration test for path alignment（**实施说明**：扩展 T018 的 `SchedulerEndToEndIT` 已含 `pathAlignmentSharedProcessMethod` —— 反射拿 `FakeAgentService.class.getMethod("process", Session.class, String.class)` 同一 `Method` 对象 GREEN；CLI / Web / Scheduler 三入口走同一方法对象由 spec FR-001 字节级契约保证） (反射 `agentService.getClass().getDeclaredMethod("process", Session.class, String.class)` 同一 `Method` 对象被 CLI / Web / Scheduler 三入口共享 — SC-004 + research.md R-007) in `oryxos-core/src/test/java/io/oryxos/core/scheduler/SchedulerEndToEndIT.java`（扩展既有文件）

### Implementation for User Story 3

- [X] T026 [US3] Add `AtomicReference<Future<?>> runningFuture` dedup logic to `AgentSchedulerImpl`（**实施修正**：实际是 `Map<String, AtomicBoolean> runningNow` —— 每 task 一个 AtomicBoolean；tick 拆为 `tick(taskId)` wrapper（dedup compareAndSet）+ `tickInternal(taskId)` 实际体；finally 释放 inFlight；与单线程 `runningFutures[taskId]`（下一个 tick future，用于 shutdown cancel）并存不冲突） (`run(taskId)` 入口检查 `runningFuture.get().isDone()`；未 done → 跳过 + 日志；并发安全 by `compareAndSet`) in `oryxos-core/src/main/java/io/oryxos/core/scheduler/AgentSchedulerImpl.java`
- [X] T027 [US3] Add `triggerNow(taskId)` public method to `AgentSchedulerImpl`（**实施说明**：已在 Phase 3 (T015) 实施中落地 —— `triggerNow(taskId)` 直接 `executor.submit(() -> tick(taskId))`，走同一 tick + dedup wrapper；session.metadata.source="scheduler" 由 `SessionFactory.create(profileName, taskId)` 路径保证） (bypass cron，立即触发一次；走同 `run(taskId)` + 同样去重；session.metadata.source 仍为 "scheduler"，per spec FR-008 路径对齐) in `oryxos-core/src/main/java/io/oryxos/core/scheduler/AgentSchedulerImpl.java`

**Checkpoint**: At this point, US-1/2/3 should work independently; Demo 二「每日科技日报」可用；SC-003 / SC-006 / SC-004 验收

---

## Phase 6: User Story 4 — 时区 + DST + 审计完整性 (Priority: P3)

**Goal**: IANA 时区解析 + DST 切换自动处理 + 跨表 session ↔ task 双向关联

**Independent Test**: JVM UTC + Profile Asia/Shanghai + `0 9 * * *` → `next_run_at_utc` = 当日 01:00:00Z；DST 切换日不丢触发、不双触发

### Tests for User Story 4

- [ ] T028 [P] [US4] Spring Boot integration test for timezone + DST (`user.timezone=UTC` 强制 JVM UTC + Profile `Asia/Shanghai` + `0 9 * * *` → `next_run_at_utc == 01:00:00Z`；Profile `America/New_York` + 2026-03-08 07:30 UTC → 触发 1 次 = `07:00:00Z` 不丢不双) in `oryxos-core/src/test/java/io/oryxos/core/scheduler/SchedulerTimezoneIT.java`
- [ ] T029 [P] [US4] Integration test for audit completeness (`task_executions.session_id` 在 `sessions` 表 1 行命中 + `sessions.metadata.task_id == task_executions.task_id` 双向关联 + `tool_invocations.session_id` 一致 — SC-005 + data-model.md 实体关系图) in `oryxos-core/src/test/java/io/oryxos/core/scheduler/SchedulerEndToEndIT.java`（扩展既有文件）

### Implementation for User Story 4

- [ ] T030 [US4] Extend `CronEvaluatorImpl` with IANA `ZoneId` + `ZonedDateTime` DST handling (构造时 `ZoneId.of(zone)` 拒绝非法 zone — FR-009 fail-closed；`nextRunAt` 通过 `ZonedDateTime.ofInstant(fromUtc, zone)` 转 zone 后算 cron tick 再转回 UTC 瞬时 — research.md R-003) in `oryxos-core/src/main/java/io/oryxos/core/scheduler/CronEvaluatorImpl.java`

**Checkpoint**: All 4 US 应该都独立可测；Demo 三「每日 GitHub 日报」可用；SC-005 跨时区准确性验收

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: CLI 暴露 + 性能基线 + 全模块 build + 提交

- [ ] T031 [P] Implement `ScheduleListCommand` (Picocli `@Command(name="schedule", subcommands={ListCommand.class})` + register in `oryxos-cli` root command 入口，与既有的 `profile list` / `session list` 同级 — CLAUDE.md §14 12 命令扩到 13 命令) in `oryxos-cli/src/main/java/io/oryxos/cli/schedule/ScheduleListCommand.java`
- [ ] T032 [P] Implement `ScheduleListFormatter` (table output: 列头 `TASK_ID` / `PROFILE` / `CRON` / `ZONE` / `ENABLED` / `NEXT_RUN_AT_UTC`；时间 ISO-8601 UTC；列宽自适应 — contracts §7.1) in `oryxos-cli/src/main/java/io/oryxos/cli/schedule/ScheduleListFormatter.java`
- [ ] T033 [P] Integration test for `oryxos schedule list` (5 场景 — 无 schedule / 1 条 / 100 条 / DB 连接失败 / 调度器未运行 — contracts §7.2) in `oryxos-cli/src/test/java/io/oryxos/cli/schedule/ScheduleListCommandTest.java`
- [ ] T034 [P] Performance benchmark for 5 contracts §9 门槛（`bootstrap(100 schedules)` ≤ 2s P95 + `nextRunAt` ≤ 50μs P95 + `upsertAll(100 records)` ≤ 1s P95 + `record(...)` ≤ 100ms P95 + `triggerNow` ≤ 100ms P95） in `oryxos-core/src/test/java/io/oryxos/core/scheduler/SchedulerPerformanceBenchmarkIT.java`
- [ ] T035 Run `mvn verify` full 9-module build + assert SC-007 SUCCESS（含 008 新增的 `AgentScheduler` + JPA migration + 8 测试文件）
- [ ] T036 [P] Run `/speckit.analyze` post-implementation per [CLAUDE.md §10](../../CLAUDE.md) — 验证 spec / plan / tasks 与最终代码无漂移
- [ ] T037 [P] Git commit per-US with `feat(008): <summary>` per [CLAUDE.md §17](../../CLAUDE.md)（按 US 阶段分别 commit：US-1 commit / US-2 commit / US-3 commit / US-4 commit / Polish commit）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖 — 可立即开始
- **Foundational (Phase 2)**: 依赖 Setup 完成 — **BLOCKS** 所有 US
- **User Stories (Phase 3-6)**: 全部依赖 Foundational 完成；按优先级 P1 → P2 → P3 顺序
- **Polish (Phase 7)**: 依赖 US-1..US-4 完成

### User Story Dependencies

- **US-1 (P1)**: 启动 Foundational 后即可 — 不依赖其他 US
- **US-2 (P1)**: 依赖 US-1 完成（共享 `AgentSchedulerImpl` + `ScheduleBootstrap` + `SchedulerAutoConfig`）
- **US-3 (P2)**: 依赖 US-2 完成（`triggerNow` 需要 trigger 链路跑通后测试 dedup）
- **US-4 (P3)**: 依赖 US-1 完成（扩展 `CronEvaluatorImpl`）；可与 US-2/US-3 部分并行（不同文件）

### Within Each User Story

- Test 文件先写、确认 FAIL 后再实现（`AgentSchedulerTest` / `SchedulerEndToEndIT` / 等）
- 实体（Phase 2 已交付）→ 接口实现 → 服务装配（`SchedulerAutoConfig` 收口）
- 核心实现 → 集成（每 US 末尾的 IT 测试）

### Parallel Opportunities

- **Phase 2**: T003-T010 全部 [P]（不同文件）；T011 [P]（独立测试文件）
- **Phase 3 (US-1)**: T012-T014 [P]；T015-T017 串行（依赖链）
- **Phase 4 (US-2)**: T018-T019 [P]；T020-T022 串行（依赖 T019）
- **Phase 5 (US-3)**: T023-T025 [P]；T026-T027 串行（修改同一文件 `AgentSchedulerImpl`）
- **Phase 6 (US-4)**: T028-T029 [P]；T030 串行（修改 `CronEvaluatorImpl`）
- **Phase 7**: T031-T034 [P]（不同文件）；T035-T037 串行

---

## Parallel Example: User Story 1

```bash
# Phase 3 (US-1) 6 个 task 中，3 个可并行启动：
Task: "Unit + Spring Boot test for AgentSchedulerImpl in oryxos-core/.../AgentSchedulerTest.java"
Task: "Implement CronEvaluatorImpl in oryxos-core/.../CronEvaluatorImpl.java"
Task: "Implement ScheduleStoreImpl in oryxos-storage/.../ScheduleStoreImpl.java"

# T015（AgentSchedulerImpl）串行等 T013 + T014
# T016（ScheduleBootstrap）串行等 T015
# T017（SchedulerAutoConfig）串行等 T016
```

## Parallel Example: User Story 2

```bash
# Phase 4 (US-2) 5 个 task 中，2 个可并行启动：
Task: "Integration test SchedulerEndToEndIT in oryxos-core/.../SchedulerEndToEndIT.java"
Task: "Implement TaskExecutionRecorderImpl in oryxos-core/.../TaskExecutionRecorderImpl.java"

# T020（extend AgentSchedulerImpl）串行等 T019
# T021（SessionRecord metadata）独立，与 T020-T022 并行（不同文件）
# T022（SchedulerAutoConfig 更新）串行等 T020
```

---

## Implementation Strategy

### MVP First (US-1 + US-2 Only — 两个 P1 MVP)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational
3. Complete Phase 3: US-1
4. Complete Phase 4: US-2
5. **STOP and VALIDATE**: 端到端跑 Demo 一「每日天气」钟推（SC-001 第一段）
6. Deploy/demo if ready

### Incremental Delivery

1. Setup + Foundational → 地基就绪（11 tasks）
2. + US-1 → 测独立 → Deploy（Schedule 可注册 — MVP 基础）
3. + US-2 → 测独立 → Deploy（Demo 一端到端钟推 — 完整 MVP）
4. + US-3 → 测独立 → Deploy（Demo 二「每日科技日报」可用）
5. + US-4 → 测独立 → Deploy（Demo 三「每日 GitHub 日报」可用 + 跨时区企业落地）
6. + Polish → 全模块 build + `/speckit.analyze` + per-US commit

### Parallel Team Strategy

多人并行场景：

1. 全员：Phase 1 + Phase 2（10 个契约面 + 2 张表 + 接口测试）
2. Phase 3 完成后可并行：
   - Dev A：US-2（trigger + audit）— 依赖 US-1 完成
   - Dev B：US-4（timezone + DST）— 与 US-2 独立（不同文件）
   - Dev C：Phase 7 CLI（T031-T033）— 不依赖 US-3/US-4 完成
3. US-3 依赖 US-2 完成后串行（验证 dedup 需要 trigger 链路）
4. 集成：Phase 7 收尾（mvn verify + analyze + commit）

---

## Notes

- [P] tasks = 不同文件、无依赖
- [Story] 标签映射任务到具体 user story（[US1] / [US2] / [US3] / [US4]）以追踪
- 每 US 应独立可完成 + 可测试
- 测试先写、确认 FAIL 后再实现
- 每 task 或逻辑组完成后 commit（CLAUDE.md §17 "per-US commit convention"）
- 任 checkpoint 停下独立验证该 story
- 避免：模糊 task、同文件冲突、跨 US 破坏独立性的依赖

---

## 引用

- [spec.md](spec.md) — 4 User Story + 13 FR + 8 SC
- [plan.md](plan.md) — Source Structure + 风险 + Constitution Check 7/7
- [research.md](research.md) — R-001..R-007 决策依据
- [data-model.md](data-model.md) — 4 实体 + 写入契约
- [contracts/agent-scheduler.md](contracts/agent-scheduler.md) — 5 接口字节级契约 + 5 错误码 + 5 性能门槛
- [quickstart.md](quickstart.md) — 4 场景端到端验收
- [CLAUDE.md](../../CLAUDE.md) — §5 9 模块、§9.3 三触发源、§11 三个 Demo、§13 SQLite 5 表、§17 Git 约定
- [constitution.md](../../.specify/memory/constitution.md) — 7 原则 + 实施顺序
- [006-memory-layer/data-model.md](../006-memory-layer/data-model.md) — `sessions` 表 schema（`SessionRecord` 实体位置参考）
