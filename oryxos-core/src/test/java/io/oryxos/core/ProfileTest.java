package io.oryxos.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Profile record 的单测 —— T015。
 *
 * <p>重点验证 US-4 Notify 引入的第 9 字段 {@code notifyChannels}：
 * <ul>
 *   <li>默认行为：null → {@link List#of()}（而非抛 NPE）</li>
 *   <li>不可变：传入可变 {@link java.util.ArrayList} → 内部 {@link List#copyOf}</li>
 *   <li>访问器返回构造时的值</li>
 * </ul>
 */
class ProfileTest {

    private static final Provider PROVIDER = new Provider("deepseek", "deepseek-chat", null, "X_API_KEY", Map.of());

    @Test
    void defaultNotifyChannelsIsEmptyWhenNull() {
        Profile p = new Profile(
            "weather-bot", PROVIDER, List.of("notify"),
            List.of(), List.of(), List.of(),
            Profile.Settings.defaults(),
            Map.of(),
            null                       // notifyChannels = null
        );
        assertNotNull(p.notifyChannels());
        assertTrue(p.notifyChannels().isEmpty());
    }

    @Test
    void providedNotifyChannelsArePreserved() {
        List<NotifyChannelConfig> channels = List.of(
            new NotifyChannelConfig("default", "webhook", "https://qyapi.weixin.qq.com/hook", null),
            new NotifyChannelConfig("feishu-tech", "webhook", "https://open.feishu.cn/hook", "secret")
        );
        Profile p = new Profile(
            "weather-bot", PROVIDER, List.of("notify"),
            List.of(), List.of(), List.of(),
            Profile.Settings.defaults(),
            Map.of(),
            channels
        );
        assertEquals(2, p.notifyChannels().size());
        assertEquals("default", p.notifyChannels().get(0).name());
        assertEquals("feishu-tech", p.notifyChannels().get(1).name());
        assertEquals("secret", p.notifyChannels().get(1).secret());
    }

    @Test
    void notifyChannelsAreImmutable() {
        java.util.List<NotifyChannelConfig> mutable = new java.util.ArrayList<>();
        mutable.add(new NotifyChannelConfig("a", "webhook", "https://example.com/h", null));
        Profile p = new Profile(
            "bot", PROVIDER, List.of("notify"),
            List.of(), List.of(), List.of(),
            Profile.Settings.defaults(),
            Map.of(),
            mutable
        );
        // Profile 构造时已 List.copyOf → 外部修改不影响内部
        mutable.clear();
        assertEquals(1, p.notifyChannels().size());
        assertThrows(UnsupportedOperationException.class,
            () -> p.notifyChannels().add(
                new NotifyChannelConfig("x", "webhook", "https://example.com/x", null)));
    }

    @Test
    void existingProfileInvariantsStillEnforced() {
        // name 不合法 → 抛 IAE
        assertThrows(IllegalArgumentException.class, () -> new Profile(
            "INVALID_NAME", PROVIDER, List.of(),
            List.of(), List.of(), List.of(),
            Profile.Settings.defaults(),
            Map.of(),
            List.of()
        ));
        // provider 为 null → 抛 NPE
        assertThrows(NullPointerException.class, () -> new Profile(
            "ok", null, List.of(),
            List.of(), List.of(), List.of(),
            Profile.Settings.defaults(),
            Map.of(),
            List.of()
        ));
    }

    @Test
    void allExistingAccessorsStillWork() {
        // 验证 9 字段访问器全部就位（防止字段顺序漂移）
        NotifyChannelConfig ch = new NotifyChannelConfig("d", "webhook", "https://e/h", null);
        Profile p = new Profile(
            "bot", PROVIDER, List.of("t1", "t2"),
            List.of("m1"), List.of("b1"), List.of("s1"),
            new Profile.Settings(15, 30),
            Map.of("k", "v"),
            List.of(ch)
        );
        assertEquals("bot", p.name());
        assertSame(PROVIDER, p.provider());
        assertEquals(List.of("t1", "t2"), p.tools());
        assertEquals(List.of("m1"), p.mcpServers());
        assertEquals(List.of("b1"), p.bootstrap());
        assertEquals(List.of("s1"), p.skills());
        assertEquals(15, p.settings().maxIterations());
        assertEquals(30, p.settings().maxHistoryTurns());
        assertEquals(Map.of("k", "v"), p.extra());
        assertEquals(1, p.notifyChannels().size());
        assertEquals("d", p.notifyChannels().get(0).name());
    }
}