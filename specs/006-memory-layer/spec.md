# 功能规格说明书：Memory 层（让 Agent 记得住事的可插拔记忆层）

**特性分支**：`006-memory-layer`
**创建日期**：2026-07-26
**状态**：草稿
**输入**：用户描述："第22节需求：Memory——让 Agent 记得住事的可插拔记忆层。……（完整需求见第22节课件《Memory 实现与代码讲解》一、二部分）"

> **范围说明**：本 spec 覆盖 OryxOS 核心能力的第三项 ——「Memory 层」（[CLAUDE.md §6](../CLAUDE.md) 与宪法 §VI），把 Agent 从"每次会话失忆"升级为"能够跨会话记住关键事实"。具体而言包含三层架构（`MemoryService` 统一门面 + `SessionManager` 会话层 + `LongTermMemoryStore` 长期层）、三档可插拔后端（`MarkdownMemoryStore` 默认 / `SqliteMemoryStore` 结构化 / `Mem0MemoryStore` 自托管语义检索）、Scope 显式隔离（`core` 永不被截断 vs `archive`）、以及配套的 `save_memory` / `recall_memory` Tool 暴露给 LLM。
>
> **关于需求来源**：用户输入引用了外部课件《Memory 实现与代码讲解》第 22 节。该课件在当前会话不可直接读取；本 spec 的功能范围以 [CLAUDE.md §9.6](../CLAUDE.md)、宪法 §VI、已有的 `oryxos-memory` 模块（`MemoryService` / `MemoryEntry` / `MemoryScope` / `MarkdownMemoryStore` + 005-tool-system 已落地的 `SaveMemoryTool` / `RecallMemoryTool`）作为权威输入。任何与上述权威来源不一致的字段以权威来源为准，并在"假设"节标注。
>
> **关于本 spec 与已有代码**：005-tool-system 已实现 `save_memory` / `recall_memory` 两个 Tool 与 `MarkdownMemoryStore` / `MemoryService` 抽象契约；本 spec 的工作是把"Tool 已就绪 + 默认 Markdown 后端已落地"作为已知事实，**不重复**实现这些组件，专注于：①完整 `MemoryService` 三层门面契约（CLAUDE.md §9.6 的 4 条契约码化为 FR）；②两路长期层后端（`SqliteMemoryStore` 与 `Mem0MemoryStore`）的细节；③Scope `core`/`archive` 显式隔离；④"每日科技日报" Demo 跨对话记偏好的端到端验收。

---

## 用户场景与测试 *（必填）*

### 用户故事 1 — Agent 跨会话记住用户偏好（P1）🎯 MVP

企业用户跑"每日 GitHub 日报"Agent 一周，每次会话开头是"请汇总今天的 PR"。前几次 Agent 都要追问："你想看哪些仓库 / 哪些标签？" 几轮对话后用户懒得重复，Agent 必须能**跨会话**记住："用户偏好 PR 标签 = bug+enhancement、只看 zhupingcmm 组织的 PR" —— 用户不再需要重复告知。

**为什么是这个优先级**：跨会话记忆是企业级 Agent 的差异化能力。无记忆的 Agent 等价于每次重新培训的实习生；MVP 的核心是让 Agent 至少能记住**用户偏好**这种最朴素的事实。其他能力（语义检索、情景记忆）放扩展阶段。P1 一旦跑通，三个验收 Demo 之一"每日科技日报"（[CLAUDE.md §11](../CLAUDE.md)）才完整成立。

**独立测试**：跑 Agent 两次（两次 process / Session 都不同），第一次显式保存偏好 `save_memory(scope=core, content="用户偏好 PR 标签 = bug+enhancement")`，第二次启动新 Session 不传该偏好、直接问"汇总今天的 PR"。断言：(a) 第二次 Agent 在 ReAct 循环里调 `recall_memory(query="标签 偏好")` 能命中前次记录；(b) ToolResult 包含前次保存的内容；(c) `agent_memories` 表（默认 Markdown 后端的当前实现是 `.oryxos/memory/MEMORY.md`）确实有该记录。

**验收场景**：

1. **假设** Agent 在 Session S1 中调 `save_memory(scope=core, content="用户偏好 PR 标签 = bug+enhancement")`，**当** Session S1 结束且 Session S2 启动，**那么** Session S2 中 Agent 调 `recall_memory(query="PR 标签 偏好")` 能命中该条记录且 ToolResult 包含完整内容。
2. **假设** Agent 调 `save_memory(scope=archive)`，**当** 同一 Session 内调 `recall_memory(query=...)`，**那么** 也能命中（archive 在同 Session 同样可读，写时区分粒度由 Agent 决定）。
3. **假设** Agent 调 `save_memory(scope=core, content=...)` 超过 1000 条，**当** 检查后端存储（`.oryxos/memory/MEMORY.md` 或 SQLite 表），**那么** core 区**仍然**保留全部 1000 条；archive 区可能因 LLM/Admin 显式清理或容量上限裁剪。

---

### 用户故事 2 — 默认 Markdown 后端的本地文件存储（P1）

企业用户在单机开发 / 个人 Agent 场景下，使用默认的 `MarkdownMemoryStore` 后端 —— Agent 的长期记忆直接落本地 `.oryxos/memory/MEMORY.md` 文件（按 `## Core` 与 `## Archive` 两个一级 heading 分区），Agent 通过 `MemoryService` 门面写入读取，业务方**不**直接接触文件路径。

**为什么是这个优先级**：`MarkdownMemoryStore` 是宪法 §VI 要求"day-one 落库"的默认后端（CLAUDE.md §9.6 标注"默认"）；它提供最朴素的可读 / 可审计 / 可 git-diff 的存储介质。MVP 必备。零依赖（不需 DB schema 演进、无 vector index）。

**独立测试**：Profile 不配 `memo.backend`；调用 `MemoryService.save(scope=core, content="xxx")`；检查 `.oryxos/memory/MEMORY.md` 文件确实新增一行（`## Core` 分区下）；用文本编辑器打开文件可见明文。

**验收场景**：

1. **假设** Profile 未指定 memo.backend，默认 `MarkdownMemoryStore`，**当** `MemoryService.save(scope=core, content="...")` 被调用，**那么** 文件 `.oryxos/memory/MEMORY.md` 在 `## Core` 分区下追加一行包含 timestamp + content。
2. **假设** 同上但 `scope=archive`，**那么** 追加在 `## Archive` 分区下。
3. **假设** MEMORY.md 文件已存在且含 `## Core` / `## Archive` 两段，**当** 新内容追加，**那么** 已有的两段不被破坏，新内容追加至对应段尾。
4. **假设** MEMORY.md 文件不存在，**当** 首次 save，**那么** 文件被创建、含两段默认 heading、内容追加至对应段。

---

### 用户故事 3 — 可插拔后端：SQLite 结构化存储 + Mem0 自托管语义检索（P2）

企业用户希望 (a) 通过 Profile YAML 切换长期层后端——结构化查询场景选 `SqliteMemoryStore`（用同一 SQLite 数据库，增 `agent_memories` 表）；(b) 语义检索场景选 `Mem0MemoryStore`（自托管 Mem0 服务，零 OpenAI / 云依赖）。

**为什么是这个优先级**：宪法 §V "零代码 / 轻代码 / 重代码" 的精神同样适用于后端配置——业务方应能**配置切换**而非**改代码切换**。SQLite 后端面向"我要按标签查 / 按时间窗口查"的场景；Mem0 后端面向"我忘了我存过什么，只记得大意"的场景。两者都是 P2——核心阶段先保证基础设施就绪 + 默认 Markdown 能跑；扩展阶段才在生产部署里切到 SQLite 或 Mem0。

**独立测试**：Profile 设 `memo.backend=sqlite`；调用 `MemoryService.save(...)`；查询 `agent_memories` 表可见新行。Profile 改 `memo.backend=mem0` 重启；同样的 save 落到 Mem0 服务（用本地 mock Mem0 server 验证，WireMock stub）。

**验收场景**：

1. **假设** Profile `memo.backend=sqlite`，**当** `MemoryService.save(scope=core, content="...", tags=[t1,t2])` 被调用，**那么** SQLite `agent_memories` 表新增一行含 `scope` / `tags` / `content` / `created_at` 字段。
2. **假设** Profile `memo.backend=sqlite`，**当** `MemoryService.recallByKeyword(query="t1", topK=5)` 被调用，**那么** SQL `WHERE tags LIKE '%t1%'` 命中相关行，按 `created_at DESC` 排序返回前 5 条。
3. **假设** Profile `memo.backend=mem0`，且本地自托管 Mem0 服务在 `http://localhost:8000`，**当** `MemoryService.save(...)` 被调用，**那么** HTTP `POST /memories` 推送至该服务；返回的 `id` 被回写到本地映射表。
4. **假设** Profile `memo.backend=mem0`，**当** Mem0 服务不可达，**那么** `MemoryService.save` 不抛异常给调用方；HTTP 失败 → 静默 fallback 写入本地 `memory_index` 表（day-one 审计完整性兜底，宪法 §VI），Tool 层返回 `ToolResult.success=true`；HTTP 失败且 fallback 也失败时 Tool 层返回 `ToolResult.success=false, errorMessage="memory backend degraded"`（无 stack trace，NFR-004）。

---

### 用户故事 4 — Scope 显式隔离：核心区永不被截断（P2）

Agent / 业务方可以**显式**指定一条记忆该写在 `core` 还是 `archive`。`core` 是关键事实（用户偏好、合规约束、长期不变的事实），永远不被自动清理；`archive` 是流水性 / 短期 / 高频的事实，可被容量上限或显式清理动作裁剪。

**为什么是这个优先级**：宪法 §VI "Day-One Audit Persistence"暗含"关键事实不能丢"。如果 Agent 默认全写 archive 且 archive 被自动清理，用户偏好某天消失，Agent 退化成"每次重来的实习生"——这是 P0 灾难。把 scope 暴露给 Agent 显式调用是 CLAUDE.md §9.6 第 3 条契约的硬约束。

**独立测试**：连续写 1500 条 `scope=core`；写完后重启 Agent；调 `recallByKeyword` 仍能命中全部 1500 条。对比：连续写 1500 条 `scope=archive`；配置 `archive.maxEntries=1000`；写完后调 recall 只能拿到最新 1000 条。

**验收场景**：

1. **假设** `MemoryScope` 枚举 = `{"core", "archive"}`，**当** Agent 调 `save_memory(scope=core, ...)`，**那么** 该记录只在 `core` 区可见，**永不**被自动清理或截断。
2. **假设** `MemoryScope=archive` + 配置 `memo.archive.maxEntries=1000`，**当** 已写 1500 条 archive，**那么** recall 只返回最新 1000 条；最早的 500 条被裁剪（裁剪时机：写入时按容量上限 trim）。
3. **假设** Markdown 后端，**当** 同一 record 在 `core` 段，**那么** 该记录 append 时间戳 + scope=core 标签；不混入 `## Archive` 段。
4. **假设** SQLite 后端，**当** `agent_memories` 表 `scope` 列做 CHECK 约束只接受 `'core' | 'archive'`，**那么** 非法 scope（如 `'CACHE'`、`null`）拒绝入库。

---

### 用户故事 5 — Memory Tools 接入 ReAct 循环（P2）

Agent 通过 `save_memory` / `recall_memory` 两个 Tool 调用 `MemoryService`，从而在 ReAct 循环的 reasoning step 内"先存再思"或"先记再答"，把"记忆"作为 Tool 体系的一等公民——和已有 9 个 Tool 走完全相同的 `DefaultToolExecutor` 派发 + `tool_invocations` 审计路径。

**为什么是这个优先级**：宪法 §V 把所有 Tool 抽象放在 `oryxos-tool` 路径上；Memory Tool 不应该绕过 Tool 体系走旁路。LLM 通过 `recall_memory` 主动调取；LLM 通过 `save_memory` 主动沉淀。这与"每日科技日报"Demo（[CLAUDE.md §11](../CLAUDE.md)）的"跨对话记偏好"能力直接挂钩——LLM 在循环里 save / recall。**对齐** 005-tool-system 既有 `SaveMemoryTool` / `RecallMemoryTool`。

**独立测试**：Profile 配 `tools: [save_memory, recall_memory]`；mock LLM 返回一轮响应含 tool_call=`recall_memory(query="...")`；Agent 循环走到 Tool 执行那一步；断言 ToolResult 包含 MemoryService 返回的内容；`tool_invocations` 写一行 `tool_name='recall_memory', source='builtin', success=true`。

**验收场景**：

1. **假设** Profile 配 `tools: [save_memory, recall_memory]`，且 MEMORY.md 已含一些 core 区记录，**当** LLM 在 ReAct 循环中调 `recall_memory(query="...")`，**那么** ToolResult 包含命中记录的列表（每条含 content + scope + timestamp），**并且** `tool_invocations` 写入 1 行 `success=true`。
2. **假设** Profile 配 `tools: [save_memory]`，**当** LLM 调 `save_memory(scope=core, content="...", tags=[...])`，**那么** ToolResult.success=true，content 字段包含"已写入 X 条记录"计数；record 落 `core` 区且**永不**被截断。
3. **假设** Profile **未**配 `tools: [recall_memory]` 等记忆 Tool，**当** Agent 启动，**那么** 记忆 Tool 不出现在 Tool 列表（[CLAUDE.md §9.6](../CLAUDE.md) "由 Agent 经 scope 显式指定" + 005-tool-system 既有白名单机制）——LLM 调不到但 Profile 仍可在 prompt 里以"自然语言告诉 Agent 长期记忆的位置"作为最低限度兜底。
4. **假设** `MemoryService.save` 写入过程中 IO 错误（如磁盘满），**当** Tool 层捕获，**那么** ToolResult.success=false + errorMessage 形如 `memory save failed: <错误原因>`，**并且** `tool_invocations` 写 `success=false` 行（审计 day-one，[CLAUDE.md §13](../CLAUDE.md)）。

---

## 边界情况

- **MEMORY.md 文件被外部进程修改**（业务方手动编辑 / `git pull` 拉取新版）：下次 save 仍按时间戳顺序追加；不破坏已有结构。若外部删除了 `## Core` 段，下次 save 会重建该段（lenient recovery）。**不**做文件内容冲突检测（不进入合并状态机）。
- **核心区记录膨胀**：写入超过 1000 条**仍不截断**；读取仍 O(N) 遍历关键字匹配；性能瓶颈放扩展阶段用 SqliteMemoryStore / Mem0MemoryStore 解决。核心阶段 Markdown 后端设计上**接受** N=1000 量级的线性扫描。
- **SQLite 后端数据库文件被外部进程占用**：启动期用 `SQLITE_BUSY` 重试 3 次（每次 200ms）；3 次后仍 busy → 启动失败 + 明确报错（与 MCP 不可达一致，fail-fast）。
- **Mem0 自托管服务不可达**（US-3 场景 4）：写动作不阻塞 Agent 循环；HTTP 失败 → 静默 fallback 写入本地 `memory_index` 表（day-one 审计完整性兜底，宪法 §VI），Tool 层返回 `ToolResult.success=true`；仅在 HTTP + fallback 双失败时 Tool 层返回 `ToolResult.success=false, errorMessage="memory backend degraded"`（无 stack trace，NFR-004）。读取走本地映射表的最近一次成功快照 + Mem0 客户端 `GET /memories?query=...` 实时查询；两者都失败时返回空 + ToolResult 含 `"memory backend degraded"` 警告。
- **两个不同 Agent 共享同一个 MEMORY.md 文件**：当前 Markdown 后端**不**做并发安全（写多线程会 race）。属于扩展阶段的并发安全边界——核心阶段假定单 Agent 单进程写入。代码路径加 synchronized 块；允许多读单写。
- **`recallByKeyword` 的 `query` 含特殊字符**（如 SQL LIKE 通配符 `%`、`_`）：Markdown 后端做字面匹配；SQLite 后端做参数化 `LIKE` 查询（自动转义 `%`/`_`）。Mem0 后端调 Mem0 服务的全文检索接口。
- **Tool 调用期间 Profile 切换 / MemoryService 重启**：调用返回的 `ToolResult` 已被 ReAct 捕获；后续重启不破坏已完成轮次的 session 对话历史（与宪法 §VI "Session JSON 持久化"对接）。
- **长期记忆中出现与系统指令（Bootstrap / SOUL.md / USER.md）冲突的事实**：本阶段**不**做冲突检测；以"事实为准"为唯一可信来源。冲突由 LLM 在 ReAct 循环里判断。扩展阶段可加 `priority` 字段。
- **同一 record 在不同 Session 多次 save**（去重）：当前实现**不**做去重；相同 content 多次保存产生多条记录，依靠 keyword recall 排序（最新优先）兜底。Mem0 后端自动去重（Mem0 服务内嵌）。

---

## 需求 *（必填）*

### 功能需求

- **FR-001**：系统 MUST 提供 `MemoryService` 统一门面（位于 `oryxos-memory` 模块），对 ReAct 暴露 `save` / `recallByKeyword` / `recallByScope` / `delete` / `clear(scope)` 五类操作；该门面 MUST 内部委派给 `SessionManager`（会话层）+ `LongTermMemoryStore`（长期层）两路实现。`clear(CORE)` MUST 抛 `IllegalStateException`（与 `LongTermMemoryStore` 接口契约一致，[CLAUDE.md §9.6](../CLAUDE.md) 契约 ②）。
- **FR-002**：会话层 `SessionManager` MUST 维护**当前 Session 内**的所有对话消息 + Agent 临时状态；存储路径用宪法 §VI 既有的 `sessions` 表（不新增表）；Session 结束**不**自动写入长期层。
- **FR-003**：长期层 MUST 通过 `LongTermMemoryStore` 接口（`save` / `recallByKeyword` / `recallByScope` / `delete` / `clear(scope)` 五方法，与 `MemoryService` 一一对应）暴露给 `MemoryService`；接口实现 MUST 可插拔（3 个 builtin 实现：`MarkdownMemoryStore` / `SqliteMemoryStore` / `Mem0MemoryStore`），由 Profile YAML `memo.backend` 字段选择。
- **FR-004**：默认长期层实现 MUST 是 `MarkdownMemoryStore`，落 `.oryxos/memory/MEMORY.md` 文件；该文件 MUST 含 `## Core`（核心区，永不被截断）+ `## Archive`（归档区，可被容量上限裁剪）两个一级 heading。
- **FR-005**：`MarkdownMemoryStore` 写入 MUST 按**追加方式**（不重写已有内容）；读 MUST 按字面 keyword 匹配（不引入正则）；对空 query MUST 返回空集合（不抛异常）。
- **FR-006**：`MemoryService.recallByKeyword(query, topK, scopeFilter?)` MUST 在指定 scopeFilter（默认不限定）下，按 `keyword` 子串匹配 `content` 字段、按 `created_at DESC` 排序、返回前 `topK` 条；该方法的 backend 实现细节 MUST 不暴露给调用方。
- **FR-007**：长期层 MUST 不引入任何 cache 层（[CLAUDE.md §9.6](../CLAUDE.md) 契约 ①）；每次 `recallByKeyword` 调用 MUST 直接读底层（MarkDown 文件 IO / SQLite `SELECT` / Mem0 `GET /memories`），不缓存前次结果。
- **FR-008**：`MemoryScope` 枚举 MUST 含 `{"core", "archive"}` 两值；Agent 调 `save` 时 MUST 显式传 `scope` 参数；`scope == null` MUST 抛 `IllegalArgumentException`（[contracts/memory-service.md §C-MS-03](./contracts/memory-service.md)）。**不允许**"忘了传 scope 就落到某个默认值"的隐式行为——本 spec 不为 `scope` 提供默认值（[CLAUDE.md §9.6](../CLAUDE.md) 契约 ③）。
- **FR-009**：`core` scope 记录 MUST 永不被截断 / 自动清理 / 容量上限裁剪（[CLAUDE.md §9.6](../CLAUDE.md) 契约 ②）；违反此契约的 backend 实现 MUST 拒绝启动。
- **FR-010**：`archive` scope 记录 MUST 支持容量上限裁剪，由后端实现控制（Markdown 默认无上限；SQLite 配置 `memo.archive.maxEntries=N`；Mem0 由服务的 quota 控制）；裁剪 MUST 按 `created_at ASC`（最旧先裁）执行。
- **FR-011**：Memory 相关的 Tool（`save_memory` / `recall_memory`）MUST 落在 `oryxos-tool` 模块，与 [005-tool-system spec FR-013](../005-tool-system/spec.md) 共用 Tool 抽象边界；本 spec **不**重新设计 Tool 抽象，仅"确认 005 的两个 Tool 与本 spec 的 MemoryService 集成符合契约"。
- **FR-012**：Memory Tool 的调用审计 MUST 写入 `tool_invocations` 表（`tool_name='save_memory'|'recall_memory'`、`source='builtin'`、`success=true|false`），与 [宪法 §VI](../CLAUDE.md) + 005-tool-system SC-002 一致。
- **FR-013**：`MemoryService` MUST 捕获底层 IO 异常（磁盘满 / SQLite busy / Mem0 不可达），转 `MemoryException`（RuntimeException 子类）；Tool 层 MUST 把 `MemoryException` 转 `ToolResult.error(...)`，形如 `memory save failed: <错误原因>`，**不**抛异常到 ReAct 主循环（FR-012 同源）。
- **FR-014**：SQLite 后端 `SqliteMemoryStore` MUST 把记录落在**既有** SQLite 数据库（与宪法 §VI 五张表同库）的 `agent_memories` 表；MUST 用 V4 DDL 手动迁移（`ALTER TABLE` 增加该表，继承宪法"不依赖 `hibernate.ddl-auto=update`"硬约束）。
- **FR-015**：Mem0 后端 `Mem0MemoryStore` MUST 通过 HTTP `POST/GET` JSON 调用自托管 Mem0 服务（默认 `http://localhost:8000`）；MUST 把 Mem0 返回的 `id` 与本地 `session_id` + timestamp 关联到本地映射表（`memory_index` SQLite 表），保证 Agent 重新加载时仍能找到 record。

### 非功能需求

- **NFR-001**：`MemoryService` 单次 `save` / `recallByKeyword` 的 wall-time MUST ≤ 200ms；**健康依赖场景** = 单后端本地 IO（H2 in-memory 模拟 SQLite / 本地文件 / 不含 Mem0 网络），Markdown 后端实测 O(file_size)；SQLite 后端走 JPA 同步客户端；Mem0 后端因依赖外部 HTTP 服务不纳入本指标（其 wall-time 由 NFR-001 的本地后端保证 + Mem0 客户端 SLA 共同决定）。
- **NFR-002**：`MemoryService` MUST 不持有任何进程级缓存 / 内存索引（[CLAUDE.md §9.6](../CLAUDE.md) 契约 ①）；每次 read MUST 直读后端，可保证"刚写的立刻能读到"（read-after-write 一致性）。
- **NFR-003**：默认 `MarkdownMemoryStore` 的文件 IO MUST 不持有文件句柄（每次 read/write 立即 close）；进程崩溃 MUST 不损坏 MEMORY.md（写采用 `Files.writeString` + atomic rename 兜底）。
- **NFR-004**：Memory 相关错误信息 MUST 对 LLM 友好（[CLAUDE.md §13](../CLAUDE.md) "ToolResult.errorMessage MUST 不含 stack trace"）；stack trace MUST 进 `.oryxos/logs/oryxos-cli-error.log`。
- **NFR-005**：Memory 后端切换 MUST 不破坏既有数据：Markdown → SQLite 切换 MUST 提供一次性迁移脚本（`scripts/migrate-markdown-to-sqlite.sh`），反向切换提供对称脚本；切换过程中业务方**不**感知停机。

### 关键实体

- **MemoryService**：统一门面。属性：`sessionManager: SessionManager`、`longTermStore: LongTermMemoryStore`。方法：`save(scope, content, tags?)`、`recallByKeyword(query, topK, scopeFilter?)`、`recallByScope(scope, topK)`、`delete(entryId)`、`clear(scope)`。
- **SessionManager**：会话层。属性：`sessionId: String`、`messages: List<Message>`。生命周期：跟随 Session。Session 结束**不**自动落长期层。
- **LongTermMemoryStore**：长期层接口。方法签名同 `MemoryService.save/recall*/delete`（接口与门面一一对应）。
- **MemoryEntry**：单条长期记忆记录。属性：`id: String`（UUID v4）、`scope: MemoryScope`、`content: String`、`tags: List<String>?`、`createdAt: Instant`、`source: String`（"core" | "archive"）。
- **MemoryScope**：枚举 = `core | archive`。`core` 永不被截断；`archive` 可被容量上限裁剪。
- **MarkdownMemoryStore**：默认实现。存储介质：`.oryxos/memory/MEMORY.md`。分两段：`## Core` / `## Archive`。无 tags 字段（Markdown 不便结构化）—— tags 在 Markdown 后端是 informational；查询走 content 子串匹配。
- **SqliteMemoryStore**：结构化实现。存储介质：与宪法 §VI 同库的 `agent_memories` 表。字段：`id` / `scope` / `content` / `tags TEXT`（JSON 数组）/ `created_at`。支持 `tags LIKE '%<tag>%'` 检索。
- **Mem0MemoryStore**：自托管语义检索实现。存储介质：Mem0 服务（默认 `http://localhost:8000`） + 本地 `memory_index` 映射表。`save` = `POST /memories`；`recallByKeyword` = `POST /memories/search`（Mem0 服务内部做 embedding 检索）。
- **agent_memories 行**：本 spec 新增的 SQLite 表（V4 DDL）。`id TEXT PRIMARY KEY` / `scope TEXT CHECK(scope IN ('core','archive'))` / `content TEXT NOT NULL` / `tags TEXT` (JSON) / `created_at INTEGER NOT NULL`。

---

## 成功标准 *（必填）*

### 可测量结果

- **SC-001**："每日科技日报"端到端能力由 `scripts/memory-smoke.sh`（6 场景）+ `MemoryAuditRestoreIT`（5 维审计还原）+ 跨 Session 召回 IT（N=100, 100%）共同覆盖；完整三 Demo（CLAUDE.md §11）放扩展阶段验证，本 spec 验收以 smoke + IT 全绿为准。
- **SC-002**：100% 跨 Session recall 命中：N=100 次 save（每次新 Session 重启进程）→ 后续 recall MUST 100% 命中；不允许 "save 之后重启进程就丢" 的丢失。
- **SC-003**：`core` 区 100% 永不被截断：连续写 1500 条 `scope=core` → 重启 → recall 全部 1500 条命中。
- **SC-004**：Memory 切换后端 MUST 0 业务中断：Profile 从 markdown 切换到 sqlite（或反之）→ 既有记录 100% 通过迁移脚本迁移成功；切换过程中 LLM ReAct 循环不报错。
- **SC-005**：`save_memory` / `recall_memory` Tool 调用 100% 写入 `tool_invocations` 审计行（`tool_name` / `source='builtin'` / `success`），与宪法 §VI + 005-tool-system SC-002 一致。
- **SC-006**：Memory 相关错误 0% 进入 LLM 上下文（= NFR-004 的可测断言）：ToolResult.errorMessage 不含 `at io.oryxos.*` / `Exception:` 模式；stack trace 100% 进 `.oryxos/logs/oryxos-cli-error.log`。
- **SC-007**：集成测试 100% 通过：`mvn verify` 全绿（继承 005-tool-system + 本 spec 新增 Memory 集成测试）。
- **SC-008**：单次 save/recall wall-time P95 ≤ 200ms（NFR-001）；Markdown 后端实测见 `MemoryBenchIT`。
- **SC-009**：Mem0 不可达场景下 `save` MUST 不阻塞 Agent 循环：HTTP 失败 → 静默 fallback 写入本地 `memory_index` 表（day-one 审计完整性兜底，宪法 §VI），ToolResult 返回 `success=true`；仅 fallback 失败时返回 `success=false` 含 `memory backend degraded` 警告（无 stack trace，NFR-004）。

### 业务结果

- **SC-010**：业务方能在不修改 OryxOS 内核的前提下切换 Memory 后端——通过 Profile YAML `memo.backend` 字段；切换不影响 ReAct 循环与 Tool 抽象。
- **SC-011**：企业合规/审计员能从 `agent_memories` 表（或 Mem0 服务 + `memory_index` 映射）完整还原"哪个 Agent / 哪个 Session / 哪条记忆 / 哪个 Scope / 什么时间"的全部长期记忆历史。

---

## 假设

1. **三层架构是 P1 的硬约束**（[CLAUDE.md §9.6](../CLAUDE.md)）；不允许把 Memory 简化成"Session 的扩展字段"。本 spec 严格区分 SessionManager 与 LongTermMemoryStore。
2. **核心阶段的默认后端是 `MarkdownMemoryStore`**（宪法 §VI + CLAUDE.md §9.6 "默认"）；`SqliteMemoryStore` 与 `Mem0MemoryStore` 是 P2 切换选项，不是 P1 默认。
3. **本 spec 不引入 vector 检索 / 语义检索原生后端**——`Mem0MemoryStore` 是接入已部署的 Mem0 服务，**不**实现 Mem0 服务本身。Mem0 服务的 embedding 模型由业务方配置；OryxOS 不内置 embedding 模型。
4. **`MemoryService` 不缓存**（[CLAUDE.md §9.6](../CLAUDE.md) 契约 ①）；该约束意味着每次 recall 都走完整 IO。对性能敏感的 Agent 应优先选 SqliteMemoryStore/Mem0MemoryStore（O(1) 索引 / O(log N) 语义检索）。
5. **`core` 永不被截断**（[CLAUDE.md §9.6](../CLAUDE.md) 契约 ②）是 hard constraint；扩展阶段的"compliance suite / 治理层"继承该契约，**不**允许引入 "core 也可裁剪" 的开关。
6. **写 core 还是 write archive 由 Agent 经 scope 显式指定**（[CLAUDE.md §9.6](../CLAUDE.md) 契约 ③）；`MemoryService.save` MUST 不允许"默认 archive"或"默认 core"+隐式行为。`scope` 参数必填。
7. **`recallByKeyword` 是关键词检索不做复杂化**（[CLAUDE.md §9.6](../CLAUDE.md) 契约 ④）：不走正则、不走 Lucene、不走 embedding。后端升级到 SqliteMemoryStore/Mem0MemoryStore 才出现 LIKE / 向量检索。
8. **Memory 相关的 Tool 已由 005-tool-system 实现**（`SaveMemoryTool` / `RecallMemoryTool` 在 `oryxos-tool/memory/`）；本 spec 不重写 Tool 抽象，只"确认既有实现符合本 spec FR-011/FR-012/FR-013"。任何 Tool 侧 fix 放 005-tool-system 后续维护。
9. **数据迁移**：Markdown ↔ SQLite 双向迁移脚本放 `scripts/`（与宪法 §I 单二进制部署兼容）。Mem0 后端的迁移由 Mem0 服务自身的 import/export 工具处理，OryxOS 不提供。
10. **不引入新的 Maven 模块**：Memory 层落在既有 `oryxos-memory` 模块；不改 9 模块边界（宪法 §I）。
11. **本 spec 的实现状态**：①MemoryService 三层门面 + 默认 Markdown 后端 + 两条 Memory Tool —— 已在 005-tool-system / 已有 `oryxos-memory` 模块**部分落地**（`MemoryService` / `MemoryEntry` / `MemoryScope` / `MarkdownMemoryStore` + `SaveMemoryTool` / `RecallMemoryTool`）；②SqliteMemoryStore 与 Mem0MemoryStore —— **待落地**；③迁移脚本 —— **待落地**；④"每日科技日报" Demo 端到端 —— **待落地**（[CLAUDE.md §11](../CLAUDE.md)）。本 spec 的 `tasks.md` 阶段按"已落地标记 / 待落地新增"组织。

---

## 不在范围内（Out of Scope）

- ❌ Vector 检索 / Embedding 模型内置 —— 扩展阶段；本阶段只接已部署的 Mem0 服务
- ❌ 情景记忆（episodic memory，按"上次做过什么"检索）—— 扩展阶段
- ❌ 长期记忆的事实冲突检测与优先级排序（与 Bootstrap / USER.md 冲突）—— 扩展阶段
- ❌ 长期记忆的自动压缩 / 摘要（防 core 区无限膨胀）—— 扩展阶段（宪法 §VI 契约 ② 写明 core 不截断，所以也没"压缩"——只能在写入端约束大小）
- ❌ 多 Agent 共享同一 long-term store 的并发安全（多写互斥）—— 扩展阶段；核心阶段假定单 Agent 单进程写
- ❌ Memory 后端性能监控（QPS / p95 latency / cache hit ratio 等指标）—— 扩展阶段（与 005 的 Tool 性能监控同源）
- ❌ Memory 的 RBAC / 租户隔离 —— 扩展阶段（与宪法 §II 多租户延后一致）
- ❌ Memory 内容的端到端加密（如合规要求不上明文）—— 扩展阶段
- ❌ Memory 存储的备份 / 跨节点同步 / 高可用 —— 扩展阶段
