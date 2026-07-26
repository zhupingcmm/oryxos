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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 不重试语义测试 —— T060（Polish 阶段）。
 *
 * <p>spec FR-011：核心阶段 MUST NOT 重试 HTTP。
 *
 * <p>断言：HTTP 第一次返回 500 时，{@link HttpClient#send} 调用次数**恰好**为 1（不是 2/3/N）。
 * 若实现里以后误加重试逻辑（指数退避 / 简单 retry），本测试会失败。
 */
class NoRetrySemanticsTest {

    private HttpClient mockHttpClient;
    private WebhookNotifyAdapter adapter;

    @BeforeEach
    void setUp() {
        mockHttpClient = mock(HttpClient.class);
        // 沙箱放行 localhost 走 5xx
        WhitelistSandbox sandbox = new WhitelistSandbox(java.util.List.of("localhost"));
        adapter = new WebhookNotifyAdapter(sandbox, mockHttpClient, new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        doReturn(status).when(resp).statusCode();
        doReturn(body).when(resp).body();
        return resp;
    }

    @Test
    void http500IsNotRetried() throws Exception {
        doReturn(response(500, "{\"errcode\":50000}"))
            .when(mockHttpClient).send(any(HttpRequest.class), any());

        NotifyResult result = adapter.send(
            new NotifyChannelConfig("default", "webhook",
                "http://localhost:9999/hook", null),
            "x");

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isEqualTo(500);
        // 关键：send 调用次数必须为 1（不重试）
        verify(mockHttpClient, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    void http400IsNotRetried() throws Exception {
        doReturn(response(400, "{\"error\":\"bad request\"}"))
            .when(mockHttpClient).send(any(HttpRequest.class), any());

        NotifyResult result = adapter.send(
            new NotifyChannelConfig("default", "webhook",
                "http://localhost:9999/hook", null),
            "x");

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isEqualTo(400);
        verify(mockHttpClient, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    void httpTimeoutIsNotRetried() throws Exception {
        // 第一次抛 HttpTimeoutException
        org.mockito.Mockito.doThrow(new java.net.http.HttpTimeoutException("timeout"))
            .when(mockHttpClient).send(any(HttpRequest.class), any());

        NotifyResult result = adapter.send(
            new NotifyChannelConfig("default", "webhook",
                "http://localhost:9999/hook", null),
            "x");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("timeout");
        // 关键：超时也不重试
        verify(mockHttpClient, times(1)).send(any(HttpRequest.class), any());
    }
}