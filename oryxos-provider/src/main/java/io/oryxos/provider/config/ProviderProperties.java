package io.oryxos.provider.config;

import io.oryxos.provider.Provider;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 实例级 Provider 目录的 {@code @ConfigurationProperties} 绑定。
 *
 * <p>对应 {@code application.yml}：
 * <pre>{@code
 * oryxos:
 *   providers:
 *     - name: deepseek
 *       model: deepseek-chat
 *       credentialRef: ${DEEPSEEK_API_KEY}
 *       options:
 *         temperature: 0.5
 * }</pre>
 *
 * <p>字段级校验（非空 / 唯一 / 正则匹配）由 {@code ProviderRegistry} 在启动期执行，
 * 不在本类做——本类只做绑定 + getter，避免 Spring Boot 配置元数据膨胀。
 */
@ConfigurationProperties(prefix = "oryxos.providers")
public class ProviderProperties {

    /** {@code oryxos.providers} 列表；空列表会导致启动失败（{@code ProviderRegistry} 校验）。 */
    private List<Provider> providers = List.of();

    public List<Provider> getProviders() {
        return providers;
    }

    public void setProviders(List<Provider> providers) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }
}