# Implementation Plan: Sandbox 白名单实现

**Branch**: `007-sandbox-whitelist` | **Date**: 2026-07-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/speckit-specify`（Section 24 需求：Sandbox 白名单实现——把工具执行前那道安全校验的墙真正砌起来）
**Artifacts**:
- Phase 0: [research.md](./research.md)（R-01..R-10 决策记录）
- Phase 1: [data-model.md](./data-model.md) | [contracts/sandbox-whitelist.md](./contracts/sandbox-whitelist.md) | [quickstart.md](./quickstart.md)

---

## Summary

**Primary requirement（来自 spec.md）**：把"放行一切只记告警"的临时 Sandbox 实现换成**真实的应用层白名单**；扩展 `SandboxProperties` + `WhitelistSandbox.enforce()` 覆盖 4 类 ActionType（HTTP_REQUEST / FILE_READ / FILE_WRITE / SHELL_COMMAND）；固化 `WebhookNotifyAdapter` 出站拦截路径；保持 `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` 4 个公共 API 字节级不变（[NFR-004](./spec.md) / [SC-007](./spec.md)）。

**Technical approach**：
- **接口先行**——[005-tool-system/contracts/sandbox.md §1](../005-tool-system/contracts/sandbox.md) 已落地 5 个核心契约（`Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` / `SandboxProperties`），007 不动公共 API
- **白名单而非黑名单**——4 类 ActionType 全部走 fail-closed 默认（空白名单 = 全部拒绝）；既有 `ShellToolProperties.dangerousCommands` 黑名单作为兜底保留（双层防御，[research.md R-06](./research.md)）
- **JDK 内置优先**——`Path.normalize()` 做路径规范化 + 严格前缀匹配；`String.split("\\s+")` 取 shell 首 token；零新增依赖
- **既有审计路径复用**——`DefaultToolExecutor` 既有 `catch (SandboxViolationException)` 自动写 `tool_invocations(success=false)`，FILE/SHELL 拦截无新增审计 helper（[research.md R-07](./research.md)）

---

## Technical Context

**Language/Version**: Java 21（[CLAUDE.md §4](../../CLAUDE.md) / §18 坑 4 强制 JDK 21 + `<forceLegacyJavacApi>true</forceLegacyJavacApi>`）

**Primary Dependencies**:
- Spring Boot 3.x（[CLAUDE.md §4](../../CLAUDE.md)）
- Spring Boot `@ConfigurationProperties` 绑定（既有，无新增）
- JDK 21 `java.nio.file.Path` 路径规范化 + `java.util.Locale` 大小写转换（内置，零新增）
- Logback / SLF4J（既有，stack trace 进 `.oryxos/logs/oryxos-cli-error.log`）
- WireMock 2.x（既有，[SandboxEnforcementIntegrationTest.java](../../../oryxos-tool/src/test/java/io/oryxos/tool/integration/SandboxEnforcementIntegrationTest.java) 已用，007 新增 IPv6 字面测试场景复用）

**Storage**: 既有 SQLite 表 `tool_invocations` 不变（007 不新增表）；新增配置的 YAML 持久化路径 = `application.yaml` / `application-{profile}.yaml`（既有模式）

**Testing**: 
- JUnit 5 + AssertJ（既有）
- `@SpringBootTest` 集成测试（既有 `SandboxEnforcementIntegrationTest` 模式，007 扩展 + 新增 4 类 ActionType 单测）
- WireMock 端到端 HTTP 越域验证（既有，007 扩展 IPv6 字面场景）

**Target Platform**: Java 21 跨平台（Linux / macOS / Windows）；路径处理跨平台（`Path.of()` + `Path.normalize()` 已封装分隔符差异）

**Project Type**: 单体多模块 Maven 项目（[CLAUDE.md §4-§5](../../CLAUDE.md)；9 个固定模块 + 既有 SQLite + 既有 Spring Boot 启动器）

**Performance Goals**: [SC-006](./spec.md) `enforce()` wall-time **P95 ≤ 5ms**（N=1000 单测）；`enforce()` 内**无 IO 调用**（[research.md R-01 备选 1](./research.md) 否决理由）

**Constraints**:
- **接口字节级不变**——[NFR-004](./spec.md) / [SC-007](./spec.md)：5 个核心契约 007 完成后 public API 与 005 阶段完全一致（`javap -p` 验证）
- **fail-closed 默认**——[宪法 §VII](../../.specify/memory/constitution.md) Demo-First 安全默认；空白名单 = 全部拒绝
- **errorMessage 不含 stack trace**——[NFR-004](./spec.md) / [CLAUDE.md §18](../../CLAUDE.md)：stack 100% 进 `.oryxos/logs/oryxos-cli-error.log`
- **JDK 21 强制**——[CLAUDE.md §18 坑 4](../../CLAUDE.md)：`<forceLegacyJavacApi>true</forceLegacyJavacApi>` + `<encoding>UTF-8</encoding>` + surefire `<argLine>-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8</argLine>`（既有 `pom.xml` 配置已落地，007 不修改）
- **不引入新 Maven 模块**——[CLAUDE.md §5 / 宪法 §I](../../.specify/memory/constitution.md)：Tool 相关代码 100% 在 `oryxos-tool`；007 阶段所有变更都在 `oryxos-tool/sandbox/` + `oryxos-boot/config/` 内
- **不引入 SecurityManager**——[CLAUDE.md §18 坑 #2](../../CLAUDE.md) / [宪法 §VII](../../.specify/memory/constitution.md)：JDK 21 已废弃；007 仅应用层白名单

**Scale/Scope**: 
- 代码增量 ≈ 200 行（`WhitelistSandbox` 扩 FILE + SHELL switch case ≈ 80 行；`SandboxProperties` 加 `File` + `Shell` 内部类 ≈ 50 行；测试增量 ≈ 70 行）
- 文档增量 ≈ 800 行（research + data-model + contracts + quickstart + plan）
- 测试用例增量 ≈ 30 个（FILE × 6 / SHELL × 6 / IPv6 × 4 / Notify × 3 / 集成 × 8 / 性能 × 3）

---

## Constitution Check

*GATE: 必须在 Phase 0 research 前通过；Phase 1 设计后重新评估。*

### §I — Single-Stack Monolith

**合规声明**：007 阶段全部代码在 Java 21 + Spring Boot 3.x 单体应用内完成，**不引入新 Maven 模块**。`Sandbox` 接口 + `WhitelistSandbox` 实现 + `SandboxProperties` 子配置 + 集成测试 = 全部在 `oryxos-tool/sandbox/` 与 `oryxos-tool/src/test/java/io/oryxos/tool/integration/`。

**GATE PASS** ✅

### §II — Core-Stage Scope

**合规声明**：007 阶段是核心阶段"Plugin Tool 体系"的最后一节（[CLAUDE.md §6 能力四](../../CLAUDE.md)）；严格不碰扩展阶段（容器隔离 / microVM / Sandbox 热更新 / Sandbox metrics / Tool Policy / 多租户 Sandbox）。`Tool Policy` / `Sandbox metrics` / `热更新` 已在 [spec.md §不在范围内](./spec.md) 第 5-7 条显式排除。

**GATE PASS** ✅

### §III — Self-Implemented ReAct

**合规声明**：007 阶段**不改 ReAct 循环**——只改 `WhitelistSandbox` 行为（新增 FILE/SHELL switch case）；`ReActLoop` / `AgentService` / `PromptBuilder` 不动。

**GATE PASS** ✅

### §IV — Spring AI Half-Strength

**合规声明**：007 阶段**不引入任何 Spring AI 概念**；`Sandbox` 是 OryxOS 自定义接口，与 Spring AI `@Tool` / `FunctionCalling` 完全解耦。`WhitelistSandbox` 是 `io.oryxos.tool.sandbox` 包下的纯 Java 类，零 Spring AI 依赖。

**GATE PASS** ✅

### §V — Three-Tier Plugin Tooling

**合规声明**：007 阶段不引入新 Tool；既有 Tool 调用契约不变（`Tool.execute()` 首行调 `sandbox.enforce(SandboxAction)`），007 只是把 `WhitelistSandbox.enforce()` 内的占位实现换成真实白名单。**§5 边界澄清**回顾：

| 类型 | 归属 | 007 行为 |
|------|------|---------|
| Tool **抽象** | `oryxos-core`（既有 `OryxTool` / `ToolExecutor` / `ToolDefinition`） | 不变 |
| Tool **实现** + Sandbox 基础设施 | `oryxos-tool`（既有 `WhitelistSandbox` / `SandboxProperties`） | 扩展（加 FILE / SHELL switch case + 2 个内部类） |
| 注册中心装配 | `oryxos-boot`（既有 `SandboxConfig`） | 不变 |

**GATE PASS** ✅

### §VI — SQLite + MEMORY.md Audit (Day-One)

**合规声明**：007 阶段**复用既有审计路径**——`DefaultToolExecutor` 既有 `catch (SandboxViolationException ex) → ToolResult.error → JpaToolAuditWriter → tool_invocations(success=false, error_message="sandbox violation: ...")`。007 不新增表 / 不改 DDL / 不引入新审计 helper。

**关键约束**：
- `error_message` 列 MUST 不含 stack trace（[NFR-004](./spec.md)）
- stack 100% 进 `.oryxos/logs/oryxos-cli-error.log`（既有 Logback 配置）
- audit writer 路径字节级不变（[research.md R-07](./research.md)）

**GATE PASS** ✅

### §VII — Demo-First（+ 安全默认）

**合规声明**：007 阶段落地**fail-closed 默认**（空白名单 = 全部拒绝），与宪法 §VII Demo-First 安全默认对齐。3 个核心 Demo（每日天气 / 每日科技日报 / 每日 GitHub 日报，详见 [CLAUDE.md §11](../../CLAUDE.md)）在配置 4 类 allowed-* 后可正常运行；未配置 = 全部拦截（强制业务方显式声明安全边界）。

**GATE PASS** ✅

### Additional Constraints（[CLAUDE.md §18](../../CLAUDE.md)）

| 约束 | 007 行为 |
|------|---------|
| ❌ 不启用 Spring AI 自动 Tool 执行 | 007 与 Spring AI 零耦合 |
| ❌ 不把 Tool 又拆成多模块 | 007 不引入新模块；所有变更在 `oryxos-tool/sandbox/` 内 |
| ❌ 不把 `AGENT.md`/`AgentLoader` 当成 Tool | 不涉及 |
| ❌ 不用容器类型扫描区分 Provider | 不涉及 |
| ❌ 不把 `tool_invocations`/`llm_calls` 只放日志 | 既有审计写库路径不变 |
| ❌ 不把 Memory 简化成跟 Session 合并 | 不涉及 |
| ❌ 不在核心阶段碰扩展阶段的东西 | [spec.md §不在范围内](./spec.md) 显式排除 10 项 |
| ❌ 不写非 JDK 21 特性 | 仅用 JDK 21 已有 API |
| ❌ 不自己修改 `constitution.md` | 不修改 |
| ❌ 不跳过 `/speckit.analyze` | 007 完成后跑 |
| ❌ 不使用 `SecurityManager`（JDK 21 不可用） | 仅应用层白名单；零 `SecurityManager` 调用 |
| ❌ 不在 Profile YAML 里硬编码 API key | 不涉及 |
| ❌ 不假设 SQLite 自动迁移能搞定所有表结构演进 | 007 不改 DDL |

**GATE PASS** ✅

### Phase 1 后重新评估

Phase 1 设计完成后（data-model.md + contracts/sandbox-whitelist.md + quickstart.md 已生成），重新评估：

| 宪法原则 | Phase 1 后状态 | 备注 |
|---------|-------------|------|
| §I 不引入新模块 | ✅ | `SandboxProperties` 加 2 个内部类 + `WhitelistSandbox` 加 2 个 switch case，全部在 `oryxos-tool/sandbox/` |
| §II 核心阶段范围 | ✅ | [spec.md §不在范围内](./spec.md) 显式排除 10 项扩展阶段项 |
| §III 不改 ReAct | ✅ | 仅扩展 `WhitelistSandbox`，不动 `ReActLoop` |
| §IV 不用 Spring AI | ✅ | 007 阶段零 Spring AI 依赖 |
| §V 边界（核心/tool/boot） | ✅ | [data-model.md §2.1](./data-model.md) 表格明确归属 |
| §VI 审计 day-one | ✅ | [data-model.md §3](./data-model.md) 复用既有审计路径 |
| §VII fail-closed | ✅ | [contracts/sandbox-whitelist.md §12.4](./contracts/sandbox-whitelist.md) 显式契约 |

**Phase 1 后所有 GATE 仍 PASS** ✅

---

## Project Structure

### Documentation (this feature)

```text
specs/007-sandbox-whitelist/
├── plan.md                      # 本文件（/speckit-plan 输出）
├── spec.md                      # /speckit-specify 输出
├── research.md                  # Phase 0 输出（/speckit-plan）✅
├── data-model.md                # Phase 1 输出（/speckit-plan）✅
├── quickstart.md                # Phase 1 输出（/speckit-plan）✅
├── contracts/
│   └── sandbox-whitelist.md     # Phase 1 输出（/speckit-plan）✅
├── checklists/
│   └── requirements.md          # /speckit-specify 输出（16/16 PASS）✅
└── tasks.md                     # Phase 2 输出（/speckit-tasks —— NOT yet generated）
```

### Source Code (repository root)

```text
oryxos-tool/src/main/java/io/oryxos/tool/sandbox/
├── Sandbox.java                            # [既有，不变] 接口
├── SandboxAction.java                      # [既有，不变] record
├── ActionType.java                         # [既有，不变] enum
├── SandboxViolationException.java          # [既有，不变] RuntimeException
├── SandboxProperties.java                  # [既有 + 扩展] 加 File + Shell 内部类
└── WhitelistSandbox.java                   # [既有 + 扩展] 加 FILE / SHELL switch case + IPv6 补强

oryxos-tool/src/test/java/io/oryxos/tool/sandbox/
├── WhitelistSandboxTest.java               # [既有 + 扩展] 新增 FILE / SHELL 单测
├── FilePathSandboxTest.java                # [NEW 007] 路径规范化 + 越界 + traversal 单测
├── ShellCommandSandboxTest.java            # [NEW 007] 命令首 token + 黑名单分层单测
├── SandboxApiCompatibilityIT.java          # [NEW 007] 接口字节级不变断言
└── SandboxPerformanceBenchmarkIT.java      # [NEW 007] P95 ≤ 5ms 性能断言

oryxos-tool/src/test/java/io/oryxos/tool/integration/
└── SandboxEnforcementIntegrationTest.java  # [既有 + 扩展] 修改 no-op 测试为真拦截 + 新增 IPv6 场景

oryxos-tool/src/main/java/io/oryxos/tool/notify/
└── WebhookNotifyAdapter.java               # [既有，不变] 既有 sandbox 钩调用 + 007 享受 IPv6 补强

oryxos-tool/src/main/java/io/oryxos/tool/shell/
├── ShellTool.java                          # [既有，不变] 既有 dangerousCommands 黑名单兜底
└── ShellToolProperties.java                # [既有，不变] 兼容读源（007 阶段不动）

oryxos-tool/src/main/java/io/oryxos/tool/file/
├── FileReadTool.java                       # [既有，不变] 既有 sandbox.enforce(FILE_READ) 调用
├── FileWriteTool.java                      # [既有，不变]
└── FileListTool.java                       # [既有，不变]

oryxos-boot/src/main/java/io/oryxos/boot/config/
├── SandboxConfig.java                      # [既有，不变] @EnableConfigurationProperties 自动绑定新子配置
├── ToolSystemConfig.java                   # [既有，不变]
└── NotifyToolConfig.java                   # [既有，不变]

oryxos-storage/src/main/resources/db/migration/
└── (007 不涉及 DDL 变更)
```

**Structure Decision**：选 **Option 1: Single project**（Java 单体 Maven 多模块）。007 阶段不引入新模块；既有 9 个模块 [CLAUDE.md §5](../../CLAUDE.md) 全部复用。代码变更集中在 `oryxos-tool/sandbox/` + `oryxos-boot/config/SandboxConfig.java`（既有装配入口）+ 测试模块。

---

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

**007 阶段无 Constitution 违规**，Complexity Tracking 表为空。

---

## Plan 实施步骤（前置 /speckit-tasks 输入）

> 注：本节不是 `/speckit-tasks` 的任务列表（任务列表由 `/speckit-tasks` 命令根据 spec.md + plan.md + contracts/ 自动生成）；本节是 plan.md 给后续 commands 的**实施顺序建议**。

### 阶段顺序（建议）

```text
Phase 0 ── research.md ✅（已生成）
   ↓
Phase 1 ── data-model.md ✅ contracts/sandbox-whitelist.md ✅ quickstart.md ✅
   ↓
Phase 2 ── tasks.md（/speckit-tasks 生成）
   ↓
Phase 3 ── /speckit-implement 执行任务
   ├── Step 1: SandboxProperties 扩展（File + Shell 内部类 + setter）
   ├── Step 2: WhitelistSandbox enforce() 扩 FILE_READ / FILE_WRITE switch case
   ├── Step 3: WhitelistSandbox enforce() 扩 SHELL_COMMAND switch case
   ├── Step 4: WhitelistSandbox isIpLiteral() IPv6 补强
   ├── Step 5: 单测（FilePathSandboxTest + ShellCommandSandboxTest + IPv6 scenarios in WhitelistSandboxTest）
   ├── Step 6: 集成测试（SandboxEnforcementIntegrationTest 修改 + 新增 IPv6 + Notify 链路）
   ├── Step 7: 性能测试（SandboxPerformanceBenchmarkIT）+ 接口兼容性测试（SandboxApiCompatibilityIT）
   ├── Step 8: mvn verify 全模块绿
   └── Step 9: /speckit.analyze 收口
```

### 风险与缓解

| 风险 | 缓解策略 |
|------|---------|
| `SandboxProperties` 公共方法签名变化破坏既有 `@ConfigurationProperties` 绑定 | setter 内做 `null → List.of()` 兜底；既有 `getHttp/setHttp/getAllowedDomains/setAllowedDomains` 字节级不变（仅新增 `getFile/setFile/getShell/setShell`） |
| `WhitelistSandbox` 扩展 switch case 引入回归 | 既有用例（[SandboxEnforcementIntegrationTest](../../../oryxos-tool/src/test/java/io/oryxos/tool/integration/SandboxEnforcementIntegrationTest.java) 4 个场景）必须全过；新增测试覆盖 FILE/SHELL 边界 |
| `ShellToolProperties.dangerousCommands` 与 `SandboxProperties.shell.dangerousCommands` 配置冲突 | 007 阶段明确优先级（`ShellToolProperties` 优先，黑名单先于白名单，research.md R-06），双层防御语义不变；008 阶段统一收敛 |
| IPv6 字面识别补强可能误判（如 `https://example.com:8080/path` 含 `:` 但不是 IPv6） | IPv6 字面识别限定为"host 部分含 `:` 且含 `[`/`]` 包装"，URL 后面的 `:port` 部分不影响 |
| 路径规范化 `Path.normalize()` 在 Windows 上行为差异 | `Path.of(raw).normalize()` 是 JDK 21 跨平台 API，已封装分隔符差异；不引入 Windows-specific 代码 |
| fail-closed 默认破坏既有 Demo | 3 个 Demo 在 [quickstart.md](./quickstart.md) §场景 S1-S4 给出明确的 YAML 配置示例；既有 [SandboxEnforcementIntegrationTest](../../../oryxos-tool/src/test/java/io/oryxos/tool/integration/SandboxEnforcementIntegrationTest.java) 第 4 个测试需修改为"路径在白名单内通过 + 路径在白名单外拒绝"，详见 [contracts/sandbox-whitelist.md §15.2](./contracts/sandbox-whitelist.md) |

---

## 引用

- [spec.md](./spec.md)（FEATURE_SPEC）
- [research.md](./research.md)（Phase 0 输出）
- [data-model.md](./data-model.md)（Phase 1 输出）
- [contracts/sandbox-whitelist.md](./contracts/sandbox-whitelist.md)（Phase 1 输出）
- [quickstart.md](./quickstart.md)（Phase 1 输出）
- [checklists/requirements.md](./checklists/requirements.md)（16/16 PASS）
- [.specify/memory/constitution.md](../../.specify/memory/constitution.md)（7 原则 + Additional Constraints）
- [.specify/templates/plan-template.md](../../.specify/templates/plan-template.md)（本文件结构依据）
- [CLAUDE.md §5 / §6 / §9.4 / §18](../../CLAUDE.md)
- [specs/005-tool-system/contracts/sandbox.md](../005-tool-system/contracts/sandbox.md)（基础契约）
- [specs/005-tool-system/contracts/tool-executor.md §3 / §4](../005-tool-system/contracts/tool-executor.md)
- [specs/004-notify-channel/spec.md FR-007](../004-notify-channel/spec.md)
- [SandboxEnforcementIntegrationTest.java](../../../oryxos-tool/src/test/java/io/oryxos/tool/integration/SandboxEnforcementIntegrationTest.java)
- [WhitelistSandbox.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java)
- [SandboxProperties.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxProperties.java)