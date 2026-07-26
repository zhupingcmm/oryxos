package io.oryxos.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * NotifyChannelConfig record 校验单测 —— TDD 红色先写。
 *
 * <p>覆盖合法 + 4 种非法用例。
 */
class NotifyChannelConfigTest {

    @Test
    void acceptsValidWebhookChannel() {
        assertDoesNotThrow(() -> new NotifyChannelConfig(
            "default", "webhook", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send", null));
        assertDoesNotThrow(() -> new NotifyChannelConfig(
            "feishu-tech", "webhook", "http://localhost:8089/hook/feishu", "secret-value"));
    }

    @Test
    void rejectsInvalidName() {
        assertThrows(IllegalArgumentException.class, () -> new NotifyChannelConfig(
            "Default", "webhook", "https://example.com/hook", null)); // 大写
        assertThrows(IllegalArgumentException.class, () -> new NotifyChannelConfig(
            "-bad", "webhook", "https://example.com/hook", null));      // 开头非字母
        assertThrows(IllegalArgumentException.class, () -> new NotifyChannelConfig(
            "a".repeat(65), "webhook", "https://example.com/hook", null)); // 超过 64
    }

    @Test
    void rejectsUnknownType() {
        assertThrows(IllegalArgumentException.class, () -> new NotifyChannelConfig(
            "x", "smtp", "https://example.com", null));
        assertThrows(IllegalArgumentException.class, () -> new NotifyChannelConfig(
            "x", "WEBHOOK", "https://example.com", null)); // 大小写敏感
    }

    @Test
    void rejectsInvalidUrl() {
        assertThrows(IllegalArgumentException.class, () -> new NotifyChannelConfig(
            "x", "webhook", "ftp://example.com/hook", null));
        assertThrows(IllegalArgumentException.class, () -> new NotifyChannelConfig(
            "x", "webhook", "not-a-url", null));
        assertThrows(IllegalArgumentException.class, () -> new NotifyChannelConfig(
            "x", "webhook", "http://", null)); // 空 host
    }

    @Test
    void rejectsNullRequiredFields() {
        assertThrows(NullPointerException.class, () -> new NotifyChannelConfig(
            null, "webhook", "https://example.com", null));
        assertThrows(NullPointerException.class, () -> new NotifyChannelConfig(
            "x", null, "https://example.com", null));
        assertThrows(NullPointerException.class, () -> new NotifyChannelConfig(
            "x", "webhook", null, null));
    }

    @Test
    void recordAccessorsReturnConstructorValues() {
        NotifyChannelConfig ch = new NotifyChannelConfig(
            "a", "webhook", "https://example.com", "s");
        assertEquals("a", ch.name());
        assertEquals("webhook", ch.type());
        assertEquals("https://example.com", ch.url());
        assertEquals("s", ch.secret());
    }
}