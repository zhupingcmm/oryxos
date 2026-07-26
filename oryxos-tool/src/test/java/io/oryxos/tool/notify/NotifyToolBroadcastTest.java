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
import org.mockito.invocation.InvocationOnMock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NotifyTool 广播测试 —— T046（US-4 P3）。
 *
 * <p>覆盖矩阵（spec FR-007 广播语义 + §3.1）：
 * <ul>
 *   <li>3 通道 Profile + broadcast=true + channel=null → 全成功；payload.results=3；audit channel="a;b;c"</li>
 *   <li>3 通道 Profile + broadcast=true + channel=null + 1 条 500 → partial；success=true；errorMessage 含 "partial: "</li>
 *   <li>3 通道 Profile + broadcast=true + channel=null + 全失败 → success=false；errorMessage 含 "all failed: "</li>
 *   <li>3 通道 Profile + broadcast=true + channel=null + 全网络错误 → notifyStatusCode=null</li>
 *   <li>3 通道 Profile + broadcast=false → 走单通道路由表（与 US-2 一致：N>1 报错）</li>
 *   <li>广播时 2xx + 非 2xx 混合 → notifyStatusCode 取非 2xx（最差）</li>
 *   <li>广播时多条非 2xx → notifyStatusCode 取数字最大</li>
 * </ul>
 */
class NotifyToolBroadcastTest {

    private WebhookNotifyAdapter adapter;
    private InMemoryProfileRegistry registry;
    private NotifyTool tool;

    @BeforeEach
    void setUp() {
        adapter = mock(WebhookNotifyAdapter.class);
        ProfileContext.set(new ProfileContext.Snapshot(
            "broadcast-agent", UUID.randomUUID(), new AtomicInteger(1)));
    }

    @AfterEach
    void tearDown() {
        ProfileContext.clear();
    }

    private static Provider deepseekProvider() {
        return new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY",
            Map.of("temperature", 0.7f));
    }

    private static NotifyChannelConfig ch(String name) {
        return new NotifyChannelConfig(name, "webhook",
            "http://localhost:9999/" + name, null);
    }

    /** 三通道 Profile + 可选 broadcast 标记。 */
    private Profile broadcastProfile(boolean broadcast) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("broadcast", broadcast);
        return new Profile("broadcast-agent", deepseekProvider(),
            List.of("notify"), List.of(), List.of(), List.of(),
            Profile.Settings.defaults(), extra,
            List.of(ch("a"), ch("b"), ch("c")));
    }

    @SuppressWarnings("unchecked")
    private static NotifyResult ok(String channel, long ms) {
        return new NotifyResult(channel, true, 200, null, ms,
            "http://localhost:9999/" + channel);
    }

    private static NotifyResult httpErr(String channel, int status, long ms) {
        return new NotifyResult(channel, false, status, "HTTP " + status, ms,
            "http://localhost:9999/" + channel);
    }

    private static NotifyResult netErr(String channel, String msg, long ms) {
        return new NotifyResult(channel, false, null, msg, ms,
            "http://localhost:9999/" + channel);
    }

    @Test
    void allChannelsSucceedBroadcastAggregatesSuccess() {
        registry = InMemoryProfileRegistry.of(broadcastProfile(true));
        tool = new NotifyTool(adapter, registry);

        // Answer-based mock：返回的 channelName 与传入 arg 一致（避免依赖虚拟线程调用顺序）
        when(adapter.send(any(NotifyChannelConfig.class), eq("hi")))
            .thenAnswer((InvocationOnMock inv) -> {
                NotifyChannelConfig c = inv.getArgument(0);
                long ms = c.name().equals("a") ? 100L
                        : c.name().equals("b") ? 200L : 300L;
                return ok(c.name(), ms);
            });

        ToolResult result = tool.execute(Map.of("content", "hi"));

        assertThat(result.success()).isTrue();
        assertThat(result.errorMessage()).isNull();
        assertThat(result.payload()).containsEntry("broadcast", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) result.payload().get("results");
        assertThat(entries).hasSize(3);
        // 审计字段：channel 用 ; 分隔，按 Profile notifyChannels 顺序
        assertThat(result.payload()).containsEntry("channel", "a;b;c");
        // 全成功 → notifyStatusCode = null（无最差值）
        assertThat(result.payload().get("status_code")).isNull();
        // duration_ms = 最长那条
        assertThat(result.payload()).containsEntry("duration_ms", 300L);
    }

    @Test
    void partialFailureReturnsSuccessWithPartialErrorMessage() {
        registry = InMemoryProfileRegistry.of(broadcastProfile(true));
        tool = new NotifyTool(adapter, registry);

        // 通道 b 失败（500），其他成功
        when(adapter.send(any(NotifyChannelConfig.class), eq("hi")))
            .thenAnswer((InvocationOnMock inv) -> {
                NotifyChannelConfig c = inv.getArgument(0);
                if ("b".equals(c.name())) {
                    return httpErr(c.name(), 500, 200L);
                }
                return ok(c.name(), 100L);
            });

        ToolResult result = tool.execute(Map.of("content", "hi"));

        // 部分失败：success=true（聚合语义），errorMessage 含 "partial: "
        assertThat(result.success()).isTrue();
        assertThat(result.errorMessage()).contains("partial: ");
        assertThat(result.errorMessage()).contains("b=500");
        // notifyStatusCode = 最差（非 2xx 优先）→ 500
        assertThat(result.payload()).containsEntry("status_code", 500);
        assertThat(result.payload()).containsEntry("channel", "a;b;c");
    }

    @Test
    void allFailureReturnsErrorWithAllFailedMessage() {
        registry = InMemoryProfileRegistry.of(broadcastProfile(true));
        tool = new NotifyTool(adapter, registry);

        // 三通道全部非 2xx
        when(adapter.send(any(NotifyChannelConfig.class), eq("hi")))
            .thenAnswer((InvocationOnMock inv) -> {
                NotifyChannelConfig c = inv.getArgument(0);
                int code = c.name().equals("a") ? 500
                         : c.name().equals("b") ? 503 : 502;
                return httpErr(c.name(), code, 100L);
            });

        ToolResult result = tool.execute(Map.of("content", "hi"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("all failed: ");
        // 多条非 2xx → 取数字最大 → 503
        assertThat(result.payload()).containsEntry("status_code", 503);
    }

    @Test
    void allNetworkErrorsLeavesStatusCodeNull() {
        registry = InMemoryProfileRegistry.of(broadcastProfile(true));
        tool = new NotifyTool(adapter, registry);

        // 三通道全网络错误
        when(adapter.send(any(NotifyChannelConfig.class), eq("hi")))
            .thenAnswer((InvocationOnMock inv) -> {
                NotifyChannelConfig c = inv.getArgument(0);
                return netErr(c.name(), "timeout after 5000ms", 5000L);
            });

        ToolResult result = tool.execute(Map.of("content", "hi"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("all failed: ");
        // 全网络错误（statusCode 全 null）→ notifyStatusCode = null
        assertThat(result.payload().get("status_code")).isNull();
    }

    @Test
    void broadcastFalseFlagFallsBackToSingleChannelRouting() {
        // broadcast=false + N>1 + channel=null → 报错（与 US-2 一致）
        registry = InMemoryProfileRegistry.of(broadcastProfile(false));
        tool = new NotifyTool(adapter, registry);

        ToolResult result = tool.execute(Map.of("content", "hi"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("channel 不能省略");
    }

    @Test
    void mixed2xxAndNon2xxPicksNon2xxAsWorst() {
        registry = InMemoryProfileRegistry.of(broadcastProfile(true));
        tool = new NotifyTool(adapter, registry);

        // c 通道 404，其他 2xx
        when(adapter.send(any(NotifyChannelConfig.class), eq("hi")))
            .thenAnswer((InvocationOnMock inv) -> {
                NotifyChannelConfig c = inv.getArgument(0);
                if ("c".equals(c.name())) {
                    return httpErr(c.name(), 404, 300L);
                }
                return ok(c.name(), 100L);
            });

        ToolResult result = tool.execute(Map.of("content", "hi"));

        assertThat(result.payload()).containsEntry("status_code", 404);
    }

    @Test
    void multipleNon2xxPicksNumericallyLargest() {
        registry = InMemoryProfileRegistry.of(broadcastProfile(true));
        tool = new NotifyTool(adapter, registry);

        // a=503, b=404, c=2xx → 取最大 503
        when(adapter.send(any(NotifyChannelConfig.class), eq("hi")))
            .thenAnswer((InvocationOnMock inv) -> {
                NotifyChannelConfig c = inv.getArgument(0);
                int code = c.name().equals("a") ? 503
                         : c.name().equals("b") ? 404 : 200;
                return code == 200 ? ok(c.name(), 100L)
                                   : httpErr(c.name(), code, 100L);
            });

        ToolResult result = tool.execute(Map.of("content", "hi"));

        // 503 > 404 → 取 503
        assertThat(result.payload()).containsEntry("status_code", 503);
    }
}