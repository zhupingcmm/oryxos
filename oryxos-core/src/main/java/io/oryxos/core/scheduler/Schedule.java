package io.oryxos.core.scheduler;

import java.util.Objects;

/**
 * 008-agent-scheduler 阶段启动期临时对象 —— Profile YAML
 * {@code schedules[]} 数组元素的不可变 record 形态。
 *
 * <p>本 record 是调度器的"调度定义"输入；{@link #profileName()} + {@link #id()}
 * 拼成 {@code task_id} 跨 Profile 唯一（见 data-model.md 实体 1）。
 *
 * <h2>字段约束</h2>
 * <ul>
 *   <li>{@code id} Profile 内唯一；非法值（空串 / 含 '/' / 含 SQL 关键字）→ 拒绝</li>
 *   <li>{@code cron} 5 段标准 cron；解析失败 → {@link IllegalArgumentException}</li>
 *   <li>{@code zone} IANA 时区名（如 {@code Asia/Shanghai}）；{@code null} 或空 → 视为 JVM 默认</li>
 *   <li>{@code message} 非空</li>
 *   <li>{@code enabled} {@code null} 视为 {@code true}</li>
 * </ul>
 *
 * @param profileName 来源 Profile 名（用于生成 {@code task_id}）
 * @param id          Profile 内 schedule id
 * @param cron        cron 表达式（5 段）
 * @param zone        IANA 时区名（可为 {@code null} / 空 → 用 JVM 默认）
 * @param message     触发消息原文
 * @param enabled     是否启用（{@code null} → {@code true}）
 */
public record Schedule(
    String profileName,
    String id,
    String cron,
    String zone,
    String message,
    Boolean enabled
) {

    public Schedule {
        Objects.requireNonNull(profileName, "profileName");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cron, "cron");
        Objects.requireNonNull(message, "message");
        if (id.isBlank()) {
            throw new IllegalArgumentException("schedule id must not be blank");
        }
        if (cron.isBlank()) {
            throw new IllegalArgumentException("schedule cron must not be blank");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("schedule message must not be blank");
        }
        if (id.contains("/")) {
            throw new IllegalArgumentException(
                "schedule id must not contain '/' (would conflict with task_id path): " + id);
        }
        // enabled null → true（per spec FR-002 默认值）
        enabled = enabled == null ? Boolean.TRUE : enabled;
    }

    /** 跨 Profile 唯一 task_id —— {@code <profile_name>:<id>} 拼接避免冲突。 */
    public String taskId() {
        return profileName + ":" + id;
    }

    /** profileName 在 task_id 之后的部分（裸 id，用于按 Profile 过滤）。 */
    public String scheduleId() {
        return id;
    }
}