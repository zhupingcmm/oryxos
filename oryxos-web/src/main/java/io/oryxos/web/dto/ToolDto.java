package io.oryxos.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * T005 + data-model.md §实体 6 — GET /api/v1/tools 响应体 list 元素.
 *
 * <p>source 枚举: "builtin" / "mcp" / "java_bean"（与 tool_invocations.source 字节级对齐）.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDto(
    String name,
    String description,
    String source,
    Object inputSchema
) {
}