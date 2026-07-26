package io.oryxos.tool.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.NotifyChannelConfig;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import io.oryxos.tool.sandbox.SandboxViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP POST 出站适配器 —— 单条通道的实际发送。
 *
 * <p>核心流程（spec FR-007..013 + contracts/webhook-payload.md）：
 * <ol>
 *   <li>{@link Sandbox#enforce} 先校验 host 白名单（<strong>在 HTTP 请求前</strong>）</li>
 *   <li>构造 JSON payload：{@code {"content": "...", "channel": "<name>"}}</li>
 *   <li>JDK {@link HttpClient#send} POST，5s 超时</li>
 *   <li>判定状态码：2xx → success=true；其他 → success=false（含 response body 前 256 字节）</li>
 *   <li>URL 经 {@link UrlRedactor} 脱敏后写入 {@link NotifyResult#redactedUrl()}</li>
 * </ol>
 *
 * <p>核心阶段 MUST NOT 重试（FR-011）—— 一次 send 即终态。
 *
 * <p>详见 <a href="../../../../../../../specs/004-notify-channel/contracts/webhook-payload.md">specs/004-notify-channel/contracts/webhook-payload.md</a>。
 */
@Component
public class WebhookNotifyAdapter {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifyAdapter.class);

    /** 单次 HTTP 请求超时（spec SC-004 / NFR-001）。 */
    public static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    /** 失败时截取的 response body 长度（spec FR-013）。 */
    public static final int BODY_SNIPPET_BYTES = 256;

    private final Sandbox sandbox;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WebhookNotifyAdapter() {
        this(new io.oryxos.tool.sandbox.WhitelistSandbox(),
            HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build(),
            new ObjectMapper());
    }

    public WebhookNotifyAdapter(Sandbox sandbox) {
        this(sandbox, HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build(),
            new ObjectMapper());
    }

    public WebhookNotifyAdapter(Sandbox sandbox, HttpClient httpClient, ObjectMapper objectMapper) {
        this.sandbox = sandbox;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 发送一条 Notify 到指定通道。
     *
     * <p>返回的 {@link NotifyResult} 字段：
     * <ul>
     *   <li>{@code channelName} = {@code config.name()}</li>
     *   <li>{@code success} = HTTP 2xx</li>
     *   <li>{@code statusCode} = HTTP status（网络错误时 null）</li>
     *   <li>{@code errorMessage} = 失败原因（含 sandbox / HTTP status + body 前 256 字节 / IOException msg）</li>
     *   <li>{@code durationMs} = 调用总时长（含 sandbox 校验 + HTTP）</li>
     *   <li>{@code redactedUrl} = {@link UrlRedactor#redact(config.url())}</li>
     * </ul>
     */
    public NotifyResult send(NotifyChannelConfig config, String content) {
        long startedNanos = System.nanoTime();
        String redactedUrl = UrlRedactor.redact(config.url());

        // Sandbox 校验 —— MUST 在 HTTP 请求之前执行（spec FR-007）
        try {
            sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, config.url()));
        } catch (SandboxViolationException ex) {
            long durationMs = elapsedMs(startedNanos);
            log.info("notify.sandbox.rejected channel={} url={} reason={}",
                config.name(), redactedUrl, ex.getMessage());
            return new NotifyResult(config.name(), false, null,
                ex.getMessage(), durationMs, redactedUrl);
        }

        // 构造请求体
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content == null ? "" : content);
        payload.put("channel", config.name());

        HttpRequest request;
        try {
            String json = objectMapper.writeValueAsString(payload);
            request = HttpRequest.newBuilder(URI.create(config.url()))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "OryxOS-Notify/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        } catch (Exception ex) {
            long durationMs = elapsedMs(startedNanos);
            return new NotifyResult(config.name(), false, null,
                "request build failed: " + ex.getMessage(), durationMs, redactedUrl);
        }

        // 发送 —— 一次即终态（spec FR-011 不重试）
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException ex) {
            long durationMs = elapsedMs(startedNanos);
            log.info("notify.timeout channel={} url={}", config.name(), redactedUrl);
            return new NotifyResult(config.name(), false, null,
                "timeout after " + HTTP_TIMEOUT.toMillis() + "ms", durationMs, redactedUrl);
        } catch (java.io.IOException ex) {
            // ConnectException / UnknownHostException 等
            long durationMs = elapsedMs(startedNanos);
            log.info("notify.network.error channel={} url={} error={}",
                config.name(), redactedUrl, ex.getMessage());
            String msg = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            return new NotifyResult(config.name(), false, null,
                "network error: " + msg, durationMs, redactedUrl);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            long durationMs = elapsedMs(startedNanos);
            return new NotifyResult(config.name(), false, null,
                "interrupted", durationMs, redactedUrl);
        }

        int status = response.statusCode();
        long durationMs = elapsedMs(startedNanos);
        String bodySnippet = snippet(response.body(), BODY_SNIPPET_BYTES);

        if (status >= 200 && status < 300) {
            log.info("notify.sent channel={} url={} status={} durationMs={}",
                config.name(), redactedUrl, status, durationMs);
            return new NotifyResult(config.name(), true, status, null, durationMs, redactedUrl);
        }

        log.info("notify.http.error channel={} url={} status={} body={}",
            config.name(), redactedUrl, status, bodySnippet);
        return new NotifyResult(config.name(), false, status,
            "HTTP " + status + ": " + bodySnippet, durationMs, redactedUrl);
    }

    private static String snippet(String body, int maxBytes) {
        if (body == null) {
            return "";
        }
        if (body.length() <= maxBytes) {
            return body;
        }
        return body.substring(0, maxBytes) + "...";
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}