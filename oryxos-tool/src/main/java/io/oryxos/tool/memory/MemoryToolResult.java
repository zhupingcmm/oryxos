package io.oryxos.tool.memory;

import java.util.List;

/**
 * Memory Tool 返回值 —— {@code save_memory} / {@code recall_memory} 共用。
 *
 * @param operation   {@code "save"} 或 {@code "recall"}
 * @param scope       {@code "core"} / {@code "archive"}；recall 通常固定 {@code "core"}
 * @param entryCount  recall 时为命中条数；save 时为 1
 * @param snippets    recall 时为命中条目截断到 200 字符的字符串列表；save 时为 {@code null}
 */
public record MemoryToolResult(
    String operation,
    String scope,
    int entryCount,
    List<String> snippets
) { }

