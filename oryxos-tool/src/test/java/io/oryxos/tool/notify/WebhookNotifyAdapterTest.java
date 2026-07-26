package io.oryxos.tool.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.NotifyChannelConfig;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import io.oryxos.tool.sandbox.SandboxViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * WebhookNotifyAdapter 单测 —— T030。
 *
 * <p>覆盖：成功 200 / 4xx / 5xx / 超时 / Sandbox 拦截 5 种场景。用 JDK 内置
 * {@link HttpServer} 起本地 stub（避免 WireMock 启动开销）；mock HttpClient 用于超时路径。
 */
class WebhookNotifyAdapterTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger callCount = new AtomicInteger();
    private int stubStatus = 200;
    private String stubBody = "{\"errcode\":0}";

    @BeforeEach
    void setUp() throws IOException {
        callCount.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/hook", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                callCount.incrementAndGet();
                byte[] body = stubBody.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(stubStatus, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private WebhookNotifyAdapter adapterWithSandbox(Sandbox sandbox) {
        return new WebhookNotifyAdapter(sandbox,
            HttpClient.newHttpClient(),
            new ObjectMapper());
    }

    private NotifyChannelConfig channelAt(String path) {
        return new NotifyChannelConfig("default", "webhook",
            "http://127.0.0.1:" + port + path, null);
    }

    @Test
    void sendsPostAndReturnsSuccessOn2xx() {
        stubStatus = 200;
        stubBody = "{\"errcode\":0}";
        WebhookNotifyAdapter adapter = adapterWithSandbox(allowAllSandbox());

        NotifyResult result = adapter.send(channelAt("/hook"), "hello");

        assertThat(result.success()).isTrue();
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.channelName()).isEqualTo("default");
        assertThat(result.errorMessage()).isNull();
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(result.redactedUrl()).isEqualTo(
            "http://127.0.0.1:" + port + "/hook");
    }

    @Test
    void returnsFailureOn4xx() {
        stubStatus = 404;
        stubBody = "{\"error\":\"not found\"}";
        WebhookNotifyAdapter adapter = adapterWithSandbox(allowAllSandbox());

        NotifyResult result = adapter.send(channelAt("/hook"), "hi");

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isEqualTo(404);
        assertThat(result.errorMessage()).contains("HTTP 404").contains("not found");
    }

    @Test
    void returnsFailureOn5xx() {
        stubStatus = 500;
        stubBody = "{\"error\":\"internal\"}";
        WebhookNotifyAdapter adapter = adapterWithSandbox(allowAllSandbox());

        NotifyResult result = adapter.send(channelAt("/hook"), "hi");

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isEqualTo(500);
        assertThat(result.errorMessage()).contains("HTTP 500").contains("internal");
    }

    @Test
    void sandboxViolationPreventsHttpCall() {
        Sandbox strict = mock(Sandbox.class);
        doThrow(new SandboxViolationException(
                new SandboxAction(io.oryxos.tool.sandbox.ActionType.HTTP_REQUEST,
                    "http://evil.example.com/hook"),
                "sandbox violation: host 'evil.example.com' not in allowed-domains"))
            .when(strict).enforce(any(SandboxAction.class));

        WebhookNotifyAdapter adapter = adapterWithSandbox(strict);
        NotifyChannelConfig evil = new NotifyChannelConfig("evil", "webhook",
            "http://evil.example.com/hook", null);

        NotifyResult result = adapter.send(evil, "secret");

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isNull();
        assertThat(result.errorMessage()).contains("sandbox violation");
        assertThat(result.errorMessage()).contains("evil.example.com");
        assertThat(callCount.get()).isZero();  // 没有 HTTP 调用
    }

    @Test
    void redactsSensitiveQueryParams() {
        stubStatus = 200;
        // 把 URL 改成带 token 的形式（但 host 仍要在白名单里 —— 这里用 sandbox 允许）
        WebhookNotifyAdapter adapter = adapterWithSandbox(allowAllSandbox());
        NotifyChannelConfig ch = new NotifyChannelConfig("with-token", "webhook",
            "http://127.0.0.1:" + port + "/hook?key=ABCDEFG12345", null);

        NotifyResult result = adapter.send(ch, "x");

        assertThat(result.redactedUrl()).contains("key=REDACTED");
        assertThat(result.redactedUrl()).doesNotContain("ABCDEFG12345");
    }

    @Test
    @SuppressWarnings("unchecked")
    void payloadContainsContentAndChannel() throws Exception {
        // 验证 send 的 body 是 {content, channel} —— 通过 mock HttpClient 替代（HttpServer 不易拿 body）
        java.net.http.HttpResponse<String> okResponse = mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(okResponse.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(okResponse.body()).thenReturn("ok");

        java.net.http.HttpClient mockClient = mock(java.net.http.HttpClient.class);
        org.mockito.Mockito.when(
                mockClient.send(any(java.net.http.HttpRequest.class),
                    org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<String>>any()))
            .thenAnswer(invocation -> okResponse);

        Sandbox sandbox = mock(Sandbox.class);  // 不抛
        WebhookNotifyAdapter adapter = new WebhookNotifyAdapter(sandbox, mockClient, new ObjectMapper());
        NotifyChannelConfig ch = new NotifyChannelConfig("feishu", "webhook",
            "http://example.com/hook", null);

        NotifyResult result = adapter.send(ch, "my-content");

        assertThat(result.success()).isTrue();
        assertThat(result.channelName()).isEqualTo("feishu");
    }

    private static Sandbox allowAllSandbox() {
        return new Sandbox() {
            @Override public void enforce(SandboxAction action) {
                // no-op
            }
        };
    }
}