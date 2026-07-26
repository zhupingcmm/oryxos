package io.oryxos.tool.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.oryxos.core.ToolResult;
import io.oryxos.core.tool.ToolDefinition;
import io.oryxos.core.tool.ToolRegistration;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.memory.MarkdownMemoryStore;
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
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T045 —— 4 个 Sandbox 拦截场景的端到端集成测试。
 *
 * <p>覆盖 quickstart §9 的核心安全护栏：
 * <ol>
 *   <li>HTTP 越域（白名单无 evil.example.com） → 拦截 + WireMock 零请求计数</li>
 *   <li>HTTP IP 字面值（127.0.0.1） → 拦截 + WireMock 零请求计数</li>
 *   <li>Shell 黑名单（rm -rf /） → 拦截 + tmp 目录无任何文件</li>
 *   <li>FILE_READ 核心阶段 no-op → 即便路径不在 allowed-paths 内也通过（扩展阶段才补强）</li>
 * </ol>
 */
@SpringBootTest(classes = SandboxEnforcementIntegrationTest.MinimalApp.class)
@TestPropertySource(properties = {
    "oryxos.tool.shell.timeout-seconds=5",
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
class SandboxEnforcementIntegrationTest {

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
        public io.oryxos.memory.MemoryService memoryService(MarkdownMemoryStore store) {
            return store;
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
                new com.fasterxml.jackson.databind.ObjectMapper());
        }
    }

    @Autowired ToolRegistry registry;
    @Autowired MarkdownMemoryStore memoryStore;

    static WireMockServer wm;
    static int wmPort;

    Path tmpDir;

    @BeforeAll
    static void startWireMock() {
        wm = new WireMockServer(WireMockConfiguration.options()
            .dynamicPort()
            .bindAddress("127.0.0.1"));
        wm.start();
        wmPort = wm.port();
        wm.stubFor(get(urlMatching("/.*")).willReturn(aResponse().withStatus(200).withBody("should-not-reach")));
    }

    @AfterAll
    static void stopWireMock() {
        if (wm != null) wm.stop();
    }

    @BeforeEach
    void resetStubs() throws Exception {
        wm.resetAll();
        wm.stubFor(get(urlMatching("/.*")).willReturn(aResponse().withStatus(200).withBody("should-not-reach")));
        tmpDir = Files.createTempDirectory("oryxos-sbx-");
        memoryStore.setFilePathForTest(tmpDir.resolve("MEMORY.md"));
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() throws Exception {
        if (tmpDir != null && Files.exists(tmpDir)) {
            try (var stream = Files.walk(tmpDir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    @DisplayName("HTTP 越域：白名单外的 evil.example.com → sandbox 拦截 + WireMock 零请求")
    void http_unknown_host_blocked_no_side_effect() {
        ToolResult r = registry.find("http_get").orElseThrow().execute(Map.of(
            "url", "https://evil.example.com/hook"));

        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("not in allowed-domains");
        wm.verify(exactly(0), getRequestedFor(urlMatching("/.*")));
    }

    @Test
    @DisplayName("HTTP IP 字面值：127.0.0.1 → sandbox 拦截 + WireMock 零请求")
    void http_ip_literal_blocked_no_side_effect() {
        ToolResult r = registry.find("http_get").orElseThrow().execute(Map.of(
            "url", "http://127.0.0.1:" + wmPort + "/api"));

        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("IP-literal");
        wm.verify(exactly(0), getRequestedFor(urlMatching("/.*")));
    }

    @Test
    @DisplayName("Shell 黑名单：rm -rf /tmp 命中 dangerous-commands → 拦截 + 零副作用")
    void shell_blacklisted_command_blocked_no_side_effect() throws Exception {
        Path sentinel = tmpDir.resolve("DO-NOT-DELETE");
        Files.writeString(sentinel, "important");

        ToolResult r = registry.find("shell").orElseThrow().execute(Map.of(
            "command", "rm -rf " + tmpDir.toString()));

        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("dangerous-commands");
        assertThat(Files.exists(sentinel)).isTrue();
        assertThat(Files.readString(sentinel)).isEqualTo("important");
    }

    @Test
    @DisplayName("FILE_READ 核心阶段 no-op：读 allowed-paths 外的文件也不被拦")
    void file_read_no_op_in_core_phase() throws Exception {
        Path note = tmpDir.resolve("note.txt");
        Files.writeString(note, "no-op-sandbox");

        ToolResult r = registry.find("file_read").orElseThrow().execute(Map.of(
            "path", note.toString()));

        assertThat(r.success()).isTrue();
        assertThat((String) r.payload().get("content")).isEqualTo("no-op-sandbox");
    }
}