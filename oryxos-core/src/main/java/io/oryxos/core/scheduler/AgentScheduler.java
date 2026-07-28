package io.oryxos.core.scheduler;

import java.util.List;

/**
 * 008-agent-scheduler 阶段 —— 第三种触发源（钟推）的统一入口接口。
 *
 * <p>与 CLI / Web 共用 {@code AgentService.process(Session, String)} 同一方法
 * （[CLAUDE.md §9.3](../../../../../../CLAUDE.md)）；本接口只暴露调度器生命
 * 周期 + 查询 + 手动补跑。
 *
 * <h2>契约条款</h2>
 * <ul>
 *   <li>C-AS-1: {@link #bootstrap(List)} 失败语义 = 非法 cron / 非法 zone / 重复 task_id → 启动拒绝 + 日志
 *       （spec FR-011 / FR-009 / FR-012 fail-closed）</li>
 *   <li>C-AS-2: {@link #shutdown()} 取消所有 runningFuture；等待当前 tick 完成（≤ 30s 超时）</li>
 *   <li>C-AS-3: {@link #listSchedules()} 按 task_id 升序，含 enabled=false</li>
 *   <li>C-AS-4: {@link #triggerNow(String)} 绕过 cron，立即触发一次；走同源 {@code run(taskId)}</li>
 *   <li>C-AS-5: {@link #isRunning()} {@link #bootstrap} 后 true；{@link #shutdown} 后 false</li>
 * </ul>
 *
 * <p>实施完成后 MUST 字节级不变（contracts §3.2）；008 阶段重构 / 多实例扩展只换实现，不动接口签名。
 *
 * @see Schedule
 * @see CronEvaluator
 * @see TaskExecutionRecorder
 */
public interface AgentScheduler {

    /**
     * 启动调度器：从 Profile YAML 加载 schedules → upsert 到 {@code scheduled_tasks} →
     * 为每条 enabled=true 的 schedule 计算 next_run_at_utc 并注册到内存调度器。
     *
     * <p>失败语义（fail-closed）：
     * <ul>
     *   <li>非法 cron → 抛 {@link IllegalArgumentException}（FR-011）；该 schedule 拒绝注册</li>
     *   <li>非法 zone → 抛 {@link IllegalArgumentException}（FR-009）；该 schedule 拒绝注册</li>
     *   <li>重复 task_id → 抛 {@link IllegalStateException}（FR-012）；不静默后写覆盖前写</li>
     * </ul>
     *
     * @param schedules 待注册的 schedule 列表（已从 Profile YAML 解析）
     * @throws IllegalArgumentException 任一 schedule 非法
     * @throws IllegalStateException    内部状态非法（如重复 task_id）
     */
    void bootstrap(List<Schedule> schedules);

    /**
     * 关闭调度器：取消所有 runningFuture，等待当前 tick 完成（≤ 30s 超时）。
     */
    void shutdown();

    /**
     * 查询所有已注册 schedule（含 enabled=false），按 task_id 升序。
     */
    List<ScheduleView> listSchedules();

    /**
     * 手动补跑：绕过 cron，立即触发一次；走 {@code AgentService.process()} 同源。
     *
     * @param taskId 目标 task_id（{@code <profile>:<id>}）
     */
    void triggerNow(String taskId);

    /**
     * 调度器是否运行中（{@link #bootstrap} 后 true；{@link #shutdown} 后 false）。
     */
    boolean isRunning();

    /**
     * Schedule 视图 —— {@link #listSchedules} 的返回类型，避免内部状态泄漏。
     */
    record ScheduleView(
        String taskId,
        String profileName,
        String cron,
        String zone,
        String message,
        boolean enabled,
        java.time.Instant nextRunAtUtc,
        java.time.Instant lastRunAtUtc
    ) {}
}