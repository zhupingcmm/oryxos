package io.oryxos.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * T021 + data-model.md §端点 3 — POST /api/v1/sessions/{id}/messages 响应体.
 *
 * <p>包含 sessionId / 更新后的 messageCount / updatedAt / 刚追加的 Message 副本.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddMessageResponse(
    String sessionId,
    Integer messageCount,
    Instant updatedAt,
    MessageDto message
) {
}
