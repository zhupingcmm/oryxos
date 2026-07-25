package io.oryxos.provider;

import io.oryxos.provider.exception.UnknownProviderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * US-3 同 type（{@link ChatModel}）多 Provider 共存单元测试。
 *
 * <p>不靠 Spring——直接 new {@link ProviderRegistry}，验证 {@code name → ChatModel} 显式
 * 映射在容器外仍能正确路由，避免依赖 Spring 的 bean 名解析路径干扰断言。
 *
 * <p>对应 SC-004：同 type 多实例并存无歧义。
 */
@DisplayName("ProviderRegistry 同 type 多 Provider")
class ProviderRegistryMultiInstanceTest {

    @Test
    @DisplayName("两个 ChatModel 同 type（如 OpenAiChatModel）+ 不同 name 都能注册并互不混淆")
    void sameTypeDifferentNamesBothRegistered() {
        ChatModel prod = mock(ChatModel.class);
        ChatModel dev  = mock(ChatModel.class);

        ProviderRegistry registry = new ProviderRegistry(
            java.util.Map.of(
                "deepseek-prod", prod,
                "deepseek-dev",  dev
            ),
            java.util.Map.of(
                "deepseek-prod", "deepseek-chat",
                "deepseek-dev",  "deepseek-coder"
            ),
            java.util.Map.of(
                "deepseek-prod", "${DEEPSEEK_PROD_API_KEY}",
                "deepseek-dev",  "${DEEPSEEK_DEV_API_KEY}"
            )
        );

        assertThat(registry.names())
            .containsExactlyInAnyOrder("deepseek-prod", "deepseek-dev");

        assertThat(registry.get("deepseek-prod")).isSameAs(prod);
        assertThat(registry.get("deepseek-dev")).isSameAs(dev);

        assertThat(registry.defaultModelFor("deepseek-prod")).isEqualTo("deepseek-chat");
        assertThat(registry.defaultModelFor("deepseek-dev")).isEqualTo("deepseek-coder");

        assertThat(registry.credentialRefFor("deepseek-prod")).isEqualTo("${DEEPSEEK_PROD_API_KEY}");
        assertThat(registry.credentialRefFor("deepseek-dev")).isEqualTo("${DEEPSEEK_DEV_API_KEY}");
    }

    @Test
    @DisplayName("查询未注册的 name 抛 UnknownProviderException")
    void unknownNameThrows() {
        ProviderRegistry registry = new ProviderRegistry(
            java.util.Map.of("qwen", mock(ChatModel.class)),
            java.util.Map.of("qwen", "qwen-plus"),
            java.util.Map.of("qwen", "${QWEN_API_KEY}")
        );

        assertThatThrownBy(() -> registry.get("nonexistent-model"))
            .isInstanceOf(UnknownProviderException.class)
            .hasMessageContaining("nonexistent-model");

        assertThat(registry.containsName("qwen")).isTrue();
        assertThat(registry.containsName("nonexistent-model")).isFalse();
    }

    @Test
    @DisplayName("启动期静态校验：同 type 多实例允许；name 重复时拒启动")
    void uniqueNameValidation() {
        var p1 = new Provider("deepseek-prod", "deepseek-chat",  null, "${DEEPSEEK_PROD_API_KEY}", java.util.Map.of());
        var p2 = new Provider("deepseek-prod", "deepseek-coder", null, "${DEEPSEEK_DEV_API_KEY}",  java.util.Map.of()); // 重复 name
        var p3 = new Provider("qwen",         "qwen-plus",      null, "${QWEN_API_KEY}",         java.util.Map.of());

        assertThat(ProviderRegistry.namesAreUnique(java.util.List.of(p1, p3))).isTrue();
        assertThat(ProviderRegistry.namesAreUnique(java.util.List.of(p1, p2))).isFalse();
    }

    @Test
    @DisplayName("name 格式校验：必须 ^[a-z][a-z0-9-]{0,63}$；大写 / 数字开头 / 带下划线都拒")
    void nameFormatEnforced() {
        var valid = new Provider("deepseek-prod", "deepseek-chat", null, "${X}", java.util.Map.of());
        assertThat(ProviderRegistry.nameFormatValid(valid)).isTrue();

        // 不允许的几种形态：
        var upper   = new Provider("DeepSeek-Prod", "x", null, "${X}", java.util.Map.of());
        var digit0  = new Provider("0deepseek",     "x", null, "${X}", java.util.Map.of());
        var under   = new Provider("deepseek_prod", "x", null, "${X}", java.util.Map.of());
        var tooLong = new Provider("a".repeat(65),  "x", null, "${X}", java.util.Map.of());
        var blank   = new Provider("",              "x", null, "${X}", java.util.Map.of());

        assertThat(ProviderRegistry.nameFormatValid(upper)).isFalse();
        assertThat(ProviderRegistry.nameFormatValid(digit0)).isFalse();
        assertThat(ProviderRegistry.nameFormatValid(under)).isFalse();
        assertThat(ProviderRegistry.nameFormatValid(tooLong)).isFalse();
        assertThat(ProviderRegistry.nameFormatValid(blank)).isFalse();
    }
}
