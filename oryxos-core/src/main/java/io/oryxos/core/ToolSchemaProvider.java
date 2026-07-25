package io.oryxos.core;

import java.util.List;
import java.util.Map;

/**
 * Tool Schema 提供方 —— 把当前 Profile 可见的 Tool 翻译为 OpenAI/DeepSeek/Qwen 通用的
 * Function Calling JSON Schema 列表（{@code {"type":"function","function":{...}}}）。
 *
 * <p>US-2 桩实现：{@link NoopToolSchemaProvider} 返回空列表（P1 阶段不传 Tool schema）。
 * US-4 引入 {@code ToolRegistry} 真实实现（按 {@code profile.tools()} 过滤 + 翻译）。
 */
@FunctionalInterface
public interface ToolSchemaProvider {

    List<Map<String, Object>> schemasFor(Profile profile);

    /** US-2 桩实现 —— 永远返回空 schema 列表。 */
    final class NoopToolSchemaProvider implements ToolSchemaProvider {
        @Override
        public List<Map<String, Object>> schemasFor(Profile profile) {
            return List.of();
        }
    }
}