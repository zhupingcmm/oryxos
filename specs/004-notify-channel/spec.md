# 功能规格说明书：Notify 出站推送（US-4 子能力）

**特性分支**：`004-notify-channel`
**创建日期**：2026-07-25
**状态**：草稿
**输入**：用户描述："第19节需求：Notify——结果主动送出去的统一出口。……（完整需求见第19节课件《Notify 模块 原理解析、实现与代码讲解》一、二部分）"

> **范围说明**：本 spec 是 US-4「Plugin Tool」（[CLAUDE.md §10](../CLAUDE.md)）的子能力 spec，覆盖 Notify 出站通道。它与 US-4 的内建 Tool（`FileTools` / `ShellTools` / `HttpTools`）、MCP 接入、Sandbox 实现平级，组合起来才构成完整的 US-4。Notify 的对外契约已在 [CLAUDE.md §9.5](../CLAUDE.md) 中给出，本 spec 在此基础上把"做什么 / 不做什么 / 验收标准"落到可演示、可审计的颗粒度。
>
> **关于需求来源**：用户输入引用了外部课件《Notify 模块 原理解析、实现与代码讲解》第 19 节。该课件在当前会话不可直接读取；本 spec 的功能范围以 [CLAUDE.md §9.5](../CLAUDE.md) 中已确定的 Notify 设计、宪法 §V 三档 Tool 接入、[CLAUDE.md §11](../CLAUDE.md) 中三个 Demo 对 Notify 的依赖作为权威输入。任何与 §9.5 不一致的字段均以 §9.5 为准，并在"假设"节标注。

---

## 用户场景与测试 *（必填）*

### 用户故事 1 — 单条消息送达默认通道（P1）🎯 MVP

一个 Agent 跑完一次任务（典型场景：每日天气、每日科技日报、每日 GitHub 日报），由 LLM 在最后一步决定"把结果告诉用户"，调用一条 `notify(content)` 工具调用；**不传 `channel` 参数**；系统按 Profile 配置的默认通道把这条消息送达企业微信群机器人。

**为什么是这个优先级**：这是三个验收 Demo 的最后一步闭环（[CLAUDE.md §11](../CLAUDE.md)）。如果没有"消息自动送出去"，Demo 只能跑通一半（Agent 答完了但群里没人收到）。Notify 是把 Agent 从"用户问 → 答"升级为"Agent 主动推送"的关键差异化能力。P1 一旦跑通，三个 Demo 的"钟推 → 群消息推送"主线立刻成立。

**独立测试**：一个最小 Profile，配置单一默认 webhook（指向一个本地 mock endpoint，例如 WireMock），LLM 在被问到"把今天的天气发给我"时调 `notify("今天北京 25°C，晴")`。断言：(a) mock endpoint 收到恰好一次 POST 请求，请求体包含 `今天北京 25°C，晴`；(b) 工具返回 success=true 给 LLM；(c) `tool_invocations` 表里多一条 `tool_name='notify', success=true` 的审计行。

**验收场景**：

1. **假设** Profile 配 `notify_channels: [{name: default, type: webhook, url: <mock URL>}]`，且该 URL 已加入 `tool.sandbox.http.allowed-domains` 白名单，**当** Agent 运行中 LLM 调 `notify("hello")`，**那么** mock endpoint 收到一个 POST 请求，body 包含 `hello`，**并且** `tool_invocations` 多一条 `success=true` 的审计行。
2. **假设** Profile **没有** `notify_channels` 配置，**当** Agent 运行中 LLM 试图调 `notify(...)`，**那么** 工具层返回 `ToolResult.success=false, errorMessage="profile 未配置 notify_channels"`，**并且** LLM 在下一轮看到这条错误并能据此调整响应（不抛异常）。
3. **假设** Profile 配默认通道但 webhook URL 返回 5xx，**当** Agent 调 `notify(...)`，**那么** `ToolResult.success=false` 且 `errorMessage` 包含 HTTP 状态码，**并且** `tool_invocations` 写入 `success=false` 审计行（包含 status_code 与 duration_ms）。

---

### 用户故事 2 — 多通道按名路由（P2）

同一个 Profile 同时配置了多个通道（如企业微信群、飞书群、钉钉群三个群机器人），LLM 通过 `notify(content, channel="feishu-tech")` 显式指定通道，系统只把消息送到飞书群，不送到另外两个群。

**为什么是这个优先级**：企业里一个 Agent 的产物常常要分发给多个接收方（运维日报→SRE 群、安全日报→安全群、综合日报→管理群）。P2 让"按 channel 路由"成为一等能力，避免每加一个群就重写一次 Profile。注意 P1 已覆盖"默认通道"这一最常见路径，P2 是其严格超集。

**独立测试**：Profile 配三个通道 `default` / `feishu-tech` / `dingtalk-security`，每个指向不同的 mock endpoint；LLM 调 `notify("...")` 时显式带 `channel="feishu-tech"`。断言：(a) 只有 feishu 对应的 mock 收到请求；(b) `tool_invocations` 行的某个字段（`channel` 字段名待 plan 阶段定）记录 `feishu-tech`；(c) `default` 与 `dingtalk-security` 对应的 mock 没收到请求。

**验收场景**：

1. **假设** Profile 配 `notify_channels: [{name: feishu-tech, ...}, {name: dingtalk-security, ...}]`，**当** LLM 调 `notify("...", channel="feishu-tech")`，**那么** 仅 `feishu-tech` 对应的 endpoint 收到一次 POST，另外两个 endpoint 零请求。
2. **假设** LLM 调 `notify("...", channel="ghost-channel")`（不在 Profile 配置中），**当** 系统处理这次调用，**那么** `ToolResult.success=false, errorMessage` 明确指出未知通道名，**并且** LLM 在下一轮看到错误能改用正确通道名，**并且** 不发起任何 HTTP 请求。
3. **假设** Profile 配 `notify_channels: [feishu-tech]`，**当** LLM 调 `notify("...", channel="feishu-tech")`，**那么** 行为等价于用户故事 1 中无 `channel` 参数 + 默认通道的情况（即未指定 channel 时落到 default；若 default 未配置则失败而非静默选第一个）。

---

### 用户故事 3 — 出站域名走 Sandbox 白名单（P2）

Notify 的每次 HTTP 出站请求都必须先过 [CLAUDE.md §9.4](../CLAUDE.md) 的 `Sandbox.enforce(HTTP_REQUEST, url)` 校验。若目标 URL 的 host 不在 `oryxos.tool.sandbox.http.allowed-domains` 白名单内，必须在真正发 HTTP 之前拒掉，并把 sandbox violation 作为 tool 错误返回给 LLM。

**为什么是这个优先级**：宪法原则 §V（企业级 Tool 治理）+ [CLAUDE.md §9.4](../CLAUDE.md) 明确要求所有出站 HTTP 走白名单。Notify 是出站 HTTP 的高频入口（每个 Demo 都会触发），不做白名单就是给企业内网开了个无监控的后门。P2 与 P1 互补：P1 是"消息送得出去"，P2 是"消息只能送到允许的地方"。

**独立测试**：Profile 配一个 webhook URL `https://evil.example.com/hook`（且 `evil.example.com` 不在白名单）。LLM 调 `notify("secret")`。断言：(a) 系统**不**对该 URL 发起 HTTP 请求（用 WireMock 计数验证零请求）；(b) `ToolResult.success=false, errorMessage` 包含 sandbox violation 信息；(c) `tool_invocations` 写入 `success=false` 审计行，`errorMessage` 含 `sandbox` 关键字。

**验收场景**：

1. **假设** `tool.sandbox.http.allowed-domains` 仅含 `api.weixin.qq.com`，**当** Profile 配的 webhook URL 是 `https://api.weixin.qq.com/corp/webhook/send`，**那么** 该 notify 调用通过校验并正常发出。
2. **假设** `tool.sandbox.http.allowed-domains` 仅含 `api.weixin.qq.com`，**当** Profile 配的 webhook URL 是 `https://evil.example.com/hook`（不论是 Profile 写错还是被 Profile 模板被改），**那么** 系统在发出 HTTP 前抛 `SandboxViolationException`，**并且** tool 调用以 `success=false` 结束，**并且** LLM 看到错误后可以选择放弃 / 改用另一个 channel / 向用户报错。
3. **假设** webhook URL 是 IP 而非域名（例如 `http://10.0.0.5:8080/hook`），**当** 系统校验该 URL，**那么** 该 IP 不在任何白名单域名下，校验失败，行为同场景 2（除非显式配置 IP 白名单；本 spec 不要求支持 IP 白名单）。
4. **假设** Profile 的 `notify_channels` 包含两条 URL：一条过白名单、一条不过，**当** LLM 按 channel 名指定过白名单的那条，**那么** 调用成功；**当** LLM 指定未过白名单的那条，**那么** sandbox 拦截；两种结果互不影响。

---

### 用户故事 4 — 多通道并发发送与部分失败（P3）

一次 `notify(content)` 调用**不带 channel 参数**且 Profile 配了多条通道（多群同时广播），系统并行发出，且每条独立审计：某一条失败不影响其他条；总体的 `ToolResult.success` 仅在**全部**失败时才为 false（否则视为部分成功，并明确报告哪些通道成功哪些失败）。

**为什么是这个优先级**：多通道广播是"日报"类 Agent 的常见需求（同时发到 SRE 群 + 管理群）。完全串行效率低、完全并行又需要处理"部分失败"的语义。P3 在 P1+P2 已成立的基础上扩展并发与聚合语义；不在主线 critical path 上但属于企业级使用常见场景。

**独立测试**：Profile 配 3 条 channel，2 条指向健康 mock，1 条指向不可达 URL（连接超时）。LLM 调 `notify("...", channel=ALL)` 或类似"广播"语义（具体参数契约在 plan 阶段确定）。断言：(a) 3 条请求并发发出（从开始到结束的 wall-time 远小于串行的 3 倍）；(b) 健康的两条 mock 各收到一次请求；(c) 不可达的那条收到 `success=false` 审计；(d) `ToolResult.success=true`，`errorMessage` 字段包含失败通道的明细。

**验收场景**：

1. **假设** Profile 配 3 条全健康的 channel，**当** LLM 调"广播"语义，**那么** 3 条 mock 在并发窗口内全部收到请求，`ToolResult.success=true`，3 条审计行 `success=true`。
2. **假设** Profile 配 3 条 channel，1 条返回 500，**当** LLM 调"广播"语义，**那么** 2 条成功、1 条失败，`ToolResult.success=true` 但 `errorMessage` 列出失败通道与状态码，3 条审计行分别记 success / failure。
3. **假设** Profile 配 3 条 channel，全部失败（连接超时 / DNS 失败 / 5xx），**当** LLM 调"广播"语义，**那么** `ToolResult.success=false`，3 条审计行 `success=false`，LLM 收到错误明细后可重试或放弃。

> **设计取舍说明**（P3 假设，已在"假设"第 7 条固化）：核心阶段"广播"语义不引入新参数；P3 由 Profile 同时配 `channel="default"` 之外的 N 条 channel 触发，**只要 LLM 不显式指定 channel，就视为对所有已配 channel 广播**。显式指定 channel 时严格路由到单条。该取舍需在 plan 阶段用契约文档固化。

---

### 边界情况

- **webhook URL 不可达**（DNS 失败 / 连接超时 / TLS 握手失败）：单条通道失败按 P1/P2 行为处理，错误信息分类清晰（network_unreachable / timeout / tls_error）。
- **HTTP 状态码非 2xx**：视为该通道失败，状态码写入审计行的 errorMessage。
- **LLM 传空 content / null content**：`ToolResult.success=false, errorMessage="content 不能为空"`，不发起 HTTP 请求。
- **LLM 传超大 content**（超过 webhook 平台限制，例如企业微信机器人限制 4096 字节）：核心阶段**不**做截断；`ToolResult.success=false, errorMessage="content 超长 (X bytes, limit=4096)"`，由 LLM 决定是否拆分。
- **同一条 `notify` 在同一 Agent 同一次循环里被并发调用两次**：核心阶段不保证去重；两次都会发出；这是 LLM 行为，不在 Notify 责任范围。
- **Notify 工具对 Agent 不可见（Profile 没配 notify_channels）**：`notify` Tool 不出现在该 Profile 的可用 Tool 列表里，LLM 调不到；这优于"调到了但总是失败"的体验。
- **webhook URL 含 query 参数里的 key**（企业微信/飞书 token 通常在 URL 上）：脱敏写入日志与审计行的 url 字段（`xxx?key=***`），不阻塞发送。
- **Notify 调用在 Agent 循环中触发**：与其他 Tool 一致，遵守 `MAX_ITERATIONS` 上限（核心默认 10，[CLAUDE.md §9.1](../CLAUDE.md)）；Notify 不额外占用迭代。

---

## 需求 *（必填）*

### 功能需求

- **FR-001**：系统 MUST 提供 `NotifyChannelAdapter` 出站接口，对接"消息怎么出去"（对称于入站 Channel Adapter，[CLAUDE.md §9.5](../CLAUDE.md)）。
- **FR-002**：核心阶段 MUST 仅实现 `WebhookNotifyAdapter`（基于 HTTP POST + JSON payload），覆盖企业微信/飞书/钉钉群机器人的通用 webhook 形态；不引入 SMTP、Slack native、Teams native 等额外协议。
- **FR-003**：系统 MUST 把 `notify(content, channel)` 作为 `OryxTool` 实现注册到 `ToolRegistry`（[CLAUDE.md §9.5](../CLAUDE.md)）；`content: String` 必填，`channel: String` 缺省时按 Profile 的默认通道路由。
- **FR-004**：每次出站 HTTP MUST 在请求发出前过 `Sandbox.enforce(HTTP_REQUEST, url)`（[CLAUDE.md §9.4](../CLAUDE.md)）；未通过校验 MUST 抛 `SandboxViolationException`，由 `ToolExecutor` 既有的审计路径消费。
- **FR-005**：Profile MUST 支持 `notify_channels: [{name, type, url, secret?}, ...]` 配置；`name` 在 Profile 内唯一；`type` 核心阶段仅合法值 `webhook`；`url` 必填；`secret` 核心阶段可空（详见 FR-013）。
- **FR-006**：当 LLM 调 `notify(content)` 不带 `channel` 参数时，系统 MUST 按以下优先级路由：
  1. 若 Profile 的 `notify_channels` 中存在名为 `default` 的通道 → 路由到 `default` 通道（FR-006 主语义）
  2. 若不存在 `default` 通道但 Profile 仅配了 1 条 `notify_channels`（不论 name） → 等同于路由到该唯一通道（**MVP 单通道语义**）
  3. 若不存在 `default` 通道且 Profile 配了 N=0 条 → `ToolResult.success=false, errorMessage="profile 未配置 notify_channels"`
  4. 若不存在 `default` 通道且 Profile 配了 N>1 条 → `ToolResult.success=false, errorMessage="channel 不能省略: profile 配了 N 条通道（无 default）"`（**MVP 显式降级**，详见 C2 修复说明 + 用户故事 4 设计取舍）
- **FR-007**：当 LLM 显式指定 `channel="<name>"` 且 `<name>` 不在 Profile 的 `notify_channels` 中时，系统 MUST 返回 `ToolResult.success=false, errorMessage="未知通道: <name>"`，且 MUST NOT 发起任何 HTTP 请求。
- **FR-008**：**广播语义仅在 US-4（多通道并发与部分失败，P3）落地**。MVP 阶段（US-1）的 FR-006 第 4 条把"无 default + N>1"显式降级为"必须显式指定 channel"。US-4 的广播触发条件（最终 spec 阶段将固化为契约）= LLM 不显式指定 `channel` **且** Profile 含名为 `default` 的通道 **且** Profile 同时配了 N≥2 条通道时，由 Profile 层显式声明 `broadcast: true` 开启广播；否则走 FR-006 单通道路径。
- **FR-009**：Notify 调用 MUST 复用既有的 `tool_invocations` 审计路径（Constitution §VI，不新增审计表）；审计行 MUST 包含 `tool_name='notify'`、`success` 布尔、`duration_ms` 数值，**以及**新增的 `channel` 字段与 `notify_status_code` 字段（HTTP 状态码；网络失败时为 `null`）。
- **FR-010**：HTTP 状态码 < 200 或 >= 300 MUST 视为该通道发送失败；失败明细（status code、响应体前 256 字节）写入 `tool_invocations.error_message`。
- **FR-011**：核心阶段 MUST NOT 在 Notify 调用失败时自动重试；失败立即作为 tool 错误返回给 LLM，让 LLM 决定重试或放弃（Demo-First 原则，避免无限循环）。
- **FR-012**：每次 HTTP 出站 MUST 配置可配置超时（默认 5 秒）；超时 MUST 视为该通道失败，错误分类为 `timeout`。
- **FR-013**：webhook URL 含敏感 token（典型 `?key=` / `?access_token=` / `?secret=`）时，审计行与日志 MUST 对该 query 参数值做脱敏（`key=***`）；发送行为不受影响。
- **FR-014**：Notify 模块的全部代码 MUST 落在 `oryxos-tool` 模块内（Constitution §I，不新增模块）；不可拆出 `notify-channel` 子模块。

### 非功能需求

- **NFR-001**：单条 notify 调用（不含 LLM 思考）的端到端 wall-time MUST ≤ 3 秒（在沙箱允许的域名、健康 endpoint 场景下）。
- **NFR-002**：N 条通道的广播 wall-time MUST 满足以下闭式表（并行发送；不应随 N 线性增长）：

  | N (通道数) | wall-time 上限 (P95) |
  | ---------- | -------------------- |
  | 1          | ≤ 3 秒               |
  | 2          | ≤ 4 秒               |
  | 5          | ≤ 5 秒               |
  | 10         | ≤ 6 秒               |
  | 20         | ≤ 7 秒               |
  | > 20       | ≤ 7 秒 (封顶)        |

  **推导逻辑**：单条基线 3 秒；每多 1 通道 +1 秒开销（虚拟线程并发）；5 条后增长放缓到封顶 7 秒（网络栈 + JDK HttpClient 并发连接上限）。N=1 即单通道路径，与 NFR-001 一致。
- **NFR-003**：Notify 调用 MUST NOT 阻塞 ReAct 主循环的其他迭代；任何 HTTP I/O MUST 在 Spring `@Async` 或等价的非阻塞模式下执行（与 `MAX_ITERATIONS` 配合，[CLAUDE.md §9.1](../CLAUDE.md)）。
- **NFR-004**：webhook payload 中的 `content` MUST UTF-8 编码、JSON 序列化；企业微信 / 飞书 / 钉钉各自的签名 / 消息格式差异由 `WebhookNotifyAdapter` 内部适配（基于 channel 配置中的 type 字段，但核心阶段 type 仅 `webhook` 一种，故而最小适配即可）。

### 关键实体

- **NotifyChannel**：Profile 内的通道声明。属性：`name`（Profile 内唯一）、`type`（核心阶段仅 `webhook`）、`url`、`secret?`。生命周期属于 Profile，不独立持久化。
- **NotifyResult（运行时，仅方法返回值，不入库）**：单条通道发送结果。属性：`channelName`、`success`、`statusCode?`、`errorMessage?`、`durationMs`。Notify 整体工具返回值（`ToolResult`）聚合所有通道的 `NotifyResult`。
- **tool_invocations 行（持久化）**：复用 §VI 现有表，新增字段 `channel`（String，可空——非 notify 工具为空）、`notify_status_code`（Integer，可空）。

---

## 成功标准 *（必填）*

### 可测量结果

- **SC-001**：三个验收 Demo（每日天气 / 每日科技日报 / 每日 GitHub 日报，[CLAUDE.md §11](../CLAUDE.md)）均能在真实 LLM + 真实 webhook 下端到端跑通，目标群在 Agent 跑完 ≤ 30 秒内收到推送；任意一个 Demo 跑通即可视为本 spec MVP 达成。
- **SC-002**：100% 的 notify 工具调用产生一行 `tool_invocations` 审计行（不论 success / failure、单一通道 / 多通道广播、网络失败 / 状态码错误 / Sandbox 拦截）。
- **SC-003**：所有未在 `tool.sandbox.http.allowed-domains` 白名单内的 webhook URL 在 notify 调用时被 100% 拦截，且拦截发生在 HTTP 请求**之前**（用 WireMock 请求计数证明零请求）。
- **SC-004**：notify 调用的 wall-time 满足 [NFR-002](#非功能需求) 的闭式表 —— 单条 ≤ 3 秒；10 通道广播 ≤ 6 秒。
- **SC-005**：notify() 调用失败（HTTP 5xx / 超时 / Sandbox 拦截）时，ReAct 主循环**不**中断；LLM 在下一轮看到 `ToolResult.success=false` 与错误明细，能据此调整响应（不抛异常给调用方）。
- **SC-006**：审计行中 webhook URL 的敏感 query 参数值（`key` / `access_token` / `secret` 等）被 100% 脱敏（`grep` 审计数据不应命中明文 token）。
- **SC-007**：在 JDK 21 / Spring Boot 3.x 单二进制部署下，集成测试 100% 通过；`mvn verify` 全绿（继承 US-2 / US-3 的 CI 基线）。

### 业务结果

- **SC-008**：运维 / 客服 / HR 等业务方能够在配置好 webhook 后，"零代码"通过 `AGENT.md` 内的指令让 Agent 把任意产物推送到指定群机器人（PoC 演示）。

---

## 假设

1. **Notify 是 US-4 的子能力**，不是独立 User Story；本 spec 不替代 US-4 的 plan.md，但作为 Notify 的可演示切片。
2. **HTTP webhook 是核心阶段唯一的通知类型**；SMTP、Slack native、Teams native、邮件、短信均不在核心阶段范围内。
3. **重试策略不在核心阶段**；Notify 失败立即返回给 LLM，让 LLM 决策。这与宪法 §VII「跑通优先于完美」一致。自动重试（如指数退避）放扩展阶段。
4. **不引入独立的 notify_invocations 审计表**；复用 `tool_invocations` 表 + 新增 `channel` / `notify_status_code` 两列，与宪法 §VI「day-one 审计」与 §I「不新增模块/表」一致。
5. **企业微信 / 飞书 / 钉钉三家 webhook 形态**：核心阶段用一个通用的 JSON-over-HTTP 适配即可覆盖（text / content 字段名差异在 `WebhookNotifyAdapter` 内部做映射）；不做三家平台专属的签名算法适配（HMAC 之类）放扩展阶段。
6. **webhook secret 字段**（FR-005 标 `secret?`）核心阶段保留字段但不强制使用——仅为未来扩展留位，避免 schema 演进；当前实现忽略 secret。
7. **广播语义**：核心阶段按用户故事 4 的取舍——"LLM 不显式指定 channel 时，视为对 Profile 全部已配通道广播"。这与 P1 的"默认通道单条"语义并行共存（取决于 channel 参数是否显式给出）。该取舍在 plan 阶段固化为契约。
8. **Notify 调用失败的退路**：当 LLM 调 notify 但所有通道都失败时，ReAct 主循环继续走完当前 iteration；LLM 在下一轮可以选择"再试一次"或"告诉用户推送失败"。**Notify 不替代最终用户响应的返回路径**——即使用户看不到推送，Session 对话历史里仍然有完整的工具调用痕迹。
9. **API key 等敏感字段在 Profile YAML 中出现时**（FR-013 已涵盖 webhook URL 内的 token），整段 Profile 加载流程复用 US-1 / 003-cli-commands 现有的 `ConfigLoader` 脱敏路径（[specs/003-cli-commands/contracts/](../003-cli-commands/contracts/)），不重复实现。
10. **Notify 不依赖 Memory 或 Web Service 能力**；但 Notify 在 Agent 内被调用的前提是 US-2（ReAct 主循环）已实现（已 ✓）且 US-4 的 ToolRegistry 已存在（部分 ✓：`ToolRegistry` 在 [oryxos-tool](oryxos-tool/) 是空壳；Notify spec 的实施可以驱动 ToolRegistry 接 OryxTool 实现）。

## 不在范围内（Out of Scope）

- ❌ SMTP / 短信 / 推送通知（Push Notification）/ Slack native / Teams native 等非 webhook 通道——扩展阶段
- ❌ 自动重试（指数退避、dead letter queue）——扩展阶段
- ❌ webhook 签名验证（HMAC-SHA256、Timestamp 校验等）——扩展阶段（FR-013 仅为日志脱敏）
- ❌ Notify 调用结果查询 UI / Web 仪表板——扩展阶段（核心阶段 [oryxos-web](oryxos-web/) 是空壳）
- ❌ 多租户级别的 Notify 配额 / 限流——扩展阶段
- ❌ Notify 模板引擎（Markdown / Card / 富文本）——核心阶段只发送纯文本
- ❌ 端到端加密 / 消息回执确认 / 撤回——核心阶段无
