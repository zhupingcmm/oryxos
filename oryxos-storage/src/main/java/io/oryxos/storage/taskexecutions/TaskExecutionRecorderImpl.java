package io.oryxos.storage.taskexecutions;

import io.oryxos.core.scheduler.TaskExecutionRecorder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 008-agent-scheduler 阶段 —— {@link TaskExecutionRecorder} 的 JPA 实现。
 *
 * <p>UUID v7（时间有序）作 execution_id；errorMessage 走 sanitize（截断到 2 KB）。
 *
 * <h2>Sanitize 规则</h2>
 * <ul>
 *   <li>从异常 message 中剥掉所有 {@code "at <pkg>."} / {@code "at java."} / {@code "\n\tat "}
 *       等 stack trace 模式（与 007-sandbox-whitelist FR-007 字节级对齐）</li>
 *   <li>长度 &gt; 2048 → 截断 + {@code ...<truncated>} 后缀</li>
 *   <li>{@code success=true} → {@code null}（已在 {@link TaskExecutionRecord#create} 校验）</li>
 * </ul>
 *
 * <h2>异常处理</h2>
 * <p>{@link #record} 自身失败 MUST NOT 冒泡（避免二次失败 plan.md 风险与缓解 #3）——
 * 用 try/catch 兜底 + 日志；调用方拿到的 {@code execution_id} 可能为 {@code "<write-failed>"}。
 */
@Component
public class TaskExecutionRecorderImpl implements TaskExecutionRecorder {

    private static final int MAX_ERROR_LEN = 2048;
    private static final String TRUNC_SUFFIX = "...<truncated>";
    private static final String WRITE_FAILED_ID = "<write-failed>";

    private final TaskExecutionRepository repository;

    public TaskExecutionRecorderImpl(TaskExecutionRepository repository) {
        this.repository = repository;
    }

    @Override
    public String record(
        ExecutionContext ctx,
        Instant startedAtUtc,
        long durationMs,
        boolean success,
        String errorMessage
    ) {
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be >= 0, got " + durationMs);
        }
        String sanitized = sanitize(success, errorMessage);

        try {
            String executionId = generateExecutionId();
            TaskExecutionRecord record = TaskExecutionRecord.create(
                executionId,
                ctx.taskId(),
                ctx.sessionId(),
                startedAtUtc.toString(),
                durationMs,
                success,
                sanitized,
                ctx.triggerSource()
            );
            return doInsert(record);
        } catch (RuntimeException e) {
            // C-TER 写入失败 MUST NOT 冒泡 —— 二次失败（plan.md 风险与缓解 #3）
            // 用 JCL/Logback 直接打印（避免循环依赖）
            try {
                org.slf4j.LoggerFactory.getLogger(TaskExecutionRecorderImpl.class)
                    .error("TaskExecutionRecorderImpl.record failed: taskId={} sessionId={} cause={}",
                        ctx.taskId(), ctx.sessionId(), e.toString());
            } catch (RuntimeException ignored) {
                // log 也不可用 → 静默
            }
            return WRITE_FAILED_ID;
        }
    }

    @Transactional
    protected String doInsert(TaskExecutionRecord record) {
        repository.save(record);
        return record.getExecutionId();
    }

    /** 剥 stack trace + 截断（C-TER-2 / C-TER-3）。 */
    static String sanitize(boolean success, String errorMessage) {
        if (success) {
            return null;  // C-TER-4
        }
        if (errorMessage == null) {
            return null;
        }
        // 1. 剥 "\n\tat io.oryxos." / "\n\tat java." / "\nCaused by:" 等 stack trace 模式
        String cleaned = errorMessage;
        int firstStack = indexOfStackTraceStart(cleaned);
        if (firstStack >= 0) {
            cleaned = cleaned.substring(0, firstStack);
        }
        // 2. 截断到 2 KB（C-TER-3）
        if (cleaned.length() > MAX_ERROR_LEN) {
            cleaned = cleaned.substring(0, MAX_ERROR_LEN - TRUNC_SUFFIX.length()) + TRUNC_SUFFIX;
        }
        return cleaned;
    }

    private static int indexOfStackTraceStart(String s) {
        // 找 "\n\tat " / "\n\tat io.oryxos." / "\nCaused by:" 起始位置
        int min = -1;
        for (String marker : new String[]{"\n\tat ", "\nCaused by: "}) {
            int idx = s.indexOf(marker);
            if (idx >= 0 && (min < 0 || idx < min)) {
                min = idx;
            }
        }
        return min;
    }

    /**
     * UUID v7 生成 —— 用 java.util.UUID v7（Java 21 标准）时间有序，
     * 便于按 execution_id 时间排序。
     */
    private static String generateExecutionId() {
        UUID uuid = UUID.randomUUID();
        // JDK 21 标准库尚未内置 UUIDv7；用 7.x via manual byte shift：
        // 48-bit timestamp + 4-bit version + 12-bit random + 2-bit variant + 62-bit random
        long timestampMs = System.currentTimeMillis();
        long msb = (timestampMs & 0xFFFFFFFFFFFFL) << 16;  // 48-bit timestamp in ms
        msb |= 0x7000L;  // version 7
        msb |= (uuid.getMostSignificantBits() & 0x0FFF_FFFF_FFFF_FFFFL) & 0x0FFFL;
        return new UUID(msb, uuid.getLeastSignificantBits()).toString();
    }
}