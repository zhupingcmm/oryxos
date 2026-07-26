package io.oryxos.tool.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP server connection metadata —— 一次成功的 {@code initialize} 握手之后的快照
 * （[contracts/mcp-adapter.md §5](../../../../../../../specs/005-tool-system/contracts/mcp-adapter.md)）。
 *
 * <p>由 {@code McpClientService.startup()} 在每个 {@code mcp_servers.yaml} 条目握手成功后产出，
 * 并通过 {@link ToolRegistry} 注册 {@link McpTool} 实例。
 *
 * @param name        来自 {@code mcp_servers.yaml} 的 server 名称（key）
 * @param transport   {@code "http"} 或 {@code "stdio"} —— 实际使用的 transport
 * @param endpoint    实际连接的 URL（http）或 command 字符串（stdio）
 * @param capabilities server 报告的 capabilities 字典（可能为空）
 * @param toolNames   暴露给本进程的工具名列表
 * @param state       连接状态（CONNECTED / DISCONNECTED —— 启动失败则不进入连接池）
 */
public record McpServerConnection(
    String name,
    String transport,
    String endpoint,
    Map<String, Object> capabilities,
    List<String> toolNames,
    ConnectionState state
) {
    public enum ConnectionState { CONNECTED, DISCONNECTED }
}
