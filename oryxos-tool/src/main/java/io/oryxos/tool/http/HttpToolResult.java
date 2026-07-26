package io.oryxos.tool.http;

/**
 * HTTP Tool 返回值（不可变 record）—— {@code http_get} / {@code http_post} 共用。
 *
 * @param statusCode   HTTP 状态码（4xx / 5xx 时仍返回，由 Tool 层决定 success = false）
 * @param contentType  响应 Content-Type（可空）
 * @param body         响应 body（截断到 {@code max-response-bytes}）
 * @param durationMs   请求 wall-time（毫秒）
 */
public record HttpToolResult(
    int statusCode,
    String contentType,
    String body,
    long durationMs
) { }

