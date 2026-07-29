package io.oryxos.web.exception;

import io.oryxos.web.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * T007 + research.md R-007 + data-model.md §实体 10 — 统一 REST 错误响应.
 *
 * <p>所有 4xx / 5xx 必须返回 {@link ErrorResponse} shape;detail MUST NOT 包含 stack trace
 * (per 007-sandbox-whitelist FR-007 byte-level contract).
 *
 * <p>HTTP → error code 映射 (per data-model.md §实体 10):
 * <ul>
 *   <li>400 invalid_request — Bean Validation 失败 (MethodArgumentNotValidException)</li>
 *   <li>400 invalid_json — JSON 反序列化失败 (HttpMessageNotReadableException)</li>
 *   <li>400 invalid_path_param — 路径参数类型错 (MethodArgumentTypeMismatchException)</li>
 *   <li>404 agent_not_found / session_not_found — Agent / Session 不存在</li>
 *   <li>500 internal_error — 兜底</li>
 *   <li>503 service_unavailable — Spring 启动失败 / Agent 未加载</li>
 *   <li>504 agent_timeout — ReAct 循环超时</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        var fieldError = ex.getBindingResult().getFieldError();
        String field = fieldError != null ? fieldError.getField() : null;
        String detail = fieldError != null
            ? fieldError.getDefaultMessage()
            : "Validation failed";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.field("invalid_request", detail, field));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleInvalidJson(HttpMessageNotReadableException ex) {
        // detail 不暴露 stack trace;只给通用消息
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of("invalid_json", "Request body is not valid JSON"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handlePathParamType(MethodArgumentTypeMismatchException ex) {
        String detail = "Path parameter '" + ex.getName() + "' has invalid format";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.field("invalid_path_param", detail, ex.getName()));
    }

    /**
     * T026 — {@code @PathVariable @Pattern} UUID 校验失败 → 400 invalid_path_param.
     *
     * <p>{@link ConstraintViolationException} 是 {@code @Valid} + {@code @Validated}
     * 在 path / query 参数上触发时的标准异常.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        var v = ex.getConstraintViolations().stream().findFirst()
            .orElseThrow(() -> ex);
        String path = v.getPropertyPath().toString();
        // propertyPath 形如 "get.id" — 取最后一段作为字段名
        String field = path.contains(".")
            ? path.substring(path.lastIndexOf('.') + 1)
            : path;
        String detail = "Path parameter '" + field + "' has invalid format: " + v.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.field("invalid_path_param", detail, field));
    }

    /**
     * Spring 6.1+ path variable 单元素校验失败 (per-method @Validated).
     * 兜底处理,行为同 ConstraintViolationException → 400 invalid_path_param.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of("invalid_request", "Validation failed: " + ex.getMessage()));
    }

    @ExceptionHandler(AgentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAgentNotFound(AgentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse.of("agent_not_found", ex.getMessage()));
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSessionNotFound(SessionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse.of("session_not_found", ex.getMessage()));
    }

    @ExceptionHandler(AgentTimeoutException.class)
    public ResponseEntity<ErrorResponse> handleAgentTimeout(AgentTimeoutException ex) {
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
            .body(ErrorResponse.of("agent_timeout", ex.getMessage()));
    }

    @ExceptionHandler(AgentNotLoadedException.class)
    public ResponseEntity<ErrorResponse> handleAgentNotLoaded(AgentNotLoadedException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ErrorResponse.of("service_unavailable", ex.getMessage()));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoHandlerFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse.of("not_found", "Endpoint not found: " + ex.getRequestURL()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        // Spring 6 fallback when static resource handler can't match — happens on
        // empty path segments /api/v1/agents//invoke. Map to 404 with stable error code.
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse.of("not_found", "Endpoint not found: " + ex.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleInternal(Exception ex) {
        log.error("Unhandled exception in REST handler: {}", ex.toString(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.of("internal_error", "Internal server error"));
    }
}