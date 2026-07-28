package io.oryxos.core.scheduler;

import java.time.Instant;

/**
 * 008-agent-scheduler 阶段 —— {@code task_executions} 表的写入门面接口。
 *
 * <p>实现归 {@code TaskExecutionRecorderImpl}（JPA insert + UUID v7 + sanitize），
 * 见 research.md R-006 + contracts §6。
 *
 * <h2>契约条款</h2>
 * <ul>
 *   <li>C-TER-1: 每次 {@link #record} 必须返回唯一 {@code execution_id}（UUID v7）</li>
 *   <li>C-TER-2: {@code errorMessage} 不含 stack trace（与 007-sandbox-whitelist FR-007 字节级对齐）</li>
 *   <li>C-TER-3: {@code errorMessage} 长度 ≤ 2 KB（超长截断 + {@code ...<truncated>} 后缀）</li>
 *   <li>C-TER-4: {@code success=true} 时 {@code errorMessage} 必为 {@code null}（不为 {@code ""}）</li>
 * </ul>
 *
 * <p>Recorder 异常 MUST NOT 冒泡 —— 避免二次失败（plan.md 风险与缓解 #3）。
 */
public interface TaskExecutionRecorder {

    /**
     * 触发上下文 —— 用于写 {@code task_executions} 表的 task_id / session_id / trigger_source。
     */
    record ExecutionContext(
        String taskId,
        String sessionId,
        String triggerSource
    ) {
        public ExecutionContext {
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("taskId must not be blank");
            }
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("sessionId must not be blank");
            }
            if (triggerSource == null || triggerSource.isBlank()) {
                throw new IllegalArgumentException("triggerSource must not be blank");
            }
            if (!triggerSource.equals("scheduler")
                && !triggerSource.equals("cli")
                && !triggerSource.equals("web")) {
                throw new IllegalArgumentException(
                    "triggerSource must be one of {scheduler, cli, web}, got: " + triggerSource);
            }
        }
    }

    /**
     * 写一行 {@code task_executions}。
     *
     * @param ctx           触发上下文
     * @param startedAtUtc  UTC 起始时间
     * @param durationMs    耗时（毫秒，{@code >= 0}）
     * @param success       是否成功
     * @param errorMessage  异常 message（已 sanitize，不含 stack trace）；
     *                      {@code success=true} 时必为 {@code null}
     * @return execution_id（UUID v7）
     */
    String record(
        ExecutionContext ctx,
        Instant startedAtUtc,
        long durationMs,
        boolean success,
        String errorMessage
    );
}