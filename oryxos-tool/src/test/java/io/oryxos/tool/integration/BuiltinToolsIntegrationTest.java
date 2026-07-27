package io.oryxos.tool.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.core.tool.ToolDefinition;
import io.oryxos.core.tool.ToolRegistration;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.memory.DefaultMemoryService;
import io.oryxos.memory.MemoryService;
import io.oryxos.memory.backend.MarkdownMemoryStore;
import io.oryxos.memory.MemoryScope;
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
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T039 —— 8 个新增内置 Tool 的端到端集成测试。
 *
 * <p>通过 {@code @SpringBootTest} 加载完整 Tool 装配上下文
 * （{@link ToolSystemConfig} + {@link NotifyToolConfig} + {@link SandboxConfig} +
 * {@link HttpClientConfig} + {@link MarkdownMemoryStore}），验证：
 * <ol>
 *   <li>{@link ToolRegistry} 包含 9 个 Tool（含 notify）</li>
 *   <li>每个 Tool 的 {@code origin} 都为 {@code "builtin"}（FR-015）</li>
 *   <li>{@code file_read/file_write/file_list/shell/save_memory/recall_memory} 走通 Spring 注入路径</li>
 *   <li>{@code http_get/http_post} 走通 WireMock 真实 HTTP 端点</li>
 *   <li>Shell 黑名单（rm/shutdown/...）由 {@code application.yml} 默认值生效</li>
 * </ol>
 *
 * <p>使用 {@code @TestPropertySource} 覆盖 shell 黑名单默认值 + http 白名单
 * （{@code localhost}），避开主配置在测试机的环境差异。
 */
@SpringBootTest(classes = BuiltinToolsIntegrationTest.MinimalApp.class)
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
    "oryxos.tool.sandbox.shell.allowed-commands[0]=echo",
    "oryxos.tool.sandbox.file.allowed-paths[0]=workspace",
    "spring.main.allow-bean-definition-overriding=true"
})
class BuiltinToolsIntegrationTest {

    /**
     * 最小 Spring 上下文 —— 在测试内联 9 个 ToolRegistration + Sandbox + MemoryStore，
     * 避免把 oryxos-boot 拉进 oryxos-tool 的测试类路径（那会触发
     * oryxos-cli → oryxos-tool → oryxos-boot → oryxos-cli 循环依赖）。
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
        public ToolRegistry toolRegistry(java.util.List<ToolRegistration> registrations) {
            Map<String, ToolRegistration> map = new java.util.LinkedHashMap<>();
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
    }

    @AfterAll
    static void stopWireMock() {
        if (wm != null) wm.stop();
    }

    @BeforeEach
    void resetStubs() throws Exception {
        wm.resetAll();
        tmpDir = Files.createTempDirectory("oryxos-it-");
        // 重定向 memory store 到 tmp 目录，避免污染 ~/.oryxos
        memoryStore.setFilePathForTest(tmpDir.resolve("MEMORY.md"));
    }

    @AfterEach
    void cleanup() throws Exception {
        if (tmpDir != null && Files.exists(tmpDir)) {
            try (var stream = Files.walk(tmpDir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) { }
                });
            }
        }
    }

    private OryxTool tool(String name) {
        return registry.find(name).orElseThrow(
            () -> new AssertionError("Tool not registered: " + name));
    }

    @Test
    @DisplayName("ToolRegistry 含 10 个 Tool（9 个 builtin + 1 个 java_bean echo）")
    void registry_has_nine_builtin_tools() {
        // 10 = 9 个 builtin + 1 个 java_bean (EchoTool 通过 @ComponentScan 自动发现)
        assertThat(registry.size()).isEqualTo(10);
        int builtinCount = 0;
        int javaBeanCount = 0;
        for (ToolDefinition def : registry.all()) {
            if ("builtin".equals(def.origin())) {
                builtinCount++;
            } else if ("java_bean".equals(def.origin())) {
                javaBeanCount++;
            }
        }
        assertThat(builtinCount).isEqualTo(9);
        assertThat(javaBeanCount).isEqualTo(1);
        // 关键 builtin Tool 都在
        List<String> expectedBuiltin = List.of(
            "file_read", "file_write", "file_list",
            "shell", "http_get", "http_post",
            "save_memory", "recall_memory", "notify"
        );
        assertThat(registry.names()).containsAll(expectedBuiltin);
        assertThat(registry.names()).contains("echo");
    }

    @Test
    @DisplayName("file_write → file_read → file_list 走通")
    void file_tools_round_trip() throws Exception {
        // 007 阶段 sandbox 拒绝绝对路径（I-SB-9）；用相对路径 + 临时切换 user.dir 到 tmpDir
        // 让 FileWriteTool/FileReadTool 写入 tmpDir/workspace/ 子目录，sandbox 看到相对路径
        // "workspace/note.txt" 与 allowed-paths[0]="workspace" 前缀匹配 → 通过校验。
        String originalUserDir = System.getProperty("user.dir");
        Files.createDirectories(tmpDir.resolve("workspace"));
        System.setProperty("user.dir", tmpDir.toString());
        try {
            String relNote = "workspace" + java.io.File.separator + "note.txt";

            ToolResult write = tool("file_write").execute(Map.of(
                "path", relNote,
                "content", "hello-oryxos"));
            assertThat(write.success()).isTrue();

            ToolResult read = tool("file_read").execute(Map.of(
                "path", relNote));
            assertThat(read.success()).isTrue();
            assertThat((String) read.payload().get("content")).isEqualTo("hello-oryxos");

            ToolResult list = tool("file_list").execute(Map.of(
                "path", "workspace"));
            assertThat(list.success()).isTrue();
            @SuppressWarnings("unchecked")
            List<String> entries = (List<String>) list.payload().get("entries");
            assertThat(entries).contains("note.txt");
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    @DisplayName("shell: echo 走通；rm/shutdown 命中 application.yml 黑名单")
    void shell_echo_and_blacklist() {
        ToolResult ok = tool("shell").execute(Map.of(
            "command", "echo hello"));
        assertThat(ok.success()).isTrue();
        assertThat((String) ok.payload().get("stdout")).contains("hello");

        ToolResult blocked = tool("shell").execute(Map.of(
            "command", "rm -rf /tmp/should-not-run"));
        assertThat(blocked.success()).isFalse();
        assertThat(blocked.errorMessage()).contains("dangerous-commands");
    }

    @Test
    @DisplayName("http_get: WireMock 200 → success=true, body 含 stub")
    void http_get_wiremock() {
        wm.stubFor(get(urlEqualTo("/api")).willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"msg\":\"hi\"}")));

        ToolResult r = tool("http_get").execute(Map.of(
            "url", "http://localhost:" + wmPort + "/api"));
        assertThat(r.success()).isTrue();
        assertThat(((Number) r.payload().get("status_code")).intValue()).isEqualTo(200);
        assertThat((String) r.payload().get("body")).contains("\"msg\":\"hi\"");
    }

    @Test
    @DisplayName("http_get: 沙箱拒 IP 字面值")
    void http_get_ip_literal_rejected() {
        ToolResult r = tool("http_get").execute(Map.of(
            "url", "http://127.0.0.1:" + wmPort + "/api"));
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("IP-literal");
    }

    @Test
    @DisplayName("http_post: body 发送到 WireMock")
    void http_post_wiremock() {
        wm.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse()
            .withStatus(200).withBody("{}")));

        ToolResult r = tool("http_post").execute(Map.of(
            "url", "http://localhost:" + wmPort + "/hook",
            "body", "{\"k\":\"v\"}"));
        assertThat(r.success()).isTrue();

        wm.verify(postRequestedFor(urlEqualTo("/hook"))
            .withRequestBody(equalTo("{\"k\":\"v\"}")));
    }

    @Test
    @DisplayName("save_memory → recall_memory 命中写入的关键词")
    void memory_round_trip() {
        ToolResult save = tool("save_memory").execute(Map.of(
            "content", "用户的咖啡偏好：美式，少糖",
            "scope", "core"));
        assertThat(save.success()).isTrue();

        ToolResult recall = tool("recall_memory").execute(Map.of(
            "query", "美式",
            "top_k", 3));
        assertThat(recall.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<String> snippets = (List<String>) recall.payload().get("snippets");
        assertThat(snippets).isNotEmpty();
        assertThat(snippets.get(0)).contains("美式");
    }

    @Test
    @DisplayName("save_memory: archive scope 也接受")
    void memory_archive_scope_accepted() {
        ToolResult save = tool("save_memory").execute(Map.of(
            "content", "归档测试",
            "scope", MemoryScope.ARCHIVE.name().toLowerCase()));
        assertThat(save.success()).isTrue();
        assertThat(save.payload().get("scope")).isEqualTo("archive");
    }
}