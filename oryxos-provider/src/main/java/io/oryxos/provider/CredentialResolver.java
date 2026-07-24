package io.oryxos.provider;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 凭证环境变量解析器（启动期 fail-fast）。
 *
 * <p>契约（spec FR-002 / FR-003 + 宪法 Additional Constraints）：
 * <ol>
 *   <li>{@link #resolve(String)} 入参必须是 {@code ${ENV_VAR}} 形态</li>
 *   <li>环境变量必须存在且非空</li>
 *   <li>任一违反即抛 {@link IllegalStateException}，<strong>不</strong>延迟到首次调用</li>
 * </ol>
 *
 * <p>此解析器是启动期双保险的第一层（第二层是 Spring 的 {@code ${...}} 占位符，
 * 在更早的 {@code Environment} 阶段就触发 {@code PlaceholderResolutionException}）。
 * 这一层专门捕捉"有人绕过 Spring 占位符直接注入字符串凭证"的情况。
 */
@Component
public class CredentialResolver {

    /**
     * 占位符形态正则：{@code ${SOME_ENV_VAR}}，变量名仅允许大写字母 / 数字 / 下划线。
     */
    private static final Pattern PLACEHOLDER_PATTERN =
        Pattern.compile("^\\$\\{([A-Z_][A-Z0-9_]*)\\}$");

    public CredentialResolver() {
        // 无状态；保留默认构造器以便 Spring 实例化
    }

    /**
     * 解析 {@code ${ENV_VAR}} 占位符为真实凭证字符串。
     *
     * @param credentialRef {@code application.yml} 中的 {@code credentialRef} 字段
     * @return 环境变量值（非空）
     * @throws IllegalStateException 入参不是 {@code ${ENV_VAR}} 形态、环境变量未设置或为空
     */
    public String resolve(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new IllegalStateException(
                "credentialRef is missing or blank. " +
                "Expected form: ${ENV_VAR}. Refusing to start.");
        }
        var matcher = PLACEHOLDER_PATTERN.matcher(credentialRef);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                "credentialRef '" + credentialRef + "' must match the pattern ${ENV_VAR} " +
                "(uppercase letters / digits / underscores only). Refusing to start.");
        }
        String envVar = matcher.group(1);
        String value = System.getenv(envVar);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Environment variable '" + envVar + "' is not set or is empty. " +
                "Provider cannot start without credentials. Refusing to start.");
        }
        return value;
    }
}