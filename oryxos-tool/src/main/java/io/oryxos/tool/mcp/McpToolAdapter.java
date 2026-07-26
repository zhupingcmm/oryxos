package io.oryxos.tool.mcp;

import io.oryxos.core.OryxTool;
import org.springframework.stereotype.Component;


import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@link McpToolDescriptor} 列表（来自 {@code tools/list}）包装为 {@link McpTool} 实例
 * （[contracts/mcp-adapter.md §6](../../../../../../../specs/005-tool-system/contracts/mcp-adapter.md)）。
 *
 * <p>每一个 MCP tool 对应一个 {@link McpTool}，共享同一个 transport —— 调用 {@code tools/call} 时
 * 把参数转回 JSON-RPC params。
 *
 * <p>{@code source} 标识为 {@code "mcp"}（走 {@code resolveSource} 的 FQCN 前缀匹配）。
 */
@Component
public class McpToolAdapter {

    /**
     * 适配一个 server 名 + 描述符列表 + transport 为 {@link OryxTool} 列表。
     *
     * @param serverName   MCP server 名称（来自 {@code mcp_servers.yaml}）
     * @param descriptors  {@code tools/list} 返回的描述符
     * @param transport    已建立的 {@link McpTransport}
     */
    public List<OryxTool> adapt(String serverName, List<McpToolDescriptor> descriptors,
                                 McpTransport transport) {
        if (descriptors == null) return List.of();
        List<OryxTool> out = new ArrayList<>(descriptors.size());
        for (McpToolDescriptor d : descriptors) {
            out.add(new McpTool(serverName, d, transport));
        }
        return out;
    }
}
