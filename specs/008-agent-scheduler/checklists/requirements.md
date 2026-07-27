# 规格质量清单：定时任务模块（AgentScheduler）

**目的**：在进入 `/speckit-plan` 或 `/speckit-tasks` 之前，验证规格说明书的完整性与质量
**创建日期**：2026-07-27
**Feature**：[specs/008-agent-scheduler/spec.md](../spec.md)

## 内容质量

- [x] CHK001 文档内不含实现细节（语言、框架、API）—— 主叙述用"企业用户 / 调度器 / 审计员 / Agent 运营"等业务语言；技术名词（`AgentScheduler` / `AgentService` / `cron-utils` / `WebhookNotifyAdapter`）仅在 FR / SC / 实体字段引用处作为契约标识出现；JDK 21 / Spring Boot 3.x / `cron-utils` 依赖在"假设"节说明
- [x] CHK002 聚焦用户价值与业务需求 —— 4 个用户故事全部从"业务方能做什么 / 触发什么 / 出错时怎样 / 时区怎么算"出发：US-1 注册、US-2 到点钟推、US-3 并发去重+失败重试、US-4 时区+审计完整性
- [x] CHK003 面向非技术干系人可读 —— Given/When/Then 验收场景不堆叠框架术语；技术名词（cron / Schedule / Session / NotifyChannelAdapter）作为契约词汇，不深入实现
- [x] CHK004 必填章节全部完成 —— 用户场景与测试 / 需求 / 关键实体 / 成功标准 / 假设 / 不在范围内 / 边界情况 七节齐全；引用章节列出 9 项外部契约依据

## 需求完整性

- [x] CHK005 无 `[NEEDS CLARIFICATION]` 标记残留 —— 已用合理默认填充（5 段标准 cron / IANA 时区名 / 错过的 tick 不补跑 / 失败不熔断 / 调度器 fail-closed 等均在"假设"或"边界情况"节标注）；13 条 FR 全部带 MUST/SHOULD 关键字 + 单一可断言行为
- [x] CHK006 需求可测试且无歧义 —— 每条 FR 都给出 MUST 关键字 + 单一可断言行为（如 FR-002 "启动时扫描 `.oryxos/agents/*/AGENT.md` → upsert `scheduled_tasks`"、FR-006 "同 task 串行化执行——上一次执行未完成时，下个 cron tick 跳过"）；FR-007 "error_message 不含 stack trace" 显式契约对齐 007-sandbox-whitelist
- [x] CHK007 成功标准可衡量 —— SC 含具体指标（100 条 schedule 启动延迟 ≤ 2s / ≥12 子场景 / mvn verify 全 10 模块 / `git diff` 验证零核心类改动 / 异常 message ≤ 2 KB）
- [x] CHK008 成功标准与技术无关 —— SC 描述"业务/运营可观察的现象"（Demo 钟推跑通 / 跨时区准确性 / 审计关联完整性 / 启动延迟），不堆叠 `ScheduledExecutorService` / `cron-utils` / `ShedLock` 等框架名
- [x] CHK009 所有验收场景定义完整 —— 4 个用户故事共 16 个 Given/When/Then 验收场景（US-1×4 / US-2×4 / US-3×4 / US-4×4），覆盖核心路径（注册 / 触发 / 路径对齐）与异常路径（非法 cron / 失败重试 / 重叠跳过 / DST）
- [x] CHK010 边界情况已识别 —— 7 条边界情况（Profile 热修改 / JVM 重启 / 执行时长 > cron 间隔 / 非法时区 / Spring 启动失败 / 重复 task_id / Session 创建失败），覆盖 IO 异常、字符处理、并发、调度冲突
- [x] CHK011 范围边界清晰 —— "不在范围内"节列出 7 项排除项（含 Scheduler REST / 多实例集群 / 热加载 / 历史补跑 / 可视化仪表板 / 自定义时区偏移），每条说明宪法或 [CLAUDE.md §15](../../CLAUDE.md) 依据；7 项扩展阶段排除（[CLAUDE.md §II 扩展阶段](../../CLAUDE.md)）再次显式引用
- [x] CHK012 依赖与假设已识别 —— "假设"节列出 7 条，覆盖 cron 表达式语法 / `cron-utils` 依赖 / IANA 时区 / SQLite 5 张表 / 单实例运行 / 调度器启动入口 / 通知链路复用 004

## Feature 就绪度

- [x] CHK013 所有 FR 有清晰验收标准 —— FR-001 → US-1 验收场景 1-2 + 边界情况"重复 task_id" / FR-002 → US-1 验收场景 1-3 / FR-003 → US-2 验收场景 1-2 / FR-004 → US-2 验收场景 1-3 + US-3 验收场景 3（手动补跑同源） / FR-005 → US-2 验收场景 2 / FR-006 → US-3 验收场景 1 + SC-003 / FR-007 → US-3 验收场景 2 + 4 + SC-006 / FR-008 → US-3 验收场景 3 + SC-004 / FR-009 → US-4 验收场景 1 + 边界情况"非法时区" / FR-010 → US-4 验收场景 2 / FR-011 → US-1 验收场景 4 / FR-012 → 边界情况"重复 task_id" / FR-013 → 边界情况"Spring 启动失败"
- [x] CHK014 用户场景覆盖主流程 —— 4 个用户故事覆盖 MVP（US-1 P1 Profile 注册 + US-2 P1 到点钟推）+ 稳定性（US-3 P2 并发去重 + 失败重试）+ 跨时区企业落地（US-4 P3 时区 + 审计完整性）；MVP = US-1 + US-2（覆盖 [CLAUDE.md §11 三个验收 Demo](../../CLAUDE.md) 的"每日天气"最小路径）
- [x] CHK015 Feature 满足 SC 中定义的可衡量结果 —— SC-001 三个 Demo 钟推跑通 / SC-002 ≥12 子场景端到端 / SC-003 100 条 schedule 启动 ≤ 2s + 零误触发 / SC-004 路径对齐断言 / SC-005 跨时区准确性 / SC-006 失败审计完整性 / SC-007 mvn verify 全 10 模块 / SC-008 核心类零改动
- [x] CHK016 无实现细节泄露到规格 —— 通篇无 `ScheduledExecutorService.schedule()` / `ThreadPoolTaskScheduler` / `cron-utils` API 调用 / `ShedLock` 锁注解 / `@EnableScheduling` 等技术栈细节；类名仅作为契约标识（`AgentScheduler` / `AgentService.process()` / `CronExpression` / `ZoneId`）出现；具体调度算法（线程池、单线程串行、ZoneId.of()）只描述行为不描述实现

## 备注

- 检查项依据 [`.specify/templates/checklist-template.md`](../../../.specify/templates/checklist-template.md) 的"规格质量"维度，对应 `/speckit-specify` 流程中的"Specification Quality Validation"步骤
- 本 spec 是 OryxOS 核心阶段第 5 个能力（Web Service）落地前提的"地基补完"——[CLAUDE.md §5](../../CLAUDE.md) 已声明 `AgentScheduler` 在 `oryxos-core` 模块但未落地实现；本 spec 不引入新模块，把"接口预留"补到"端到端跑通"
- 验收人在 plan 阶段应额外检查：plan.md 的"Constitution Check"节需对照宪法 §I / §III / §VI / §VII 给出每条原则的合规声明（尤其 §I 不引入新模块、Scheduler 落在 `oryxos-core`；§III 自实现 ReAct 不被 Scheduler 改动；§VI 调度审计 day-one 写库 `scheduled_tasks` + `task_executions`；§VII 三个 Demo 端到端跑通）
- 与既有模块的边界：`AgentScheduler` + Schedule 注册 → upsert 实现都在 `oryxos-core/scheduler/`（宪法 §I 既定）；`task_executions` 表归属 `oryxos-storage`（既有的 `SessionRepository` / `ToolInvocationRepository` / `LlmCallRepository` 同模块）；CLI `schedule list` 命令归属 `oryxos-cli`（与 `oryxos profile list` / `oryxos agent list` 同模式，**不**加 REST API）
- 与既有 5 张 SQLite 表的边界：`scheduled_tasks` / `task_executions` 2 表的 schema 已在 [CLAUDE.md §13](../../CLAUDE.md) 声明（day-one）；DDL 已由 006 阶段 H2 / SQLite 测试创建。本 spec 不动 DDL，只补实现 + cron 解析 + 调度触发 + 审计写入路径
- 与 007-sandbox-whitelist 契约对齐：`task_executions.error_message` MUST NOT 含 stack trace（FR-007 + SC-006）；与 sandbox audit errorMessage 字节级一致
- 与 006-memory-layer 契约对齐：`Session.metadata` JSON 字段扩展（`task_id` / `source`）；不修改 `sessions` 表 schema（FR-004 隐含）