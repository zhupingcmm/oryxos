package io.oryxos.tool.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * MCP client 配置（[data-model §5.3](../../../../../../../specs/005-tool-system/data-model.md)）。
 *
 * <p>YAML 路径：{@code oryxos.tool.mcp.*}。
 *
 * @param connectTimeoutSeconds   TCP 连接 / 子进程启动超时
 * @param requestTimeoutSeconds   单次 JSON-RPC 请求-响应超时
 * @param failFastOnStartup       {@code true}（默认）→ 任一 server 不可用即启动失败
 * @param sandboxAllowedDomains   可选 —— MCP HTTP transport 走外网时的域名白名单
 */
@ConfigurationProperties(prefix = "oryxos.tool.mcp")
public record McpClientProperties(
    int connectTimeoutSeconds,
    int requestTimeoutSeconds,
    boolean failFastOnStartup,
    List<String> sandboxAllowedDomains
) {
    public McpClientProperties {
        if (connectTimeoutSeconds <= 0) connectTimeoutSeconds = 5;
        if (requestTimeoutSeconds <= 0) requestTimeoutSeconds = 30;
        if (sandboxAllowedDomains == null) sandboxAllowedDomains = List.of();
    }
}
