package io.oryxos.core.scheduler;

import java.time.Instant;

/**
 * 008-agent-scheduler 阶段 —— Cron 表达式解析 + 下次触发时间计算接口。
 *
 * <p>实现归 {@code CronEvaluatorImpl}（cron-utils v9.x + JDK 21 {@code ZoneId}），
 * 见 research.md R-001 / R-003。
 *
 * <h2>契约条款</h2>
 * <ul>
 *   <li>C-CE-1: 构造时必须能解析给定 cron；非法 cron 抛 {@link IllegalArgumentException}</li>
 *   <li>C-CE-2: 构造时必须能解析给定 zone；非法 zone 抛 {@link IllegalArgumentException}（FR-009）</li>
 *   <li>C-CE-3: {@link #nextRunAt(Instant)} 永不返回 {@code fromUtc} 或更早 —— 调度器不会"补跑"</li>
 *   <li>C-CE-4: DST 切换由 JDK {@code ZonedDateTime} + IANA tzdata 自动处理（R-003）</li>
 * </ul>
 *
 * @see Schedule
 */
public interface CronEvaluator {

    /**
     * 计算下次触发 UTC 时间戳。
     *
     * @param fromUtc 当前时间（UTC 瞬时）
     * @return 下次触发 UTC 瞬时；永远 &gt; {@code fromUtc}
     */
    Instant nextRunAt(Instant fromUtc);

    /**
     * 校验 cron + zone 合法性（实现已在构造时校验过；本方法用于启动期 fail-fast 显式调用）。
     *
     * @throws IllegalArgumentException cron 非法或 zone 非法
     */
    void validate();
}