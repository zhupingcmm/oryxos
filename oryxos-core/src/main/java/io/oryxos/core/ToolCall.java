package io.oryxos.core;

import java.util.Map;
import java.util.Objects;

/**
 * 单条 tool call —— LLM 响应中的工具调用表示。
 *
 * <p>Provider 中立格式（详见 [data-model.md §3.4](../../../../../specs/002-react-loop/data-model.md)）。
 *
 * @param id        LLM 给出的 tool_call.id；与 {@code Message.toolCallId}（{@link Role#TOOL}）
 *                  对应，用于跨轮关联。
 * @param name      工具名（应匹配 {@code Profile.tools()} 白名单）
 * @param arguments 解析后的参数 map（可空 → 视为空 map）
 */
public record ToolCall(
    String id,
    String name,
    Map<String, Object> arguments
) {
    /** 规范化 null arguments → 空 map（不可变）。 */
    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
