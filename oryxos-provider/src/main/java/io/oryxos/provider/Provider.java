package io.oryxos.provider;

import java.util.Collections;
import java.util.Map;

/**
 * Provider 实例级配置（{@code application.yml} 中 {@code oryxos.providers.*} 的一条）。
 *
 * <p>由 {@code CredentialResolver} 把 {@link #credentialRef} 解析为真实 API key
 * 后注入到 {@code ChatModel} Bean；本 record 自身不持有明文凭证。
 *
 * <p>字段约束见 [application-provider-config.md](../../../../specs/001-llm-provider-routing/contracts/application-provider-config.md)。
 *
 * @param name          路由键；全局唯一；匹配 {@code ^[a-z][a-z0-9-]{0,63}$}
 * @param model         模型标识（如 {@code deepseek-chat} / {@code qwen-plus} / {@code MiniMax-M3}）
 * @param endpoint      自定义 base URL；为 null 走 Provider 类型默认
 * @param credentialRef 环境变量名（如 {@code DEEPSEEK_API_KEY}），启动期被解析
 * @param options       Provider 私有参数（{@code temperature} / {@code top_p} / {@code maxTokens} 等）
 */
public record Provider(
    String name,
    String model,
    String endpoint,
    String credentialRef,
    Map<String, Object> options
) {
    public Provider {
        options = options == null ? Map.of() : Collections.unmodifiableMap(options);
    }
}