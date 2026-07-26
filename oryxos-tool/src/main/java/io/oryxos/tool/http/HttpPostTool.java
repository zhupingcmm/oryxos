package io.oryxos.tool.http;

import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code http_post} —— 发起 HTTP POST 请求（受沙箱校验，body 为 JSON 字符串）。
 *
 * <p>默认 {@code Content-Type: application/json}，调用方可在 {@code headers} 里覆盖。
 *
 * <p>行为（[contracts/builtin-tools.md §6](../../../../../../../specs/005-tool-system/contracts/builtin-tools.md)）：
 * 同 {@link HttpGetTool} + {@code POST(BodyPublishers.ofString(body))}。
 */
@Component
public class HttpPostTool implements OryxTool {

    public static final String NAME = "http_post";

    private final HttpClient httpClient;
    private final HttpToolProperties properties;
    private final Sandbox sandbox;

    public HttpPostTool() {
        this(HttpClient.newHttpClient(),
            new HttpToolProperties(5, 1_048_576),
            action -> { });
    }

    @Autowired
    public HttpPostTool(HttpClient httpClient, HttpToolProperties properties, Sandbox sandbox) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.sandbox = sandbox;
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "发起 HTTP POST 请求（受沙箱校验，body 为 JSON 字符串）";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Object rawUrl = arguments.get("url");
        if (!(rawUrl instanceof String url) || url.isBlank()) {
            return ToolResult.error("http_post: missing required argument 'url'");
        }
        Object rawBody = arguments.get("body");
        String body = rawBody == null ? "" : rawBody.toString();

        try {
            sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url));
        } catch (RuntimeException ex) {
            return ToolResult.error(ex.getMessage());
        }

        long start = System.nanoTime();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
            applyHeaders(builder, arguments);

            HttpResponse<String> resp = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            String respBody = truncate(resp.body(), properties.maxResponseBytes());
            Map<String, Object> payload = new HashMap<>();
            payload.put("status_code", resp.statusCode());
            payload.put("content_type", String.valueOf(
                resp.headers().firstValue("Content-Type").orElse("")));
            payload.put("body", respBody);
            payload.put("duration_ms", durationMs);
            return ToolResult.ok(payload);
        } catch (java.net.http.HttpTimeoutException ex) {
            return ToolResult.error("http_post timeout after "
                + properties.timeoutSeconds() + " seconds: " + url);
        } catch (java.io.IOException ex) {
            return ToolResult.error("http_post IO error: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolResult.error("http_post interrupted");
        }
    }

    @SuppressWarnings("unchecked")
    private static void applyHeaders(HttpRequest.Builder builder, Map<String, Object> arguments) {
        Object headers = arguments.get("headers");
        if (headers instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    builder.header(e.getKey().toString(), e.getValue().toString());
                }
            }
        }
    }

    private static String truncate(String body, int maxBytes) {
        if (body == null) return "";
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return body;
        }
        return new String(bytes, 0, maxBytes, java.nio.charset.StandardCharsets.UTF_8)
            + "...[truncated:" + bytes.length + "bytes]";
    }
}

