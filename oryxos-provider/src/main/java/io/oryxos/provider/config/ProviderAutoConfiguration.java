package io.oryxos.provider.config;

import io.oryxos.core.Provider;
import io.oryxos.provider.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provider 路由层 Spring Boot 自动配置入口。
 *
 * <p>职责：
 * <ol>
 *   <li>启用 {@link ProviderProperties} 配置绑定</li>
 *   <li>启动期对 {@code oryxos.providers.*} 中每条 Provider 调
 *       {@link ChatModelConfig#build(Provider)} 构造 {@link ChatModel}，
 *       <strong>按 {@link Provider#name()} 显式名注册</strong>到容器
 *       （{@code beanFactory.registerSingleton}）</li>
 *   <li>从容器按 name 取回，构造 {@link ProviderRegistry}</li>
 *   <li>启动期校验：name 唯一、name 合法、所有 ChatModel bean 都被覆盖；
 *       任一校验失败抛 {@link IllegalStateException}，进程退出</li>
 * </ol>
 *
 * <p>由 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} 加载。
 */
@AutoConfiguration
@EnableConfigurationProperties(ProviderProperties.class)
public class ProviderAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProviderAutoConfiguration.class);

    private final ProviderProperties providerProperties;
    private final ChatModelConfig chatModelConfig;
    private final ApplicationContext applicationContext;

    public ProviderAutoConfiguration(ProviderProperties providerProperties,
                                     ChatModelConfig chatModelConfig,
                                     ApplicationContext applicationContext) {
        this.providerProperties = providerProperties;
        this.chatModelConfig = chatModelConfig;
        this.applicationContext = applicationContext;
    }

    /**
     * 启动期按 {@code application.yml} 中每条 {@link Provider} 显式名注册 {@link ChatModel}。
     *
     * <p>这一步<strong>必须在</strong> {@link #providerRegistry()} 之前完成，
     * 因为 registry 按 bean name 取回。Spring 在调用 {@code @Bean} 方法之前已完成
     * {@link ConfigurableListableBeanFactory} 的初始化，因此 {@code registerSingleton} 合法。
     */
    @Bean
    public ChatModelRegistrar chatModelRegistrar() {
        ConfigurableListableBeanFactory bf =
            (ConfigurableListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
        for (Provider p : providerProperties.getProviders()) {
            ChatModel cm = chatModelConfig.build(p);
            bf.registerSingleton(p.name(), cm);
        }
        return new ChatModelRegistrar();
    }

    /**
     * 构造 {@link ProviderRegistry}：按 {@code Provider.name()} 从容器取回已注册的
     * {@link ChatModel} Bean，并把 {@code model} / {@code credentialRef} 一并存入。
     *
     * <p>{@link DependsOn} 强制 {@link #chatModelRegistrar()} 先执行——后者把每个
     * Provider 对应的 {@link ChatModel} 用显式 name 注册到容器，前者才能 {@code getBean} 拿到。
     */
    @Bean
    @DependsOn("chatModelRegistrar")
    public ProviderRegistry providerRegistry() {
        List<Provider> providers = providerProperties.getProviders();

        Map<String, ChatModel> index = new HashMap<>();
        Map<String, String> nameToModel = new HashMap<>();
        Map<String, String> nameToCredentialRef = new HashMap<>();

        for (Provider p : providers) {
            ChatModel cm = applicationContext.getBean(p.name(), ChatModel.class);
            index.put(p.name(), cm);
            nameToModel.put(p.name(), p.model());
            nameToCredentialRef.put(p.name(), p.credentialRef());
        }
        return new ProviderRegistry(index, nameToModel, nameToCredentialRef);
    }

    /**
     * 启动期校验：任一违反抛 {@link IllegalStateException}（fail-fast）。
     *
     * <p>实现为独立的 {@code @Bean} 方法，{@link DependsOn @DependsOn("chatModelRegistrar")}
     * 强制 {@link #chatModelRegistrar()} 先于本方法执行——后者把每个 Provider 对应的
     * {@link ChatModel} 用显式 name 注册到容器，本方法才能 {@code getBeanNamesForType}
     * 拿到并做"全覆盖"校验。
     *
     * <p>不能放在本类的 {@code @PostConstruct}：{@code @PostConstruct} 在 Spring
     * 完成本类字段注入后立即执行，但本类定义的 {@code @Bean} 方法（{@code chatModelRegistrar}）
     * 要等到其他 bean 引用时才被调用——顺序不对会导致校验看到 0 个 ChatModel 而误报。
     */
    @Bean
    @DependsOn("chatModelRegistrar")
    public ChatModelRegistrar providerStartupValidator() {
        List<Provider> providers = providerProperties.getProviders();
        log.info("ProviderRegistry startup validation: {} provider(s) configured",
            providers.size());

        if (providers.isEmpty()) {
            throw new IllegalStateException(
                "oryxos.providers is empty. At least one Provider must be configured. Refusing to start.");
        }
        if (!ProviderRegistry.namesAreUnique(providers)) {
            throw new IllegalStateException(
                "oryxos.providers contains duplicate 'name' entries. Refusing to start.");
        }
        for (Provider p : providers) {
            if (!ProviderRegistry.nameFormatValid(p)) {
                throw new IllegalStateException(
                    "Provider name '" + p.name() + "' must match ^[a-z][a-z0-9-]{0,63}$. Refusing to start.");
            }
            if (p.model() == null || p.model().isBlank()) {
                throw new IllegalStateException(
                    "Provider '" + p.name() + "' has blank 'model'. Refusing to start.");
            }
        }

        String[] chatModelBeans = applicationContext.getBeanNamesForType(ChatModel.class);
        Set<String> beanNames = new HashSet<>(List.of(chatModelBeans));
        if (!ProviderRegistry.allChatModelsCovered(providers, beanNames)) {
            throw new IllegalStateException(
                "ChatModel bean names " + beanNames +
                " do not fully match application.yml providers " + providers.stream().map(Provider::name).toList() +
                ". Refusing to start.");
        }

        log.info("ProviderRegistry validated OK. Active providers: {}", providers.stream().map(Provider::name).toList());
        return new ChatModelRegistrar();
    }

    /** 单纯用于触发 {@link #chatModelRegistrar()} Bean 顺序的占位类型。 */
    public static final class ChatModelRegistrar {
        // unused; exists solely so @Bean method returns a value
    }
}
