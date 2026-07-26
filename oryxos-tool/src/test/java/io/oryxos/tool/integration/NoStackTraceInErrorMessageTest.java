package io.oryxos.tool.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.oryxos.core.DefaultToolExecutor;
import io.oryxos.core.OryxTool;
import io.oryxos.core.Profile;
import io.oryxos.core.Provider;
import io.oryxos.core.ToolAuditWriter;
import io.oryxos.core.ToolResult;
import io.oryxos.core.tool.ToolDefinition;
import io.oryxos.core.tool.ToolRegistration;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.tool.notify.WebhookNotifyAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T079 —— NFR-004 / SC-009：ToolResult.errorMessage 必须不含 Java stack trace。
 *
 * <p>触发 3 种典型错误场景，每种都必须返回**短文本**错误（≤ 200 字符 + 无
 * {@code at io.oryxos.*} 或 {@code Exception:} 等 stack trace 标记）。
 *
 * <p>这条护栏防止 Tool 实现里把 {@code ex.toString()} / {@code ex.printStackTrace()} 串
 * 到 errorMessage 里，污染审计行 + 让 LLM 看到无意义的长文本。
 */
class NoStackTraceInErrorMessageTest {

    /** 识别 Java stack trace 的正则。 */
    private static final Pattern STACK_TRACE_PATTERN = Pattern.compile(
        "(?:\\sat\\s+[\\w.$]+\\.[\\w$.]+\\([^)]*\\)"  // "at io.foo.bar(File.java:123)"
        + "|Exception:"                                 // "java.lang.RuntimeException:"
        + "|^\\s*Caused by:"
        + "|\\.printStackTrace\\(\\)"
        + ")"
    );

    private static WireMockServer wm;
    private static int wmPort;
    private DefaultToolExecutor executor;

    @BeforeAll
    static void startWireMock() {
        wm = new WireMockServer(WireMockConfiguration.options()
            .dynamicPort().bindAddress("127.0.0.1"));
        wm.start();
        wmPort = wm.port();
    }

    @AfterAll
    static void stopWireMock() {
        if (wm != null) wm.stop();
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // 1 个 Tool：always-throw（验证 errorMessage 不含 stack trace）
        OryxTool thrower = new OryxTool() {
            @Override public String name() { return "thrower"; }
            @Override public String description() { return "always throws"; }
            @Override public ToolResult execute(Map<String, Object> args) {
                throw new RuntimeException("real exception with deep cause");
            }
        };

        java.util.LinkedHashMap<String, ToolRegistration> map = new java.util.LinkedHashMap<>();
        map.put("thrower", new ToolRegistration(
            new ToolDefinition("thrower", "always throws", "java_bean"), thrower, "thrower"));

        ToolRegistry registry = ToolRegistry.of(map);
        executor = new DefaultToolExecutor(new ToolAuditWriter.NoopToolAuditWriter(), registry);
    }

    @Test
    @DisplayName("Tool 抛 RuntimeException → errorMessage 是 'tool execution failed: <msg>' 短文本，无 stack trace")
    void runtime_exception_error_message_clean() {
        Profile profile = profileWithTools("thrower");
        io.oryxos.core.ProfileContext.set(new io.oryxos.core.ProfileContext.Snapshot(
            profile.name(), UUID.randomUUID(), new java.util.concurrent.atomic.AtomicInteger(0)));

        try {
            ToolResult r = executor.invoke("thrower", Map.of(), profile);
            assertThat(r.success()).isFalse();
            String msg = r.errorMessage();
            assertThat(msg).isNotNull();
            assertThat(msg).contains("tool execution failed");
            // 不含 stack trace 标记
            assertThat(STACK_TRACE_PATTERN.matcher(msg).find())
                .as("errorMessage must not contain stack trace: %s", msg)
                .isFalse();
            // 短文本（≤ 200 字符）—— 防御性上限，避免过长错误污染审计
            assertThat(msg.length()).isLessThanOrEqualTo(200);
        } finally {
            io.oryxos.core.ProfileContext.clear();
        }
    }

    @Test
    @DisplayName("白名单拒绝 → errorMessage 是 'tool not in profile: ...' 短文本")
    void refused_tool_error_message_clean() {
        Profile profile = profileWithTools("file_read");  // 不含 thrower
        io.oryxos.core.ProfileContext.set(new io.oryxos.core.ProfileContext.Snapshot(
            profile.name(), UUID.randomUUID(), new java.util.concurrent.atomic.AtomicInteger(0)));

        try {
            ToolResult r = executor.invoke("thrower", Map.of(), profile);
            assertThat(r.success()).isFalse();
            String msg = r.errorMessage();
            assertThat(msg).startsWith("tool not in profile:");
            assertThat(STACK_TRACE_PATTERN.matcher(msg).find())
                .as("errorMessage must not contain stack trace: %s", msg)
                .isFalse();
        } finally {
            io.oryxos.core.ProfileContext.clear();
        }
    }

    @Test
    @DisplayName("注册表 miss → errorMessage 是 'tool not registered: ...' 短文本")
    void unregistered_tool_error_message_clean() {
        // Profile 白名单含 thrower，但注册表里没注册 thrower-missing
        Profile profile = profileWithTools("thrower-missing");
        io.oryxos.core.ProfileContext.set(new io.oryxos.core.ProfileContext.Snapshot(
            profile.name(), UUID.randomUUID(), new java.util.concurrent.atomic.AtomicInteger(0)));

        try {
            ToolResult r = executor.invoke("thrower-missing", Map.of(), profile);
            assertThat(r.success()).isFalse();
            String msg = r.errorMessage();
            assertThat(msg).startsWith("tool not registered:");
            assertThat(STACK_TRACE_PATTERN.matcher(msg).find())
                .as("errorMessage must not contain stack trace: %s", msg)
                .isFalse();
        } finally {
            io.oryxos.core.ProfileContext.clear();
        }
    }

    @SuppressWarnings("unused")
    private static void ensureWireMockReachable() {
        wm.stubFor(post(urlEqualTo("/probe"))
            .willReturn(aResponse().withStatus(200).withBody("ok")));
    }

    private Profile profileWithTools(String... tools) {
        return new Profile(
            "stack-trace-test",
            new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of()),
            List.of(tools),
            List.of(), List.of(), List.of(),
            new Profile.Settings(10, 20),
            Map.of(),
            List.of()
        );
    }
}