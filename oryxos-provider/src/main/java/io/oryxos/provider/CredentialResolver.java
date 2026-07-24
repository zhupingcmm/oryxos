package io.oryxos.provider;

import org.springframework.stereotype.Component;

/**
 * 凭证非空校验（启动期 fail-fast）。
 *
 * <p>职责（spec FR-002 / FR-003 + 宪法 Additional Constraints）：
 * <ol>
 *   <li>{@link #resolve(String)} 入参非 null / 非 blank；否则抛 {@link IllegalStateException}</li>
 *   <li>真实的环境变量解析由 Spring 的 {@code @ConfigurationProperties} 占位符解析完成：
 *       {@code credentialRef: ${DEEPSEEK_API_KEY}} 在绑定阶段就被解析为真实字符串，
 *       若 {@code DEEPSEEK_API_KEY} 未设置则直接抛 {@code PlaceholderResolutionException}，
 *       进程退出（fail-fast 最早一层）</li>
 *   <li>本类作为<strong>第二层兜底</strong>，专门捕捉"有人绕过 Spring 占位符直接
 *       注入空白字符串凭证"的情况</li>
 * </ol>
 *
 * <p>之所以不做 {@code ${ENV_VAR}} 形态校验：{@code @ConfigurationProperties} 绑定
 * 时 Spring 已把占位符解析成实际值，到达本方法时入参已经是真实凭证（不是占位符）。
 * 强校验形态必然误伤——{@link TestCredentialResolver}（测试）能解析占位符只是因为
 * e2e 测试绕开了 {@code @ConfigurationProperties} 绑定走 {@code setProviders(...)} 程序化注入。
 */
@Component
public class CredentialResolver {

    public CredentialResolver() {
        // 无状态；保留默认构造器以便 Spring 实例化
    }

    /**
     * 校验凭证字符串非空。
     *
     * @param credentialRef 已由 Spring 占位符解析过的 {@code application.yml} 中的
     *                      {@code credentialRef} 字段值；非 null / 非 blank
     * @return 入参本身（非空字符串）
     * @throws IllegalStateException 入参为 null 或 blank
     */
    public String resolve(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new IllegalStateException(
                "credentialRef is missing or blank. " +
                "Set the referenced environment variable. Refusing to start.");
        }
        return credentialRef;
    }
}