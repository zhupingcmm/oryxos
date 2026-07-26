package io.oryxos.tool.notify;

import io.oryxos.core.NotifyChannelConfig;
import io.oryxos.core.Profile;
import io.oryxos.core.ProfileContext;
import io.oryxos.core.Provider;
import io.oryxos.core.ToolResult;
import io.oryxos.core.InMemoryProfileRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotifyTool 多通道路由测试 —— T040（US-2 P2）。
 *
 * <p>覆盖矩阵（spec FR-006 §3 路由表）：
 * <ul>
 *   <li>3 通道 Profile + channel="<命中名>" → 仅该通道被发，其他零 HTTP 调用</li>
 *   <li>3 通道 Profile + channel="<未知名>" → ToolResult.error("未知通道: ...")，adapter.send 调用 0 次</li>
 *   <li>3 通道 Profile + channel=null → "channel 不能省略" 错误（US-2 显式降级；广播在 US-4 阶段固化）</li>
 *   <li>1 通道 Profile + channel="<该通道名>" → 仍发到该通道（显式匹配不影响唯一通道语义）</li>
 *   <li>1 通道 Profile + channel="<其他名>" → "未知通道" 错误</li>
 * </ul>
 */
class NotifyToolMultiChannelTest {

    private WebhookNotifyAdapter adapter;
    private InMemoryProfileRegistry registry;
    private NotifyTool tool;

    @BeforeEach
    void setUp() {
        adapter = mock(WebhookNotifyAdapter.class);
        ProfileContext.set(new ProfileContext.Snapshot(
            "multi-agent", UUID.randomUUID(), new AtomicInteger(1)));
    }

    @AfterEach
    void tearDown() {
        ProfileContext.clear();
    }

    private static Provider deepseekProvider() {
        return new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY",
            Map.of("temperature", 0.7f));
    }

    /** 三通道 Profile：default / feishu / dingtalk。 */
    private Profile threeChannelProfile() {
        return new Profile("multi-agent", deepseekProvider(),
            List.of("notify"), List.of(), List.of(), List.of(),
            Profile.Settings.defaults(), Map.of(),
            List.of(
                new NotifyChannelConfig("default",  "webhook", "http://localhost:9999/a", null),
                new NotifyChannelConfig("feishu",   "webhook", "http://localhost:9999/b", null),
                new NotifyChannelConfig("dingtalk", "webhook", "http://localhost:9999/c", null)
            ));
    }

    /** 单通道 Profile：name=ops，URL 任意。 */
    private Profile singleChannelProfile() {
        return new Profile("multi-agent", deepseekProvider(),
            List.of("notify"), List.of(), List.of(), List.of(),
            Profile.Settings.defaults(), Map.of(),
            List.of(new NotifyChannelConfig("ops", "webhook", "http://localhost:9999/ops", null)));
    }

    @Test
    void explicitChannelOnMultiProfileRoutesOnlyToNamed() {
        registry = InMemoryProfileRegistry.of(threeChannelProfile());
        tool = new NotifyTool(adapter, registry);

        when(adapter.send(any(NotifyChannelConfig.class), eq("hi")))
            .thenReturn(new NotifyResult("feishu", true, 200, null, 234L,
                "http://localhost:9999/b"));

        ToolResult result = tool.execute(Map.of("content", "hi", "channel", "feishu"));

        assertThat(result.success()).isTrue();
        assertThat(result.payload()).containsEntry("channel", "feishu");
        assertThat(result.payload()).containsEntry("status_code", 200);
        // 关键：只有 feishu 被发；其他两条通道零 HTTP 调用
        verify(adapter).send(any(NotifyChannelConfig.class), eq("hi"));
    }

    @Test
    void unknownChannelOnMultiProfileZeroHttpCalls() {
        registry = InMemoryProfileRegistry.of(threeChannelProfile());
        tool = new NotifyTool(adapter, registry);

        ToolResult result = tool.execute(Map.of("content", "hi", "channel", "missing"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("未知通道: missing");
        assertThat(result.payload()).containsEntry("error_class", "unknown_channel");
        verify(adapter, never()).send(any(NotifyChannelConfig.class), any());
    }

    @Test
    void multiProfileWithNamedDefaultRoutesToDefault() {
        // spec FR-006 优先级 #1：含名为 default 的通道 + channel=null → 路由到 default（不论 N）
        // （T062 阶段补：原 multiProfileWithoutChannelNameFailsExplicitly 断言的是 spec 偏离行为）
        registry = InMemoryProfileRegistry.of(threeChannelProfile());
        tool = new NotifyTool(adapter, registry);

        when(adapter.send(any(NotifyChannelConfig.class), eq("hi")))
            .thenAnswer(inv -> {
                NotifyChannelConfig c = inv.getArgument(0);
                return new NotifyResult(c.name(), true, 200, null, 100L,
                    "http://localhost:9999/" + c.name());
            });

        ToolResult result = tool.execute(Map.of("content", "hi"));

        // 关键：含名为 default 的通道 → 应路由到 default（不论另外两条 feishu/dingtalk）
        assertThat(result.success()).isTrue();
        assertThat(result.payload()).containsEntry("channel", "default");
        assertThat(result.payload()).containsEntry("status_code", 200);
        // 仅 default 被发；feishu/dingtalk 零 HTTP 调用
        verify(adapter).send(any(NotifyChannelConfig.class), eq("hi"));
    }

    @Test
    void multiProfileWithoutDefaultAndBroadcastFalseFailsExplicitly() {
        // spec FR-006 #4：N>1 + 无 default 通道 + broadcast 未声明 → 报错
        // （T062 阶段补：与原 multiProfileWithoutChannelNameFailsExplicitly 同义但用无 default Profile）
        Profile profileNoDefault = new Profile("multi-agent", deepseekProvider(),
            List.of("notify"), List.of(), List.of(), List.of(),
            Profile.Settings.defaults(), Map.of(),
            List.of(
                new NotifyChannelConfig("ops",      "webhook", "http://localhost:9999/ops", null),
                new NotifyChannelConfig("sre",      "webhook", "http://localhost:9999/sre", null),
                new NotifyChannelConfig("security", "webhook", "http://localhost:9999/sec", null)
            ));
        registry = InMemoryProfileRegistry.of(profileNoDefault);
        tool = new NotifyTool(adapter, registry);

        ToolResult result = tool.execute(Map.of("content", "hi"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("channel 不能省略");
        assertThat(result.errorMessage()).contains("3 条通道");
        verify(adapter, never()).send(any(NotifyChannelConfig.class), any());
    }

    @Test
    void explicitChannelMatchingSingleProfileStillSends() {
        // 唯一通道 + 显式同名 channel → 显式路由正常工作
        registry = InMemoryProfileRegistry.of(singleChannelProfile());
        tool = new NotifyTool(adapter, registry);

        when(adapter.send(any(NotifyChannelConfig.class), eq("hi")))
            .thenReturn(new NotifyResult("ops", true, 200, null, 100L,
                "http://localhost:9999/ops"));

        ToolResult result = tool.execute(Map.of("content", "hi", "channel", "ops"));

        assertThat(result.success()).isTrue();
        assertThat(result.payload()).containsEntry("channel", "ops");
        verify(adapter).send(any(NotifyChannelConfig.class), eq("hi"));
    }

    @Test
    void explicitNonMatchingNameOnSingleProfileReturnsUnknownChannel() {
        // 单通道但显式了一个不存在的名字 → 未知通道错误
        registry = InMemoryProfileRegistry.of(singleChannelProfile());
        tool = new NotifyTool(adapter, registry);

        ToolResult result = tool.execute(Map.of("content", "hi", "channel", "feishu"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("未知通道: feishu");
        assertThat(result.payload()).containsEntry("error_class", "unknown_channel");
        verify(adapter, never()).send(any(NotifyChannelConfig.class), any());
    }

    @Test
    void channelNameIsCaseSensitive() {
        // 通道名匹配严格区分大小写（spec FR-006 隐含语义；Profile 字段定义在契约 §1）
        registry = InMemoryProfileRegistry.of(threeChannelProfile());
        tool = new NotifyTool(adapter, registry);

        ToolResult result = tool.execute(Map.of("content", "hi", "channel", "FEISHU"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("未知通道: FEISHU");
        verify(adapter, never()).send(any(NotifyChannelConfig.class), any());
    }
}