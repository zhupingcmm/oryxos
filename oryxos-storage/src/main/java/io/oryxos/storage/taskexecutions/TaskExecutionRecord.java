package io.oryxos.storage.taskexecutions;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 008-agent-scheduler 阶段 —— {@code task_executions} 表 JPA 实体（审计表）。
 *
 * <p>对应 data-model.md 实体 3 + [CLAUDE.md §13](../../../../../../CLAUDE.md) day-one 表。
 *
 * <h2>关键不变式</h2>
 * <ul>
 *   <li>{@link #errorMessage} MUST NOT 包含 {@code "at io.oryxos."} / {@code "at java."} /
 *       {@code "\n\tat "} 等 stack trace 模式（007-sandbox-whitelist 契约字节级对齐）</li>
 *   <li>写库时机：{@code AgentService.process()} 完成后无论成功失败都写；
 *       执行未启动不写</li>
 * </ul>
 */
@Entity
@Table(name = "task_executions")
public class TaskExecutionRecord {

    @Id
    @Column(name = "execution_id", nullable = false, length = 64)
    private String executionId;

    @Column(name = "task_id", nullable = false, length = 200)
    private String taskId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "started_at_utc", nullable = false, length = 32)
    private String startedAtUtc;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "error_message", length = 2048)
    private String errorMessage;

    @Column(name = "trigger_source", nullable = false, length = 16)
    private String triggerSource;

    public TaskExecutionRecord() {
        // JPA required
    }

    /** 工厂：构造新执行记录；{@code success=true} 时 {@code errorMessage} 必为 null（C-TER-4）。 */
    public static TaskExecutionRecord create(
        String executionId,
        String taskId,
        String sessionId,
        String startedAtUtc,
        long durationMs,
        boolean success,
        String errorMessage,
        String triggerSource
    ) {
        if (success && errorMessage != null) {
            throw new IllegalArgumentException(
                "success=true MUST have errorMessage=null, got: " + errorMessage);
        }
        TaskExecutionRecord r = new TaskExecutionRecord();
        r.executionId = executionId;
        r.taskId = taskId;
        r.sessionId = sessionId;
        r.startedAtUtc = startedAtUtc;
        r.durationMs = durationMs;
        r.success = success;
        r.errorMessage = errorMessage;
        r.triggerSource = triggerSource;
        return r;
    }

    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getStartedAtUtc() { return startedAtUtc; }
    public void setStartedAtUtc(String startedAtUtc) { this.startedAtUtc = startedAtUtc; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }
}