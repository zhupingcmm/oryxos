package io.oryxos.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.OryxTool;
import io.oryxos.core.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MCP client service —— 启动期握手 + 注册 Tools 到 {@link ToolRegistry}
 * （[contracts/mcp-adapter.md §5](../../../../../../../specs/005-tool-system/contracts/mcp-adapter.md)）。
 *
 * <p>生命周期：
 * <ol>
 *   <li>{@code @PostConstruct startup()} 读 {@code mcp_servers.yaml} → 创建 transport → 调
 *       {@code initialize} + {@code tools/list} → 通过 {@link McpToolAdapter} 包装为 {@link McpTool}</li>
 *   <li>每个 Tool 注册到 {@link ToolRegistry}（name = {@code "{server}__{tool}"}, origin = {@code "mcp"}）</li>
 *   <li>任一 server 不可达时抛 {@link McpConnectionException}；{@code failFastOnStartup=true}（默认）→ 启动失败</li>
 * </ol>
 */
@Component
public class McpClientService {

    private static final Logger log = LoggerFactory.getLogger(McpClientService.class);

    private final McpClientProperties properties;
    private final ObjectMapper objectMapper;
    private final McpToolAdapter adapter;
    private final ToolRegistry toolRegistry;
    private final List<HttpClientProvider> httpClients;
    private final SandboxProvider sandboxProvider;
    private final String yamlPath;
    private final boolean failFast;

    private final List<McpTransport> openedTransports = new ArrayList<>();

    @Autowired
    public McpClientService(
        McpClientProperties properties,
        ObjectMapper objectMapper,
        McpToolAdapter adapter,
        ToolRegistry toolRegistry,
        @org.springframework.beans.factory.annotation.Autowired(required = false)
            List<HttpClientProvider> httpClients,
        @Value("${oryxos.tool.mcp.config-path:./mcp_servers.yaml}") String yamlPath
    ) {
        this(properties, objectMapper, adapter, toolRegistry, httpClients == null ? List.of() : httpClients,
            yamlPath, properties.failFastOnStartup(), new SandboxProvider.Noop());
    }

    /** 测试 / 包内手工构造版本。 */
    public McpClientService(
        McpClientProperties properties,
        ObjectMapper objectMapper,
        McpToolAdapter adapter,
        ToolRegistry toolRegistry,
        List<HttpClientProvider> httpClients,
        String yamlPath,
        boolean failFast,
        SandboxProvider sandboxProvider
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.adapter = adapter;
        this.toolRegistry = toolRegistry;
        this.httpClients = httpClients;
        this.yamlPath = yamlPath;
        this.failFast = failFast;
        this.sandboxProvider = sandboxProvider;
    }

    @PostConstruct
    public void startup() {
        Path path = Paths.get(yamlPath);
        List<McpServerConfig> configs = McpServerConfig.load(path);
        if (configs.isEmpty()) {
            log.info("mcp.startup.no_config path={} (zero servers)", yamlPath);
            return;
        }
        List<String> registeredTools = new ArrayList<>();
        for (McpServerConfig cfg : configs) {
            try {
                List<OryxTool> tools = connectAndRegister(cfg);
                for (OryxTool t : tools) {
                    registeredTools.add(t.name());
                }
            } catch (McpConnectionException ex) {
                closeAllOpened();
                if (failFast) {
                    throw ex;
                }
                log.warn("mcp.startup.server_skipped server={} reason={}", cfg.name(), ex.getMessage());
            }
        }
        if (!registeredTools.isEmpty()) {
            log.info("mcp.startup.registered tools={}", registeredTools);
        }
    }

    private List<OryxTool> connectAndRegister(McpServerConfig cfg) {
        McpTransport transport = createTransport(cfg);
        openedTransports.add(transport);
        try {
            Map<String, Object> initParams = Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "oryxos", "version", "0.1.0")
            );
            McpResponse initResp = transport.sendRequest("initialize", initParams);
            if (initResp.isError()) {
                throw new McpConnectionException(cfg.name(),
                    "initialize failed: " + initResp.errorMessage());
            }
            McpResponse listResp = transport.sendRequest("tools/list", Map.of());
            if (listResp.isError()) {
                throw new McpConnectionException(cfg.name(),
                    "tools/list failed: " + listResp.errorMessage());
            }
            List<McpToolDescriptor> descriptors = parseToolsList(listResp);
            List<OryxTool> tools = adapter.adapt(cfg.name(), descriptors, transport);
            // Register to the ToolRegistry via ToolDefinition
            Map<String, io.oryxos.core.tool.ToolRegistration> mcpRegs = new java.util.LinkedHashMap<>();
            for (OryxTool t : tools) {
                mcpRegs.put(t.name(), new io.oryxos.core.tool.ToolRegistration(
                    new io.oryxos.core.tool.ToolDefinition(t.name(), t.description(), "mcp"),
                    t,
                    "mcp:" + cfg.name() + ":" + t.name()
                ));
            }
            toolRegistry.registerAll(mcpRegs);
            log.info("mcp.server.connected server={} toolCount={}", cfg.name(), tools.size());
            return tools;
        } catch (RuntimeException ex) {
            throw new McpConnectionException(cfg.name(),
                "startup failed: " + ex.getMessage(), ex);
        }
    }

    private McpTransport createTransport(McpServerConfig cfg) {
        if (cfg.isHttp()) {
            java.net.http.HttpClient client = httpClients.isEmpty()
                ? java.net.http.HttpClient.newHttpClient()
                : httpClients.get(0).get();
            return new HttpMcpTransport(client, objectMapper, sandboxProvider.get(),
                cfg, properties.requestTimeoutSeconds());
        }
        if (cfg.isStdio()) {
            return new StdioMcpTransport(objectMapper, cfg);
        }
        throw new McpConnectionException(cfg.name(),
            "unsupported transport: " + cfg.transport());
    }

    @SuppressWarnings("unchecked")
    private List<McpToolDescriptor> parseToolsList(McpResponse resp) {
        if (resp.result() == null) return Collections.emptyList();
        Object toolsObj = resp.result().get("tools");
        if (!(toolsObj instanceof List<?> list)) return Collections.emptyList();
        List<McpToolDescriptor> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String name = String.valueOf(m.get("name"));
            String desc = String.valueOf(m.get("description"));
            Object schemaObj = m.get("inputSchema");
            String schema = schemaObj == null ? "{}"
                : (schemaObj instanceof String s ? s
                    : objectMapper.valueToTree(schemaObj).toString());
            out.add(new McpToolDescriptor(name, desc, schema));
        }
        return out;
    }

    private void closeAllOpened() {
        for (McpTransport t : openedTransports) {
            try { t.close(); } catch (RuntimeException ignored) { }
        }
        openedTransports.clear();
    }

    /** 包外可访问 —— 测试 / 运维关闭。 */
    public void shutdown() {
        closeAllOpened();
    }

    /** Test seam —— 给包外注入 {@link java.net.http.HttpClient}。 */
    public interface HttpClientProvider {
        java.net.http.HttpClient get();
    }

    /** Test seam —— 让 transport 的 sandbox 能在测试里被替换。 */
    public interface SandboxProvider {
        io.oryxos.tool.sandbox.Sandbox get();
        final class Noop implements SandboxProvider {
            @Override public io.oryxos.tool.sandbox.Sandbox get() {
                return action -> { };
            }
        }
    }
}
