package io.oryxos.cli.config;

import io.oryxos.core.NotifyChannelConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ConfigLoader.parseNotifyChannels 单测 —— T027。
 *
 * <p>覆盖 5 种用例（contracts/channel-config.md §1.2 + §2）：
 * <ul>
 *   <li>合法 YAML（含 {@code ${ENV_VAR}} 占位符已替换）</li>
 *   <li>缺 {@code url} 字段</li>
 *   <li>未知 {@code type}（非 "webhook"）</li>
 *   <li>{@code name} 重复</li>
 *   <li>{@code notify_channels} 字段缺失 → 空列表</li>
 * </ul>
 *
 * <p>环境变量缺失（MissingEnvVarException）由 {@link ConfigLoader} 自身的占位符解析覆盖，
 * 不在本测试范围。
 */
class ConfigLoaderNotifyChannelsTest {

    @Test
    void parsesValidChannels() {
        Map<String, Object> fm = Map.of(
            "notify_channels", List.of(
                Map.of("name", "default", "type", "webhook",
                    "url", "https://qyapi.weixin.qq.com/hook?key=ABC"),
                Map.of("name", "feishu-tech", "type", "webhook",
                    "url", "https://open.feishu.cn/hook", "secret", "shh")
            )
        );
        List<NotifyChannelConfig> result = ConfigLoader.parseNotifyChannels(fm, "weather-bot");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("default");
        assertThat(result.get(0).type()).isEqualTo("webhook");
        assertThat(result.get(0).url()).startsWith("https://qyapi.weixin.qq.com");
        assertThat(result.get(1).name()).isEqualTo("feishu-tech");
        assertThat(result.get(1).secret()).isEqualTo("shh");
    }

    @Test
    void returnsEmptyListWhenFieldMissing() {
        Map<String, Object> fm = Map.of("name", "weather-bot", "tools", List.of("notify"));
        List<NotifyChannelConfig> result = ConfigLoader.parseNotifyChannels(fm, "weather-bot");
        assertThat(result).isEmpty();
    }

    @Test
    void rejectsMissingUrlField() {
        Map<String, Object> ch = new java.util.LinkedHashMap<>();
        ch.put("name", "x");
        ch.put("type", "webhook");
        // 故意缺 url
        Map<String, Object> fm = Map.of("notify_channels", List.of(ch));

        assertThatThrownBy(() -> ConfigLoader.parseNotifyChannels(fm, "weather-bot"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing required field: url");
    }

    @Test
    void rejectsUnknownType() {
        Map<String, Object> fm = Map.of(
            "notify_channels", List.of(
                Map.of("name", "x", "type", "smtp", "url", "https://example.com")
            )
        );

        assertThatThrownBy(() -> ConfigLoader.parseNotifyChannels(fm, "weather-bot"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported notify_channel type")
            .hasMessageContaining("smtp");
    }

    @Test
    void rejectsDuplicateName() {
        Map<String, Object> fm = Map.of(
            "notify_channels", List.of(
                Map.of("name", "default", "type", "webhook", "url", "https://e1.com"),
                Map.of("name", "default", "type", "webhook", "url", "https://e2.com")
            )
        );

        assertThatThrownBy(() -> ConfigLoader.parseNotifyChannels(fm, "weather-bot"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate notify_channels name")
            .hasMessageContaining("default");
    }

    @Test
    void rejectsInvalidNamePattern() {
        Map<String, Object> fm = Map.of(
            "notify_channels", List.of(
                Map.of("name", "BadName", "type", "webhook", "url", "https://e.com")
            )
        );

        assertThatThrownBy(() -> ConfigLoader.parseNotifyChannels(fm, "weather-bot"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("BadName");
    }

    @Test
    void rejectsNonListRoot() {
        Map<String, Object> fm = Map.of("notify_channels", "not-a-list");
        assertThatThrownBy(() -> ConfigLoader.parseNotifyChannels(fm, "weather-bot"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be a list");
    }

    @Test
    void rejectsNonMappingEntry() {
        Map<String, Object> fm = Map.of(
            "notify_channels", List.of("not-a-mapping")
        );
        assertThatThrownBy(() -> ConfigLoader.parseNotifyChannels(fm, "weather-bot"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("notify_channels[0]")
            .hasMessageContaining("not a mapping");
    }

    @Test
    void handlesNullFrontmatter() {
        // 防御性：null frontmatter → 空列表（不抛 NPE）
        List<NotifyChannelConfig> result = ConfigLoader.parseNotifyChannels(null, "any");
        assertThat(result).isEmpty();
    }
}