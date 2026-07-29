package io.oryxos.web.dto;

import java.util.Map;

/**
 * T005 + data-model.md §实体 8 — GET /api/v1/health 响应体.
 */
public record HealthDto(
    String status,
    Long uptimeMs,
    String version,
    Map<String, Object> components
) {
}