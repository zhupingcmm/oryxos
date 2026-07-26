package io.oryxos.core;

import java.util.Map;
import java.util.Objects;

/**
 * {@link ToolExecutor} 执行结果（不可变 record）。
 *
 * <p>详见 [contracts/ToolExecutor.md §1](../../../../../specs/002-react-loop/contracts/ToolExecutor.md)。
 *
 * @param success      true = Tool 成功；false = 失败（包括"tool not in profile"）
 * @param payload      成功时工具的实际输出（结构化）；失败时为 null 或诊断 info
 * @param errorMessage 失败时必非空；成功时通常为 null，但允许携带聚合信息
 *                     （如 Notify 广播 partial 路径，spec §4.5：success=true + 失败明细）
 */
public record ToolResult(
    boolean success,
    Map<String, Object> payload,
    String errorMessage
) {
    /** Compact constructor —— 强制 errorMessage 在失败时必填；成功时允许携带聚合信息。 */
    public ToolResult {
        // 失败路径：errorMessage 必非空
        if (!success && (errorMessage == null || errorMessage.isBlank())) {
            throw new IllegalArgumentException(
                "success=false must have non-blank errorMessage");
        }
        // 成功路径：errorMessage 可为 null（标准成功）或非 null（聚合语义，如
        // Notify 广播的 partial: ... 场景，spec §4.5 显式约定 success=true + 失败明细）
        // payload：成功时做不可变拷贝；失败时可为 null
        if (payload != null) {
            payload = Map.copyOf(payload);
        }
    }

    /** 成功结果 —— payload 走 {@code Map.copyOf} 不可变化。 */
    public static ToolResult ok(Map<String, Object> payload) {
        return new ToolResult(true,
            payload == null ? Map.of() : payload,
            null);
    }

    /** 失败结果 —— errorMessage 必非空。 */
    public static ToolResult error(String message) {
        return new ToolResult(false, null, Objects.requireNonNull(message, "message"));
    }
}
