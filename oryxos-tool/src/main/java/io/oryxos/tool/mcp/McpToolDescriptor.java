package io.oryxos.tool.mcp;

/**
 * MCP {@code tools/list} 返回的单条工具元数据（[contracts/mcp-adapter.md §6](../../../../../../../specs/005-tool-system/contracts/mcp-adapter.md)）。
 *
 * @param name        远端工具名（MCP server 视角）
 * @param description 工具描述（来自 MCP server）
 * @param inputSchema JSON Schema 字符串（Function Calling input_schema 的等价 JSON）
 */
public record McpToolDescriptor(
    String name,
    String description,
    String inputSchema
) {}
