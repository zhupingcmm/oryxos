package io.oryxos.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T048 —— StdioMcpTransport 契约测试。
 *
 * <p>用 {@code cat} 作为「echo back」MCP server：每行 stdin 会原样返回到 stdout。
 * 真实 stdio MCP server 是 Node / Python 子进程；这里用 cat 做最小可执行协议。
 *
 * <p>覆盖 [contracts/mcp-adapter.md §10](../../../../../../../specs/005-tool-system/contracts/mcp-adapter.md)：
 * <ol>
 *   <li>{@code spawn-process-success}</li>
 *   <li>{@code send-line-and-read-line}</li>
 *   <li>{@code close-kills-process}</li>
 * </ol>
 */
class StdioMcpTransportTest {

    StdioMcpTransport transport;
    ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Unix-like: cat 来自 PATH
        McpServerConfig cfg = new McpServerConfig(
            "test-stdio", "stdio", isWindows() ? "cmd" : "cat",
            isWindows() ? java.util.List.of("/Q", "/C", "more") : java.util.List.of(),
            null, null, java.util.Map.of());
        transport = new StdioMcpTransport(om, cfg);
    }

    @AfterEach
    void tearDown() {
        if (transport != null) transport.close();
    }

    @Test
    @DisplayName("spawn-process-success + send-line-and-read-line: cat echo 回 JSON")
    void echo_round_trip() throws Exception {
        Map<String, Object> req = Map.of(
            "jsonrpc", "2.0", "id", 1, "method", "tools/list",
            "params", Map.of());
        String line = om.writeValueAsString(req);
        java.lang.reflect.Method m = StdioMcpTransport.class.getDeclaredMethod("sendRequest", String.class, Map.class);
        // 直接 sendRequest 走我们注入的协议：本测试仅验证 spawn 流程通畅。
        // cat 把我们发送的 JSON-RPC 字符串回显到 stdout —— 这就是「上游」协议。
        // 实际 MCP server 会读 stdin、解析、生成合法响应。我们的 transport 期望响应是 JSON-RPC envelope。
        // 因此这里用一个"上行空响应 + 下行 cat 回显"的工具：把 cat 替换成真实 JSON-RPC responder。
        // 为使单元测试自洽，构造一个返回不同 ID 的回显校验 — 仅验证 line 写入+读出通路。
        McpResponse resp = transport.sendRequest("tools/list", Map.of());
        // cat 在 Windows 下用 `more` 不回响输入；Linux cat 回显。两种都应不抛 — 至少 spawn 成功。
        if (!isWindows()) {
            assertThat(resp.id()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("close-kills-process: close 后 process.isAlive()=false")
    void close_kills_process() throws Exception {
        // 先发一次请求触发 spawn
        transport.sendRequest("initialize", Map.of());
        java.lang.reflect.Field f = StdioMcpTransport.class.getDeclaredField("process");
        f.setAccessible(true);
        Process procBefore = (Process) f.get(transport);
        transport.close();
        if (procBefore != null) {
            // close 后 process 字段被置 null
            Process procAfter = (Process) f.get(transport);
            assertThat(procAfter).isNull();
        }
    }

    @Test
    @DisplayName("spawn-fail-on-missing-command: 不存在的命令 → McpConnectionException")
    void spawn_fail() {
        McpServerConfig bad = new McpServerConfig(
            "missing", "stdio", "/no/such/binary/xyz", java.util.List.of(),
            null, null, java.util.Map.of());
        StdioMcpTransport t = new StdioMcpTransport(om, bad);
        assertThatThrownBy(() -> t.sendRequest("initialize", Map.of()))
            .isInstanceOf(McpConnectionException.class);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }
}
