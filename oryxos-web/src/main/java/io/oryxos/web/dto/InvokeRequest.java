package io.oryxos.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * T005 + data-model.md §实体 1 — POST /api/v1/agents/{name}/invoke 请求体.
 *
 * <p>字段命名遵循 spec 字节级契约：驼峰 + REST 业务语义.
 */
public record InvokeRequest(
    @NotBlank(message = "message must not be blank")
    @Size(max = 16_384, message = "message must not exceed 16 KB")
    String message,

    @Size(max = 36, message = "sessionId must be UUID format")
    String sessionId,

    String profileName,

    Map<String, Object> metadata
) {
}