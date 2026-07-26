# 实施计划：Tool 体系（Agent 的"双手"）

**分支**：`005-tool-system` | **日期**：2026-07-26 | **Spec**：[spec.md](./spec.md)

**输入**：来自 [spec.md](./spec.md) 的功能规格说明。

> **关于 §3 Constitution Check 字段**：本文件 §3（Constitution Check）**完整保留**模板原样的字段占位（"Gates determined based on constitution file"），同时**新增** §3.1—§3.9 一节填写本特性的实际合规结论，避免与模板字段混淆。

---

## 1. Summary

Tool 体系是 OryxOS 核心能力第四项 ——「Plugin Tool」（[CLAUDE.md §10](../CLAUDE.md) 与宪法 §V），把 Agent 从"只能回答"升级为"能够真正动手做事"。本 spec 在 [004-notify-channel](../004-notify-channel/spec.md) 已落地的 `NotifyTool` / `WebhookNotifyAdapter` / `WhitelistSandbox` / `ToolRegistry` 之上，把 Tool 系统补齐到"完整视图"：

- **5 类内置 Tool**（FileTools / ShellTools / HttpTools / NotifyTools / MemoryTools；Notify 已落地，新增其余 4 类）
- **MCP 接入**（`McpClientService` + `McpToolAdapter`；把 MCP server 暴露的每个工具转成 `OryxTool` 实现）
- **重代码接入**（`@Component implements OryxTool` 自动发现；Spring bean 扫描 + `ToolRegistry` 装配）
- **`tool_invocations` 表新增 `source` 列**（区分 `builtin` / `mcp` / `java_bean`）
- **统一的失败语义**（Sandbox 拦截 / Tool 异常 → `ToolResult.success=false`，不冒泡到 ReAct 主循环；NFR-004 错误信息不携带 stack trace）

**核心 trade-off**：

1. 本 spec 是 US-4 的"完整视角"合并说明，与 [004-notify-channel](../004-notify-channel/spec.md) 在 Notify 部分构成**互补对**（004 是"Notify 子能力的详细契约"，本 spec 是"完整 Tool 系统视角"）。Notify 部分的 FR / SC 不在本 spec 重复。
2. 部分代码已在 004 分支落地（`Sandbox` / `ToolRegistry` / `DefaultToolExecutor` / `NotifyTool`）；本 spec 的 plan 区分"已有实现 → 引用"与"剩余差距 → 落地"。
3. 三档接入（零代码 / 轻代码 / 重代码）中"零代码"路径在核心阶段以"基础设施已就绪 + 接入示例可用"为态，扩展阶段才重点推广；`AGENTS.md` / `SKILL.md` / `mcp_servers.yaml` 的解析在 [specs/003-cli-commands](../003-cli-commands/spec.md) 覆盖。

详见 [research.md](./research.md) 的 R-01..R-12 决策记录。

---

## 2. Technical Context

> 以下条目按 plan-template 字段填写；任何标 "n/a" 的字段均与本 spec 无关（CLI/Web 详情由 003-cli-commands / US-5 覆盖）。

**Language/Version**：Java 21（[CLAUDE.md §4](../CLAUDE.md) 强制）；records / sealed types / virtual threads / pattern matching 全部允许使用

**Primary Dependencies**：

- `oryxos-core`（已存在；`OryxTool` / `ToolRegistry` / `ToolRegistration` / `ToolDefinition` / `DefaultToolExecutor` / `ToolSchemaProvider` 均已落地）
- `oryxos-tool`（已存在；`notify/` 与 `sandbox/` 包已落地；本 spec 新增 `file/` / `shell/` / `http/` / `mcp/` 子包与对应内置 Tool）
- `oryxos-storage`（已存在；`ToolInvocationRecord` 已含 `channel` / `notify_status_code`，本 spec 新增 1 列 `source`）
- `oryxos-boot`（已存在；`NotifyToolConfig` 已装配 `ToolRegistry`；本 spec 扩装配路径以包含新 Tool Bean）
- `spring-context`（Spring DI bean 装配 + `@Component` 扫描）
- `java.net.http.HttpClient`（JDK 21 内置；`HttpTools` 用）
- `java.nio.file.Files`（JDK 内置；`FileTools` 用）
- `ProcessBuilder`（JDK 内置；`ShellTools` 用）
- MCP Java SDK（mcp-sdk-java；版本待定，参考 [research.md R-04](./research.md)）
- `com.fasterxml.jackson`（Spring Boot starter 自带；JSON 序列化）

**Storage**：SQLite via Spring Data JPA（沿用既有）；`tool_invocations` 表 DDL 演进（[data-model.md §7](./data-model.md) 新增 `source` 列）

**Testing**：JUnit 5（沿用）；Mockito 单测；WireMock 集成测（HTTP Tool mock）；`scripts/tool-smoke.sh` 端到端冒烟（tasks.md 阶段创建）

**Target Platform**：Linux server / Windows server（与 US-1 / US-2 / US-3 / US-4 一致；JDK 21 跨平台）

**Project Type**：library（`oryxos-tool` 模块作为 Spring bean 集合）+ embedded CLI/Web Service（`oryxos chat` / `POST /api/v1/agents/{name}/invoke` 触发链路）；不属于独立 web 服务（[CLAUDE.md §5](../CLAUDE.md)）

**Performance Goals**：

- 单条 Tool 调用 wall-time P95 ≤ 30 秒（spec NFR-001；健康依赖场景下）
- ReAct 主循环不被 Tool 副作用阻塞（spec NFR-002）
- Tool schema 生成在 Spring Boot 启动期完成（spec NFR-003）
- 三个 Demo 端到端跑通（spec SC-001）

**Constraints**：

- 仅 `http_get` / `http_post` / `file_read` / `file_write` / `file_list` / `shell` / `notify` / `save_memory` / `recall_memory` 共 9 个内置 Tool（spec FR-003）
- Tool 失败统一走 `ToolResult.success=false` 返回，**不抛** RuntimeException（spec FR-012）
- Tool 错误信息不含 stack trace，stack trace 100% 进 `.oryxos/logs/`（spec NFR-004）
- 单 binary fat JAR 部署（[CLAUDE.md §4](../CLAUDE.md)）
- 不新增 Maven 模块（Constitution §I / §V）
- 不依赖 `hibernate.ddl-auto=update` 演进 schema（Constitution "Additional Constraints" 第 3 条）
- Spring AI 自动 Tool 执行必须被禁用（Constitution §IV）

**Scale/Scope**：本 spec 不引入新外部服务；Tool 调用频率跟随三个 Demo 的频率（每日 ≤ 100 次 / Agent × Profile 数量）。

---

## 3. Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

> **模板原字段**（保留）：Gates determined based on constitution file
>
> 模板期望本节列出"基于 constitution 文件派生的 Gates"。本 spec 把这一内容移到 §3.1—§3.9 九节，每节对应宪法 §I—§VII + 附加约束；模板字段本身不再赘述。

---

### 3.1 原则 I — Single-Stack Monolith（JDK 21 + Spring Boot 3.x）

✅ **合规**。

- JDK 21（Language/Version 已声明）
- Spring Boot 3.x（[CLAUDE.md §4](../CLAUDE.md)）
- 9 模块不动；本 spec 不新增 Maven 模块，所有代码落在已有 `oryxos-tool` / `oryxos-core` / `oryxos-storage` / `oryxos-boot` 之内
- 单 fat JAR 部署；`mvn -pl oryxos-boot -am package` 仍出单 JAR
- Tool 实现全部用 JDK 内置 API（`HttpClient` / `Files` / `ProcessBuilder`），除 MCP Java SDK 外不新增第三方依赖

### 3.2 原则 II — Core-Stage Scope Discipline（五大核心能力优先）

✅ **合规**。

- Tool 体系是 US-4「Plugin Tool」的完整视角（[CLAUDE.md §6](../CLAUDE.md) 第 4 能力）
- 不引入扩展阶段能力：Tool Policy 引擎 / Tool Marketplace / 多租户级别 Tool 配额 / Tool 调用流式输出 / Tool 调用结果缓存 / Tool 性能分析（OTel 集成） —— 全部明确不在范围内（spec §"不在范围内"）
- 零代码路径的运营者自助接入放扩展阶段；核心阶段仅提供基础设施 + 接入示例

### 3.3 原则 III — Self-Implemented ReAct Loop

✅ **合规**。

- 本 spec 不修改 `ReActLoop`；只通过 `DefaultToolExecutor` 派发路径扩展 Tool 实现
- ReAct 循环的最大迭代次数、Tool 消息语义、审计时机均沿用 US-2 已落地契约
- 沙箱拦截 / Tool 异常统一包成 `ToolResult.success=false`，不破坏 ReAct 主循环的"包错不抛错"语义

### 3.4 原则 IV — Spring AI Used at Half-Strength（禁用自动 tool 执行）

✅ **合规**。

- `ToolSchemaProvider` 用 Spring AI `@Tool` 注解生成 Function Calling schema（仅 schema 生成，spec FR-006）
- 工具派发由 `DefaultToolExecutor` 触发 → `ToolRegistry.find(name)` → `OryxTool.execute()`，**不**依赖 Spring AI 自动执行（spec FR-007）
- 验证手段已固化（[spec FR-007](../spec.md)）：`tool_invocations` 表里同一 Tool 同一参数在同一 Session 内重复计数 ≥ 2 行说明违反
- 不会触发 tool 被调两次的已知坑（[CLAUDE.md §8.1](../CLAUDE.md)）

### 3.5 原则 V — Three-Tier Plugin Tooling

✅ **合规**（边界澄清后）。

- **零代码**：`AGENT.md` + `SKILL.md` + MCP server config（spec FR-008）；`AGENT.md` 加载归 `oryxos-core` 的 `ContextLoader`，**不**当作 Tool
- **轻代码**：自定义 MCP server（任何语言），在 `mcp_servers.yaml` 登记（spec FR-009）；`McpToolAdapter` 把 MCP 工具转成 `OryxTool` 注册到 `ToolRegistry`
- **重代码**：Java `@Component implements OryxTool`（或 Spring AI `@Tool`，spec FR-008）；`OryxTool.description()` 默认方法已落地（[OryxTool.java](../../oryxos-core/src/main/java/io/oryxos/core/OryxTool.java) 第 31 行）
- **模块归属**（[CLAUDE.md §5 §V 边界澄清](../CLAUDE.md)）：
  - Tool **抽象**（`OryxTool` / `ToolRegistry` / `ToolRegistration` / `ToolDefinition` / `ToolExecutor` / `ToolSchemaProvider`）归 `oryxos-core`
  - Tool **实现 + 基础设施**（`FileTools` / `ShellTools` / `HttpTools` / `NotifyTool` / `McpClientService` / `McpToolAdapter` / `WhitelistSandbox` 等）归 `oryxos-tool`
  - 注册表装配 `@Bean ToolRegistry` 归 `oryxos-boot`（`NotifyToolConfig` 已有；扩展新增其他 Tool Bean）
- 不拆 `builtin-tools` / `skill-tools` / `mcp-tools` 子模块（§V 原意）
- `AGENT.md` 加载归 `oryxos-core` 的 `ContextLoader`，**不**当作 Tool

### 3.6 原则 VI — SQLite + MEMORY.md with Day-One Audit Persistence

⚠️ **条件合规**（一处需注意）。

- `tool_invocations` 表是 day-one 表（[CLAUDE.md §13](../CLAUDE.md)）
- 本 spec 在该表上**新增 1 列** `source`（`builtin` / `mcp` / `java_bean` 三选一），不破坏既有不变量
- 每次 Tool 调用必产 1 行审计（spec FR-005）
- **不**新增 `tool_audit_extra` 之类的子表；审计统一收口
- ⚠️ **DDL 演进路径**：依宪法 "Additional Constraints" 第 3 条，**不依赖** `hibernate.ddl-auto=update`；新增 `source` 列需要手动写 DDL 脚本（详见 [data-model.md §7](./data-model.md) 的 V3 migration）

### 3.7 原则 VII — Demo-First Delivery（跑通优先于完美）

✅ **合规**。

- 本 spec 的核心验收标准 = 三个 Demo 的端到端跑通（spec SC-001）
- [quickstart.md](./quickstart.md) 给出可演示路径（WireMock 模拟 HTTP + 本地 file/shell 真实副作用）
- 不追求完美（不引入连接池 / 不引入异步限流 / 不引入工具调用结果缓存）—— 与跑通优先一致

---

### 3.8 附加约束（"不要做的事"）

✅ **全部合规**。

- ❌ 不使用 `SecurityManager` — 应用层 `Sandbox`（spec FR-004，[CLAUDE.md §9.4](../CLAUDE.md)）
- ❌ 不硬编码 API key — Profile YAML 用 `${ENV_VAR}` 占位（继承 003-cli-commands 的 ConfigLoader 路径）
- ❌ 不依赖 `hibernate.ddl-auto=update` 演进 schema — 手动 V3 DDL 脚本（[data-model.md §7](./data-model.md)）
- ❌ 不扫描容器类型区分 Provider — 本 spec 不涉及 Provider
- ❌ 不把 Session 与 Memory 合并 — 本 spec 不涉及 Memory（仅复用 Memory 工具接口）
- ❌ 不用非 JDK 21 特性 — records / virtual threads / sealed types 都是 JDK 21 stable；不使用 preview 特性

---

### 3.9 Constitution Check 总结

| 原则 | 状态 | 备注 |
| ---- | ---- | ---- |
| I. Single-Stack Monolith | ✅ | JDK 21 + 9 模块 + 单 JAR |
| II. Core-Stage Scope | ✅ | US-4 完整视角；8 项延后能力已列 |
| III. Self-Implemented ReAct | ✅ | 不改 ReActLoop；扩展派发路径 |
| IV. Spring AI Half-Strength | ✅ | 仅 schema 生成；不自执行 |
| V. Three-Tier Plugin Tooling | ✅ | 零/轻/重三档就位（Notify 已实现；MCP + Java Bean 新增） |
| VI. SQLite + Day-One Audit | ⚠️ | 复用 tool_invocations；新增 `source` 列；手动 DDL |
| VII. Demo-First | ✅ | quickstart 端到端可演示 |

**GATE 结果**：✅ 全部通过（VI 的"条件合规"已在 §3.6 标注 V3 DDL 路径，进入 plan 阶段）。可进入 Phase 0 research 与 Phase 1 design。

---

## 4. Project Structure

### 4.1 Documentation（本次特性）

```text
specs/005-tool-system/
├── plan.md              # 本文件
├── research.md          # Phase 0 产物
├── data-model.md        # Phase 1 产物
├── quickstart.md        # Phase 1 产物
├── contracts/           # Phase 1 产物
│   ├── oryx-tool.md         # OryxTool 接口契约
│   ├── tool-executor.md     # DefaultToolExecutor 派发 + 审计契约
│   ├── sandbox.md           # Sandbox 接口契约
│   ├── builtin-tools.md     # 9 个内置 Tool 的 schema + 行为
│   └── mcp-adapter.md       # McpToolAdapter 契约
└── tasks.md             # Phase 2 产物（/speckit-tasks 阶段创建）
```

### 4.2 Source Code（仓库根）

> 本 spec 涉及的源码改动分布；标 `[已落地]` 表示 004 阶段已实现的复用项，标 `[NEW]` 表示本 spec 需新增。

```text
oryxos-core/
└── src/main/java/io/oryxos/core/
    ├── OryxTool.java                  # [已落地]  含 default description()
    ├── DefaultToolExecutor.java       # [已落地]  派发路径已就位；扩展 source 字段抽取
    ├── ToolAuditWriter.java           # [已落地]
    ├── ToolResult.java                # [已落地]
    ├── ToolSchemaProvider.java        # [已落地]
    └── tool/
        ├── ToolRegistry.java          # [已落地]  含 find()
        ├── ToolRegistration.java      # [已落地]
        └── ToolDefinition.java        # [已落地]

oryxos-tool/
└── src/main/java/io/oryxos/tool/
    ├── sandbox/                       # [已落地]  完整接口 + WhitelistSandbox
    │   ├── Sandbox.java
    │   ├── ActionType.java
    │   ├── SandboxAction.java
    │   ├── SandboxProperties.java
    │   ├── SandboxViolationException.java
    │   └── WhitelistSandbox.java
    ├── notify/                        # [已落地]  Notify 子能力（004 spec）
    │   ├── NotifyTool.java
    │   ├── WebhookNotifyAdapter.java
    │   ├── NotifyResult.java
    │   └── UrlRedactor.java
    ├── file/                          # [NEW]     文件 I/O 内置 Tool
    │   ├── FileReadTool.java
    │   ├── FileWriteTool.java
    │   └── FileListTool.java
    ├── shell/                         # [NEW]     Shell 执行内置 Tool
    │   └── ShellTool.java
    ├── http/                          # [NEW]     HTTP 客户端内置 Tool
    │   ├── HttpGetTool.java
    │   ├── HttpPostTool.java
    │   └── HttpToolProperties.java    # 共享 timeout 等配置
    ├── memory/                        # [NEW]     Memory 工具（封装 MemoryService）
    │   ├── SaveMemoryTool.java
    │   └── RecallMemoryTool.java
    └── mcp/                           # [NEW]     MCP 接入
        ├── McpClientService.java
        ├── McpClientProperties.java
        ├── McpToolAdapter.java
        └── McpServerConnection.java

oryxos-storage/
└── src/main/java/io/oryxos/storage/entity/
│   └── ToolInvocationRecord.java      # [+1 字段 source]
└── src/main/resources/db/migration/
    └── V3__add_tool_source.sql        # [NEW]     ALTER TABLE ADD COLUMN source

oryxos-boot/
└── src/main/java/io/oryxos/boot/config/
    ├── NotifyToolConfig.java          # [已落地]  @Primary @Bean ToolRegistry
    ├── ToolSystemConfig.java          # [NEW]     HttpToolProperties / McpClientProperties 装配
    └── McpClientConfig.java           # [NEW]     MCP server 连接池 / 启动期握手

oryxos-cli/
└── src/main/java/io/oryxos/cli/command/
    └── ToolListCommand.java           # [调整]   列出所有 9 个 Tool（含 source 标记）
```

**结构决策**：Option 1（单项目 / library）的变体——所有变更在已有 Maven 多模块内，不引入新顶层目录；不改 Spring Boot 主类。`oryxos-tool` 模块按工具族分包（`file/` / `shell/` / `http/` / `notify/` / `memory/` / `mcp/`），每个子包独立自治，但**不**升级为 Maven 子模块（[CLAUDE.md §V 边界澄清](../CLAUDE.md) §V 原意）。

---

## 5. Complexity Tracking

> 仅在 Constitution Check 有违规需要解释时填写。本 spec 无违规（VI 的"条件合规"已在 §3.6 标注 V3 DDL 路径，进入 plan 阶段），**本节留空**——模板保留占位行。

| Violation | Why Needed | Simpler Alternative Rejected Because |
| --------- | ----------- | ------------------------------------- |
| （无） | — | — |
