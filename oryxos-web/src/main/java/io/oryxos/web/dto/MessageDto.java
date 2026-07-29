package io.oryxos.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

/**
 * T005 + data-model.md §实体 4 — 单条 Session 消息 DTO.
 *
 * <p>role 枚举: "user" / "assistant" / "tool";非法值 → Bean Validation 失败抛
 * {@code MethodArgumentNotValidException} → {@code GlobalExceptionHandler} 兜底
 * 400 invalid_request (per web-api.md §端点 3 错误响应表).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageDto(
    @NotBlank(message = "role must not be blank")
    @Pattern(regexp = "^(user|assistant|tool)$",
        message = "role must be one of: user, assistant, tool")
    String role,

    @NotBlank(message = "content must not be blank")
    String content,

    String toolName,
    Instant timestamp
) {
}