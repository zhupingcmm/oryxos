# Phase 0 研究：Memory 层（让 Agent 记得住事的可插拔记忆层）

**目的**：在 Phase 1 design 之前，把 [plan.md §2 Technical Context](./plan.md) 里所有未明确的决策点收敛成可执行项
**分支**：`006-memory-layer` | **日期**：2026-07-26 | **Spec**：[spec.md](./spec.md) | **Plan**：[plan.md](./plan.md)

> **研究方法**：本 spec 的所有决策点都已在 [CLAUDE.md](../CLAUDE.md)、[spec.md](./spec.md)、宪法 §VI 与 005-tool-system 既有架构中找到权威依据。本文档只把"散落在多处的依据收敛成单一决策记录"，**不**做新的外部技术选型调研（Mem0 / JPA / HikariCP / `HttpClient` 全部沿用既有栈）。

---

## R-01：SQLite 后端存储介质选择 — `agent_memories` 表 vs 同库 `sessions` 表扩字段

**Decision**：新增 `agent_memories` 表（与宪法 §VI 五张表**并列**），落同一 SQLite 数据库 `oryxos.db`。

**Rationale**：

- **不**走"在 `sessions` 表扩字段"路径 —— 会话与长期记忆是不同生命周期（会话结束不自动落库 vs 长期层跨会话存在），混用会破坏 §9.6 第 ② / ③ 条契约
- 新增表是宪法 §VI 的合规路径：宪法只要求"五张核心表 day-one"，**不**禁止新增表；宪法 "Additional Constraints" 第 3 条要求"不依赖 `hibernate.ddl-auto=update` 演进 schema"，本决策用 V4 手动 DDL 完全合规
- 与 sessions 同库 = 单 SQLite 文件 = 不引入额外存储介质 = 与宪法 §I 单 binary 部署一致

**Alternatives considered**：

- ❌ **独立 SQLite 文件 `memory.db`**：多文件管理复杂；事务不跨库；与宪法 §VI 单库路径不符
- ❌ **PostgreSQL / H2 等**：宪法 §VI 明确 SQLite；引入新依赖违反 §I 单栈原则
- ❌ **JSON 文件 + 全文索引**：Markdown 后端已在用；SQLite 后端要解决的是"结构化查询"问题，JSON 退回到 Markdown 水平

---

## R-02：`agent_memories` 表的 `tags` 字段存储格式

**Decision**：`tags TEXT` 列存 JSON 数组字符串（如 `["t1","t2"]`），查询时用 `LIKE '%"t1"%'` 子串匹配（前后双引号避免子串误命中）。

**Rationale**：

- SQLite 没有原生 array 类型；JSON-as-TEXT 是 SQLite 社区惯例（参考 [SQLite JSON1 extension](https://www.sqlite.org/json1.html)）
- `LIKE '%"t1"%'` 比 `JSON_EACH` + `LIKE` join 简单，且性能差距在小规模数据（N≤10⁴ tags / Agent）下可忽略
- `tags` 默认 `[]`（空 JSON 数组），NOT NULL，避免 NULL 边界

**Alternatives considered**：

- ❌ **逗号分隔字符串 `t1,t2`**：与 JSON 数组相比，可读性差、未来扩展性弱
- ❌ **`memory_tags` 独立表 + JOIN**：过规范化；对当前查询模式（"按单个 tag 子串命中"）是 N+1 性能问题；扩展阶段如有需要可加
- ❌ **SQLite JSON1 extension 的 `json_each` 展开查询**：实现复杂；性能优势在小规模数据下不明显

---

## R-03：Mem0 HTTP 客户端的"不可达"容错策略

**Decision**：Mem0 不可达时，**写**动作不抛异常，记录标记 `pending=true` 返回 `ToolResult.success=true, metadata={pending:true}`，由后台定时任务（5 分钟间隔）flush 重试；**读**动作先查本地映射表 `memory_index`，再调 Mem0 服务（两者均失败返回空 + ToolResult 含 `"memory backend degraded"` 警告）。

**Rationale**：

- 满足 spec 边界情况 4 "Mem0 不可达不阻塞 Agent 循环" 的硬要求
- "Tool 层 success=true + metadata pending" 让 LLM 在下一次循环可重试；与"save memory failed" 区分开（避免 LLM 误以为记忆没存）
- 后台 flush 由 `AgentScheduler` 复用（继承宪法 §VII "Demo-First" 的既有基础设施；不引入新调度器）

**Alternatives considered**：

- ❌ **Mem0 不可达时直接抛 `MemoryException`**：违反 spec FR-013 "不抛异常到 ReAct 主循环"
- ❌ **Mem0 不可达时切到 Markdown 本地降级**：自动切换后端带来"用户不知道当前用的是哪个后端"的不可观测性；扩展阶段可考虑
- ❌ **同步阻塞等待 Mem0 重连**：违反 spec NFR-001 "≤ 200ms wall-time"

---

## R-04：Markdown 后端的并发安全策略

**Decision**：`MarkdownMemoryStore.save` 用 `synchronized (fileLock)` 块串行化单 JVM 内并发写；写采用 `Files.writeString` + atomic rename 兜底（`Files.move(tmp, target, ATOMIC_MOVE)`）；**不**做跨进程文件锁。

**Rationale**：

- 单 Agent 单进程写是核心阶段假定（spec 边界情况 5）；核心阶段**不**需要跨进程锁
- `ATOMIC_MOVE` 防止写过程中进程崩溃导致 MEMORY.md 损坏（spec NFR-003）
- `synchronized` 比 `ReentrantLock` 简单；持有时间 < 50ms（Markdown 文件通常 ≤ 100 KB）

**Alternatives considered**：

- ❌ **`FileChannel` + `flock` / `fcntl`**：跨进程锁；增加 NFR 复杂度（Linux/Windows 平台差异）；核心阶段不需要
- ❌ **`WatchService` 监听外部修改**：spec 边界情况 1 已说明 "外部修改由 lenient recovery 兜底"，**不**引入监听复杂度
- ❌ **改为 `append-only` 文件 + 后台 compaction**：复杂度高；与宪法 §VII "跑通优先" 不符

---

## R-05：`core` 永不被截断契约的执行点

**Decision**：契约验证放在 `LongTermMemoryStore` 接口的 `save(scope=archive, ...)` 实现里（即 archive 容量上限裁剪**仅**作用于 archive scope）；`save(scope=core, ...)` 的实现**不**调用任何 trim / delete 逻辑。如果某个后端实现引入全局 trim（含 core 区），启动期 `@PostConstruct` 校验"后端是否声明实现核心契约"，不声明则 `IllegalStateException` 拒绝启动（spec FR-009 硬约束）。

**Rationale**：

- 把契约放在接口层（统一）而非分散到每个实现，避免某个后端"忘了实现契约"
- 启动期 fail-fast 比运行时 fail-silent 更安全；违反契约 = 直接拒启动，**不**让配置错误上线

**Alternatives considered**：

- ❌ **运行时检测**：用户已写入 core 区 1000 条后才报错 → 灾难（用户偏好已丢失）
- ❌ **`MemoryScope` 枚举加 `core_immutable` 标记**：增加枚举语义复杂度；不如接口契约清晰

---

## R-06：Markdown 后端的 archive 容量裁剪语义

**Decision**：Markdown 后端 **不**实现 archive 容量上限裁剪（保留所有 archive 记录）；仅在 SQLite 后端实现（`memo.archive.maxEntries` 配置，默认 1000），裁剪时机 = 每次 `save(scope=archive, ...)` 写入前检查 count，超出则 `DELETE FROM agent_memories WHERE scope='archive' ORDER BY created_at ASC LIMIT (count - maxEntries)`。

**Rationale**：

- Markdown 后端**不**做裁剪 = spec §"假设 10" / Markdown 后端"无上限"默认一致
- 裁剪时机 = 写入时（lazy trim on save）而不是后台调度 —— 简单；与"核心阶段不引入新调度器"一致
- Mem0 后端**不**做裁剪（由 Mem0 服务自身的 quota 控制；spec §"假设 2"）—— OryxOS 不替 Mem0 服务做决定

**Alternatives considered**：

- ❌ **后台定时任务裁剪**：引入新调度器；宪法 §VII "跑通优先" 不符
- ❌ **读时裁剪**：违反 spec FR-010 "裁剪 MUST 按 created_at ASC 最旧先裁"的字面要求（read 时裁剪是"挑最近 N 条留下"，与"裁最旧"语义相反）
- ❌ **Mem0 客户端兜底裁剪**：跨越服务边界；违背 "核心阶段不替 Mem0 决定"

---

## R-07：跨后端的 `MemoryEntry.id` 生成策略

**Decision**：所有后端的 `MemoryEntry.id` 在 `MemoryService.save` 入口用 `UUID.randomUUID().toString()` 生成；Mem0 后端拿到本地的 `id` 后用 `POST /memories` 的 `metadata.user_id` 字段回传，Mem0 服务返回 `id` 后存到本地 `memory_index.id` 映射表（spec FR-015）。

**Rationale**：

- `MemoryEntry.id` 是本地主键，三后端必须统一
- Mem0 服务自己有 `id`（全局唯一）；本地 `id` 与 Mem0 `id` 通过映射表 1:1 关联
- 业务方对 `MemoryService.delete(entryId)` 的调用：本地 `id` → 查映射表 → Mem0 `id` → `DELETE /memories/{mem0_id}`

**Alternatives considered**：

- ❌ **统一用 Mem0 的 id**：未配 Mem0 后端的 Agent 无法使用
- ❌ **统一用自增 INTEGER PRIMARY KEY**：跨后端无法对齐（SQLite 自增 ≠ Mem0 全局 id ≠ Markdown 文件 hash）
- ❌ **不带 id，全靠 `content + scope` 唯一键**：与 spec FR-001 "MemoryService 提供 delete(entryId)" 接口契约不符

---

## R-08：Profile YAML `memo.backend` 字段的绑定方式

**Decision**：在 `Profile` record 增 `memo: MemoConfig` 字段；`MemoConfig` 含 `backend: String`（默认 `"markdown"`） + `archiveMaxEntries: Integer?`（仅 SQLite 后端生效，默认 1000） + `mem0BaseUrl: String?`（仅 Mem0 后端生效，默认 `http://localhost:8000`）；运行时通过 `MemoryBackendSelector` Bean 按 Profile 选择对应后端实现（spec FR-003）。

**Rationale**：

- Profile YAML 字段直绑 → 业务方切换后端不改代码（spec FR-003 "可插拔"）
- 单一 `backend` 字段（`"markdown"` / `"sqlite"` / `"mem0"`）+ 字段级 nullable 配置 = YAML 简洁 + 后端参数可扩展
- `MemoryBackendSelector` 在 `oryxos-boot` 装配（继承 [CLAUDE.md §5 §V 边界澄清](../CLAUDE.md) "DI → boot"）

**Alternatives considered**：

- ❌ **每个后端一个独立 Profile 字段**：YAML 结构复杂；Profile 字段名与后端名耦合
- ❌ **`application.yaml` 全局配置 + Profile 覆盖**：违反宪法 §VI "Per-Profile Memory" 语义；不同 Agent 可用不同后端
- ❌ **Spring `@Profile("mem0")` 注解**：编译期决定，不能运行时切换；与 spec FR-003 "可插拔" 不符

---

## 备注

- **不引入新 Maven 模块**（宪法 §I）— 8 个决策全部落在既有 `oryxos-memory` / `oryxos-tool/memory/` / `oryxos-storage` / `oryxos-boot` / `scripts/`
- **不引入新第三方依赖**（宪法 §I）— Mem0 走 HTTP + Jackson JSON，无新增 `mcp-sdk-java` / `mem0-java-sdk`
- **不依赖 `hibernate.ddl-auto=update`**（宪法 "Additional Constraints" 第 3 条）— `agent_memories` 表走手动 V4 DDL（详见 [data-model.md §3](./data-model.md)）
- **不实现 Mem0 服务本身**（宪法 §II）— 仅实现 HTTP 客户端；Mem0 服务的 embedding 模型由业务方部署时配置