# 实施计划：Notify 出站推送（US-4 子能力）

**分支**：`004-notify-channel` | **日期**：2026-07-25 | **Spec**：[spec.md](./spec.md)

**输入**：来自 [spec.md](./spec.md) 的功能规格说明。

> **关于 §3 Constitution Check 字段**：本文件 §3（Constitution Check）**完整保留**模板原样的字段占位（"Gates determined based on constitution file"），同时**新增** §3.1—§3.9 一节填写本特性的实际合规结论，避免与模板字段混淆。

---

## 1. Summary

Notify 是 OryxOS 出站推送的统一出口（[CLAUDE.md §9.5](../CLAUDE.md)）。本 spec 在 US-4 Plugin Tool 体系内独立落地 Notify 子能力，覆盖：

- 一个 `OryxTool` 实现 `NotifyTool`（LLM 通过 Function Calling 调用）
- 一个出站适配器 `WebhookNotifyAdapter`（HTTP POST + JSON，覆盖企业微信/钉钉/飞书群机器人）
- 一个最小可用 `Sandbox` 抽象与 `WhitelistSandbox` 实现（`HTTP_REQUEST` 白名单）
- `tool_invocations` 表新增 2 列（`channel` / `notify_status_code`）
- Profile `notify_channels[]` 配置项 + 路由规则（含广播语义）

**核心 trade-off**：本 spec 不依赖 US-4 主 plan（FileTools / ShellTools / MCP / HttpTools）——直接实现最小可用 Sandbox，使 Notify 可独立 demo。三个验收 Demo（每日天气 / 每日科技日报 / 每日 GitHub 日报，[CLAUDE.md §11](../CLAUDE.md)）都依赖 Notify 把结果推到群机器人；Notify 落地后，三个 Demo 的最后一步"主动推送"才成立。

详见 [research.md](./research.md) 的 R-01..R-10 决策记录。

---

## 2. Technical Context

> 以下条目按 plan-template 字段填写；任何标 "n/a" 的字段均与本 spec 无关（CLI/Web 详情由 003-cli-commands 覆盖）。

**Language/Version**：Java 21（[CLAUDE.md §4](../CLAUDE.md) 强制）；records / sealed types / virtual threads / pattern matching 全部允许使用

**Primary Dependencies**：

- `oryxos-core`（已存在；新增 `notifyChannels` 字段 + `NotifyChannelConfig` record）
- `oryxos-tool`（已存在；本 spec 大量新增代码）
- `oryxos-storage`（已存在；`ToolInvocationRecord` 新增 2 列）
- `oryxos-cli`（已存在；`ConfigLoader` 解析 `notify_channels` 字段）
- `spring-context`（Spring DI bean 装配）
- `spring-boot-autoconfigure`（`@ConfigurationProperties` 绑定 `tool.sandbox.http.allowed-domains`）
- `java.net.http.HttpClient`（JDK 内置）
- `com.fasterxml.jackson`（Spring Boot starter 自带；JSON 序列化）

**Storage**：SQLite via Spring Data JPA（沿用既有）；`tool_invocations` 表 DDL 演进（[data-model.md §8](./data-model.md)）

**Testing**：JUnit 5（沿用）；Mockito 单测；WireMock 集成测；`scripts/notify-smoke.sh` 端到端冒烟（tasks.md 阶段创建）

**Target Platform**：Linux server / Windows server（与 US-1 / US-2 一致；JDK 21 跨平台）

**Project Type**：library（`oryxos-tool` 模块作为 Spring bean 集合）+ embedded CLI（`oryxos chat` 触发链路）；不属于独立 web 服务（[CLAUDE.md §5](../CLAUDE.md)）

**Performance Goals**：

- 单条 notify wall-time P95 ≤ 3 秒（spec SC-004）
- 10 通道广播 wall-time P95 ≤ 5 秒（spec SC-004）
- ReAct 循环其他迭代不被 Notify 阻塞（spec NFR-003）

**Constraints**：

- 仅 HTTP webhook 一种通道类型（spec FR-002；扩展阶段再支持 SMTP / Slack native 等）
- 不重试（spec FR-011；扩展阶段再支持指数退避）
- 不签名（spec FR-013；扩展阶段再支持 HMAC）
- 单 binary fat JAR 部署（[CLAUDE.md §4](../CLAUDE.md)）
- 不新增 Maven 模块（Constitution §I）

**Scale/Scope**：本 spec 不引入新外部服务；webhook 调用频率跟随三个 Demo 的频率（每日 1 次 / Profile × Agent 数量，量级 ≤ 100 次/天）。

---

## 3. Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

> **模板原字段**（保留）：Gates determined based on constitution file
>
> 模板期望本节列出"基于 constitution 文件派生的 Gates"。本 spec 把这一内容移到 §3.1—§3.9 七节，每节对应宪法 §I—§VII 一条原则；模板字段本身不再赘述。

---

### 3.1 原则 I — Single-Stack Monolith（JDK 21 + Spring Boot 3.x）

✅ **合规**。

- JDK 21（Language/Version 已声明）
- Spring Boot 3.x（[CLAUDE.md §4](../CLAUDE.md)）
- 9 模块不动；本 spec 不新增 Maven 模块
- 单 fat JAR 部署；`mvn -pl oryxos-boot -am package` 仍出单 JAR

### 3.2 原则 II — Core-Stage Scope Discipline（五大核心能力优先）

✅ **合规**。

- Notify 是 US-4「Plugin Tool」的内置 Tool 之一（[CLAUDE.md §6](../CLAUDE.md) 第 4 能力）
- 不引入扩展阶段能力：多租户 / SSO / 完整审计 UI / Tool Policy / Web 仪表板 / 集群 HA — 全部明确不在范围内
- spec"不在范围内"节显式列出 7 个延后项

### 3.3 原则 III — Self-Implemented ReAct Loop

✅ **合规**。

- 本 spec 不修改 `ReActLoop` 本身；只通过 `DefaultToolExecutor` 派发路径接入
- ReAct 循环的最大迭代次数、Tool 消息语义、审计时机均沿用 US-2 已落地契约

### 3.4 原则 IV — Spring AI Used at Half-Strength（禁用自动 tool 执行）

✅ **合规**。

- `ToolSchemaProvider` 翻译 Notify 的 Function Calling schema（仅 schema 生成）
- 工具派发由 `DefaultToolExecutor` 触发 → `NotifyTool.execute()`，**不**依赖 Spring AI 自动执行
- 不会触发 tool 被调两次的已知坑（[CLAUDE.md §8.1](../CLAUDE.md)）

### 3.5 原则 V — Three-Tier Plugin Tooling

✅ **合规**（边界澄清后）。

- **零代码**：`notify` Tool 通过 Profile `tools: [notify]` + `notify_channels[]` 即可用——无需写 Java
- **轻代码**：扩展阶段支持自定义 webhook 签名（不覆盖）
- **重代码**：本 spec 的 `NotifyTool` / `WebhookNotifyAdapter` 都是 Java `@Component`，符合三档框架
- **模块归属**（[CLAUDE.md §5 §V 边界澄清](../CLAUDE.md)）：
  - Tool **抽象**（`OryxTool` / `ToolRegistry` / `ToolRegistration` / `ToolDefinition` / `ToolExecutor` / `ToolSchemaProvider`）归 `oryxos-core`
  - Tool **实现 + 基础设施**（`NotifyTool` / `WebhookNotifyAdapter` / `WhitelistSandbox` 等）归 `oryxos-tool`
  - 注册表装配 `@Bean ToolRegistry` 归 `oryxos-boot`（`NotifyToolConfig`）
- 不拆 `builtin-tools` / `skill-tools` / `mcp-tools` 子模块（§V 原意）
- `AGENT.md` 加载归 `oryxos-core` 的 `ContextLoader`，**不**当作 Tool

### 3.6 原则 VI — SQLite + MEMORY.md with Day-One Audit Persistence

✅ **合规**。

- `tool_invocations` 表是 day-one 表（[CLAUDE.md §13](../CLAUDE.md)）
- 本 spec 在该表上**新增 2 列**（`channel` / `notify_status_code`），不破坏既有不变量
- 每次 `notify` 调用必产 1 行审计（spec FR-009）
- **不**新增 `notify_invocations` 表（[research.md R-09](./research.md)）；审计统一收口
- DDL 用手动 SQL（[CLAUDE.md §13 风险提示](../CLAUDE.md)）；不回退 `hibernate.ddl-auto=update`

### 3.7 原则 VII — Demo-First Delivery（跑通优先于完美）

✅ **合规**。

- 本 spec 的核心验收标准 = 三个 Demo 的最后一步闭环（[CLAUDE.md §11](../CLAUDE.md)）
- [quickstart.md](./quickstart.md) 第 1-10 步给出端到端可演示路径（WireMock 模拟）
- 不追求完美（不重试 / 不签名 / 不支持 SMTP / 不支持飞书 v2 hook）—— 与跑通优先一致

---

### 3.8 附加约束（"不要做的事"）

✅ **全部合规**。

- ❌ 不使用 `SecurityManager` — 本 spec 用应用层 `Sandbox`
- ❌ 不硬编码 API key — `notify_channels[*].url` 用 `${ENV_VAR}` 占位（[contracts/channel-config.md §3](./contracts/channel-config.md)）
- ❌ 不依赖 `hibernate.ddl-auto=update` 演进 schema — DDL 手动 SQL 维护（[data-model.md §8](./data-model.md)）
- ❌ 不扫描容器类型区分 Provider — 本 spec 不涉及 Provider；Notify 透传 `ProfileContext` 拿到的 Profile
- ❌ 不把 Session 与 Memory 合并 — 本 spec 不涉及 Memory
- ❌ 不用非 JDK 21 特性 — records / virtual threads 都是 JDK 21 stable；不使用 preview 特性

---

### 3.9 Constitution Check 总结

| 原则 | 状态 | 备注 |
| ---- | ---- | ---- |
| I. Single-Stack Monolith | ✅ | JDK 21 + 9 模块 + 单 JAR |
| II. Core-Stage Scope | ✅ | US-4 子能力；7 项延后能力已列 |
| III. Self-Implemented ReAct | ✅ | 不改 ReActLoop；仅扩展派发路径 |
| IV. Spring AI Half-Strength | ✅ | 仅 schema 生成；不自执行 |
| V. Three-Tier Plugin Tooling | ✅ | 零代码/轻代码/重代码三档就位 |
| VI. SQLite + Day-One Audit | ✅ | 复用 tool_invocations；新增 2 列 |
| VII. Demo-First | ✅ | quickstart 端到端可演示 |

**GATE 结果**：✅ 全部通过；可进入 Phase 0 research 与 Phase 1 design。

---

## 4. Project Structure

### 4.1 Documentation（本次特性）

```text
specs/004-notify-channel/
├── plan.md              # 本文件
├── research.md          # Phase 0 产物
├── data-model.md        # Phase 1 产物
├── quickstart.md        # Phase 1 产物
├── contracts/           # Phase 1 产物
│   ├── notify-tool.md
│   ├── webhook-payload.md
│   └── channel-config.md
└── tasks.md             # Phase 2 产物（/speckit-tasks 阶段创建）
```

### 4.2 Source Code（仓库根）

> 本 spec 涉及的源码改动分布：

```text
oryxos-core/
└── src/main/java/io/oryxos/core/
    ├── Profile.java                      # [+1 字段]
    ├── NotifyChannelConfig.java          # [NEW]
    ├── OryxTool.java                     # [+1 default 方法]
    ├── DefaultToolExecutor.java          # [-UOE, +dispatch, +Notify audit 字段抽取]
    └── tool/                             # [NEW]  Tool 抽象门面（与 Tool impl 分离；详见 CLAUDE.md §5 §V 边界澄清）
        ├── ToolRegistry.java             # [改 of() 签名 + 新增 find()]
        ├── ToolRegistration.java         # [NEW]
        └── ToolDefinition.java           # [NEW；CLI tool list 用]

oryxos-tool/
└── src/main/java/io/oryxos/tool/
    ├── sandbox/
    │   ├── ActionType.java               # [NEW]
    │   ├── SandboxAction.java            # [NEW]
    │   ├── Sandbox.java                  # [NEW]
    │   ├── WhitelistSandbox.java         # [NEW]
    │   ├── SandboxViolationException.java # [NEW]
    │   └── SandboxProperties.java        # [NEW]
    └── notify/
        ├── NotifyTool.java               # [NEW]  OryxTool 实现
        ├── WebhookNotifyAdapter.java     # [NEW]  HTTP 出站
        ├── NotifyResult.java             # [NEW]  运行时 record
        └── UrlRedactor.java              # [NEW]

oryxos-boot/
└── src/main/java/io/oryxos/boot/config/
    └── NotifyToolConfig.java             # [NEW]  @Primary @Bean ToolRegistry 装配

oryxos-storage/
└── src/main/java/io/oryxos/storage/entity/
    └── ToolInvocationRecord.java         # [+2 字段]
└── src/main/resources/db/migration/
    └── V2__add_notify_columns.sql        # [NEW]

oryxos-cli/
└── src/main/java/io/oryxos/cli/config/
    └── ConfigLoader.java                 # [+notify_channels 解析]
```

**结构决策**：Option 1（单项目 / library）的变体——所有变更在已有 Maven 多模块内，不引入新顶层目录；不改 Spring Boot 主类。

---

## 5. Complexity Tracking

> 仅在 Constitution Check 有违规需要解释时填写。本 spec 无违规，**本节留空**——模板保留占位行。

| Violation | Why Needed | Simpler Alternative Rejected Because |
| --------- | ----------- | ------------------------------------- |
| （无） | — | — |
