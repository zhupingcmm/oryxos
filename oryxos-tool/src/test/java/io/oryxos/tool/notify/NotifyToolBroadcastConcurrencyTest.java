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

import java.util.ArrayList;
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
 * NotifyTool 广播并发性能测试 —— T050（US-4 NFR-003 验证）。
 *
 * <p>验证 spec NFR-002 闭式表：
 * <ul>
 *   <li>1 通道 → wall-time ≤ 3s</li>
 *   <li>2 通道 → wall-time ≤ 4s</li>
 *   <li>5 通道 → wall-time ≤ 5s</li>
 *   <li>10 通道 → wall-time ≤ 6s</li>
 * </ul>
 *
 * <p>实现要点：
 * <ul>
 *   <li>用 mock adapter 注入固定 sleep（如 100ms）模拟 HTTP 延迟</li>
 *   <li>JDK 21 virtual threads 并行；N 条 send 总耗时 ≈ 单条耗时 + overhead</li>
 *   <li>串行路径下 N×100ms 远超阈值；并行路径下应在阈值内</li>
 * </ul>
 *
 * <p>本测试仅验证"并行执行"的语义（wall-time ≪ 串行 N 倍），不要求精确测时。
 */
class NotifyToolBroadcastConcurrencyTest {

    private WebhookNotifyAdapter adapter;
    private InMemoryProfileRegistry registry;
    private NotifyTool tool;

    @BeforeEach
    void setUp() {
        adapter = mock(WebhookNotifyAdapter.class);
        ProfileContext.set(new ProfileContext.Snapshot(
            "perf-agent", UUID.randomUUID(), new AtomicInteger(1)));
    }

    @AfterEach
    void tearDown() {
        ProfileContext.clear();
    }

    private Profile broadcastProfile(int channelCount) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("broadcast", true);
        List<NotifyChannelConfig> channels = new ArrayList<>();
        for (int i = 0; i < channelCount; i++) {
            channels.add(new NotifyChannelConfig("ch" + i, "webhook",
                "http://localhost:9999/ch" + i, null));
        }
        return new Profile("perf-agent",
            new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY",
                Map.of("temperature", 0.7f)),
            List.of("notify"), List.of(), List.of(), List.of(),
            Profile.Settings.defaults(), extra, channels);
    }

    /**
     * Mock：每次 send 模拟 100ms 延迟。channelName 跟传入 arg 一致。
     * 返回 success=true（聚合全成功路径）。
     */
    private void stubSimulateLatency() {
        when(adapter.send(any(NotifyChannelConfig.class), eq("hi")))
            .thenAnswer(inv -> {
                NotifyChannelConfig c = inv.getArgument(0);
                Thread.sleep(100);
                return new NotifyResult(c.name(), true, 200, null, 100L,
                    "http://localhost:9999/" + c.name());
            });
    }

    @Test
    void oneChannelCompletesWithinBudget() {
        registry = InMemoryProfileRegistry.of(broadcastProfile(1));
        tool = new NotifyTool(adapter, registry);
        stubSimulateLatency();

        long start = System.nanoTime();
        ToolResult result = tool.execute(Map.of("content", "hi"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(result.success()).isTrue();
        // 单通道 ≤ 3s
        assertThat(elapsedMs).isLessThan(3000L);
        // 串行 100ms 也远低于 3s——这里主要验证不会触发单通道回归到串行路径
    }

    @Test
    void twoChannelsParallelCompletesWithinBudget() {
        registry = InMemoryProfileRegistry.of(broadcastProfile(2));
        tool = new NotifyTool(adapter, registry);
        stubSimulateLatency();

        long start = System.nanoTime();
        ToolResult result = tool.execute(Map.of("content", "hi"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(result.success()).isTrue();
        // 2 通道 ≤ 4s；并行 wall-time 应接近 100ms（远低于 4s）
        assertThat(elapsedMs).isLessThan(4000L);
    }

    @Test
    void fiveChannelsParallelCompletesWithinBudget() {
        registry = InMemoryProfileRegistry.of(broadcastProfile(5));
        tool = new NotifyTool(adapter, registry);
        stubSimulateLatency();

        long start = System.nanoTime();
        ToolResult result = tool.execute(Map.of("content", "hi"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(result.success()).isTrue();
        // 5 通道 ≤ 5s
        assertThat(elapsedMs).isLessThan(5000L);
    }

    @Test
    void tenChannelsParallelCompletesWithinBudget() {
        registry = InMemoryProfileRegistry.of(broadcastProfile(10));
        tool = new NotifyTool(adapter, registry);
        stubSimulateLatency();

        long start = System.nanoTime();
        ToolResult result = tool.execute(Map.of("content", "hi"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(result.success()).isTrue();
        // 10 通道 ≤ 6s
        assertThat(elapsedMs).isLessThan(6000L);
        // 串行 10*100=1000ms 也在 6s 内，但并行 ~100ms 应比串行快一个数量级
        // 这里不强加"远小于"断言，避免 CI 抖动
    }

    @Test
    void tenChannelsWallTimeMuchLessThanSerial() {
        // 验证 broadcast 确实"并行"——串行 10×100=1000ms，并行应 << 1000ms
        registry = InMemoryProfileRegistry.of(broadcastProfile(10));
        tool = new NotifyTool(adapter, registry);
        stubSimulateLatency();

        long start = System.nanoTime();
        tool.execute(Map.of("content", "hi"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        // 串行 baseline = 1000ms；并行预期 ~100-200ms（包含线程创建 + 调度）
        // 阈值 600ms 留足 CI 抖动空间，远小于 1000ms
        assertThat(elapsedMs)
            .as("10 通道广播 wall-time 应显著小于串行 1000ms (实测: %d ms)", elapsedMs)
            .isLessThan(600L);
    }
}