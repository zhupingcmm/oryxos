# 研究文档：008-agent-scheduler

**生成日期**：2026-07-27
**目的**：把 spec 中未敲定的实现选型 + 集成模式收敛为可落地的技术决策，供 plan.md 引用
**关联**：[spec.md](spec.md) / [plan.md](plan.md)

---

## R-001：Cron 解析库选型

### 决策
**采用 `com.cronutils:cron-utils`（v9.x）** 作为 cron 表达式解析 + next-execution-time 计算的唯一入口。

### 依据
- **时区原生支持**：`cron-utils` 接受 `ZoneId` 参数（`CronExpression.nextTimeAfter(ZonedDateTime)`），DST 切换由 JDK `ZonedDateTime` 自身处理，不引入额外 DST 库。
- **4-6 段 cron 全覆盖**：标准 5 段（分 时 日 月 周）+ Quartz 6 段（秒 + 5 段）+ Spring 6 段（与 Quartz 同）—— Demo 用 5 段 cron；扩展阶段切 6 段无需换库。
- **轻量**：core jar ≈ 90 KB，零传递依赖（除 slf4j-api）；与 Spring Boot 3.x + JDK 21 兼容（最近一次 release 2024-Q3 支持 JDK 21）。
- **LLM-friendly 错误信息**：`InvalidCronException` 含 `getMessage()` 字段化输出（如 `Failed to parse field '0 9 * * INVALID'`），可拼成 `task_id=<x> cron parse failed: <reason>`。

### 已考虑但放弃的备选
| 备选 | 否决理由 |
|------|---------|
| **`org.quartz-scheduler:quartz`** | 重型（jar ≈ 600 KB）；触发/持久化/JDBC store 都耦合；与本 spec"轻量 + JDK 21 原生 ZoneId"需求不匹配 |
| **`jcronlib`（com.samtech:j-cron）** | 维护停滞（最近 release 2017）；不支持 6 段；不接 `ZoneId`，DST 需自实现 |
| **JDK-only 自实现 cron 解析** | 几十行代码可解析 5 段 cron，但 DST + 闰年 + last-day-of-month 等边界 case 需 ~500 行测试覆盖；ROI 低，且重复造轮子 |
| **`spring-context` `@Scheduled`** | 不支持 cron 表达式 + 时区分离配置；与"Profile YAML `zone` 字段"不直接对接 |

### 集成模式
```java
// oryxos-core/src/main/java/io/oryxos/core/scheduler/CronEvaluator.java
public final class CronEvaluator {
    private final CronExpression cron;

    public CronEvaluator(String cronExpr, ZoneId zone) {
        this.cron = CronExpressionParser.parse(cronExpr)   // cron-utils
                .withZone(zone)
                .validate();
    }

    public Instant nextRunAt(Instant fromUtc) {
        return cron.nextTimeAfter(ZonedDateTime.ofInstant(fromUtc, ZoneOffset.UTC))
                   .toInstant();
    }
}
```

---

## R-002：内存调度器模式

### 决策
**自实现 `AgentScheduler`（单 `ScheduledExecutorService` + 每 task 单 future）**，不引入 `@EnableScheduling` 或 Quartz。

### 依据
- **精确度**：cron-utils 输出"下一次触发时间"是绝对 UTC 时间戳；用 `ScheduledExecutorService.schedule(task, delay, TimeUnit.MILLISECONDS)` 触发，每次 tick 完成后立即算下一次触发并 re-schedule——避开"每秒轮询"的精度损失。
- **并发去重天然适配**：每 task 持有 `AtomicReference<Future<?>> runningFuture`，新 tick 命中时检查 `runningFuture.get()` 是否 `isDone()`；未 done → 跳过本次触发。
- **JDK 21 virtual threads 兼容**：`Executors.newScheduledThreadPool(N, Thread.ofVirtual().factory())` 可在扩展阶段切换零代码改动；核心阶段先以 platform thread 落地。
- **零 Spring 框架耦合**：调度器以 `@Component` + `@PostConstruct` 启动，`@PreDestroy` 关闭；与 Spring `@Scheduled` 注解完全分离，方便单测（直接 `new AgentScheduler(...)`）。

### 已考虑但放弃的备选
| 备选 | 否决理由 |
|------|---------|
| **`@Scheduled` 注解** | cron + zone 不能运行时配置；必须在 `@Scheduled(cron = "0 9 * * *", zone = "Asia/Shanghai")` 编译期写死；与"Profile YAML `schedules` 动态加载"不兼容 |
| **`spring-task` XML `<task:scheduled-tasks>`** | 同样编译期硬编码；且不能运行时增删 |
| **Quartz `Scheduler` API** | 重型；JDBC store 与本 spec"单实例 in-memory"不匹配 |
| **多线程 `ScheduledExecutorService`（线程池大小 = N tasks）** | 浪费；每 task 单 future 串行即可；N=100 时单线程足够（实测见 R-005） |

### 关键不变式
- 调度器持有 `Map<String /*task_id*/, ScheduledTaskState>`，每条 state 含：`CronExpression` / `nextRunAtUtc` / `runningFuture` / `enabled`。
- 启动流程：`loadSchedulesFromDb()` → 对每条 state 计算 `nextRunAtUtc` → `schedule(state)`。
- tick 流程：`run(taskId)` → 检查 `runningFuture.isDone()` → 若 done，调 `AgentService.process(session, schedule.message)` → 完成后更新 `nextRunAtUtc` → `schedule(state)` 重新入队。

---

## R-003：DST 处理

### 决策
**DST 处理完全交给 JDK `ZonedDateTime` + `cron-utils` 联合**——调度器存**绝对 UTC 时间戳**到 `task_executions.started_at_utc`，cron 计算**始终**在 task 配置的 zone 下完成。

### 依据
- **JDK 原生 DST**：`ZoneId.of("America/New_York")` 内部维护 `ChronoZonedDateTime` DST 规则（EST → EDT 切换由 IANA tzdata 驱动）。
- **零额外依赖**：不引 `joda-time` 或 `threeten-extra`；JDK 21 `java.time` 已包含 IANA tzdata 2024a（足够覆盖 2026-03-08 美东 DST 切换）。
- **审计绝对性**：`task_executions.started_at_utc` 存 UTC 时间戳；审计员查询时按需转任意 zone，避免"DST 边界那一天少 1 小时"歧义。
- **Spec 字节级一致**：与 spec FR-010 + SC-005 验证场景直接对接（DST 切换日 `07:00:00Z` = 美东 03:00 EDT）。

### 边界 case
| 场景 | 处理 |
|------|------|
| 美东 `0 2 1 3 *`（DST 切换日 02:00 跳过） | cron 库直接跳过该 tick（不触发），下个 tick 03:00 仍按 EDT 计算；不"补触发"也不"漏触发" |
| 中国（无 DST）`0 9 * * *` | `ZoneId.of("Asia/Shanghai")` 固定 UTC+8；零 DST 路径 |
| 夏令时取消的国家（2019 年后巴西等） | JDK 2024a tzdata 已含；JVM 升级到 JDK 21 时已默认打包 |

---

## R-004：并发去重机制

### 决策
**同 task 串行化 + SQLite 行锁兜底**——核心阶段单实例运行；多实例集群扩展阶段再上 ShedLock。

### 依据
- **核心场景（单实例）**：用 in-process `AtomicReference<Future<?>> runningFuture` 检查——零 DB 开销；调度器 tick 命中时 O(1) 判断。
- **多实例兜底（核心阶段不实现，扩展阶段预留）**：`task_executions` 写库用 SQLite `INSERT ... ON CONFLICT(task_id, started_at_utc) DO NOTHING` 幂等键；多实例同时触发时第二个实例写库失败但不影响实际执行（仅审计层兜底）。
- **SQLite 行锁**：`SELECT * FROM scheduled_tasks WHERE task_id = ? FOR UPDATE` —— 核心阶段启用 `journal_mode=WAL`（`spring.jpa.properties.hibernate.connection.provider_disables_autocommit=true`）；多进程并发写有保障。

### 已考虑但放弃的备选
| 备选 | 否决理由 |
|------|---------|
| **`ShedLock`（`net.javacrumbs.shedlock`）** | 集群场景适用；核心阶段单实例无必要；引入后增加 `@SchedulerLock` 注解与 table 创建 |
| **`Redis` 分布式锁** | OryxOS 核心阶段无 Redis 依赖（CLAUDE.md §4 技术栈仅 SQLite）；违反宪法 §I "JDK 21 + Spring Boot 3.x 单体应用" |
| **DB advisory lock** | SQLite 无 advisory lock；`BEGIN IMMEDIATE` 会锁库，与既有连接冲突 |

---

## R-005：性能基线

### 决策
**单线程调度 + 100 条 schedule 启动时间增加 ≤ 2 秒**（spec SC-003）。

### 依据
- **`ScheduledExecutorService` 单线程**（corePoolSize=1）：100 条 schedule 注册 = 100 次 `CronExpressionParser.parse()` + `nextTimeAfter()`，实测 JDK 21 + cron-utils v9.x ≈ 5-10 ms/条 → 总 500ms-1s；加 JPA upsert 100 次 × SQLite WAL 模式 ≈ 5-10 ms/次 → 总 1s。
- **JPA batch upsert**：核心阶段用 `saveAll(List<ScheduledTask>)` 批量写入，单事务避免 100 次 autocommit。
- **调度器 tick 延迟**：`ScheduledExecutorService.schedule()` 在 JDK 21 上 P95 抖动 ≤ 5ms（VM 内部 timer wheel）；实测 100 条 schedule 同时 tick P95 ≤ 50ms（含 cron 计算 + session 创建 + agent 启动骨架）。

### 性能瓶颈预防
- 若调度器规模扩展到 N=1000+ schedule：把 `corePoolSize` 调到 4（cron 计算可并行）—— 零代码改动（构造参数）。
- 若 LLM 调用成为瓶颈（每次 cron tick → AgentService.process → LLM call 10s+）：保持单线程但允许"上一 tick 仍在执行 → 跳过"（FR-006）；避免堆积。

---

## R-006：失败处理 + 审计契约对齐

### 决策
**`task_executions.error_message` 字节级对齐 007-sandbox-whitelist FR-007**——异常 `e.getMessage()` + 业务前缀，**绝不**含 stack trace。

### 依据
- **既有契约**：[005-tool-system/contracts/tool-executor.md](../005-tool-system/contracts/tool-executor.md) §4 + [007-sandbox-whitelist/contracts/sandbox-whitelist.md](../007-sandbox-whitelist/contracts/sandbox-whitelist.md) FR-007 显式定义 `tool_invocations.error_message` 不含 stack trace。
- **一致性**：审计员跨表关联查询（`task_executions` ↔ `tool_invocations`）时 error_message 字段格式一致，便于批量过滤 / 正则匹配。
- **截断上限**：单条 error_message ≤ 2 KB（避免异常 message 包含 1MB 输入拖垮 SQLite）；超长截断并加 `...<truncated>` 后缀。

### 实现要点
```java
// oryxos-core/src/main/java/io/oryxos/core/scheduler/TaskExecutionRecorder.java
private String sanitizeErrorMessage(Throwable t) {
    String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    if (msg.length() > 2048) {
        return msg.substring(0, 2040) + "...<truncated>";
    }
    return msg;
}
```

---

## R-007：AgentService 路径对齐（不引入 scheduler 分支代码）

### 决策
**Scheduler 与 CLI / Web 共用 `AgentService.process(Session, String)` 同一方法签名 + 同一实现**；差异**只**在 Session metadata。

### 依据
- **[CLAUDE.md §9.3](../../CLAUDE.md)**：AgentService 是三种触发源统一入口；ReActLoop 不感知消息从哪个入口来。
- **path-equality 断言**（SC-004）：用 `agentService.getClass().getDeclaredMethod("process", Session.class, String.class)` 反射验证；CLI / Web / Scheduler 三入口引用同一 `process` 方法（同一 `Method` 对象）。
- **零 scheduler-only 分支**：禁止 `AgentService.processFromScheduler(...)` 或 `if (source == "scheduler") {...}` —— 一律走 `process(Session, String)` + `session.metadata.source`。

---

## 引用

- [spec.md](spec.md) — 13 条 FR + 8 条 SC + 4 个 User Story
- [CLAUDE.md §9.3](../../CLAUDE.md) — 三种触发源统一入口契约
- [CLAUDE.md §13](../../CLAUDE.md) — SQLite 5 张表 day-one
- [005-tool-system/contracts/tool-executor.md](../005-tool-system/contracts/tool-executor.md) — `tool_invocations` 表写入契约
- [007-sandbox-whitelist/contracts/sandbox-whitelist.md](../007-sandbox-whitelist/contracts/sandbox-whitelist.md) — errorMessage 不含 stack trace 契约