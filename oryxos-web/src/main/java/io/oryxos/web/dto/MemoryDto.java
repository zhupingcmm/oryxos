package io.oryxos.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * T005 + data-model.md §实体 7 — GET /api/v1/memory 响应体.
 *
 * <p>仅暴露元数据；内容读取走 Agent 经 Tool 维护（per CLAUDE.md §15 + spec FR-011）.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemoryDto(
    String backend,
    Integer coreEntries,
    Integer archiveEntries,
    String filePath
) {
}