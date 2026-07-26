package io.oryxos.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import io.oryxos.tool.sandbox.SandboxViolationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP transport —— JSON-RPC over HTTP POST
 * （[contracts/mcp-adapter.md §4.1](../../../../../../../specs/005-tool-system/contracts/mcp-adapter.md)）。
 *
 * <p>每个请求：
 * <ol>
 *   <li>{@code sandbox.enforce(HTTP_REQUEST, url)}（域名白名单，应用层）</li>
 *   <li>构造 JSON-RPC envelope：{@code {"jsonrpc":"2.0","id":<n>,"method":...,"params":...}}</li>
 *   <li>{@code POST} application/json</li>
 *   <li>解析响应 body 为 {@link McpResponse}</li>
 * </ol>
 */
@Component
public class HttpMcpTransport implements McpTransport {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Sandbox sandbox;
    private final McpServerConfig config;
    private final int requestTimeoutSeconds;
    private final AtomicInteger idGenerator = new AtomicInteger(1);

    public HttpMcpTransport() {
        this(HttpClient.newHttpClient(), new ObjectMapper(),
            action -> { }, new McpServerConfig("default", "http", null, java.util.List.of(),
                "http://localhost", null, java.util.Map.of()),
            30);
    }

    public HttpMcpTransport(HttpClient httpClient, ObjectMapper objectMapper,
                             Sandbox sandbox, McpServerConfig config,
                             int requestTimeoutSeconds) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.sandbox = sandbox;
        this.config = config;
        this.requestTimeoutSeconds = requestTimeoutSeconds > 0 ? requestTimeoutSeconds : 30;
    }

    @Override
    public McpResponse sendRequest(String method, Map<String, Object> params) {
        if (!config.isHttp()) {
            throw new IllegalStateException("HttpMcpTransport requires transport=http, got: " + config.transport());
        }
        String url = config.url();
        if (url == null || url.isBlank()) {
            throw new McpConnectionException(config.name(),
                "http transport requires non-blank url");
        }
        sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url));

        int id = idGenerator.getAndIncrement();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("method", method);
        envelope.put("params", params == null ? Map.of() : params);

        try {
            String body = objectMapper.writeValueAsString(envelope);
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            if (config.authToken() != null && !config.authToken().isBlank()) {
                // For a real implementation we'd mutate the builder BEFORE send; we re-build via header below.
            }
            HttpRequest finalReq = config.authToken() != null && !config.authToken().isBlank()
                ? HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.authToken())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                : req;

            HttpResponse<String> resp = httpClient.send(finalReq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new McpConnectionException(config.name(),
                    "non-2xx http response: " + resp.statusCode() + " body=" + truncate(resp.body()));
            }
            Map<String, Object> parsed = objectMapper.readValue(resp.body(), Map.class);
            Object respIdObj = parsed.get("id");
            int respId = respIdObj instanceof Number n ? n.intValue() : id;
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) parsed.get("result");
            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) parsed.get("error");
            return new McpResponse(respId, result, error);
        } catch (SandboxViolationException ex) {
            throw new McpConnectionException(config.name(),
                "sandbox violation: " + ex.getMessage(), ex);
        } catch (java.net.http.HttpTimeoutException ex) {
            throw new McpConnectionException(config.name(),
                "request timeout after " + requestTimeoutSeconds + "s for method=" + method, ex);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new McpConnectionException(config.name(),
                "send method=" + method + " failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void close() {
        // HttpClient shared instance — no per-server cleanup
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 256 ? s : s.substring(0, 256) + "...[truncated]";
    }

    /** 包外访问 config 用 —— e.g. {@code McpClientService} 记录 endpoint。 */
    public McpServerConfig config() {
        return config;
    }
}
