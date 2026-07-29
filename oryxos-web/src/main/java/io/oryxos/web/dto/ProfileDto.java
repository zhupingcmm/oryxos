package io.oryxos.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * T005 + data-model.md §实体 5 — GET /api/v1/profiles 响应体 list 元素.
 *
 * <p>不暴露完整 Profile YAML（per spec "不在范围内" + spec FR-011）.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProfileDto(
    String name,
    String description,
    String agentName,
    String providerName,
    String model,
    Integer toolCount,
    Integer scheduleCount,
    Integer notifyChannelCount,
    List<String> bootstrapFiles
) {
}