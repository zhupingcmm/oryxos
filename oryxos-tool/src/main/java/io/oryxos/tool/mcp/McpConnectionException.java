package io.oryxos.tool.mcp;

/**
 * MCP 连接 / 协议异常 —— 启动期 fail-fast 抛出，调用期远端异常也归此类
 * （[contracts/mcp-adapter.md §5.3](../../../../../../../specs/005-tool-system/contracts/mcp-adapter.md)）。
 *
 * <p>{@link McpClientService#startup()} 在任何 server 不可达 / 协议不匹配 / handshake 失败时抛此异常，
 * 由调用方选择 {@code failFastOnStartup} 配置 —— 核心阶段默认 true：任一 server 不可用即 JVM 退出。
 */
public class McpConnectionException extends RuntimeException {

    private final String serverName;

    public McpConnectionException(String serverName, String message) {
        super("mcp connection failure [" + serverName + "]: " + message);
        this.serverName = serverName;
    }

    public McpConnectionException(String serverName, String message, Throwable cause) {
        super("mcp connection failure [" + serverName + "]: " + message, cause);
        this.serverName = serverName;
    }

    public String serverName() {
        return serverName;
    }
}
