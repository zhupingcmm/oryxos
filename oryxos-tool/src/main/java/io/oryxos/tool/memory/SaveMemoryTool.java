package io.oryxos.tool.memory;

import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.MemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code save_memory} —— 写入长期记忆（核心区 / 归档区）。
 *
 * <p>行为（[contracts/builtin-tools.md §8](../../../../../../../specs/005-tool-system/contracts/builtin-tools.md)）：
 * <ol>
 *   <li>解析 {@code content} + {@code scope}（默认 {@code "core"}）</li>
 *   <li>{@link MemoryService#save(String, MemoryScope)}</li>
 *   <li>返回 {@link MemoryToolResult} payload</li>
 * </ol>
 *
 * <p>Audit：{@code source=builtin, success=true/false}；异常 message 不含 stack trace（NFR-004）。
 */
@Component
public class SaveMemoryTool implements OryxTool {

    public static final String NAME = "save_memory";

    private final MemoryService memoryService;

    public SaveMemoryTool() {
        this(null);
    }

    @Autowired
    public SaveMemoryTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "写入长期记忆（支持核心区 / 归档区）";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Object rawContent = arguments.get("content");
        if (!(rawContent instanceof String content) || content.isBlank()) {
            return ToolResult.error("save_memory: missing required argument 'content'");
        }
        Object rawScope = arguments.get("scope");
        String scopeStr = rawScope == null ? "core" : rawScope.toString();
        MemoryScope scope;
        try {
            scope = MemoryScope.fromString(scopeStr);
        } catch (IllegalArgumentException ex) {
            return ToolResult.error("save_memory: invalid scope '" + scopeStr
                + "' (must be 'core' or 'archive')");
        }
        if (memoryService == null) {
            return ToolResult.error("save_memory: MemoryService unavailable");
        }
        try {
            MemoryEntry entry = memoryService.save(content, scope);
            return ToolResult.ok(Map.of(
                "operation", "save",
                "scope", entry.scope().name().toLowerCase(),
                "entry_count", 1
            ));
        } catch (RuntimeException ex) {
            return ToolResult.error("save_memory failed: " + ex.getMessage());
        }
    }
}

