# Implementation Plan: CLI 命令行入口

**Branch**: `003-cli-commands` | **Date**: 2026-07-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-cli-commands/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

本 US 交付 [CLAUDE.md §14](../CLAUDE.md) 表中列出的 12 个 Picocli 命令（[FR-001](../003-cli-commands/spec.md)），作为企业 OryxOS 实例的命令行入口。CLI 复用 US-1 / US-2 的 day-one 实体（`Profile` / `Session` / `ToolInvocation` 等），**不**新增 SQLite 表；新增 3 个内存瞬时态结构（[data-model.md](data-model.md) §2-§4：`WorkspaceLayout` / `CommandInvocation` / `SpringContextHandle`）。技术栈以 JDK 21 + Spring Boot 3.x + Picocli + SnakeYAML 为基（[Constitution §I](../.specify/memory/constitution.md)），零 Spring / 必须 Spring 双路径共用一个 `Main` 类（[research.md 决策 2](research.md)）。所有架构 / 模块 / 选型 / 退出码 / 测试 / 审计的决策都落在 [research.md](research.md) + [data-model.md](data-model.md) + [contracts/](contracts/) + [quickstart.md](quickstart.md)，**plan.md 仅承载 Constitution Check + Project Structure 收口**。

## Technical Context

**Language/Version**: Java 21（含 records / sealed / virtual threads / sequenced collections） — [Constitution §I](../.specify/memory/constitution.md) + [NFR-004](../003-cli-commands/spec.md)

**Primary Dependencies**:

- [info.picocli:picocli](https://picocli.info) ≥ 4.7.6（[research.md 决策 3](research.md) — 不引 `picocli-spring-boot-starter`）
- [org.yaml:snakeyaml](https://www.snakeyaml.org) ≥ 2.2（[FR-014](../003-cli-commands/spec.md) — Profile YAML + `${ENV_VAR}` 替换）
- [org.springframework.boot:spring-boot](https://spring.io/projects/spring-boot) ≥ 3.3.5（既有的 [CLAUDE.md §4](../CLAUDE.md) 栈）
- [org.slf4j:slf4j-api](https://www.slf4j.org) + Logback（既有的 [CLAUDE.md §4](../CLAUDE.md) 栈）

**Storage**:

- SQLite（既有 [Constitution §VI](../.specify/memory/constitution.md) day-one 五表 — `sessions` / `tool_invocations` / `llm_calls` / `scheduled_tasks` / `task_executions`，**不**新增表）
- `MEMORY.md` 文件（既有的 Markdown 长期记忆）
- `.oryxos/logs/oryxos-cli.log` + `oryxos-cli-error.log`（[research.md 决策 10](research.md)）

**Testing**:

- JUnit 5 + AssertJ（既有）
- WireMock（既有，[SC-008](../003-cli-commands/spec.md) 集成测试）
- `mockito-core`（T032 `SessionListCommandTest` 用以 mock `SessionRepository.findAll(...)`，避免 H2/Testcontainers 的重型测试装置）
- `scripts/cli-smoke.sh`（[research.md 决策 8](research.md) 新增 — 端到端冒烟）
- 不引新测试框架

**Target Platform**:

- Linux x86_64 / macOS arm64 / Windows x64（[A-008](../003-cli-commands/spec.md) — 跨平台）
- Java 21 运行时（Constitution §I）

**Project Type**: `cli`（9 模块 Maven 多模块，[Constitution §I](../.specify/memory/constitution.md) + [CLAUDE.md §5](../CLAUDE.md)）

**Performance Goals**:

- 零 Spring 命令 ≤ 200 ms 首输出（[SC-003 / SC-004 / FR-013](../003-cli-commands/spec.md)）
- Spring 启动命令 ≤ 5 s 首输出（[FR-013](../003-cli-commands/spec.md)）
- `chat` 端到端 ≤ 30 s（[SC-001](../003-cli-commands/spec.md)）
- CLI 不在主路径上做缓存（day-one 不需要）；扩展阶段可加 LLM response 缓存
- **CI 缓冲**：测试预算用 SC 目标的 10×（SC-003 200 ms → 测 2 000 ms / SC-004 200 ms → 测 2 000 ms / SC-005 2 s → 测 20 s），保证重负载 CI runner 不抖动；想收紧请显式 review 后再改 `PerformanceBaselineTest` 的常量。

**Constraints**:

- 不用 `picocli-spring-boot-starter`（[research.md 决策 3](research.md)）
- 不用 Spring AI 的 Agent 抽象 / 自动 tool 执行（[NFR-002 / NFR-003](../003-cli-commands/spec.md) + [Constitution §III / §IV](../.specify/memory/constitution.md)）
- 不用 `java.lang.SecurityManager`（[Constitution §硬约束](../.specify/memory/constitution.md)）
- 不用 `hibernate.ddl-auto=update` 做表结构演进（[CLAUDE.md §18](../CLAUDE.md) + [Constitution §硬约束](../.specify/memory/constitution.md)）
- API key 不入 stdout / 日志 / `status` 默认输出（[FR-020](../003-cli-commands/spec.md) + [Constitution §硬约束](../.specify/memory/constitution.md)）
- 不新增第 10 个模块（[Constitution §I](../.specify/memory/constitution.md) + [NFR-001](../003-cli-commands/spec.md)）

**Scale/Scope**:

- CLI 启动到第一行输出 ≤ 200 ms（零 Spring）/ ≤ 5 s（必须 Spring）
- 12 个子命令
- 启动后持有一个 `ConfigurableApplicationContext`（按需）
- 8 个契约文件（contracts/）+ 1 个 quickstart
- 0 个新增 SQLite 表
- 3 个新增内存瞬时态（`WorkspaceLayout` / `CommandInvocation` / `SpringContextHandle`）
- 0 个跨模块新增公共 API

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

|原则|状态|证据|
|---|---|---|
|**§I. Single-Stack Monolith (JDK 21 + Spring Boot 3.x)**|✅ PASS|9 模块 Maven 多模块，**不**新增；本 US 只动 `oryxos-cli` 与 `oryxos-channel-cli` 依赖（[research.md 决策 9](research.md)）。`java -jar oryxos-boot` 单一 fat JAR 入口（[NFR-005](../003-cli-commands/spec.md)）|
|**§II. Core-Stage Scope Discipline (五大核心能力优先)**|✅ PASS|本 US 不碰多租户 / SSO / 完整审计 / Tool Policy / Web 仪表板 / 集群；`serve` / `gateway` 仅做 stub（[FR-008](../003-cli-commands/spec.md)）|
|**§III. Self-Implemented ReAct Loop**|✅ PASS|`chat` 命令**不**直接持有 `ChatModel` Bean；通过 `AgentService.process()` 间接驱动（[FR-002 / NFR-002](../003-cli-commands/spec.md)）|
|**§IV. Spring AI Used at Half-Strength**|✅ PASS|① 不引 `picocli-spring-boot-starter`（[research.md 决策 3](research.md)）；② 不启用 Spring AI 自动 tool 执行（[NFR-003](../003-cli-commands/spec.md)）；③ Provider name → `ChatModel` 走显式映射（既有 US-1，本 US 不动）|
|**§V. Three-Tier Plugin Tooling**|✅ PASS|本 US 不引入 Tool 相关代码（[research.md 决策 9](research.md)）；`tool list` 只**列**已注册的 Tool Bean，**不**实现 / 测试；`AGENT.md` 加载归 `oryxos-core` 的 `ContextLoader`（既有）。**注**：为了让 `oryxos tool list` 能从 DI 容器拿到 bean，`oryxos-tool/ToolRegistry.java` + `ToolDefinition.java` 作为最小接口已被加进 `oryxos-tool` 模块（不包含任何 Tool 实现）；US-4 阶段填入真实的 9 个 built-in + MCP + SKILL.md 三档工具。|
|**§VI. SQLite + MEMORY.md with Day-One Audit**|✅ PASS|① 复用 5 张表（[data-model.md §6](data-model.md)），**不**新增；② `CommandInvocation` 仅落日志（[data-model.md §3.2](data-model.md)）；③ `chat` 命令经 `AgentService` 自动写 `sessions` / `llm_calls` / `tool_invocations`|
|**§VII. Demo-First Delivery**|✅ PASS|[quickstart.md](quickstart.md) 9 场景对应 8 条 SC；`scripts/cli-smoke.sh` 一键跑全平台冒烟（[A-008](../003-cli-commands/spec.md)）|
|**§硬约束 — SecurityManager**|✅ PASS|不引入；CLI 不调用|
|**§硬约束 — API key 不硬编码**|✅ PASS|`${ENV_VAR}` 占位符走 `ConfigLoader`（[FR-014 / FR-020](../003-cli-commands/spec.md)）；缺 key → exit 69|
|**§硬约束 — hibernate.ddl-auto=update**|✅ PASS|本 US 不改 schema|
|**§硬约束 — ChatModel 容器扫描**|✅ PASS|本 US 走 `ProviderService.allProviders()`（既有 US-1 显式映射）|
|**§硬约束 — Session ≠ 长期 Memory**|✅ PASS|本 US 不动 MemoryService；`MEMORY.md` 由 US-3 接管|
|**§硬约束 — JDK 21 特性**|✅ PASS|计划用 records（`WorkspaceLayout` / `CommandInvocation` / `SpringContextHandle`）+ sealed 不引入（`ChatCommand` 等命令类用普通 `final class`）|

**Constitution Check 结论**：✅ **ALL PASS**。无需走 Complexity Tracking 通道。

## Project Structure

### Documentation (this feature)

```text
specs/003-cli-commands/
├── plan.md              # 本文件
├── spec.md              # 已生成
├── research.md          # Phase 0 — 10 个技术决策
├── data-model.md        # Phase 1 — 3 个新增实体 + 5 条不变量
├── quickstart.md        # Phase 1 — 9 个端到端场景 + smoke 脚本
├── contracts/           # Phase 1 — 12 命令的契约
│   ├── _index.md
│   ├── chat.md
│   ├── init.md
│   ├── status.md
│   ├── profile.md
│   ├── provider.md
│   ├── tool.md
│   ├── session.md
│   └── serve.md
├── checklists/
│   └── requirements.md # 已生成（16/16 PASS）
├── evidence/            # /speckit-analyze 与 /speckit-converge 输出
└── tasks.md             # Phase 2 — /speckit-tasks 生成
```

### Source Code (repository root)

```text
oryxos-cli/                                    # 既有模块 — 本 US 增量
├── pom.xml                                    # +1 依赖 (picocli + snakeyaml)
├── src/main/java/io/oryxos/cli/
│   ├── OryxOsCli.java                         # Picocli 根入口；按子命令决定是否启 Spring（pom.xml 的 <mainClass> 指这里）
│   ├── config/
│   │   ├── ConfigLoader.java                  # YAML + ${ENV_VAR} 替换（决策 4）
│   │   └── MissingEnvVarException.java
│   ├── workspace/
│   │   ├── WorkspaceLayout.java               # record（决策 4）
│   │   └── NotInitializedException.java
│   ├── diag/
│   │   └── CommandInvocation.java             # record（决策 7）
│   ├── spring/
│   │   ├── SpringContextHandle.java           # AutoCloseable 包 ctx（决策 2）
│   │   └── BootCommandLineRegistrar.java      # 启动 Spring 后注册 Spring 持有的子命令
│   ├── command/
│   │   ├── CommandBase.java                   # 零 Spring 命令基类
│   │   ├── CommandSpringBase.java             # 必须 Spring 命令基类
│   │   ├── InitCommand.java                   # 零 Spring（init.md）
│   │   ├── StatusCommand.java                 # 零 Spring（status.md）
│   │   ├── ChatCommand.java                   # 必须 Spring（chat.md）
│   │   ├── ServeCommand.java                  # stub（serve.md）
│   │   ├── GatewayCommand.java                # stub（serve.md）
│   │   ├── ProfileCommand.java                # 0-Spring CRUD（profile.md）
│   │   ├── ProviderListCommand.java           # Spring（provider.md）
│   │   ├── ToolListCommand.java               # Spring（tool.md）
│   │   └── SessionListCommand.java            # Spring（session.md）
│   └── exitcode/
│       └── Sysexits.java                      # 退出码常量
├── src/main/resources/
│   ├── logback.xml                            # oryxos-cli logger
│   └── templates/                             # profile --template 的骨架
│       ├── minimal.md
│       ├── weather.md
│       ├── tech-digest.md
│       └── github-pr-digest.md
└── src/test/java/io/oryxos/cli/
    ├── MainHelpTest.java                      # 12 命令 --help 烟雾
    ├── ConfigLoaderTest.java                  # YAML + ${ENV_VAR} 替换
    ├── InitCommandTest.java                   # init happy + idempotent
    ├── StatusCommandTest.java                 # 3 退出码分级
    ├── ChatCommandIT.java                     # WireMock + Spring 集成
    ├── ProfileCommandTest.java                # 4 子命令
    ├── SysexitsTest.java                      # 退出码契约
    └── StderrOnlyTest.java                    # FR-010

oryxos-channel-cli/                            # 既有模块 — 本 US 不动
└── (本 US 仅占位依赖，US-3+ 接入 CliChannel)

oryxos-boot/                                   # 既有模块 — 本 US 不动
└── (OryxosApplication.java 已是 Spring Boot 主类；CLI 复用)

scripts/                                       # 新增
├── cli-smoke.sh                               # 一键跑 quickstart 9 场景（1 驱动脚本 6 子场景）
└── cli-smoke/                                 # 独立片段
    ├── 01-init.sh                             # 快速 init + 二次跑 + diff
    ├── 02-profile-crud.sh                     # profile list / show / create / delete
    └── 03-status.sh                           # status 三档退出码验证
```

**Structure Decision**：单 `oryxos-cli` 模块承载全部 CLI 命令；按"零 Spring / 必须 Spring / stub"分三组抽象基类（`CommandBase` / `CommandSpringBase` / `ServeCommand` / `GatewayCommand`）。

**冒烟脚本组织**：`scripts/cli-smoke.sh` 是 1 个驱动脚本（6 个子场景）+ 3 个独立片段（`01-init` / `02-profile-crud` / `03-status`），共 9 条端到端路径，对应 [quickstart.md](quickstart.md) 9 场景；CI matrix 三平台都跑驱动 + 三个片段（见 [`.github/workflows/cli-smoke.yml`](../../../.github/workflows/cli-smoke.yml)）。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无。Constitution Check 全部通过；无任何 violations 需要 justify。

## Appendix: 与本 US 相关的已有证据

|文件|用途|
|---|---|
|[specs/002-react-loop/evidence/T066-analyze.md](../002-react-loop/evidence/T066-analyze.md)|US-2 analyze 报告（`ToolAuditWriter` 接口先在 core 定义、JPA 落库在 storage 的设计）|
|[specs/002-react-loop/evidence/T075-jpa-tool-audit-writer.md](../002-react-loop/evidence/T075-jpa-tool-audit-writer.md)|US-2 / US-5 延期清单 —— 本 US 不引入新表，符合"audit day one 在 storage，CLI 不动"|
|[CLAUDE.md §18 / §21](../CLAUDE.md)|"不要做的事" 14 条 + Spec Kit 输出中文约束（已在 spec.md 中落实）|
|[Constitution §I](../.specify/memory/constitution.md)|9 模块 = `oryxos-cli` + `oryxos-channel-cli` 既有，本 US 不新增模块|

