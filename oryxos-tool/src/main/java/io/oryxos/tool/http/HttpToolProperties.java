package io.oryxos.tool.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HTTP Tool 配置 —— 从 {@code oryxos.tool.http.*} 绑定。
 *
 * @param timeoutSeconds    请求超时秒数（默认 5）
 * @param maxResponseBytes  响应 body 截断上限（默认 1048576 = 1 MB）
 */
@ConfigurationProperties(prefix = "oryxos.tool.http")
public record HttpToolProperties(
    int timeoutSeconds,
    int maxResponseBytes
) {
    public HttpToolProperties {
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 5;
        }
        if (maxResponseBytes <= 0) {
            maxResponseBytes = 1_048_576;
        }
    }
}

