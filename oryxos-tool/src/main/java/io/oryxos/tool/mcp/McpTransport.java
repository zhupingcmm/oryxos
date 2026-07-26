package io.oryxos.tool.mcp;

import java.util.Map;

/**
 * MCP transport 抽象 —— JSON-RPC over HTTP（SSE）/ stdio（line-delimited）
 * （[contracts/mcp-adapter.md §4](../../../../../../../specs/005-tool-system/contracts/mcp-adapter.md)）。
 *
 * <p>实现：
 * <ul>
 *   <li>{@link HttpMcpTransport} —— HTTP POST + SSE（或一次性 JSON）response 解析</li>
 *   <li>{@link StdioMcpTransport} —— 子进程 stdin/stdout 行式 JSON-RPC</li>
 * </ul>
 */
public interface McpTransport {

    /**
     * 发送一次 JSON-RPC 请求并阻塞等待响应。
     *
     * @param method JSON-RPC method（如 {@code "initialize"} / {@code "tools/list"} / {@code "tools/call"}）
     * @param params params 字典（可能为空，但不能为 null —— 用 {@link Map#of()}）
     * @return 远端响应
     * @throws McpConnectionException 协议错误 / 连接断开 / JSON 解析失败
     */
    McpResponse sendRequest(String method, Map<String, Object> params);

    /** 关闭底层连接（HTTP 关闭连接池 / stdio kill 子进程）；幂等。 */
    void close();
}
