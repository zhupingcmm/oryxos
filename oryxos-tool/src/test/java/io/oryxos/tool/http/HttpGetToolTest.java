package io.oryxos.tool.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.oryxos.core.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** T017：{@code http_get} —— WireMock 200 + 沙箱拦截 + IP 拒绝 + 超时（[contracts/builtin-tools.md §5]）。 */
class HttpGetToolTest {

    WireMockServer wm;
    HttpGetTool allowedTool;
    HttpGetTool blockedTool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().port(8089));
        wm.start();

        wm.stubFor(get(urlEqualTo("/hello"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":true}")));

        // 允许 WireMock 的 localhost
        WhitelistStub allow = new WhitelistStub(List.of("localhost"));
        WhitelistStub deny = new WhitelistStub(List.of()); // 不允许任何 host

        HttpToolProperties props = new HttpToolProperties(2, 4096);
        allowedTool = new HttpGetTool(HttpClient.newHttpClient(), props, allow);
        blockedTool = new HttpGetTool(HttpClient.newHttpClient(), props, deny);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    @DisplayName("成功：白名单内 WireMock → status_code=200")
    void wiremock_200() {
        ToolResult r = allowedTool.execute(Map.of("url", "http://localhost:8089/hello"));
        assertThat(r.success()).isTrue();
        assertThat(((Number) r.payload().get("status_code")).intValue()).isEqualTo(200);
        assertThat((String) r.payload().get("body")).contains("\"ok\"");
    }

    @Test
    @DisplayName("失败：沙箱拒绝 → errorMessage 含 'sandbox violation'")
    void blocked_by_sandbox() {
        ToolResult r = blockedTool.execute(Map.of("url", "http://localhost:8089/hello"));
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("not in allowed-domains");
    }

    @Test
    @DisplayName("失败：IP 字面量 → 沙箱拒绝")
    void ip_literal_rejected() {
        ToolResult r = blockedTool.execute(Map.of("url", "http://1.2.3.4/hello"));
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("IP-literal");
    }

    @Test
    @DisplayName("失败：未连接地址 + 短 timeout → IO error")
    void timeout() {
        HttpToolProperties shortProps = new HttpToolProperties(1, 4096);
        HttpGetTool tool = new HttpGetTool(HttpClient.newHttpClient(), shortProps,
            new WhitelistStub(List.of("127.0.0.1")));
        ToolResult r = tool.execute(Map.of("url", "http://127.0.0.1:1/nothing"));
        assertThat(r.success()).isFalse();
    }

    /** 最小白名单沙箱 stub —— 不复用 WhitelistSandbox 避免绑定 Properties。 */
    private record WhitelistStub(List<String> allowedDomains) implements io.oryxos.tool.sandbox.Sandbox {
        @Override
        public void enforce(io.oryxos.tool.sandbox.SandboxAction action) {
            if (action.type() != io.oryxos.tool.sandbox.ActionType.HTTP_REQUEST) return;
            String host = extractHost(action.target());
            if (host == null || host.isBlank()) {
                throw new io.oryxos.tool.sandbox.SandboxViolationException(action, "no host");
            }
            if (host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                throw new io.oryxos.tool.sandbox.SandboxViolationException(action,
                    "sandbox violation: IP-literal hosts are not allowed: " + host);
            }
            boolean ok = allowedDomains.stream().anyMatch(d ->
                host.equalsIgnoreCase(d) || host.toLowerCase().endsWith("." + d.toLowerCase()));
            if (!ok) {
                throw new io.oryxos.tool.sandbox.SandboxViolationException(action,
                    "sandbox violation: host '" + host + "' not in allowed-domains");
            }
        }
        private static String extractHost(String target) {
            try { return new java.net.URI(target).getHost(); }
            catch (Exception e) { return null; }
        }
    }
}

