package io.oryxos.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.oryxos.tool.sandbox.Sandbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T047 —— HttpMcpTransport 契约测试。
 *
 * <p>覆盖 [contracts/mcp-adapter.md §10](../../../../../../../specs/005-tool-system/contracts/mcp-adapter.md)：
 * <ol>
 *   <li>{@code sendRequest-success} —— 200 响应正确解析为 {@link McpResponse}</li>
 *   <li>{@code sendRequest-connection-fail} —— WireMock 关闭后 connect-fail 抛 {@link McpConnectionException}</li>
 *   <li>{@code sendRequest-timeout} —— 1s timeout 阻塞时 {@link McpConnectionException}</li>
 *   <li>{@code close-clears-resources} —— close() 幂等无副作用</li>
 * </ol>
 */
class HttpMcpTransportTest {

    WireMockServer wm;
    HttpMcpTransport transport;
    int wmPort;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        wmPort = wm.port();
        Sandbox allow = action -> { };
        McpServerConfig cfg = new McpServerConfig(
            "test-http", "http", null, java.util.List.of(),
            "http://localhost:" + wmPort + "/mcp", null, java.util.Map.of());
        transport = new HttpMcpTransport(
            HttpClient.newHttpClient(), new ObjectMapper(), allow, cfg, 5);
    }

    @AfterEach
    void tearDown() {
        if (transport != null) transport.close();
        if (wm != null) wm.stop();
    }

    @Test
    @DisplayName("sendRequest-success: 200 JSON → McpResponse 正确解析")
    void sendRequest_success() {
        wm.stubFor(post(urlEqualTo("/mcp")).willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}")));

        McpResponse resp = transport.sendRequest("initialize", Map.of(
            "protocolVersion", "2024-11-05"));
        assertThat(resp.isError()).isFalse();
        assertThat(resp.id()).isEqualTo(1);
        assertThat(resp.result()).containsEntry("ok", true);
    }

    @Test
    @DisplayName("sendRequest-connection-fail: 远端关闭后抛 McpConnectionException")
    void sendRequest_connection_fail() {
        McpServerConfig badCfg = new McpServerConfig(
            "bad", "http", null, java.util.List.of(),
            "http://127.0.0.1:1/mcp", null, java.util.Map.of());
        HttpMcpTransport bad = new HttpMcpTransport(
            HttpClient.newHttpClient(), new ObjectMapper(),
            action -> { }, badCfg, 1);
        assertThatThrownBy(() -> bad.sendRequest("tools/list", Map.of()))
            .isInstanceOf(McpConnectionException.class);
    }

    @Test
    @DisplayName("sendRequest-timeout: 1s timeout 服务延迟 2s → McpConnectionException")
    void sendRequest_timeout() {
        wm.stubFor(post(urlEqualTo("/mcp")).willReturn(aResponse()
            .withStatus(200)
            .withFixedDelay(2500)
            .withBody("{}")));
        McpServerConfig slowCfg = new McpServerConfig(
            "slow", "http", null, java.util.List.of(),
            "http://localhost:" + wmPort + "/mcp", null, java.util.Map.of());
        HttpMcpTransport slow = new HttpMcpTransport(
            HttpClient.newHttpClient(), new ObjectMapper(),
            action -> { }, slowCfg, 1);
        assertThatThrownBy(() -> slow.sendRequest("tools/list", Map.of()))
            .isInstanceOf(McpConnectionException.class)
            .hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("close-clears-resources: 幂等无副作用")
    void close_idempotent() {
        transport.close();
        transport.close(); // 第二次幂等
    }

    @Test
    @DisplayName("POST body 是 JSON-RPC envelope: id + method + params")
    void post_body_is_json_rpc_envelope() {
        wm.stubFor(post(urlEqualTo("/mcp")).willReturn(aResponse()
            .withStatus(200)
            .withBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")));

        transport.sendRequest("tools/call", Map.of("name", "echo", "arguments", Map.of("text", "hi")));

        wm.verify(postRequestedFor(urlEqualTo("/mcp"))
            .withRequestBody(equalToJson(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}}",
                true, true)));
    }
}
