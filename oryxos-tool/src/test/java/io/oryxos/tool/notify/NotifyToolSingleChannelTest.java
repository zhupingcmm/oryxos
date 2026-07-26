package io.oryxos.tool.notify;

import io.oryxos.core.NotifyChannelConfig;
import io.oryxos.core.OryxTool;
import io.oryxos.core.Profile;
import io.oryxos.core.ProfileContext;
import io.oryxos.core.Provider;
import io.oryxos.core.ToolResult;
import io.oryxos.core.InMemoryProfileRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotifyTool 单测 —— T031（MVP 单通道场景）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>默认通道（{@code channel=null} + Profile 仅 1 条通道）→ 成功 200，payload 含 channel+status_code</li>
 *   <li>显式通道（{@code channel="feishu"} + 命中）→ 发到指定通道</li>
 *   <li>未知通道（{@code channel="missing"}）→ ToolResult.error</li>
 *   <li>空 content → ToolResult.error（empty_content）</li>
 *   <li>超长 content → ToolResult.error（content_too_long）</li>
 *   <li>HTTP 4xx → ToolResult.error（http_error），payload 仍带 channel+status_code</li>
 *   <li>Sandbox 拦截 → ToolResult.error（sandbox_violation）</li>
 *   <li>Profile 未配通道 → ToolResult.error（no_channels）</li>
 * </ol>
 */
class NotifyToolSingleChannelTest {

    private WebhookNotifyAdapter adapter;
    private InMemoryProfileRegistry registry;
    private NotifyTool tool;
    private Profile profile;

    @BeforeEach
    void setUp() {
        adapter = mock(WebhookNotifyAdapter.class);
        ProfileContext.set(new ProfileContext.Snapshot(
            "default-agent", UUID.randomUUID(), new AtomicInteger(1)));

        profile = new Profile("default-agent",
            new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of("temperature", 0.7f)),
            List.of("notify"), List.of(), List.of(), List.of(),
            Profile.Settings.defaults(), Map.of(),
            List.of(new NotifyChannelConfig("default", "webhook",
                "http://localhost:9999/webhook", null)));
        registry = InMemoryProfileRegistry.of(profile);
        tool = new NotifyTool(adapter, registry);
    }

    @AfterEach
    void tearDown() {
        ProfileContext.clear();
    }

    private static NotifyResult ok(String channel, int status, long ms) {
        return new NotifyResult(channel, true, status, null, ms,
            "http://localhost:9999/webhook");
    }

    private static NotifyResult httpErr(String channel, int status, String msg, long ms) {
        return new NotifyResult(channel, false, status, msg, ms,
            "http://localhost:9999/webhook");
    }

    private static NotifyResult netErr(String channel, String msg, long ms) {
        return new NotifyResult(channel, false, null, msg, ms,
            "http://localhost:9999/webhook");
    }

    @Test
    void defaultChannelSuccess() {
        when(adapter.send(any(NotifyChannelConfig.class), eq("hi")))
            .thenReturn(ok("default", 200, 234L));

        ToolResult result = tool.execute(Map.of("content", "hi"));

        assertThat(result.success()).isTrue();
        assertThat(result.errorMessage()).isNull();
        assertThat(result.payload()).containsEntry("channel", "default");
        assertThat(result.payload()).containsEntry("status_code", 200);
        assertThat(result.payload()).containsEntry("success", true);
        assertThat(result.payload()).containsEntry("duration_ms", 234L);
        verify(adapter).send(any(NotifyChannelConfig.class), eq("hi"));
    }

    @Test
    void explicitChannelRoutesToNamed() {
        // 加一条 named 通道，测试显式路由
        Profile namedProfile = new Profile("default-agent",
            new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of("temperature", 0.7f)),
            List.of("notify"), List.of(), List.of(), List.of(),
            Profile.Settings.defaults(), Map.of(),
            List.of(
                new NotifyChannelConfig("default", "webhook", "http://localhost:9999/a", null),
                new NotifyChannelConfig("feishu",  "webhook", "http://localhost:9999/b", null)));
        registry = InMemoryProfileRegistry.of(namedProfile);
        tool = new NotifyTool(adapter, registry);

        when(adapter.send(any(NotifyChannelConfig.class), eq("hi")))
            .thenReturn(ok("feishu", 200, 312L));

        ToolResult result = tool.execute(Map.of("content", "hi", "channel", "feishu"));

        assertThat(result.success()).isTrue();
        assertThat(result.payload()).containsEntry("channel", "feishu");
    }

    @Test
    void unknownChannelReturnsError() {
        ToolResult result = tool.execute(Map.of("content", "hi", "channel", "missing"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("未知通道: missing");
        assertThat(result.payload()).containsEntry("error_class", "unknown_channel");
    }

    @Test
    void emptyContentReturnsError() {
        ToolResult result = tool.execute(Map.of("content", ""));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("content 不能为空");
        assertThat(result.payload()).containsEntry("error_class", "empty_content");
    }

    @Test
    void nullContentReturnsError() {
        Map<String, Object> args = new HashMap<>();
        args.put("content", null);

        ToolResult result = tool.execute(args);

        assertThat(result.success()).isFalse();
        assertThat(result.payload()).containsEntry("error_class", "empty_content");
    }

    @Test
    void contentTooLongReturnsError() {
        // 构造 4097 字节的字符串
        String tooLong = "a".repeat(NotifyTool.MAX_CONTENT_BYTES + 1);

        ToolResult result = tool.execute(Map.of("content", tooLong));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("content 超长");
        assertThat(result.payload()).containsEntry("error_class", "content_too_long");
    }

    @Test
    void contentAtBoundaryIsAccepted() {
        String boundary = "a".repeat(NotifyTool.MAX_CONTENT_BYTES);
        when(adapter.send(any(NotifyChannelConfig.class), eq(boundary)))
            .thenReturn(ok("default", 200, 10L));

        ToolResult result = tool.execute(Map.of("content", boundary));

        assertThat(result.success()).isTrue();
        assertThat(result.payload()).containsEntry("channel", "default");
    }

    @Test
    void http4xxReturnsErrorWithStatusCodeInPayload() {
        when(adapter.send(any(NotifyChannelConfig.class), eq("hi")))
            .thenReturn(httpErr("default", 404, "HTTP 404: not found", 100L));

        ToolResult result = tool.execute(Map.of("content", "hi"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("HTTP 404");
        // 关键：payload 仍带 channel + status_code，供 DefaultToolExecutor 写审计列
        assertThat(result.payload()).containsEntry("channel", "default");
        assertThat(result.payload()).containsEntry("status_code", 404);
        assertThat(result.payload()).containsEntry("error_class", "http_error");
    }

    @Test
    void sandboxViolationReturnsErrorWithClassification() {
        when(adapter.send(any(NotifyChannelConfig.class), eq("hi")))
            .thenReturn(netErr("default",
                "sandbox violation: host 'evil.example.com' not in allowed-domains", 0L));

        ToolResult result = tool.execute(Map.of("content", "hi"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("sandbox violation");
        assertThat(result.payload()).containsEntry("error_class", "sandbox_violation");
        // network error 时 status_code 应为 null
        assertThat(result.payload()).doesNotContainKey("status_code");
    }

    @Test
    void timeoutReturnsErrorWithClassification() {
        when(adapter.send(any(NotifyChannelConfig.class), eq("hi")))
            .thenReturn(netErr("default", "timeout after 5000ms", 5000L));

        ToolResult result = tool.execute(Map.of("content", "hi"));

        assertThat(result.success()).isFalse();
        assertThat(result.payload()).containsEntry("error_class", "timeout");
    }

    @Test
    void noChannelsConfiguredReturnsError() {
        Profile emptyProfile = new Profile("default-agent",
            new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of("temperature", 0.7f)),
            List.of("notify"), List.of(), List.of(), List.of(),
            Profile.Settings.defaults(), Map.of(),
            List.of());
        registry = InMemoryProfileRegistry.of(emptyProfile);
        tool = new NotifyTool(adapter, registry);

        ToolResult result = tool.execute(Map.of("content", "hi"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("profile 未配置 notify_channels");
        assertThat(result.payload()).containsEntry("error_class", "no_channels");
    }

    @Test
    void multipleChannelsWithoutExplicitNameReturnsError() {
        Profile multiProfile = new Profile("default-agent",
            new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of("temperature", 0.7f)),
            List.of("notify"), List.of(), List.of(), List.of(),
            Profile.Settings.defaults(), Map.of(),
            List.of(
                new NotifyChannelConfig("default", "webhook", "http://localhost:9999/a", null),
                new NotifyChannelConfig("feishu",  "webhook", "http://localhost:9999/b", null)));
        registry = InMemoryProfileRegistry.of(multiProfile);
        tool = new NotifyTool(adapter, registry);

        ToolResult result = tool.execute(Map.of("content", "hi"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("channel 不能省略");
    }

    @Test
    void nameAndDescription() {
        assertThat(tool.name()).isEqualTo("notify");
        assertThat(((OryxTool) tool).description())
            .isNotBlank()
            .contains("webhook");
    }
}