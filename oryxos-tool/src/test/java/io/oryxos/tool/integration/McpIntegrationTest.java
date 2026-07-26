package io.oryxos.tool.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.tool.mcp.McpClientProperties;
import io.oryxos.tool.mcp.McpClientService;
import io.oryxos.tool.mcp.McpConnectionException;
import io.oryxos.tool.mcp.McpToolAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T065 —— MCP end-to-end 集成测试（[quickstart.md §7](../../../../../../../specs/005-tool-system/quickstart.md)）。
 *
 * <p>场景：用 WireMock 模拟一个 MCP server（HTTP + JSON-RPC），验证：
 * <ol>
 *   <li>{@code McpClientService.startup()} 握手 + register 成功</li>
 *   <li>Tool 通过 {@link OryxTool#execute} 触发远端调用</li>
 *   <li>连接断开（WireMock stop）后再次调用 → ToolResult.error</li>
 * </ol>
 */
class McpIntegrationTest {

    WireMockServer wm;
    Path tmpConfig;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (wm != null) wm.stop();
        if (tmpConfig != null) Files.deleteIfExists(tmpConfig);
    }

    @Test
    @DisplayName("E2E: handshake + register + tool execute 走完整路径")
    void full_integration_path() throws IOException {
        // Mock MCP server 暴露 2 个工具：echo、add
        wm.stubFor(post(urlEqualTo("/mcp"))
            .withRequestBody(matchingJsonPath("$.method", equalTo("initialize")))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")));
        wm.stubFor(post(urlEqualTo("/mcp"))
            .withRequestBody(matchingJsonPath("$.method", equalTo("tools/list")))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":["
                    + "{\"name\":\"echo\",\"description\":\"回显输入\",\"inputSchema\":\"{}\"},"
                    + "{\"name\":\"add\",\"description\":\"两数相加\",\"inputSchema\":\"{}\"}"
                    + "]}}")));
        wm.stubFor(post(urlEqualTo("/mcp"))
            .withRequestBody(matchingJsonPath("$.method", equalTo("tools/call")))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"echo\":\"hi-from-mcp\"}}")));

        Path cfg = writeHttpConfig("integration", "http://localhost:" + wm.port() + "/mcp");
        ToolRegistry registry = ToolRegistry.of(Map.of());
        McpClientService svc = newService(registry, cfg.toString(), true);
        svc.startup();

        assertThat(registry.find("integration__echo")).isPresent();
        assertThat(registry.find("integration__add")).isPresent();
        assertThat(registry.size()).isEqualTo(2);

        OryxTool echo = registry.find("integration__echo").orElseThrow();
        ToolResult r = echo.execute(Map.of("text", "hello"));
        assertThat(r.success()).isTrue();
        assertThat(r.payload()).containsEntry("echo", "hi-from-mcp");

        svc.shutdown();
    }

    @Test
    @DisplayName("connection-lost → ToolResult.error: 服务消失后 Tool 调用不抛")
    void connection_lost_after_register() throws IOException {
        wm.stubFor(post(urlEqualTo("/mcp"))
            .withRequestBody(matchingJsonPath("$.method", equalTo("initialize")))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")));
        wm.stubFor(post(urlEqualTo("/mcp"))
            .withRequestBody(matchingJsonPath("$.method", equalTo("tools/list")))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":["
                    + "{\"name\":\"echo\",\"description\":\"d\",\"inputSchema\":\"{}\"}]}}")));

        Path cfg = writeHttpConfig("disconnect", "http://localhost:" + wm.port() + "/mcp");
        ToolRegistry registry = ToolRegistry.of(Map.of());
        McpClientService svc = newService(registry, cfg.toString(), true);
        svc.startup();

        // 关闭 server 模拟连接丢失
        wm.stop();
        wm = null;

        OryxTool echo = registry.find("disconnect__echo").orElseThrow();
        ToolResult r = echo.execute(Map.of("text", "x"));
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("mcp connection failed");
    }

    @Test
    @DisplayName("fail-fast: 任何 server 不可达 → McpConnectionException 启动失败")
    void fail_fast_on_unreachable_server() throws IOException {
        Path cfg = writeHttpConfig("unreach",
            "http://127.0.0.1:1/mcp");  // 故意连不上
        ToolRegistry registry = ToolRegistry.of(Map.of());
        McpClientService svc = newService(registry, cfg.toString(), true);

        assertThatThrownBy(svc::startup)
            .isInstanceOf(McpConnectionException.class);
    }

    private Path writeHttpConfig(String name, String url) throws IOException {
        Path p = Files.createTempFile("mcp-int-", ".yaml");
        Files.writeString(p,
            "servers:\n  - name: " + name + "\n    transport: http\n    url: " + url + "\n");
        tmpConfig = p;
        return p;
    }

    private McpClientService newService(ToolRegistry registry, String yamlPath, boolean failFast) {
        McpClientProperties props = new McpClientProperties(5, 5, failFast, List.of());
        return new McpClientService(
            props,
            new ObjectMapper(),
            new McpToolAdapter(),
            registry,
            List.of(HttpClient::newHttpClient),
            yamlPath,
            failFast,
            new McpClientService.SandboxProvider.Noop());
    }
}
