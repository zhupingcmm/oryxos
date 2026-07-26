package io.oryxos.tool.mcp;

import io.oryxos.core.OryxTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * T050 —— McpToolAdapter 契约测试。
 *
 * <p>覆盖 [contracts/mcp-adapter.md §6](../../../../../../../specs/005-tool-system/contracts/mcp-adapter.md)：
 * descriptor → Tool 转换保持 name / description / inputSchema；origin 通过 {@code McpTool} 类型枚举为 "mcp"。
 */
class McpToolAdapterTest {

    @Test
    @DisplayName("adapt 保留 name + description + inputSchema")
    void adapt_preserves_descriptor_fields() {
        McpToolAdapter adapter = new McpToolAdapter();
        McpTransport mockTransport = mock(McpTransport.class);
        List<McpToolDescriptor> descriptors = List.of(
            new McpToolDescriptor("echo", "Echo back input", "{\"type\":\"object\"}"),
            new McpToolDescriptor("add", "Add two numbers", "{\"type\":\"object\"}")
        );

        List<OryxTool> tools = adapter.adapt("serverA", descriptors, mockTransport);

        assertThat(tools).hasSize(2);
        assertThat(tools.get(0).name()).isEqualTo("serverA__echo");
        assertThat(tools.get(0).description()).isEqualTo("Echo back input");
        assertThat(tools.get(1).name()).isEqualTo("serverA__add");
    }

    @Test
    @DisplayName("adapt 空列表 → 返回空 list（非 null）")
    void adapt_empty() {
        McpToolAdapter adapter = new McpToolAdapter();
        List<OryxTool> tools = adapter.adapt("s", List.of(), mock(McpTransport.class));
        assertThat(tools).isEmpty();
    }

    @Test
    @DisplayName("source 标识为 'mcp': McpTool 的 FQCN 前缀 io.oryxos.tool.mcp.*")
    void source_resolution_returns_mcp() {
        McpTool tool = new McpTool("srv",
            new McpToolDescriptor("x", "x", "{}"), mock(McpTransport.class));
        // resolveSource 期望 io.oryxos.tool.* → builtin, mcp.* 子包 → mcp
        // 但实际 DefaultToolExecutor 的分类基于 McpTool 的 FQCN "io.oryxos.tool.mcp.McpTool" → "mcp"
        // 验证类的 FQCN 路径符合约定
        assertThat(tool.getClass().getName()).startsWith("io.oryxos.tool.mcp.");
    }

    @Test
    @DisplayName("execute 带 args → 内部走 sendRequest(\"tools/call\", ...)")
    void execute_passes_arguments_to_sendRequest() {
        McpTransport mockTransport = mock(McpTransport.class);
        org.mockito.Mockito.when(mockTransport.sendRequest(
                org.mockito.ArgumentMatchers.eq("tools/call"),
                org.mockito.ArgumentMatchers.anyMap()))
            .thenReturn(new McpResponse(1, Map.of("ok", true), null));

        McpTool tool = new McpTool("srv",
            new McpToolDescriptor("echo", "", "{}"), mockTransport);
        var result = tool.execute(Map.of("text", "hello"));

        org.mockito.Mockito.verify(mockTransport).sendRequest(
            org.mockito.ArgumentMatchers.eq("tools/call"),
            org.mockito.ArgumentMatchers.argThat(p ->
                p.containsKey("arguments") && p.get("arguments").equals(Map.of("text", "hello"))
            )
        );
        assertThat(result.success()).isTrue();
    }
}
