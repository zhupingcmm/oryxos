# 数据模型：008-agent-scheduler

**生成日期**：2026-07-27
**关联**：[spec.md](spec.md) / [research.md](research.md) / [plan.md](plan.md)

---

## 概述

008-agent-scheduler 在 OryxOS 已有的 SQLite 5 张表（[CLAUDE.md §13](../../CLAUDE.md)）基础上
**新增 2 张表**（`scheduled_tasks` / `task_executions` 已声明但未落地实现）+ **扩展 1 个 JSON 字段**
（`sessions.metadata`）。DDL 已由 006 阶段 H2 / SQLite 测试创建；本 spec 不重新设计 schema，
仅固化字段语义 + 状态转移 + 写入契约。

---

## 实体 1：`Schedule`（Profile YAML 定义 — 非持久化）

**来源**：Profile YAML `schedules[]` 字段（[CLAUDE.md §16](../../CLAUDE.md) Profile 字段规范）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | String | ✅ | Profile 内唯一；跨 Profile 可重复（不同 Agent）；非法值（空串 / 含 `/` / 含 SQL 关键字）→ 拒绝 |
| `cron` | String | ✅ | 5 段标准 cron 表达式；解析失败 → 拒绝该 schedule（FR-011 fail-closed） |
| `zone` | String | ❌（默认 JVM） | IANA 时区名（如 `Asia/Shanghai`）；非法 → 拒绝该 schedule（FR-009） |
| `message` | String | ✅ | 到点喂给 Agent 的用户消息；非空（FR-001） |
| `enabled` | Boolean | ❌（默认 true） | false → 写表**但**不调度（US-1 验收场景 3） |

**生命周期**：
1. Agent operator 写 `AGENT.md` 包含 `schedules:` 字段
2. `oryxos serve` 启动 → `ContextLoader` 解析 YAML → 列表传给 `AgentScheduler.bootstrap(...)`
3. `AgentScheduler` 调用 `ScheduleStore.upsert(Schedule)` 写 `scheduled_tasks`
4. `enabled=true` → 注册到 `ScheduledExecutorService`；`enabled=false` → 仅写表

**校验规则**：
- `id` 唯一性（Profile 内）：FR-012 重复 → 启动拒绝 + 日志
- `cron` 合法：FR-011 解析失败 → 启动拒绝
- `zone` 合法：FR-009 解析失败 → 启动拒绝
- `message` 非空：FR-001 → 启动拒绝

---

## 实体 2：`scheduled_tasks` 表行（持久化 — SQLite）

**DDL 来源**：[006-memory-layer/data-model.md §sessions 表](../006-memory-layer/data-model.md) 已声明
**本 spec 落地字段**：

| 列 | 类型 | 约束 | 说明 |
|----|------|------|------|
| `task_id` | TEXT | PRIMARY KEY | 跨 Profile 唯一标识（`<profile_name>:<schedule.id>` 拼接避免冲突） |
| `profile_name` | TEXT | NOT NULL, FK | 关联 `agents` 目录（无独立表，由 `ContextLoader` 派生） |
| `cron_expr` | TEXT | NOT NULL | 5 段 cron 字符串 |
| `timezone` | TEXT | NOT NULL | IANA 时区名（`ZoneId.of()` 合法值） |
| `message` | TEXT | NOT NULL | 触发消息原文 |
| `enabled` | BOOLEAN | NOT NULL DEFAULT 1 | false → 调度器跳过 |
| `last_run_at_utc` | TEXT | NULL | ISO-8601 UTC 时间戳；从未触发则 NULL |
| `next_run_at_utc` | TEXT | NOT NULL | ISO-8601 UTC 时间戳；启动时 + 每次触发完成后更新 |
| `created_at` | TEXT | NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')) | 创建时间 |
| `updated_at` | TEXT | NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')) | 最后修改时间 |

**关键不变式**：
- `next_run_at_utc` MUST NOT 是过去时间（启动校验；过去 → 推到现在 + 1s 兜底）
- `task_id` 在启动期 upsert 时**幂等**（`INSERT ... ON CONFLICT(task_id) DO UPDATE SET ...`）

**与 spec FR-005 对应**：触发完成后更新 `last_run_at_utc` = 当前时间，`next_run_at_utc` = 推到下一个 cron tick。

---

## 实体 3：`task_executions` 表行（持久化 — SQLite — 审计表）

**DDL 来源**：[CLAUDE.md §13](../../CLAUDE.md) "task_executions 每次执行历史" 已声明
**本 spec 落地字段**：

| 列 | 类型 | 约束 | 说明 |
|----|------|------|------|
| `execution_id` | TEXT | PRIMARY KEY | UUID v7（时间排序） |
| `task_id` | TEXT | NOT NULL, FK → `scheduled_tasks.task_id` | 触发的任务 |
| `session_id` | TEXT | NOT NULL, FK → `sessions.session_id` | 本次执行的 Session |
| `started_at_utc` | TEXT | NOT NULL | ISO-8601 UTC；绝对时间（不依赖 JVM 时区） |
| `duration_ms` | INTEGER | NOT NULL | 从 `AgentService.process()` 进入 → 返回的耗时 |
| `success` | BOOLEAN | NOT NULL | true = 正常完成；false = 抛异常 / Session 创建失败 |
| `error_message` | TEXT | NULL | 仅 success=false 时写入；LLM-friendly；**不**含 stack trace；≤ 2 KB（FR-007 / SC-006） |
| `trigger_source` | TEXT | NOT NULL | `"scheduler"` / `"cli"` / `"web"` —— 手动补跑场景下非 "scheduler" |

**关键不变式**：
- `session_id` MUST 在 `sessions` 表存在（审计关联 SC-005 验收场景 3）
- `error_message` MUST NOT 包含 `at io.oryxos.` / `at java.` / `\n\tat ` 等 stack trace 模式（007-sandbox-whitelist 契约字节级对齐）
- 写库时机：`AgentService.process()` 完成后**无论**成功失败都写；执行未启动不写（区别于"调度器跳过"的记录走 `scheduled_tasks.next_run_at_utc` 不更新）

**状态转移**：

```text
[调度器 tick 命中]
    │
    ├── runningFuture.isDone() == false → 跳过（不写 task_executions；日志打印 skip）
    │
    └── runningFuture.isDone() == true
         │
         ├── try { AgentService.process(session, message) }
         │       │
         │       ├── 正常返回 → success=true, error_message=null
         │       │
         │       └── 抛异常 → success=false, error_message=exception.getMessage()
         │
         └── finally → INSERT INTO task_executions ... ; UPDATE scheduled_tasks SET
                        last_run_at_utc = now, next_run_at_utc = nextCronTickUtc
```

---

## 实体 4：`sessions.metadata` JSON 扩展（非 schema 演进）

**已有**：`sessions` 表（[006-memory-layer/data-model.md](../006-memory-layer/data-model.md)）含
`metadata` JSON 字段（自由结构）。

**本 spec 新增 key**：

| key | 类型 | 必填？ | 触发场景 |
|-----|------|--------|---------|
| `task_id` | String | 仅 `source="scheduler"` 时必填 | 映射 `scheduled_tasks.task_id`（含 `<profileName>:<id>` 前缀） |
| `source` | String | 必填（三触发源统一） | 枚举：`"cli"` / `"web"` / `"scheduler"` |
| `started_at` | String（ISO-8601 UTC） | 可选 | 触发起始时间（UTC）；方便审计回溯 |

**命名一致性约束**（A12 修复）：
- 会话这一侧字段名**统一为** `source`（**不**是 `trigger_source`）；`task_executions.trigger_source` 是审计表专用术语
- 但**取值枚举三选一完全相同**：都是 `"cli"` / `"web"` / `"scheduler"`（字节级一致）—— 跨表 JOIN 时按 `trigger_source == metadata.source` 做关联即可

**JSON shape 字节级契约**（实施后不变）：
- `metadata.source` 必填，三选一；缺 / 越界 → 拒绝该 Session 持久化（fail-closed）
- `metadata.task_id` 仅在 `source="scheduler"` 时必填；其他 source → null
- 键名固定为 `task_id` / `source` / `started_at`（驼峰，不接受 `taskId` / `triggerSource` 之类变形）

**为什么用 metadata JSON 而非新增列**：
- 与 [CLAUDE.md §13](../../CLAUDE.md) "SQLite 的 `ALTER TABLE` 能力有限"一致；避免破坏 006 阶段已锁定的 DDL
- 与宪法 §VI "审计 day-one" 一致；`metadata` JSON 字段已预留
- 与 spec "不在范围内"节 "本 spec 不修改 `sessions` 表 schema" 一致

**示例**（钟推触发）：
```json
{
  "task_id": "daily-weather-agent:daily-weather",
  "source": "scheduler",
  "started_at": "2026-07-28T00:00:00Z"
}
```

---

## 实体关系图

```text
┌─────────────────────────┐
│  Profile YAML           │
│  schedules: [{id,       │
│   cron, zone, message,  │
│   enabled}]             │
└──────────┬──────────────┘
           │ ContextLoader 解析
           ▼
┌─────────────────────────┐
│  Schedule (in-memory)   │  启动期临时对象
└──────────┬──────────────┘
           │ ScheduleStore.upsert
           ▼
┌─────────────────────────┐
│  scheduled_tasks (DB)   │ ◄────────┐
│  task_id (PK)           │          │
│  cron_expr, timezone    │          │  触发完成更新
│  last_run_at_utc        │          │  last/next_run
│  next_run_at_utc        │          │
└──────────┬──────────────┘          │
           │  AgentService.process   │
           ▼                          │
┌─────────────────────────┐          │
│  sessions (DB, 既有)    │          │
│  session_id (PK)        │          │
│  metadata.task_id ──────┼──────────┘
│  metadata.source        │
└──────────┬──────────────┘
           │ session_id 关联
           ▼
┌─────────────────────────┐
│  task_executions (DB)   │
│  execution_id (PK)      │
│  task_id (FK) ──────────┼──────→ scheduled_tasks
│  session_id (FK) ───────┼──────→ sessions
│  started_at_utc         │
│  duration_ms, success   │
│  error_message?         │
└─────────────────────────┘

既有关联（不动 schema）：
sessions.session_id ←→ tool_invocations.session_id ←→ llm_calls.session_id
```

---

## 写入契约

| 时机 | 表 | 触发源 |
|------|------|--------|
| 启动时 | `scheduled_tasks` | `ScheduleStore.upsert(List<Schedule>)` |
| 启动时 | `scheduled_tasks` | `next_run_at_utc` 字段写入 |
| 触发完成 | `sessions` | `AgentService` 既有入口 |
| 触发完成 | `task_executions` | `TaskExecutionRecorder.record(...)` |
| 触发完成 | `scheduled_tasks` | `last_run_at_utc` + `next_run_at_utc` 更新 |
| 手动补跑 | `sessions` | `AgentService` 既有入口（CLI / Web） |
| 手动补跑 | `task_executions` | `TaskExecutionRecorder.record(..., trigger_source="cli"/"web")` |
| 失败 | `task_executions` | `success=false, error_message=sanitize(t)` |

---

## 不在本 spec 范围

- `scheduled_tasks` / `task_executions` DDL **已**在 006 阶段落地；本 spec 不重新设计 schema
- `sessions` 表 schema **不**演进；扩展仅在 `metadata` JSON 字段加 key
- 索引调优（`task_id` / `session_id` 已有 FK 隐式索引；时间范围查询索引放扩展阶段）
- 数据保留策略（`task_executions` 长期保留 vs 定期清理）—— 扩展阶段

---

## 引用

- [spec.md](spec.md) §关键实体 — `Schedule` / `scheduled_tasks` / `task_executions` / `Session.metadata` 4 实体描述
- [CLAUDE.md §13](../../CLAUDE.md) — SQLite 5 张表 day-one DDL
- [006-memory-layer/data-model.md](../006-memory-layer/data-model.md) — `sessions` 表 schema
- [research.md](research.md) — R-002 / R-004 / R-006 性能 + 并发 + 审计契约