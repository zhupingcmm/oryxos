package io.oryxos.web.dto;

/**
 * T005 + data-model.md §实体 9 — GET /api/v1/info 响应体.
 */
public record InfoDto(
    String name,
    String version,
    String javaVersion,
    String osName,
    Integer agents,
    Integer tools,
    Long uptimeMs
) {
}