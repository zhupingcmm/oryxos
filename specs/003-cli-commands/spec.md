# 功能规格：CLI（OryxOS 命令行入口）

**Feature Branch**: `003-cli-commands`
**Created**: 2026-07-25
**Status**: Draft
**Input**: User description: "第18节需求：CLI——OryxOS 的命令行入口。……（完整需求见第18节课件《CLI：功能概述、实现思路与代码讲解》一、二部分）"

本 spec 锚定在 [CLAUDE.md](../../CLAUDE.md) §5（9 模块布局）、§14（12 个 Picocli 命令清单）、§16（Profile YAML schema）+ Constitution §I / §III / §V 之上；不重述课堂细节，按**主入口收口**的视角定义 CLI 的用户级契约。

---

## 用户场景与验收测试 *（必填）*

### 用户故事 1 — 一次性 chat 触发 Agent（优先级：P1） 🎯 MVP

用户在终端用一条命令触发某个 Profile 的 Agent 处理单条消息，并把 Agent 最终响应打印到 stdout。这是 [CLAUDE.md §11](../../CLAUDE.md) "人推手动补跑一次" 的核心入口 —— 与 `POST /api/v1/agents/{name}/invoke`（Web）和 AgentScheduler（钟推）走同一条 `AgentService.process(...)` 链路（FR-021 已确立）。

**为什么是这个优先级**：CLI 是企业运维 / 研发日常排查、demo 验证、CI smoke 的最高频入口；一个用户装好 OryxOS 二进制后，第一个会跑的命令就是它。

**独立测试**：给定一个本地 `.oryxos/`（含至少一个 Profile + 一个可用 Provider 配置 + `DEEPSEEK_API_KEY`），跑 `oryxos chat <profile-name> "你好"`，在 ≤ 30 s 内得到 Agent 的最终文本回复，且 exit code 0。

**验收场景**：

1. **Given** `.oryxos/agents/weather-bot/AGENT.md` 存在 + `DEEPSEEK_API_KEY` 已注入环境变量，**When** 用户跑 `oryxos chat weather-bot "今天上海天气如何"`（或带 stdin 交互提示的等价形式），**Then** 终端在 ≤ 30 s 内打印 Agent 的最终文本回复（例 "上海今天多云，25°C"），**And** 命令退出码为 0，**And** `.oryxos/sessions/*.db` 中多了一条 Session 记录（消息数为 user + assistant + 可能的 tool 消息）。
2. **Given** 指定的 `<profile-name>` 在 `.oryxos/agents/` 下不存在，**When** 用户跑 `oryxos chat ghost-bot "hi"`，**Then** 命令在启动阶段（非 Spring 启动后）fail-fast，stderr 打印 `Unknown profile: 'ghost-bot'`，**And** exit code 非 0（建议 64 — EX_USAGE），**And** 不写任何 Session 或审计行。
3. **Given** `DEEPSEEK_API_KEY` 未注入或对应 Provider 未配置，**When** 用户跑 `oryxos chat weather-bot "hi"`，**Then** 命令在 Spring 启动后第一次 LLM 调用前 fail-fast，stderr 打印具体缺失字段（`API key missing` / `Provider 'deepseek' not configured`），**And** exit code 非 0（建议 69 — EX_UNAVAILABLE），**And** `llm_calls` 表无新行（避免半审计状态）。

---

### 用户故事 2 — 工作区初始化与运行状态（优先级：P2）

用户首次拿到 OryxOS 二进制、需要把 `.oryxos/` 工作区落地，或在运维时确认当前实例的健康度。

**为什么是这个优先级**：没有 init 就跑不了 chat；没有 status 就无法排查 "我的 Agent 为啥不响应"。两条命令虽然低频但是**阻塞性前置**。

**独立测试**：

- `oryxos init` 在空目录跑：生成完整 `.oryxos/` 树（`agents/`、`memory/`、`mcp_servers.yaml`、`sessions/`、`logs/`、`AGENTS.md`、`SOUL.md`、`USER.md`）+ 初始化 SQLite（`oryxos.db`）；二次跑报告 "already initialized"。
- `oryxos status` 报告：JVM / JDK 版本、当前 `.oryxos/` 绝对路径、已发现 Profile 数、Provider 配置状态（`name / model / api_key_resolved` 三列表）、MCP server 数、Spring Context 是否可启动。

**验收场景**：

1. **Given** 当前目录没有 `.oryxos/`，**When** 用户跑 `oryxos init`，**Then** 退出码 0，stdout 列出创建的文件清单，**And** 二次跑 `oryxos init` 报告 `Already initialized at <path>` 且 exit code 非 0（建议 1）。
2. **Given** `.oryxos/` 已存在且 Profile 数 = 3、Provider 数 = 2（含 1 个未配置 API key），**When** 用户跑 `oryxos status`，**Then** stdout 输出 JSON 或人类可读表格（含 Provider 表 + 缺失字段标红），exit code 反映 "健康度"（0 = 全绿，2 = warning，1 = error）。
3. **Given** `oryxos init` 与 `oryxos status` 都**不**启动 Spring Context，**When** 在没装 LLM SDK 的 CI 容器里跑，**Then** 命令仍然正常退出（避免 chat 路径的副作用）。

---

### 用户故事 3 — Profile / Provider / Tool / Session 查询与 Profile CRUD（优先级：P3）

用户在调试或交付前需要盘点和操作资源。

**为什么是这个优先级**：高频辅助但不会阻塞 MVP；放在 P3 保证 P1 / P2 通畅后再补。

**独立测试**：

- `oryxos profile list`：列出 `.oryxos/agents/<name>/` 所有 Profile，含 `description` 第一行、Provider 名、Tool 数。
- `oryxos profile show <name>`：打印完整 Profile YAML。
- `oryxos profile create <name> --template <tpl>` / `oryxos profile delete <name>`：从内建模板创建 / 删除 Profile。
- `oryxos provider list` / `oryxos tool list` / `oryxos session list`：分别列出 application.yaml 中配置的 Providers（来自 Spring DI）、Tools（含内建 + MCP）、Sessions（含 `id / profile_name / updated_at / message_count`）。

**验收场景**：

1. **Given** `.oryxos/agents/` 下有 3 个 Profile，**When** 用户跑 `oryxos profile list`，**Then** stdout 表格列出三行（`name / description / provider / tools_count`），exit code 0，**And** 不启动 Spring（仅文件 IO）。
2. **Given** 用户跑 `oryxos profile create weather-bot --template minimal`，**Then** 自动在 `.oryxos/agents/weather-bot/AGENT.md` 写入最小模板（含 name、provider=deepseek、tools=[]、memory_blocks=[]），**And** 不覆盖已存在的同名 Profile（fail-fast）。
3. **Given** SQLite 中有 5 条 Session，**When** 用户跑 `oryxos session list --limit 10`，**Then** 按 `updated_at` 倒序列出 ≤ 10 行；**And** 启动 Spring 读 SQLite（lazy）以保证数据新鲜。

---

### 边界情况

- **`<profile-name>` 含特殊字符（中文 / 空格 / `*`）**：`AGENT.md` YAML 用 frontmatter，`name` 字段受 Constitution §V 隐含的 `^[a-z][a-z0-9-]{0,63}$` 约束；CLI 必须在校验失败时 stderr 输出 + 退出码 64。
- **`.oryxos/` 是软链 / 跨设备挂载**：`init` 创建文件用 `Files.createDirectories(..., LinkOption.NOFOLLOW_LINKS)`；`status` 报告 `realpath()`。
- **并发 chat**：`oryxos chat` 一次只起一个 Spring Context；同一 Profile 同名 chat 不并发（不同 Session ID 互不冲突）。
- **LLM 响应含非法 JSON Schema**：抛 `ProviderException` 由 CLI 包成非零退出码 + stderr 摘要（不打印完整堆栈）；详细堆栈走 `.oryxos/logs/`。
- **`serve` / `gateway` 子命令**：本 spec 仅占位（明确属于 US-5 Web Service），CLI 层只暴露 stub（"not yet implemented in 003"），不强行实现。
- **Spring Context 启动失败（缺 bean、循环依赖）**：CLI 顶层捕获 + 打印精简 cause chain + 退出码 1。
- **Windows / macOS / Linux 行为差异**：CRLF vs LF、路径分隔符、`~` 展开 —— 一律走 `java.nio.file.Path`，避免硬编码 `/`。

---

## 需求 *（必填）*

### 功能需求

#### 命令集（12 个，[CLAUDE.md §14](../../CLAUDE.md) 全集）

- **FR-001**：系统**必须**暴露 `oryxos` 二进制，根命令由 Picocli 解析；子命令清单 = `{init, status, chat, serve, gateway, profile, provider, tool, session}` 严格按 [CLAUDE.md §14](../../CLAUDE.md) 表中的 12 个落地（`profile` / `provider` / `tool` / `session` 各带子子命令）。
- **FR-002**：`oryxos chat <profile-name> [--message <msg> | stdin]` **必须**启动 Spring Context → 解析 Profile → 调 `AgentService.process(Session, message)` → 把 `LoopResult.finalText()` 打印到 stdout → 优雅关闭 Context → 退出码 0。**禁止**绕过 `AgentService` 直接驱动 `ReActLoop`。
- **FR-003**：`oryxos init` **不得**启动 Spring Context，仅做文件 IO；**必须**生成完整 `.oryxos/` 树（`agents/` + `memory/` + `mcp_servers.yaml` + `sessions/` + `logs/` + `AGENTS.md` + `SOUL.md` + `USER.md`），并初始化空的 SQLite（`oryxos.db`，schema 走 US-1 既有 `llm_calls` + US-2 day-one `sessions` / `tool_invocations`）。
- **FR-004**：`oryxos status` **不得**启动 Spring Context；**必须**输出 JVM / JDK / OS 版本、`.oryxos/` realpath、Profile 数、Provider 配置矩阵（含 API key 是否 resolved）、MCP server 数；退出码分级（0/1/2）。
- **FR-005**：`oryxos profile {list, show, create, delete}` 操作 `.oryxos/agents/` 下的目录；`list` / `show` 不启动 Spring；`create` / `delete` 仅写文件系统，**不**触 SQLite。**禁止**在 US-3 memory / US-4 tool 接管前允许其他隐式 CRUD 入口。
- **FR-006**：`oryxos provider list` / `oryxos tool list` **必须**启动 Spring 以拿到 DI 容器内的实际 Provider 与 Tool Bean（反映当前生效配置）；退出码 0 即表非空。
- **FR-007**：`oryxos session list [--limit N]` **必须**启动 Spring + 走 `SessionRepository`（Spring Data JPA），按 `updated_at` 倒序输出最近 N 条。
- **FR-008**：`oryxos serve` / `oryxos gateway` **必须**作为 US-5 占位 stub —— 当前在 CLI 层只解析参数 + 打印 `not yet implemented (US-5)` + 退出码 0（**不**抛异常），让 P1/P2/P3 demo 不被 stub 阻塞。**禁止**在 US-3 之前伪造一个简易 HTTP server。
- **FR-009**：所有命令的退出码 **必须** 遵循 [BSD sysexits](https://man.openbsd.org/sysexits) 约定：`0 = 成功`、`1 = generic`、`2 = usage / config bad`、`64 = EX_USAGE`、`69 = EX_UNAVAILABLE`、`78 = EX_CONFIG`；**禁止**全部用 0/1 二值。
- **FR-010**：所有错误消息 **必须** 走 stderr；stdout **仅** 承载成功的命令输出（chat 文本、status 表格、profile list）。便于 `oryxos chat foo "x" | grep ...` 这类管道用法。

#### 启动行为分层（[CLAUDE.md §14](../../CLAUDE.md) 后半段）

- **FR-011**：`init` / `status` / `profile list` / `profile show` / `profile create` / `profile delete` **必须**实现"零 Spring 启动"路径 —— 构造 `CommandLine` 时跳过 `OryxosApplication.main` 的 Spring Boot bootstrap，直接走文件 IO / SnakeYAML。
- **FR-012**：`chat` / `serve` / `gateway` / `provider list` / `tool list` / `session list` **必须**启动 Spring Context（共享一个 `OryxosApplication` 入口）；Spring 启动失败必须在 CLI 顶层包成非零退出码 + stderr，**不**打印 stack trace 给最终用户（详细堆栈走 `.oryxos/logs/oryxos-cli-error.log`）。
- **FR-013**：CLI 启动到第一行输出（chat 文本 / status 表 / profile list）**必须**在 [CLAUDE.md §14](../../CLAUDE.md) "零 Spring" 命令 ≤ 200 ms 内完成；Spring 启动命令 ≤ 5 s（dev 模式 / 默认 profile）。

#### 配置 / Profile 加载

- **FR-014**：Profile YAML 解析 **必须** 走 `ConfigLoader`（[CLAUDE.md §5](../../CLAUDE.md) 提到的 `oryxos-cli` 组件）；`${ENV_VAR}` 占位符 **必须** 在加载时从进程环境变量解析，**禁止**硬编码 API key（Constitution §IV + 硬约束 "MUST NOT hard-code API keys"）。
- **FR-015**：Profile 名称 **必须** 匹配 `^[a-z][a-z0-9-]{0,63}$`（与 Provider 名同 pattern，[CLAUDE.md §16](../../CLAUDE.md) 隐含）；CLI 在校验失败时打印可读错误（含合法字符集说明），exit code 64。
- **FR-016**：`AGENT.md` 缺失时 **必须** fail-fast with `Profile not found: <name>`（不要 fallback 到默认空 Profile，避免 silent breakage）。

#### 可观测性 / 审计

- **FR-017**：`oryxos chat` 一次执行 **必须** 产出一条 `react.completed` 结构化日志（[CLAUDE.md §11](../../CLAUDE.md) + spec FR-020 复用）+ N 条 `react.iteration`；日志路径 `.oryxos/logs/oryxos-cli.log`，rotation 走 Logback 默认策略。
- **FR-018**：所有命令 **必须** 捕获顶层未捕获异常 + 写一条 `cli.command.failed command=<name> exit=<n> duration_ms=<d>` 到 `.oryxos/logs/`，**禁止**让 stack trace 逃到终端（除非 `--debug`）。

#### 安全 / 沙箱（与 Constitution 对齐）

- **FR-019**：CLI **禁止** 使用 `java.lang.SecurityManager`（Constitution 硬约束）；沙箱行为在 Tool 路径由 `Sandbox.enforce` 落地（US-4 拥有）。
- **FR-020**：API key **禁止** 出现在 stdout / 日志 / `oryxos status` 默认输出中（Constitution 硬约束 + 企业合规）；`status --verbose` 可显示前 4 位 + `...` 掩码。

### 关键实体

- **`CommandSpec`**（Picocli `@Command` 注解的对象）：1 个根命令 + 12 个子命令；不带持久化状态。
- **`.oryxos/` 工作区**：包含 `agents/`（每子目录 = 一个 Profile）、`memory/`、`mcp_servers.yaml`、`sessions/`、`logs/`、`AGENTS.md` / `SOUL.md` / `USER.md`（Bootstrap 文件）、`oryxos.db`（SQLite）。**关键属性**：路径解析时永远用 `Path.toRealPath()` 防 symlink 攻击；二次 init 幂等。
- **`Profile`**：从 `.oryxos/agents/<name>/AGENT.md` 解析；详见 [data-model.md §3.3](../002-react-loop/data-model.md) + [CLAUDE.md §16](../../CLAUDE.md) YAML schema。
- **`OryxosApplication`**：Spring Boot 主类；CLI 中"启动 Spring"与"不启动 Spring"两条路径共用，但 init 时机不同。
- **`ConfigLoader`**：`oryxos-cli` 模块组件；负责 Profile YAML + `${ENV_VAR}` 解析。

---

## 成功标准 *（必填）*

### 可量化结果

- **SC-001**：用户首次装好 OryxOS 二进制 + 一个 Profile + `DEEPSEEK_API_KEY`，跑 `oryxos chat <profile> "你好"`，**必须** 在 ≤ 30 s 内拿到 Agent 文本回复，exit code 0。
- **SC-002**：`oryxos init` 在空目录跑一次，**必须** 生成 ≥ 8 个文件 + 1 个 SQLite 数据库，**且** 二次跑报告已初始化且 exit code ≠ 0；全程**不**启动 Spring。
- **SC-003**：`oryxos status` 在已初始化工作区跑一次，**必须** 在 ≤ 200 ms 内打印完整健康度报告（含 JVM 版本 + Profile 数 + Provider 配置矩阵 + MCP 数）。
- **SC-004**：`oryxos profile list` 在 `.oryxos/agents/` 下含 N 个 Profile 时，**必须** 在 ≤ 200 ms 内打印 N 行表格，且**不**启动 Spring。
- **SC-005**：12 个 Picocli 命令全部注册且 `oryxos --help` 输出含全部 12 个（含 4 个子命令的子子命令）；`--help` 退出码 0。
- **SC-006**：所有错误消息走 stderr；`oryxos chat ghost-bot "x" | grep foo` **必须** 在 Profile 不存在时 `foo` 找不到匹配（因为错误不进 stdout）。
- **SC-007**：BSD sysexits 退出码约定落地：`oryxos chat <bad-profile>` → 64；`oryxos chat <profile>`（API key 缺）→ 69；`oryxos init` 在已初始化目录 → 1。
- **SC-008**：`mvn -pl oryxos-cli,oryxos-channel-cli test` **必须** 100% 绿；新增测试覆盖：12 个 CommandLine `--help` 烟雾 + 至少 3 个端到端 happy path（init / status / chat with WireMock）。

### 非功能需求

- **NFR-001**：CLI **不得**引入超出 Constitution §I 列出的 9 模块之外的模块；`oryxos-cli` 与 `oryxos-channel-cli` 是既有模块，**禁止**新增 `oryxos-cli-v2` / `cli-runner` 之类。
- **NFR-002**：CLI **不得**依赖 Spring AI 的 Agent 抽象（Constitution §III）—— `chat` 仅通过 `AgentService.process(...)` 驱动，**禁止**直接持有 `ChatModel` Bean。
- **NFR-003**：CLI **不得** 启用 Spring AI 自动 tool 执行（Constitution §IV），即使某条 `oryxos chat` 命令用到了 `MCP`，派发也走 `ReActLoop + ToolExecutor`。
- **NFR-004**：JDK 21（records / sealed / virtual threads / sequenced collections 全用上）；**禁止**任何 pre-JDK 21 写法（Constitution 硬约束）。
- **NFR-005**：二进制交付走 `java -jar`（[CLAUDE.md §4](../../CLAUDE.md)）；CLI 主入口 = `oryxos-cli` 模块的 `io.oryxos.cli.Main`（Picocli `CommandLine.execute`）。

---

## 假设

- **A-001**：[CLAUDE.md §14](../../CLAUDE.md) 列出的 12 个 Picocli 命令清单 = 本 spec 的全集；课堂细节（具体参数风格、输出表格格式）按企业 CLI 惯例补全（`--json` / `--table` 双输出、GNU 长选项）。
- **A-002**：`oryxos-channel-cli`（[CLAUDE.md §5](../../CLAUDE.md)）的 `CliChannel` 是"消息怎么进来"（inbound channel adapter）的对称部分，由 US-3 Memory / US-4 Plugin Tool 阶段自然接入；本 spec 不展开其协议细节（HTTP POST / WS / SSE 等留 US-5 决策）。
- **A-003**：`serve` / `gateway` 子命令是 US-5 Web Service 的 CLI 入口；本 spec 仅放 stub（FR-008），等 005-web-service spec 启动时细化。
- **A-004**：SQLite schema 与日-one 表（`sessions` / `tool_invocations` / `llm_calls` / `scheduled_tasks` / `task_executions`）由 US-1 + US-2 落地，本 US 不引入新表。
- **A-005**：环境变量 `${ENV_VAR}` 在 ConfigLoader 加载时解析（[CLAUDE.md §16](../../CLAUDE.md)）；本 spec 不引入 dotenv / Vault 集成（属于扩展阶段）。
- **A-006**：`oryxos init` 是幂等的；二次运行报告已初始化，不覆盖任何文件（避免误删用户的 `.oryxos/memory/MEMORY.md`）。
- **A-007**：CLI 日志走 Logback + SLF4J（[CLAUDE.md §4](../../CLAUDE.md)）；结构化字段在 `.oryxos/logs/oryxos-cli.log` 用 Logback 默认 pattern 输出；扩展阶段的 JSON appender 不在本 spec 范围。
- **A-008**：Windows / macOS / Linux 三平台一致行为；CI smoke 在三者之上跑（gh actions matrix）。

---

## 明确不在范围内（本 US **不**包含）

- Web Service 端点 / SSE / WebSocket —— US-5（`005-web-service`）。
- AgentScheduler cron 表达式注册 CLI —— US-5（避免在 US-3 之前引入 schedule 维度）。
- 多租户 / RBAC / SSO —— 扩展阶段（Constitution §II）。
- 流式输出（实时把 LLM token 推到 stdout）—— 扩展阶段。
- 国际化（i18n）—— 扩展阶段；本 spec 文案 + 日志全英文。
- Profile / Provider / Tool 的 GUI 编辑器 —— 扩展阶段；本 spec 仅 CLI。
- `oryxos serve` / `oryxos gateway` 真实实现 —— US-5。

---

## 备注（与 CLAUDE.md / Constitution 的对齐检查）

- ✅ **Constitution §I（9 模块）**：`oryxos-cli` + `oryxos-channel-cli` 既有，本 spec 不引入新模块。
- ✅ **§II（核心阶段范围）**：本 spec 仅交付 CLI；不涉及治理层。
- ✅ **§III（自实现 ReAct）**：CLI 通过 `AgentService.process()` 间接驱动 `ReActLoop`，**不**直接持有循环对象。
- ✅ **§IV（Spring AI 用一半）**：CLI 不引入 Spring AI 自动 tool 执行；Provider 调用走 US-1 既有 `ProviderService`。
- ✅ **§V（Tool 单一模块）**：CLI 不引入 Tool 相关代码（Tool 注册 / Sandbox / Notify 都在 US-4 的 `oryxos-tool`）。
- ✅ **§VI（SQLite + MEMORY.md day-one 审计）**：CLI 的 `chat` 命令复用 US-2 的 `tool_invocations` / `llm_calls` 表，**不**绕过审计。
- ✅ **§VII（Demo-First 交付）**：本 spec 末尾必须能 demo `oryxos chat weather-bot "x"` 在真实 DeepSeek + stub Tool 上跑通。

---

## Spec 质量自检（Spec Kit checklist 入口）

详细质量清单在 [`checklists/requirements.md`](checklists/requirements.md)；运行 `/speckit-clarify` / `/speckit-plan` 之前请先 review。
