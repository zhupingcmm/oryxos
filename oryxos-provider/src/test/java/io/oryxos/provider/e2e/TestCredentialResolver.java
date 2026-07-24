package io.oryxos.provider.e2e;

import io.oryxos.provider.CredentialResolver;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 测试专用 {@link CredentialResolver}：先查 {@link System#getProperty(String)}（VM 参数 / -D 注入），
 * 找不到再查 {@link System#getenv(String)}，最后失败抛 {@link IllegalStateException}。
 *
 * <p><strong>仅用于集成测试</strong>——{@code @Primary} 会覆盖生产 bean。
 * 真实部署仍然受生产 {@code CredentialResolver} 守门，env 必须存在。
 */
@Component
@Primary
public class TestCredentialResolver extends CredentialResolver {

    /** 完全复用父类的 ${ENV_VAR} 形态校验。 */
    private static final Pattern PLACEHOLDER_PATTERN =
        Pattern.compile("^\\$\\{([A-Z_][A-Z0-9_]*)\\}$");

    @Override
    public String resolve(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new IllegalStateException(
                "credentialRef is missing or blank. " +
                "Expected form: ${ENV_VAR}. Refusing to start.");
        }
        var matcher = PLACEHOLDER_PATTERN.matcher(credentialRef);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                "credentialRef '" + credentialRef + "' must match the pattern ${ENV_VAR}. " +
                "Refusing to start.");
        }
        String envVar = matcher.group(1);
        String prop = System.getProperty(envVar);
        if (prop != null && !prop.isBlank()) return prop;
        String env = System.getenv(envVar);
        if (env != null && !env.isBlank()) return env;
        throw new IllegalStateException(
            "Neither System property nor environment variable '" + envVar +
            "' is set. Refusing to start.");
    }
}
