package io.oryxos.tool.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.oryxos.core.DefaultToolExecutor;
import io.oryxos.core.NotifyChannelConfig;
import io.oryxos.core.Profile;
import io.oryxos.core.Provider;
import io.oryxos.core.ToolAuditWriter;
import io.oryxos.core.ToolResult;
import io.oryxos.core.ToolSchemaProvider;
import io.oryxos.core.tool.ToolDefinition;
import io.oryxos.core.tool.ToolRegistration;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.core.tool.ToolRegistrySchemaAdapter;
import io.oryxos.memory.DefaultMemoryService;
import io.oryxos.memory.MemoryService;
import io.oryxos.memory.backend.MarkdownMemoryStore;
import io.oryxos.tool.file.FileListTool;
import io.oryxos.tool.file.FileReadTool;
import io.oryxos.tool.file.FileWriteTool;
import io.oryxos.tool.http.HttpGetTool;
import io.oryxos.tool.http.HttpPostTool;
import io.oryxos.tool.http.HttpToolProperties;
import io.oryxos.tool.memory.RecallMemoryTool;
import io.oryxos.tool.memory.SaveMemoryTool;
import io.oryxos.tool.notify.NotifyTool;
import io.oryxos.tool.notify.WebhookNotifyAdapter;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxProperties;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import io.oryxos.tool.shell.ShellTool;
import io.oryxos.tool.shell.ShellToolProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T075 —— NotifyTool 作为出站 Tool 的 cross-cutting 集成测试（spec US-5 场景 1+2+3）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>{@code notify} 已注册到 {@link ToolRegistry}（{@code origin=builtin}）</li>
 *   <li>{@link ToolRegistrySchemaAdapter}：Profile 配 notify_channels → notify 出现在 schema；
 *       未配 → 不出现</li>
 *   <li>{@link DefaultToolExecutor} 派发 notify → 审计行 success=true, channel=feishu,
 *       notify_status_code=200, source=builtin</li>
 * </ol>
 */
@SpringBootTest(classes = NotifyToolInRegistryIntegrationTest.MinimalApp.class)
@TestPropertySource(properties = {
    "oryxos.tool.shell.timeout-seconds=10",
    "oryxos.tool.shell.max-output-bytes=4096",
    "oryxos.tool.shell.dangerous-commands[0]=rm",
    "oryxos.tool.shell.dangerous-commands[1]=shutdown",
    "oryxos.tool.shell.dangerous-commands[2]=reboot",
    "oryxos.tool.shell.dangerous-commands[3]=dd",
    "oryxos.tool.shell.dangerous-commands[4]=mkfs",
    "oryxos.tool.http.timeout-seconds=5",
    "oryxos.tool.http.max-response-bytes=4096",
    "oryxos.tool.sandbox.http.allowed-domains[0]=localhost",
    "spring.main.allow-bean-definition-overriding=true"
})
class NotifyToolInRegistryIntegrationTest {

    @Configuration
    @ComponentScan(
        basePackages = "io.oryxos.tool",
        excludeFilters = @ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.REGEX,
            pattern = "io\\.oryxos\\.tool\\.mcp\\.McpClientService"
        )
    )
    @EnableConfigurationProperties({
        ShellToolProperties.class,
        HttpToolProperties.class,
        SandboxProperties.class
    })
    static class MinimalApp {

        @Bean
        @Primary
        public Sandbox sandbox(SandboxProperties props) {
            return new WhitelistSandbox(props);
        }

        @Bean
        public java.net.http.HttpClient httpClient() {
            return java.net.http.HttpClient.newHttpClient();
        }

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        public MarkdownMemoryStore markdownMemoryStore() {
            return new MarkdownMemoryStore();
        }

        @Bean
        @Primary
        public MemoryService memoryService(MarkdownMemoryStore store) {
            return new DefaultMemoryService(store);
        }

        @Bean
        public io.oryxos.core.ProfileRegistry profileRegistry() {
            return new io.oryxos.core.InMemoryProfileRegistry(Map.of());
        }

        @Bean
        public ToolRegistration fileReadToolRegistration(FileReadTool tool) {
            return new ToolRegistration(
                new ToolDefinition(FileReadTool.NAME, tool.description(), "builtin"), tool, "fileReadTool");
        }

        @Bean
        public ToolRegistration fileWriteToolRegistration(FileWriteTool tool) {
            return new ToolRegistration(
                new ToolDefinition(FileWriteTool.NAME, tool.description(), "builtin"), tool, "fileWriteTool");
        }

        @Bean
        public ToolRegistration fileListToolRegistration(FileListTool tool) {
            return new ToolRegistration(
                new ToolDefinition(FileListTool.NAME, tool.description(), "builtin"), tool, "fileListTool");
        }

        @Bean
        public ToolRegistration shellToolRegistration(ShellTool tool) {
            return new ToolRegistration(
                new ToolDefinition(ShellTool.NAME, tool.description(), "builtin"), tool, "shellTool");
        }

        @Bean
        public ToolRegistration httpGetToolRegistration(HttpGetTool tool) {
            return new ToolRegistration(
                new ToolDefinition(HttpGetTool.NAME, tool.description(), "builtin"), tool, "httpGetTool");
        }

        @Bean
        public ToolRegistration httpPostToolRegistration(HttpPostTool tool) {
            return new ToolRegistration(
                new ToolDefinition(HttpPostTool.NAME, tool.description(), "builtin"), tool, "httpPostTool");
        }

        @Bean
        public ToolRegistration saveMemoryToolRegistration(SaveMemoryTool tool) {
            return new ToolRegistration(
                new ToolDefinition(SaveMemoryTool.NAME, tool.description(), "builtin"), tool, "saveMemoryTool");
        }

        @Bean
        public ToolRegistration recallMemoryToolRegistration(RecallMemoryTool tool) {
            return new ToolRegistration(
                new ToolDefinition(RecallMemoryTool.NAME, tool.description(), "builtin"), tool, "recallMemoryTool");
        }

        @Bean
        public ToolRegistration notifyToolRegistration(NotifyTool tool) {
            return new ToolRegistration(
                new ToolDefinition(NotifyTool.NAME, tool.description(), "builtin"), tool, "notifyTool");
        }

        @Bean
        public ToolRegistry toolRegistry(java.util.List<ToolRegistration> registrations) {
            Map<String, ToolRegistration> map = new LinkedHashMap<>();
            for (ToolRegistration r : registrations) {
                map.put(r.definition().name(), r);
            }
            return ToolRegistry.of(map);
        }

        @Bean
        public WebhookNotifyAdapter webhookNotifyAdapter(Sandbox sandbox) {
            return new WebhookNotifyAdapter(sandbox, java.net.http.HttpClient.newHttpClient(),
                new ObjectMapper());
        }

        @Bean
        @Primary
        public ToolSchemaProvider toolSchemaProvider(ToolRegistry registry) {
            return new ToolRegistrySchemaAdapter(registry);
        }

        @Bean
        public AtomicReference<ToolAuditWriter.ToolAuditData> capturedAudit() {
            return new AtomicReference<>();
        }

        @Bean
        public ToolAuditWriter capturingAuditWriter(AtomicReference<ToolAuditWriter.ToolAuditData> ref) {
            return data -> ref.set(data);
        }

        @Bean
        public DefaultToolExecutor defaultToolExecutor(
                ToolRegistry registry,
                ToolAuditWriter capturingAuditWriter) {
            return new DefaultToolExecutor(capturingAuditWriter, registry);
        }
    }

    @Autowired ToolRegistry registry;
    @Autowired ToolSchemaProvider schemaProvider;
    @Autowired DefaultToolExecutor executor;
    @Autowired AtomicReference<ToolAuditWriter.ToolAuditData> capturedAudit;
    @Autowired io.oryxos.core.ProfileRegistry profileRegistry;

    static WireMockServer wm;
    static int wmPort;

    @BeforeAll
    static void startWireMock() {
        wm = new WireMockServer(WireMockConfiguration.options()
            .dynamicPort()
            .bindAddress("127.0.0.1"));
        wm.start();
        wmPort = wm.port();
    }

    @AfterAll
    static void stopWireMock() {
        if (wm != null) wm.stop();
    }

    @BeforeEach
    void resetStubs() {
        wm.resetAll();
        capturedAudit.set(null);
    }

    private Profile profileWith(List<String> tools, List<NotifyChannelConfig> channels) {
        return new Profile(
            "notifier",
            new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of()),
            tools,
            List.of(), List.of(), List.of(),
            new Profile.Settings(10, 20),
            Map.of(),
            channels
        );
    }

    private NotifyChannelConfig channel(String name, String url) {
        return new NotifyChannelConfig(name, "webhook", url, null);
    }

    /** 同 MinimalApp.webhookNotifyAdapter 的逻辑 —— 独立实例避免共享状态。 */
    private WebhookNotifyAdapter wmNotifyAdapter() {
        SandboxProperties props = new SandboxProperties();
        SandboxProperties.Http http = new SandboxProperties.Http();
        http.setAllowedDomains(java.util.List.of("localhost"));
        props.setHttp(http);
        Sandbox sandbox = new WhitelistSandbox(props);
        return new WebhookNotifyAdapter(sandbox, java.net.http.HttpClient.newHttpClient(),
            new ObjectMapper());
    }

    /** 同 MinimalApp.capturingAuditWriter 的逻辑 —— 引用 Spring 注入的 capturedAudit。 */
    private ToolAuditWriter capturedAuditWriter() {
        return data -> capturedAudit.set(data);
    }

    @Test
    @DisplayName("notify Tool 已注册到 ToolRegistry（origin=builtin）")
    void notify_registered_in_registry() {
        assertThat(registry.find("notify")).isPresent();
        ToolDefinition def = registry.get("notify");
        assertThat(def.name()).isEqualTo("notify");
        assertThat(def.origin()).isEqualTo("builtin");
    }

    @Test
    @DisplayName("Profile 配 notify_channels → notify 出现在 schema 列表")
    void notify_visible_when_channels_configured() {
        Profile profile = profileWith(
            List.of("file_read", "notify"),
            List.of(channel("feishu", "http://localhost:" + wmPort + "/hook"))
        );
        List<String> names = schemaProvider.schemasFor(profile).stream()
            .map(s -> (String) ((Map) s.get("function")).get("name"))
            .toList();
        assertThat(names).contains("notify");
    }

    @Test
    @DisplayName("Profile 未配 notify_channels → notify 不出现")
    void notify_hidden_when_no_channels() {
        Profile profile = profileWith(List.of("file_read", "notify"), List.of());
        List<String> names = schemaProvider.schemasFor(profile).stream()
            .map(s -> (String) ((Map) s.get("function")).get("name"))
            .toList();
        assertThat(names).doesNotContain("notify");
    }

    @Test
    @DisplayName("走 DefaultToolExecutor 派发 notify → 审计 channel + source=builtin + notify_status_code=200")
    void notify_dispatch_audit_consistent() throws Exception {
        Path tmpDir = Files.createTempDirectory("oryxos-ntf-");
        wm.stubFor(post(urlEqualTo("/hook"))
            .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        Profile profile = profileWith(
            List.of("notify"),
            List.of(channel("feishu", "http://localhost:" + wmPort + "/hook"))
        );
        // 用含此 profile 的自定义 registry 构造独立 NotifyTool —— 避免 Spring 单例 ProfileRegistry
        // 不支持运行时注册的限制。executor 同样换成新的以接收 capturedAuditWriter。
        io.oryxos.core.ProfileRegistry registryWithProfile =
            io.oryxos.core.InMemoryProfileRegistry.of(profile);
        NotifyTool notifyTool = new NotifyTool(
            wmNotifyAdapter(),
            registryWithProfile
        );
        ToolRegistry toolRegistryWithNotify = ToolRegistry.of(Map.of(
            "notify", new ToolRegistration(
                new ToolDefinition(NotifyTool.NAME, notifyTool.description(), "builtin"),
                notifyTool, "notifyTool")));
        DefaultToolExecutor freshExecutor = new DefaultToolExecutor(capturedAuditWriter(), toolRegistryWithNotify);
        io.oryxos.core.ProfileContext.set(new io.oryxos.core.ProfileContext.Snapshot(
            profile.name(), java.util.UUID.randomUUID(), new java.util.concurrent.atomic.AtomicInteger(0)));

        try {
            ToolResult r = freshExecutor.invoke("notify",
                Map.of("content", "hello-from-test", "channel", "feishu"),
                profile);
            assertThat(r.success()).isTrue();

            wm.verify(postRequestedFor(urlEqualTo("/hook")));

            ToolAuditWriter.ToolAuditData audit = capturedAudit.get();
            assertThat(audit).isNotNull();
            assertThat(audit.toolName()).isEqualTo("notify");
            assertThat(audit.channel()).isEqualTo("feishu");
            assertThat(audit.source()).isEqualTo("builtin");
            assertThat(audit.notifyStatusCode()).isEqualTo(200);
            assertThat(audit.success()).isTrue();
        } finally {
            io.oryxos.core.ProfileContext.clear();
            try (var stream = Files.walk(tmpDir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) { }
                });
            }
        }
    }
}