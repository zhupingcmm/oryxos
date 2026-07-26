package io.oryxos.tool.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.NotifyChannelConfig;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Sandbox 拦截测试 —— T043（US-3 P2）。
 *
 * <p>验证 spec FR-007 + FR-008：WebhookNotifyAdapter 必须先调
 * {@link io.oryxos.tool.sandbox.Sandbox#enforce}，再发起 HTTP 请求。
 * 白名单外 / IP 字面量 host 被拦截后，{@link HttpClient#send} 调用次数必须为 0。
 *
 * <p>不依赖 WireMock —— 直接 mock HttpClient 验证 send() 调用计数。
 */
class NotifyToolSandboxTest {

    /** 仅放行 allowed.example.com 子树的 Sandbox。 */
    private WhitelistSandbox sandbox;

    private HttpClient mockHttpClient;

    @BeforeEach
    void setUp() {
        sandbox = new WhitelistSandbox(java.util.List.of("allowed.example.com"));
        mockHttpClient = mock(HttpClient.class);
    }

    private WebhookNotifyAdapter adapter() {
        return new WebhookNotifyAdapter(sandbox, mockHttpClient, new ObjectMapper());
    }

    private NotifyChannelConfig ch(String name, String url) {
        return new NotifyChannelConfig(name, "webhook", url, null);
    }

    private HttpResponse<String> okResponse() {
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        doReturn(200).when(resp).statusCode();
        doReturn("{\"ok\":1}").when(resp).body();
        return resp;
    }

    @Test
    void allowedDomainTriggersHttpSend() throws Exception {
        doReturn(okResponse()).when(mockHttpClient)
            .send(any(HttpRequest.class), any());

        NotifyResult result = adapter().send(ch("default",
            "https://allowed.example.com/hook"), "hi");

        assertThat(result.success()).isTrue();
        assertThat(result.statusCode()).isEqualTo(200);
        verify(mockHttpClient).send(any(HttpRequest.class), any());
    }

    @Test
    void disallowedDomainShortCircuitsBeforeHttp() throws Exception {
        NotifyResult result = adapter().send(ch("default",
            "https://evil.example.com/hook"), "hi");

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isNull();
        assertThat(result.errorMessage()).contains("sandbox violation");
        assertThat(result.errorMessage()).contains("evil.example.com");
        // 关键：HttpClient.send 一次都没被调
        verify(mockHttpClient, never()).send(any(HttpRequest.class), any());
    }

    @Test
    void ipLiteralUrlShortCircuitsBeforeHttp() throws Exception {
        NotifyResult result = adapter().send(ch("default",
            "http://127.0.0.1:8089/hook"), "hi");

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isNull();
        assertThat(result.errorMessage()).contains("sandbox violation");
        assertThat(result.errorMessage()).contains("IP-literal");
        verify(mockHttpClient, never()).send(any(HttpRequest.class), any());
    }

    @Test
    void ipv6LiteralUrlShortCircuitsBeforeHttp() throws Exception {
        NotifyResult result = adapter().send(ch("default",
            "http://[::1]:8080/hook"), "hi");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("sandbox violation");
        verify(mockHttpClient, never()).send(any(HttpRequest.class), any());
    }

    @Test
    void subdomainOfAllowedDomainPasses() throws Exception {
        doReturn(okResponse()).when(mockHttpClient).send(any(HttpRequest.class), any());

        NotifyResult result = adapter().send(ch("default",
            "https://api.allowed.example.com/v1/hook"), "hi");

        assertThat(result.success()).isTrue();
        verify(mockHttpClient).send(any(HttpRequest.class), any());
    }

    @Test
    void emptyWhitelistBlocksEverything() throws Exception {
        // 默认空白名单：所有 host 都被拒绝（含 localhost）
        WhitelistSandbox empty = new WhitelistSandbox(java.util.List.of());
        WebhookNotifyAdapter strictAdapter = new WebhookNotifyAdapter(empty,
            mockHttpClient, new ObjectMapper());

        NotifyResult result = strictAdapter.send(ch("default",
            "https://api.openweathermap.org/data"), "weather");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("sandbox violation");
        verify(mockHttpClient, never()).send(any(HttpRequest.class), any());
    }
}