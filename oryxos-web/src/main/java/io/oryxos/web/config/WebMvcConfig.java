package io.oryxos.web.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T009 — Jackson 全局配置.
 *
 * <p>三件事：
 * <ul>
 *   <li>{@code JsonInclude.NON_NULL} — 可选字段为 null 时不序列化（如 {@code ErrorResponse.field}）</li>
 *   <li>{@code PropertyNamingStrategies.SNAKE_CASE} — 对齐 spec.md 用词
 *       （{@code session_id} / {@code tool_calls[]} / {@code duration_ms}）；Java record 字段仍为 camelCase
 *       （{@code sessionId} / {@code durationMs}），由 Jackson 序列化层翻译</li>
 *   <li>{@code simpleDateFormat} — 统一时间戳格式 yyyy-MM-dd'T'HH:mm:ss.SSSXXX</li>
 * </ul>
 */
@Configuration
public class WebMvcConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> {
            builder.serializationInclusion(JsonInclude.Include.NON_NULL);
            builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            builder.simpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        };
    }
}