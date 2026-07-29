package io.oryxos.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * T005 + data-model.md §实体 3 — GET /api/v1/sessions/{id} 响应体.
 */
public record SessionDto(
    String sessionId,
    String profileName,
    Instant createdAt,
    Instant updatedAt,
    Integer messageCount,
    Map<String, Object> metadata,
    List<MessageDto> history
) {
}