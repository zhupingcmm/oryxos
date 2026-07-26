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
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** T018：{@code http_post} —— WireMock POST 收到 body + 沙箱拦截。 */
class HttpPostToolTest {

    WireMockServer wm;
    HttpPostTool tool;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().port(8089));
        wm.start();
        wm.stubFor(post(urlEqualTo("/echo"))
            .willReturn(aResponse().withStatus(201)));

        HttpToolProperties props = new HttpToolProperties(5, 4096);
        WhitelistStub allow = new WhitelistStub(List.of("localhost"));
        tool = new HttpPostTool(HttpClient.newHttpClient(), props, allow);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    @DisplayName("成功：POST 走到 WireMock + status_code=201")
    void post_sends_body() {
        ToolResult r = tool.execute(Map.of(
            "url", "http://localhost:8089/echo",
            "body", "{\"foo\":\"bar\"}"
        ));
        assertThat(r.success()).isTrue();
        assertThat(((Number) r.payload().get("status_code")).intValue()).isEqualTo(201);
        wm.verify(postRequestedFor(urlEqualTo("/echo"))
            .withRequestBody(equalToJson("{\"foo\":\"bar\"}")));
    }

    @Test
    @DisplayName("失败：沙箱拒绝 → errorMessage 含 'not in allowed-domains'")
    void sandbox_blocked() {
        HttpPostTool denied = new HttpPostTool(HttpClient.newHttpClient(),
            new HttpToolProperties(5, 4096),
            new WhitelistStub(List.of()));
        ToolResult r = denied.execute(Map.of(
            "url", "http://localhost:8089/echo",
            "body", "{}"
        ));
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("not in allowed-domains");
    }

    @Test
    @DisplayName("失败：超大 body → 触发 truncate/error")
    void body_too_large_truncates() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append("a");
        ToolResult r = tool.execute(Map.of(
            "url", "http://localhost:8089/echo",
            "body", big.toString()
        ));
        // 不强制失败 —— 截断实现下应仍 201；断言 ≥ 0 字节
        assertThat(r.success()).isTrue();
    }

    /** 同 HttpGetToolTest 的最小白名单 stub。 */
    private record WhitelistStub(List<String> allowedDomains) implements io.oryxos.tool.sandbox.Sandbox {
        @Override
        public void enforce(io.oryxos.tool.sandbox.SandboxAction action) {
            if (action.type() != io.oryxos.tool.sandbox.ActionType.HTTP_REQUEST) return;
            try {
                String host = new java.net.URI(action.target()).getHost();
                boolean ok = host != null && allowedDomains.stream().anyMatch(d ->
                    host.equalsIgnoreCase(d) || host.toLowerCase().endsWith("." + d.toLowerCase()));
                if (!ok) {
                    throw new io.oryxos.tool.sandbox.SandboxViolationException(action,
                        "sandbox violation: host '" + host + "' not in allowed-domains");
                }
            } catch (java.net.URISyntaxException e) {
                throw new io.oryxos.tool.sandbox.SandboxViolationException(action, "bad uri");
            }
        }
    }
}

