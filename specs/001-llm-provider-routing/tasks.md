# Tasks: LLM Provider 路由（US-1）

**Input**: 设计文档来自 `/specs/001-llm-provider-routing/`
**Prerequisites**: [plan.md](./plan.md) ✅、[spec.md](./spec.md) ✅、[research.md](./research.md) ✅、[data-model.md](./data-model.md) ✅、[contracts/](./contracts/) ✅、[quickstart.md](./quickstart.md) ✅、[`../../.specify/memory/constitution.md`](../../.specify/memory/constitution.md) ✅

**Tests**: 是。spec SC-001 ~ SC-007 通过单元测试 + 集成测试 + SQLite SQL 查询断言。

**Organization**: 任务按 User Story 分组，使每个 story 可独立实现和测试。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 任务所属 User Story（US1, US2, US3, US4, US5）
- 描述含完整文件路径

---

## Phase 1: Setup（共享基础设施）

**目的**：项目初始化与基础结构

- [ ] T001 验证 Maven 9 模块结构（parent pom + 9 个子模块各自有 pom.xml + 包占位），通过 `mvn validate` 全绿（在 `oryxos-provider/src/main/java/io/oryxos/provider/` 与 `oryxos-storage/src/main/java/io/oryxos/storage/` 已各有 `package-info.java` 占位；其余包结构待建）
- [ ] T002 添加 SQLite JDBC 驱动 + Hypersistence Utils 到 `oryxos-storage/pom.xml`（支持 SQLite 主键 + JSON 列；不引入其他 JPA 增强库）
- [ ] T003 添加 Spring AI Alibaba + Spring Boot 起步依赖到 `oryxos-provider/pom.xml`（含 OpenAI 兼容 ChatModel；保留 `ChatModel` 接口可用，不引入 `ChatClient`）
- [ ] T004 [P] 在 parent `pom.xml` 的 `<dependencyManagement>` 中锁定 Mockito + AssertJ 版本（覆盖所有模块的测试依赖）
- [ ] T005 [P] 添加 WireMock 到 `oryxos-provider/pom.xml` 测试作用域（e2e 阶段模拟 Provider HTTP 端点）

---

## Phase 2: Foundational（阻塞前置）

**目的**：所有 User Story 必须前置的核心基础设施

**⚠️ CRITICAL**: User Story 工作必须等此阶段完成

- [ ] T006 创建 `LlmCallRecord` JPA 实体于 `oryxos-storage/src/main/java/io/oryxos/storage/entity/LlmCallRecord.java`（字段严格按 [data-model.md](./data-model.md) §2：`id` / `sessionId` / `profileName` / `provider` / `model` / `success` / `errorMessage` / `promptTokens` / `completionTokens` / `durationMs` / `timestamp`；CHECK 约束 `success=0 OR error_message IS NULL` 通过 `@Check` 注解或 `hbm2ddl` 落地）
- [ ] T007 创建 `LlmCallRecordRepository`（Spring Data JPA）于 `oryxos-storage/src/main/java/io/oryxos/storage/repository/LlmCallRecordRepository.java`（继承 `JpaRepository<LlmCallRecord, UUID>`；提供 `findBySessionId(UUID)` 与 `findByProviderAndTimestampBetween(String, Instant, Instant)` 两个查询方法）
- [ ] T008 [P] 创建 `Provider` 配置 record 于 `oryxos-provider/src/main/java/io/oryxos/provider/Provider.java`（字段：`name` / `model` / `endpoint` / `credentialRef` / `options`；均为不可变）
- [ ] T009 [P] 创建 `ProviderProperties`（`@ConfigurationProperties("oryxos.providers")`）于 `oryxos-provider/src/main/java/io/oryxos/provider/config/ProviderProperties.java`（绑定 `oryxos.providers.*` 为 `List<Provider>`；提供 `getProviders()` 访问器）
- [ ] T010 创建 `ChatModelConfig` 于 `oryxos-provider/src/main/java/io/oryxos/provider/config/ChatModelConfig.java`（定义 3 个 `@Bean(name = {"deepseek", "qwen", "minimax"})` 的 `ChatModel`；全部走 OpenAI 兼容路径，仅 baseUrl / apiKey 不同；**不**标 `@Primary`；`apiKey` 取自 `Provider.credentialRef` 解析后的环境变量值）
- [ ] T011 创建 `ProviderRegistry` 于 `oryxos-provider/src/main/java/io/oryxos/provider/ProviderRegistry.java`（启动期构建 `Map<String, ChatModel>`；校验 `name` 唯一 + 全部 `ChatModel` Bean 已被 `application.yml` 中某条 Provider 配置覆盖 + `credentialRef` 已解析为非空字符串；违反抛 `IllegalStateException`；提供 `get(name)` 与 `containsName(name)` 方法）
- [ ] T012 创建 `CredentialResolver` 于 `oryxos-provider/src/main/java/io/oryxos/provider/CredentialResolver.java`（解析 `credentialRef` 形如 `${ENV_VAR}`：必须匹配正则；调用 `System.getenv` 返回非空字符串；任一不满足抛 `IllegalStateException` 并指明变量名）
- [ ] T013 创建 `ProviderAutoConfiguration` 于 `oryxos-provider/src/main/java/io/oryxos/provider/config/ProviderAutoConfiguration.java`（`@Configuration` + `@EnableConfigurationProperties(ProviderProperties.class)` + 通过 `@Bean` 方法装配 ProviderRegistry、CredentialResolver、ChatModelConfig 全部 Bean；`@PostConstruct` 触发启动校验）

**Checkpoint**: 基础就绪 — User Story 实现可并行开始

---

## Phase 3: User Story 1 + 2 — 按名路由 + 每次审计（优先级：P1）🎯 MVP

> **整合说明**：US-1 的验收场景显式要求"恰好写入一行 llm_calls"，US-2 落地该审计写入。两者在 MVP demo 中原子闭环（[quickstart.md](./quickstart.md) Step 5 同时验证两条），故合并为同一阶段。

**Goal**: 实现 `ProviderService.invoke(name, request)`，按 `name` 精确路由到 ChatModel；每次调用在返回前写一行 `llm_calls`。

**Independent Test**: 配两个 Provider（`deepseek` + `qwen`），写 Profile 声明 `provider.name: deepseek`；通过 `ProviderService.invoke` 触发一次调用；查 SQLite 确认 `llm_calls` 行 `provider='deepseek'`、`success=true`、token 有值（SC-001）。

### Tests for US1 + US2（spec 显式要求）

- [ ] T014 [P] [US1] 单元测试 `DefaultProviderServiceTest`（含 `@Nested` 路由 + 未知 Provider 两组）于 `oryxos-provider/src/test/java/io/oryxos/provider/DefaultProviderServiceTest.java`（mock `ChatModel` 与 `ProviderRegistry`；断言按 name 调用对应 ChatModel 一次；未知 name 抛 `UnknownProviderException`）
- [ ] T015 [P] [US2] 单元测试 `DefaultAuditWriterTest`（含 `@Nested` 成功 / 失败 / 写失败三组）于 `oryxos-provider/src/test/java/io/oryxos/provider/DefaultAuditWriterTest.java`（mock `LlmCallRecordRepository`；断言成功时 `success=true + promptTokens/completionTokens 有值`；失败时 `success=false + errorMessage 非空`；repo 抛异常时调用方仍收到原始 `LlmInvocationException`，不向外抛新异常）
- [ ] T016 [US1] 集成测试 `ProviderRoutingE2ETest` 于 `oryxos-provider/src/test/java/io/oryxos/provider/e2e/ProviderRoutingE2ETest.java`（起 Spring 上下文 + 内存 SQLite + WireMock 两个 Provider 端点；用真实 `ProviderService.invoke` 跑一次成功路径 + 一次失败路径；用 SQL 查询 `llm_calls` 断言 SC-001）

### Implementation for US1 + US2

- [ ] T017 [P] [US1] 创建 `LlmRequest` record 于 `oryxos-provider/src/main/java/io/oryxos/provider/LlmRequest.java`（字段：`sessionId` / `profileName` / `messages`（`List<Map<String, Object>>`）/ `toolSchemas`（可空 `List<Map<String, Object>>`）/ `temperature`（可空）/ `maxTokens`（可空）；全为不可变）
- [ ] T018 [P] [US1] 创建 `LlmResponse` record 于 `oryxos-provider/src/main/java/io/oryxos/provider/LlmResponse.java`（含嵌套 `ToolCall` record（`name` / `arguments` / `callId`）与 `TokenUsage` record（`promptTokens` / `completionTokens`）；主字段 `textContent` / `toolCalls` / `usage` / `finishReason`）
- [ ] T019 [P] [US1] 创建 `UnknownProviderException` + `LlmInvocationException` 于 `oryxos-provider/src/main/java/io/oryxos/provider/exception/`（前者单参 `name`；后者四参 `providerName` / `message` / `durationMs` / `cause`）
- [ ] T020 [US1] 创建 `ProviderService` 接口于 `oryxos-provider/src/main/java/io/oryxos/provider/ProviderService.java`（单方法 `LlmResponse invoke(String providerName, LlmRequest request)`；Javadoc 列出契约要点，对齐 [contracts/ProviderService.java](./contracts/ProviderService.java)）
- [ ] T021 [US1] 实现 `DefaultProviderService` 路由核心于 `oryxos-provider/src/main/java/io/oryxos/provider/DefaultProviderService.java`（按 `name` 查 `ProviderRegistry.get(name)` 拿 `ChatModel`；构造 `ChatOptions`（温度 / maxTokens / 预留 toolSpecifications 字段）；调 `ChatModel.call(Prompt)`；用 `Instant` 记录 `durationMs`；把 `ChatResponse` 翻译为 `LlmResponse`；遇 `ChatModelException` 包成 `LlmInvocationException`）
- [ ] T022 [US2] 实现 `DefaultAuditWriter` 于 `oryxos-provider/src/main/java/io/oryxos/provider/DefaultAuditWriter.java`（`@Component`；方法签名 `void write(LlmCallRecord record)`；标 `@Transactional(propagation = REQUIRES_NEW)`；外层 try 写正常记录，捕获 `Exception` 后内层 try 用 `success=false, errorMessage="audit write failed: <原因>", durationMs=已用` 的兜底记录再写一次；最终失败仅 ERROR 日志，**不**抛给调用方）
- [ ] T023 [US2] 注入 `AuditWriter` 到 `DefaultProviderService`（在 `invoke` 方法返回前调 `auditWriter.write(record)`；写库异常被吞后仅记 ERROR 日志；不影响响应）于 `oryxos-provider/src/main/java/io/oryxos/provider/DefaultProviderService.java`（修改 T021 文件）
- [ ] T024 [US1] 在 `ProviderRegistry` 暴露 `containsName(String)` 方法（已被 T011 隐含；本任务显式加公开方法 + 单测）于 `oryxos-provider/src/main/java/io/oryxos/provider/ProviderRegistry.java`（修改 T011 文件）
- [ ] T025 [US1] `DefaultProviderService` 把 `LlmRequest.toolSchemas`（目前可能为 null / 空）透传到 `ChatOptions.toolSpecifications`（空时不设字段；为 US-5 翻译器接入留口）于 `oryxos-provider/src/main/java/io/oryxos/provider/DefaultProviderService.java`（修改 T021 文件）

**Checkpoint**: MVP 完成 — 路由 + 审计端到端可跑通；SC-001 / SC-002 可验证

---

## Phase 4: User Story 3 — 同一类型多 Provider 共存（优先级：P2）

**Goal**: 支持两个或更多共享同一底层类型的 Provider，用不同 `name` 注册，无串扰、无 fallback。

**Independent Test**: 配 `deepseek-prod` + `deepseek-dev`（同 type、不同 API key）；两个 Profile 各跑一次；查 `llm_calls` 归属正确 + 凭证不串扰（SC-004）。

### Tests for US3

- [ ] T026 [P] [US3] 单元测试 `ProviderRegistryMultiInstanceTest` 于 `oryxos-provider/src/test/java/io/oryxos/provider/ProviderRegistryMultiInstanceTest.java`（注入两个同 type（mock `ChatModel`）不同 `name` 的 Provider；断言都注册成功；按 `name` 查询互不混淆；缺 name 时不命中）
- [ ] T027 [US3] 集成测试 `MultiProviderE2ETest` 于 `oryxos-provider/src/test/java/io/oryxos/provider/e2e/MultiProviderE2ETest.java`（WireMock 三个端点：`deepseek` / `qwen` / `minimax`；Profile 切换 `provider.name` 三次；查 `llm_calls` 验证 SC-004：归属正确 + 三家真实独立供应商无串扰）

### Implementation for US3

- [ ] T028 [P] [US3] 在 `oryxos-boot/src/main/resources/application.yml` 添加同 type 多实例示例（注释段加 `deepseek-prod` + `deepseek-dev`，方便后续运维参照）
- [ ] T029 [US3] 确认 `ChatModelConfig` 接受同 type 多 Bean 不冲突（不加 `@Primary`；依赖 Spring 容器按 `@Bean(name = ...)` 显式名注册；`ProviderRegistry` 用 `getBeansOfType(ChatModel.class)` 按 name 收集）于 `oryxos-provider/src/main/java/io/oryxos/provider/config/ChatModelConfig.java`（修改 T010 文件）

**Checkpoint**: SC-004 可验证；多供应商并存成立

---

## Phase 5: User Story 4 — 通过 Profile 热切换模型（优先级：P2）

**Goal**: Profile 的 `provider.model` 改动 + 配置重载后，新模型名体现在 audit 行。

**Independent Test**: Profile 写 `model: deepseek-chat` 跑一次 → audit `model='deepseek-chat'`；改 `model: deepseek-coder` 重启 → 新 audit `model='deepseek-coder'`（SC-003）。

### Tests for US4

- [ ] T030 [P] [US4] 单元测试 `ProfileModelOverrideTest` 于 `oryxos-provider/src/test/java/io/oryxos/provider/ProfileModelOverrideTest.java`（mock `Profile`，验证 `LlmRequest.model` 字段反映 Profile `provider.model` 而非 `application.yml` 中 Provider 配置的默认 model；Profile 未填 model 时回落到 `application.yml` 默认）
- [ ] T031 [US4] 集成测试 `HotSwapModelE2ETest` 于 `oryxos-provider/src/test/java/io/oryxos/provider/e2e/HotSwapModelE2ETest.java`（同一 Profile 两阶段：第一阶段 `model: deepseek-chat` → invoke → 审计断言 `model='deepseek-chat'`；第二阶段修改 Profile `model: deepseek-coder` → invoke → 审计断言 `model='deepseek-coder'`；SC-003）

### Implementation for US4

- [ ] T032 [US4] 确认 `Profile` 加载层把 `provider.model` 字段透传（位于 `oryxos-core/src/main/java/io/oryxos/core/profile/Profile.java`；若无该字段读取则补充 frontmatter 解析；空值校验在 Profile 加载期 fail-fast；该任务只确保**消费链路通畅**，不动 Provider 路由代码）

**Checkpoint**: SC-003 可验证；运维不需改代码即可换模型

---

## Phase 6: User Story 5 — 翻译工具 Schema，不执行工具（优先级：P2）

**Goal**: 把 Provider 中立的工具 schema 翻译为 Provider 原生（OpenAI）格式随请求发出；响应中的 tool call 以中立格式返回；本层绝无工具代码副作用。

**Independent Test**: Profile 声明 N 个工具（mock schema），验证请求恰好 N 个 tool schema 条目；mock `OryxTool.execute` 计数器，断言 Provider 调用期间 = 0（SC-005 / SC-006）。

### Tests for US5

- [ ] T033 [P] [US5] 单元测试 `ToolSchemaTranslatorTest`（含 `@Nested` translate + denormalize 两组）于 `oryxos-provider/src/test/java/io/oryxos/provider/ToolSchemaTranslatorTest.java`（中立 → OpenAI 格式：`{type:"function", function:{name, description, parameters}}` 字段对齐；OpenAI → 中立：响应中 `tool_calls` → `LlmResponse.ToolCall(name, arguments, callId)`）
- [ ] T034 [US5] 单元测试 `ProviderServiceNoToolExecutionTest` 于 `oryxos-provider/src/test/java/io/oryxos/provider/ProviderServiceNoToolExecutionTest.java`（mock 一个 `OryxTool` Bean + `AtomicInteger executeCallCount`；跑一次完整 `ProviderService.invoke`；断言 `executeCallCount.get() == 0`；SC-006 硬约束）

### Implementation for US5

- [ ] T035 [P] [US5] 创建 `ToolSchemaTranslator` 于 `oryxos-provider/src/main/java/io/oryxos/provider/ToolSchemaTranslator.java`（`@Component`；两个纯函数：`List<...> translate(List<Map<String, Object>> neutral)` 把中立 JSON Schema → OpenAI `ToolSpecification` 列表；`List<ToolCall> denormalize(ChatResponse)` 把 OpenAI 响应 → `LlmResponse.ToolCall` 列表；无 IO 无状态）
- [ ] T036 [US5] 把 `ToolSchemaTranslator` 接入 `DefaultProviderService`：请求前 `translate` 中立 schema 写入 `ChatOptions.toolSpecifications`；响应后 `denormalize` 把 tool_calls 写入 `LlmResponse.toolCalls`；**不**注入 `ToolCallback` / `FunctionCallback`（物理上消除工具自动执行）于 `oryxos-provider/src/main/java/io/oryxos/provider/DefaultProviderService.java`（修改 T021 / T025 文件）

**Checkpoint**: SC-005 / SC-006 可验证；工具翻译层就绪（执行由 US-2 ReAct 层负责，本层不参与）

---

## Phase 7: Polish & Cross-Cutting Concerns

**目的**：影响多 User Story 的改进与最终验收

- [ ] T037 在 `oryxos-boot/src/main/resources/application.yml` 添加完整 `oryxos.providers` 配置（deepseek + qwen + minimax 三家真实供应商，含 `temperature` 默认值；`credentialRef` 全部走 `${ENV_VAR}`）
- [ ] T038 按 [quickstart.md](./quickstart.md) Steps 0-9 真实跑一次端到端 demo（需 `DEEPSEEK_API_KEY` + `QWEN_API_KEY` + `MINIMAX_API_KEY` 三个真实 key；先用 WireMock 跑通离线版，再切真实 key）
- [ ] T039 验证 SC-001 ~ SC-007 七条验收标准（通过 SQLite SQL 查询 + curl + log 检查；逐条打勾，结果记入 demo 报告）
- [ ] T040 [P] 修复 [plan.md](./plan.md) Summary 中残留的"DeepSeek + Qwen 双供应商 MVP"措辞为"DeepSeek + Qwen + MiniMax 三家不同供应商"（澄清 Q1 后未同步的笔误；同时核对 Phase 0 产物表格无遗漏）
- [ ] T041 [P] 运行 `/speckit.analyze` 校验 spec.md / plan.md / 实现 三者一致性（Constitution §III "Per-US gate"）
- [ ] T042 git commit 在分支 `001-llm-provider-routing`（提交信息：`feat: implement LLM Provider routing per US-1 (deepseek + qwen + minimax, audit-first)`；Constitution §III "Per-US commit"）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup（Phase 1）**: 无依赖 — 立即开始
- **Foundational（Phase 2）**: 依赖 Setup 完成 — **阻塞** 所有 User Story
- **User Stories（Phase 3-6）**: 全部依赖 Foundational 完成
  - Phase 3（US1+US2）→ Phase 4（US3）→ Phase 5（US4）→ Phase 6（US5）按优先级
  - Phase 4 / 5 / 6 互相独立（不同测试 / 不同文件），可并行
- **Polish（Phase 7）**: 依赖全部 User Story 完成

### User Story Dependencies

- **US-1 + US-2（Phase 3）**: 依赖 Foundational。MVP 闭环。
- **US-3（Phase 4）**: 依赖 Phase 3。验证"多 Provider 共存"叠加在已可路由之上。
- **US-4（Phase 5）**: 依赖 Phase 3。验证 model 覆盖不破坏路由。
- **US-5（Phase 6）**: 依赖 Phase 3。验证 tool schema 翻译在路由链路中工作；不依赖 US-3 / US-4。

### Within Each User Story

- 测试**先写**并确认失败（RED），再实现（GREEN）— Constitution §III + §VII
- DTO / Exception / 接口先于 Service
- Service 接口先于实现
- Config Bean 注入先于 Service 消费
- Story complete before moving to next priority

### Parallel Opportunities

- 所有 Setup 标 [P] 任务并行（T004 / T005）
- 所有 Foundational 标 [P] 任务并行（T008 / T009）
- Phase 3 内 T017 / T018 / T019 三件 DTO + Exception 并行；T014 / T015 两件测试并行
- Phase 4 内 T026 / T028 并行（不同文件）
- Phase 5 内 T030 / T032 并行（不同文件）
- Phase 6 内 T033 / T035 并行（不同文件）
- 不同 User Story 可由不同开发者并行（Phase 4 / 5 / 6 互不依赖）

---

## Parallel Example: US1 + US2 MVP

```bash
# 阶段 3 — DTO 与异常并行创建（不同文件，无依赖）：
任务: "T017 创建 LlmRequest record 于 oryxos-provider/src/main/java/io/oryxos/provider/LlmRequest.java"
任务: "T018 创建 LlmResponse record 于 oryxos-provider/src/main/java/io/oryxos/provider/LlmResponse.java"
任务: "T019 创建 UnknownProviderException + LlmInvocationException 于 oryxos-provider/src/main/java/io/oryxos/provider/exception/"

# 阶段 3 — 接口、单元测试、集成测试并行准备（接口先于实现，确保测试可编译）：
任务: "T020 创建 ProviderService 接口于 oryxos-provider/src/main/java/io/oryxos/provider/ProviderService.java"
任务: "T014 单元测试 DefaultProviderServiceTest 于 oryxos-provider/src/test/java/io/oryxos/provider/DefaultProviderServiceTest.java"
任务: "T015 单元测试 DefaultAuditWriterTest 于 oryxos-provider/src/test/java/io/oryxos/provider/DefaultAuditWriterTest.java"
任务: "T016 集成测试 ProviderRoutingE2ETest 于 oryxos-provider/src/test/java/io/oryxos/provider/e2e/ProviderRoutingE2ETest.java"
```

---

## Implementation Strategy

### MVP First（US-1 + US-2 Only）

1. 完成 Phase 1: Setup（T001-T005）
2. 完成 Phase 2: Foundational（T006-T013）
3. 完成 Phase 3: US-1 + US-2（T014-T025）— 路由 + 审计
4. **STOP and VALIDATE**: 跑 quickstart.md Steps 0-5 独立验证 MVP（SC-001 / SC-002 应通过）
5. 可 demo 则部署（"三 Provider + 审计"已闭环）

### Incremental Delivery

1. Setup + Foundational → 基础设施就绪
2. 加 US-1 + US-2 → 独立测试 → Demo（**MVP!**）
3. 加 US-3 → 独立测试 → Demo（多供应商并存）
4. 加 US-4 → 独立测试 → Demo（热切换模型）
5. 加 US-5 → 独立测试 → Demo（工具翻译就绪，待 US-2 ReAct 闭环）
6. 每个 story 加价值不破坏前序 story

### Parallel Team Strategy

多开发者场景（Foundational 完成后）：

1. Dev A: US-3（多 Provider）
2. Dev B: US-4（热切换）
3. Dev C: US-5（工具翻译）
4. Stories 独立完成 + 独立集成 + 独立 commit

---

## Notes

- [P] 任务 = 不同文件、无未完成依赖
- [Story] 标签映射到具体 User Story 用于追溯
- 每个 User Story 可独立完成 + 独立测试
- **测试先写**（RED）再实现（GREEN），不省
- 每个 task 或逻辑组完成后 commit
- 任意 checkpoint 停止以独立验证 story
- 避免：含糊任务、同文件冲突、跨 story 依赖破坏独立性

### Constitution 硬约束（不可改）

- **§IV Spring AI 只用一半**：T021 用 `ChatModel.call(Prompt)` 而非 `ChatClient`；T036 不引入 `ToolCallback` / `FunctionCallback`
- **§IV §IV陷阱 #1 物理消除自动 tool 执行**：T036 不传 `ToolCallback`，Spring AI 无可执行内容
- **§VI day-one 审计**：T022 的 `REQUIRES_NEW` + 双层 try/catch 是硬性要求，不可改
- **§I 严格九模块**：所有改动仅落在 oryxos-provider / oryxos-storage / oryxos-core / oryxos-boot 四个模块
- **§IV陷阱 #2 不容器扫描 Provider**：T010 + T011 用显式 `@Bean(name = ...)` + `Map<String, ChatModel>`
- **§Additional Constraints 凭证 env var**：T012 + T013 启动期 fail-fast，缺 `${ENV_VAR}` 直接退出

### spec 边界（spec FR-002 / FR-003 / FR-011）

- **不重试 / 不 fallback**：T021 遇 `ChatModelException` 仅包成 `LlmInvocationException` 后透传，不二次尝试
- **不同步流**：T021 用 `ChatModel.call(Prompt)` 同步 API，不引入 `Flux<ChatResponse>`
- **凭证只来自 env**：T002 配置文件 `credentialRef` 必须 `${...}` 形态；T012 双保险校验非空