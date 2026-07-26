package io.oryxos.tool.mcp;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 单条 MCP server 配置 —— {@code mcp_servers.yaml} 一个条目的扁平映射。
 *
 * <p>YAML 片段示例（[contracts/mcp-adapter.md §5.1](../../../../../../../specs/005-tool-system/contracts/mcp-adapter.md)）：
 *
 * <pre>
 * servers:
 *   - name: github
 *     transport: http
 *     url: https://mcp.github.com/sse
 *     auth_token: ${MCP_GITHUB_TOKEN}
 *   - name: local-python
 *     transport: stdio
 *     command: python
 *     args: ["./mcp_servers/echo_server.py"]
 *     env:
 *       PYTHONPATH: ./lib
 * </pre>
 *
 * <p>加载走 {@link #load(Path)} —— YAML → {@code List<McpServerConfig>}。
 */
public record McpServerConfig(
    String name,
    String transport,
    String command,
    List<String> args,
    String url,
    String authToken,
    Map<String, String> env
) {

    /** HTTP transport 校验 —— {@code url} 非空；ignore 其他字段。 */
    public boolean isHttp() {
        return "http".equalsIgnoreCase(transport);
    }

    /** stdio transport 校验 —— {@code command} 非空。 */
    public boolean isStdio() {
        return "stdio".equalsIgnoreCase(transport);
    }

    /**
     * 加载 {@code mcp_servers.yaml} → 配置列表。
     *
     * <p>支持两种结构：扁平 {@code List<McpServerConfig>} 或包在 {@code servers:} 键下的同样列表。
     * 文件不存在时返回空列表（视为零 MCP server）。
     */
    @SuppressWarnings("unchecked")
    public static List<McpServerConfig> load(Path yamlPath) {
        if (yamlPath == null || !Files.exists(yamlPath)) {
            return List.of();
        }
        try (InputStream in = Files.newInputStream(yamlPath)) {
            Object raw = new Yaml().load(in);
            List<Map<String, Object>> rows;
            if (raw instanceof Map<?, ?> root && root.get("servers") instanceof List<?> list) {
                rows = (List<Map<String, Object>>) list;
            } else if (raw instanceof List<?> list) {
                rows = (List<Map<String, Object>>) list;
            } else {
                return List.of();
            }
            List<McpServerConfig> out = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                out.add(parseRow(row));
            }
            return Collections.unmodifiableList(out);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read mcp_servers.yaml: " + yamlPath, ex);
        }
    }

    private static McpServerConfig parseRow(Map<String, Object> row) {
        String name = str(row.get("name"));
        String transport = str(row.get("transport")).toLowerCase();
        String command = str(row.get("command"));
        String url = str(row.get("url"));
        String authToken = str(row.get("auth_token"));
        @SuppressWarnings("unchecked")
        List<String> args = row.get("args") instanceof List<?> list
            ? list.stream().map(String::valueOf).toList()
            : List.of();
        @SuppressWarnings("unchecked")
        Map<String, String> env = row.get("env") instanceof Map<?, ?> m
            ? m.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                e -> String.valueOf(e.getKey()),
                e -> String.valueOf(e.getValue())))
            : Map.of();
        return new McpServerConfig(name, transport, command, args, url, authToken, env);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
