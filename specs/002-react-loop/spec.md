# Feature Specification: ReAct Loop (US-2)

**Feature Branch**: `002-react-loop`
**Created**: 2026-07-25
**Status**: Draft
**Input**: User description (verbatim):

> "ReAct 循环是 OryxOS 最核心的一段代码。输入一条用户消息，输出 Agent 的最终响应，中间可能调用若干次 LLM 和若干次 Tool。
> ReAct 是 **Reason** 加 **Act** 的简称。算法步骤：
> 1. 接到用户消息追加到 Session 对话历史
> 2. 组装 Prompt（system prompt 加 Bootstrap 加 Skill 加 Memory 加对话历史加可用 Tool 列表）
> 3. 调用 LLM Provider 获取响应
> 4. 如果响应**没有** Tool 调用，返回最终响应
> 5. 如果**有** Tool 调用，OryxOS 执行 Tool 并把结果作为 tool 消息追加到对话历史
> 6. 回到组装 Prompt 步骤继续循环
> 7. 达到最大迭代次数（默认 10 次）强制结束
> ReAct 循环：Reason → Act → Observe，循环直到无工具调用或达到最大轮数。"

本 spec 对应 OryxOS 5 阶段 US 交付序列中的 US-2（[CLAUDE.md §10](../CLAUDE.md)）。它依赖 US-1（`oryxos-provider`），并为 US-3 / US-4 / US-5 解锁。Constitution §III 明确要求此循环以 Java 手写实现，§IV 禁用 Spring AI 的自动工具执行——两条原则合并让本 spec 成为不可协商项。

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Pure Reason Path (Priority: P1)

用户问一个**不需要调用任何 Tool** 就能回答的对话性问题（例：*"法国的首都是什么？"*）。ReAct 循环应当：组装 Prompt、调用一次 LLM、观察响应中**没有**任何 tool_calls，并将该文本响应作为最终答案返回——完全不触达 Tool 层。

**为什么是这个优先级**：这是循环的最小可用形式。其它所有路径（P2、P3）都是 P1 的严格超集。如果 P1 跑不通，系统里每个 Agent 都坏的——包括那些还没绑 Tool 的。P1 端到端验证了一次完整的纯 Reason 往返（即"每日天气" Demo 不调 API 时的回退路径）。

**独立测试**：用一个 **零 Tool** 的 Agent Profile 配合一条用户消息调用循环，使 LLM 返回纯文本响应。断言：恰好一次 LLM 调用被记录、无 Tool 执行发生、用户可见的回复就是 LLM 文本、Session 对话历史只包含 `[user, assistant]` 两条有序消息。

**验收场景**：

1. **给定** 一个名为 "weather-bot" 的 Agent Profile（`tools: []`）和一个空 Session，**当** 用户提交 *"你好"*，**那么** 系统返回 LLM 的文本回复，**并且** Session 历史中恰好两条有序消息（`user` → `assistant`），**并且** 恰好一条 `llm_calls` 审计记录被写入，**并且** 零条 `tool_invocations` 审计记录被写入。
2. **给定** 一个 Agent Profile，其 Prompt 引用 `Bootstrap: [AGENTS.md, SOUL.md, USER.md]`，**当** 用户提交任何消息，**那么** 发送给 LLM 的 system prompt 包含 AGENT.md 正文，后面按 Profile 顺序追加三个 Bootstrap 文件，**并且** 在 system prompt 末尾追加一行当前本地日期时间（[CLAUDE.md §9.2](../CLAUDE.md) 步骤 1）。
3. **给定** 一个 Agent Profile 设置 `settings.max_iterations: 1`，**当** 用户提交任何消息，**那么** 系统至多调用 LLM 一次，返回值要么是 LLM 的 tool_call 响应（max_iterations 终止、未拿到无 tool_call 的最终答案），要么是 LLM 的文本回复（只迭代了一次 Reason）。

---

### User Story 2 — Single Reason-Act-Observe Cycle (Priority: P2)

用户问一个**恰好需要一次 Tool 调用**才能回答的问题（例：*"今天北京的天气怎么样？"*）。ReAct 循环应当：Reason 一次 → 看到 tool_call → Act（执行 Tool）→ Observe Tool 结果 → 用上下文里的 observation 再 Reason 一次 → 出最终回复。

**为什么是这个优先级**：P2 是测试 Tool 执行路径的最小价值单元。它证明循环可以穿越 Tool 执行而不丢失消息顺序、审计保真度和会话状态。P2 让每个 Demo 的头部（每日天气、每日科技日报）都能跑通。没有 P2，整个 Tool 层无法端到端验证。

**独立测试**：用一个 Profile（含 `http_get` Tool）配合 WireMock 端点返回固定 weather JSON，再来一条会触发正好一次 HTTP 调用的用户消息。断言：两次 LLM 调用（第一次见到 tool_call，第二次返回文本）、一次 Tool 调用被记录、LLM 的第二次响应引用了 WireMock 载荷里的数据、Session 历史中两条 `assistant` 之间夹一条 `tool` 消息。

**验收场景**：

1. **给定** 一个名为 "weather-bot" 的 Agent Profile（`tools: [http_get]`）和活跃 Session，**当** 用户提问触发一次 HTTP 外出调用，**那么** 循环迭代恰好两次（两次 LLM 调用），**并且** 恰好一条 `tool_invocations` 记录被持久化且 `success=true`，**并且** 最终 assistant 文本回复包含 Tool 返回的值，**并且** Session 对话历史按以下顺序：`user → assistant(tool_call) → tool(result) → assistant(text)`。
2. **给定** 在 Reason-Act-Observe 周期内 Tool 调用失败（超时 / 4xx / 5xx），**当** 系统处理这次失败，**那么** 恰好一条 `tool_invocations` 记录被持久化且 `success=false`、错误信息齐全，**并且** 循环仍然把失败作为 `tool` 消息回喂给 LLM（让 LLM 自己决定重试、放弃或对外报错），**并且** 循环继续进入下一轮而非突然终止。
3. **给定** 一条用户消息触发的 Tool 调用，其 Tool 名**不在** Profile 的 `tools` 列表中，**当** 循环看到 LLM 请求这个 Tool，**那么** 循环拒绝调用，**并且** 拒绝行为被记录为一条 `tool_invocations` 记录，错误为 "tool not in profile"，**并且** 循环以合成的错误结果回喂给 LLM 后继续（不崩溃、不静默重试）。

---

### User Story 3 — Multi-Iteration Tool Chain (Priority: P3)

用户问一个**需要多个** Tool 调用（可能涉及**不同的** Tool，并且模型要在中间 observation 上做推理）的问题（例：*"找出 repo X 上最新的 GitHub PR 并总结它的 diff"*）。循环应当多次迭代 Reason → Act → Observe，直到 LLM 给出一个无 tool_call 的最终响应。

**为什么是这个优先级**：P3 是 OryxOS 差异化能力登场的地方——多 Tool 组合 + 有 observation 推理的状态化场景。P3 让"每日 GitHub 日报" demo 和任何非平凡的 Agent 工作流都能跑通。P3 同时在真实负载下验证终止逻辑。

**独立测试**：用一个含 `http_get` 和 `read_file` 两个 Tool 的 Profile，配合一条其真值需要"一次 `http_get` + 一次 `read_file`"才能完成的用户消息。断言：共三次 LLM 调用、两条 `tool_invocations` 记录、两个 Tool 按正确顺序出现在 Session 历史中、最终 assistant 文本回复同时引用了两个 observation。

**验收场景**：

1. **给定** 一个 Agent Profile 有两个 Tool（`tool_a`、`tool_b`）和一条需要按序使用两者的用户消息，**当** 循环运行完毕，**那么** 系统调用 LLM 恰好 K+1 次（K 是 tool_call 数），**并且** 每个 Tool 至多执行一次（无双调用，满足 Constitution §IV），**并且** K 条 `tool_invocations` 记录按 `invocation_id` 单调递增，**并且** Session 历史按以下严格顺序：`user → assistant(tool_a) → tool_a(result) → assistant(tool_b) → tool_b(result) → assistant(text)`。
2. **给定** LLM 持续不断请求 tool_call（病态场景），**当** 循环已迭代 `MAX_ITERATIONS` 次仍无 tool-free 响应，**那么** 循环终止，**并且** 系统把最后一次 `assistant(tool_call)` 响应作为尽力而为的最终答案返回（携带结构化的"循环于 max_iterations 终止"标记），**并且** 终止后不再发生任何 Tool 调用和 LLM 调用，**并且** Session 以良好定义的形态关闭。
3. **给定** 两个不同 Session 上的并发循环（例如：一个 CLI 用户 + 一个 Scheduler 触发的作业），**当** 二者在同一 Spring Application Context 上并行运行，**那么** 各 Session 的历史保持隔离（无消息串扰），**并且** `llm_calls` 与 `tool_invocations` 记录通过 `session_id` 列正确归属每次调用，**并且** thread-local `ProfileContext` 在并发调用之间不泄漏。

---

### Edge Cases

- **LLM 调用失败（瞬时或永久）**：若 Provider 抛出异常（网络、5xx、超时），循环立即终止并将错误向上传播；`llm_calls` 记录 `success=false` 与错误消息；该迭代中不再触发 Tool。
- **Tool 执行抛出未检异常**：异常被循环捕获、记录为 `tool_invocations` 的 `success=false` 行、异常 message 作为 tool 消息回喂给 LLM（由 LLM 决定下一步）。循环继续。
- **Tool 执行时发生 Sandbox 违例**：当作 Tool 失败处理（捕获、记 `success=false`、作为 tool 消息回喂）；循环继续。
- **LLM 返回空的 `tool_calls` 与空的 `text`**：当作模型截断处理——以合成的"模型返回空响应"作为最终答案终止循环，不无限迭代；记录 `llm_calls` 携带空响应标记。
- **`MAX_ITERATIONS == 0`**：跳过整个循环；返回一个静态"loop not configured"最终答复（防御性，在 Profile schema 中显式文档化）。
- **`process()` 引用的 Profile 不存在**：循环在边界处（迭代 1 之前）fail-fast，抛 `IllegalArgumentException("Unknown profile: {name}")`；不写 `llm_calls` 记录。
- **Profile 引用的 Provider 未配置**：同上 fail-fast，不写 `llm_calls` 记录。
- **Session 找不到**：同上 fail-fast，不写审计记录。
- **线程跨循环复用**：`ProfileContext` ThreadLocal **必须**在 `finally` 块中清空，避免 Spring scheduler 把同一工作线程分配给另一个 Agent 时造成泄漏。
- **迭代中途被中断（JVM 关闭、CLI SIGINT）**：尽力而为：在退出前持久化 Session 当前状态（到上次完成迭代为止的消息），审计员可看到部分状态。

---

## Requirements *(mandatory)*

### Functional Requirements

#### Input & Entry Point

- **FR-001**：系统**必须**提供唯一的公开入口 `AgentService.process(Session session, String userMessage)`，由三种触发源（CLI chat、Web Service、`AgentScheduler`）统一调用。ReAct 循环**不得**感知调用来源。
- **FR-002**：系统**必须**在迭代 1 启动前查找 Session 引用的 Profile，缺失、不可读、或引用未配置 Provider 时立即 fail-fast（`IllegalArgumentException`）。
- **FR-003**：系统**必须**在第一次 LLM 调用**之前**把入参 `userMessage` 作为 `user` 角色消息追加进 Session（避免中途失败丢失用户输入）。

#### Prompt Assembly

- **FR-004**：系统**必须**按 [CLAUDE.md §9.2](../CLAUDE.md) 中定义的**精确四步顺序**组装每次迭代的 Prompt：(1) system prompt = AGENT.md 正文 + Bootstrap 文件 + 当前本地日期时间；(2) Memory 注入（Session 历史 + 长期记忆）；(3) 截断后的对话历史（按 `max_history_turns`）；(4) Profile 可用 Tool 对应的 `toolSchemas`（Function Calling JSON Schema 形态）。
- **FR-005**：系统**必须**在每次迭代的 system prompt 中包含一行**当前本地日期时间**（定时 Agent 依赖模型"知道今天是几号"）。
- **FR-006**：系统**必须**从 Profile 的 `tools` 列表出发，通过 `OryxTool` 注册表（重代码）和/或配置的 MCP server（零代码/轻代码）填充 `toolSchemas`，翻译为 Provider 中立的 JSON Schema；**禁止**通过 Spring AI 自动发现。

#### LLM Invocation

- **FR-007**：系统**必须**通过 `ProviderService.invoke(String providerName, LlmRequest)` 调用 LLM，其中 `providerName` 取自 `Profile.provider.name`。循环**不得**调用任何 Spring AI Agent 抽象（Constitution §III），**不得**启用 Spring AI 的自动工具执行（Constitution §IV）。
- **FR-008**：系统**必须**为每次 LLM 调用持久化恰好一条 `llm_calls` 审计记录——由 ProviderService 自身写（US-1 契约），不由循环写。循环**不得**静默吞异常。

#### Tool Execution

- **FR-009**：若 LLM 响应包含一个或多个 `tool_calls`，系统**必须**遍历每个 `tool_call`，通过 `ToolExecutor.invoke(toolName, arguments)` 分发（**禁止**通过 Spring AI 自动执行）。
- **FR-010**：系统**必须**为每次 Tool 调用持久化恰好一条 `tool_invocations` 审计记录，含 `success=true|false` 与可能的错误消息，由 `ToolExecutor`（US-4 契约）写入。循环**必须**通过捕获、记录、把错误回喂成合成 tool 结果的方式处理 Tool 异常。
- **FR-011**：系统**必须**拒绝调用不在 `Profile.tools` 里的 Tool；拒绝行为**必须**被记为 `tool_invocations` 记录，错误为 `"tool not in profile"`，循环继续（不崩溃、不静默重试）。
- **FR-012**：若 `Profile.tools` 为空（或 LLM 响应中无 tool_call），循环**必须**完全跳过 Tool 执行步骤并进入终止流程。

#### Loop Control

- **FR-013**：系统**必须**迭代 Reason → Act → Observe 循环，满足以下任一条件**即停**：(a) LLM 响应无 `tool_calls`；或 (b) 已达 `MAX_ITERATIONS`。
- **FR-014**：`MAX_ITERATIONS` **必须**默认 **10**（Constitution §III），且**必须**支持 Profile 级通过 `settings.max_iterations` 覆盖。Profile 值 `0` **必须**让循环返回静态"loop not configured"答复（不发 LLM 调用）。
- **FR-015**：系统**必须**在每次单条用户消息上以**至多 `MAX_ITERATIONS + 1`** 次 LLM 调用终止（`+1` 预算覆盖"最后一次 LLM 响应本身就是 tool_call"的情况）。
- **FR-016**：系统**必须**按发生顺序把每次 `assistant(text|tool_call)` 响应和每次 `tool(result|error)` 消息追加进 Session 对话历史，以保证 Session 可完整回放。

#### Profile Context

- **FR-017**：系统**必须**在 `process()` 入口**一次性**设置 thread-local `ProfileContext`（含当前 Profile 名与 Session ID），并**必须**在 `finally` 块中清空，使 `OryxTool.execute(...)` 无需接收 Profile 参数即可解析当前 Agent。
- **FR-018**：系统**必须**不在不同 Session 的并发 `process()` 调用间共享 `ProfileContext`；thread-local 隔离足够（核心阶段不需要 `InheritableThreadLocal` 或异步传播）。

#### Audit & Observability

- **FR-019**：系统**必须**为每次迭代输出一条结构化日志行，形如 `react.iteration session_id={id} iteration={n}/{max} tool_calls={k}`，其中 `k` 是该次迭代中请求的 tool_call 数（Constitution §VII：Demo-First Delivery——可观测性 day-one 必选）。
- **FR-020**：系统**必须**输出一条最终汇总日志行 `react.completed session_id={id} iterations={k} duration_ms={d} final_tool_call={true|false}`，其中 `final_tool_call=true` 表示循环因达到 `MAX_ITERATIONS` 而终止（未拿到无 tool_call 的最终答案）。

#### Entry Point Independence (Acceptance)

- **FR-021**：系统**必须**可由三种触发源之一（CLI chat、Web Service `POST /agents/{name}/invoke`、`AgentScheduler` cron）无修改地调用；三种源的循环行为**必须**一致。[CLAUDE.md §10](../CLAUDE.md) 指定的 Demo "每日天气" 会端到端验证。

### Key Entities

- **Session**：单条用户面向的会话线索。具备稳定的 `sessionId`、一个 `profileName` 引用，以及一个有序的 `messages` 列表（元素类型 `user | assistant | tool`）。循环从消息列表头组装 Prompt，向尾部写入新消息。US-1 / `oryxos-storage` 中既存实体。
- **Agent Profile**：单个 Agent 的 YAML 文档。由 `ContextLoader`（[CLAUDE.md §5](../CLAUDE.md)）读取，本 US 不变更其结构，只要求循环遵守 `settings.max_iterations`（FR-014）。`oryxos-core` 中既存 schema。
- **LlmCall (审计)**：`ProviderService.invoke(...)` 每次写入一行。Schema（`session_id, profile_name, provider_name, model, prompt_tokens, completion_tokens, duration_ms, success, error_message, created_at`）在 US-1 固定。循环**不**直接插入 `llm_calls`——由 ProviderService 契约（FR-007）保证。
- **ToolInvocation (审计)**：`ToolExecutor.invoke(...)` 每次写入一行。Schema（`session_id, profile_name, tool_name, arguments, success, error_message, duration_ms, started_at`）在 US-4 固定。循环**不**直接插入 `tool_invocations`——由 ToolExecutor 契约（FR-010）保证。
- **ProfileContext (瞬时)**：ThreadLocal，承载 `{profileName, sessionId, currentIteration}`。由 `AgentService.process` 设置，`finally` 清空。不持久化。纯循环控制态。

#### Out of Scope (US-2 显式非目标)

为防止意外把范围泄漏到后续 US（Constitution §II），US-2 **不得**包含：

- Tool 实现（`builtin-tools`、MCP 适配器、Sandbox 实现）—— 归 US-4。
- 长期记忆语义检索 —— 归 US-3（`MarkdownMemoryStore` 已存在；本 US 仅消费其只读 API）。
- Web Service 端点 —— 归 US-5。
- `AgentScheduler` cron 注册 —— 归 US-5（循环**可被** scheduler 调用，但加 cron API 不在 US-2 范围）。
- 流式 SSE 响应 —— 扩展阶段。
- 单次 assistant 消息内并发派发多个 tool_call —— 核心阶段顺序处理（与 Provider 的单消息契约对齐）。
- 跨 Provider 的瞬时错误重试/降级 —— 扩展阶段。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**：对一条需要 **N** 个 Tool 调用才能完成的用户消息，系统**恰好**调用 LLM **N+1 次**、执行**恰好 N 个** Tool。循环完成后通过统计 `llm_calls` 与 `tool_invocations` 表的记录数验证（Constitution §IV：零双调用容忍度）。
- **SC-002**：任何用户消息，循环**必须**在 `MAX_ITERATIONS + 1` 次 LLM 调用内终止——即使 LLM 持续不断请求 tool_call（病态重试）。由 mock LLM stub 在每次请求都返 `tool_call` 的场景下验证。
- **SC-003**：N 个并发用户消息共用同一个 Spring Application Context 时，各 Session 的对话历史**必须**隔离：Session 间零消息串扰。由集成测试在同一 ApplicationContext 上并发触发 20 次 `process(...)` 并断言每个 Session 的 `messages` 列表只含自身的 `user` 消息验证。
- **SC-004**：Tool 调用的 100%——无论成功或失败，含拒绝调用（tool not in profile）与 Sandbox 违例——**必须**出现在 `tool_invocations` 表中。由"恰好一半 Tool 失败"的测试验证：审计行数 == 尝试调用次数。
- **SC-005**：系统端到端支持 **每日天气 Demo**（[CLAUDE.md §11](../CLAUDE.md)）：一个含 `tools: [http_get]` 与 Notify 通道的 Profile 即可由 CLI chat、Web Service `POST /api/v1/agents/weather/invoke`、`AgentScheduler` cron 三种源触发，且在相同输入下三种源产出相同最终文本回复。
- **SC-006**：系统仅靠 US-2 即可端到端支持 **每日科技日报 Demo**（仅 Memory 文件注入，语义检索延后到 US-3 / 扩展阶段）：一个引用 Skill 文件（`skills/daily-digest.md`）与 Memory 文件（`MEMORY.md`）的 Profile 能产出引用当天主题的日报。
- **SC-007**：循环可观测性：每次循环执行至少产出一条 `react.completed` 汇总日志与 N 条 `react.iteration` 迭代日志——单条日志一行结构化、Logback 默认文本格式可查询。由捕获日志输出的集成测试验证。

### Non-Functional Requirements

- **NFR-001**：单次 `process()` 含 5 次 Tool 调用时，**必须**在开发者机器（真实 LLM：DeepSeek 或 Qwen + 本地 HTTP mock Tool）30 秒内完成。（延迟预算：5 × (LLM_RTT + Tool_RTT) ≈ 5 × 3 s 基线。）
- **NFR-002**：单次 `process()` **必须**支持被 SIGINT 安全中断；任何一经写入的审计记录**不得**丢失（commit-on-write 语义继承自 US-1 与 US-4 的 `LlmCall` / `ToolInvocation` 表契约）。
- **NFR-003**：循环**不得**依赖 Spring AI 的 Agent 抽象、MCP SDK 或任何第三方 Agent 框架。在 `oryxos-core` 内用 ≤ ~200 行 Java 实现（Constitution §III：数十行）。

## Assumptions

- **A-001**：US-1（`ProviderService.invoke(...)` + audit-on-write 契约）已在本分支实现并稳定。ReAct 循环把它当黑盒调用。
- **A-002**：Session 持久化层（Spring Data JPA 实体 + `SessionRepository`）已在 `oryxos-storage` 实现。循环调用 `session.appendMessage(...)` 而非直接写库。若 US-3 / US-5 尚未发布，US-2 自测可以用无操作 Session 实现。
- **A-003**：`PromptBuilder`（`oryxos-core`）已实现并暴露 `build(profile, sessionMemorySnapshot) -> Prompt` API。循环每次迭代调用 `PromptBuilder.build` 一次。
- **A-004**：`ToolExecutor`（US-4 契约，但循环侧的接口形状稳定）暴露 `invoke(String toolName, Map<String, Object> arguments) -> ToolResult`。US-2 自测用 stub `ToolExecutor`。
- **A-005**：Tool 实现（`HttpTools`、`FileTools` 等）归 US-4，在 US-2 开发期可以是 stub。循环**必须**能用 stub Tool 端到端验证。
- **A-006**：`ProfileContext` thread-local 是 `oryxos-core` 契约 —— FR-017 在**强制执行**它而非**设计**它。（US-4 hook 可能依赖此 thread-local 解析当前 Profile；若 US-4 先交付，本契约已在 US-4 spec 中文档化。）
- **A-007**：审计记录（`llm_calls`、`tool_invocations`）的 `created_at` 时间戳使用 **JVM 本地时间** 而非 UTC。这与 [CLAUDE.md §9.2](../CLAUDE.md) 步骤 1（system prompt 注入"当前本地日期时间"）一致，也与 Scheduler 在用户本地时区下的预期 cron 语义一致。
- **A-008**：`MAX_ITERATIONS` 是硬上限。循环不会因应答简单而调小——每次 Reason 迭代都走完整 `PromptBuilder` + `ProviderService.invoke` 链路。
- **A-009**：Provider 契约是同步、非流式的。若 US-1 已交付流式，US-2 以**同步** `LlmResponse` 消费（先收完所有分块再返回）。核心阶段不暴露流式 UI（属于扩展阶段）。
- **A-010**：单次 assistant 消息中的多 tool_call（LLM 在一次响应里同时请求 `tool_a` 与 `tool_b`）在核心阶段**顺序**处理——先追加 `tool_a` 结果，再追加 `tool_b` 结果，然后进入下一次迭代。单次 assistant 消息内的并发派发是扩展阶段关注点。（这并不违反 Constitution §IV 双调用规则——因为每个 tool_call 都是独立的；该规则指"同一 Tool 在一次用户请求里被调两次"。）
