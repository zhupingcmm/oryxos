package io.oryxos.tool.mcp;

import io.oryxos.core.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T051 —— McpTool 契约测试。
 *
 * <p>覆盖 [contracts/mcp-adapter.md §7](../../../../../../../specs/005-tool-system/contracts/mcp-adapter.md)：
 * <ol>
 *   <li>{@code execute-dispatches-tools-call} —— 正常路径</li>
 *   <li>{@code execute-handles-error-response} —— JSON-RPC error</li>
 *   <li>{@code execute-handles-connection-lost} —— McpConnectionException 包装</li>
 * </ol>
 */
class McpToolTest {

    @Test
    @DisplayName("execute-dispatches-tools-call: 成功 → ToolResult.ok")
    void execute_success() {
        McpTransport t = mock(McpTransport.class);
        when(t.sendRequest(eq("tools/call"), any())).thenReturn(
            new McpResponse(1, Map.of("echo", "hello"), null));
        McpTool tool = new McpTool("srv",
            new McpToolDescriptor("echo", "echo", "{}"), t);
        ToolResult r = tool.execute(Map.of("text", "hello"));
        assertThat(r.success()).isTrue();
        assertThat(r.payload()).containsEntry("echo", "hello");
    }

    @Test
    @DisplayName("execute-handles-error-response: JSON-RPC error → ToolResult.error")
    void execute_error_response() {
        McpTransport t = mock(McpTransport.class);
        when(t.sendRequest(eq("tools/call"), any())).thenReturn(
            new McpResponse(1, null,
                Map.of(McpResponse.ERR_CODE, -32601,
                       McpResponse.ERR_MESSAGE, "tool not found")));
        McpTool tool = new McpTool("srv",
            new McpToolDescriptor("echo", "echo", "{}"), t);
        ToolResult r = tool.execute(Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("tool not found");
    }

    @Test
    @DisplayName("execute-handles-connection-lost: McpConnectionException → ToolResult.error")
    void execute_connection_lost() {
        McpTransport t = mock(McpTransport.class);
        when(t.sendRequest(eq("tools/call"), any())).thenThrow(
            new McpConnectionException("srv", "stream closed"));
        McpTool tool = new McpTool("srv",
            new McpToolDescriptor("echo", "echo", "{}"), t);
        ToolResult r = tool.execute(Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("mcp connection failed");
    }

    @Test
    @DisplayName("name 命名空间隔开: server__tool")
    void name_namespaced() {
        McpTool tool = new McpTool("github",
            new McpToolDescriptor("list_issues", "list issues", "{}"),
            mock(McpTransport.class));
        assertThat(tool.name()).isEqualTo("github__list_issues");
    }
}
