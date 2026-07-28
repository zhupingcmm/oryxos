package io.oryxos.storage.scheduler;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 008-agent-scheduler 阶段 —— {@code scheduled_tasks} 表 JPA 实体。
 *
 * <p>对应 data-model.md 实体 2；DDL 已由 006 阶段声明（本类补实现，DDL 不动）。
 *
 * <h2>关键不变式</h2>
 * <ul>
 *   <li>{@link #taskId} 主键，跨 Profile 唯一（{@code <profile>:<id>}）</li>
 *   <li>{@link #nextRunAtUtc} MUST NOT 是过去时间（启动校验；过去 → 推到现在 + 1s 兜底）</li>
 * </ul>
 */
@Entity
@Table(name = "scheduled_tasks")
public class ScheduledTaskRecord {

    @Id
    @Column(name = "task_id", nullable = false, length = 200)
    private String taskId;

    @Column(name = "profile_name", nullable = false, length = 100)
    private String profileName;

    @Column(name = "cron_expr", nullable = false, length = 100)
    private String cronExpr;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @Column(name = "message", nullable = false, length = 4000)
    private String message;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "last_run_at_utc", length = 32)
    private String lastRunAtUtc;

    @Column(name = "next_run_at_utc", nullable = false, length = 32)
    private String nextRunAtUtc;

    @Column(name = "created_at", nullable = false, length = 32)
    private String createdAt;

    @Column(name = "updated_at", nullable = false, length = 32)
    private String updatedAt;

    public ScheduledTaskRecord() {
        // JPA required
    }

    /** 工厂：用于调度器启动期构造新行（taskId / 初始 nextRun 由调用方填）。 */
    public static ScheduledTaskRecord create(
        String taskId,
        String profileName,
        String cronExpr,
        String timezone,
        String message,
        boolean enabled,
        Instant nextRunAtUtc
    ) {
        ScheduledTaskRecord r = new ScheduledTaskRecord();
        r.taskId = taskId;
        r.profileName = profileName;
        r.cronExpr = cronExpr;
        r.timezone = timezone;
        r.message = message;
        r.enabled = enabled;
        r.nextRunAtUtc = nextRunAtUtc.toString();
        String now = Instant.now().toString();
        r.createdAt = now;
        r.updatedAt = now;
        return r;
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getProfileName() { return profileName; }
    public void setProfileName(String profileName) { this.profileName = profileName; }

    public String getCronExpr() { return cronExpr; }
    public void setCronExpr(String cronExpr) { this.cronExpr = cronExpr; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getLastRunAtUtc() { return lastRunAtUtc; }
    public void setLastRunAtUtc(String lastRunAtUtc) { this.lastRunAtUtc = lastRunAtUtc; }

    public String getNextRunAtUtc() { return nextRunAtUtc; }
    public void setNextRunAtUtc(String nextRunAtUtc) { this.nextRunAtUtc = nextRunAtUtc; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}