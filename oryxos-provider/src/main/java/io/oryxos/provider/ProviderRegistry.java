package io.oryxos.provider;

import io.oryxos.core.Provider;
import io.oryxos.provider.exception.UnknownProviderException;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Provider 路由注册表（{@code name → ChatModel}）。
 *
 * <p>关键约束（research.md R-01 + 宪法 §I "陷阱 #2"）：
 * <ul>
 *   <li>显式 {@code Map<String, ChatModel>}，<strong>禁止</strong>容器按类型扫描</li>
 *   <li>同 type（{@code OpenAiChatModel}）多实例并存无歧义（US-3 验收）</li>
 *   <li>启动期校验 {@link #namesAreUnique()}、{@link #allChatModelsCovered()}、{@link #allCredentialsResolved()}</li>
 * </ul>
 *
 * <p>注册流程由 {@code ChatModelConfig} 写入 + {@code ProviderAutoConfiguration}
 * 扫描容器后构造本对象；不再依赖 {@code BeanFactoryPostProcessor} 注入时机。
 */
@Component
public class ProviderRegistry {

    /** {@link Provider#name()} 合法格式：小写字母起头 + 字母数字连字符，长度 ≤ 64。 */
    private static final Pattern NAME_PATTERN =
        Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

    private final Map<String, ChatModel> providerIndex;
    private final Map<String, String> nameToModel;
    private final Map<String, String> nameToCredentialRef;

    public ProviderRegistry(Map<String, ChatModel> providerIndex,
                            Map<String, String> nameToModel,
                            Map<String, String> nameToCredentialRef) {
        this.providerIndex = Collections.unmodifiableMap(new LinkedHashMap<>(providerIndex));
        this.nameToModel = Collections.unmodifiableMap(new LinkedHashMap<>(nameToModel));
        this.nameToCredentialRef = Collections.unmodifiableMap(new LinkedHashMap<>(nameToCredentialRef));
    }

    /** 按 {@code name} 查 ChatModel；找不到抛 {@link UnknownProviderException}。 */
    public ChatModel get(String name) {
        ChatModel cm = providerIndex.get(name);
        if (cm == null) {
            throw new UnknownProviderException(name);
        }
        return cm;
    }

    /** 检查某 name 是否已注册（启动期 Profile 校验用）。 */
    public boolean containsName(String name) {
        return providerIndex.containsKey(name);
    }

    /** 所有已注册的 Provider name。 */
    public Set<String> names() {
        return providerIndex.keySet();
    }

    /** 该 Provider 配置的默认模型（{@code application.yml} 中的 {@code model}）。 */
    public String defaultModelFor(String name) {
        return nameToModel.get(name);
    }

    /** 该 Provider 配置的 {@code credentialRef}（仅用于日志，不返明文）。 */
    public String credentialRefFor(String name) {
        return nameToCredentialRef.get(name);
    }

    // --- 启动期校验工具（静态方法，供 ProviderAutoConfiguration 调用） ---

    /** 校验 name 集合是否两两不同。 */
    public static boolean namesAreUnique(List<Provider> providers) {
        Set<String> seen = new HashSet<>();
        for (Provider p : providers) {
            if (!seen.add(p.name())) {
                return false;
            }
        }
        return true;
    }

    /** 校验每条 Provider 的 name 满足 {@link #NAME_PATTERN}。 */
    public static boolean nameFormatValid(Provider p) {
        return p.name() != null && NAME_PATTERN.matcher(p.name()).matches();
    }

    /** 校验容器中所有 ChatModel Bean 都被 {@code application.yml} 中的某条 Provider 配置覆盖。 */
    public static boolean allChatModelsCovered(List<Provider> providers,
                                               Collection<String> beanNames) {
        Set<String> configured = new HashSet<>();
        providers.forEach(p -> configured.add(p.name()));
        return configured.containsAll(beanNames);
    }
}