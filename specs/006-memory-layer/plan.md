# 实施计划：Memory 层（让 Agent 记得住事的可插拔记忆层）

**分支**：`006-memory-layer` | **日期**：2026-07-26 | **Spec**：[spec.md](./spec.md)

**输入**：来自 [spec.md](./spec.md) 的功能规格说明。

> **关于 §3 Constitution Check 字段**：本文件 §3（Constitution Check）**完整保留**模板原样的字段占位（"Gates determined based on constitution file"），同时**新增** §3.1—§3.9 一节填写本特性的实际合规结论，避免与模板字段混淆。

---

## 1. Summary

Memory 层是 OryxOS 核心能力第三项 ——「三档 Memory」（[CLAUDE.md §10](../CLAUDE.md) 与宪法 §VI），把 Agent 从"每次会话失忆"升级为"能跨会话记住关键事实"。本 spec 在既有 `oryxos-memory` 模块（`MemoryService` / `MemoryEntry` / `MemoryScope` / `MarkdownMemoryStore` 部分落地于 005-tool-system 之前的早期迭代）与 005-tool-system 已落地的 `SaveMemoryTool` / `RecallMemoryTool` 之上，把 Memory 三层补齐到"完整视图"：

- **`MemoryService` 三层门面契约码化**（[CLAUDE.md §9.6](../CLAUDE.md) 的 4 条契约码化为 FR：①不缓存 ②core 永不被截断 ③scope 显式 ④keyword-only 检索）
- **两路新长期层后端**：`SqliteMemoryStore`（结构化查询 + 标签检索，`agent_memories` 表）+ `Mem0MemoryStore`（自托管 Mem0 服务，HTTP JSON-RPC 风格）
- **Scope 显式隔离**：`MemoryScope` 枚举 = `{core, archive}`；`core` 永不被截断；`archive` 可被容量上限裁剪（Markdown 默认无上限 / SQLite 配置 `memo.archive.maxEntries` / Mem0 由服务 quota 控制）
- **数据迁移脚本**：`scripts/migrate-markdown-to-sqlite.sh` + 反向脚本，切换后端 0 业务中断
- **"每日科技日报" Demo 端到端**：跨 Session 跑两次，验证 Agent 记住用户偏好

**核心 trade-off**：

1. `MarkdownMemoryStore` 已是默认后端（核心阶段 day-one 落地）；`SqliteMemoryStore` 与 `Mem0MemoryStore` 是 P2 切换选项，**不**与 Markdown 后端并行铺开。P1 MVP 仅依赖 Markdown 后端。
2. 本 spec 严格区分 `SessionManager`（会话层）与 `LongTermMemoryStore`（长期层）：**不**把 Session 扩字段当作长期层，**不**让"四层统一门面"变形为"两层混用"。
3. **不**实现 Mem0 服务本身 —— 仅实现 Mem0 的 HTTP 客户端；Mem0 服务的 embedding 模型与索引由业务方配置。

详见 [research.md](./research.md) 的 R-01..R-08 决策记录。

---

## 2. Technical Context

> 以下条目按 plan-template 字段填写。

**Language/Version**：Java 21（[CLAUDE.md §4](../CLAUDE.md) 强制）；records / sealed types / virtual threads / pattern matching 全部允许使用；**不**使用 preview 特性

**Primary Dependencies**：

- `oryxos-memory`（已存在；`MemoryService` / `MemoryEntry` / `MemoryScope` / `MarkdownMemoryStore` 抽象已落地；本 spec 新增 `SqliteMemoryStore` + `Mem0MemoryStore` 实现）
- `oryxos-tool`（已存在；`save_memory` / `recall_memory` 两个 Tool 在 005 落地于 `oryxos-tool/memory/`；本 spec **不**重写 Tool 抽象，只确认 Tool ↔ MemoryService 集成符合 FR-011/FR-012/FR-013）
- `oryxos-storage`（已存在；本 spec 新增 V4 DDL `agent_memories` 表）
- `oryxos-boot`（已存在；本 spec 新增 `MemoryProperties` / `MemoryBackendSelector` Bean 装配 + Profile `memo.backend` 字段绑定）
- `spring-data-jpa`（已存在；`agent_memories` 表的 Repository 走 JPA）
- `java.net.http.HttpClient`（JDK 21 内置；`Mem0MemoryStore` 用）
- `java.nio.file.Files`（JDK 内置；`MarkdownMemoryStore` 用）
- `com.fasterxml.jackson`（Spring Boot starter 自带；JSON 序列化 for Mem0 HTTP 响应）
- **不**新增 Maven 模块；**不**新增第三方依赖（Mem0 走 HTTP，无需 mcp-sdk-java 或 mem0-java-sdk）

**Storage**：SQLite via Spring Data JPA（沿用既有）；新增 `agent_memories` 表（[data-model.md §3](./data-model.md)）；`.oryxos/memory/MEMORY.md` 文件（MarkdownMemoryStore 默认后端，宪法 §VI 要求）

**Testing**：JUnit 5（沿用）；Mockito 单测；WireMock 集成测（Mem0 HTTP 后端 mock）；`scripts/memory-smoke.sh` 端到端冒烟

**Target Platform**：Linux server / Windows server（与 US-1 / US-2 / US-4 / US-5 一致；JDK 21 跨平台）

**Project Type**：library（`oryxos-memory` 模块作为 Spring bean 集合）+ embedded CLI/Web Service（ReAct 循环触发 `MemoryService`）；不属于独立 web 服务（[CLAUDE.md §5](../CLAUDE.md)）

**Performance Goals**：

- 单次 `MemoryService.save` / `recallByKeyword` wall-time P95 ≤ 200ms（spec NFR-001）
- 跨 Session 召回率 100%（spec SC-002）
- `core` 区 100% 永不被截断（spec SC-003）
- 后端切换 0 业务中断（spec SC-004）
- 三个 Demo 端到端跑通（spec SC-001）

**Constraints**：

- 仅 `MarkdownMemoryStore` / `SqliteMemoryStore` / `Mem0MemoryStore` 三个长期层实现（spec FR-003）
- 严格区分 `SessionManager`（会话层）与 `LongTermMemoryStore`（长期层）（spec FR-001/FR-002）
- `MemoryScope` 枚举只接受 `core` / `archive` 两值；`save` 时 scope 必填（spec FR-008）
- `core` 永不被截断（[CLAUDE.md §9.6](../CLAUDE.md) 契约 ②，违反该契约的后端 MUST 拒绝启动）
- Memory 错误走 `MemoryException` → `ToolResult.error(...)`，**不**抛异常到 ReAct 主循环（spec FR-013）
- Tool 错误信息不携带 stack trace，stack trace 100% 进 `.oryxos/logs/`（spec NFR-004 / [CLAUDE.md §13](../CLAUDE.md)）
- 单 binary fat JAR 部署（[CLAUDE.md §4](../CLAUDE.md)）
- **不**新增 Maven 模块（宪法 §I）
- **不**依赖 `hibernate.ddl-auto=update` 演进 schema；手动 V4 DDL 脚本（宪法 "Additional Constraints" 第 3 条）

**Scale/Scope**：本 spec 不引入新外部服务（Mem0 是已有部署，本 spec 仅做客户端）。Memory 调用频率跟随三个 Demo（每日 ≤ 100 次 / Agent × Profile 数量）；core 区数据量估算 N=1000 量级（Markdown 后端线性扫描，扩展阶段用 SQLite/Mem0 升级）。

---

## 3. Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

> **模板原字段**（保留）：Gates determined based on constitution file
>
> 模板期望本节列出"基于 constitution 文件派生的 Gates"。本 spec 把这一内容移到 §3.1—§3.9 九节，每节对应宪法 §I—§VII + 附加约束；模板字段本身不再赘述。

---

### 3.1 原则 I — Single-Stack Monolith（JDK 21 + Spring Boot 3.x）

✅ **合规**。

- JDK 21（Technical Context 已声明）
- Spring Boot 3.x（[CLAUDE.md §4](../CLAUDE.md)）
- 9 模块不动；本 spec **不**新增 Maven 模块，所有代码落在已有 `oryxos-memory` / `oryxos-tool/memory/` / `oryxos-storage` / `oryxos-boot` 之内
- 单 fat JAR 部署；`mvn -pl oryxos-boot -am package` 仍出单 JAR
- Memory 三个后端全部用 JDK 内置 API（`HttpClient` / `Files` / JPA），**不**新增第三方依赖

### 3.2 原则 II — Core-Stage Scope Discipline（五大核心能力优先）

✅ **合规**。

- Memory 层是 US-3「Memory」的完整视角（[CLAUDE.md §10](../CLAUDE.md) 第 3 能力）
- 不引入扩展阶段能力：向量检索 / 情景记忆 / 长期记忆的事实冲突检测 / 自动压缩 / 多 Agent 并发安全 / Memory 性能监控 / Memory 的 RBAC / 端到端加密 / 备份同步 —— 全部明确不在范围内（spec §"不在范围内"）
- `Mem0MemoryStore` 是接入已部署的 Mem0 服务（HTTP 客户端），**不**实现 Mem0 服务本身（避免 §II "不应在核心阶段做扩展阶段的事"）

### 3.3 原则 III — Self-Implemented ReAct Loop

✅ **合规**。

- 本 spec **不**修改 `ReActLoop`；只通过 `MemoryService` 门面扩展"记忆能力"作为既有 ReAct 循环的输入
- Memory Tool（`save_memory` / `recall_memory`）走既有 `DefaultToolExecutor` 派发路径，**不**改变 ReAct 主循环的最大迭代次数、消息语义、审计时机
- Memory 错误统一包成 `ToolResult.success=false`，不破坏 ReAct 主循环的"包错不抛错"语义

### 3.4 原则 IV — Spring AI Used at Half-Strength（禁用自动 tool 执行）

✅ **合规**。

- Memory 不参与 Spring AI 协议转换；`MemoryService` 是纯 Java 门面
- Memory Tool（`save_memory` / `recall_memory`）的 schema 生成走既有 `ToolSchemaProvider`（仅 schema 生成，005-tool-system spec FR-006）
- 工具派发由 `DefaultToolExecutor` 触发 → `ToolRegistry.find(name)` → `OryxTool.execute()`，**不**依赖 Spring AI 自动执行（继承 005-tool-system spec FR-007）
- 不会触发 tool 被调两次的已知坑（[CLAUDE.md §8.1](../CLAUDE.md)）

### 3.5 原则 V — Three-Tier Plugin Tooling

✅ **合规**。

- Memory 工具（`save_memory` / `recall_memory`）是 Tool 体系的一等公民（继承 005-tool-system spec FR-013）
- **模块归属**（[CLAUDE.md §5 §V 边界澄清](../CLAUDE.md)）：
  - Memory **抽象**（`MemoryService` / `SessionManager` / `LongTermMemoryStore` / `MemoryEntry` / `MemoryScope`）归 `oryxos-memory`
  - Memory **实现 + 基础设施**（`MarkdownMemoryStore` / `SqliteMemoryStore` / `Mem0MemoryStore` / `MemoryToolProperties` / `SaveMemoryTool` / `RecallMemoryTool`）归 `oryxos-memory` + `oryxos-tool/memory/`
  - DI 装配（`MemoryProperties` / `MemoryBackendSelector` / `Repository` / `HttpClient` Bean）归 `oryxos-boot`
- 不新增 Maven 模块；`AGENT.md` 加载仍归 `oryxos-core` 的 `ContextLoader`，**不**误把 Memory 当 Tool

### 3.6 原则 VI — SQLite + MEMORY.md with Day-One Audit Persistence

⚠️ **条件合规**（一处需注意）。

- `tool_invocations` 表是 day-one 表（[CLAUDE.md §13](../CLAUDE.md)）；Memory Tool 调用走既有审计路径
- 本 spec **新增** `agent_memories` 表（长期层 SQLite 后端的存储介质），与宪法 §VI 的五张表**并列** —— 五张表全保留，**不**删 `sessions` / `tool_invocations` / `llm_calls` / `scheduled_tasks` / `task_executions`
- 每次 Memory Tool 调用必产 1 行审计（spec FR-012，继承 005-tool-system spec FR-005）
- ⚠️ **DDL 演进路径**：依宪法 "Additional Constraints" 第 3 条，**不依赖** `hibernate.ddl-auto=update`；新增 `agent_memories` 表需要手动写 DDL 脚本（详见 [data-model.md §3](./data-model.md) 的 V4 migration）

### 3.7 原则 VII — Demo-First Delivery（跑通优先于完美）

✅ **合规**。

- 本 spec 的核心验收标准 = "每日科技日报" Demo 端到端跑通（spec SC-001）
- [quickstart.md](./quickstart.md) 给出可演示路径（两次独立 Session，验证跨会话记忆）
- 不追求完美（不引入向量索引 / 不引入异步刷新 / 不引入 Memory 性能监控）—— 与跑通优先一致

---

### 3.8 附加约束（"不要做的事"）

✅ **全部合规**。

- ❌ 不使用 `SecurityManager` — 本 spec 不涉及；继承既有 `WhitelistSandbox`（005-tool-system 落地）
- ❌ 不硬编码 API key — Profile YAML `memo.backend` 字段不包含 key；Mem0 服务地址走 `${MEM0_BASE_URL}` 占位（spec FR-015）
- ❌ 不依赖 `hibernate.ddl-auto=update` 演进 schema — 手动 V4 DDL 脚本（[data-model.md §3](./data-model.md)）
- ❌ 不扫描容器类型区分 Provider — 本 spec 不涉及 Provider
- ❌ **不把 Session 与 Memory 合并** — 严格区分 `SessionManager`（会话层）与 `LongTermMemoryStore`（长期层）；spec FR-001/FR-002 硬约束
- ❌ 不用非 JDK 21 特性 — records / virtual threads / sealed types 都是 JDK 21 stable；不使用 preview 特性

---

### 3.9 Constitution Check 总结

| 原则 | 状态 | 备注 |
| ---- | ---- | ---- |
| I. Single-Stack Monolith | ✅ | JDK 21 + 9 模块 + 单 JAR；不新增模块 |
| II. Core-Stage Scope | ✅ | US-3 完整视角；9 项延后能力已列 |
| III. Self-Implemented ReAct | ✅ | 不改 ReActLoop；扩展 Memory 门面 |
| IV. Spring AI Half-Strength | ✅ | 不涉及 Spring AI 协议转换；Tool 走既有派发 |
| V. Three-Tier Plugin Tooling | ✅ | Memory Tool 是一等公民；边界澄清后归属明确 |
| VI. SQLite + Day-One Audit | ⚠️ | 复用 tool_invocations；新增 agent_memories 表；手动 V4 DDL |
| VII. Demo-First | ✅ | quickstart 端到端可演示 |

**GATE 结果**：✅ 全部通过（VI 的"条件合规"已在 §3.6 标注 V4 DDL 路径，进入 Phase 0 research 与 Phase 1 design）。

---

## 4. Project Structure

### 4.1 Documentation（本次特性）

```text
specs/006-memory-layer/
├── plan.md              # 本文件
├── research.md          # Phase 0 产物
├── data-model.md        # Phase 1 产物
├── quickstart.md        # Phase 1 产物
├── contracts/           # Phase 1 产物
│   ├── memory-service.md     # MemoryService 三层门面契约
│   ├── long-term-store.md    # LongTermMemoryStore 接口契约（3 后端实现）
│   ├── markdown-backend.md   # MarkdownMemoryStore 契约
│   ├── sqlite-backend.md     # SqliteMemoryStore + agent_memories 表契约
│   ├── mem0-backend.md       # Mem0MemoryStore HTTP 客户端契约
│   └── migration-scripts.md  # Markdown ↔ SQLite 迁移契约
└── tasks.md             # Phase 2 产物（/speckit-tasks 阶段创建）
```

### 4.2 Source Code（仓库根）

> 本 spec 涉及的源码改动分布；标 `[已落地]` 表示 005-tool-system / 早期迭代已实现的复用项，标 `[NEW]` 表示本 spec 需新增。

```text
oryxos-memory/
└── src/main/java/io/oryxos/memory/
    ├── MemoryService.java          # [已落地]  统一门面（save / recallByKeyword / recallByScope / delete）
    ├── SessionManager.java         # [已落地]  会话层；跟随 Session 生命周期
    ├── LongTermMemoryStore.java    # [已落地]  接口；3 后端实现
    ├── MemoryEntry.java            # [已落地]  单条记录 record
    ├── MemoryScope.java            # [已落地]  枚举 = {core, archive}
    ├── MemoryException.java        # [已落地]  RuntimeException 子类
    ├── MemoryProperties.java       # [NEW]     @ConfigurationProperties；含 archive.maxEntries / backend 选择 / core 永不被截断契约验证
    ├── backend/
    │   ├── MarkdownMemoryStore.java    # [已落地]  默认后端；.oryxos/memory/MEMORY.md
    │   ├── SqliteMemoryStore.java      # [NEW]     结构化后端；agent_memories 表
    │   └── Mem0MemoryStore.java        # [NEW]     自托管语义检索后端；HTTP 客户端
    └── repository/
        └── MemoryEntryRepository.java  # [NEW]     JPA Repository for agent_memories

oryxos-storage/
└── src/main/resources/db/migration/
    └── V4__add_agent_memories.sql   # [NEW]     CREATE TABLE agent_memories + idx_agent_memories_scope_created + DOWN rollback

oryxos-tool/
└── src/main/java/io/oryxos/tool/memory/      # [已落地]  005-tool-system 落地
    ├── SaveMemoryTool.java                   # 继承既有；spec FR-011 确认集成契约
    ├── RecallMemoryTool.java                 # 继承既有；spec FR-011 确认集成契约
    └── MemoryToolResult.java                 # 继承既有

oryxos-boot/
└── src/main/java/io/oryxos/boot/config/
    ├── MemoryConfig.java                     # [NEW]     @Bean MemoryService / MemoryBackendSelector / @EnableConfigurationProperties(MemoryProperties.class)
    └── Mem0ClientConfig.java                 # [NEW]     HttpClient Bean for Mem0MemoryStore（共享 005-tool-system 的 HttpClient；也可独立）
└── src/main/resources/
    └── application.yaml                       # [调整]   memo.backend 默认 = markdown

scripts/
├── migrate-markdown-to-sqlite.sh            # [NEW]     Markdown → SQLite 一次性迁移
├── migrate-sqlite-to-markdown.sh            # [NEW]     SQLite → Markdown 反向迁移
└── memory-smoke.sh                          # [NEW]     端到端冒烟（跨 Session 召回 / Scope 隔离 / 后端切换）
```

**结构决策**：Option 1（单项目 / library）的变体——所有变更在已有 Maven 多模块内，不引入新顶层目录；不改 Spring Boot 主类。`oryxos-memory` 模块按"门面 + 后端"分包（`backend/` + `repository/` + 顶层门面），每个子包独立自治。Memory Tool 实现**已在** `oryxos-tool/memory/`（005-tool-system 落地），**不**移动、不重写。

---

## 5. Complexity Tracking

> 仅在 Constitution Check 有违规需要解释时填写。本 spec 无违规（VI 的"条件合规"已在 §3.6 标注 V4 DDL 路径，进入 Phase 0/1），**本节留空**——模板保留占位行。

| Violation | Why Needed | Simpler Alternative Rejected Because |
| --------- | ----------- | ------------------------------------- |
| （无） | — | — |
