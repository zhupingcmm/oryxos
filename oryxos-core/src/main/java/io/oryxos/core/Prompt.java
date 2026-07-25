package io.oryxos.core;

import java.util.List;
import java.util.Map;

/**
 * ReAct 循环发送给 LLM 的"内存形态"prompt。
 *
 * <p>四段式组装（按 [CLAUDE.md §9.2](../../../../../CLAUDE.md)）：
 * <ol>
 *   <li>{@link #systemBlocks} —— AGENT.md + Bootstrap 文件 + 当前日期时间行</li>
 *   <li>{@link #memoryBlocks} —— Memory 注入（US-3 提供；US-2 默认 empty）</li>
 *   <li>{@link #historyBlocks} —— 最近 N 条对话历史（按 {@code settings.maxHistoryTurns} 截断）</li>
 *   <li>{@link #toolSchemas} —— 当前 Profile 可用 Tool 的 JSON Schema 列表</li>
 * </ol>
 *
 * <p>每段均为 {@code List<Map<String,Object>>} —— 与 {@link LlmRequest#messages()} +
 * {@link LlmRequest#toolSchemas()} 的 Provider 中立 JSON 格式保持一致
 * （spec FR-006 / data-model §3.6）。
 */
public record Prompt(
    List<Map<String, Object>> systemBlocks,
    List<Map<String, Object>> memoryBlocks,
    List<Map<String, Object>> historyBlocks,
    List<Map<String, Object>> toolSchemas
) {
    public Prompt {
        // 不可为 null；null 视为空 list；元素本身可空列表（但不可为 null）
        systemBlocks = systemBlocks == null ? List.of() : List.copyOf(systemBlocks);
        memoryBlocks = memoryBlocks == null ? List.of() : List.copyOf(memoryBlocks);
        historyBlocks = historyBlocks == null ? List.of() : List.copyOf(historyBlocks);
        toolSchemas = toolSchemas == null ? List.of() : List.copyOf(toolSchemas);
    }

    /** 全部消息按"system → memory → history"顺序展平为 {@code LlmRequest.messages} 形态。 */
    public List<Map<String, Object>> flatten() {
        var all = new java.util.ArrayList<Map<String, Object>>(
            systemBlocks.size() + memoryBlocks.size() + historyBlocks.size());
        all.addAll(systemBlocks);
        all.addAll(memoryBlocks);
        all.addAll(historyBlocks);
        return List.copyOf(all);
    }

    /** 工厂：空 prompt（用于没有历史/工具的边界场景）。 */
    public static Prompt empty() {
        return new Prompt(List.of(), List.of(), List.of(), List.of());
    }
}