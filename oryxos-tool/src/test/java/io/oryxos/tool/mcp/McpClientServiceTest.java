package io.oryxos.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.oryxos.core.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T049 —— McpClientService 契约测试。
 *
 * <p>覆盖 [contracts/mcp-adapter.md §5.2](../../../../../../../specs/005-tool-system/contracts/mcp-adapter.md)：
 * <ol>
 *   <li>{@code startup-handshake-succeeds}</li>
 *   <li>{@code startup-server-unreachable-fails-fast}</li>
 *   <li>{@code startup-protocol-mismatch-fails}</li>
 *   <li>{@code list-tools-returns-descriptors}</li>
 * </ol>
 */
class McpClientServiceTest {

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
    @DisplayName("startup-handshake-succeeds: stub → McpClientService 注册 Tools 不抛")
    void startup_succeeds() throws IOException {
        // initialize → success; tools/list → 1 tool
        wm.stubFor(post(urlEqualTo("/mcp"))
            .withRequestBody(matchingJsonPath("$.method", equalTo("initialize")))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")));
        wm.stubFor(post(urlEqualTo("/mcp"))
            .withRequestBody(matchingJsonPath("$.method", equalTo("tools/list")))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":"
                    + "[{\"name\":\"echo\",\"description\":\"d\",\"inputSchema\":\"{}\"}]}}")));

        Path cfgFile = writeHttpConfig("http-server", "http://localhost:" + wm.port() + "/mcp");
        ToolRegistry registry = ToolRegistry.of(java.util.Map.of());
        McpClientService svc = newService(registry, cfgFile.toString(), true);
        svc.startup();

        assertThat(registry.find("http-server__echo")).isPresent();
        assertThat(registry.size()).isEqualTo(1);
        svc.shutdown();
    }

    @Test
    @DisplayName("startup-server-unreachable-fails-fast: 不可达端口 → McpConnectionException")
    void startup_server_unreachable() throws IOException {
        Path cfgFile = writeHttpConfig("bad", "http://127.0.0.1:1/mcp");
        ToolRegistry registry = ToolRegistry.of(java.util.Map.of());
        McpClientService svc = newService(registry, cfgFile.toString(), true);
        assertThatThrownBy(svc::startup).isInstanceOf(McpConnectionException.class);
    }

    @Test
    @DisplayName("startup-protocol-mismatch-fails: initialize 返回 error → McpConnectionException")
    void startup_protocol_mismatch() throws IOException {
        wm.stubFor(post(urlEqualTo("/mcp"))
            .withRequestBody(matchingJsonPath("$.method", equalTo("initialize")))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":"
                    + "{\"code\":-32600,\"message\":\"unsupported protocol\"}}")));

        Path cfgFile = writeHttpConfig("mismatch", "http://localhost:" + wm.port() + "/mcp");
        ToolRegistry registry = ToolRegistry.of(java.util.Map.of());
        McpClientService svc = newService(registry, cfgFile.toString(), true);
        assertThatThrownBy(svc::startup).isInstanceOf(McpConnectionException.class)
            .hasMessageContaining("initialize failed");
    }

    @Test
    @DisplayName("list-tools-returns-descriptors: tools/list 返回真实 descriptor")
    void list_tools_returns_descriptors() throws IOException {
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
                    + "{\"name\":\"a\",\"description\":\"A\",\"inputSchema\":\"{}\"},"
                    + "{\"name\":\"b\",\"description\":\"B\",\"inputSchema\":\"{}\"}"
                    + "]}}")));

        Path cfgFile = writeHttpConfig("t", "http://localhost:" + wm.port() + "/mcp");
        ToolRegistry registry = ToolRegistry.of(java.util.Map.of());
        McpClientService svc = newService(registry, cfgFile.toString(), true);
        svc.startup();

        assertThat(registry.find("t__a")).isPresent();
        assertThat(registry.find("t__b")).isPresent();
        svc.shutdown();
    }

    private Path writeHttpConfig(String name, String url) throws IOException {
        Path p = Files.createTempFile("mcp-servers-", ".yaml");
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
