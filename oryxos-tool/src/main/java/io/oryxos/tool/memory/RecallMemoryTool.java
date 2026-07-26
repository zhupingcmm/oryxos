package io.oryxos.tool.memory;

import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code recall_memory} —— 按关键词检索长期记忆。
 *
 * <p>行为（[contracts/builtin-tools.md §9](../../../../../../../specs/005-tool-system/contracts/builtin-tools.md)）：
 * <ol>
 *   <li>解析 {@code query} + {@code top_k}（默认 5）</li>
 *   <li>{@link MemoryService#recallByKeyword(String, int)}</li>
 *   <li>命中条目截断到 200 字符</li>
 *   <li>返回 {@link MemoryToolResult} payload</li>
 * </ol>
 */
@Component
public class RecallMemoryTool implements OryxTool {

    public static final String NAME = "recall_memory";

    private static final int MAX_SNIPPET_LEN = 200;

    private final MemoryService memoryService;

    public RecallMemoryTool() {
        this(null);
    }

    @Autowired
    public RecallMemoryTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "按关键词检索长期记忆";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Object rawQuery = arguments.get("query");
        if (!(rawQuery instanceof String query) || query.isBlank()) {
            return ToolResult.error("recall_memory: missing required argument 'query'");
        }
        int topK = 5;
        Object rawTopK = arguments.get("top_k");
        if (rawTopK instanceof Number n) {
            topK = Math.max(1, n.intValue());
        }
        if (memoryService == null) {
            return ToolResult.error("recall_memory: MemoryService unavailable");
        }
        try {
            List<MemoryEntry> hits = memoryService.recallByKeyword(query, topK);
            List<String> snippets = hits.stream()
                .map(MemoryEntry::content)
                .map(c -> c.length() > MAX_SNIPPET_LEN
                    ? c.substring(0, MAX_SNIPPET_LEN) + "..."
                    : c)
                .collect(Collectors.toList());
            return ToolResult.ok(Map.of(
                "operation", "recall",
                "scope", "core",
                "entry_count", hits.size(),
                "snippets", snippets
            ));
        } catch (RuntimeException ex) {
            return ToolResult.error("recall_memory failed: " + ex.getMessage());
        }
    }
}

