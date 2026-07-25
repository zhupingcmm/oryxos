# 功能规格说明书：LLM Provider 路由（US-1）

**特性分支**：`[001-llm-provider-routing]`
**创建日期**：2026-07-24
**状态**：草稿
**输入**：用户描述："对接大模型的统一入口。多家模型并存、模型可随时换、每次调用可审计；Profile 声明模型选择；实例级 provider 清单与凭证；按名路由调用；只翻译工具不执行；成败都落审计；凭证走环境变量。边界：不做 ReAct/工具执行/fallback/成本看板/流式。"

## 澄清记录（Clarifications）

### Session 2026-07-24

- **Q1**：MiniMax 作为第 3 家 Provider 时的实例级配置细节（`name` / `model` / `endpoint` / `credentialRef`）→ **A**：`name=minimax`，`model=MiniMax-M3`，endpoint 用 MiniMax OpenAI 兼容端点，`credentialRef=MINIMAX_API_KEY`。三家（DeepSeek / Qwen / MiniMax）均为不同供应商且全部 OpenAI 兼容，落到 R-03 同一翻译器覆盖范围内。

**触发更新**（已应用）：

- `## 假设` 第 7 条由"DeepSeek + 另一个 Provider"扩展为"DeepSeek + Qwen + MiniMax 三家不同供应商"。
- `quickstart.md` 的 env 变量列表、`application.yml` 示例、Step 7（多 Provider 切换）演示同步扩展到 3 家。
- `contracts/application-provider-config.md` 实例目录示例由 2 条扩展为 3 条。
- `research.md` R-07 由"2 家不同供应商"扩展为"3 家不同供应商"。
- `plan.md` 实施顺序 Step 3（ChatModel Bean 配置）由 2 个扩展为 3 个；Phase 0 产物表格无其它变更。

## 用户场景与测试 *（必填）*

### 用户故事 1 — 按 Provider 名称路由 LLM 调用（优先级：P1）🎯 MVP

一个 Agent Profile 声明要使用哪个模型（`provider.name` + `provider.model`）。
当 Agent 运行时发起一次对话补全调用时，系统把请求**精确路由**到名称匹配
的那个 Provider —— 不能"挑一个相近的"、不能从 model 名猜、不能扫描容器里的
Bean 来决定。

**为什么是这个优先级**：按名路由是整个 LLM 层的奠基契约。其余一切
（审计、多 Provider、热切换）都依赖于"name 是一个稳定的路由键"。
没有它，后面整个体系都无法成立。

**独立测试**：在实例目录中配置两个 Provider：`deepseek` 和 `qwen`。
创建一个 Profile 声明 `provider.name: deepseek`。运行一次 Agent ，
确认产生的 `llm_calls` 行的 `provider` 字段是 `'deepseek'` 而不是 `qwen`，
并且**不会**两个都出现。

**验收场景**：

1. **假设** 一个 Profile 声明 `provider.name: deepseek`，且存在一个已配置的 `deepseek` Provider，**当** Agent 运行时发起 LLM 调用，**则** 该调用被派发到 `deepseek` Provider，并且恰好写入一行 `llm_calls`，`provider='deepseek'`。
2. **假设** 一个 Profile 声明了一个未知的 Provider 名（例如 `provider.name: gpt-99`），**当** Agent 运行时发起 LLM 调用，**则** 调用以"未知 Provider"错误失败，并且写入一行 `llm_calls`，`success=false, error_message` 包含该未知 Provider 名。
3. **假设** 多个 Profile，每个声明不同的 `provider.name`，**当** 每个 Profile 的 Agent 各自运行一次，**则** 每次调用都路由到对应声明的 Provider，`llm_calls` 记录按调用一一对应。

---

### 用户故事 2 — 每次调用都审计，无论成败（优先级：P1）

每一次 LLM 调用，不论结果如何，都**精确产出**一条持久化审计记录。
成功时，记录包含 provider、model、token 数、耗时；失败时，记录包含
非空的错误消息和 `success=false`。审计记录在调用方的响应返回之前写入。

**为什么是这个优先级**：day-one 可审计是宪法原则，是 OryxOS 区别于
开源 Agent OS 的关键差异化能力。审计员不会接受"查日志"——他们要的是
可从数据库查询的记录。

**独立测试**：人为给 `deepseek` 配一个已知错误的 API Key。运行 Agent。
确认 (a) 运行时向调用方返回错误，**且** (b) 存在一行 `llm_calls` 记录，
`provider='deepseek', success=false, error_message` 非空，`duration_ms` 有值。

**验收场景**：

1. **假设** 一次成功的 LLM 调用，**当** 调用完成，**则** 恰好存在一行 `llm_calls`，`success=true, duration_ms` 有值，`prompt_tokens` 与 `completion_tokens` 有值。
2. **假设** 一次失败的 LLM 调用（错误的 Key、网络异常、请求格式不合法），**当** 调用失败，**则** 调用方仍收到错误信息，并存在一行 `llm_calls`，`success=false, error_message` 非空，`duration_ms` 有值。
3. **假设** 一次在网络层成功但 Provider 返回错误响应（例如 HTTP 400），**当** 调用返回，**则** 写入一行 `llm_calls`，把错误记入 `error_message`，`success=false`。

---

### 用户故事 3 — 同一类型的多 Provider 共存（优先级：P2）

系统支持配置两个或更多共享同一底层类型的 Provider（例如一个 prod 账号
和一个 dev 账号，或 DeepSeek 官方 + 一个 DeepSeek 兼容的本地服务）。
每个实例用不同的 `name` 注册，只通过这个 name 选中。无串扰，无自动 fallback。

**为什么是这个优先级**：受监管企业通常有 prod / staging / dev 多个环境，
有时还需要供应商冗余。"同一底层类型只能配一个"是生产场景不能接受的限制，
但 MVP 演示可以暂时不演示这一点。

**独立测试**：配置 `deepseek-prod` 和 `deepseek-dev`，都接 DeepSeek API 但
用不同的 API Key。创建两个 Profile，各自对应一个。分别运行一次。
确认 `llm_calls` 行的归属按 provider name 正确分开，**且** 凭证没有交叉。

**验收场景**：

1. **假设** 两个 Provider `deepseek-prod` 和 `deepseek-dev`，凭证不同，**当** 两个 Profile 分别调用各自的 Provider，**则** 每次调用使用对应的凭证，并记入对应的 name。
2. **假设** `deepseek-prod` 健康但 `deepseek-dev` 凭证错误，**当** 一个 Profile 声明 `provider.name: deepseek-dev`，**则** 调用失败并被审计；`deepseek-prod` 不会被作为 fallback 联系。

---

### 用户故事 4 — 通过 Profile 热切换模型（优先级：P2）

运维人员可以修改 Profile 的 `provider.model` 字段（例如从 `deepseek-chat`
改为 `deepseek-coder`），经过一次常规的配置重载后，下一次调用就使用新模型。
新模型名体现在审计记录里。无须改代码、无须部署、无须重启整个运行时
（配置重载就是契约）。

**为什么是这个优先级**：模型选择是 Agent 开发中最常见的实验对象。
把它做成配置改动而不是代码改动，是核心的易用性要求，但不会阻塞 MVP。

**独立测试**：先以 `model: deepseek-chat` 启动 Profile。调用一次，确认
`llm_calls.model='deepseek-chat'`。把 Profile 改成 `model: deepseek-coder`。
重载配置。再调用一次，确认 `llm_calls.model='deepseek-coder'`。

**验收场景**：

1. **假设** 一个 Profile 的 `provider.model: deepseek-chat`，**当** 运行时发起一次调用，**则** 调用使用 `deepseek-chat`，`llm_calls.model='deepseek-chat'`。
2. **假设** 同一个 Profile 改为 `provider.model: deepseek-coder`，**当** 运行时在配置重载后发起调用，**则** 调用使用 `deepseek-coder`，`llm_calls.model='deepseek-coder'`。

---

### 用户故事 5 — 翻译工具 Schema，不执行工具（优先级：P2）

Profile 声明其 Agent 可以调用的工具（工具名列表或 schema 列表）。
LLM Provider 层把这些 schema 翻译成 Provider 原生的 function-calling 格式，
并随请求一起发出。当 LLM 响应时，该层提取其中所有的 tool call，并以
Provider 中立的格式返回给调用方。**此层绝不执行任何工具。**

**为什么是这个优先级**：工具 schema 是 Agent 触达外部世界的方式。
LLM 必须能"请求"工具调用，运行时必须能"读"这些请求。但执行是上一层
（ReAct 循环）的事，不是这一层的事。

**独立测试**：Profile 声明 3 个工具。验证发往 Provider 的请求恰好包含
3 个工具 schema（按 Provider 原生格式，例如 DeepSeek 的 `tools` 数组、
OpenAI 的 `functions` 数组）。当 LLM 响应中包含 `tool_call` 时，验证
返回给调用方的响应以 Provider 中立格式承载该 tool call，**且** 没有任何
工具代码作为本次 LLM 调用的副作用被执行。

**验收场景**：

1. **假设** 一个 Profile 声明 3 个工具，**当** 运行时发起一次 LLM 调用，**则** 发往 Provider 的请求恰好包含 3 个工具 schema 条目。
2. **假设** LLM 响应中含一个或多个 `tool_call` 条目，**当** 运行时把响应返回给调用方，**则** 响应以 Provider 中立格式承载这些 tool call，并且没有任何工具代码被触发。

---

### 边界情况

- **Profile 的 `provider.name` 在实例层未配置怎么办？** 启动期 fail-fast，抛出清晰错误，列出缺失的 Provider；运行时不应启动。
- **Profile 的 `provider.model` 为空或缺失怎么办？** Profile 加载期 fail-fast，抛出校验错误。
- **Provider 的凭证环境变量未设置怎么办？** 启动期（或 Provider 配置加载期）fail-fast，错误信息明确指出缺失的变量名。
- **Provider 返回 HTTP 429（限流）怎么办？** 错误透传给调用方；写一行 `llm_calls`；**不重试**。
- **网络层超时怎么办？** 超时错误透传给调用方；写一行 `llm_calls`，`success=false`；**不重试**。
- **Profile 引用了运行时未注册的工具名怎么办？** 超出本层职责 —— 该错误由上游 ReAct / Tool 层抛出，不由 Provider 层抛出。

## 需求 *（必填）*

### 功能需求

- **FR-001**：系统必须允许运维人员在实例级配置（例如 `application.yaml` 或等效文件）中按 name 配置一个或多个 Provider，每条配置包含 model、endpoint、options 与凭证引用。
- **FR-002**：系统必须在加载期把凭证引用解析为环境变量查找。硬编码凭证必须被拒绝。
- **FR-003**：系统必须在启动期 fail-fast：若任何已配置 Provider 的凭证环境变量缺失或为空，进程不应继续启动。
- **FR-004**：系统必须允许 Profile 声明 `provider.name` 与 `provider.model` 字段，其中 `name` 是路由到实例级 Provider 目录的键。
- **FR-005**：系统必须把 `ProviderService.invoke(providerName, request)` 派发到其注册 `name` 与 `providerName` 完全相等的那一个 Provider。Provider 选择必须按 name 进行，**禁止**通过容器类型扫描。
- **FR-006**：系统必须支持共享同一底层类型的多个 Provider，每个用不同的 name 注册，路由之间无歧义。
- **FR-007**：系统必须在每条调用路径上都写入恰好一行 `llm_calls` 审计记录（成功、网络异常、Provider 错误、未知 Provider 都不例外）。
- **FR-008**：`llm_calls` 记录必须包含：`provider`、`model`、`success`、`duration_ms`、`timestamp`；成功时还要包含 `prompt_tokens`、`completion_tokens`；失败时必须包含非空的 `error_message`。
- **FR-009**：系统必须把 Profile 工具列表中的 schema 定义翻译成 Provider 原生的 function-calling 格式后再发出请求。
- **FR-010**：系统必须以 Provider 中立格式把 LLM 响应中提取出的 tool call 返回给调用方。Provider 层**禁止**执行任何工具。
- **FR-011**：调用失败时，系统**禁止**进行重试、回退或备选 Provider 选择。错误**原样**透传给调用方一次。
- **FR-012**：系统**禁止**流式响应。Provider 调用是同步的请求/响应。
- **FR-013**：系统**禁止**做成本/Token 用量聚合或看板。每次调用的记录就是一行；聚合是另一个独立关注点（不在本层职责范围）。
- **FR-014**：系统必须支持通过配置重载在 Profile 内热切换 `provider.model`，**不要求**改代码，且新模型名体现在后续 `llm_calls` 记录中。

### 关键实体

- **Provider（配置）**：实例级目录中的一条命名条目。字段：`name`（路由键）、`model`、`endpoint`、`credentialRef`（环境变量名）、`options`（Provider 私有）。
- **LlmRequest**：`ProviderService.invoke` 的入参。字段：`providerName`、`messages`（对话历史）、`toolSchemas`（要声明的工具 schema 列表）、`temperature`、`maxTokens`。
- **LlmResponse**：`ProviderService.invoke` 的出参。字段：`textContent`、`toolCalls`（Provider 中立的 tool-call 条目列表）、`usage`（prompt tokens、completion tokens）、`finishReason`。
- **LlmCallRecord**（审计行）：持久化到 `llm_calls` 表。字段：`id`、`sessionId`（可空）、`profileName`、`provider`、`model`、`success`（布尔）、`errorMessage`（可空）、`promptTokens`（可空）、`completionTokens`（可空）、`durationMs`、`timestamp`。

## 成功标准 *（必填）*

### 可衡量结果

- **SC-001**：对于声明 `provider.name: deepseek` 且存在已配置 `deepseek` Provider 的 Profile，其每次调用产生的 `llm_calls` 行 `100%` 的 `provider` 字段是 `'deepseek'`。
- **SC-002**：对于失败的 Provider 调用（例如无效 Key），`100%` 的调用产生一行 `llm_calls`，`success=false`，`error_message` 非空。零静默失败。
- **SC-003**：在 Profile 中修改 `provider.model` 并重载配置后，`100%` 的后续 `llm_calls` 行反映新模型名。旧模型名不出现在任何新行中。
- **SC-004**：配置两个共享同一底层类型的 Provider 并通过不同 name 调用时，`llm_calls` 行按 name 正确归属，**无**凭证交叉。通过审计核验：每行的 `provider` 与声明的 name 一致，且响应载荷反映正确凭证对应的账户。
- **SC-005**：声明 N 个工具的 Profile 产生的对外 LLM 请求恰好包含 N 个工具 schema 条目（与 Profile 声明的工具数一致）。无重复、无遗漏。
- **SC-006**：LLM 返回的 tool call 以 Provider 中立格式透传给调用方，且 LLM 调用本身**无副作用**触发任何工具代码。通过单元测试断言：Provider 调用期间 `OryxTool.execute` 调用次数为 0。
- **SC-007**：运维人员可以在 5 分钟内完成新 Provider 的配置（写配置、设环境变量、重载），覆盖所有受支持的 Provider 类型，**不要求**改代码。

## 假设

- Agent Profile 是一个 YAML 文档，其 schema 由其他模块定义（不在本 spec 范围）。本 spec 仅消费 `provider.name`、`provider.model` 与工具列表字段。
- 实例级 Provider 目录在启动时配置；运行中动态变更 Provider 不在本 spec 范围。
- 配置重载由运行时的配置层负责（不在本 spec 范围）。Provider 层在每次调用时消费最新加载的配置。
- 审计表 `llm_calls` 是一个已存在的 schema（按项目宪法，day-one 持久化是必须的）。本 spec 定义"写入什么行"；表的 DDL 由存储层负责。
- 启动期"未知 Provider" 错误是期望行为（fail-fast）。曾考虑过"首次调用时再失败"的延迟方案，但被否决：运维体验更差。
- "热切换模型"指修改 `provider.model` 字段。修改 `provider.name` 为一个此前未知的 name，同样是 fail-fast 错误。
- 受支持的 Provider 类型清单（DeepSeek、Qwen、MiniMax 等）由可用的 Provider 实现决定；本 spec 不枚举预置了哪些 Provider。**MVP 演示必须包含 DeepSeek、Qwen、MiniMax 三家不同供应商**，以同时证明"多 Provider 共存"（同 type 多个实例）和"不同供应商并存"（多家厂商）两个价值点。
