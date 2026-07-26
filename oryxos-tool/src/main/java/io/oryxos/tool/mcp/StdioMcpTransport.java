package io.oryxos.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP transport —— JSON-RPC over stdio（line-delimited JSON，每行一个请求 / 一个响应）
 * （[contracts/mcp-adapter.md §4.2](../../../../../../../specs/005-tool-system/contracts/mcp-adapter.md)）。
 *
 * <p>每个请求：
 * <ol>
 *   <li>构造 JSON-RPC envelope</li>
 *   <li>写入子进程 stdin（{@code args.get(method)}）</li>
 *   <li>从子进程 stdout 阻塞读一行（去掉行尾 {@code \n} / {@code \r\n}）</li>
 *   <li>解析为 {@link McpResponse}</li>
 * </ol>
 *
 * <p>{@link #close()} 在第一次调用时销毁子进程；idempotent。
 */
@Component
public class StdioMcpTransport implements McpTransport {

    private final ObjectMapper objectMapper;
    private final McpServerConfig config;
    private final AtomicInteger idGenerator = new AtomicInteger(1);
    private final Object lifecycleLock = new Object();
    private Process process;
    private Writer stdinWriter;
    private BufferedReader stdoutReader;

    public StdioMcpTransport() {
        this(new ObjectMapper(),
            new McpServerConfig("default", "stdio", "cat", java.util.List.of(), null, null, java.util.Map.of()));
    }

    public StdioMcpTransport(ObjectMapper objectMapper, McpServerConfig config) {
        this.objectMapper = objectMapper;
        this.config = config;
    }

    @Override
    public McpResponse sendRequest(String method, Map<String, Object> params) {
        if (!config.isStdio()) {
            throw new IllegalStateException("StdioMcpTransport requires transport=stdio, got: " + config.transport());
        }
        ensureStarted();
        int id = idGenerator.getAndIncrement();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("method", method);
        envelope.put("params", params == null ? Map.of() : params);

        synchronized (lifecycleLock) {
            try {
                String line = objectMapper.writeValueAsString(envelope);
                stdinWriter.write(line);
                stdinWriter.write("\n");
                stdinWriter.flush();
                String respLine = stdoutReader.readLine();
                if (respLine == null) {
                    throw new McpConnectionException(config.name(),
                        "stdin MCP server closed stdout unexpectedly (process exited?)");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(respLine, Map.class);
                Object respIdObj = parsed.get("id");
                int respId = respIdObj instanceof Number n ? n.intValue() : id;
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) parsed.get("result");
                @SuppressWarnings("unchecked")
                Map<String, Object> error = (Map<String, Object>) parsed.get("error");
                return new McpResponse(respId, result, error);
            } catch (IOException ex) {
                throw new McpConnectionException(config.name(),
                    "stdio I/O error in sendRequest method=" + method + ": " + ex.getMessage(), ex);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (process == null) return;
            try {
                if (stdinWriter != null) stdinWriter.close();
            } catch (IOException ignored) { }
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            process = null;
            stdinWriter = null;
            stdoutReader = null;
        }
    }

    private void ensureStarted() {
        synchronized (lifecycleLock) {
            if (process != null && process.isAlive()) return;
            try {
                ProcessBuilder pb = new ProcessBuilder();
                pb.command(buildCommand());
                if (config.env() != null) pb.environment().putAll(config.env());
                process = pb.start();
                stdinWriter = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
                stdoutReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            } catch (IOException ex) {
                throw new McpConnectionException(config.name(),
                    "failed to spawn stdio MCP process command=" + config.command(), ex);
            }
        }
    }

    private String[] buildCommand() {
        String[] base = new String[1 + config.args().size()];
        base[0] = config.command();
        for (int i = 0; i < config.args().size(); i++) {
            base[i + 1] = config.args().get(i);
        }
        return base;
    }

    public McpServerConfig config() {
        return config;
    }
}
