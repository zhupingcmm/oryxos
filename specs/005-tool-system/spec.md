# 功能规格说明书：Tool 体系（Agent 的"双手"）

**特性分支**：`005-tool-system`
**创建日期**：2026-07-26
**状态**：草稿
**输入**：用户描述："第20节需求：Tool 体系——Agent 真正能动手干事的那双手。……（完整需求见第20节课件《Tool 体系 原理解析、实现与代码讲解》一、二部分）"

> **范围说明**：本 spec 覆盖 OryxOS 核心能力的第四项 ——「Plugin Tool」（[CLAUDE.md §10](../CLAUDE.md) 与宪法 §V），它把 Agent 从"只能回答问题"升级为"能够真正动手做事"。具体而言包含 5 类内置 Tool（`FileTools` / `ShellTools` / `HttpTools` / `NotifyTools` 等）、MCP 接入、`ToolRegistry` 注册表、`Sandbox` 安全护栏、`NotifyChannelAdapter` 出站推送、以及"零代码 / 轻代码 / 重代码"三档接入能力。Notify 出站推送的完整契约已经在 [specs/004-notify-channel](004-notify-channel/spec.md) 落地，本 spec 不复制其 FR / SC，仅以"Notify 是 Tool 体系的出站出口"这一边界关系引用之。
>
> **关于需求来源**：用户输入引用了外部课件《Tool 体系 原理解析、实现与代码讲解》第 20 节。该课件在当前会话不可直接读取；本 spec 的功能范围以 [CLAUDE.md §9.4 / §9.5](../CLAUDE.md)、宪法原则 §I / §IV / §V / §VI、以及 004 阶段已落地的 Notify 实现作为权威输入。任何与上述权威来源不一致的字段以权威来源为准，并在"假设"节标注。

---

## Clarifications

### Session 2026-07-26

- Q1: ReAct 主循环 worst-case wall-time bound 是否需要在 spec 显式声明？ → A: 文档化为 `MAX_ITERATIONS × (LLM_call_timeout + max_tool_wall_time)` ≈ 15 min（默认配置下），纳入 NFR-001 附录，并新增 SC-004b 测 ReAct 级 wall-time。
- Q2: "Tool 系统" vs "Tool 体系" / "NotifyTool" 命名 → A: 全文统一为 "Tool 体系"（与 spec 标题 + CLAUDE.md §9.7 + 004 spec 一致）；Java 类名用 `NotifyTool`（PascalCase），Tool name 字符串用 `notify`（lowercase）。**Glossary**：①"Tool 体系" = 整套 Tool subsystem；②`NotifyTool`（class）= Java 类名；③`notify`（lowercase）= 注册表里的 Tool name 字符串；④"Notify 出站" = 出站推送语义。
- Q3: 数据规模 / 容量 bound 是否进 spec？ → A: 作为**软参考**纳入 NFR-006（≤ 100 calls/day/Agent × Profile 数，非硬保）；新增 SC-012 记录 3 个 Demo 实际运行频率作为实测样本。

---

## 用户场景与测试 *（必填）*

### 用户故事 1 — Agent 调一个内置 Tool，拿到结果并被审计（P1）🎯 MVP

企业用户配好一个最小 Profile（如"每日天气"），Agent 跑起来后由 LLM 在循环中决定调一次 `http_get(url=...)` 拉取天气数据；系统把外部 HTTP 结果返回给 LLM，并在 `tool_invocations` 表里落一行完整审计（tool 名、参数、success / failure、duration_ms）。

**为什么是这个优先级**：这是 Tool 体系最朴素也最有说明力的端到端用例 —— 一行 Tool 调用打通"LLM 决定 → 协议层 schema → 调度 → 执行 → 结果回填 → 审计入库"完整链路。三个验收 Demo（[CLAUDE.md §11](../CLAUDE.md)）全部依赖这条链路。P1 一旦跑通，Tool 体系对 ReAct 主循环的契约（[CLAUDE.md §9.2](../CLAUDE.md) 第 4 步）立刻成立。

**独立测试**：Profile 配 1 个内置 Tool（`http_get`）+ 1 个 mock endpoint（WireMock stub 返回固定 JSON）；LLM 触发一次 `http_get`。断言：(a) ReAct 循环下一轮 LLM 看到 ToolResult 内容包含 mock 返回；(b) `tool_invocations` 写入恰好 1 行，`tool_name='http_get'`、`success=true`、`duration_ms > 0`；(c) 不存在重复调用（验证宪法 §IV —— Spring AI 自动执行已禁用）。

**验收场景**：

1. **假设** Profile 配置 `tools: [http_get]`，且 mock endpoint 已加 `tool.sandbox.http.allowed-domains` 白名单，**当** Agent 运行中 LLM 调 `http_get(url=<mock URL>)`，**那么** LLM 下一轮看到 ToolResult.success=true 且结果为 mock 返回的 JSON 内容，**并且** `tool_invocations` 多一行 `tool_name='http_get', success=true, duration_ms>0`。
2. **假设** Profile 配置 `tools: [http_get]`，**当** mock endpoint 返回 HTTP 500，**那么** ToolResult.success=false，errorMessage 包含状态码 500，**并且** 审计行 `success=false`、duration_ms 仍被记录（失败也计入耗时）。
3. **假设** Agent 调一次 `http_get` 后 LLM 收到结果，**当** 检查 Spring AI 的调用日志，**那么** Spring AI 没有为这一次 Tool 调用再做第二次 LLM→Tool 派发（验证宪法 §IV —— 自动 Tool 执行被禁用）。

---

### 用户故事 2 — Tool 调用必经 Sandbox 安全护栏（P1）

任何 Tool 在真正执行副作用（读 / 写文件、起 shell、HTTP 出站）之前，必须先过 `Sandbox.enforce(action)` 校验；越界动作（文件越界读、shell 黑名单命令、HTTP 越域）在执行前被拦掉，把 sandbox violation 作为 Tool 错误返回给 LLM，**绝不**让越界副作用落到真实环境上。

**为什么是这个优先级**：宪法 §V（企业级 Tool 治理）+ [CLAUDE.md §9.4](../CLAUDE.md) 明确要求所有 Tool 副作用走白名单护栏。Tool 是 Agent 唯一能"动手"的入口，护栏一旦缺位，Agent OS 等于给企业内网开了一个无监控的后门。P2 在 P1 已成立的基础上给 Tool 加"安全带"，与 P1 等权（P1 是"能干活"，P2 是"只能在允许范围内干活"）。

**独立测试**：Profile 配一个 `shell` Tool；LLM 调 `shell(command="rm -rf /")`。断言：(a) `Files.deleteIfExists(...)` / `ProcessBuilder.start()` 之类的真实副作用**不**被触发（用文件系统快照 / 进程计数证明零副作用）；(b) ToolResult.success=false，errorMessage 包含 "sandbox" 关键字；(c) `tool_invocations` 写入 `success=false` 审计行，errorMessage 含 "sandbox"。

**验收场景**：

1. **假设** `tool.sandbox.shell.allowed-commands` 白名单仅含 `["echo", "ls", "cat"]`，**当** LLM 调 `shell("echo hello")`，**那么** 该调用通过校验并正常执行。
2. **假设** 同上，**当** LLM 调 `shell("rm -rf /")`，**那么** 系统在执行前抛 `SandboxViolationException`，**并且** ToolResult.success=false，errorMessage 包含 "sandbox" / "not in whitelist"，**并且** 文件系统零变更。
3. **假设** `tool.sandbox.file.allowed-paths` 仅含 `["./workspace"]`，**当** LLM 调 `file_read(path="/etc/passwd")`，**那么** 在 `Files.readString` 调用之前抛 SandboxViolationException，**并且** ToolResult.success=false。
4. **假设** `tool.sandbox.http.allowed-domains` 仅含 `["api.weixin.qq.com"]`，**当** LLM 调 `http_get(url="https://evil.example.com/...")`，**那么** HTTP 请求**不**被发出（WireMock 零请求计数），ToolResult.success=false。

---

### 用户故事 3 — 零代码 / 轻代码接入新 Tool（P2）

企业用户想给"每日 GitHub 日报"Agent 加一个 `github_pr_digest` Tool，但不想写一行 Java 代码；只通过 (a) `AGENT.md` 里描述 Tool 调用协议 + (b) `mcp_servers.yaml` 里登记一个外部 MCP server（如 `uvx mcp-server-github`），就能让 LLM 在 ReAct 循环里调到该 Tool。

**为什么是这个优先级**：宪法 §V 把"零代码 / 轻代码"列为三档接入的优先档（高于"重代码"）。企业里 90% 的 Tool 接入应该走零代码路径。P3 让"加 Tool 不再是 Java 发版"，这是 Agent OS 与传统工作流引擎差异化的关键。

**独立测试**：Profile 配 `mcp_servers: [github-mcp]`，`mcp_servers.yaml` 登记一个本地 mock MCP server（提供 `list_pull_requests` 工具）。LLM 调 `list_pull_requests(owner="oryxos", repo="demo")`。断言：(a) 真实 HTTP/SSE 流量经 `McpClientService` 到 mock server；(b) ToolResult 返回 mock 提供的结构化结果；(c) `tool_invocations` 写入 `tool_name='list_pull_requests', source='mcp'`（新增字段区分内置 / MCP / Java）；(d) `McpToolAdapter` 负责把 MCP 的工具 schema 转成 `OryxTool` 接口暴露给 LLM（统一抽象，ReAct 循环对"内置 / MCP / 自定义"无感）。

**验收场景**：

1. **假设** `mcp_servers.yaml` 登记一个健康 mock MCP server，**当** Agent 启动，**那么** `McpClientService` 在 Spring Boot 启动期完成连接握手，**并且** MCP server 声明的所有 Tool 都出现在该 Profile 的可用 Tool 列表里。
2. **假设** 同上，**当** LLM 调一个 MCP Tool，**那么** ToolResult 内容来自 MCP server 的返回值（与内置 Tool 走完全相同的 `ToolExecutor` → `OryxTool.execute` 链路）。
3. **假设** `mcp_servers.yaml` 登记一个不可达的 MCP server（端口拒绝连接），**当** Agent 启动，**那么** Spring Boot 启动失败并给出明确报错（不静默降级为"该 MCP 不可用"），**并且** Profile 该 Tool 列表为空，LLM 调不到。
4. **假设** MCP server 在运行中挂掉（健康检查变红），**当** LLM 已经发起 Tool 调用，**那么** ToolResult.success=false，errorMessage 含 "mcp connection"，**并且** 审计行 `success=false` 记录失败原因。

---

### 用户故事 4 — 重代码接入：Java `@Tool` 自定义 Tool（P2）

企业用户有一个内部系统的 SDK（Java lib），想把它包成一个 OryxOS Tool；只需写一个 Spring `@Component` 实现 `OryxTool` 接口（或在方法上用 Spring AI 的 `@Tool` 注解），加到 classpath，Profile 里配 `tools: [sdk_function_name]` 即可被 LLM 调到，无需改 OryxOS 任何核心代码。

**为什么是这个优先级**：宪法 §V 第三档"重代码"是为前两档覆盖不到的场景（内部 SDK / 已有 Java lib / 需要事务语义的 Tool）准备的入口。P4 让"重代码"也只是一个标准 Bean，不是改 OryxOS 内核。

**独立测试**：写一个最小 `EchoTool implements OryxTool`（输入字符串、回显字符串）。`mvn install` 后启动 OryxOS。Profile 配 `tools: [echo]`。LLM 调 `echo(text="hello")`。断言：(a) `ToolRegistry` 在 Spring 启动期发现 `EchoTool` Bean；(b) ToolResult.success=true，content="hello"；(c) `tool_invocations` 写入 `tool_name='echo', source='java_bean'`。

**验收场景**：

1. **假设** 自定义 Tool 实现 `OryxTool` 接口并标 `@Component`，**当** Spring Boot 启动，**那么** `ToolRegistry` 自动发现该 Bean 并按 `@Tool` / `OryxTool` 的 schema 生成 Function Calling 元数据。
2. **假设** Profile 的 `tools: [echo]`，**当** LLM 调 `echo(...)`，**那么** 走标准 `ToolExecutor.execute` 链路，行为与其他内置 Tool 一致。
3. **假设** 自定义 Tool 在 `execute` 阶段抛 RuntimeException，**当** LLM 调它，**那么** ToolResult.success=false，errorMessage 含异常 message（不冒泡到 ReAct 主循环），**并且** 审计行 `success=false`、duration_ms 仍记录。
4. **假设** Tool 副作用在 JDK 21 虚拟线程隔离栈内执行（虚拟线程调度器负责挂载 OS 线程，不污染调用方线程状态），**当** LLM 调它，**那么** 调用栈同步阻塞到 ToolResult 返回（与 NFR-002 同步派发模型一致），wall-time 受 NFR-001 ≤ 30 秒约束；超时由 `ToolExecutor` 强制中断并返回 `ToolResult.error("timeout")`（继承自 [contracts/tool-executor.md](./contracts/tool-executor.md) §3.4）。

---

### 用户故事 5 — NotifyTools 作为出站 Tool（P2）

Agent 跑完一次任务后由 LLM 在最后一步调 `notify(content, channel?)`，消息经 `NotifyChannelAdapter` 送达企业微信 / 飞书 / 钉钉 webhook。Notify 工具**复用** `ToolRegistry` + `tool_invocations` 体系，不另起独立通道。

**为什么是这个优先级**：Notify 是出站推送场景的"第一公里"，它的契约已经在 [specs/004-notify-channel](004-notify-channel/spec.md) 落地（含 FR-001 至 FR-014 与 SC-001 至 SC-008）。P5 在本 spec 里仅承担"Notify 是 Tool 体系的一等公民"这层关系说明，不重复其细节。Notify 的出站 HTTP 仍走 Sandbox 白名单（用户故事 2 覆盖）；Notify 的审计仍写入 `tool_invocations` 表（用户故事 1 覆盖）；Notify 的多通道并发与部分失败语义见 004-spec。

**独立测试**：见 [specs/004-notify-channel US-1 / US-2 / US-3 / US-4](004-notify-channel/spec.md)；本 spec 的独立测试仅验证：(a) `notify` Tool 出现在配置 `notify_channels` 的 Profile 的可用 Tool 列表；(b) `notify` 不出现在**没有**配置 `notify_channels` 的 Profile 的可用 Tool 列表（避免 LLM 调到但总是失败）。

**验收场景**：

1. **假设** Profile 配 `notify_channels: [{name: default, type: webhook, url: <mock>}]`，**当** LLM 列出可用 Tool，**那么** `notify` 在列表里。
2. **假设** Profile **没有** `notify_channels`，**当** LLM 列出可用 Tool，**那么** `notify` **不**在列表里（LLM 根本看不到，避免"调得到但永远失败"的体验）。
3. **假设** 任何 Tool 调用（含 notify），**当** 调用完成，**那么** 都对应 `tool_invocations` 表中**恰好一行**审计行（无重复计数，无遗漏）。

---

### 边界情况

- **Tool 调用超时**（HTTP 5 秒 / shell 30 秒等）：到时间后 `ToolExecutor` 强制中断，ToolResult.success=false，errorMessage="timeout"，**并且** 审计行写入 timeout 分类，duration_ms 为实际等待时间。
- **Tool 调用被中断**（Agent 循环达到 `MAX_ITERATIONS` 上限）：核心阶段未实现"在途 Tool 强制 kill"语义；`MAX_ITERATIONS` 只控制 ReAct 主循环的迭代次数，**不**影响已发起的 Tool 调用。该边界情况放扩展阶段跟踪。
- **同一个 Profile 配了 20 个 Tool**：LLM 列出 Tool schema 时返回全部 20 个；不做截断（宪法 §IV 的 function-calling schema 必须完整，截断会让 LLM 误判工具可用性）。
- **Tool schema 冲突**（两个 Tool 同名）：启动期 `ToolRegistry` 检测冲突并抛 `IllegalStateException`，Spring Boot 启动失败（fail-fast，不静默选一个）。
- **Tool 实现持有外部连接**（数据库连接池、HTTP 客户端）：Tool 自身负责连接生命周期；Agent OS 不提供连接池（核心阶段不引入 Tool-as-a-Service 抽象）。
- **LLM 传非法参数**（类型错误、缺必填字段）：Spring AI 的 schema 校验阶段就会拒掉，**不会**到达 `OryxTool.execute`；Tool 自身仍要做 defensive check（schema 校验不能替代）。
- **Tool 调用在循环里被并发触发**：核心阶段不保证并发安全；`ToolExecutor.execute` 同一时刻只跑一个 Tool（与 `MAX_ITERATIONS` 配合的串行语义）。
- **Notify 工具不可见**（Profile 没配 notify_channels）：见用户故事 5 场景 2 —— `notify` Tool **不**出现在该 Profile 的可用列表，避免无效调用。
- **Sandbox 拦截 vs Tool 自身校验**：Sandbox 拦截**优先**于 Tool 自身校验；Tool 自身校验是 defensive 的第二道防线（schema 校验已是第一道）。

---

## 需求 *（必填）*

### 功能需求

- **FR-001**：系统 MUST 提供 `OryxTool` 接口作为 Tool 的统一抽象契约；该接口 MUST 暴露 `name` / `description` / `parameters`（JSON Schema）/ `execute(args: Map<String, Any>): ToolResult` 四要素（参考 [CLAUDE.md §5](../CLAUDE.md) 关于"Tool 抽象归 core"的边界）。
- **FR-002**：系统 MUST 提供 `ToolRegistry` 注册表门面，按"Tool name → OryxTool 实例"的映射暴露给 `PromptBuilder` 与 `ToolExecutor`；注册表 MUST 在 Spring Boot 启动期完成 Bean 扫描与 MCP 连接握手（[CLAUDE.md §5](../CLAUDE.md)）。
- **FR-003**：核心阶段 MUST 实现下列内置 Tool（落在 `oryxos-tool` 模块）：
  - `file_read` / `file_write` / `file_list`（文件 I/O）
  - `shell`（执行 shell 命令，含沙箱校验）
  - `http_get` / `http_post`（HTTP 客户端）
  - `notify`（出站推送，见 [specs/004-notify-channel](004-notify-channel/spec.md)）
  - `save_memory` / `recall_memory`（Memory 工具，由 [specs/003-cli-commands](003-cli-commands/spec.md) 描述）
- **FR-004**：所有 Tool 在执行副作用前 MUST 调用 `Sandbox.enforce(SandboxAction)`（[CLAUDE.md §9.4](../CLAUDE.md)）；未通过 MUST 抛 `SandboxViolationException`，由 `ToolExecutor` 包装为 ToolResult.success=false 返回给 LLM，**绝不**让越界副作用落到真实环境。
- **FR-005**：每次 Tool 调用 MUST 在 `tool_invocations` 表里落一行审计（宪法 §VI）；审计字段 MUST 包含 `tool_name` / `success` / `duration_ms` / `error_message?` / `channel?`（仅 notify）/ `source`（`builtin` / `mcp` / `java_bean` 三选一）。
- **FR-006**：Function Calling schema MUST 由 `ToolRegistrySchemaAdapter`（位于 `oryxos-core`）从 `ToolRegistry` 手动物化为 OpenAI Function Calling JSON 格式（`{type:"function", function:{name, description, parameters}}`）。遵循宪法 §IV —— 只用协议转换（构造 schema），**不**用 Spring AI 的自动执行能力（避免 tool 被调两次，[CLAUDE.md §8 坑 #1](../CLAUDE.md)）。schema 物化在 Spring Boot 启动期完成（与 NFR-003 一致）。
- **FR-007**：Spring AI 的自动 Tool 执行 MUST 被禁用（宪法 §IV）；Tool 调度 MUST 完全由 `ReActLoop` + `ToolExecutor` 控制。**症状**：若违反则同一 Tool 被调两次。
- **FR-008**：系统 MUST 支持三档 Tool 接入（宪法 §V）：
  1. **零代码**：`AGENT.md` + `SKILL.md` + MCP server config（无需 Java 代码）。
  2. **轻代码**：自定义 MCP server（任何语言），在 `mcp_servers.yaml` 登记。
  3. **重代码**：Java `@Component implements OryxTool`（或 Spring AI `@Tool`）。
- **FR-009**：MCP 接入 MUST 通过 `McpClientService` 完成 server 连接握手 + 工具发现；`McpToolAdapter` MUST 把 MCP server 暴露的每个工具转成 `OryxTool` 实现，注册到 `ToolRegistry`。
- **FR-010**：Notify 出站 MUST 通过 `NotifyChannelAdapter` 接口实现；核心阶段唯一实现是 `WebhookNotifyAdapter`（基于 HTTP POST + JSON payload，覆盖企业微信 / 飞书 / 钉钉群机器人通用 webhook 形态）。Notify 的全部契约见 [specs/004-notify-channel](004-notify-channel/spec.md)。
- **FR-011**：Profile MUST 通过 `tools: [string]` 字段限定该 Agent 可用的 Tool 列表；未列入的 Tool MUST NOT 出现在 PromptBuilder 的可用 Tool 列表里，LLM 看不到也调不到。
- **FR-012**：Tool 调用失败 MUST 以 `ToolResult.success=false, errorMessage=<原因>` 的形式返回给 LLM，**不**抛 RuntimeException 到 ReAct 主循环（保留 LLM 决策权）。
- **FR-013**：Tool **实现**（具体 Tool、适配器、SDK 包装、Notify 适配器、Sandbox 实现）MUST 全部落在 `oryxos-tool` 模块（宪法 §I / §V）；**不**拆出 `builtin-tools` / `skill-tools` / `mcp-tools` 子模块。Tool **抽象**（`OryxTool` 接口、`ToolRegistry` / `ToolRegistration` / `ToolDefinition` 门面、`ToolExecutor` 派发接口、`ToolRegistrySchemaAdapter` schema 物化）MAY 落在 `oryxos-core` —— 判定标准：该类被 `ReActLoop` / `PromptBuilder` / `DefaultToolExecutor` 直接 import 当 API 消费。本边界是 [CLAUDE.md §5 "§V 边界澄清"](../CLAUDE.md) 的硬约束，不是 §V 的宽松解读。
- **FR-014**：Tool 调度 MUST 配合 `MAX_ITERATIONS`（默认 10，[CLAUDE.md §9.1](../CLAUDE.md)）；同一时刻只跑一个 Tool（串行语义）；不并发触发同一 Profile 内的多个 Tool。
- **FR-015**：Tool 注册表 MUST 在启动期检测 Tool name 冲突；冲突时 MUST 抛 `IllegalStateException` 并阻止 Spring Boot 启动（fail-fast）。

### 非功能需求

- **NFR-001**：单条 Tool 调用的端到端 wall-time MUST ≤ 30 秒（健康依赖场景下）；超过 MUST 走 ToolResult.success=false + errorMessage="timeout"。**ReAct 主循环 worst-case wall-time** = `MAX_ITERATIONS × (LLM_call_timeout + max_tool_wall_time)` = `10 × (~60s + 30s)` ≈ **15 min**（默认配置下，[CLAUDE.md §9.1](../CLAUDE.md) + NFR-002 同步派发模型）。该上界是 SC-004 测试样例的边界条件；**Tool 级**与 **ReAct 级** wall-time 各自独立测试（SC-004 测 Tool，SC-004b 测 ReAct）。
- **NFR-002**：Tool 调用 MUST 在 JDK 21 虚拟线程隔离栈内执行（不污染主线程状态、不抛 RuntimeException 到 ReAct 主循环，FR-012 兜底）；调用栈采用**同步阻塞**模型（与 [CLAUDE.md §9.1](../CLAUDE.md) ReAct 同步迭代模型一致 ——"组装 Prompt → 调 LLM → 解析响应 → [有 tool 调用] 执行 → 追加到 Session → 继续"），ToolResult 返回后由 ReAct 继续下一轮迭代；**不**引入异步 streaming / ToolResult 占位 / 下一轮回填真实结果 语义（宪法 §III 自实现 ReAct loop 显式拒绝第三方 Agent 框架的异步派发）。"MUST NOT 阻塞 ReAct 主循环"指的是**不抛异常中断**而非**调用栈不等待**。
- **NFR-003**：Tool schema 生成 MUST 在 Spring Boot 启动期完成（`tool_invocations` 不为 schema 生成付运行时开销）。
- **NFR-004**：Tool 错误信息 MUST 对用户友好（LLM 可读）；stack trace MUST 写到 `.oryxos/logs/oryxos-cli-error.log` 或等价位置，**绝不**进 ToolResult.errorMessage（避免污染 LLM 上下文）。
- **NFR-005**：所有 Tool 副作用 MUST 在 Sandbox 校验**通过后**才能发生；Sandbox 校验失败的调用 MUST 零副作用（用文件系统快照 / 进程计数 / HTTP 请求计数证明）。
- **NFR-006（设计容量 / 非硬保）**：核心阶段设计容量参考为 **≤ 100 次 Tool 调用 / 日 / Agent × Profile 数**。该值是**软参考**（informational），不构成验收契约；超此规模应触发扩展阶段的 Tool 连接池 / 限流 / 性能调优（参见 [CLAUDE.md §13](../CLAUDE.md) 第 5 表 `tool_invocations` 与 §18 "不做的扩展阶段的事"）。

### 关键实体

- **OryxTool**：Tool 的统一抽象接口。属性：`name`（String，Profile 内唯一）、`description`（String，给 LLM 看的自然语言）、`parameters`（JSON Schema）、`execute(args) → ToolResult`。生命周期：Spring Bean，Scope = singleton。
- **ToolRegistry**：Tool name → OryxTool 实例的注册表。属性：`tools: Map<String, OryxTool>`。注册来源：(a) 内置 Tool（`@Component` 扫描）、(b) MCP Tool（启动期 `McpClientService` 发现）、(c) 自定义 Java Tool（`@Component implements OryxTool`）。生命周期：Spring Bean，启动期完成装配。
- **ToolResult**：Tool 执行返回结果。属性：`success`（boolean）、`content`（String，LLM 可读）、`errorMessage?`（String，失败原因）、`metadata?`（Map<String, Any>，可选附加信息，如 notify 的 status code）。**不**入库，仅在 ReAct 循环里传递。
- **SandboxAction**：Sandbox 校验的输入。属性：`type`（`FILE_READ` / `FILE_WRITE` / `SHELL_COMMAND` / `HTTP_REQUEST`）、`target`（路径 / 命令 / URL）。
- **SandboxViolationException**：Sandbox 校验失败的异常。`ToolExecutor` 捕获后转 `ToolResult.success=false`。
- **tool_invocations 行**：复用宪法 §VI 现有表，本 spec 新增字段 `source`（`builtin` / `mcp` / `java_bean`）与 `channel`（仅 notify 有值）。
- **McpClientService**：MCP server 连接的运行时持有者。启动期完成握手，运行期负责心跳 / 重连；不在本 spec 的实现细节范围。
- **NotifyChannelAdapter**：Notify 出站通道的抽象接口；本 spec 仅声明该接口存在，详细契约见 [specs/004-notify-channel](004-notify-channel/spec.md)。

---

## 成功标准 *（必填）*

### 可测量结果

- **SC-001**：三个验收 Demo（每日天气 / 每日科技日报 / 每日 GitHub 日报，[CLAUDE.md §11](../CLAUDE.md)）均能在真实 LLM + 真实依赖下端到端跑通；每个 Demo 至少成功调用 1 次 Tool（每日天气 → `http_get`；每日科技日报 → MCP Tool；每日 GitHub 日报 → `shell`）。
- **SC-002**：100% 的 Tool 调用产生一行 `tool_invocations` 审计行（不论 success / failure、内置 / MCP / 自定义、Sandbox 拦截 / 超时 / 正常返回）。
- **SC-003**：所有未在 Sandbox 白名单内的 Tool 副作用（文件越界、shell 黑名单命令、HTTP 越域）在 Tool 调用**之前**被 100% 拦截；用文件系统快照 / 进程计数 / WireMock 请求计数证明零副作用。
- **SC-004**：Tool 调用的 wall-time 满足 NFR-001（单条 ≤ 30 秒）；ReAct 主循环不被 Tool 副作用阻塞（验证 NFR-002）。
- **SC-005**：Tool 调用失败（HTTP 5xx / 超时 / Sandbox 拦截 / 异常抛出）时，ReAct 主循环**不**中断；LLM 在下一轮看到 `ToolResult.success=false` 与错误明细，能据此调整响应（不抛异常给调用方）。
- **SC-006**：零代码接入一个新 Tool（`AGENT.md` + `mcp_servers.yaml`，无 Java 代码）≤ 30 分钟；以"加一个 `github_pr_digest` Tool"为对照样本验证。
- **SC-007**：重代码接入一个新 Tool（`@Component implements OryxTool`）≤ 100 行 Java（含 import 与 javadoc）；以"内部 SDK 包一个 Echo Tool"为对照样本验证。
- **SC-008**：JDK 21 / Spring Boot 3.x 单二进制部署下，集成测试 100% 通过；`mvn verify` 全绿（继承 US-2 / US-3 / US-4 的 CI 基线）。
- **SC-009**：Tool 错误信息中 0% 含 stack trace（验证 NFR-004）；stack trace 100% 进 `.oryxos/logs/`。
- **SC-004b**：ReAct 主循环 worst-case wall-time MUST ≤ NFR-001 计算出的上界（≈ 15 min，默认 `MAX_ITERATIONS=10` + LLM 调用 + Tool ≤30s）；Mock 环境下让 10 个连续 Tool 调用都返回 success=true → 端到端 wall-time ≤ 15 min 且每个 Tool 调用结束时 ToolResult 都已正确归并到 Session 对话历史。该 SC 与 SC-004 互补：SC-004 测 Tool 级 wall-time；SC-004b 测 ReAct 整体 worst-case 不越界。

### 业务结果

- **SC-010**：业务方（运维 / 客服 / HR / 销售等）能够在不修改 OryxOS 内核的前提下，通过零代码或重代码路径新增 Agent 所需的 Tool；新增 Tool 不需要 OryxOS 发版（仅 Skill 注册 / MCP 配置 / 自定义 Bean 引入）。
- **SC-011**：企业审计员可以从 `tool_invocations` 表里完整还原"哪个 Agent / 哪个 Session / 哪个 Tool / 哪个参数 / 哪个时间 / 什么结果"的全部 Tool 调用历史（day-one 审计基石，宪法 §VI）。
- **SC-012（设计容量参考）**：核心阶段在 3 个验收 Demo（每日天气 / 每日科技日报 / 每日 GitHub 日报）的实际运行频率下（如每日 1-3 次 / Agent × Profile），所有 SC 通过；该值是 NFR-006 软参考的实测样本。**不是**性能 / 容量硬保。

---

## 假设

1. **Tool 体系是 US-4 整体的范围**（[CLAUDE.md §10](../CLAUDE.md)）；本 spec 是 US-4 的"完整功能视角"合并说明，与 [specs/004-notify-channel](004-notify-channel/spec.md)（Notify 出站子能力的详细契约）**互补**而非重复。本 spec 在 Notify 部分以引用 004 spec 的方式避免内容分裂。
2. **核心阶段的 Tool 数量是固定的 9 个**（FR-003）；扩展阶段才允许运营者通过零代码路径加 Tool。这意味着 SC-006 / SC-007 的"零代码 / 重代码接入"在核心阶段是"基础设施已就绪 + 接入示例可用"的演示态，不是开放给业务方自由发挥。
3. **三档接入的优先级**：宪法 §V 明示"零代码 / 轻代码 / 重代码"按此顺序推荐；本 spec 在功能上同时支持三档，但 US-3 / US-4 的优先级（P2）反映"核心阶段先把基础设施做好，扩展阶段才重点推广零代码接入"。
4. **MCP server 健康检查**：核心阶段用启动期握手作为唯一健康信号；运行期心跳 / 自动重连放扩展阶段（[CLAUDE.md §11](../CLAUDE.md) 中关于"运行期挂掉"的边界情况已在场景 4 标注）。
5. **Tool schema 冲突检测**（FR-015）：核心阶段 fail-fast；扩展阶段可能引入"按 namespace 隔离"机制。
6. **Tool-as-a-Service 抽象**（连接池、生命周期管理、限流）放扩展阶段；核心阶段 Tool 自身负责外部资源生命周期。
7. **Sandbox 校验失败的 Tool 调用仍然计入审计**（FR-005 + 用户故事 2）；`errorMessage` 字段含 "sandbox" 关键字以便审计员筛出。这与宪法 §VI"day-one 审计"一致 —— 即使被拦掉，也是一条记录。
8. **Notify 工具不替代最终用户响应的返回路径**：用户故事 5 不重复 [specs/004-notify-channel](004-notify-channel/spec.md) 的契约；Notify 失败仅作为 Tool 错误返回给 LLM，ReAct 主循环继续。
9. **Tool 调用的并发模型**：核心阶段同一 Agent 内 Tool 调用串行（与 ReAct 主循环串行迭代一致，FR-014）；不同 Agent 之间的 Tool 调用并发（每个 Agent 独立 Session，独立 ReAct 循环）。
10. **JDK 21 虚拟线程**用于 Tool 副作用执行（NFR-002）：与 Spring Boot 3.x 的 virtual thread 支持结合；不引入额外线程池。
11. **Spring AI 自动 Tool 执行禁用**（FR-007）的验证手段：观察 `tool_invocations` 表里同一 Tool 同一参数在同一 Session 内的重复计数；若 ≥ 2 行说明违反。已在用户故事 1 场景 3 标注验收方式。
12. **本 spec 的实现状态**：核心阶段代码已在 `004-notify-channel` 分支陆续落地（[T043 / T045 / T046 / T049 / T050 / T051 / T060 / T062](004-notify-channel/tasks.md)）；本 spec 承担"完整视角整合"职责，**不**追加新代码任务，仅在 plan / tasks 阶段标记"已有实现 → 引用"或"剩余差距 → 落地"。

---

## 不在范围内（Out of Scope）

- ❌ Tool Policy 引擎（细粒度权限控制、动态权限撤销）—— 宪法 §II 明示放扩展阶段
- ❌ Tool 调用流式输出（SSE / WebSocket / 流式 partial result）—— 扩展阶段
- ❌ Tool 调用结果缓存（同一参数返回同一结果时跳过实际执行）—— 扩展阶段
- ❌ Tool Marketplace / Tool 注册中心 UI —— 扩展阶段
- ❌ Tool 性能分析 / 调用链路追踪（OpenTelemetry 集成）—— 扩展阶段（宪法 §VI 仅审计表 + duration_ms，不接入 OTel）
- ❌ 多租户级别的 Tool 配额 / 限流 —— 宪法 §II 明示放扩展阶段
- ❌ Notify 之外的非 Tool 通道（SMTP / Slack native / Teams native / 短信）—— [specs/004-notify-channel](004-notify-channel/spec.md) §"不在范围内"已明确
- ❌ Tool 调用在分布式集群下的协同 —— 扩展阶段
