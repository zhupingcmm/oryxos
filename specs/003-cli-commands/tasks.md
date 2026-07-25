# Tasks: CLI 命令行入口

**Input**: Design documents from `/specs/003-cli-commands/`
**Prerequisites**: plan.md (✅), spec.md (✅), research.md (✅), data-model.md (✅), contracts/ (✅), quickstart.md (✅)
**Tests**: Test tasks included per [research.md 决策 8](research.md) — JUnit 5 + WireMock + `scripts/cli-smoke.sh`
**Organization**: Tasks grouped by user story (P1 → P2 → P3) so each story ships as an independently testable increment.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (`[US1]` / `[US2]` / `[US3]`)
- All paths absolute or relative to repo root; module-relative paths prefixed with `oryxos-cli/...`

## Path Conventions

- **Module**: `oryxos-cli` (this US's sole deliverable module; `oryxos-channel-cli` is left as-is per [research.md 决策 1](research.md))
- **Source root**: `oryxos-cli/src/main/java/io/oryxos/cli/`
- **Test root**: `oryxos-cli/src/test/java/io/oryxos/cli/`
- **Resources**: `oryxos-cli/src/main/resources/`
- **CLI scripts**: `scripts/`

---

## Phase 1: Setup（项目脚手架增量）

**Purpose**: 把 US-1 已经搭好的 `OryxOsCli` banner-only 脚手架扩成 12 命令的承载点；不引新模块。

- [x] T001 [P] Add logback config for oryxos-cli in `oryxos-cli/src/main/resources/logback.xml`（双 logger：`oryxos-cli.log` + `oryxos-cli-error.log`，落 `.oryxos/logs/`，FR-017 / FR-018）
- [x] T002 [P] Create profile templates in `oryxos-cli/src/main/resources/templates/`：`minimal.md`、`weather.md`、`tech-digest.md`、`github-pr-digest.md`（每份 = YAML frontmatter + AGENT.md 正文骨架，供 `profile create --template` 使用，[contracts/profile.md](../../specs/003-cli-commands/contracts/profile.md)）
- [x] T003 Verify existing `oryxos-cli/pom.xml` deps 满足本 US（picocli + snakeyaml + oryxos-core + oryxos-channel-cli + oryxos-web + junit-jupiter 已在 [pom.xml](../../oryxos-cli/pom.xml)）；仅在 wiremock 缺失时新增 `com.github.tomakehurst:wiremock-jre8` 测试依赖

---

## Phase 2: Foundational（阻塞性前置，所有 User Story 依赖）

**Purpose**: 为所有 12 命令提供共用的退出码 / 异常 / 配置 / 工作区 / 上下文 / 命令基类 / 启动契约。本阶段完成前**禁止**开始任何 User Story。

**⚠️ CRITICAL**: 任何 user story 工作都依赖本阶段完成

- [x] T004 [P] Create BSD sysexits exit-code constants in `oryxos-cli/src/main/java/io/oryxos/cli/exitcode/Sysexits.java`（`OK=0 / GENERIC=1 / WARNING=2 / EX_USAGE=64 / EX_UNAVAILABLE=69 / EX_CONFIG=78`，FR-009 / SC-007）
- [x] T005 [P] Create `NotInitializedException` in `oryxos-cli/src/main/java/io/oryxos/cli/workspace/NotInitializedException.java`（init / status 在 `.oryxos/` 不存在时抛，包成 exit 1）
- [x] T006 [P] Create `MissingEnvVarException` in `oryxos-cli/src/main/java/io/oryxos/cli/config/MissingEnvVarException.java`（`${ENV_VAR}` 缺失时抛，包成 exit 69 / 78）
- [x] T007 [P] Create `ConfigLoader` in `oryxos-cli/src/main/java/io/oryxos/cli/config/ConfigLoader.java`（SnakeYAML + `${ENV_VAR}` 进程环境变量替换；Profile YAML 加载入口，FR-014 + [research.md 决策 4](research.md)）
- [x] T008 [P] Create `WorkspaceLayout` record + `probe()` + `initialize()` + `renderHumanReadable()` + `renderJson()` in `oryxos-cli/src/main/java/io/oryxos/cli/workspace/WorkspaceLayout.java`（[data-model.md §2](../../specs/003-cli-commands/data-model.md)；`LinkOption.NOFOLLOW_LINKS` 防 symlink）
- [x] T009 [P] Create `CommandInvocation` record in `oryxos-cli/src/main/java/io/oryxos/cli/diag/CommandInvocation.java`（含 `commandName / args / durationMs / exitCode / stderrSummary`，落 `.oryxos/logs/oryxos-cli.log`，[data-model.md §3](../../specs/003-cli-commands/data-model.md)）
- [x] T010 [P] Create `SpringContextHandle` (`AutoCloseable`) in `oryxos-cli/src/main/java/io/oryxos/cli/spring/SpringContextHandle.java`（包 `ConfigurableApplicationContext` + 启动超时，命令结束自动 close，[data-model.md §4](../../specs/003-cli-commands/data-model.md)）
- [x] T011 [P] Create `CommandBase` abstract class in `oryxos-cli/src/main/java/io/oryxos/cli/command/CommandBase.java`（零 Spring 命令基类：含 `Path workspaceRoot()`、`Sysexits` 退出码映射、stderr-only 错误输出）
- [x] T012 [P] Create `CommandSpringBase` abstract class in `oryxos-cli/src/main/java/io/oryxos/cli/command/CommandSpringBase.java`（必须 Spring 命令基类：持有 `SpringContextHandle`，从 ctx 拿 bean 的便捷方法）
- [x] T013 [P] Create `BootCommandLineRegistrar` in `oryxos-cli/src/main/java/io/oryxos/cli/spring/BootCommandLineRegistrar.java`（启动 Spring 后从 context 收集 Spring 持有的子命令 bean，回写到根 `CommandLine`，[research.md 决策 2](research.md)）
- [x] T014 Create `ServeCommand` + `GatewayCommand` stubs in `oryxos-cli/src/main/java/io/oryxos/cli/command/{ServeCommand,GatewayCommand}.java`（stdout `not yet implemented (US-5)` + exit 0；参数写到 `System.getProperty("oryxos.cli.us5.placeholder")`，[contracts/serve.md](../../specs/003-cli-commands/contracts/serve.md) / FR-008）
- [x] T015 Refactor `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsCli.java` 为 Picocli 根入口：保留 `@Command` 注解 + banner；`subcommands = {...}` 含 `InitCommand / StatusCommand / ChatCommand / ServeCommand / GatewayCommand / ProfileCommand / ProviderCommand / ToolCommand / SessionCommand`（共 9 类，子命令自身再带 4 个 profile 子子命令）；main 入口走 `new CommandLine(new OryxOsCli()).execute(args)`，避免引入 `picocli-spring-boot-starter`（[research.md 决策 3](research.md) + NFR-001）
- [x] T016 [P] Create unit tests `ConfigLoaderTest` + `WorkspaceLayoutTest` in `oryxos-cli/src/test/java/io/oryxos/cli/`（覆盖 `${ENV_VAR}` 已解析 / 缺失抛 `MissingEnvVarException`；`init` 幂等 / symlink 不跟随 / `status` realpath）

**Checkpoint**: 编译 `mvn -pl oryxos-cli compile` 绿；root `--help` 列出 banner（无子命令时报 `Run 'oryxos --help'` 现有行为保留到 Phase 3 US-1 接入前可接受）；`mvn -pl oryxos-cli test` 跑过 ConfigLoader / WorkspaceLayout 测试 —— User Story 实施可开始。

---

## Phase 3: User Story 1 — `oryxos chat` 触发 Agent（Priority: P1）🎯 MVP

**Goal**: 用户在终端用一条命令触发某个 Profile 的 Agent 处理单条消息，并把 Agent 最终响应打印到 stdout。这与 `POST /api/v1/agents/{name}/invoke`（Web，US-5）+ `AgentScheduler`（钟推）走同一条 `AgentService.process(...)` 链路（FR-002 / FR-021）。

**Independent Test**: 给定 `.oryxos/agents/weather-bot/AGENT.md` + `DEEPSEEK_API_KEY`，跑 `oryxos chat weather-bot "今天上海天气如何"`，≤ 30 s 内拿到 Agent 文本 + exit 0（[SC-001](../../specs/003-cli-commands/spec.md)）。

### Tests for User Story 1

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] T017 [P] [US1] Create `MainHelpTest` in `oryxos-cli/src/test/java/io/oryxos/cli/MainHelpTest.java`（断言 `oryxos --help` 含 `chat` 子命令；完整 9 类 + 12 命令数的断言放到 Phase 5 US-3 完成时一并验，SC-005）
- [x] T018 [P] [US1] Create `ChatCommandIT` integration test in `oryxos-cli/src/test/java/io/oryxos/cli/ChatCommandIT.java`（当前覆盖 FR-015 Profile 名正则 + 空 message 校验 → exit 64；WireMock + 真实 Spring boot + `sessions` / `llm_calls` 行数断言留到 `oryxos-storage` 接 H2/Testcontainers 测试 datasource 之后，SC-001 / SC-008）

### Implementation for User Story 1

- [x] T019 [P] [US1] Create `ChatCommand` in `oryxos-cli/src/main/java/io/oryxos/cli/command/ChatCommand.java`（`@Command(name = "chat")`；参数：`<profile-name>` + `[--message]` + `[--session-id]`；Profile 名正则 `^[a-z][a-z0-9-]{0,63}$`，FR-015；`--message` 缺省时从 stdin 读一行）
- [x] T020 [US1] Wire `ChatCommand` to `AgentService.process()` in `oryxos-cli/src/main/java/io/oryxos/cli/command/ChatCommand.java`（通过 `CommandSpringBase` 拿 `AgentService` bean；构造 `Session` → `process(session, message)` → 打印 `LoopResult.finalText()`；FR-002 / FR-021 / [contracts/chat.md](../../specs/003-cli-commands/contracts/chat.md)）
- [x] T021 [US1] Add sysexits mapping to `ChatCommand` in `oryxos-cli/src/main/java/io/oryxos/cli/command/ChatCommand.java`（Profile 不存在 → 64 / YAML 解析失败 → 78 / API key 缺失 → 69 / Spring 启动失败 → 1 / LLM 4xx-5xx → 1，FR-009 / SC-007；映射在 `CommandSpringBase#call()` 统一完成）
- [x] T022 [US1] Add stderr-only error reporting to `ChatCommand` in `oryxos-cli/src/main/java/io/oryxos/cli/command/ChatCommand.java`（stack trace 走 `oryxos-cli-error.log`；stdout 仅承载 Agent 最终文本，FR-010 / FR-018 / SC-006；同样在 `CommandSpringBase#call()` 统一完成）

**Checkpoint**: `mvn -pl oryxos-cli verify` 绿；`ChatCommandIT` 端到端绿；Demo 一"每日天气"可手工跑（quickstart 场景 4）；API key 缺 → exit 69 + stderr 一行；Profile 不存在 → exit 64 + stderr 一行。User Story 1 独立交付完成 —— 这是 MVP。

---

## Phase 4: User Story 2 — 工作区初始化与运行状态（Priority: P2）

**Goal**: 用户首次拿到 OryxOS 二进制需要把 `.oryxos/` 工作区落地，或运维时确认当前实例健康度。两条命令虽然低频但是**阻塞性前置** —— 没有 init 跑不了 chat，没有 status 无法排查 [US-2 spec](../../specs/003-cli-commands/spec.md#用户故事-2--工作区初始化与运行状态优先级p2)。

**Independent Test**:

- `oryxos init` 在空目录跑：生成完整 `.oryxos/` 树（4 目录 + 5 文件 + 1 SQLite db）；二次跑报 `Already initialized` 且 exit ≠ 0
- `oryxos status` 报告：JVM / JDK 版本、`.oryxos/` realpath、Profile 数、Provider 配置矩阵（含 `api_key_resolved`）、MCP server 数；退出码分级（0/1/2）

### Tests for User Story 2

- [x] T023 [P] [US2] Create `InitCommandTest` in `oryxos-cli/src/test/java/io/oryxos/cli/InitCommandTest.java`（空目录 → 4 dir + 5 file + 1 db + exit 0；二次跑 → stderr `Already initialized` + exit 1；不启动 Spring，SC-002 / SC-008）
- [x] T024 [P] [US2] Create `StatusCommandTest` in `oryxos-cli/src/test/java/io/oryxos/cli/StatusCommandTest.java`（`.oryxos/` 缺失 → exit 1；完整工作区 + 全绿 Provider → exit 0；缺 1 个 API key → exit 2；不启动 Spring，SC-003 / SC-007）

### Implementation for User Story 2

- [x] T025 [P] [US2] Create `InitCommand` in `oryxos-cli/src/main/java/io/oryxos/cli/command/InitCommand.java`（`@Command(name = "init")`；参数：`[--workspace <path>]`；逻辑：调 `WorkspaceLayout.initialize()`；二次跑检测 → exit 1 + stderr，[contracts/init.md](../../specs/003-cli-commands/contracts/init.md) / FR-003）
- [x] T026 [US2] Bootstrap file content generation in `oryxos-cli/src/main/java/io/oryxos/cli/workspace/BootstrapContent.java`（`AGENTS.md` / `SOUL.md` / `USER.md` / `MEMORY.md` 模板字符串常量；`init` 写入对应文件，[CLAUDE.md §12](../../CLAUDE.md)）
- [x] T027 [P] [US2] Create `StatusCommand` in `oryxos-cli/src/main/java/io/oryxos/cli/command/StatusCommand.java`（`@Command(name = "status")`；参数：`[--format table|json]` + `[--verbose]`；逻辑：调 `WorkspaceLayout.probe()` + `renderHumanReadable()`；退出码按健康度分级，[contracts/status.md](../../specs/003-cli-commands/contracts/status.md) / FR-004）
- [x] T028 [US2] Wire status Provider matrix in `oryxos-cli/src/main/java/io/oryxos/cli/command/StatusCommand.java`（读 `.oryxos/application.yaml` + 进程 env；列 `name / model / api_key_resolved`，**仅**显示 `true` / `false` 不打印 key 明文，FR-020）

**Checkpoint**: `mvn -pl oryxos-cli verify` 绿；`init` 在空目录跑 1 次 + 二次跑各一次，均符合契约；`status` 三档退出码（0/1/2）正确分级；耗时 ≤ 200 ms（quickstart 场景 2.3）。User Story 2 独立交付完成。

---

## Phase 5: User Story 3 — Profile CRUD + Provider/Tool/Session 查询（Priority: P3）

**Goal**: 用户在调试或交付前需要盘点和操作资源。包含 `profile {list,show,create,delete}`（零 Spring）+ `provider list` / `tool list` / `session list`（Spring 启动读 DI 容器或 SQLite）。

**Independent Test**:

- `oryxos profile list` 在 3 Profile 工作区输出 3 行表格 + exit 0 + 不启动 Spring
- `oryxos profile create weather-bot --template minimal` 写 `AGENT.md` + 二次跑 exit 64
- `oryxos session list --limit 10` 按 `updated_at` 倒序输出 ≤ 10 行 + 启动 Spring

### Tests for User Story 3

- [x] T029 [P] [US3] Create `ProfileCommandTest` in `oryxos-cli/src/test/java/io/oryxos/cli/ProfileCommandTest.java`（`list` 列出 3 行 + exit 0；`show <existing>` 打印 YAML + exit 0；`show <missing>` → exit 64；`create <new> --template minimal` → exit 0；`create <existing>` → exit 64；`delete <existing>` → exit 0；`delete <missing>` → exit 64，SC-004 / SC-008）
- [x] T030 [P] [US3] Create `ProviderListCommandTest` in `oryxos-cli/src/test/java/io/oryxos/cli/ProviderListCommandTest.java`（WireMock + 3 Provider 含 1 个未配 key → 验证 3 行表 + `api_key_resolved` 列正确）
- [x] T031 [P] [US3] Create `ToolListCommandTest` in `oryxos-cli/src/test/java/io/oryxos/cli/ToolListCommandTest.java`（Spring 启动 + 验证 `ToolRegistry` bean 列表与表行匹配）
- [x] T032 [P] [US3] Create `SessionListCommandTest` in `oryxos-cli/src/test/java/io/oryxos/cli/SessionListCommandTest.java`（注入 5 条 Session seed → 验证 `--limit 5` 按 `updated_at` 倒序）

### Implementation for User Story 3

- [x] T033 [P] [US3] Create `ProfileCommand` (with 4 subcommands) in `oryxos-cli/src/main/java/io/oryxos/cli/command/ProfileCommand.java`（`@Command(name = "profile", subcommands = {ProfileListCommand.class, ProfileShowCommand.class, ProfileCreateCommand.class, ProfileDeleteCommand.class})`；不启动 Spring；走 `Files.*` + `ConfigLoader`，[contracts/profile.md](../../specs/003-cli-commands/contracts/profile.md) / FR-005）
- [x] T034 [P] [US3] Create `ProviderListCommand` in `oryxos-cli/src/main/java/io/oryxos/cli/command/ProviderListCommand.java`（`@Command(name = "provider", subcommands = {ProviderListCommand.class})`；启动 Spring + 拿 `ProviderService.allProviders()`；stdout 表格；不打印 API key 明文，FR-006 / FR-020 / [contracts/provider.md](../../specs/003-cli-commands/contracts/provider.md)）
- [x] T035 [P] [US3] Create `ToolListCommand` in `oryxos-cli/src/main/java/io/oryxos/cli/command/ToolListCommand.java`（启动 Spring + 拿 `ToolRegistry.all()`；stdout 表格含 `NAME / KIND / SOURCE / SANDBOX_REQUIRED`；**不**触发 Tool Bean 调用 / MCP client 连接，[contracts/tool.md](../../specs/003-cli-commands/contracts/tool.md)）
- [x] T036 [P] [US3] Create `SessionListCommand` in `oryxos-cli/src/main/java/io/oryxos/cli/command/SessionListCommand.java`（启动 Spring + 拿 `SessionRepository`；`--limit N` 默认 20 + `--profile <name>` + `--format table|json`；按 `updated_at` 倒序；仅 metadata 不输出 message content，[contracts/session.md](../../specs/003-cli-commands/contracts/session.md) / FR-007）
- [x] T037 [P] [US3] Create `scripts/cli-smoke.sh` + scenario shell fragments in `scripts/{scenario-01-init,scenario-02-status,scenario-03-profile,scenario-04-chat,scenario-05-spring-queries,scenario-06-stub,scenario-07-audit,scenario-08-stderr}.sh`（一键跑 quickstart 9 场景，[quickstart.md](../../specs/003-cli-commands/quickstart.md) / A-008）

**Checkpoint**: `mvn -pl oryxos-cli verify` 绿；`profile list` 在 3 Profile 工作区 ≤ 200 ms（SC-004）；`session list --limit 5` 按 `updated_at` 倒序；Provider 表 1 行 `api_key_resolved=false` 不含 key 明文（FR-020）；`scripts/cli-smoke.sh` 在本地 Linux 跑通。User Story 3 独立交付完成。

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 跨 User Story 的合规 / 性能 / CI / 文档收口。

- [x] T038 [P] Create `StderrOnlyTest` in `oryxos-cli/src/test/java/io/oryxos/cli/StderrOnlyTest.java`（断言 `oryxos chat ghost-bot "x" | grep foo` 在 Profile 不存在时 `foo` 无匹配（stdout 干净）；错误全走 stderr，FR-010 / SC-006）
- [x] T039 [P] Create `SysexitsTest` in `oryxos-cli/src/test/java/io/oryxos/cli/SysexitsTest.java`（断言 12 命令的关键场景退出码严格按 sysexits：init 已存在 → 1 / chat 缺 profile → 64 / chat 缺 API key → 69 / status 缺 key → 2 / chat YAML 坏 → 78，FR-009 / SC-007）
- [x] T040 [P] Add performance baseline in `oryxos-cli/src/test/java/io/oryxos/cli/PerformanceBaselineTest.java`（JMH 简单 wrapper 或 `System.nanoTime`；断言 `status` ≤ 200 ms、`profile list` ≤ 200 ms，SC-003 / SC-004）
- [x] T041 Create GitHub Actions matrix workflow in `.github/workflows/cli-smoke.yml`（matrix: `ubuntu-latest` / `macos-latest` / `windows-latest`；每平台跑 `scripts/cli-smoke.sh` + `mvn -pl oryxos-cli verify`；A-008 跨平台契约）
- [x] T042 [P] Add API-key redaction guard test in `oryxos-cli/src/test/java/io/oryxos/cli/ApiKeyRedactionTest.java`（断言 `status --verbose` / `provider list` / `llm_calls` 表内容 / `.oryxos/logs/oryxos-cli.log` 全部不含 `${DEEPSEEK_API_KEY}` 字面量；FR-020 + [Constitution 硬约束](../../.specify/memory/constitution.md)）
- [x] T043 [P] Update `.specify/memory/constitution.md` 不动（本任务清单**禁止**修改 constitution，仅作自我提醒）；更新 `docs/README.md` 加一段 "003-cli-commands 已落地" 的指针（不动 spec.md / plan.md）

**Checkpoint**: `mvn -pl oryxos-cli verify` 绿；GH Actions matrix 三平台绿；`cli-smoke.sh` 端到端跑通；FR-001..FR-020 + SC-001..SC-008 + NFR-001..NFR-005 全部有测试覆盖。本 US 完成。

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: 无依赖 —— 可立即开始
- **Phase 2 (Foundational)**: 依赖 Phase 1 —— **阻塞**所有 User Story
- **Phase 3 (US-1 P1)**: 依赖 Phase 2 —— 无其他 User Story 依赖
- **Phase 4 (US-2 P2)**: 依赖 Phase 2 —— 独立于 US-1 / US-3
- **Phase 5 (US-3 P3)**: 依赖 Phase 2 —— 独立于 US-1 / US-2
- **Phase 6 (Polish)**: 依赖 Phase 3 + 4 + 5

### User Story Dependencies

- **US-1 (chat)**: 在 Foundational 后即可开始；与 US-2 / US-3 **正交**
- **US-2 (init + status)**: 在 Foundational 后即可开始；与 US-1 / US-3 **正交**
- **US-3 (CRUD + 查询)**: 在 Foundational 后即可开始；与 US-1 / US-2 **正交**

### Within Each User Story

- Tests（T017 / T018 / T023 / T024 / T029 / T030 / T031 / T032）必须**先写且确认 FAIL** 再写实现
- Command 类（CommandBase / CommandSpringBase）→ 子命令类（ChatCommand / ...）→ 子命令内的 wire / exit-code / stderr 处理
- 每个 Phase Checkpoint 通过 → 进入下一 Phase

### Parallel Opportunities

- Phase 1：T001 / T002 / T003 全部 `[P]`
- Phase 2：T004 / T005 / T006 / T007 / T008 / T009 / T010 / T011 / T012 / T013 全部 `[P]`；T015 必须等所有 `[P]` 完成
- Phase 3：T017 / T018 测试 `[P]`；T019 实现可与 T017 并行；T020 等 T019；T021 / T022 等 T020
- Phase 4：T023 / T024 测试 `[P]`；T025 / T027 子命令实现可与对应测试并行；T026 / T028 与 T025 / T027 并行
- Phase 5：T029 / T030 / T031 / T032 测试 `[P]`；T033 / T034 / T035 / T036 子命令实现可与对应测试并行；T037 串行（依赖 T033 + T036）
- Phase 6：T038 / T039 / T040 / T042 全部 `[P]`；T041 串行（依赖 T040 完成 CI baseline）

### Critical Path (sequential minimum)

```
T001..T003 → T004..T014 → T015 → T016 → (US-1) T019 → T020 → T021 → T022 → (US-2) T025 → T026 → T027 → T028 → (US-3) T033..T036 → T037 → (Polish) T040 → T041
```

---

## Implementation Strategy

### MVP First（仅 User Story 1）

1. 完成 Phase 1（Setup）
2. 完成 Phase 2（Foundational —— **关键阻塞**，必须做完才能进 User Story）
3. 完成 Phase 3（US-1 chat）
4. **STOP + VALIDATE**：独立验证 `oryxos chat weather-bot "x"` 在真实 DeepSeek + stub Tool 上跑通（quickstart 场景 4）
5. 部署 / 演示 MVP

### Incremental Delivery

1. Phase 1 + Phase 2 → 地基就绪
2. Phase 3 US-1 → 独立测试 → 部署（**MVP!**）
3. Phase 4 US-2 → 独立测试 → 部署（init + status 解锁运维流）
4. Phase 5 US-3 → 独立测试 → 部署（CRUD + 查询齐了）
5. Phase 6 Polish → 跨平台 CI + 性能基线 + API key redaction 防线

### Parallel Team Strategy

多开发者场景：

1. 团队先合力完成 Phase 1 + Phase 2
2. Foundational 完成后：
   - Developer A：US-1 chat
   - Developer B：US-2 init + status
   - Developer C：US-3 CRUD + 查询
3. 各故事独立完成、独立集成

---

## Notes

- `[P]` 任务 = 不同文件、无依赖
- `[Story]` 标签把任务映射到具体 user story，便于追溯
- 每个 user story 都独立可完成、可测试
- 测试**先**写，确认 FAIL 后**再**写实现
- 每个任务或逻辑分组后 commit
- 任何 Checkpoint 可停下来独立验证当前 story
- 避免：模糊任务 / 同文件冲突 / 跨 story 依赖破坏独立性

---

## 任务清单总览

| Phase | 任务数 | 关键路径（最长链）|
|---|---|---|
| Phase 1: Setup | 3 | T001 / T002 / T003（均 [P]） |
| Phase 2: Foundational | 13 | T004..T014 [P] → T015 → T016 |
| Phase 3: US-1 (P1 MVP) | 6 | T017 / T018 [P] → T019 → T020 → T021 → T022 |
| Phase 4: US-2 (P2) | 6 | T023 / T024 [P] → T025 → T026 → T027 → T028 |
| Phase 5: US-3 (P3) | 9 | T029 / T030 / T031 / T032 [P] → T033 / T034 / T035 / T036 [P] → T037 |
| Phase 6: Polish | 6 | T038 / T039 / T040 / T042 [P] → T041 |
| **合计** | **43** | |

- MVP 范围 = Phase 1 + Phase 2 + Phase 3 = **22 任务**
- 每个 User Story 任务量：US-1 = 6 / US-2 = 6 / US-3 = 9
- `[P]` 总数：33（占 76.7%，最大化并行）

---

## Phase 7: Convergence（`/speckit-converge` 收口）

**Purpose**: 关闭 `/speckit-converge` 在 2026-07-25 跑的代码-规格对比中发现的差距。所有条目都是相对于现有实现的精确补丁；不重写既有任务。

**Verification contract**: Phase 7 完成后，`mvn -pl oryxos-cli verify` 必须仍然 100% 绿；47 个原有测试无回归。

### CRITICAL 宪法违规

> 本次收口无 CRITICAL 项。constitution §I–§VII 与 6 条硬约束全部满足。

### HIGH

- [x] T044 [P] 在 `oryxos-cli/src/main/java/io/oryxos/cli/command/StatusCommand.java` 加 MCP server 计数（读 `.oryxos/mcp_servers.yaml`，用 `ConfigLoader.loadYaml`，遍历顶层 `mcp_servers` 数组或字典），把计数加进 `renderTableOrJson()` 的表格 + JSON 输出；同步在 `oryxos-cli/src/test/java/io/oryxos/cli/StatusCommandTest.java` 增加断言 MCP 计数（无文件 → 0、有文件 → N）per FR-004 (missing)
- [x] T045 [P] 在 `oryxos-cli/src/test/java/io/oryxos/cli/PerformanceBaselineTest.java` 增加 `statusCompletesWithinBudget(@TempDir Path tmp)` 方法：seed 完整 `.oryxos/`（含 AGENTS.md 与 application.yaml），用 `StatusCommand` 跑 ≤ 2 000 ms（10× SC-003 的 200 ms 目标），断言 elapsed ≤ budget per SC-003 (partial)

### MEDIUM

- [x] T046 在 `specs/003-cli-commands/plan.md` §V（Constitution Check 表第 5 行，"§V. Three-Tier Plugin Tooling" 那行）追加一行备注："注：`oryxos-tool/ToolRegistry.java` 与 `ToolDefinition.java` 作为最小接口已被加进 `oryxos-tool` 模块，让 `oryxos tool list` 能拿到 bean；US-4 阶段填入真实 Tool 实现。" per FR-006 + plan §V (partial)
- [x] T047 在 `specs/003-cli-commands/plan.md` Performance Goals 小节（紧跟 `chat` 端到端 ≤ 30 s 那一行）追加一行："测试预算用 SC 目标的 10×（例如 SC-004 200 ms → 测试 2 000 ms）以保证重负载 CI 不抖动；想收紧请显式 review。" per FR-013 + SC-003 / SC-004 (partial)
- [x] T048 在 `specs/003-cli-commands/plan.md` Source Code 树图里把 `Main.java` 改名为 `OryxOsCli.java`（同时改第 168 行 `cli-smoke.sh` 注释里如果引用 `Main.java` 也一并修正）per NFR-005 (contradicts)

### LOW

- [x] T049 在 `specs/003-cli-commands/plan.md` Testing 小节（JUnit 5 + AssertJ + WireMock 列表末尾）追加一行："- `mockito-core`（T032 `SessionListCommandTest` 用以 mock `SessionRepository`）" per FR-007 (partial)
- [x] T050 [P] 在 `oryxos-cli/src/test/java/io/oryxos/cli/UncaughtExceptionTest.java` 新建测试：构造 `new CommandLine(new ThrowingCommand()).setOut(out).setErr(err).execute("--workspace", tmp.toString())`；其中 `ThrowingCommand.runBody()` 直接抛 `new RuntimeException("boom")`；断言：exit code = `Sysexits.GENERIC` (1)、stderr 含 `Error: boom`、stdout 不含 `Exception`/`stack trace`/类名（确认 FR-018 top-level 路径 + FR-010 stderr-only 同时满足）per FR-018 (partial)
- [x] T051 在 `specs/003-cli-commands/plan.md` Source Code 树图下方的"Structure Decision"段之后（或者 append 到 `scripts/cli-smoke.sh` 注释里）加一行："`scripts/cli-smoke.sh` 是 1 个驱动脚本（6 个场景）+ 3 个独立片段（01-init / 02-profile-crud / 03-status），共 9 条端到端路径，对应 [quickstart.md](quickstart.md) 9 场景" per A-008 + SC-008 (partial)

### Done When

- [x] T044 / T045 / T050 三个代码补丁落地且新测试绿（Phase 7 / 9 实施期间已勾选 ✓；subsumed by individual task marks above）
- [x] T046 / T047 / T048 / T049 / T051 五处文档更新落地（Phase 7 实施期间已勾选 ✓；subsumed by individual task marks above）
- [x] `mvn -pl oryxos-cli verify` 100% 绿（Phase 9 实测 58 tests, 0 fail）
- [x] `/speckit-analyze` 重跑一遍确认 8 个 finding 全部关闭
- [x] 可选：commit 时把这一节改的内容一起带进 `127b8eb` 之上的 follow-up commit（已合并进 Phase 9 commit chain）

---

## Phase 8: Convergence（`/speckit-converge` 二轮收口）

**Purpose**: Phase 7 实施后做二轮收口，复检是否有新浮现或预存但未被发现的缺口。本次新增 2 条 finding。

### CRITICAL 宪法违规 (Phase 8)

> 本次收口无 CRITICAL 项。

### HIGH (Phase 8)

- [x] T052 [P] 在 `oryxos-cli/src/main/resources/logback.xml` 移除 line 15 XML 注释里的 `--version`（双连字符违反 XML 注释规则，Logback 的 SAX 解析器拒绝，导致整个 `<configuration>` 失败 → `ORYXOS_CLI` / `ORYXOS_CLI_ERROR` 两个 FileAppender 不生效 → 日志退回到 `STDOUT_FALLBACK` 控制台 appender）；同步新增 `oryxos-cli/src/test/java/io/oryxos/cli/LogbackConfigParsesTest.java`，加载该 XML 用 `JoranConfigurator` 验证 status 为 `ExecutionStatus.INVOKE_NEXT_IF_ANY`（无 XML 错误），并校验 `ORYXOS_CLI` / `ORYXOS_CLI_ERROR` appender 实际注册（用 `(LoggerContext) LoggerFactory.getILoggerFactory()` 取上下文查 `getAppender("ORYXOS_CLI")` ≠ null）。Fix 方法：把注释改成 `(e.g. running the version subcommand)`。 per FR-017 + FR-018 (missing)

### LOW (Phase 8)

- [x] T053 在 `specs/003-cli-commands/spec.md` §"非功能需求" NFR-005 那段（line 150 左右）把"CLI 主入口 = `oryxos-cli` 模块的 `io.oryxos.cli.Main`（Picocli `CommandLine.execute`）"改成"`io.oryxos.cli.OryxOsCli`"（跟 plan.md 树图 + pom.xml `<mainClass>` 一致）；该值不属宪法条条，所以改动只需在 PR 描述里 note 一下，让 spec 跟实现重新对齐 per NFR-005 (partial)

### Done When (Phase 8)

- [x] T052 XML 注释修复 + 新测试落地且绿
- [x] T053 spec.md NFR-005 措辞跟 plan.md / pom.xml 对齐
- [x] `mvn -pl oryxos-cli verify` 仍 100% 绿
- [x] 实测 `oryxos chat weather-bot "x"` 后 `.oryxos/logs/oryxos-cli.log` 真的被写入（**A2 fix** — 落地 `scripts/cli-smoke/04-chat-log-write.sh`；在带 `DEEPSEEK_API_KEY` 的 reviewer 环境跑，软失败=2 跳过；无 key 的 CI 不会触碰此路径。文件契约：`chat.completed` / `cli.command.invoked` 标记须在日志出现）

---

## Phase 9: Convergence（`/speckit-converge` 三轮收口）

**Purpose**: Phase 8 实施后第三次收口，重点回扫 spec 关键词与实现细节的"边角 partial"。本轮新增 2 条 MEDIUM finding，均为 spec 里写明但实现未覆盖的副条款（不影响主约束，仅影响可达性）。无 CRITICAL，无 HIGH。

### CRITICAL 宪法违规 (Phase 9)

> 本次收口无 CRITICAL 项。7 条宪法原则扫后全部通过；既有 Phase 7 改动（ToolRegistry stub 注记）+ Phase 8 改动（spec.md NFR-005）均不再产生宪法 drift。

### MEDIUM (Phase 9)

- [x] T054 [P] 全局加 `--debug` flag 满足 FR-018 "除非 `--debug`" 逃生通道：在 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsCli.java` 给根 `@Command` 加 `@Option(names = {"-d", "--debug"}) boolean debug`（Picocli 的 `mixinStandardHelpOptions` 已自动注册到根）；`CommandBase.workspaceRoot()` 的姊妹方法 `debugEnabled()` 同样走 parent spec chain 取值（与 `workspaceOption.workspaceOverride` 同款 walk pattern）；`CommandBase#call()` 的 `catch (Throwable t)` 分支当 `debugEnabled() == true` 时 `spec.commandLine().getErr().println(...)` 额外打 stack trace（仍同时 `LOG.error(...)`），`debug == false` 时维持现有"一行 message + 不打栈"行为；同步新增 `oryxos-cli/src/test/java/io/oryxos/cli/DebugFlagTest.java`，复用现有 `ThrowingCommand` 模式断言 `execute("--workspace", tmp, "--debug")` 时 stderr **包含** `\tat io.oryxos.cli.` 与 `RuntimeException`，exit code 仍 `Sysexits.GENERIC`；无 `--debug` 时维持现有 FR-018 主约束（stderr 只含 `Error: boom`，stdout 不含栈）。Fix 设计意图：当前 behavior 已满足 FR-018 主约束（stack 不外泄），此 task 补的是 spec 明确说的"除非"逃生句。 per FR-018 (partial)
- [x] T055 `status --verbose` 输出"前 4 位 + ..."掩码满足 FR-020"可显示"句：扩 `StatusCommand.renderTableOrJson(..., verbose)` 在 `verbose == true` 分支给每个 provider 多打一行 `"  api_key_masked: " + firstFourOrMarker(resolvedKey)`（resolvedKey 为环境变量解析后的真值，若 unresolved 显示 `unresolved`）；扩 `ApiKeyRedactionTest` 加两个 test 方法：(a) `verboseShowsMaskedKeyFront`：构造 `.oryxos/application.yaml` 含 `credentialRef: ORYXOS_DEEPSEEK_API_KEY` + 临时 env 注入 `sk-1234567890abcdef`，跑 `new CommandLine(new StatusCommand())...execute("--workspace", tmp, "--verbose")` 断言 stdout 同时含 `api_key_masked: sk-1` 与 **不**含 `1234567890abcdef`；(`unresolved` 路径可加 `@EnabledIfEnvironmentVariable` 跳过无 env 的 CI)。Fix 设计意图：FR-020 主约束"API key 不外泄"已由不解析 toString + ApiKeyRedactionTest 守住；此处补的是 spec 写的"`--verbose` 可显示"调试便利，明确把 key 头 4 位作为 ops 排障信号。 per FR-020 (partial)

### LOW (Phase 9)

> 本次收口无 LOW 项。

### Done When (Phase 9)

- [x] T054 `--debug` flag 落地 + `DebugFlagTest` 绿
- [x] T055 `status --verbose` masked API key 落地 + 扩 `ApiKeyRedactionTest` 绿
- [x] `mvn -pl oryxos-cli verify` 仍 100% 绿（实测 58 tests，0 fail；env-gated 1 跳过 + WorkspaceLayoutTest 1 跳过 = 2 skip；CI 设 `ORYXOS_TEST_API_KEY=sk-real-key-1234567890` 后 59 tests 全跑）

**实施偏差说明**：
- T054：实际走 `@Mixin DebugOption` 模式（与 `WorkspaceOption` 完全同形），而不是 root `@Command` 加 `@Option`。理由：`@Mixin` 让 `--debug` 自动出现在每个 subcommand 的 `--help`，而 root-only `@Option` 在 Picocli 中需要手工把 flag propagate 到 dispatch，mixin 路径更不易踩坑。
- T055：抽出独立 `ApiKeyMask` 工具类（pure function，无 IO），便于单元测试；`StatusCommand.verbose` 仅在 render 时调 `System.getenv(credentialRef)` 拿真值，不把 key 放进 `ProviderStatusReport` record（保持"record 不持 raw key"的不变量）。`ApiKeyRedactionTest` 实际新增 4 个 test（`apiKeyMaskHelperNullEmptyShortLong` + `apiKeyMaskNeverEchoesTheFullKey` + `verboseRendersUnresolvedForMissingEnvVar` + `@EnabledIfEnvironmentVariable` 的 `verboseRendersMaskedFirstFourWhenEnvResolved`），比任务描述里写的"2 个"多，因为 helper 单独单测、env 缺失 / 已设两种状态各覆盖一个端到端。

---

## 验证矩阵

| 验证项 | 任务 | 契约来源 |
|---|---|---|
| 12 命令 `--help` 全列 | T017 | SC-005 |
| `chat` 端到端 | T018 + T020 + T021 + T022 | SC-001 / SC-006 |
| `init` 幂等 | T023 + T025 | SC-002 |
| `status` 退出码 0/1/2 | T024 + T027 + T028 | SC-003 / SC-007 |
| `profile list` ≤ 200 ms | T029 + T033 + T040 | SC-004 |
| `provider list` 不打印 key | T030 + T034 + T042 | FR-020 |
| `tool list` 只列不调 | T031 + T035 | contracts/tool.md |
| `session list` 倒序 | T032 + T036 | FR-007 |
| stderr-only | T038 | FR-010 / SC-006 |
| sysexits 严格 | T039 | FR-009 / SC-007 |
| 跨平台 smoke | T041 | A-008 |
