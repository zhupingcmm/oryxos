package io.oryxos.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * T005 + data-model.md §实体 10 — 统一错误响应 envelope.
 *
 * <p>所有 4xx / 5xx 必须返回此 shape（per research.md R-007）.
 * <p>field 仅在表单校验失败时出现;其他情况为 null（{@code @JsonInclude(NON_NULL)}）.
 * <p>detail MUST NOT 包含 stack trace（per 007-sandbox-whitelist FR-007）.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    String error,
    String detail,
    String field
) {
    public static ErrorResponse of(String error, String detail) {
        return new ErrorResponse(error, detail, null);
    }

    public static ErrorResponse field(String error, String detail, String field) {
        return new ErrorResponse(error, detail, field);
    }
}