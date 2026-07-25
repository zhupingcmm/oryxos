package io.oryxos.core;

import java.util.List;

/**
 * {@link ProviderService#invoke(String, LlmRequest)} 的出参。
 *
 * <p>工具调用以 Provider 中立格式返回：每个 {@link ToolCall} 包含 {@code name} +
 * {@code arguments}（JSON 字符串）+ {@code callId}。Provider 层不持有任何
 * {@code ToolCallback} / {@code FunctionCallback}，调用方拿到这个列表后自己 dispatch
 * （spec FR-010 + 宪法 §IV）。
 */
public record LlmResponse(
    String textContent,
    List<ToolCall> toolCalls,
    TokenUsage usage,
    String finishReason
) {

    /**
     * 单个 tool call（Provider 中立格式）。
     *
     * @param name      工具名
     * @param arguments 参数，JSON 字符串（{@code {"url":"https://...", "timeout":30}}）
     * @param callId    Provider 给的调用 ID（用于响应回溯）
     */
    public record ToolCall(String name, String arguments, String callId) {}

    /**
     * Token 用量；{@code promptTokens} / {@code completionTokens} 均为 {@code Integer}，
     * 失败时可空。
     */
    public record TokenUsage(Integer promptTokens, Integer completionTokens) {}
}
