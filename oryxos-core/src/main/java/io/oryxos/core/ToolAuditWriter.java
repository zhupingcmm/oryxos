package io.oryxos.core;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Tool 调用审计写入接口 —— {@link ToolExecutor} 实现每次 invoke 调用 MUST 通过本接口写一行审计记录。
 *
 * <p>本接口定义在 oryxos-core 中；JPA 落地实现在 oryxos-storage（{@code JpaToolAuditWriter}）。
 * 这样 DefaultToolExecutor 可以注入审计能力，同时保持模块依赖方向 core ← storage。
 *
 * <h2>契约条款（来自 [contracts/ToolExecutor.md §2](../../../../../specs/002-react-loop/contracts/ToolExecutor.md)）</h2>
 * <ul>
 *   <li>C-TE-2 每次调用写一行，无论成功失败</li>
 *   <li>C-TE-3 session_iteration 取自 {@code ProfileContext.current().currentIteration().get()}</li>
 *   <li>C-TE-4 started_at 为本地时间</li>
 *   <li>C-TE-9 写入不被 Spring 事务回滚（即便后续循环异常）</li>
 * </ul>
 */
@FunctionalInterface
public interface ToolAuditWriter {

    /**
     * 记录一次 Tool 调用审计行。
     *
     * @param data 审计字段（含 success / errorMessage / durationMs / sessionIteration 等）
     */
    void record(ToolAuditData data);

    /**
     * 审计写入的 POJO（不可变 record）。字段命名对齐 {@code tool_invocations} 表。
     */
    record ToolAuditData(
        UUID sessionId,
        String profileName,
        String toolName,
        Map<String, Object> arguments,
        boolean success,
        String errorMessage,
        long durationMs,
        Instant startedAt,
        int sessionIteration
    ) {
        public ToolAuditData {
            // 不可变；null 视为空
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    /** US-2 桩 —— 不写任何审计；US-4 接入 JpaToolAuditWriter。 */
    final class NoopToolAuditWriter implements ToolAuditWriter {
        @Override
        public void record(ToolAuditData data) {
            // no-op
        }
    }
}