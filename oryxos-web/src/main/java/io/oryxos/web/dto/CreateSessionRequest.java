package io.oryxos.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

/**
 * T021 + data-model.md §端点 2 — POST /api/v1/sessions 请求体.
 *
 * <p>profileName 必填 (必须已注册的 Profile);metadata 可选 (≤ 4KB,作为附加键注入 sessions.metadata).
 *
 * <p>profileName 字符集限制与 SessionFactory.create() 同款 (per 008 数据模型 §"C-AS-3 契约"):
 * {@code ^[a-z][a-z0-9-]{0,63}$} —— 失败抛 {@link org.springframework.web.bind.MethodArgumentNotValidException} → 400 invalid_request.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateSessionRequest(
    @NotBlank(message = "profileName must not be blank")
    @Pattern(regexp = "^[a-z][a-z0-9-]{0,63}$",
        message = "profileName must match pattern ^[a-z][a-z0-9-]{0,63}$")
    String profileName,

    Map<String, Object> metadata
) {
}
