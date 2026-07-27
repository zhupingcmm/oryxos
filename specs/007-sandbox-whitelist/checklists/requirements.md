# 规格质量清单：Sandbox 白名单实现

**目的**：在进入 `/speckit-plan` 或 `/speckit-tasks` 之前，验证规格说明书的完整性与质量
**创建日期**：2026-07-27
**Feature**：[specs/007-sandbox-whitelist/spec.md](../spec.md)

## 内容质量

- [x] CHK001 文档内不含实现细节（语言、框架、API）—— 主叙述用"业务方 / Agent / LLM / 审计员"等业务语言；Java 类名仅在 FR / 关键实体 / 验收场景引用处出现，作为契约标识（`Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` / `SandboxProperties` / `WhitelistSandbox`）；JDK 21 / Spring Boot 3.x / Sandbox 升级路径在"假设"节说明，而非主体叙述
- [x] CHK002 聚焦用户价值与业务需求 —— 4 个用户故事全部从"业务方能做什么 / 看不到什么 / 出错时怎样"出发；US-1 文件越界、US-2 命令越名单、US-3 Notify 出站拦截、US-4 跨 ActionType 集成审计
- [x] CHK003 面向非技术干系人可读 —— Given/When/Then 验收场景不堆叠框架术语；技术名词（Sandbox / 白名单 / ActionType）作为契约词汇，不深入实现
- [x] CHK004 必填章节全部完成 —— 用户场景与测试 / 需求 / 关键实体 / 成功标准 / 假设 / 不在范围内 六节齐全；边界情况单独列节

## 需求完整性

- [x] CHK005 无 `[NEEDS CLARIFICATION]` 标记残留 —— 已用合理默认填充（fail-closed 默认 / 路径前缀严格匹配 / 命令首 token 大小写不敏感 / dangerousCommands 兼容读源等均在"假设"节标注）；4 条接口先行契约来自 [CLAUDE.md §9.4](../CLAUDE.md) 权威来源
- [x] CHK006 需求可测试且无歧义 —— 每条 FR 都给出 MUST 关键字 + 单一可断言行为（如 FR-003 "FILE_READ 含 `..` → 拒绝 `path traversal detected`"）；FR-008 "errorMessage MUST 不含 stack trace" 有明确断言路径
- [x] CHK007 成功标准可衡量 —— SC 含具体指标（P95 ≤ 5ms / 100% / 4 类 ActionType / fail-closed 默认）；每条 SC 都可被自动化或人工验收（如 SC-001 "4 类 ActionType 集成测试全过"、SC-006 "P95 ≤ 5ms"）
- [x] CHK008 成功标准与技术无关 —— SC 描述的是"用户/业务可观察的现象"（拦截 wall-time P95、audit 表行数、错误信息中 stack trace 比例、Fail-closed 默认行为），不含框架名（`SandboxViolationException` / `tool_invocations` 仅作为契约标识出现）
- [x] CHK009 所有验收场景定义完整 —— 4 个用户故事共 13 个 Given/When/Then 验收场景（US-1×4 / US-2×4 / US-3×3 / US-4×2），覆盖核心路径与异常路径（白名单通过 / 越界拒 / fail-closed / 多通道部分失败）
- [x] CHK010 边界情况已识别 —— 9 条边界情况（`..` traversal / 绝对 vs 相对路径 / 前缀绕过 / shell 元字符 / IPv6 字面 / 空白名单 fail-closed / 重复配置 / 控制字符 / 并发），覆盖 IO 异常、字符处理、并发
- [x] CHK011 范围边界清晰 —— "不在范围内"节列出 10 项排除项，每条说明宪法依据（核心阶段不做容器隔离，遵守宪法 §II；扩展阶段才做 microVM；SecurityManager 红线 — JDK 21 不可用）
- [x] CHK012 依赖与假设已识别 —— "假设"节列出 10 条，覆盖接口先行 / 核心阶段不做容器 / SecurityManager 不可用 / dangerousCommands 兼容读源 / fail-closed 默认 / 路径前缀严格匹配 / 命令首 token 大小写不敏感 / Notify 链路已落地 / 不引入新模块 / 实现状态盘点

## Feature 就绪度

- [x] CHK013 所有 FR 有清晰验收标准 —— FR-001 → US-4 接口稳定性 / FR-002 → US-1 验收场景 1-2 + US-3 / FR-003 → US-1 验收场景 1-4 / FR-004 → US-2 验收场景 1-4 / FR-005 → SC-002 / FR-006 → US-4 验收场景 1 / FR-007 → US-3 验收场景 1-3 / FR-008 → SC-003 / FR-009 → SC-005 / FR-010 → US-4 验收场景 2 + 假设 3 / FR-011 → NFR-003 + 边界情况"重复配置"
- [x] CHK014 用户场景覆盖主流程 —— 4 个用户故事覆盖 MVP（US-1 P1 文件白名单 + US-2 P1 Shell 白名单）+ Notify 链路固化（US-3 P2）+ 接口稳定性（US-4 P2）；MVP = US-1 + US-2（覆盖 [CLAUDE.md §9.4](../CLAUDE.md) 接口先行 + 应用层白名单硬约束）
- [x] CHK015 Feature 满足 SC 中定义的可衡量结果 —— SC-001 4 类 ActionType 端到端测试 / SC-002 4 类配置绑定 / SC-003 100% 审计 + 无 stack trace / SC-004 mvn verify green / SC-005 跨 ActionType 集成测试 / SC-006 P95 ≤ 5ms / SC-007 接口字节级不变 / SC-008 业务方白名单内可跑 Demo / SC-009 升级路径明确
- [x] CHK016 无实现细节泄露到规格 —— 通篇无 `Files.writeString` / `ProcessBuilder` / `Path.normalize()` / `MockMvc` / `@Primary` 等技术栈细节；类名仅作为契约标识（`Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` / `SandboxProperties` / `WhitelistSandbox` / `WebhookNotifyAdapter` / `tool_invocations`）出现；具体算法（路径规范化、元字符扫描、scheme 校验）只描述行为不描述实现

## 备注

- 检查项依据 [`.specify/templates/checklist-template.md`](../../../.specify/templates/checklist-template.md) 的"规格质量"维度，对应 `/speckit-specify` 流程中的"Specification Quality Validation"步骤
- 本 spec 是 OryxOS 核心能力第四项「Plugin Tool」的安全校验层收口（[CLAUDE.md §9.4](../CLAUDE.md) + 宪法 §VII Demo-First 安全默认）；`Sandbox` 接口 / 4 类 ActionType / `SandboxViolationException` 已在 005-tool-system spec 落地，本 spec 不重写这些契约，只做"应用层白名单真正落地"
- 验收人在 plan 阶段应额外检查：plan.md 的"Constitution Check"节需对照宪法 §I / §V / §VII 给出每条原则的合规声明（尤其 §I 不引入新模块、Sandbox 留在 oryxos-tool；§V Tool 实现归 oryxos-tool + 接口归 oryxos-core 边界；§VII fail-closed 默认）
- 与既有模块的边界：`Sandbox` 接口 + `WhitelistSandbox` 实现都在 `oryxos-tool/sandbox/`（宪法 §I 既定）；`SandboxProperties` 在 `oryxos-tool`（继承现有 `@ConfigurationProperties("oryxos.tool.sandbox")`）；`SandboxConfig` 在 `oryxos-boot`（与现有 `NotifyToolConfig` / `HttpClientConfig` 同模式，配置入口归 boot）
- 与 005-tool-system 既有 `Sandbox` 抽象完全兼容：`Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` / `SandboxProperties` 这 5 个契约面的 public API 在 007 完成后**字节级不变**（NFR-004 / SC-007）