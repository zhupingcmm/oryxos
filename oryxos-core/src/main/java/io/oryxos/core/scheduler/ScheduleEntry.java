package io.oryxos.core.scheduler;

import java.time.Instant;

/**
 * 008-agent-scheduler 阶段 —— {@link ScheduleStore} 的传输 DTO。
 *
 * <p>合并 Profile YAML 字段（{@code profileName / id / cron / zone / message / enabled}）
 * + 运行期字段（{@code nextRunAtUtc / lastRunAtUtc}），避免 core 模块依赖 storage 的 JPA entity。
 *
 * <h2>字段语义</h2>
 * <ul>
 *   <li>{@code profileName} / {@code id} 拼接得到 {@code task_id = "<profileName>:<id>"}</li>
 *   <li>{@code cron} / {@code zone} 由 Profile YAML 写死 + 启动期校验</li>
 *   <li>{@code message} 触发时喂给 AgentService.process()（同 CLI/Web 消息路径）</li>
 *   <li>{@code enabled} = false 时调度器跳过 tick 注册（但仍在 DB 留行供 list 查询）</li>
 *   <li>{@code nextRunAtUtc} / {@code lastRunAtUtc} 由调度器写入；upsert 时首写用 Instant.now()+1s 兜底</li>
 * </ul>
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

    /** 主键 {@code <profileName>:<id>}（跨 Profile 全局唯一）。 */
    public String taskId() {
        return profileName + ":" + id;
    }
}