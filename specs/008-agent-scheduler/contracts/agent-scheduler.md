# 契约：AgentScheduler（接口先行）

**生成日期**：2026-07-27
**关联**：[spec.md §FR-003](../spec.md) / [research.md R-002](../research.md) / [data-model.md](../data-model.md)

---

## §1 概述

`AgentScheduler` 是 OryxOS 第三种触发源（钟推）的入口契约。本文件定义 5 个公共接口
面的字节级契约，**实施阶段**（tasks.md）落地实现 + 测试。

## §2 接口面清单

| 接口 | 模块 | 用途 |
|------|------|------|
| `AgentScheduler` | `oryxos-core` | 调度器启动/关闭/查询 |
| `ScheduleStore` | `oryxos-storage` | `scheduled_tasks` 表持久化 |
| `CronEvaluator` | `oryxos-core` | cron 解析 + 下次触发计算 |
| `TaskExecutionRecorder` | `oryxos-core` | `task_executions` 表写入 |
| `ScheduleListCli` | `oryxos-cli` | `oryxos schedule list` CLI 命令 |

> **接口先行原则**（[CLAUDE.md §9.4](../../CLAUDE.md)）：上述 5 个 public 接口**实施完成后字节级不变**，
> 后续 008 阶段重构 / 多实例扩展只换实现，不动接口签名。

---

## §3 `AgentScheduler` 接口

### §3.1 签名

```java
package io.oryxos.core.scheduler;

public interface AgentScheduler {
    /**
     * 启动调度器：从 Profile YAML 加载 schedules → upsert 到 scheduled_tasks →
     * 为每条 enabled=true 的 schedule 计算 next_run_at_utc 并注册到内存调度器。
     * 失败语义：非法 cron / 非法 zone / 重复 task_id → 启动拒绝 + 日志
     * （spec FR-011 / FR-009 / FR-012 fail-closed）
     */
    void bootstrap(List<Schedule> schedules);

    /** 关闭调度器：取消所有 runningFuture，等待当前 tick 完成（≤ 30s 超时） */
    void shutdown();

    /** 查询所有已注册 schedule（含 enabled=false），按 task_id 排序 */
    List<ScheduleView> listSchedules();

    /** 手动补跑：绕过 cron，立即触发一次；走 AgentService.process() 同源 */
    void triggerNow(String taskId);

    /** 调度器是否运行中（PostConstruct 后 true；PreDestroy 后 false） */
    boolean isRunning();
}
```

### §3.2 字节级不变断言

| # | 断言 | 测试方法 |
|---|------|---------|
| 1 | 接口是 `public interface` | 反射 |
| 2 | 5 个公开方法签名严格匹配（`bootstrap(List)` / `shutdown()` / `listSchedules()` / `triggerNow(String)` / `isRunning()`） | 反射 getMethods() |
| 3 | 返回类型：`bootstrap/void` / `shutdown/void` / `listSchedules/List<ScheduleView>` / `triggerNow/void` / `isRunning/boolean` | 反射 |
| 4 | 无新增 public 方法（仅 5 个） | 反射计数 == 5 |

---

## §4 `ScheduleStore` 接口

### §4.1 签名

```java
package io.oryxos.core.scheduler;

public interface ScheduleStore {
    /**
     * 启动时批量 upsert：按 task_id 主键冲突 → 覆盖；非冲突 → 插入。
     * 返回实际写入行数（含 update）。
     */
    int upsertAll(List<ScheduleEntry> schedules);

    /** 查询所有 enabled=true 的 schedule（调度器 tick 用） */
    List<ScheduleEntry> findAllEnabled();

    /** 按 task_id 查询 */
    Optional<ScheduleEntry> findByTaskId(String taskId);

    /** 触发完成后更新 last_run_at_utc + next_run_at_utc */
    void updateRunTimes(String taskId, Instant lastRunAtUtc, Instant nextRunAtUtc);

    /** 删除（核心阶段不做 REST 删除；CLI 不提供 delete；该方法供扩展阶段使用） */
    void deleteByTaskId(String taskId);
}

/**
 * 传输 DTO —— 合并 Profile YAML 字段（profileName / id / cron / zone / message / enabled）
 * + 运行期字段（nextRunAtUtc / lastRunAtUtc）。
 * 主键 taskId 派生：profileName + ":" + id。
 * 字段顺序与命名固定（实施后字节级不变，详见 §4.2）。
 */
public record ScheduleEntry(
    String profileName,
    String id,
    String cron,
    String zone,
    String message,
    boolean enabled,
    Instant nextRunAtUtc,
    Instant lastRunAtUtc
) {
    public String taskId() { return profileName + ":" + id; }
}
```

> **模块归属说明**（[CLAUDE.md §5 接口归使用方原则](../../CLAUDE.md)）：`ScheduleStore`
> 接口位于 `oryxos-core`（被 `AgentSchedulerImpl` 直接依赖），实现 `ScheduleStoreImpl`
> 在 `oryxos-storage`（与 `ScheduledTaskRecord` JPA 实体同模块）。`ScheduledTaskRecord`
> 是 JPA 内部 entity，**不对外暴露**——`ScheduleStore` 全部方法入参/返回均走
> `ScheduleEntry`（DTO），避免 core 模块依赖 storage 的 JPA 类型。

### §4.2 字节级不变断言

| # | 断言 |
|---|------|
| 1 | 接口位于 `io.oryxos.core.scheduler.ScheduleStore`（**不**在 storage） |
| 2 | 5 个公开方法签名严格匹配：`upsertAll(List)` / `findAllEnabled()` / `findByTaskId(String)` / `updateRunTimes(String, Instant, Instant)` / `deleteByTaskId(String)` |
| 3 | 返回类型：`upsertAll/int` / `findAllEnabled/List<ScheduleEntry>` / `findByTaskId/Optional<ScheduleEntry>` / `updateRunTimes/void` / `deleteByTaskId/void` |
| 4 | 入参/返回**不暴露** `ScheduledTaskRecord`（JPA entity）—— 全部走 `ScheduleEntry` DTO |
| 5 | `ScheduleEntry` 8 字段顺序固定：`profileName, id, cron, zone, message, enabled, nextRunAtUtc, lastRunAtUtc`；`taskId()` 派生方法存在 |
| 6 | 无新增 public 方法 |

---

## §5 `CronEvaluator` 接口

### §5.1 签名

```java
package io.oryxos.core.scheduler;

public interface CronEvaluator {
    /**
     * 计算下次触发 UTC 时间戳。
     * @param fromUtc 当前时间（UTC 瞬时）
     * @return 下次触发 UTC 瞬时；永远 > fromUtc（保证调度器不会"补跑"）
     */
    Instant nextRunAt(Instant fromUtc);

    /** 校验 cron 表达式合法性；非法抛 IllegalArgumentException（FR-011） */
    void validate();
}
```

### §5.2 字节级不变断言

| # | 断言 |
|---|------|
| 1 | 2 个公开方法签名严格匹配 |
| 2 | `nextRunAt(Instant)` 返回 `Instant`（非 Optional；非法 cron 已在构造器抛异常） |

---

## §6 `TaskExecutionRecorder` 接口

### §6.1 签名

```java
package io.oryxos.core.scheduler;

public interface TaskExecutionRecorder {
    /**
     * 写一行 task_executions。
     * @param ctx 触发上下文（task_id / session_id / trigger_source）
     * @param startedAtUtc UTC 起始时间
     * @param durationMs 耗时（毫秒）
     * @param success 是否成功
     * @param errorMessage 异常 message（已 sanitize，不含 stack trace）；success=true 时为 null
     * @return execution_id（UUID v7）
     */
    String record(ExecutionContext ctx, Instant startedAtUtc, long durationMs,
                  boolean success, String errorMessage);
}
```

### §6.2 字节级不变断言

| # | 断言 |
|---|------|
| 1 | 1 个公开方法签名严格匹配 |
| 2 | 返回 `String`（execution_id） |
| 3 | `errorMessage` 在 success=true 时必传 null（不为 ""）—— 007-sandbox-whitelist 契约字节级对齐 |

---

## §7 `ScheduleListCli` 命令契约

### §7.1 命令格式

```text
$ oryxos schedule list

TASK_ID                          PROFILE              CRON              ZONE              ENABLED   NEXT_RUN_AT_UTC
daily-weather                    weather-agent        0 8 * * *         Asia/Shanghai     true      2026-07-28T00:00:00Z
daily-tech-news                  tech-news-agent      0 9 * * *         Asia/Shanghai     true      2026-07-28T01:00:00Z
slow-task                        slow-agent           * * * * *         UTC               true      2026-07-27T22:00:00Z
disabled-task                    test-agent           0 0 * * *         UTC               false     -
```

### §7.2 行为契约

| 场景 | 期望 |
|------|------|
| 无 schedule 注册 | 输出 `No schedules registered.` + 退出码 0 |
| 1 条 schedule | 1 行 + 退出码 0 |
| 100 条 schedule | 100 行（无分页；输出 < 50 KB 一次性打印） |
| 数据库连接失败 | 输出 `error: cannot read schedules: <reason>` + 退出码 2 |
| 调度器未运行（CLI 不启动 Spring） | 输出 `note: scheduler is not running (this command reads DB only)` + 退出码 0 |

### §7.3 字节级不变断言

| # | 断言 |
|---|------|
| 1 | 命令名 `schedule list`（非 `schedule-list` / `schedules`） |
| 2 | 列头固定（`TASK_ID` / `PROFILE` / `CRON` / `ZONE` / `ENABLED` / `NEXT_RUN_AT_UTC`） |
| 3 | 时间格式 ISO-8601 UTC（`2026-07-28T00:00:00Z`） |

---

## §8 错误信息契约（LLM-friendly）

### §8.1 错误信息模板

| 错误码 | 模板 | spec FR |
|--------|------|---------|
| `E-SCH-001` | `task_id=<id> cron parse failed: <reason>` | FR-011 |
| `E-SCH-002` | `task_id=<id> invalid timezone: <zone>` | FR-009 |
| `E-SCH-003` | `duplicate task_id=<id> in profile=<name>` | FR-012 |
| `E-SCH-004` | `task_id=<id> skip reason="previous run still in progress"` | FR-006 |
| `E-SCH-005` | `task_id=<id> execution failed: <message>` | FR-007 |

### §8.2 字节级不变断言

| # | 断言 |
|---|------|
| 1 | 所有 5 个错误码前缀为 `task_id=<id>`（不含堆栈；不含 ANSI 色码） |
| 2 | 错误 message MUST NOT 含 `at io.oryxos.` / `at java.` / `\n\tat `（007-sandbox-whitelist FR-007 字节级对齐） |
| 3 | 错误 message ≤ 2 KB（spec SC-006） |

---

## §9 性能契约

| 接口调用 | 性能目标（PRD） | 性能目标（CI） |
|---------|---------------|---------------|
| `AgentScheduler.bootstrap(100 schedules)` | ≤ 2s P95 | ≤ 5s P95 |
| `CronEvaluator.nextRunAt(Instant)` | ≤ 50μs P95 | ≤ 200μs P95 |
| `ScheduleStore.upsertAll(100 records)` | ≤ 1s P95 | ≤ 3s P95 |
| `TaskExecutionRecorder.record(...)` | ≤ 100ms P95 | ≤ 500ms P95 |
| `AgentScheduler.triggerNow(taskId)` | ≤ 100ms P95（触发到 AgentService.process 入参） | ≤ 500ms P95 |

---

## §10 实施门槛（验收清单）

008 阶段实施完成 MUST 满足：

1. ✅ §3.2 / §4.2 / §5.2 / §6.2 / §7.3 / §8.2 字节级断言**全部**通过反射 + 输出断言测试
2. ✅ §9 性能契约 5 项**全部**通过 JMH 或 surefire 集成测试
3. ✅ spec FR-001..FR-013 共 13 条**全部**映射到至少 1 个测试用例
4. ✅ spec SC-001..SC-008 共 8 条**全部**通过验收
5. ✅ `mvn verify` 全 10 模块 SUCCESS（含 008 新增的 `AgentScheduler` + 集成测试 + JPA migration）

---

## §11 不在本契约范围

- 集群调度（ShedLock / Quartz JDBC）—— 扩展阶段
- REST 增删查改（`POST /api/v1/schedules`）—— 核心阶段不做
- 动态热加载（`AGENT.md` 文件 watch）—— 核心阶段不做
- 历史 tick 补跑 —— 核心阶段不做

---

## §12 引用

- [spec.md](../spec.md) — FR-001..FR-013 + SC-001..SC-008
- [research.md](../research.md) — R-001 cron-utils / R-002 单线程调度 / R-004 并发去重 / R-005 性能基线 / R-006 审计契约 / R-007 路径对齐
- [data-model.md](../data-model.md) — 实体 1-4
- [CLAUDE.md §9.4](../../CLAUDE.md) — 接口先行原则
- [CLAUDE.md §9.3](../../CLAUDE.md) — 三种触发源统一入口契约
- [007-sandbox-whitelist/contracts/sandbox-whitelist.md](../007-sandbox-whitelist/contracts/sandbox-whitelist.md) — errorMessage 不含 stack trace 字节级对齐