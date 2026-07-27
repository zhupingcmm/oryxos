package io.oryxos.core.scheduler;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 008-agent-scheduler 阶段 —— {@code scheduled_tasks} 表的持久化门面接口（core 层抽象）。
 *
 * <p>实现归 {@code ScheduleStoreImpl}（JPA + SQLite WAL 模式，在 oryxos-storage 模块），
 * 见 research.md R-004。
 *
 * <p>本接口在 core 而非 storage 模块的原因：被 core 模块的 {@link AgentSchedulerImpl}
 * 直接依赖（接口使用方归使用方同模块，符合 CLAUDE.md §5「接口归使用方，impl 归实现方」原则）。
 *
 * <h2>契约条款</h2>
 * <ul>
 *   <li>C-SS-1: {@code task_id} 主键 upsert 语义（INSERT ... ON CONFLICT DO UPDATE）</li>
 *   <li>C-SS-2: {@link #findAllEnabled()} 仅返回 {@code enabled=true} 行</li>
 *   <li>C-SS-3: {@link #updateRunTimes} 原子更新 last_run + next_run</li>
 *   <li>C-SS-4: 所有方法线程安全（Spring bean 默认单例）</li>
 * </ul>
 */
public interface ScheduleStore {

    /**
     * 启动时批量 upsert：按 {@code task_id} 主键冲突 → 覆盖；非冲突 → 插入。
     *
     * @param schedules 待写入/更新的 schedule 列表（含 task_id / cron / zone / next_run_at）
     * @return 实际写入行数
     */
    int upsertAll(List<ScheduleEntry> schedules);

    /** 查询所有 {@code enabled=true} 的 schedule（调度器 tick 用）。 */
    List<ScheduleEntry> findAllEnabled();

    /** 按 {@code task_id} 查询（{@code <profile>:<id>} 拼接形式）。 */
    Optional<ScheduleEntry> findByTaskId(String taskId);

    /**
     * 触发完成后更新 last_run + next_run。
     *
     * @param taskId         主键
     * @param lastRunAtUtc   本次触发 UTC 时间戳
     * @param nextRunAtUtc   下次触发 UTC 时间戳（cron 计算结果）
     */
    void updateRunTimes(String taskId, Instant lastRunAtUtc, Instant nextRunAtUtc);

    /**
     * 删除（核心阶段不做 REST 删除；CLI 不提供 delete；该方法供扩展阶段使用）。
     */
    void deleteByTaskId(String taskId);
}