# 功能规格说明书：定时任务模块（AgentScheduler）

**功能分支**：`008-agent-scheduler`
**创建日期**：2026-07-27
**状态**：Draft
**输入**：用户描述："第25节需求：定时任务模块——让 Agent 不用人喂话、到点自己干活。定时任务是第三种触发源（CLI 人推、Web 人推、定时钟推），到点了系统自己拼一条消息喂给跟 CLI/Web 完全一样的统一编排入口，ReAct/Tool/Provider 一个字不用改。"

> **范围说明**：本 spec 是 OryxOS 核心阶段第 5 个能力延伸触发的"地基补完"——
> 把 CLAUDE.md §5 已经声明的 `AgentScheduler`（位于 `oryxos-core` 模块）从"接口预留"补到
> "端到端跑通"。本 spec 不引入新模块、不重写 ReAct / Tool / Provider / Memory，
> 只新增 ①Schedule 注册链路 + ②Cron 调度触发链路 + ③`task_executions` 审计 + ④并发去重。
>
> 三个验收 Demo（[CLAUDE.md §11](../../CLAUDE.md) 每日天气 / 每日科技日报 / 每日 GitHub 日报）
> 全部依赖本 spec；本 spec 落地后三个 Demo 才能从"手跑 CLI"升级为"系统钟推"。

---

## 用户场景与测试 *(mandatory)*

### 用户故事 1 — Profile 注册 Schedule（P1） 🎯 MVP

企业用户给 Agent 配 Profile YAML 的 `schedules` 字段（cron + 时区 + 触发消息 + enabled），
启动 OryxOS 时系统扫描 `.oryxos/profiles/*.yaml`（[CLAUDE.md §12](../../CLAUDE.md) 工作区结构），
把每条 schedule 写到 SQLite 的 `scheduled_tasks` 表（按 `task_id` upsert），并在内存里注册到调度器。
`enabled=false` 的 schedule 不进调度器。

**为什么是这个优先级**：注册是"钟推"全链路的入口——没有注册就没有 cron，
没有 cron 就没法"到点触发"。同时 `scheduled_tasks` 表（[CLAUDE.md §13](../../CLAUDE.md)
核心阶段 day-one 表）从 0 行变 N 行，是审计可视化（运营能查"哪些 Agent 配了定时")
的最小地基。MVP 跑通 = 后 3 个 US（触发 / 重试 / 时区）才有载体。

**独立测试**：单 Agent Profile 配 1 条 `schedule: { id: "daily-weather", cron: "0 8 * * *",
zone: "Asia/Shanghai", message: "查一下今天上海天气", enabled: true }`；启动后 SQLite
`scheduled_tasks` 表查到 1 行（task_id="daily-weather", profile_name="weather-agent"）；
`schedule.list` CLI 命令输出该 schedule 的 `next_run_at`。

**验收场景**：

1. **假设** Profile `weather-agent` 含 1 条 `schedule`，**当** `oryxos serve` 启动完成，**那么**
   `scheduled_tasks` 表新增 1 行（task_id / profile_name / cron_expr / timezone / enabled /
   next_run_at），**并且** CLI `oryxos schedule list` 输出该 schedule。
2. **假设** 同一 Profile 含 3 条 schedule，**当** 启动完成，**那么** `scheduled_tasks` 表
   有 3 行（每行独立 task_id），**并且** 调度器内存里注册 3 个 cron 触发器。
3. **假设** Profile 含 1 条 `enabled: false` 的 schedule，**当** 启动完成，**那么**
   `scheduled_tasks` 表有该行（保留配置），**但是** 调度器内存里**没有**该 cron 触发器。
4. **假设** 启动时扫描到非法 cron（如 `"not-a-cron"`），**当** 解析失败，**那么**
   该 schedule **拒绝注册**（不写表、不进内存），**并且** 启动日志明确打印
   `task_id=<x> cron parse failed: <reason>`（LLM-friendly errorMessage）。

---

### 用户故事 2 — 到点钟推（P1） 🎯 MVP

调度器命中 cron tick 时，系统自己拼一条消息（Profile `schedule.message`）+ 新建 Session，
喂给跟 CLI / Web 完全一样的 `AgentService.process(Session, String)` 入口，走完整的
Provider → ReAct → Tool → Memory → Notify 链路。ReActLoop / PromptBuilder / ToolExecutor
一字不改。Session 落 `sessions` 表（带 `task_id` 元数据），执行结果落 `tool_invocations`
和 `llm_calls`（既有两表）+ `task_executions`（新增）。

**为什么是这个优先级**：钟推的"业务结果可见性"是核心价值——三个 Demo 全部依赖。
如果不实现，OryxOS 跟"CLI/Web 二选一"没差别；定时调度能力是 Agent OS 跟单点 Chat
客户端的本质区别。MVP = US-1 + US-2 已经能跑通"每日天气"Demo。

**独立测试**：Profile `daily-weather-agent` 配 `cron: "0 8 * * *" zone: "Asia/Shanghai" message:
"查上海今天天气"`；mock 当前时间 = 2026-07-28 08:00:00 +08:00；启动 Scheduler + 等待 1 个
tick；观察 ①`task_executions` 表新增 1 行（success=true, duration_ms>0, session_id 关联）；
②`sessions` 表新增 1 行（metadata 含 task_id="daily-weather"）；③NotifyChannelAdapter
发出 webhook（每日天气 Demo 验收路径）。

**验收场景**：

1. **假设** schedule 在 next_run_at 命中，**当** 调度器触发，**那么** 系统调
   `AgentService.process(session, schedule.message)`，session 是新建的（session_id 不与
   任何已有 session 冲突），session.metadata 含 `task_id=<id>`，`started_at=now()`，
   `source="scheduler"`。
2. **假设** 触发完成，**当** `AgentService.process()` 返回，**那么** `task_executions`
   表新增 1 行（task_id / session_id / started_at / duration_ms / success=true /
   `last_run_at` 更新到当前时间，`next_run_at` 推到下一个 cron tick）。
3. **假设** 触发期间 LLM 调用 / Tool 调用发生，**当** 调度链路结束，**那么**
   `llm_calls` 和 `tool_invocations` 表各 N 行（既有审计链），**并且** 这些行的
   `session_id` 与本任务的 `task_executions.session_id` 一致（可关联回溯）。
4. **假设** Profile 同时配 `notify_channels`（如 Webhook），**当** Agent 在 ReAct 链路
   调 `notify`，**那么** 出站 webhook 收到推送（NotifyChannelAdapter 落地走 004 既有
   链路），**并且** `tool_invocations.channel` 字段记录实际通道名。

---

### 用户故事 3 — 并发去重 + 失败重试（P2）

同一个 task 在上一次执行未完成时，下一个 cron tick 命中**跳过**（不重叠触发）。
执行失败的 task 写 `task_executions(success=false, error_message=...)` 后，调度器继续按
cron 等下一个 tick（不等距重试，只在原 cron 点重跑）。手动补跑（"人推"通过 CLI / Web
触发同一条 schedule）走 `AgentService.process()` 同样的入口，但**绕开** scheduler 的
cron（立即触发），与自动触发的执行链路一致。

**为什么是这个优先级**：Demo 长任务场景下（如"每日科技日报"调 GitHub API 慢），cron
间隔 1 小时但执行要 5 分钟，重叠触发会浪费 LLM 调用、产生双份 webhook；并发去重是
"钟推可用性"的基本门槛。手动补跑对应"Demo 失败时人推一次"，是企业运维的真实诉求。

**独立测试**：

- 并发去重：Profile `slow-agent` 配 `cron: "* * * * *"`（每分钟） + mock `AgentService`
  执行耗时 90s；启动后等待 2 个 tick；观察 ①`task_executions` 2 次执行尝试，但实际
  只 1 次进入 `AgentService.process()`（第二次 tick 命中"执行中"被跳过），②日志明确
  打印 `task_id=<id> previous execution still running; skipping tick`。
- 失败重试：mock `AgentService` 抛 `RuntimeException`；触发一次；观察 `task_executions`
  行 `success=false, error_message="<exception message>"`（**不含** stack trace）；
  下个 tick 仍触发（不"熔断"）。
- 手动补跑：CLI `oryxos chat slow-agent` 与 Scheduler 自动触发**走同一条路径**
  （`AgentService.process()`），区别只在 session.source（"cli" vs "scheduler"）。

**验收场景**：

1. **假设** task A 在执行中（执行开始后 30s），**当** 下个 cron tick 命中，**那么**
   调度器**跳过**本次触发（不调 `AgentService.process()`），**并且** 日志打印
   `task_id=A skip reason="previous run still in progress"`。
2. **假设** task A 执行失败（`AgentService` 抛异常），**当** 失败处理完成，**那么**
   `task_executions` 写 `success=false` + `error_message="<msg>"`（不含 stack trace），
   **并且** 调度器**不熔断**——下个 tick 仍触发（除非 Profile 显式 `enabled=false`）。
3. **假设** 用户通过 CLI `oryxos chat daily-weather` 手动触发，**当** 执行完成，**那么**
   session.metadata.source="cli"（vs 钟推时 source="scheduler"），**但是** 同一 Profile
   / 同一 `notify_channels` / 同一 `notify_channels` 路由——与钟推**走完全相同的**
   `AgentService.process()` 实现路径（不是另写一条分支）。
4. **假设** task A 连续 3 次执行失败，**当** 第 4 个 tick 命中，**那么** 调度器仍
   触发（不熔断）；**并且** `task_executions` 留下完整失败历史（运营可审计"这 Agent
   最近老挂"）。

---

### 用户故事 4 — 时区 + 审计完整性（P3）

调度器按 Profile 配置的 `zone`（时区）解析 cron，不依赖 JVM 默认时区；启动时
`AgentScheduler` 把每条 schedule 的 `next_run_at` 计算到该 zone 的本地时间，再转 UTC
存 `scheduled_tasks.next_run_at_utc`。DST（夏令时）切换由 cron 库（`cron-utils` 或 JDK
`java.time.ZonedDateTime`）按 zone 规则自动处理。`task_executions` 完整记录 `task_id /
session_id / started_at / duration_ms / success / error_message`，与 `sessions` /
`tool_invocations` / `llm_calls` 三表可关联回溯（同一 session_id）。

**为什么是这个优先级**：Demo 跨时区（如 Agent 跑在美国时区，但用户在中国配
`zone: Asia/Shanghai`）和合规审计（"那天的 webhook 是谁发的？"）都需要精确时区 +
session ↔ task 关联。MVP 阶段（US-1 + US-2）可以简化为 JVM 默认时区，但跨时区企业
落地必须 P3 完成。

**独立测试**：

- 时区：Profile `daily-news` 配 `cron: "0 9 * * *", zone: "Asia/Shanghai"`；mock 当前
  JVM 时区 = UTC；启动后 `scheduled_tasks.next_run_at_utc` 应等于
  `2026-07-28T01:00:00Z`（= 上海 09:00 UTC+8）。
- DST：Profile `daily-news` 配 `zone: "America/New_York", cron: "0 2 1 3 *"`（美东 3 月
  1 日 02:00，跳过 DST）；启动后验证调度器在 DST 切换日不"丢触发"也不"双触发"。
- 审计关联：`task_executions` 行的 `session_id` 必须能在 `sessions` 表查到；
  `sessions.metadata.task_id` 必须能在 `scheduled_tasks` 表查到。

**验收场景**：

1. **假设** JVM 默认时区 = UTC，Profile `daily-news` 配 `zone: Asia/Shanghai,
   cron: "0 9 * * *"`，**当** 启动完成，**那么** `scheduled_tasks.next_run_at_utc`
   = 当日 `01:00:00Z`（即 `09:00 +08:00`），**并且** 到点触发实际发生在
   上海时间 09:00（**不**在 UTC 09:00）。
2. **假设** Profile `daily-news` 配 `zone: America/New_York, cron: "0 2 1 3 *"`（3 月 1 日
   美东 02:00，跳过 DST），**当** DST 切换日（2026-03-08）03:00 触发，**那么**
   本次触发时间正确（不丢触发、不双触发），`task_executions.started_at_utc` = 该日
   `07:00:00Z`（EST → EDT 已切换）。
3. **假设** task 触发后产生 LLM 调用和 Tool 调用，**当** 调度链路结束，**那么**
   `task_executions.session_id` 在 `sessions` 表 1 行命中，
   `sessions.metadata.task_id` 在 `scheduled_tasks` 表 1 行命中，
   `sessions.metadata.task_id == task_executions.task_id`（双向关联一致）。
4. **假设** 失败 task 写 `task_executions`，**当** 审计员查询，**那么**
   `error_message` 含异常 message，**不**含 stack trace（与 [CLAUDE.md §18](../../CLAUDE.md)
   "审计 day-one" + 007-sandbox-whitelist 契约一致）。

---

### 边界情况

- **Profile 热修改**：`AGENT.md` 改了 schedule 后，**核心阶段不热加载**（重启 OryxOS
  生效）；扩展阶段再考虑文件 watch + reload。
- **JVM 重启**：`task_executions` 历史保留；`scheduled_tasks.last_run_at` 不更新到
  重启前的 tick——重启后调度器按当前时间重新算 `next_run_at`，**不补跑跳过的 tick**
  （避免 1 天 Agent 没启动就堆 24 条补跑）。
- **执行时长 > cron 间隔**：长任务执行 5 分钟 + cron 每分钟触发 → 触发被去重跳过
  （US-3 场景 1），**不**堆积；下次 cron tick 仍按原计划触发。
- **时区非法**：`zone: "Invalid/Zone"` → 启动拒绝该 schedule（US-1 验收场景 4 同款
  fail-closed）；不静默 fallback 到 JVM 默认时区。
- **Spring 上下文启动失败**：`AgentScheduler` fail-closed——启动失败时不接管任何
  cron 触发（避免半启动状态乱触发）；CLI / Web 入口继续可用。
- **重复 task_id**：同一 Profile 内两条 schedule `id` 相同 → 启动拒绝，提示
  `duplicate task_id=<x> in profile=<name>`；不静默后写覆盖前写。
- **Session 创建失败**：`AgentService.process()` 入口抛 `SessionCreationException`
  → `task_executions(success=false, error_message=...)` 写入；调度器继续按 cron 等下个
  tick。

---

## 需求 *(mandatory)*

### 功能需求

- **FR-001**：系统 MUST 解析 Profile YAML `schedules` 字段为结构化对象（`id` / `cron`
  / `zone` / `message` / `enabled`），缺字段时报清晰 LLM-friendly errorMessage
  （不堆栈、不含 GBK 乱码）。
- **FR-002**：系统 MUST 在 `oryxos serve` 启动时扫描 `.oryxos/profiles/*.yaml`
  （每文件 = 一个 Profile），把每条 schedule upsert 到 SQLite `scheduled_tasks` 表
  （按 `task_id` 主键），同时注册到内存调度器；`enabled=false` 的 schedule 写表**但不**
  注册到调度器。
- **FR-003**：系统 MUST 提供内存调度器（基于 JDK 21 `ScheduledExecutorService` 或
  `cron-utils` 库），按 cron + zone 计算 `next_run_at_utc`；到点触发走
  `AgentService.process(session, schedule.message)`，**不绕过** ReAct 链路。
- **FR-004**：每次触发 MUST 新建独立 Session（session_id 唯一），session.metadata 含
  `task_id=<id>` 和 `source="scheduler"`；`sessions` 表 day-one 写入（[CLAUDE.md §13](../../CLAUDE.md)）。
- **FR-005**：触发完成后，`task_executions` 表 MUST 新增 1 行（task_id / session_id /
  started_at_utc / duration_ms / success / error_message?）；同时
  `scheduled_tasks.last_run_at_utc` 更新，`next_run_at_utc` 推到下一个 cron tick。
- **FR-006**：调度器 MUST 对同 task 串行化执行——同一 task 在上一次执行未完成时，
  下个 cron tick 命中**跳过**（不重叠触发），日志打印 skip 原因。
- **FR-007**：执行失败的 task MUST 写 `task_executions(success=false)`，
  `error_message` 含异常 message 但**不**含 stack trace（007-sandbox-whitelist 契约对齐）；
  调度器**不熔断**——下个 tick 仍触发（除非 `enabled=false`）。
- **FR-008**：手动补跑（`oryxos chat <agent>` 或 `POST /api/v1/agents/{name}/invoke`）
  与自动触发走**同一条** `AgentService.process()` 实现路径；区别只在 session.metadata
  .source（"cli" / "web" / "scheduler"）。
- **FR-009**：调度器 MUST 按 Profile `zone` 字段（IANA 时区名，如
  `Asia/Shanghai` / `America/New_York`）解析 cron，**不依赖** JVM 默认时区；
  非法 zone → 该 schedule 拒绝注册（fail-closed）。
- **FR-010**：DST（夏令时）切换 MUST 由 cron 库按 zone 规则自动处理（不丢触发、
  不双触发），`task_executions.started_at_utc` 用 UTC 时间戳记录（绝对时间）。
- **FR-011**：非法 cron 表达式 MUST 在启动时拒绝注册该 schedule（fail-closed），
  启动日志含 `task_id=<x> cron parse failed: <reason>`；不静默跳过。
- **FR-012**：同一 Profile 内 `task_id` 重复 MUST 在启动时拒绝，提示
  `duplicate task_id=<x> in profile=<name>`；不静默覆盖。
- **FR-013**：Spring 上下文启动失败时 `AgentScheduler` MUST NOT 接管任何 cron
  触发（fail-closed）；CLI / Web 入口继续可用（解耦）。

### 关键实体 *(include if feature involves data)*

- **Schedule 定义**（Profile YAML `schedules[]`）：`id`（Profile 内唯一）、`cron`
  （cron 表达式）、`zone`（IANA 时区名，默认 `JVM`）、`message`（到点喂给 Agent 的
  用户消息）、`enabled`（bool，默认 true）。
- **`scheduled_tasks` 表行**（[CLAUDE.md §13](../../CLAUDE.md) day-one 表）：
  `task_id`（主键，跨重启稳定）、`profile_name`（外键）、`cron_expr`、
  `timezone`、`enabled`、`last_run_at_utc`、`next_run_at_utc`、`created_at` /
  `updated_at`。
- **`task_executions` 表行**（[CLAUDE.md §13](../../CLAUDE.md) day-one 表）：
  `execution_id`（主键，UUID）、`task_id`（外键）、`session_id`（外键，对应
  `sessions.session_id`）、`started_at_utc`（UTC 时间戳）、`duration_ms`
  （执行耗时）、`success`（bool）、`error_message?`（LLM-friendly，不含 stack trace）。
- **Session.metadata**（既有 `sessions` 表 JSON 字段扩展）：`task_id` /
  `source`（"cli" / "web" / "scheduler"）—— 本 spec 不修改 `sessions` 表 schema，
  只在 metadata JSON 中加 2 个 key。

---

## 成功标准 *(mandatory)*

### 可衡量结果

- **SC-001**：三个验收 Demo（[CLAUDE.md §11](../../CLAUDE.md) 每日天气 / 每日科技日报
  / 每日 GitHub 日报）全部"钟推"成功——即不通过 CLI / Web 触发，由 Scheduler 自主
  触发完成；每个 Demo 跑通时 `task_executions` 新增 ≥1 行 success=true。
- **SC-002**：Scheduler 端到端集成测试全过——注册（US-1 4 子场景）、触发
  （US-2 4 子场景）、并发去重（US-3 场景 1）、失败重试（US-3 场景 2）、
  手动补跑路径对齐（US-3 场景 3）、时区（US-4 场景 1-2） 共 ≥12 子场景。
- **SC-003**：100 条 schedule 注册（模拟企业规模化场景），启动时间增加
  ≤ 2 秒（PRD 基线）；并发去重逻辑零误触发（10 次 cron 触发 + 长任务阻塞 → 实际
  进入 `AgentService.process()` 仅 1 次）。
- **SC-004**：手动补跑（CLI / Web）与自动触发走同一条 `AgentService.process()`
  路径——通过反射或依赖注入图断言（不存在"scheduler-only 分支代码"）。
- **SC-005**：跨时区准确性——JVM 时区 = UTC 时，`zone: Asia/Shanghai, cron: "0 9 * * *"`
  实际触发时间 = 上海 09:00（**不**是 UTC 09:00）；DST 切换日不丢触发、不双触发。
- **SC-006**：失败审计完整性——异常 message 写入 `task_executions.error_message`，
  长度 ≤ 2 KB，**不**含 stack trace；连续失败 3 次后下个 tick 仍触发（不熔断）。
- **SC-007**：`mvn verify` 全 10 模块 SUCCESS（含本 spec 新增的 `AgentScheduler`、
  `task_executions` schema、集成测试）。
- **SC-008**：Scheduler 不动 ReAct / Tool / Provider / Memory——`git diff
  006-memory-layer..008-agent-scheduler` 在 `oryxos-core/src/main/java/io/oryxos/core/{ReActLoop,PromptBuilder,ToolExecutor}.java`
  三个核心类零改动；`AgentService.process()` 接口签名不变。

---

## 假设

- Agent 用户已熟悉 cron 表达式语法（5 段或 6 段标准 cron）；不提供图形化配置。
- `cron-utils` 或同类 cron 解析库已加入 `oryxos-core` `pom.xml` 依赖（核心阶段新加
  一个轻量依赖；不重写 cron 解析逻辑）。
- 时区用 IANA 名（`Asia/Shanghai`），不引入自定义时区格式（与 JDK `ZoneId.of()`
  兼容）。
- SQLite 5 张表（[CLAUDE.md §13](../../CLAUDE.md)）已在 006 阶段落地；
  `scheduled_tasks` 和 `task_executions` 2 表的 schema 是声明，本次补实现（DDL
  不变）；新增 `last_run_at_utc` / `next_run_at_utc` 列（与既有 `last_run_at` /
  `next_run_at` 并存或迁移，由 plan 阶段评估）。
- OryxOS 单实例运行（核心阶段不做集群协调）；多实例并发由"DB 行锁 + skip"
  实现，扩展阶段再上 ShedLock / Quartz JDBC。
- 调度器在 `oryxos serve` / `oryxos gateway` 启动时启用；`oryxos chat` /
  `oryxos init` / `oryxos status` 等短命令**不**启动调度器（与 Spring 上下文绑定）。
- Agent 在 ReAct 链路调用 `notify` 走 004 既有 `WebhookNotifyAdapter`；本 spec 不重写
  通知链路，只确保 Scheduler 触发的 session 也能用上。

## 不在范围内 *(mandatory 显式排除)*

- **Scheduler REST 增删查改**（`POST /api/v1/schedules` / `GET /api/v1/schedules`
  / `PUT /api/v1/schedules/{id}` / `DELETE /api/v1/schedules/{id}`）—— 核心阶段
  不做；schedule 配置仅通过 Profile YAML + 重启生效。([CLAUDE.md §15](../../CLAUDE.md)
  "核心阶段不做")
- **多实例集群协调**（Quartz JDBC / ShedLock / Hazelcast 锁）—— 单实例运行；
  多实例并发由 SQLite 行锁 + skip 兜底；扩展阶段再上分布式调度。
- **动态 Profile 热加载**（文件 watch + schedule 热重载）—— 重启生效；
  扩展阶段加 `spring-cloud-bus` 或自实现 file watch。
- **历史任务补跑**（重启后跳过的 tick 自动补跑）—— 重启后按当前时间重新算
  `next_run_at`，不补跑跳过的 tick；避免"Agent 周末没启动 → 周一早上堆 48 条"。
- **可视化调度仪表板**（web UI 查 next_run_at / 手动 disable）—— 扩展阶段。
- **时区别名 / 自定义时区偏移**（如 `UTC+8`）—— 仅支持 IANA 时区名；
  不引入 `+08:00` 这种偏移形式（与 JDK `ZoneId.of()` 一致）。
- **核心阶段之外的 7 项排除**（[CLAUDE.md §II 扩展阶段](../../CLAUDE.md)）——
  多租户 / SSO / 完整审计查询 / Tool Policy / Web 仪表板 / 集群高可用
  均不在本 spec 范围。

---

## 引用 *(mandatory)*

- [CLAUDE.md §5 9 个模块](../../CLAUDE.md) — `AgentScheduler` 归属 `oryxos-core`
  （本 spec 落地于此模块）
- [CLAUDE.md §9.3 AgentService 三种触发源统一入口](../../CLAUDE.md) — 钟推进
  `process(Session, String)`，与 CLI / Web 完全一致
- [CLAUDE.md §11 三个验收 Demo](../../CLAUDE.md) — 全部依赖本 spec 落地
- [CLAUDE.md §13 SQLite 5 张表](../../CLAUDE.md) — `scheduled_tasks` /
  `task_executions` 是 day-one 表
- [CLAUDE.md §15 REST API](../../CLAUDE.md) — Scheduler REST 不在核心阶段
- [CLAUDE.md §18 不要做的事](../../CLAUDE.md) — SecurityManager 红线 / API key
  占位 / `ddl-auto=update` 警告 / Provider 显式映射
- [.specify/memory/constitution.md](../../.specify/memory/constitution.md) — 7 原则
  与 Additional Constraints
- [007-sandbox-whitelist/contracts/sandbox-whitelist.md](../007-sandbox-whitelist/contracts/sandbox-whitelist.md)
  — `task_executions.error_message` 字节级对齐 sandbox audit errorMessage 契约
- [005-tool-system/contracts/tool-executor.md](../005-tool-system/contracts/tool-executor.md)
  — Tool 调用审计写入契约（`tool_invocations` 表 day-one）
- [006-memory-layer/contracts/memory-service.md](../006-memory-layer/contracts/memory-service.md)
  — `sessions` 表 schema + Session 生命周期（钟推触发的新 Session 走此契约）
