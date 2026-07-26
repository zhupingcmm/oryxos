package io.oryxos.tool.mcp;

import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;

import java.util.HashMap;
import java.util.Map;

/**
 * 单个 MCP 工具的 OryxTool 适配 —— {@code execute(args)} 等价于
 * {@code transport.sendRequest("tools/call", {name, arguments})}
 * （[contracts/mcp-adapter.md §7](../../../../../../../specs/005-tool-system/contracts/mcp-adapter.md)）。
 *
 * <p>错误处理：
 * <ul>
 *   <li>{@link McpConnectionException}（连接 / 超时 / 解析）→ {@link ToolResult#error} 包含原因</li>
 *   <li>JSON-RPC 协议错误（{@code error != null}）→ {@link ToolResult#error} 包含 server code+message</li>
 *   <li>成功 → {@link ToolResult#ok} 以 {@code result} 字典为 payload</li>
 * </ul>
 */
public class McpTool implements OryxTool {

    private final String serverName;
    private final McpToolDescriptor descriptor;
    private final McpTransport transport;

    public McpTool(String serverName, McpToolDescriptor descriptor, McpTransport transport) {
        this.serverName = serverName;
        this.descriptor = descriptor;
        this.transport = transport;
    }

    @Override
    public String name() {
        // 命名空间隔开避免冲突：{server}__{tool}
        return serverName + "__" + descriptor.name();
    }

    @Override
    public String description() {
        return descriptor.description();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", descriptor.name());
        params.put("arguments", arguments == null ? Map.of() : arguments);
        try {
            McpResponse resp = transport.sendRequest("tools/call", params);
            if (resp.isError()) {
                return ToolResult.error("mcp tool [" + descriptor.name() + "] error: "
                    + resp.errorMessage());
            }
            return ToolResult.ok(resp.result() == null ? Map.of() : resp.result());
        } catch (McpConnectionException ex) {
            return ToolResult.error("mcp connection failed: " + ex.getMessage());
        } catch (RuntimeException ex) {
            return ToolResult.error("mcp tool [" + descriptor.name() + "] failed: " + ex.getMessage());
        }
    }
}
