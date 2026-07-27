package io.oryxos.tool.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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
import io.oryxos.tool.memory.RecallMemoryTool;
import io.oryxos.tool.memory.SaveMemoryTool;
import io.oryxos.tool.notify.NotifyTool;
import io.oryxos.tool.notify.WebhookNotifyAdapter;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxProperties;
import io.oryxos.tool.sandbox.SandboxViolationException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T045 —— 4 个 Sandbox 拦截场景的端到端集成测试。
 *
 * <p>覆盖 quickstart §9 的核心安全护栏：
 * <ol>
 *   <li>HTTP 越域（白名单无 evil.example.com） → 拦截 + WireMock 零请求计数</li>
 *   <li>HTTP IP 字面值（127.0.0.1） → 拦截 + WireMock 零请求计数</li>
 *   <li>Shell 黑名单（rm -rf /） → 拦截 + tmp 目录无任何文件</li>
 *   <li>FILE_READ 白名单内路径 → 通过；白名单外 / 绝对路径 → 拦截（007 阶段真实白名单）</li>
 *   <li>Shell 双层防御顺序：blacklist（{@code dangerousCommands}）先于 whitelist（{@code shell.allowed-commands}）（T011）</li>
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
    "oryxos.tool.sandbox.shell.allowed-commands[0]=rm",
    "oryxos.tool.sandbox.shell.allowed-commands[1]=git",
    "oryxos.tool.sandbox.shell.allowed-commands[2]=ls",
    "spring.main.allow-bean-definition-overriding=true"
})
class SandboxEnforcementIntegrationTest {

    /**
     * 动态注入 file.allowed-paths = user.dir —— 跨平台通用（Maven test JVM CWD = oryxos-tool 模块根）。
     * <p>同时复用为 sandbox Bean 的工作区根，便于 {@code file_read_in_whitelist_allowed} 用相对路径验证。
     */
    @org.springframework.test.context.DynamicPropertySource
    static void fileAllowedPathsDynamicProperty(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("oryxos.tool.sandbox.file.allowed-paths[0]",
            () -> System.getProperty("user.dir"));
    }

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
    @DisplayName("FILE_READ 白名单内路径 → 通过")
    void file_read_in_whitelist_allowed() throws Exception {
        // 通过 @DynamicPropertySource 注入 allowed-paths[0]=user.dir（即 oryxos-tool 模块根）
        // 创建 .test-workspace 子目录 + 写入 notes.md；用相对路径 ".test-workspace/notes.md" 调 file_read
        Path workspace = Path.of(System.getProperty("user.dir"), ".test-workspace");
        Files.createDirectories(workspace);
        Path note = workspace.resolve("notes.md");
        Files.writeString(note, "inside-whitelist");

        try {
            // 用相对路径（spec FR-003：核心阶段只允许相对路径）
            ToolResult r = registry.find("file_read").orElseThrow().execute(Map.of(
                "path", ".test-workspace/notes.md"));
            assertThat(r.success()).isTrue();
            assertThat((String) r.payload().get("content")).isEqualTo("inside-whitelist");
        } finally {
            Files.deleteIfExists(note);
            Files.deleteIfExists(workspace);
        }
    }

    @Test
    @DisplayName("FILE_READ 白名单外路径 → sandbox 拦截（'not in allowed-paths'）")
    void file_read_outside_whitelist_blocked() {
        // /etc/passwd 是绝对路径 → 抛 "absolute path not allowed"
        // tmpDir 是相对路径但不在白名单 → 抛 "not in allowed-paths"
        SandboxViolationException ex = assertThrows(SandboxViolationException.class,
            () -> registry.find("file_read").orElseThrow().execute(Map.of(
                "path", "/etc/passwd")));
        // 绝对路径优先抛 "absolute path not allowed"
        assertThat(ex.getMessage()).contains("absolute path not allowed");
    }

    @Test
    @DisplayName("FILE_READ 越界相对路径（not in allowed-paths） → 拦截")
    void file_read_relative_path_outside_whitelist_blocked() {
        // ../etc/passwd 是相对路径（不抛 absolute 错误），workspace = user.dir
        // resolve 后 = user.dir/../etc/passwd = user.dir 父目录的 etc 子目录
        // → 不在 user.dir 子树 → 抛 "not in allowed-paths"
        SandboxViolationException ex = assertThrows(SandboxViolationException.class,
            () -> registry.find("file_read").orElseThrow().execute(Map.of(
                "path", "../somewhere-outside-workspace/secret.md")));
        assertThat(ex.getMessage()).contains("not in allowed-paths");
    }

    @Test
    @DisplayName("Shell 双层防御顺序：blacklist 先于 whitelist（'rm' 同名双方 → 仅命中 blacklist）")
    void shell_blacklist_precedes_whitelist() {
        // 配置：
        //   ShellToolProperties.dangerousCommands  = ["rm", "shutdown", ...]（既有）
        //   SandboxProperties.shell.allowed-commands = ["rm", "git", "ls"]（本测试类 @TestPropertySource 注入）
        //   → rm 同时在 blacklist 和 whitelist
        // 预期：命中 blacklist → errorMessage 包含 "dangerous-commands"，不包含 "not in allowed-commands"
        //   证明 ShellTool.execute() 第 72 行 blacklist 检查在 sandbox.enforce() 之前（research.md R-03）
        ToolResult r = registry.find("shell").orElseThrow().execute(Map.of(
            "command", "rm -rf " + tmpDir.resolve("should-not-touch").toString()));

        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("dangerous-commands");
        assertThat(r.errorMessage()).doesNotContain("not in allowed-commands");
    }
}