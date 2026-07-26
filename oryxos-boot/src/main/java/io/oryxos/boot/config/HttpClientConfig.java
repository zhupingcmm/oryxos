package io.oryxos.boot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * JDK 21 {@link HttpClient} 单例 Bean —— 与 {@code oryxos-tool/http/} 的
 * {@code HttpGetTool} / {@code HttpPostTool}、{@code oryxos-tool/mcp/} 的
 * {@code HttpMcpTransport} 以及 {@code WebHookNotifyAdapter}（004 spec）共享。
 *
 * <p>关键决策（[research.md R-01](../../../../../../../specs/005-tool-system/research.md)）：
 * <ul>
 *   <li>{@link HttpClient} 是 JDK 内置（无第三方依赖） —— 与宪法 §I 单栈一致</li>
 *   <li>单例（Spring 默认 scope = singleton） —— 内部已自带连接池</li>
 *   <li>{@code connectTimeout = 5s} —— 弱依赖场景下的默认上限，避免 Tool 调用被 TCP 握手阻塞过久</li>
 *   <li>不设置版本（默认 HTTP/2） —— 简化配置；需要 HTTP/1.1 在 Tool 内部 {@code .version(...)} 显式覆盖</li>
 * </ul>
 *
 * <p>US-4 / 005-tool-system 阶段用途：
 * <ul>
 *   <li>{@code HttpTools} 调外部 HTTP API</li>
 *   <li>{@code HttpMcpTransport} 通过 SSE 与 MCP server 通信</li>
 * </ul>
 *
 * <p>为什么不放在 {@code oryxos-tool}：保持 {@code oryxos-tool} 模块对 Spring
 * 自动配置的依赖最小化（不让工具模块决定 connectTimeout），把"装配决策"统一收到
 * {@code oryxos-boot}（与 {@code NotifyToolConfig} 同模式）。
 */
@Configuration
public class HttpClientConfig {

    /** 默认 5 秒连接超时 —— 弱依赖场景下的合理上限（5s 仍是悲观值，正常 RTT < 200ms）。 */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 共享 {@link HttpClient} Bean。所有 HTTP 出站 Tool 注入这个实例。
     */
    @Bean
    public HttpClient sharedHttpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
            .build();
    }
}


