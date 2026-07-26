package io.oryxos.tool.notify;

import java.util.Objects;

/**
 * 单条 Notify 调用的内部结果 record —— {@link WebhookNotifyAdapter#send} 的返回。
 *
 * <p>与 {@link io.oryxos.core.ToolResult} 区别：
 * <ul>
 *   <li>{@code ToolResult} 是 LLM 视角（成功/失败的语义）</li>
 *   <li>{@code NotifyResult} 是 HTTP 视角（含 status code、redacted URL、durationMs）</li>
 * </ul>
 *
 * <p>详见 <a href="../../../../../../../specs/004-notify-channel/data-model.md">specs/004-notify-channel/data-model.md §3.1</a>。
 *
 * @param channelName   通道名（对应 {@code NotifyChannelConfig.name()}）
 * @param success       HTTP 2xx → true；其他 → false
 * @param statusCode    HTTP 状态码（2xx/4xx/5xx）；网络错误（超时/ConnectException）→ null
 * @param errorMessage  失败原因（含 sandbox violation / HTTP 5xx body 前 256 字节等）；
 *                     成功时为 null
 * @param durationMs    本次发送耗时（毫秒）；用于审计与性能验证
 * @param redactedUrl   已脱敏的 URL（key=xxx 这类 query 参数被替换为 {@code key=REDACTED}）；
 *                     写入审计的 {@code arguments.url} 字段
 */
public record NotifyResult(
    String channelName,
    boolean success,
    Integer statusCode,
    String errorMessage,
    long durationMs,
    String redactedUrl
) {
    public NotifyResult {
        Objects.requireNonNull(channelName, "channelName");
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be >= 0, got " + durationMs);
        }
        if (success && errorMessage != null) {
            throw new IllegalArgumentException(
                "success=true must have errorMessage == null (got: " + errorMessage + ")");
        }
        if (!success && (errorMessage == null || errorMessage.isBlank())) {
            throw new IllegalArgumentException(
                "success=false must have non-blank errorMessage");
        }
        redactedUrl = redactedUrl == null ? "" : redactedUrl;
    }
}