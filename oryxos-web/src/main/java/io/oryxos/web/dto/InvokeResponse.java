package io.oryxos.web.dto;

import java.util.Map;

/**
 * T005 + data-model.md §实体 2 — POST /api/v1/agents/{name}/invoke 响应体.
 *
 * <p>字段命名遵循 spec 字节级契约.
 */
public record InvokeResponse(
    String sessionId,
    String reply,
    Integer iterations,
    Long durationMs,
    Map<String, Object> metadata
) {
}