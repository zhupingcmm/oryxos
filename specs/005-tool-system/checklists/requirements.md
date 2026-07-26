# 规格质量清单：Tool 体系（Agent 的"双手"）

**目的**：在进入 `/speckit-plan` 或 `/speckit-tasks` 之前，验证规格说明书的完整性与质量
**创建日期**：2026-07-26
**Feature**：[specs/005-tool-system/spec.md](../spec.md)

## 内容质量

- [x] CHK001 文档内不含实现细节（语言、框架、API）—— 主叙述用"系统 / Agent / LLM"等业务语言；Java 类名仅在 FR / 关键实体 / 验收场景引用处出现，作为契约标识而非实现指南
- [x] CHK002 聚焦用户价值与业务需求 —— 5 个用户故事全部从"业务方能做什么 / 看不到什么 / 出错时怎样"出发
- [x] CHK003 面向非技术干系人可读 —— Given/When/Then 验收场景不使用框架术语；技术名词只在 FR/SC 引用 contract 时出现
- [x] CHK004 必填章节全部完成 —— 用户场景与测试 / 需求 / 成功标准 / 假设 / 不在范围内 五节齐全

## 需求完整性

- [x] CHK005 无 `[NEEDS CLARIFICATION]` 标记残留 —— 已用合理默认填充（NFR 时长、并发模型、超时阈值均在"假设"节标注）
- [x] CHK006 需求可测试且无歧义 —— 每条 FR 都给出 MUST 关键字 + 单一可断言行为；FR-007 的验证手段（重复计数 ≥ 2 = 违反）已显式给出
- [x] CHK007 成功标准可衡量 —— SC 含具体指标（≤ 30 秒 / ≤ 100 行 / 100% / 0% / ≤ 30 分钟）；每条 SC 都可被自动化或人工验收
- [x] CHK008 成功标准与技术无关 —— SC 描述的是"用户/业务可观察的现象"（墙钟时间、审计表行数、错误信息中 stack trace 比例），不含框架名
- [x] CHK009 所有验收场景定义完整 —— 5 个用户故事共 16 个 Given/When/Then 验收场景（US-1×3 / US-2×4 / US-3×4 / US-4×4 / US-5×3），覆盖核心路径与异常路径
- [x] CHK010 边界情况已识别 —— 9 条边界情况（超时 / 中断 / 20 Tool / schema 冲突 / 外部连接 / 非法参数 / 并发触发 / Notify 不可见 / Sandbox 优先序）
- [x] CHK011 范围边界清晰 —— "不在范围内"节列出 8 项排除项，每条说明宪法依据或 004-spec 引用
- [x] CHK012 依赖与假设已识别 —— "假设"节列出 12 条，覆盖 Notify 引用关系 / Tool 数量硬约束 / 三档优先级 / MCP 健康检查策略 / schema 冲突策略 / Tool-as-a-Service 推迟 / 审计一致性 / Notify 错误返回路径 / 并发模型 / JDK 21 虚拟线程 / Spring AI 禁用验证手段 / 实现状态（已部分落地）

## Feature 就绪度

- [x] CHK013 所有 FR 有清晰验收标准 —— FR-001 → US-1 / FR-004 → US-2 / FR-009 → US-3 / FR-008（重档）→ US-4 / FR-010 → US-5；FR-002/003/005/006/007/011/012/013/014/015 由 §"非功能需求"或宪法 §IV/§V/§VI 直接约束
- [x] CHK014 用户场景覆盖主流程 —— 5 个用户故事覆盖 MVP（US-1 + US-2）+ 三档接入演示（US-3 + US-4）+ 出站出口（US-5）
- [x] CHK015 Feature 满足 SC 中定义的可衡量结果 —— SC-001 三个 Demo、SC-002 100% 审计、SC-003 零越界副作用、SC-006 ≤ 30 分钟、SC-007 ≤ 100 行等可直接由 plan/tasks 阶段实现
- [x] CHK016 无实现细节泄露到规格 —— 通篇无"Lombok / MapStruct / Hibernate / MyBatis"等技术栈细节；类名仅作为契约标识（`OryxTool` / `ToolRegistry` / `ToolResult` / `SandboxAction` / `SandboxViolationException` / `McpClientService` / `NotifyChannelAdapter`）出现

## 备注

- 检查项依据 [`.specify/templates/checklist-template.md`](../../../.specify/templates/checklist-template.md) 的"规格质量"维度，对应 `/speckit-specify` 流程中的"Specification Quality Validation"步骤
- 本 spec 是 US-4（Plugin Tool，[CLAUDE.md §10](../CLAUDE.md)）的"完整功能视角"合并说明；与 [specs/004-notify-channel](../004-notify-channel/spec.md) 在 Notify 部分构成"功能视角 / 详细契约"的互补对（参见假设 1）
- 验收人在 plan 阶段应额外检查：plan.md 的"Constitution Check"节需对照宪法 §I / §IV / §V / §VI 给出每条原则的合规声明