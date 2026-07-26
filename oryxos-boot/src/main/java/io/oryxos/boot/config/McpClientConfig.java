package io.oryxos.boot.config;

import io.oryxos.tool.mcp.McpClientProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MCP client 装配 —— {@link McpClientProperties} 走 {@code oryxos.tool.mcp.*} 配置
 * （[contracts/mcp-adapter.md §8](../../../../../../../specs/005-tool-system/contracts/mcp-adapter.md)）。
 *
 * <p>{@code McpClientService} 自身已标 {@code @Component}，由 {@code oryxos-tool} 模块的 component-scan 自动发现；
 * 这里只负责启用对应的 {@code @ConfigurationProperties}。
 */
@Configuration
@EnableConfigurationProperties(McpClientProperties.class)
public class McpClientConfig {
}
