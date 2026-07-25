---
description: "US-2 ReAct 循环实现的 Task 清单"
---

# Tasks: ReAct 循环（US-2）

**输入**：来自 `/specs/002-react-loop/` 的设计文档 — [spec.md](spec.md)、[plan.md](plan.md)、[research.md](research.md)、[data-model.md](data-model.md)、[contracts/](contracts/)、[quickstart.md](quickstart.md)
**前置条件**：plan.md ✓、spec.md ✓、research.md ✓、data-model.md ✓、contracts/ ✓、quickstart.md ✓、constitution.md ✓
**测试**：US-2 **必需** —— spec SC-001..SC-007 + NFR-001..NFR-003 要求产出单元 + 集成 + 端到端测试制品。
**组织方式**：US-2 内部按三个优先级切片（P1 / P2 / P3）+ 一个负责接口下沉（R-1）与共享类型的基础阶段 + 一个负责组装 `AgentService` 的集成阶段。P1 即 MVP。

## 格式：`[ID] [P?] [Story] 描述`

- **[P]**：可并行（不同文件、无依赖）
- **[Story]**：所属优先级切片 — `[US-2/P1]`（纯 Reason）、`[US-2/P2]`（单 Tool）、`[US-2/P3]`（多 Tool 串联）、`[US-2/AG]`（AgentService 集成 — 跨切片）
- 文件路径使用绝对、仓库相对路径（`d:/code/java/oryxos/...` 缩写为 `oryxos-*/src/main/java/...`）。
- Story 标签用于与伞形 US-2 交付项名称消歧。

## 路径约定

多模块 Maven 布局（Constitution §I：恰好 9 个模块）。任务落点：

- `oryxos-core/src/main/java/io/oryxos/core/*.java` — `ReActLoop`、接口/record、ToolExecutor 接口、ProfileContext
- `oryxos-core/src/test/java/io/oryxos/core/*Test.java` + `*IT.java` — 单元 + Spring Boot 集成测试
- `oryxos-storage/src/main/java/io/oryxos/storage/{entity,repository}/*.java` — JPA 实体 / Repository
- `oryxos-storage/src/main/resources/db/migration/V2__*.sql`（可选，US-2 内手动 SQL；Flyway/Liquibase 留扩展阶段）
- `oryxos-provider/src/main/java/io/oryxos/provider/*.java` — 仅针对 R-1 接口下沉做 import 路径调整

---

## 阶段 1：环境准备（共享基础设施）

**目的**：在 US-2 任何代码落地前确认工作区 + 分支 + Maven 模块状态干净。验证 US-1 已完成的地基端到端可编译（这是回归基线 — 参见 [plan.md](plan.md) §"Risk & Mitigation"）。

- [ ] T001 确认 `git branch` 是 `002-react-loop` 且工作树干净；若否，`git checkout 002-react-loop && git pull`
- [ ] T002 执行 `mvn -pl oryxos-core,oryxos-storage,oryxos-provider,oryxos-boot -am clean compile` 并把构建输出保存到 `specs/002-react-loop/evidence/T002-baseline-compile.log`；预期 BUILD SUCCESS，除预先存在的 Spring AI deprecation 提示外零警告
- [ ] T003 [P] 执行 `mvn -pl oryxos-provider test` 并把输出保存到 `evidence/T003-baseline-tests.log`；预期 35/35 通过（US-1 基线在 US-2 期间必须始终保持绿）
- [ ] T004 [P] 验证 `oryxos-storage/pom.xml` 已声明 `spring-boot-starter-data-jpa` 与 `hypersistence-utils-hibernate-63`（US-1 为 `LlmCallRecord` 引入）；若缺失则补齐并重跑 T002

---

## 阶段 2：基础（阻塞性前置）

**目的**：在所有 US-2 子故事启动前，必须完成接口下沉（R-1）、新的核心数据类型、以及新的存储实体。**关键** —— 每个后续阶段都依赖此阶段完成。

- [x] T005 将 `ProviderService` 接口从 `oryxos-provider/src/main/java/io/oryxos/provider/ProviderService.java` 移至 `oryxos-core/src/main/java/io/oryxos/core/ProviderService.java`；按 [contracts/ProviderService.md](contracts/ProviderService.md) §1 + §2 更新 package 声明与 javadoc（每条 C-PS-1..C-PS-7 以 `@implNote` 块注释形式引用）
- [x] T006 [P] 同法移动 `LlmRequest`、`LlmResponse`、`Provider` 三个 record（`oryxos-provider` 中删除，`oryxos-core` 中重建）；保留完全一致的字段签名，US-1 的 `LlmCallRecord` 反序列化依赖它们
- [x] T007 [P] 更新 `DefaultProviderService`（位于 `oryxos-provider`）改为 `implements io.oryxos.core.ProviderService`；仅修改 import + `implements` 子句 —— 不改逻辑
- [x] T008 [P] 在 `oryxos-provider` 全量更新 US-1 的 import（35 个测试 + 约 10 个生产类）：把 `io.oryxos.provider.{ProviderService,LlmRequest,LlmResponse,Provider}` 替换为 `io.oryxos.core.{...}`；扫完后跑 `mvn -pl oryxos-provider test`，必须维持 35/35 全绿
- [x] T009 [P] 在 `oryxos-core/src/main/java/io/oryxos/core/Message.java` 创建 `Message` record，字段按 [data-model.md §3.1](data-model.md) + 4 个静态工厂方法（`user`/`assistantText`/`assistantToolCalls`/`toolResult`）+ 一个 `Role` 枚举；compact constructor 校验角色专属不变量
- [x] T010 [P] 在 `oryxos-core/src/main/java/io/oryxos/core/ToolCall.java` 创建 `ToolCall` record，字段 `{id, name, arguments}`；构造函数接受 `null` 的 arguments（视作空 map）
- [x] T011 [P] 在 `oryxos-core/src/main/java/io/oryxos/core/ToolResult.java` 创建 `ToolResult` record，字段 `{success, payload, errorMessage}` + 静态 `ok(Map)` / `error(String)` 工厂；compact constructor 拒绝 `success=true && errorMessage != null`
- [x] T012 [P] 在 `oryxos-core/src/main/java/io/oryxos/core/LoopResult.java` 创建 `LoopResult` record，按 [contracts/LoopResult.md](contracts/LoopResult.md) §1 + §2（compact constructor 强制 C-LR-1 / C-LR-2 / C-LR-7；注意 C-LR-2 允许 `iter=0` 用于 `MAX_ITERATIONS==0` 边界路径）
- [x] T013 [P] 在 `oryxos-core/src/main/java/io/oryxos/core/Session.java` 创建 `Session` 接口，按 [data-model.md §3.2.1](data-model.md)（id、profileName、messages、appendMessage、createdAt、updatedAt）
- [x] T014 [P] 在 `oryxos-core/src/main/java/io/oryxos/core/OryxTool.java` 创建 `OryxTool` 占位接口 —— 最小签名 `{String name(); ToolResult execute(Map<String,Object>);}`；US-2 不提供实现（按 R-2；归 US-4）
- [x] T015 在 `oryxos-core/src/main/java/io/oryxos/core/ProfileContext.java` 创建 `ProfileContext` final class，按 [contracts/ProfileContext.md](contracts/ProfileContext.md) §1 —— 包括嵌套 `Snapshot` record + thread-local + `set`/`current`/`clear` 静态方法；`set` 在重复设置时抛 `IllegalStateException`（C-PC-1）
- [x] T016 [P] 在 `oryxos-core/src/main/java/io/oryxos/core/Profile.java` 创建 `Profile` record，按 [data-model.md §3.3](data-model.md)，含嵌套 `Settings` record + `extra: Map<String,Object>` 透传；compact constructor 用 `^[a-z][a-z0-9-]{0,63}$` 模式校验 `name`
- [x] T017 在 `oryxos-storage/src/main/java/io/oryxos/storage/entity/SessionEntity.java` 创建 `SessionEntity` JPA 实体，按 [data-model.md §3.2.2](data-model.md) —— 表 `sessions`，`messages` 通过 `JsonType` 序列化，`appendMessage` 加 `@Transactional`，`create(UUID, String)` 工厂方法
- [x] T018 [P] 在 `oryxos-storage/src/main/java/io/oryxos/storage/repository/SessionRepository.java` 创建 `SessionRepository` 接口，继承 `JpaRepository<SessionEntity, UUID>`，自定义查找方法 `findByProfileName(String)` 与 `findByUpdatedAtAfter(Instant)`
- [x] T019 在 `oryxos-storage/src/main/java/io/oryxos/storage/entity/ToolInvocationRecord.java` 创建 `ToolInvocationRecord` JPA 实体，按 [data-model.md §3.9](data-model.md) —— 表 `tool_invocations`，所有 `@Check` 约束，含 `session_iteration` 列用于跨表 join
- [x] T020 [P] 在 `oryxos-storage/src/main/java/io/oryxos/storage/repository/ToolInvocationRepository.java` 创建 `ToolInvocationRepository` 接口，含 `countBySessionId(UUID)` 与 `findBySessionIdOrderByStartedAt(UUID)`
- [x] T021 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/testing/InMemorySession.java` 创建 `InMemorySession` 测试辅助 —— 实现 `Session`，把消息存在 `ArrayList<Message>` 内，**不**用于生产
- [x] T022 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/testing/FakeProviderService.java` 创建 `FakeProviderService` 测试辅助 —— 实现 `io.oryxos.core.ProviderService`，持有预排好序的 `LlmResponse` 队列（按顺序弹出；空队列时抛 `IllegalStateException("test stub empty")` 以暴露测试中耗尽）
- [x] T023 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/testing/FakeToolExecutor.java` 创建 `FakeToolExecutor` 测试辅助 —— 实现 `io.oryxos.core.ToolExecutor`，通过 `Map<String, ToolResult>` 查表，同时按调用捕获 `(toolName, arguments, profile)` 用于断言
- [x] T024 在 `oryxos-core/src/test/java/io/oryxos/core/ProfileContextTest.java` 编写 `ProfileContextTest`，覆盖：`setAndClear`、`doubleSetThrows`（I-06 / C-PC-1）、`isolatedAcrossThreads`（验证两条线程各自看到独立状态）、`clearWithoutSetIsNoop`；预期 4 个测试全绿
- [x] T025 迁移完成后执行 `mvn -pl oryxos-core,oryxos-storage,oryxos-provider -am clean compile`；预期 BUILD SUCCESS，US-1 测试仍为 35/35（T003 基线维持）；把日志保存到 `evidence/T025-foundation-compile.log`

**检查点**：基础就绪 —— `ReActLoop` 现在可以基于稳定接口编写。子故事 P1/P2/P3 可启动（按优先级顺序串行，或在严格纪律下并行 —— 因为三者各自写到 `ReActLoop` 不同方法级槽位）。

---

## 阶段 3：[US-2/P1] 纯 Reason 路径（优先级：P1）🎯 MVP

**目标**：实现最小可用循环 —— 组装 prompt、调一次 LLM、观察无 tool_call、返回文本。不触达 Tool 层。

**独立测试**：`ReActLoopPureReasonTest` 通过下列断言（按 spec 用户故事 1 的验收场景）：

- 给定 `Profile{tools=[]}` 与一条用户消息，循环返回 `LoopResult(iter=1, terminatedAtMax=false)`
- `LlmCallRecord` 行数恰好增长 1
- `ToolInvocationRecord` 行数不变（零 tool 调用）
- Session 的 messages 长度恰好增长 2：`user → assistant(text)`
- Bootstrap 文件（AGENT.md、SOUL.md、USER.md）按声明顺序出现在 system prompt 中
- 当前本地日期/时间行追加到 system prompt 末尾（FR-005）

### [US-2/P1] 测试（先写，必须在实现前失败）

- [x] T026 [P] [US-2/P1] 在 `oryxos-core/src/test/java/io/oryxos/core/ReActLoopPureReasonTest.java` 编写 `ReActLoopPureReasonTest` 骨架 —— 5 个 `@Test` 方法覆盖 [spec.md](spec.md) 用户故事 1 的验收场景 1、2、3。预期此刻 5 个失败 / 0 个通过；把失败版本提交到 `evidence/T026-failing-tests.log`
- [x] T027 [P] [US-2/P1] 在 `oryxos-core/src/test/java/io/oryxos/core/MessageTest.java` 编写 `MessageTest` —— [data-model.md §3.1](data-model.md) 中 compact constructor 的不变量；预期 4 个测试全部**立刻**绿（T009 record 的测试也包含在这一步）

### [US-2/P1] 实现

- [x] T028 [P] [US-2/P1] 在 `oryxos-core/src/main/java/io/oryxos/core/Prompt.java` 创建 `Prompt` record，按 [data-model.md §3.6](data-model.md) —— 字段 `{systemBlocks, memoryBlocks, historyBlocks, toolSchemas}`，全部为 `List<Map<String,Object>>`；包含 `flatten()` 辅助方法按 spec FR-004 顺序拼接四个列表
- [x] T029 [P] [US-2/P1] 在 `oryxos-core/src/main/java/io/oryxos/core/ToolExecutor.java` 创建 `ToolExecutor` 接口，按 [contracts/ToolExecutor.md](contracts/ToolExecutor.md) §1 —— `invoke(String, Map<String,Object>, Profile) -> ToolResult`
- [x] T030 [P] [US-2/P1] 在 `oryxos-core/src/main/java/io/oryxos/core/DefaultToolExecutor.java` 创建 `DefaultToolExecutor` 桩 —— US-2 范围内实现 `ToolExecutor`：当 `toolName ∉ profile.tools()` 时返回 `ToolResult.error("tool not in profile: <name>")`（P1 测试中恒成立，因 profile 的 `tools` 为空）；当 tool 允许时抛 `UnsupportedOperationException("Default stub — US-4 will implement")`（满足 C-TE-2 + C-TE-7 的审计写入责任，P1 测试使用 `FakeToolExecutor` 完全跳过审计写入步骤）
- [x] T031 [P] [US-2/P1] 在 `oryxos-core/src/main/java/io/oryxos/core/MemoryInjector.java` 创建桩 `MemoryInjector` 接口，单一方法 `List<Message> inject(Profile, Session);` —— 包内 `NoopMemoryInjector` 在 US-2 返回空列表；US-3 提供真实实现
- [x] T032 [P] [US-2/P1] 在 `oryxos-core/src/main/java/io/oryxos/core/ToolSchemaProvider.java` 创建桩 `ToolSchemaProvider` 接口，单一方法 `List<Map<String,Object>> schemasFor(Profile);` —— 默认实现对 P1 返回 `List.of()`（不需要 tool schema）
- [x] T033 [US-2/P1] 实现 `PromptBuilder`（位于 `oryxos-core/src/main/java/io/oryxos/core/PromptBuilder.java`） —— 按 FR-004 的四段式组装：(1) AGENT.md 内容 + Bootstrap 文件 + 本地日期时间行；(2) `memoryInjector.inject(...)`；(3) 最近 N 条历史消息（按 `settings.maxHistoryTurns` 截断 `Session.messages()`）；(4) `toolSchemaProvider.schemasFor(...)`。构造函数注入上述两个桩接口。（无 AGENT.md 回退：使用 `""` 保持测试确定性。）
- [x] T034 [US-2/P1] 在 `oryxos-core/src/main/java/io/oryxos/core/ReActLoop.java` 实现 `ReActLoop` 骨架 —— 支持 P1 的最小形态：单次迭代、不处理 tool、返回 `LoopResult(r.text(), 1, false, profile.name(), session.id())`。T040（P2）与 T052（P3）会扩展它。构造函数：`(ProviderService, PromptBuilder, ToolExecutor)`。添加 `@Component` 注解供 Spring 拾取。（注意：P1 阶段 `DefaultToolExecutor` 尚未接入 wiring；测试用 `FakeToolExecutor`，wiring 留到 T041。）
- [x] T035 [US-2/P1] 给 `ReActLoop` 加结构化日志钩子：按 FR-019 + FR-020 输出 `react.iteration session_id={id} iteration=1/{max} tool_calls=0` 与 `react.completed session_id={id} iterations=1 duration_ms=N final_tool_call=false`；使用 SLF4J `Logger` 在 INFO 级别配合 `{}` 占位符（**禁止**字符串拼接）
- [x] T036 [US-2/P1] 运行 `ReActLoopPureReasonTest` 与 `MessageTest`；预期所有 9 个测试绿。把结果保存到 `evidence/T036-P1-green.log`
- [x] T037 [US-2/P1] `git add` 仅阶段 3 中创建/修改的文件（**不要**包含 `tasks.md`）；提交信息 `feat(core): implement US-2/P1 Pure Reason Path (single LLM, no tools)`；把 `git log --oneline -1` 保存到 `evidence/T037-P1-commit.txt`

**检查点**：`ReActLoop` 在最简场景下可运行。P1 demo（quickstart §1）可走通。

---

## 阶段 4：[US-2/P2] 单次 Reason-Act-Observe 循环（优先级：P2）

**目标**：扩展 `ReActLoop` 以把 `Tool_call` 派发给 `ToolExecutor` 并把结果回喂；循环至多迭代 `MAX_ITERATIONS` 次。

**独立测试**：`ReActLoopToolChainTest` 覆盖 spec US2 验收场景：

- profile 含 `tools=[http_get]` + 用户消息 → 2 次 LLM 调用 + 1 条 Tool 审计行 + 4 条 Session 消息
- Tool 失败 → 1 次 LLM 调用 + 1 条失败 Tool 审计行 + 4 条消息 + 循环继续
- Tool 名不在 profile 中 → 合成 `ToolResult.error("tool not in profile: ...")`、写入审计行、循环继续

### [US-2/P2] 测试（先写，必须在实现前失败）

- [x] T038 [P] [US-2/P2] 在 `oryxos-core/src/test/java/io/oryxos/core/ReActLoopToolChainTest.java` 编写 `ReActLoopToolChainTest` 骨架 —— 6 个 `@Test` 方法覆盖 US2 全部 3 个验收场景（每个场景分成功路径 + 失败路径 → 共约 6 个）。预期初始全 FAIL。
- [x] T039 [P] [US-2/P2] 在 `oryxos-core/src/test/java/io/oryxos/core/DefaultToolExecutorTest.java` 编写 `DefaultToolExecutorTest` —— 至少包含：`refusedToolReturnsError`（C-TE-1）+ `allowedToolThrowsUnsupported`（US-2 桩行为）。测试必须初始失败 —— 因为 `DefaultToolExecutor` 存在但尚未写审计行，那是 T044 的职责。

### [US-2/P2] 实现

- [x] T040 [US-2/P2] 扩展 `ReActLoop.run(...)` 以支持 tool 派发：每次 LLM 响应后，若 `r.toolCalls()` 非空，遍历它们，调用 `toolExecutor.invoke(tc.name(), tc.arguments(), profile)`，追加 `Message.toolResult(...)`；迭代计数器每 LLM 调用自增 1（见 FR-013）。更新返回逻辑处理路径 (a) —— 无 tool call → 按 FR-013 返回 `LoopResult`。
- [x] T041 [US-2/P2] 把 `DefaultToolExecutor` 接入依赖注入容器：在 `oryxos-core/src/main/java/io/oryxos/core/config/ToolExecutorConfig.java` 创建 `ToolExecutorConfig`，暴露 `@Bean ToolExecutor toolExecutor(ToolInvocationRepository, MemoryInjector)`（非测试环境的默认 wiring）。添加 `@Bean @Primary` 给 `FakeToolExecutor`，scope 限定 `application-e2e-test` profile（Spring `@Profile("test")`）。
- [x] T042 [US-2/P2] 更新 `DefaultToolExecutor`（T030 桩）以在两条路径上都写 `ToolInvocationRecord` 行：拒绝路径（写 `success=false`、`error_message="tool not in profile: <name>"`）与允许路径（先写行，再在 invoke 时抛 `UnsupportedOperationException` —— 保留桩语义同时满足 C-TE-2 的 audit-on-write）。从 `ProfileContext.current()` 捕获 `ToolInvocationContext` 以填充 `session_id` + `session_iteration`（C-TE-3）。
- [x] T043 [US-2/P2] 给 `ReActLoop` 加 MAX_ITERATIONS 终止逻辑：当 `currentIteration.get() >= profile.settings().maxIterations()` 且上一次 LLM 响应是 tool_call 时，返回 `LoopResult(lastText, currentIteration.get(), true, ...)`。边界情况（MAX_ITERATIONS=0）—— 见 T047。
- [x] T044 [US-2/P2] 按边界情况 4 加入 fail-fast 空响应处理：若 LLM 响应 `text == null` 且 `toolCalls.isEmpty()`，返回 `LoopResult("model returned empty response", iter, false, ...)` 以避免无限循环。
- [x] T045 [US-2/P2] 运行 `ReActLoopToolChainTest` + `DefaultToolExecutorTest`；预期 8 个测试全绿。把日志保存到 `evidence/T045-P2-green.log`
- [x] T046 [US-2/P2] 用桩化 `http_get` Tool（`FakeToolExecutor` 返回固定天气）跑 quickstart §2 每日天气 demo；针对 H2 + `tool_invocations` 表验证 SC-001 + SC-004。把证据保存到 `evidence/T046-P2-quickstart.log`
- [x] T047 [US-2/P2] 加入边界单元：`ReActLoopMaxIterZeroTest`（边界 5）+ `ReActLoopEmptyResponseTest`（边界 4）；预期 2 个测试绿
- [x] T048 [US-2/P2] `git commit` 信息 `feat(core): implement US-2/P2 single Reason-Act-Observe cycle (tool dispatch + audit)`。把 commit hash 保存到 `evidence/T048-P2-commit.txt`

**检查点**：P2 demo（quickstart §2 每日天气）配合桩 Tool 可端到端跑通。

---

## 阶段 5：[US-2/P3] 多迭代 Tool 链（优先级：P3）

**目标**：循环多次迭代 Reason → Act → Observe；并发隔离；SPEC-003 20 线程测试；SPEC-004 100% 审计覆盖强制。

**独立测试**：

- `ReActLoopTerminationTest` —— mock LLM 不断吐 `tool_call`；循环恰好在 `MAX_ITERATIONS` 终止（SC-002）
- `ReActLoopMultiToolTest` —— LLM 吐 2 个顺序的 tool_call；循环以 K+1 次 LLM 调用 + K 个 Tool 审计行 + 消息 `[user, assistant(tool_a), tool_a, assistant(tool_b), tool_b, assistant(text)]` 完成
- `ReActLoopConcurrencyTest` —— 在同一 ApplicationContext 上 20 个并发 `process()` 调用；零消息串扰（SC-003）；`llm_calls` + `tool_invocations` 行通过 `session_id` 正确归属

### [US-2/P3] 测试（先写，必须在实现前失败）

- [ ] T049 [P] [US-2/P3] 在 `oryxos-core/src/test/java/io/oryxos/core/ReActLoopTerminationTest.java` 编写 `ReActLoopTerminationTest` —— 使用 `FakeProviderService`，其队列预先装载 `MAX_ITERATIONS` 个 tool_call 响应；预期 `LoopResult(iter=10, terminatedAtMax=true)`（SC-002）。若 T043 的 MAX 守卫缺失则测试 FAIL。
- [ ] T050 [P] [US-2/P3] 在 `oryxos-core/src/test/java/io/oryxos/core/ReActLoopMultiToolTest.java` 编写 `ReActLoopMultiToolTest` —— 覆盖 spec US3 验收场景 1（3 次 LLM 调用 + 2 次 Tool 调用 + 严格按序 6 条消息）。在 `ReActLoop` 正确处理顺序多 tool call 之前应 FAIL。
- [ ] T051 [P] [US-2/P3] 在 `oryxos-core/src/test/java/io/oryxos/core/ReActLoopConcurrencyTest.java` 编写 `ReActLoopConcurrencyTest` —— 启动一次 Spring `ApplicationContext`，对每个独立 `InMemorySession` 并发触发 20 次 `ReActLoop.run(...)`，断言：(a) 无异常，(b) 每个 session 恰好以 2 条消息结束（自身的 user + 合成的 assistant），(c) 在非循环线程上 `ProfileContext.current()` 返回 `Optional.empty()`（验证 R-7 隔离）

### [US-2/P3] 实现

- [ ] T052 [US-2/P3] 验证现有 `ReActLoop`（T040+）已正确处理顺序多 tool 派发（按 R-5 设计 —— 已完成）。若 `ReActLoopMultiToolTest`（T050）暴露缺陷，原地修复；否则无代码改动。
- [ ] T053 [US-2/P3] 运行全部 US-2/P3 测试；预期 `Termination` + `MultiTool` + `Concurrency` 全绿。保存到 `evidence/T053-P3-green.log`
- [ ] T054 [US-2/P3] Quickstart §4 并发负载测试：启动 20 个并行 CLI `chat small-talk` 会话共享单一 `ApplicationContext`；查询 SQLite：`SELECT COUNT(DISTINCT session_id) FROM llm_calls WHERE profile_name = 'small-talk'` 应等于 20。把 SQL 查询 + 结果保存到 `evidence/T054-concurrency.txt`
- [ ] T055 [US-2/P3] `git commit` 信息 `feat(core): implement US-2/P3 multi-iteration termination + 20-thread concurrency isolation`。把 commit hash 保存到 `evidence/T055-P3-commit.txt`

**检查点**：P3 demo（quickstart §3 每日日报，桩化 Tool）可跑通；SPEC-001/002/003/004 全部端到端验证。

---

## 阶段 6：[US-2/AG] AgentService 集成

**目的**：装配把 ProfileContext + Profile 查找 + ReActLoop 串起来的统一入口。没有 `AgentService`，三个触发源（CLI/Web/Scheduler）无法共享同一循环行为（FR-001 / FR-021）。

- [ ] T056 [P] [US-2/AG] 在 `oryxos-core/src/main/java/io/oryxos/core/ProfileRegistry.java` 创建 `ProfileRegistry` 接口，含 `Optional<Profile> find(String name);` + `Set<String> names();`
- [ ] T057 [P] [US-2/AG] 在 `oryxos-core/src/main/java/io/oryxos/core/FilesystemProfileRegistry.java` 创建 `FilesystemProfileRegistry` 桩 —— 启动时扫描 `.oryxos/agents/*/AGENT.md`，把 YAML frontmatter 解析为 `Profile` record；这是 US-2 的最小实现（US-5 将换成 SQLite `profiles` 表支撑的 registry）
- [ ] T058 [US-2/AG] 在 `oryxos-core/src/main/java/io/oryxos/core/DefaultAgentService.java` 实现 `DefaultAgentService`，按 [contracts/AgentService.md](contracts/AgentService.md) §5 —— Spring `@Service`；构造函数注入 `ProfileRegistry` + `ReActLoop`；`process(session, message)` 在 `try` 块设置 `ProfileContext`、在 `finally` 清除（C-AS-2 / I-06）
- [ ] T059 [P] [US-2/AG] 在 `oryxos-core/src/test/java/io/oryxos/core/DefaultAgentServiceTest.java` 编写 `DefaultAgentServiceTest` —— 按 [contracts/AgentService.md §6](contracts/AgentService.md) 的 4 个测试：`happyPath`、`unknownProfileThrows`（C-AS-3 / C-AS-4）、`profileContextClearedOnException`（C-AS-2 / C-AS-5）、`profileContextClearedOnSuccess`（C-AS-2）。预期 4 个绿。
- [ ] T060 [P] [US-2/AG] 在 `oryxos-core/src/test/java/io/oryxos/core/AgentServiceE2EIT.java` 编写 `AgentServiceE2EIT` —— 完整 Spring Boot `@SpringBootTest` + `@ActiveProfiles("e2e")`；用 WireMock 桩 deepseek（端口 8081），通过 `DefaultAgentService` 跑一遍完整 Daily Weather 流程。断言：
  - `LlmCallRecord` 行数 == 2
  - `ToolInvocationRecord` 行数 == 1，`success=true`
  - Session 消息数 == 4
  - 保存到 `evidence/T060-AgentServiceE2E-green.log`
- [ ] T061 [US-2/AG] 把 `FilesystemProfileRegistry` 作为生产 `@Bean` 注册（T058 构造函数注入）；添加 `@Configuration @Profile("!test")` 注册它；添加 `@Bean @Primary` 暴露内存版 `ProfileRegistry` 供 `DefaultAgentServiceTest` 使用
- [ ] T062 [US-2/AG] 运行全部 US-2 测试：`mvn -pl oryxos-core test -DfailIfNoTests=false`；预期总计 ≥ 30 测试全绿；重跑 `mvn -pl oryxos-provider test` 时 US-1 基线（35/35）仍通过。两份输出都保存到 `evidence/T062-all-tests.log`
- [ ] T063 [US-2/AG] `git commit` 信息 `feat(core): implement US-2/AG AgentService unified entry point + ProfileContext lifecycle`。把 commit hash 保存到 `evidence/T063-AG-commit.txt`

**检查点**：`AgentService.process(...)` 是单一入口。SC-005（通过 CLI 的每日天气）就绪；SC-005（通过 Web/Scheduler 部分）留到 US-5 spec。

---

## 阶段 7：打磨与横切关注点

**目的**：加固、验证、交付物。满足 Constitution §VII "Demo-First Delivery" + per-US `git commit` + `/speckit-analyze` 门禁。

- [ ] T064 运行 `mvn -pl oryxos-core,oryxos-storage,oryxos-provider,oryxos-boot -am clean verify`；预期 BUILD SUCCESS，≥ 30 个 US-2 测试绿 + 35 个 US-1 测试绿（零回归）。保存到 `evidence/T064-final-verify.log`
- [ ] T065 [P] 在真实代码库上（不仅是单元测试）端到端跑 quickstart.md §1~§4；把每节的预期输出 + 实际输出分别保存到 `evidence/T065-quickstart-§N.txt` 文件
- [ ] T066 [P] 重跑 `/speckit-analyze` 校验完成的 US-2 制品；把任何 critical/high 发现记录到 `evidence/T066-analyze.md` 并修复或说明延期
- [ ] T067 代码清理：清理所有 US-2 文件中未使用的 import；确保无 `System.out.println`（用 SLF4J）；确保所有 `record` 类在 compact constructor 中对非空字段声明 `Objects.requireNonNull`（FR-018 record 不变量）
- [ ] T068 [P] Constitution 合规验证：重走 [constitution.md §I..§VII](../.specify/memory/constitution.md) —— 确认 [plan.md](plan.md) 的 Complexity Tracking 仍为空；未新增模块；未引入第三方 Agent 框架依赖；`tool_invocations` day-one 表已有真实运行写入的行（不仅是单元测试桩）
- [ ] T069 [P] 按项目约定（CLAUDE.md §2）把 `tasks.md` 与关键 spec 文档翻译为中文。保留英文章节标题（让 `/speckit-analyze` 仍能匹配锚点）；翻译散文；跳过代码块。
- [ ] T070 [P] 如果出现新模式（例如循环中发现值得为后续 agent 记录的非显然陷阱），更新 CLAUDE.md —— 但**绝不**修改 constitution.md（Constitution §V §5："Constitution 不可变性"）
- [ ] T071 [P] 运行 `git status` 确认只有预期文件进入 changeset（无调试日志、来自开发运行的 `.oryxos/sessions/*.db`、过期的 `.class` 文件）；把状态保存到 `evidence/T071-pre-commit-status.txt`
- [ ] T072 [P] `git add specs/002-react-loop/evidence/` + 任何尚未提交的 US-2 源文件；**不要**添加 `tasks.md` 本身（按 Constitution V.5 per-US 约定，保持未暂存以供人工审阅）
- [ ] T073 per-US 提交：确认 `T037`、`T048`、`T055`、`T063` 四次提交存在；若任何阶段提交静默失败则重建
- [ ] T074（终态）`git push origin 002-react-loop`；把 `git log origin/002-react-loop..002-react-loop --oneline` 保存到 `evidence/T074-push.txt`；若 remote 存在则开 PR 指向 `main`
- [ ] T075 [跨 US-2 / US-3 修复] **PromptBuilder Spring 装配 bug 修复** —— 2026-07-25 在 [003-cli-commands](../003-cli-commands/spec.md) 验证 `OryxOsApplication.main` 启动时发现：`PromptBuilder` 带 `@Component` 但有 2 个 public 构造（4 参 + 2 参便捷），Spring 无法决定走哪个，回退找默认构造 → `NoSuchMethodException: PromptBuilder.<init>()` → 应用启动失败。修复在 `oryxos-core/src/main/java/io/oryxos/core/config/PromptBuilderConfig.java`：
  - 移除 `PromptBuilder` 类上的 `@Component`（与 [ProfileRegistryConfig](oryxos-core/src/main/java/io/oryxos/core/config/ProfileRegistryConfig.java) 同样的 config-as-source-of-truth 模式）
  - 新增 `@Configuration PromptBuilderConfig`，用 `@Bean` 工厂方法显式调用 4 参构造；注册 4 个桩 bean：`MemoryInjector` → `NoopMemoryInjector`、`ToolSchemaProvider` → `NoopToolSchemaProvider`、`BootstrapLoader` → `NoopBootstrapLoader`、`Clock` → `Clock.systemDefaultZone()`
  - 4 个 Noop bean **都**不带 `@Primary` —— US-3（MemoryServiceBridge）/ US-4（FilesystemBootstrapLoader + ToolRegistrySchemaAdapter）落地真实实现时加 `@Primary` 即自动覆盖
  - 新增 4 个烟雾测试在 `oryxos-core/src/test/java/io/oryxos/core/config/PromptBuilderConfigTest.java`：`configBootsCleanly_noBeanCreationException`（直接对应原 bug 现场）、`promptBuilderBeanIsConstructedAndBuildsPrompt`（端到端 build 验证）、`noopBeansAreNotPrimary_soFutureRealImplsCanOverrideViaPrimary`（结构保护）、`sanity_absentBeanStillThrowsNoSuchBeanDefinitionException`（测试基础设施非桩）。预期 4/4 绿；`mvn -pl oryxos-core,oryxos-cli,oryxos-boot test -am` 共 122 测试绿（零回归）
  - 保存输出到 `evidence/T075-promptbuilder-fix.log`
  - commit 信息：`fix(core): register PromptBuilder deps as @Bean to fix Spring startup NoSuchMethodException`。把 commit hash 保存到 `evidence/T075-commit.txt`

---

## 依赖与执行顺序

### 阶段依赖

- **Setup（阶段 1）**：无内部依赖；T001 完成后可立即启动。
- **基础（阶段 2）**：依赖阶段 1（T001..T004）干净完成。**关键** —— T025 完成（基础验证构建绿）之前每个后续阶段都被阻塞。
- **用户故事阶段（3/4/5）**：各自依赖阶段 2 完成。US-2 内三个优先级切片**必须按 P1 → P2 → P3 串行执行**，因为三者都写到同一个 `ReActLoop.run(...)` 方法体。（P1 铺设骨架，P2 加入 tool 派发，P3 加固终止 + 并发。）故事级别的并行在此不安全 —— 与标准模板不同。
- **AgentService 集成（阶段 6）**：依赖阶段 5（P3），因为 `DefaultAgentService.process` 调用 `ReActLoop.run` 走完整路径；P3 的并发测试基础设施（`@SpringBootTest` ApplicationContext）被复用为 `AgentServiceE2EIT` 的 boot 地基。
- **打磨（阶段 7）**：依赖所有前序阶段。

### 每个阶段内

- 测试优先（必须在实现前 FAIL），然后实现，再重跑测试转绿。
- 阶段内 `[P]` 标记的任务可并行；非 `[P]` 任务有顺序依赖。
- 基础阶段中触及*不同文件*的任务标为 `[P]`；移动 + 重写任务（T005/T007）形成链（T007 依赖 T005），不是 `[P]`。

### 用户故事依赖

- **[US-2/P1] 纯 Reason**：独立；复用基础阶段接口（T009、T013、T014）。不依赖 P2 或 P3。
- **[US-2/P2] 单 Tool**：依赖 P1（扩展 `ReActLoop.run`）；使用 P1 的 `ToolExecutor` 接口。
- **[US-2/P3] 多 Tool**：依赖 P2（进一步扩展 `ReActLoop.run`）；在 `ReActLoop` 自身几乎不引入新内容，主要是加测试 + quickstart。
- **[US-2/AG] AgentService**：依赖 P3（完整循环行为就绪）；引入 `ProfileRegistry` + `FilesystemProfileRegistry`（无先前依赖）。

### 关键路径

T001 → T002 → T005 → T007 → T008 → T017 → T019 → T025（基础构建绿）
  → T026（P1 失败测试）→ T033（PromptBuilder）→ T034（ReActLoop 骨架）→ T036（P1 绿）
  → T038（P2 失败测试）→ T040（扩展 ReActLoop）→ T043（MAX_ITERATIONS 守卫）→ T045（P2 绿）
  → T049（P3 失败测试）→ T053（P3 绿）
  → T060（AgentServiceE2E IT）→ T062（所有测试通过）
  → T064（最终验证）→ T066（speckit-analyze）→ T074（推送）

---

## 并行机会

阶段内 `[P]` 任务可在不同文件上并行：

- **阶段 1**：T003、T004 —— 不同 Maven 目标 + 不同 `pom.xml` 行；可并行。
- **阶段 2**：T006、T009、T010、T011、T012、T013、T014、T016、T018、T020、T021、T022、T023 —— 不同 `.java` 文件；可全部并行创建（**移动**任务 T005/T007 必须串行）。注意：T008（US-1 import 扫）应在 T007 之后跑以避免双重 import 修复。
- **阶段 3**：T026、T027（测试）可并行；T028..T032（record/接口）可并行；T033+ 串行因为 T033 依赖 T029（`ToolExecutor` 接口）、T034 依赖 T028（`Prompt`）。
- **阶段 5**：T049/T050/T051 测试可全部并行（不同文件）。
- **阶段 6**：T056/T057（ProfileRegistry + 实现）+ T059（测试）并行；T058 依赖 T056；T060 依赖 T058。
- **阶段 7**：T065（多次 quickstart 跑）、T066、T069、T070、T072 全部 `[P]`。T067、T068、T071 必须先于 T073/T074。

**并行说明**：项目编码约定是顺序的单开发者单分支；本节中的并行针对工具辅助的舰队执行（例如多个 Sonnet 实例同时编辑不同文件）。单开发者路径按列出的顺序串行执行。

---

## 并行示例：用户故事 P2（单 Reason-Act-Observe）

舰队可并行启动 T038 + T039（失败测试）；一旦红，则串行跑 T040 + T041 + T042 + T043 + T044（实现）—— 它们都在同一份 `ReActLoop.java` 内（所以串行，**不是**并行 —— 同一文件）。转绿后 T046 + T047 可并行（不同 demo + 不同边界测试）。

---

## 实施策略

### MVP 优先（仅用户故事 P1）

1. 完成阶段 1（环境准备）—— 快赢
2. 完成阶段 2（基础）—— R-1 迁移是**成本最高的单一步骤**；把 T005/T008 当作独立 PR 来对待
3. 完成阶段 3（US-2/P1）—— 首个端到端循环体
4. **停止并验证**：重跑 `ReActLoopPureReasonTest`；手动跑 quickstart §1 真实 DeepSeek
5. 演示 MVP：`chat small-talk` 返回一条确定性问候；检查 `llm_calls` 表

### 增量交付（P1 → P2 → P3 → AG）

1. Setup → Foundation → P1 → P2 demo（每日天气、桩 Tool）→ P3 demo（每日日报、桩 Tool）→ AgentService → polish
2. 每一步增加一种能力，前面所有步骤保持绿。
3. per-US `git commit` 边界（Constitution §III Per-US commit）—— T037/T048/T055/T063 是四个需捕获的关键 commit hash。

### 推荐的分支策略

- US-2 全程单分支 `002-react-loop`（与 `001-llm-provider-routing` 阶段一致）
- per-US commit 是分支内（不是独立分支）—— 保留线性历史 + per-US 回退粒度
- 末尾（T074）开 PR 供人工审阅 + 合入 main

---

## 建议的 MVP 范围

**仅阶段 3 [US-2/P1]** 就足以满足 PR 内的评审里程碑。P1 之外，P2/P3/AG 各自解锁具体的 spec 验收场景：

| Story | Spec 映射 | 独立 Demo |
| --- | --- | --- |
| P1 | spec US1 / SC-007（日志） | "Hello" → 1 次 LLM 调用、1 条审计行 |
| P2 | spec US2 / SC-001 / SC-005 / SC-004 | 天气机器人：2 次 LLM 调用 + 1 次 Tool 调用 + 4 条消息 |
| P3 | spec US3 / SC-002 / SC-003 | 多 Tool + 20 并发线程 |
| AG | spec FR-001 / FR-021 | 同一机器人由 3 个源（CLI/Web/Scheduler）触发 —— US-2 仅 CLI |

包含 P1+P2+P3+AG 全部是自然的发布候选（Constitution §VII "Demo-First Delivery" 要求"完成的 US"具备端到端 demo）。

---

## 备注

- 强制校验和：**（上面所有 task + Constitution §I..§VII 仍 PASS + 35 个 US-1 测试仍绿 + ≥ 30 个 US-2 测试绿 + quickstart.md §1~§4 走过）**。
- 总任务数：**74**（不含阶段 7 overhead）。
- 任务细分：
  - 阶段 1（环境准备）：4 个任务
  - 阶段 2（基础）：21 个任务
  - 阶段 3（US-2/P1 纯 Reason）：12 个任务
  - 阶段 4（US-2/P2 单 Tool）：11 个任务
  - 阶段 5（US-2/P3 多 Tool + 并发）：7 个任务
  - 阶段 6（US-2/AG AgentService）：8 个任务
  - 阶段 7（打磨）：11 个任务
- `[P]` 可并行的总任务数：**22**（~30%）。
- 格式验证：每个 task 都符合 `- [ ] TNNN [P] [Story] 带文件路径的描述` 形式；每个 `[Story]` 对应一个 spec story 或 `[US-2/AG]`（横切）。
- 任何实现前的预检：阅读 [research.md §R-1](research.md) —— 接口下沉是整个 plan 中风险最高的操作；至少预算半天。
