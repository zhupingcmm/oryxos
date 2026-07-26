package io.oryxos.tool.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.oryxos.core.OryxTool;
import io.oryxos.core.Profile;
import io.oryxos.core.Provider;
import io.oryxos.core.ToolAuditWriter;
import io.oryxos.core.ToolResult;
import io.oryxos.core.tool.ToolDefinition;
import io.oryxos.core.tool.ToolRegistration;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.memory.DefaultMemoryService;
import io.oryxos.memory.MemoryService;
import io.oryxos.memory.backend.MarkdownMemoryStore;
import io.oryxos.tool.file.FileListTool;
import io.oryxos.tool.file.FileReadTool;
import io.oryxos.tool.file.FileWriteTool;
import io.oryxos.tool.http.HttpGetTool;
import io.oryxos.tool.http.HttpPostTool;
import io.oryxos.tool.http.HttpToolProperties;
import io.oryxos.tool.javabean.EchoTool;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T071 —— Java Bean Tool（{@link EchoTool}）端到端集成测试。
 *
 * <p>覆盖 spec US-4 场景 3 + FR-008 第 3 档 + FR-005 source=java_bean：
 * <ol>
 *   <li>Spring 自动发现 {@code @Component implements OryxTool} 形式的 EchoTool Bean</li>
 *   <li>{@link ToolRegistry#find(String)} 能按 name 找到 echo</li>
 *   <li>直接调 {@link OryxTool#execute} → ToolResult.success=true</li>
 *   <li>走 {@code DefaultToolExecutor} 派发后审计行 source='java_bean'</li>
 * </ol>
 */
@SpringBootTest(classes = JavaBeanToolIntegrationTest.MinimalApp.class)
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
class JavaBeanToolIntegrationTest {

    /**
     * 最小 Spring 上下文 —— 装配 9 个内置 Tool + EchoTool（Java Bean 示例）+ 捕获审计 writer。
     */
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
        public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
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
            return new io.oryxos.core.InMemoryProfileRegistry(java.util.Map.of());
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
        public ToolRegistration echoToolRegistration(EchoTool tool) {
            return new ToolRegistration(
                new ToolDefinition(EchoTool.NAME, tool.description(), "java_bean"), tool, "echoTool");
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
        public io.oryxos.core.DefaultToolExecutor defaultToolExecutor(
                ToolRegistry registry,
                ToolAuditWriter capturingAuditWriter) {
            return new io.oryxos.core.DefaultToolExecutor(capturingAuditWriter, registry);
        }

        @Bean
        public WebhookNotifyAdapter webhookNotifyAdapter(Sandbox sandbox) {
            return new WebhookNotifyAdapter(sandbox, java.net.http.HttpClient.newHttpClient(),
                new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Bean
        public AtomicReference<ToolAuditWriter.ToolAuditData> capturedAudit() {
            return new AtomicReference<>();
        }

        @Bean
        public ToolAuditWriter capturingAuditWriter(AtomicReference<ToolAuditWriter.ToolAuditData> ref) {
            return data -> ref.set(data);
        }
    }

    @Autowired ToolRegistry registry;
    @Autowired AtomicReference<ToolAuditWriter.ToolAuditData> capturedAudit;
    @Autowired io.oryxos.core.DefaultToolExecutor executor;

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

    private Profile profileWithTools(List<String> tools) {
        return new Profile(
            "javabean-test",
            new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of()),
            tools,
            List.of(), List.of(), List.of(),
            new Profile.Settings(10, 20),
            Map.of(),
            List.of()
        );
    }

    @Test
    @DisplayName("Spring 自动发现 EchoTool Bean 并写入 ToolRegistry")
    void spring_autodiscovery_registers_echo() {
        OryxTool echo = registry.find(EchoTool.NAME).orElseThrow(
            () -> new AssertionError("EchoTool not auto-discovered"));
        assertThat(echo).isInstanceOf(EchoTool.class);
        assertThat(registry.names()).contains(EchoTool.NAME);
    }

    @Test
    @DisplayName("EchoTool.execute(text='hello') → ToolResult.ok(payload.text='hello')")
    void echo_execute_returns_payload() {
        OryxTool echo = registry.find(EchoTool.NAME).orElseThrow();
        ToolResult r = echo.execute(Map.of("text", "hello"));
        assertThat(r.success()).isTrue();
        assertThat((String) r.payload().get("text")).isEqualTo("hello");
        // EchoTool 是 @Component 单例，calls 计数器在多个测试间共享；只断言 ≥ 1
        assertThat(((Number) r.payload().get("calls")).longValue()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("DefaultToolExecutor 派发 EchoTool → 审计 source='java_bean'")
    void executor_audit_records_source_java_bean() {
        capturedAudit.set(null);
        ToolResult r = executor.invoke(EchoTool.NAME,
            Map.of("text", "world"),
            profileWithTools(List.of(EchoTool.NAME)));
        assertThat(r.success()).isTrue();
        ToolAuditWriter.ToolAuditData audit = capturedAudit.get();
        assertThat(audit).isNotNull();
        assertThat(audit.source()).isEqualTo("java_bean");
        assertThat(audit.toolName()).isEqualTo(EchoTool.NAME);
        assertThat(audit.success()).isTrue();
    }
}