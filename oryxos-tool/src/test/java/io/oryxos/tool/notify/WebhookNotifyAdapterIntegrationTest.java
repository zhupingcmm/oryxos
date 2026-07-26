package io.oryxos.tool.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.oryxos.core.NotifyChannelConfig;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import io.oryxos.tool.sandbox.SandboxViolationException;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebhookNotifyAdapter 集成测试 —— T035。
 *
 * <p>用 WireMock 起一个真实的 HTTP server 端，覆盖 spec §FR-007..013 + SC-004 全路径：
 * <ol>
 *   <li>200 OK → success=true, statusCode=200, payload 是 {content, channel}</li>
 *   <li>4xx → success=false, statusCode=4xx, errorMessage 含 body 前 256 字节</li>
 *   <li>5xx → success=false, statusCode=5xx, errorMessage 含 body 前 256 字节</li>
 *   <li>请求体是 {@code {"content":"...","channel":"..."}}</li>
 *   <li>Content-Type 是 {@code application/json; charset=utf-8}</li>
 *   <li>User-Agent 是 {@code OryxOS-Notify/1.0}</li>
 *   <li>Sandbox 拒绝的 host 不发出 HTTP 请求</li>
 *   <li>慢响应（延迟 6s）→ 5s 超时，返回 timeout NotifyResult</li>
 * </ol>
 *
 * <p>为什么用 WireMock 而不是 JDK HttpServer：WireMock 提供 verify() / matching JSON /
 * 可控延迟等高级断言，集成测试更接近"真实 webhook"的语义。
 */
class WebhookNotifyAdapterIntegrationTest {

    private static WireMockServer wireMock;
    private static int port;

    private WebhookNotifyAdapter adapter;
    private ObjectMapper objectMapper;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options()
            .dynamicPort()
            .bindAddress("127.0.0.1"));
        wireMock.start();
        port = wireMock.port();
        // 把 WireMock 静态客户端指向我们启的 server（动态端口）—— 不指的话会用默认 8080
        WireMock.configureFor("127.0.0.1", port);
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
        objectMapper = new ObjectMapper();
        // 集成测试只验证 HTTP 路径；sandbox 行为在单元测试（WebhookNotifyAdapterTest）已覆盖。
        // 这里用一个"允许所有 host"的 sandbox（因为 WireMock 绑 127.0.0.1，IP 字面值会被 WhitelistSandbox 默认拒绝）。
        Sandbox permissive = new Sandbox() {
            @Override public void enforce(SandboxAction action) {
                // no-op
            }
        };
        adapter = new WebhookNotifyAdapter(
            permissive,
            HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build(),
            objectMapper);
    }

    @AfterEach
    void cleanup() {
        wireMock.resetAll();
    }

    private NotifyChannelConfig channel(String name, String path) {
        return new NotifyChannelConfig(name, "webhook",
            "http://127.0.0.1:" + port + path, null);
    }

    @Test
    void successOn2xxReturnsSuccessResult() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/hook"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"errcode\":0,\"errmsg\":\"ok\"}")));

        NotifyResult result = adapter.send(channel("default", "/hook"), "hello world");

        assertThat(result.success()).isTrue();
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.channelName()).isEqualTo("default");
        assertThat(result.errorMessage()).isNull();
        assertThat(result.redactedUrl()).contains("127.0.0.1:" + port + "/hook");
    }

    @Test
    void failureOn4xx() {
        wireMock.stubFor(post(urlEqualTo("/hook"))
            .willReturn(aResponse().withStatus(404)
                .withBody("{\"error\":\"not found\"}")));

        NotifyResult result = adapter.send(channel("default", "/hook"), "x");

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isEqualTo(404);
        assertThat(result.errorMessage()).contains("HTTP 404").contains("not found");
    }

    @Test
    void failureOn5xx() {
        wireMock.stubFor(post(urlEqualTo("/hook"))
            .willReturn(aResponse().withStatus(500)
                .withBody("{\"error\":\"internal server error\"}")));

        NotifyResult result = adapter.send(channel("default", "/hook"), "x");

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isEqualTo(500);
        assertThat(result.errorMessage()).contains("HTTP 500");
    }

    @Test
    void payloadContainsContentAndChannel() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/hook"))
            .willReturn(aResponse().withStatus(200).withBody("{}")));

        adapter.send(channel("feishu", "/hook"), "my-content");

        // 验证 POST body 是 {"content":"my-content","channel":"feishu"}
        verify(postRequestedFor(urlEqualTo("/hook"))
            .withRequestBody(matchingJsonPath("$.content", equalTo("my-content")))
            .withRequestBody(matchingJsonPath("$.channel", equalTo("feishu"))));
    }

    @Test
    void contentTypeHeaderIsJson() {
        wireMock.stubFor(post(urlEqualTo("/hook"))
            .willReturn(aResponse().withStatus(200).withBody("{}")));

        adapter.send(channel("default", "/hook"), "x");

        verify(postRequestedFor(urlEqualTo("/hook"))
            .withHeader("Content-Type", containing("application/json")));
    }

    @Test
    void userAgentHeaderIsSet() {
        wireMock.stubFor(post(urlEqualTo("/hook"))
            .willReturn(aResponse().withStatus(200).withBody("{}")));

        adapter.send(channel("default", "/hook"), "x");

        verify(postRequestedFor(urlEqualTo("/hook"))
            .withHeader("User-Agent", equalTo("OryxOS-Notify/1.0")));
    }

    @Test
    void sandboxViolationShortCircuitsHttp() {
        // 用一个 deny-everything 沙箱，验证不发任何 HTTP 请求
        Sandbox strict = new Sandbox() {
            @Override public void enforce(SandboxAction action) {
                throw new SandboxViolationException(action,
                    "sandbox violation: test deny");
            }
        };
        WebhookNotifyAdapter strictAdapter = new WebhookNotifyAdapter(
            strict, HttpClient.newHttpClient(), new ObjectMapper());

        NotifyResult result = strictAdapter.send(channel("x", "/hook"), "secret");

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isNull();
        assertThat(result.errorMessage()).contains("sandbox violation");
        // WireMock 端没有收到任何请求
        verify(0, postRequestedFor(urlEqualTo("/hook")));
    }

    @Test
    void slowResponseTriggersTimeout() {
        // 延迟 6s 响应 → 5s 超时（spec SC-004 / NFR-001）
        wireMock.stubFor(post(urlEqualTo("/slow"))
            .willReturn(aResponse().withStatus(200)
                .withFixedDelay(6000)
                .withBody("late")));

        NotifyResult result = adapter.send(channel("slow", "/slow"), "x");

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isNull();
        assertThat(result.errorMessage()).contains("timeout");
        // 实际耗时 ~5s（不会等到 6s）
        assertThat(result.durationMs()).isLessThan(6000L);
    }
}