package io.oryxos.tool.mcp;

import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;

import java.util.Map;

/**
 * US-4 / 005-tool-system 测试桩 —— 模拟 MCP Tool。
 *
 * <p>关键：{@code getClass().getName()} MUST 以 {@code io.oryxos.tool.mcp.} 开头，
 * 否则 {@code DefaultToolExecutor.resolveSource()} 无法识别为 {@code "mcp"}。
 */
public class StubMcpTool implements OryxTool {

    @Override public String name() { return "stub-mcp"; }

    @Override public String description() { return "stub mcp"; }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        return ToolResult.ok(Map.of());
    }
}