package io.oryxos.web.controller;

import io.oryxos.core.tool.ToolDefinition;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.web.dto.ToolDto;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * T033 + data-model.md §实体 6 + contracts/web-api.md §端点 8 — GET /api/v1/tools.
 *
 * <p>{@code source} 枚举与 {@code tool_invocations.source} 字节级一致 ——
 * {@code "builtin"} / {@code "mcp"} / {@code "java_bean"} (per CLAUDE.md §9.7
 * "Tool 来源审计" + 005 契约).
 *
 * <p>{@code origin} → {@code source} 映射规则 (per CLAUDE.md §9.7 + DefaultToolExecutor):
 * <ul>
 *   <li>{@code "builtin"} → "builtin"</li>
 *   <li>{@code "mcp"}    → "mcp"</li>
 *   <li>{@code "skill"} / {@code "external"} → "java_bean" (FQCN 不在 io.oryxos.tool.*)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class ToolsController {

    private static final String SOURCE_PATTERN = "^(builtin|mcp|java_bean)$";

    private final ToolRegistry toolRegistry;

    public ToolsController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping("/tools")
    public List<ToolDto> list(
        @RequestParam(required = false)
        @Pattern(regexp = SOURCE_PATTERN,
            message = "source must be one of: builtin, mcp, java_bean")
        String source
    ) {
        List<ToolDto> out = new ArrayList<>();
        for (ToolDefinition def : toolRegistry.all()) {
            String mappedSource = mapOriginToSource(def.origin());
            if (source != null && !source.equals(mappedSource)) {
                continue;
            }
            out.add(new ToolDto(
                def.name(),
                def.description(),
                mappedSource,
                null  // inputSchema 由 ToolSchemaProvider 渲染;控制器不生成 (per US-3 contract)
            ));
        }
        return out;
    }

    private static String mapOriginToSource(String origin) {
        if (origin == null) return "java_bean";
        return switch (origin) {
            case "builtin" -> "builtin";
            case "mcp" -> "mcp";
            default -> "java_bean"; // skill / external
        };
    }
}
