---
description: "Task list for Memory layer implementation (006-memory-layer)"
---

# Tasks: Memory 层（让 Agent 记得住事的可插拔记忆层）

**Input**: Design documents from `/specs/006-memory-layer/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅, quickstart.md ✅
**Tests**: 显式包含（宪法 §VII "Demo-First" + [quickstart.md §端到端冒烟脚本](./quickstart.md)）；JUnit 5 + Mockito + WireMock + 5 场景端到端 smoke。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4, US5)
- Include exact file paths in descriptions

## Path Conventions

- **Maven multi-module**: 9 modules under `oryxos-{core,tool,memory,provider,storage,web,channel-cli,cli,boot}/`
- **Test paths**: `oryxos-<module>/src/test/java/io/oryxos/...`
- **DB migrations**: `oryxos-storage/src/main/resources/db/migration/V<n>__<name>.sql`
- **Scripts**: `scripts/<name>.sh`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**：项目初始化 + Memory 模块前置条件 + 测试基础设施

- [x] T001 [P] 验证 9 Maven 模块存在（oryxos-core/tool/memory/provider/storage/web/channel-cli/cli/boot）符合 [plan.md §4.2](./plan.md)
- [x] T002 [P] 在 `oryxos-memory/pom.xml` 添加 `wiremock-standalone` 作为 test scope 依赖（Mem0 后端集成测用；版本由父 pom `wiremock.version=3.9.1` 管理）
- [x] T003 验证 `oryxos-storage/src/main/resources/db/migration/` 资源目录存在（[data-model.md §3.1](./data-model.md) V4 DDL 落点；005 阶段已创建）
- [x] T004 [P] 在 `oryxos-boot/pom.xml` 添加 `oryxos-memory` 模块依赖（DI 装配 MemoryProperties + MemoryBackendSelector Bean；已存在 005 阶段已加，确认 ✓）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**：阻塞所有 User Story 的核心基础设施 —— V4 DDL、JPA Entity、MemoryService 接口、核心契约码化。这些不完成，任何 US 都不能跑通。

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T005 编写 V4 DDL migration `oryxos-storage/src/main/resources/db/migration/V4__add_agent_memories.sql`（[data-model.md §3.2](./data-model.md)）：CREATE TABLE agent_memories（含 CHECK 约束）+ 2 索引（idx_agent_memories_scope_created / idx_agent_memories_tags）+ DOWN rollback
- [x] T006 [P] 创建 JPA Entity `oryxos-memory/src/main/java/io/oryxos/memory/repository/MemoryEntryEntity.java`（[data-model.md §3.3](./data-model.md)）：`@Entity @Table(name="agent_memories")`，含 id / scope / content / tags / source / createdAt 字段 + toMemoryEntry() 方法
- [x] T007 [P] 创建 JPA Repository `oryxos-memory/src/main/java/io/oryxos/memory/repository/MemoryEntryRepository.java`（[data-model.md §3.4](./data-model.md)）：继承 `JpaRepository<MemoryEntryEntity, String>`；含 `findByScopeAndContentLike` / `findByScopeOrderByCreatedAtMillisDesc` / `findByTag` / `trimArchive` / `countByScope` / `deleteByScope` 方法
- [x] T008 [P] 创建 `@ConfigurationProperties` record `oryxos-memory/src/main/java/io/oryxos/memory/MemoryProperties.java`（[data-model.md §2.4](./data-model.md)）：`prefix = "oryxos.memory"`，含 backend / archive / mem0 / markdown 子 record + 默认值（backend="markdown" / archiveMaxEntries=1000 / mem0BaseUrl="http://localhost:8000" / markdownPath=".oryxos/memory/MEMORY.md"）
- [x] T009 [P] 创建 `MemoryException` 类 `oryxos-memory/src/main/java/io/oryxos/memory/MemoryException.java`（[data-model.md §2.3](./data-model.md)）：`RuntimeException` 子类，构造器接受 message + cause
- [x] T010 [P] 验证或创建接口 `oryxos-memory/src/main/java/io/oryxos/memory/backend/LongTermMemoryStore.java`（[contracts/long-term-store.md §1](./contracts/long-term-store.md)）：方法签名 save / recallByKeyword / recallByScope / delete / clear / isHealthy（如未落地按契约创建）
- [x] T011 创建 `MemoryBackendSelector` `@Component @ConditionalOnBean(LongTermMemoryStore.class)` `oryxos-boot/src/main/java/io/oryxos/boot/config/MemoryBackendSelector.java`（[data-model.md §2.5](./data-model.md)）：按 `MemoryProperties.backend` 选择 LongTermMemoryStore 实现；注入 `Map<String, LongTermMemoryStore>`；`@PostConstruct` 启动期 fail-fast 验证 backend 字符串合法 + Bean 存在 + isHealthy()
- [x] T012 [P] 验证枚举 `oryxos-memory/src/main/java/io/oryxos/memory/MemoryScope.java`（[data-model.md §2.2](./data-model.md)）：值 = `CORE / ARCHIVE`（既有，spec FR-008 大小写不敏感 by `fromString` / `isValid` 兼容；`isValid(String)` 已在 Phase 2 增量）
- [x] T013 [P] 验证 record `oryxos-memory/src/main/java/io/oryxos/memory/MemoryEntry.java`（[data-model.md §2.1](./data-model.md)）：字段 = id / scope / content / tags / createdAt / source（既有 3 字段已扩展为 6 字段，保留 3 字段向后兼容构造器以不破既有 MarkdownMemoryStore 调用点）

**Checkpoint**：基础就绪 —— V4 DDL 已写、JPA Entity + Repository 已建、MemoryException + MemoryProperties + MemoryScope + MemoryEntry 全到位、MemoryBackendSelector Bean 待 US-3 阶段装配三后端后才完全可用。User Story 实现可从此开始。

---

## Phase 3: User Story 1 — Agent 跨会话记住用户偏好（P1）🎯 MVP

**Goal**：实现 `MemoryService` 统一门面 + 4 条核心契约码化（[CLAUDE.md §9.6](../CLAUDE.md) + [spec.md FR-001—FR-010](./spec.md)）+ 跨 Session 端到端召回验证

**Independent Test**：跑两次独立 chat session（两次 process 各自新建 Session + AgentService.process），第一次显式 `save(scope=core, ...)`，第二次新 Session 调 `recallByKeyword(query, topK, scopeFilter=core)` 100% 命中前次记录

### Tests for User Story 1 ⚠️ (TDD: write first, verify fail)

- [x] T014 [P] [US1] 契约测试 `oryxos-memory/src/test/java/io/oryxos/memory/MemoryServiceContractTest.java`：覆盖 8 条 C-MS 条款（[contracts/memory-service.md §2](./contracts/memory-service.md)）：no-cache / core-never-truncate / scope-explicit / clear(core)-rejects / empty-query / topK-cap / MemoryException-not-rethrown / no-stack-trace
- [x] T015 [P] [US1] 契约测试 `oryxos-memory/src/test/java/io/oryxos/memory/SessionManagerContractTest.java`：验证 Session 结束**不**自动调用 `longTermStore.save`（会话层与长期层边界；[spec.md FR-002](./spec.md)）

### Implementation for User Story 1

- [x] T016 [P] [US1] 创建 `SessionManager` 类 `oryxos-memory/src/main/java/io/oryxos/memory/SessionManager.java`（如未落地）：管理当前 Session 的对话消息；生命周期跟随 Session；提供 `getMessages() / addMessage()` 方法；**不**暴露 `LongTermMemoryStore` 引用（避免会话层误调长期层）
- [x] T017 [US1] 实现 `DefaultMemoryService` 类 `oryxos-memory/src/main/java/io/oryxos/memory/DefaultMemoryService.java`（[contracts/memory-service.md §3](./contracts/memory-service.md)）：构造器注入 SessionManager + LongTermMemoryStore；实现 5 个方法（save / recallByKeyword / recallByScope / delete / clear）；4 条核心契约码化为方法内断言（no-cache = 委派即返回；core-never-truncate = save(core) 不调用 trim；scope-explicit = save(scope=null) 抛 IllegalArgumentException；clear(core) = 抛 IllegalStateException）
- [x] T018 [P] [US1] 集成测试 `oryxos-memory/src/test/java/io/oryxos/memory/integration/CrossSessionMemoryIT.java`：使用 `@SpringBootTest` + MarkdownMemoryStore 默认后端；场景 1：Session A 调 `memoryService.save(core, "用户偏好 PR 标签 = bug+enhancement", tags=["preference"])`；Session B（独立 SessionId）调 `memoryService.recallByKeyword("PR 标签 偏好", 5, MemoryScope.core)`；断言命中 1 条（SC-002）
- [x] T019 [P] [US1] 集成测试 `oryxos-memory/src/test/java/io/oryxos/memory/integration/ReadAfterWriteIT.java`：N=100 次 save 后立即 recallByKeyword，断言 100% 命中（C-LT-01 / C-MS-01）

**Checkpoint**：至此 MemoryService 门面 + 4 条核心契约 + 跨 Session 端到端跑通；User Story 1 完整可独立测试。MVP 路径 = Phase 1 + Phase 2 + Phase 3。

---

## Phase 4: User Story 2 — 默认 Markdown 后端 + 文件可见性（P1）

**Goal**：实现 `MarkdownMemoryStore` 默认后端 + 文件结构契约（[spec.md FR-004/FR-005](./spec.md)）+ 9 条 C-MD 条款码化 + 文件追加 + lenient recovery

**Independent Test**：跑两次 chat session，Session B 调 save(scope=core) → 检查 `.oryxos/memory/MEMORY.md` 文件含 ## Core 段 + 新行；手动删除 ## Core 段 → save → 文件重建 ## Core 段（C-MD-08 lenient recovery）

### Tests for User Story 2 ⚠️

- [X] T020 [P] [US2] 契约测试 `oryxos-memory/src/test/java/io/oryxos/memory/backend/MarkdownMemoryStoreTest.java`：覆盖 9 条 C-MD 条款（[contracts/markdown-backend.md §3](./contracts/markdown-backend.md)）：append-mode / literal-keyword-match / empty-query / atomic-move / sync-serialization / tags-informational / lenient-recovery / archive-no-trim / core-1000-records
- [X] T021 [P] [US2] 契约测试 `oryxos-memory/src/test/java/io/oryxos/memory/backend/MarkdownFileStructureTest.java`：验证文件结构契约（[contracts/markdown-backend.md §2](./contracts/markdown-backend.md)）：含 `# MEMORY` 顶层标题 + `## Core` / `## Archive` 两段；行格式 `- [<ISO-8601>] [<UUID>] <content> [#tags=tag1,tag2]`

### Implementation for User Story 2

- [X] T022 [P] [US2] 验证或创建 `MarkdownMemoryStore` `@Component("markdownMemoryStore")` `oryxos-memory/src/main/java/io/oryxos/memory/backend/MarkdownMemoryStore.java`（[contracts/markdown-backend.md §1](./contracts/markdown-backend.md)）：实现 `LongTermMemoryStore` 6 个方法；构造器注入 `@Value("${oryxos.memory.markdown.path:.oryxos/memory/MEMORY.md}")`；`synchronized (writeLock)` 块串行化写；`Files.move(tmp, target, ATOMIC_MOVE)` 写文件（research R-04）
- [X] T023 [P] [US2] 实现 `MarkdownMemoryStore.formatLine` / `appendLine` / `parseSection` 私有方法：`formatLine(entryId, scope, content, tags, createdAt)` 生成行格式；`appendLine(scope, line)` 读全文 + 找段尾 + append + 写回；`parseSection(scope)` 读全文解析为 List<MemoryEntry>（按 created_at DESC 排序）
- [X] T024 [P] [US2] 集成测试 `oryxos-memory/src/test/java/io/oryxos/memory/backend/integration/MarkdownBackendIT.java`：使用 `@SpringBootTest` + 真实文件系统（tmp 目录）；场景 2（[quickstart.md §场景 2](./quickstart.md)）：save 3 条 → 读全文验证含 ## Core + ## Archive 两段 + 3 行；手动删除 ## Core → save 1 条 → 文件重建 ## Core 段（C-MD-08）；N=10 线程并发 save 100 次 → 最终 100 行（C-MD-06）
- [X] T025 [P] [US2] 在 `oryxos-boot/src/main/resources/application.yaml` 设置默认 `oryxos.memory.backend: markdown`（[research R-08](./research.md)）+ 默认 markdown.path / mem0.base-url / archive.max-entries 配置

**Checkpoint**：至此 Markdown 后端完整可独立测试；User Story 2 满足 SC-005 + FR-004 + FR-005 + 9 条 C-MD 条款。

---

## Phase 5: User Story 3 — 可插拔后端：SQLite 结构化 + Mem0 自托管（P2）

**Goal**：实现 `SqliteMemoryStore` + `Mem0MemoryStore` + 10 条 C-SQ + 10 条 C-M0 条款码化 + Profile `memo.backend` 字段切换

**Independent Test**：Profile `memo.backend=sqlite` → 跑 chat → save 后查 SQLite `agent_memories` 表含 1 行；切换 `memo.backend=mem0` 重启 → WireMock mock Mem0 服务 → save 后查本地 `memory_index` 表 + WireMock 收到 POST /memories 请求（SC-004 后端切换 0 业务中断）

### Tests for User Story 3 ⚠️

- [x] T026 [P] [US3] 契约测试 `oryxos-memory/src/test/java/io/oryxos/memory/backend/SqliteMemoryStoreTest.java`：覆盖 10 条 C-SQ 条款（[contracts/sqlite-backend.md §2](./contracts/sqlite-backend.md)）：DDL-manual / archive-lazy-trim / core-no-trim / tags-JSON / LIKE-match / index-sort / scope-CHECK / transactional / busy-retry / parameterized-query
- [x] T027 [P] [US3] 契约测试 `oryxos-memory/src/test/java/io/oryxos/memory/backend/Mem0MemoryStoreTest.java`（使用 WireMock）：覆盖 10 条 C-M0 条款（[contracts/mem0-backend.md §4](./contracts/mem0-backend.md)）：unreachable-save / unreachable-recall / timeout-5s / shared-http-client / localId-mapping / metadata-userId / core-no-trim / scope-validation / delete-double-delete / health-check
- [x] T028 [P] [US3] 契约测试 `oryxos-memory/src/test/java/io/oryxos/memory/MemoryBackendSelectorTest.java`：按 `MemoryProperties.backend` 字符串选择对应实现（markdown/sqlite/mem0）；非法 backend 抛 IllegalArgumentException（research R-08）

### Implementation for User Story 3

- [x] T029 [US3] 实现 `SqliteMemoryStore` `@Component("sqliteMemoryStore")` `oryxos-memory/src/main/java/io/oryxos/memory/backend/SqliteMemoryStore.java`（[contracts/sqlite-backend.md §1](./contracts/sqlite-backend.md)）：构造器注入 MemoryEntryRepository + ObjectMapper + `@Value("${oryxos.memory.archive.max-entries:1000}")`；实现 6 个方法（save/recallByKeyword/recallByScope/delete/clear/isHealthy）；`save(archive)` 触发 lazy trim（research R-06）；`isHealthy()` 触发一次 `repository.count()`
- [x] T030 [P] [US3] 实现 `MemoryProperties.serializeTags` / `deserializeTags` 工具方法：Jackson `ObjectMapper.writeValueAsString` / `readValue` 处理 JSON-as-TEXT（research R-02）；异常包 `MemoryException`
- [x] T031 [P] [US3] 创建 `memory_index` 表 V5 DDL `oryxos-storage/src/main/resources/db/migration/V5__add_memory_index.sql`：CREATE TABLE memory_index（含 local_id / mem0_id / scope / created_at / pending 字段 + 2 索引）—— 仅当 Mem0 后端激活时需要；与 V4 agent_memories **并列**（[data-model.md §6](./data-model.md)）
- [x] T032 [P] [US3] 创建 JPA Entity `MemoryEntryIndexEntity` `oryxos-memory/src/main/java/io/oryxos/memory/repository/MemoryEntryIndexEntity.java`：映射 `memory_index` 表；含 localId / mem0Id / scope / createdAt / pending 字段
- [x] T033 [P] [US3] 创建 Repository `MemoryEntryIndexRepository` `oryxos-memory/src/main/java/io/oryxos/memory/repository/MemoryEntryIndexRepository.java`：含 `findByLocalId` / `findByMem0Id` / `findByScope` / `findByScopeOrderByCreatedAtDesc` / `findRecent` / `deleteByScope` 方法
- [x] T034 [US3] 实现 `Mem0MemoryStore` `@Component("mem0MemoryStore")` `oryxos-memory/src/main/java/io/oryxos/memory/backend/Mem0MemoryStore.java`（[contracts/mem0-backend.md §1](./contracts/mem0-backend.md)）：构造器注入共享 `HttpClient`（005-tool-system T010 装配）+ ObjectMapper + `@Value("${oryxos.memory.mem0.base-url:http://localhost:8000}")` + `@Value("${oryxos.memory.mem0.timeout-seconds:5}")` + MemoryEntryIndexRepository；实现 6 个方法（save/recallByKeyword/recallByScope/delete/clear/isHealthy）；不可达容错（research R-03）—— save 落 pending=true 行 + recall 降级到本地 memory_index 快照
- [x] T035 [US3] 更新 `MemoryBackendSelector`（[plan.md §4.2](./plan.md)）：添加 `@Qualifier` 注解区分三个 Bean（markdownMemoryStore / sqliteMemoryStore / mem0MemoryStore）；`select(backendName)` switch-case 按 backend 名字返回对应实现
- [x] T036 [P] [US3] 集成测试 `oryxos-memory/src/test/java/io/oryxos/memory/backend/integration/BackendSwitchIT.java`：场景 3（[quickstart.md §场景 3](./quickstart.md)）：跑 migrate-markdown-to-sqlite.sh（[migration-scripts.md §2](./contracts/migration-scripts.md)）→ SQLite agent_memories 含 22 行；改 Profile `memo.backend=sqlite` 重启 → recallByKeyword 命中迁移后的数据（SC-004）；再切到 `memo.backend=mem0` + WireMock → save 成功 + memory_index 含 1 行
- [x] T036a [P] [US3] 0 内核修改切换后端契约测试（SC-010）：集成测试 `oryxos-memory/src/test/java/io/oryxos/memory/backend/integration/BackendSwitchZeroKernelChangeIT.java`：git checkout `006-memory-layer` HEAD → 在 `oryxos-core/` 与 `oryxos-tool/` 下跑 `git diff --stat HEAD` → 断言 0 行变更；切换 Profile `memo.backend: sqlite` ↔ `memo.backend: mem0` 仅触发 `oryxos-memory/` + `oryxos-boot/` + Profile YAML 三处变更；业务 ReAct 循环与 Tool 抽象代码不变
- [x] T036b [P] [US3] 合规审计还原集成测试（SC-011）：脚本 `scripts/memory-audit-restore-test.sh`：① SqliteMemoryStore 后端 → `sqlite3 .oryxos/oryxos.db "SELECT id, scope, content, tags, source, created_at FROM agent_memories;"` dump 全字段 → 写回 Markdown 文件 → 与原始 MEMORY.md `diff` 为空；② Mem0MemoryStore 后端（WireMock）→ 验证 `memory_index` 表含 `(local_id, mem0_id, scope, created_at, pending)` 5 列完整映射 → 关联 Mem0 服务端 GET /memories/{id} → 内容字节级匹配

**Checkpoint**：至此 SQLite + Mem0 两个新后端 + 后端选择器 + SC-010 0 内核修改 + SC-011 审计还原全部就位；User Story 3 满足 SC-004 + SC-010 + SC-011 + FR-014 + FR-015 + 10 条 C-SQ + 10 条 C-M0 条款。

---

## Phase 6: User Story 4 — Scope 显式隔离：核心区永不被截断（P2）

**Goal**：验证三后端 `core` 永不被 trim + `archive` 在 SQLite 后端 lazy trim + `clear(core)` 三后端统一拒绝

**Independent Test**：场景 4（[quickstart.md §场景 4](./quickstart.md)）：N=1500 条 `scope=core` 写入 → 重启 → recall 全部 1500 条（SC-003）；N=1500 条 `scope=archive` + `archiveMaxEntries=1000` → 写入后 DB 仅 1000 行（C-SQ-02）

### Tests for User Story 4 ⚠️

- [x] T037 [P] [US4] 契约测试 `oryxos-memory/src/test/java/io/oryxos/memory/ScopeContractTest.java`（覆盖三后端）：每个后端实现都验 `core` 永不 trim + `clear(core)` 抛 IllegalStateException + `archive` 在 SQLite 后端 lazy trim（research R-05 / R-06）
- [x] T038 [P] [US4] 契约测试 `oryxos-memory/src/test/java/io/oryxos/memory/ScopeHardConstraintTest.java`：验证 MemoryProperties 启动期 fail-fast —— 任何后端实现 `LongTermMemoryStore.clear(core)` 必须抛 IllegalStateException（C-LT-05）；该契约是 [CLAUDE.md §9.6](../CLAUDE.md) 第 ② 条的硬约束

### Implementation for User Story 4

- [x] T039 [US4] 验证 `SqliteMemoryStore.trimArchive` 实现（[data-model.md §3.4](./data-model.md) + [contracts/sqlite-backend.md §1](./contracts/sqlite-backend.md)）：`save(archive)` 后若 `count > archiveMaxEntries` 则调 `repository.trimArchive(archiveMaxEntries)`；同一 `@Transactional` 内（C-SQ-08 数据一致性）
- [x] T040 [US4] 验证 `MarkdownMemoryStore` 不主动 trim archive（research R-06）：Markdown 后端 `save(archive)` MUST NOT 触发任何 trim / delete；测试 save(archive) 1500 条无 trim（C-MD-09）
- [x] T041 [P] [US4] 集成测试 `oryxos-memory/src/test/java/io/oryxos/memory/integration/ScopeIsolationIT.java`：场景 4（[quickstart.md §场景 4](./quickstart.md)）：三后端 × 2 scope × 1500 条写入 → 验证 core 全保留 + archive 按 maxEntries 裁剪；调用 `memoryService.clear(MemoryScope.core)` → 抛 IllegalStateException（SC-003 / FR-009 / FR-010 / C-LT-05）

**Checkpoint**：至此 Scope 显式隔离 + core 永不被截断契约 + archive 容量上限裁剪在三后端全部就位；User Story 4 满足 SC-003 + FR-009 + FR-010。

---

## Phase 7: User Story 5 — Memory Tools 接入 ReAct 循环（P2）

**Goal**：验证 `save_memory` / `recall_memory` 两个 Tool 走既有 `DefaultToolExecutor` 派发路径 + `tool_invocations` 100% 审计 + `MemoryException` → `ToolResult.error(...)` 转换不抛异常到 ReAct 主循环

**Independent Test**：场景 5（[quickstart.md §场景 5](./quickstart.md)）：Profile 配 `tools: [save_memory, recall_memory]` → 跑 chat 让 LLM 在 ReAct 循环中 save + recall → 查 `tool_invocations` 含 2 行 source='builtin'；模拟 IO 错误 → ToolResult.success=false + errorMessage 不含 stack trace（SC-005 / SC-006）

### Tests for User Story 5 ⚠️

- [ ] T042 [P] [US5] 验证 `SaveMemoryToolTest` `oryxos-tool/src/test/java/io/oryxos/tool/memory/SaveMemoryToolTest.java`（005-tool-system 已有）：save 成功 + scope 校验 + MemoryService-failure 三场景（[005-tool-system spec FR-008](../005-tool-system/spec.md)）
- [ ] T043 [P] [US5] 验证 `RecallMemoryToolTest` `oryxos-tool/src/test/java/io/oryxos/tool/memory/RecallMemoryToolTest.java`（005-tool-system 已有）：recall 成功 + 无命中 + topK 上限三场景
- [ ] T044 [P] [US5] 契约测试 `oryxos-memory/src/test/java/io/oryxos/memory/integration/MemoryExceptionTranslationTest.java`：MemoryException → ToolResult.error(...) 转换 + errorMessage 不含 stack trace（C-MS-08 / NFR-004）

### Implementation for User Story 5

- [ ] T045 [US5] 验证 `SaveMemoryTool` `oryxos-tool/src/main/java/io/oryxos/tool/memory/SaveMemoryTool.java`（005-tool-system 已有，spec FR-011）：构造器注入 MemoryService；execute(args) 解析 scope/content/tags → 调 `memoryService.save(scope, content, tags)`；捕获 MemoryException → ToolResult.error(`memory save failed: ${msg}`) 不含 stack trace
- [ ] T046 [US5] 验证 `RecallMemoryTool` `oryxos-tool/src/main/java/io/oryxos/tool/memory/RecallMemoryTool.java`（005-tool-system 已有，spec FR-011）：execute(args) 解析 query/topK/scopeFilter → 调 `memoryService.recallByKeyword(query, topK, scopeFilter)`；返回 `MemoryToolResult("recall", scope, hits.size(), snippets)`
- [ ] T047 [P] [US5] 集成测试 `oryxos-tool/src/test/java/io/oryxos/tool/memory/integration/MemoryToolInReActIT.java`：场景 5（[quickstart.md §场景 5](./quickstart.md)）：mock LLM 返回 tool_call=`recall_memory` → Agent 循环走 Tool 执行 → 查 `tool_invocations` 含 1 行 source='builtin' success=true；模拟 IO 错误 → tool_invocations 含 1 行 success=false + errorMessage 不含 stack trace（SC-005 / SC-006）

**Checkpoint**：至此 Memory Tool 完全接入既有 Tool 体系 + 审计 + 异常兜底全部就位；User Story 5 满足 SC-005 + FR-011/FR-012/FR-013 + 005-tool-system 继承。

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**：迁移脚本 + 端到端冒烟 + 全场景验证 + 最终分析 + per-US 提交

- [ ] T048 创建 `scripts/migrate-markdown-to-sqlite.sh`（[contracts/migration-scripts.md §2](./contracts/migration-scripts.md)）：Markdown → SQLite 一次性迁移；幂等（重复跑 DB 行数不变）；行数校验；exit code 0/1/2/3；UTF-8 编码
- [ ] T049 创建 `scripts/migrate-sqlite-to-markdown.sh`（[contracts/migration-scripts.md §3](./contracts/migration-scripts.md)）：SQLite → Markdown 反向迁移；幂等；保留 SQLite 表
- [ ] T050 创建 `scripts/memory-smoke.sh`（[quickstart.md §端到端冒烟脚本](./quickstart.md)）：编排 5 场景（CrossSession / MarkdownBackend / BackendSwitch / ScopeIsolation / ReActIntegration）使用 WireMock + tmp 目录；输出 PASS/FAIL 汇总
- [ ] T051 [P] 创建 `scripts/test-write-1500-records.sh`（[quickstart.md §场景 4](./quickstart.md)）：批量写 1500 条 core + 1500 条 archive；用于 SC-003 自动化验证
- [ ] T052 [P] 创建 `scripts/test-cross-session-memory.sh`（[quickstart.md §场景 1](./quickstart.md)）：两次独立进程 save + recall；用于 SC-002 100% 跨 Session 召回验证
- [ ] T053 [P] 验证 Memory Tool 调用 100% 写入 `tool_invocations` 审计行（cross-cutting）：集成测试 `oryxos-memory/src/test/java/io/oryxos/memory/integration/AuditConsistencyIT.java`：N=20 次 save_memory + recall_memory 调用 → `tool_invocations` 增 20 行；`source='builtin'` 全命中（SC-005）
- [ ] T054 [P] 验证 NO duplicate Tool execution（FR-007 / SC-009）：集成测试 `oryxos-tool/src/test/java/io/oryxos/tool/memory/integration/NoDuplicateMemoryToolIT.java`：save_memory 调 1 次 → `tool_invocations` 中 (tool_name='save_memory', session_id, args) 唯一键 EXACTLY 1 行
- [ ] T055 [P] 验证 Tool errorMessage 0% 含 stack trace（NFR-004 / SC-006）：JUnit 测试 `oryxos-tool/src/test/java/io/oryxos/tool/memory/MemoryToolErrorMessageTest.java`：模拟 IOException → 解析 ToolResult.errorMessage → 断言不含 `at io.oryxos.*` 或 `Exception:` 模式
- [ ] T056 性能基准测试 `oryxos-memory/src/test/java/io/oryxos/memory/MemoryPerformanceIT.java`：N=100 次 save + recallByKeyword → P95 ≤ 200ms（NFR-001 / SC-008）；按 MarkdownMemoryStore / SqliteMemoryStore / Mem0MemoryStore 分别报告
- [ ] T057 [P] 更新 `docs/`（或 CLAUDE.md §9.6）：最终 Memory 三层参考（MemoryService + 3 后端 + Scope 契约 + 迁移脚本）；cross-reference quickstart.md + contracts/
- [ ] T058 跑 `/speckit-analyze` 对 spec.md + plan.md + tasks.md 验证无漂移（宪法合规、FR 覆盖、SC 覆盖）符合 [CLAUDE.md §10](../CLAUDE.md) "每个 US 完成后必须跑 /speckit.analyze"
- [ ] T059 跑 `mvn verify` 在所有 9 模块上并确认 0 失败（SC-007 baseline from 005 + 006）符合 [CLAUDE.md §17](../CLAUDE.md) git 协作约定
- [ ] T060 [P] 按 US 提交：`feat(006): <summary>` 约定（[CLAUDE.md §17](../CLAUDE.md)）—— 5 个 commit（US-1 / US-2 / US-3 / US-4 / US-5）+ Polish 1 个 commit（depends on `.specify/extensions.yml` `after_implement` hook）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖 —— 可立即开始
- **Foundational (Phase 2)**: 依赖 Setup 完成（T001—T004）—— **BLOCKS** 所有 US
- **User Stories (Phase 3—7)**: 全部依赖 Foundational 完成
  - **US-1 → US-2 依赖**：US-1 的 MemoryService 依赖 US-2 的 MarkdownMemoryStore 默认实现（T017 委派给 LongTermMemoryStore，T022 落地该实现）
  - **US-2 → US-3 依赖**：US-3 的 SqliteMemoryStore / Mem0MemoryStore 是 US-2 MarkdownMemoryStore 的"可插拔"路径；同 LongTermMemoryStore 接口
  - **US-1 / US-2 → US-4 依赖**：US-4 的 Scope 隔离测试需要三后端实现（US-2 + US-3）均已落地
  - **US-1 / US-2 → US-5 依赖**：US-5 的 Memory Tool 集成测试需要 MemoryService（US-1）已落地
- **Polish (Phase 8)**: 依赖所有目标 US 完成

### User Story Dependencies

```text
┌─────────────┐
│  Phase 1    │  T001—T004  Setup
└──────┬──────┘
       ▼
┌─────────────┐
│  Phase 2    │  T005—T013  Foundational (BLOCKS all US)
└──────┬──────┘
       ▼
┌─────────────┐
│  Phase 3    │  T014—T019  US-1 MemoryService 跨会话 (P1) 🎯 MVP
└──────┬──────┘
       ▼
┌─────────────┐
│  Phase 4    │  T020—T025  US-2 Markdown 默认后端 (P1)
└──────┬──────┘
       ▼
┌─────────────┐
│  Phase 5    │  T026—T036  US-3 SQLite + Mem0 可插拔 (P2)
└──────┬──────┘
       ▼
┌─────────────┐
│  Phase 6    │  T037—T041  US-4 Scope 显式隔离 (P2)
└──────┬──────┘
       ▼
┌─────────────┐
│  Phase 7    │  T042—T047  US-5 Memory Tool 接入 (P2)
└──────┬──────┘
       ▼
┌─────────────┐
│  Phase 8    │  T048—T060  Polish & smoke & analyze
└─────────────┘
```

- **User Story 1 (P1)**: Foundational 后可开始 —— 不依赖其他 US
- **User Story 2 (P1)**: Foundational 后可开始；与 US-1 并行（不同文件）；但 US-1 MemoryService 委派到 LongTermMemoryStore 默认 Markdown 实现 → US-2 MarkdownMemoryStore 应在 US-1 集成测试前落地（T022 优先于 T018）
- **User Story 3 (P2)**: Foundational + US-2 后可开始（V4 DDL 已落、MarkdownMemoryStore 已验证）
- **User Story 4 (P2)**: US-2 + US-3 后可开始（三后端全部就位才能跨后端测 Scope）
- **User Story 5 (P2)**: US-1 + US-2 后可开始（MemoryService + MarkdownMemoryStore 已落地，005-tool-system 既有 Tool 实现复用）

### Within Each User Story

- Tests（T014—T015 / T020—T021 / T026—T028 / T037—T038 / T042—T044）MUST 先写并 FAIL 再实现
- JPA Entity / Repository（T006 / T007）→ ConfigurationProperties（T008）→ MemoryService（T017）
- 后端 Properties（T008）→ 后端实现（T022 / T029 / T034）→ 集成测试
- MarkdownMemoryStore（T022）→ SqliteMemoryStore（T029）→ Mem0MemoryStore（T034）顺序：默认后端优先

### Parallel Opportunities

- **Phase 1**: T001 / T002 / T003 / T004 可并行（不同文件）
- **Phase 2**: 大多数任务可并行（T006 / T007 / T008 / T009 / T010 / T012 / T013）—— 7 个并行任务；T005 / T011 顺序依赖
- **Phase 3 (US-1)**: T014 / T015 合同测试可并行；T016 SessionManager 与 T017 DefaultMemoryService 顺序（T017 依赖 T016）；T018 / T019 集成测试可并行
- **Phase 4 (US-2)**: T020 / T021 合同测试可并行；T022 / T023 实现可并行；T024 / T025 集成测试 + 配置可并行
- **Phase 5 (US-3)**: T026 / T027 / T028 合同测试可并行；T029 SqliteMemoryStore 依赖 T007 / T030；T034 Mem0MemoryStore 依赖 T032 / T033 / T030；T031 V5 DDL 独立；T036 集成测试依赖所有实现
- **Phase 6 (US-4)**: T037 / T038 合同测试可并行；T039 / T040 / T041 顺序依赖 US-3 / US-2 实现
- **Phase 7 (US-5)**: T042 / T043 / T044 合同测试可并行；T045 / T046 / T047 顺序依赖合同测试
- **Phase 8**: T048 / T049 迁移脚本可并行；T050 smoke 脚本依赖 T051 / T052；T053 / T054 / T055 验证测试可并行；T056 / T057 / T058 / T059 / T060 各自独立

---

## Parallel Example: User Story 3 (US-3 MVP path)

```bash
# Phase 5 parallel group A: All contract tests written first (TDD)
Task: "T026 [P] [US3] SqliteMemoryStoreTest in oryxos-memory/src/test/java/io/oryxos/memory/backend/SqliteMemoryStoreTest.java"
Task: "T027 [P] [US3] Mem0MemoryStoreTest in oryxos-memory/src/test/java/io/oryxos/memory/backend/Mem0MemoryStoreTest.java"
Task: "T028 [P] [US3] MemoryBackendSelectorTest in oryxos-memory/src/test/java/io/oryxos/memory/MemoryBackendSelectorTest.java"

# Phase 5 parallel group B: Implementation foundations
Task: "T031 [P] [US3] V5 DDL memory_index in oryxos-storage/src/main/resources/db/migration/V5__add_memory_index.sql"
Task: "T032 [P] [US3] MemoryEntryIndexEntity in oryxos-memory/src/main/java/io/oryxos/memory/repository/MemoryEntryIndexEntity.java"
Task: "T033 [P] [US3] MemoryEntryIndexRepository in oryxos-memory/src/main/java/io/oryxos/memory/repository/MemoryEntryIndexRepository.java"
Task: "T030 [P] [US3] MemoryProperties serialize/deserialize methods in oryxos-memory/src/main/java/io/oryxos/memory/MemoryProperties.java"

# Phase 5 sequential: SqliteMemoryStore (depends on T007, T030)
Task: "T029 [US3] SqliteMemoryStore class in oryxos-memory/src/main/java/io/oryxos/memory/backend/SqliteMemoryStore.java"

# Phase 5 sequential: Mem0MemoryStore (depends on T032, T033, T030)
Task: "T034 [US3] Mem0MemoryStore class in oryxos-memory/src/main/java/io/oryxos/memory/backend/Mem0MemoryStore.java"

# Phase 5 final: Selector update + integration
Task: "T035 [US3] MemoryBackendSelector @Qualifier updates in oryxos-boot/src/main/java/io/oryxos/boot/config/MemoryBackendSelector.java"
Task: "T036 [P] [US3] BackendSwitchIT in oryxos-memory/src/test/java/io/oryxos/memory/backend/integration/BackendSwitchIT.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 + User Story 2 + User Story 4 = P1 核心契约)

P1 是 [CLAUDE.md §10](../CLAUDE.md) 与宪法 §VI 强调的核心治理能力。MVP 路径：

1. 完成 Phase 1：Setup（T001—T004）
2. 完成 Phase 2：Foundational（T005—T013）—— V4 DDL + JPA Entity + Repository + MemoryException + MemoryProperties + MemoryScope + MemoryEntry + LongTermMemoryStore 接口
3. 完成 Phase 3：User Story 1（T014—T019）—— MemoryService 门面 + 4 条核心契约码化
4. 完成 Phase 4：User Story 2（T020—T025）—— MarkdownMemoryStore 默认后端 + 文件结构
5. **STOP and VALIDATE**：
   - `mvn verify` 全绿
   - 场景 1 跑通（[quickstart.md §场景 1](./quickstart.md)）：跨 Session 召回 100%
   - 场景 2 跑通：Markdown 文件结构 + lenient recovery
6. Deploy/demo if ready（"每日科技日报"Demo 部分功能）

### Incremental Delivery

1. Setup + Foundational → 基础就绪
2. 加 US-1 → 独立测试 → 部署/Demo（**MVP P1!**）
3. 加 US-2 → 独立测试 → 部署/Demo（**MVP P1 完整**：4 条核心契约全部码化）
4. 加 US-3 → 独立测试 → 部署/Demo（可插拔后端：SQLite 结构化 + Mem0 语义检索）
5. 加 US-4 → 独立测试 → 部署/Demo（Scope 显式隔离 + core 永不被截断）
6. 加 US-5 → 独立测试 → 部署/Demo（Memory Tool 完全接入 ReAct）
7. Polish + smoke + analyze → 生产就绪 Memory 层

### Parallel Team Strategy

With multiple developers：

1. Team completes Phase 1 + Phase 2 together（T001—T013）
2. Foundational done 后：
   - Developer A：User Story 1（T014—T019）—— MemoryService 门面 + 4 条契约码化
   - Developer B：User Story 2（T020—T025）—— MarkdownMemoryStore + 文件结构
   - Developer A 与 B 在 T018 / T024 集成测试时汇合（US-1 依赖 US-2 的默认实现）
3. US-1 + US-2 done 后：
   - Developer A：User Story 3（T026—T036）—— SqliteMemoryStore + Mem0MemoryStore + Selector
   - Developer B：User Story 4（T037—T041）—— Scope 隔离跨后端测试
   - Developer C：User Story 5（T042—T047）—— Memory Tool 集成验证
4. Stories 完成并通过 ToolRegistry / LongTermMemoryStore / MemoryBackendSelector 独立集成

---

## Task Count Summary

| Phase | Story | Tasks | Parallelizable |
|-------|-------|-------|----------------|
| Phase 1 | Setup | T001—T004 (4) | T001, T002, T003, T004 |
| Phase 2 | Foundational | T005—T013 (9) | T006, T007, T008, T009, T010, T012, T013 |
| Phase 3 | US-1 MemoryService 跨会话 (P1) | T014—T019 (6) | T014, T015（测试）；T018, T019（集成） |
| Phase 4 | US-2 Markdown 默认后端 (P1) | T020—T025 (6) | T020, T021（测试）；T022, T023（实现）；T024, T025（验证） |
| Phase 5 | US-3 SQLite + Mem0 可插拔 (P2) | T026—T036 (11) | T026, T027, T028（测试）；T030, T031, T032, T033（基础设施） |
| Phase 6 | US-4 Scope 显式隔离 (P2) | T037—T041 (5) | T037, T038（测试） |
| Phase 7 | US-5 Memory Tool 接入 (P2) | T042—T047 (6) | T042, T043, T044（测试） |
| Phase 8 | Polish | T048—T060 (13) | T051, T052, T053, T054, T055, T057, T060 |
| **Total** | | **60 tasks** | **~55% parallelizable** |

---

## Coverage Matrix

| FR / SC | Tasks |
|---------|-------|
| FR-001 (MemoryService facade) | T017 |
| FR-002 (SessionManager) | T015, T016 |
| FR-003 (3 backends) | T022, T029, T034 |
| FR-004 (Markdown ## Core / ## Archive) | T022, T023, T024 |
| FR-005 (append + literal keyword) | T020, T022, T023 |
| FR-006 (recallByKeyword by createdAt DESC) | T014, T017, T029, T034 |
| FR-007 (no cache) | T014, T019 |
| FR-008 (scope explicit) | T012, T014, T017 |
| FR-009 (core never truncated) | T037, T038, T041 |
| FR-010 (archive maxEntries trim) | T026, T029, T039, T041 |
| FR-011 (Memory Tool = Tool 体系一等公民) | T045, T046 |
| FR-012 (tool_invocations audit) | T047, T053 |
| FR-013 (MemoryException → ToolResult.error) | T014, T044, T045 |
| FR-014 (SqliteMemoryStore + agent_memories) | T005, T006, T007, T029 |
| FR-015 (Mem0 HTTP + memory_index) | T031, T032, T033, T034 |
| NFR-001 (P95 ≤ 200ms) | T056 |
| NFR-002 (no cache) | T014, T019 |
| NFR-003 (Markdown no handle leak + atomic move) | T020, T022 |
| NFR-004 (no stack trace in errorMessage) | T014, T055 |
| NFR-005 (migration scripts) | T048, T049 |
| SC-001 (3 Demos) | T018, T050 |
| SC-002 (100% cross-session recall) | T018, T052 |
| SC-003 (core 1000 records intact) | T041, T051 |
| SC-004 (0 downtime backend switch) | T036, T048, T049 |
| SC-005 (100% audit) | T047, T053 |
| SC-006 (no stack trace) | T055 |
| SC-007 (mvn verify green) | T059 |
| SC-008 (P95 ≤ 200ms) | T056 |
| SC-009 (Mem0 degraded mode) | T027, T034 |
| SC-010 (0 内核修改切换 backend) | T036a |
| SC-011 (审计还原) | T036b |

---

## Notes

- **[P] tasks** = different files, no dependencies
- **[Story] label** maps task to specific user story for traceability
- **Each user story is independently completable and testable**
- **Tests MUST fail before implementing** (TDD per spec quickstart.md)
- **Commit after each task or logical group**; use `feat(006): <summary>` convention (per `.specify/extensions.yml` `after_implement` hook)
- **Stop at any checkpoint** to validate story independently (especially P1 MVP at end of Phase 4)
- **Avoid**: vague tasks, same-file conflicts, cross-story dependencies that break independence
- **Reminder**: V4 DDL must run in sequence on existing databases (or fresh init) — V1 → V2 → V3 (005) → V4 (006)
- **Reminder**: V5 DDL for memory_index is only required when Mem0 backend is used; can be deferred but recommended to ship together for zero-runtime-friction
- **Reminder**: Memory Tool implementations (`SaveMemoryTool` / `RecallMemoryTool`) are inherited from 005-tool-system spec — verify they still work after contract changes; if not, fix in this spec's tasks (T045 / T046)

---

## Suggested MVP Scope

**MVP = User Story 1 + User Story 2 + User Story 4 (all P1 核心契约)** = T001—T041 = **41 tasks**.

This delivers:
- `MemoryService` 三层门面 + 4 条核心契约码化（[CLAUDE.md §9.6](../CLAUDE.md)）
- `MarkdownMemoryStore` 默认后端 + 文件结构契约
- `Scope` 显式隔离 + `core` 永不被截断 + `archive` 容量上限裁剪
- `mvn verify` 全绿 + 跨 Session 召回 100% + Markdown 文件可见

**Defer to post-MVP**: US-3（SQLite + Mem0 可插拔后端）/ US-5（Memory Tool 接入验证）。这两部分是 P2 切换选项，核心阶段先保证基础设施就绪 + 默认 Markdown 能跑。