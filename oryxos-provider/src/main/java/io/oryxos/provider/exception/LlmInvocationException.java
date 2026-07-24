package io.oryxos.provider.exception;

/**
 * LLM 调用失败（凭证错、网络异常、Provider 错误等）。
 *
 * <p>此异常被抛出时，审计行已写入 {@code llm_calls}（{@code success=false}）。
 * 调用方不应再尝试重试或回退——直接处理错误并呈现给上层（spec FR-011）。
 *
 * <p>{@link #providerName} 用于日志/响应中标识 Provider；
 * {@link #durationMs} 用于上层做超时判定。
 */
public class LlmInvocationException extends RuntimeException {

    private final String providerName;
    private final Long durationMs;

    public LlmInvocationException(String providerName, String message,
                                  Long durationMs, Throwable cause) {
        super(message, cause);
        this.providerName = providerName;
        this.durationMs = durationMs;
    }

    public String getProviderName() {
        return providerName;
    }

    public Long getDurationMs() {
        return durationMs;
    }
}