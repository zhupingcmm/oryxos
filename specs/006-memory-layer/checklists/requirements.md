# 规格质量清单：Memory 层（让 Agent 记得住事的可插拔记忆层）

**目的**：在进入 `/speckit-plan` 或 `/speckit-tasks` 之前，验证规格说明书的完整性与质量
**创建日期**：2026-07-26
**Feature**：[specs/006-memory-layer/spec.md](../spec.md)

## 内容质量

- [x] CHK001 文档内不含实现细节（语言、框架、API）—— 主叙述用"系统 / Agent / LLM / 业务方"等业务语言；Java 类名仅在 FR / 关键实体 / 验收场景引用处出现，作为契约标识而非实现指南（如 `MemoryService` / `LongTermMemoryStore` / `MarkdownMemoryStore` 等作为契约标识；JDK 21 / Spring Boot / SQLite 等基础设施在"假设"节说明，而非主体叙述）
- [x] CHK002 聚焦用户价值与业务需求 —— 5 个用户故事全部从"业务方能做什么 / 看不到什么 / 出错时怎样"出发；US-1 跨会话记忆、US-2 默认 Markdown 后端、US-3 可插拔后端切换、US-4 Scope 显式隔离、US-5 接入 ReAct 循环
- [x] CHK003 面向非技术干系人可读 —— Given/When/Then 验收场景不使用框架术语；技术名词（如 scope / recall / archive）仅作为契约词汇，不是实现细节
- [x] CHK004 必填章节全部完成 —— 用户场景与测试 / 需求 / 成功标准 / 假设 / 不在范围内 五节齐全；边界情况单独列节

## 需求完整性

- [x] CHK005 无 `[NEEDS CLARIFICATION]` 标记残留 —— 已用合理默认填充（Mem0 后端默认 localhost:8000 / Markdown 后端无 archive 上限 / SQLite 后端 `archive.maxEntries` 默认 1000 等均在"假设"节标注）；4 条核心契约（不缓存 / core 永不被截断 / scope 显式 / keyword-only 检索）来自 [CLAUDE.md §9.6](../CLAUDE.md) 权威来源
- [x] CHK006 需求可测试且无歧义 —— 每条 FR 都给出 MUST 关键字 + 单一可断言行为（如 FR-009 "core 永不被截断 / 违反此契约的 backend 实现 MUST 拒绝启动"）；FR-005 "读 MUST 按字面 keyword 匹配" 等均有明确断言路径
- [x] CHK007 成功标准可衡量 —— SC 含具体指标（100% / 0% / ≤ 200ms / N=1500 / 100 条 等）；每条 SC 都可被自动化或人工验收（如 SC-002 "N=100 次 save 跨 Session 100% 命中"、SC-008 "P95 ≤ 200ms"）
- [x] CHK008 成功标准与技术无关 —— SC 描述的是"用户/业务可观察的现象"（跨会话召回率、audit 表行数、错误信息中 stack trace 比例、wall-time P95），不含框架名
- [x] CHK009 所有验收场景定义完整 —— 5 个用户故事共 18 个 Given/When/Then 验收场景（US-1×3 / US-2×4 / US-3×4 / US-4×4 / US-5×4），覆盖核心路径与异常路径（success / IO error / backend degraded / scope 越界）
- [x] CHK010 边界情况已识别 —— 9 条边界情况（外部文件修改 / core 区膨胀 / SQLite BUSY / Mem0 不可达 / 多 Agent 共享 / 特殊字符 / Profile 切换 / 与 Bootstrap 冲突 / 重复 save 去重），覆盖 IO 异常、并发、字符处理、跨重启一致性
- [x] CHK011 范围边界清晰 —— "不在范围内"节列出 9 项排除项，每条说明宪法依据（核心阶段不做扩展阶段功能，遵守宪法 §II）
- [x] CHK012 依赖与假设已识别 —— "假设"节列出 11 条，覆盖三层架构硬约束 / 默认后端 / 不内置 vector 检索 / 不缓存契约 / core 不截断契约 / scope 显式契约 / keyword-only 检索契约 / 005 Tool 已落地 / 数据迁移 / 不引入新模块 / 实现状态盘点

## Feature 就绪度

- [x] CHK013 所有 FR 有清晰验收标准 —— FR-001→US-1/FR-004→US-2/FR-008→US-4/FR-009→US-4 验证 3/FR-011→US-5/FR-014→US-3/FR-015→US-3；FR-002/003/005/006/007/010/012/013 由 §"非功能需求"或宪法 §VI 或 [CLAUDE.md §9.6](../CLAUDE.md) 4 条契约直接约束
- [x] CHK014 用户场景覆盖主流程 —— 5 个用户故事覆盖 MVP（US-1 P1 + US-2 P1）+ 可插拔后端（US-3 P2）+ Scope 隔离（US-4 P2）+ 接入 ReAct（US-5 P2）；MVP = US-1 + US-2 + US-4（覆盖 §9.6 的 4 条契约）
- [x] CHK015 Feature 满足 SC 中定义的可衡量结果 —— SC-001 "每日科技日报" Demo、SC-002 100% 跨会话召回、SC-003 1500 条 core 永不被截断、SC-005 100% audit 等可直接由 plan/tasks 阶段实现
- [x] CHK016 无实现细节泄露到规格 —— 通篇无 `ProcessBuilder` / `Files.writeString` / `JSON-RPC` / `wiremock` / `Mockito` 等技术栈细节；类名仅作为契约标识（`MemoryService` / `MemoryEntry` / `MemoryScope` / `MarkdownMemoryStore` / `SqliteMemoryStore` / `Mem0MemoryStore` / `agent_memories` 表）出现；具体 SQL 语法 / HTTP 路径 / 文件 IO API 全部不出现

## 备注

- 检查项依据 [`.specify/templates/checklist-template.md`](../../../.specify/templates/checklist-template.md) 的"规格质量"维度，对应 `/speckit-specify` 流程中的"Specification Quality Validation"步骤
- 本 spec 是 US-3「Memory」（[CLAUDE.md §10](../CLAUDE.md)）的完整功能视角；`MemoryService` / `MarkdownMemoryStore` / `save_memory` / `recall_memory` 部分已在 005-tool-system / 已有 `oryxos-memory` 模块**部分落地**，本 spec 不重写这些组件，只聚焦：①三层门面契约码化（4 条契约 → FR-005/006/007/008/009/010）、②两路新后端（SqliteMemoryStore + Mem0MemoryStore → FR-014/015）、③Scope 显式隔离（FR-008/009/010）、④"每日科技日报"端到端（SC-001）
- 验收人在 plan 阶段应额外检查：plan.md 的"Constitution Check"节需对照宪法 §I / §II / §VI 给出每条原则的合规声明（尤其 §I 不引入新模块、§II 不碰扩展阶段功能、§VI 不破坏 day-one audit 不变量）
- 与既有模块的边界：`MemoryService` 落在 `oryxos-memory`（宪法 §I 既定）；`SaveMemoryTool` / `RecallMemoryTool` 落在 `oryxos-tool/memory/`（005-tool-system 已落地，FR-011 明确"本 spec 不重写 Tool 抽象"）；Spring bean 装配若涉及新 Properties 类则放 `oryxos-boot`（继承 [CLAUDE.md §5 §V 边界澄清](../CLAUDE.md) "DI → boot" 原则）