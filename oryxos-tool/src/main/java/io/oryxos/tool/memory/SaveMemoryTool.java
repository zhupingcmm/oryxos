package io.oryxos.tool.memory;

import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.MemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code save_memory} —— 写入长期记忆（核心区 / 归档区）。
 *
 * <p>行为（[contracts/builtin-tools.md §8](../../../../../../../specs/005-tool-system/contracts/builtin-tools.md)）：
 * <ol>
 *   <li>解析 {@code content} + {@code scope}（默认 {@code "core"}）+ {@code tags}（可选 List<String>）</li>
 *   <li>{@link MemoryService#save(MemoryScope, String, List)}</li>
 *   <li>返回 {@code Map.of("operation", "save", "scope", ..., "entry_count", 1)} payload</li>
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
        return "写入长期记忆（支持核心区 / 归档区，可选 tags）";
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
        // 006-memory-layer spec FR-008：scope 必填（不允许隐式默认）。
        // 本 Tool 层仍保留 LLM 友好默认 "core"，让 Agent / User 调用更简单；
        // 显式非法值（如 "garbage"）才报错。
        List<String> tags = parseTags(arguments.get("tags"));
        if (memoryService == null) {
            return ToolResult.error("save_memory: MemoryService unavailable");
        }
        try {
            MemoryEntry entry = memoryService.save(scope, content, tags);
            return ToolResult.ok(Map.of(
                "operation", "save",
                "scope", entry.scope().name().toLowerCase(),
                "entry_count", 1
            ));
        } catch (IllegalArgumentException ex) {
            // scope 校验 / content 校验 —— 这层是 Tool 参数错误，语义清晰
            return ToolResult.error("save_memory invalid argument: " + ex.getMessage());
        } catch (RuntimeException ex) {
            // 底层 IO / MemoryException → 不含 stack trace（C-MS-08）
            return ToolResult.error("save_memory failed: " + ex.getMessage());
        }
    }

    /**
     * 解析 {@code tags} 参数 —— 支持 {@code List<String>} 或 {@code String}（按 {@code ,} 切分）。
     * null / 非字符串/非列表 → 空列表。
     */
    private static List<String> parseTags(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null) result.add(o.toString());
            }
            return result;
        }
        if (raw instanceof String s && !s.isBlank()) {
            return List.of(s.split("\\s*,\\s*"));
        }
        return List.of();
    }
}

