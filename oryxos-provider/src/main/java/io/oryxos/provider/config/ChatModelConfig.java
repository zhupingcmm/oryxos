package io.oryxos.provider.config;

import io.oryxos.provider.CredentialResolver;
import io.oryxos.core.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * 三个 Provider 的 {@link ChatModel} 工厂。
 *
 * <p>关键约束（research.md R-01）：每个 bean 用 {@link Provider#name()} 显式命名，
 * {@code ProviderRegistry} 按 {@code name → ChatModel} 查表，
 * <strong>禁止</strong>容器按类型扫描。
 *
 * <p>三个 Provider 全部走 OpenAI 兼容协议（research.md R-03）：
 * <ul>
 *   <li>{@code deepseek} → {@code https://api.deepseek.com}</li>
 *   <li>{@code qwen}     → {@code https://dashscope.aliyuncs.com/compatible-mode}</li>
 *   <li>{@code minaimax}   → {@code https://api.minimax.chat/v1}</li>
 * </ul>
 *
 * <p>同一类型可同时注册多个实例（如 {@code deepseek-prod} + {@code deepseek-dev}），
 * 由 {@code ProviderRegistry} 通过 bean name 区分；无 {@code @Primary} 冲突（宪法 §I "陷阱 #2"）。
 *
 * <p><strong>本类不做 Bean 注册</strong>——{@code @Bean} 写法的 name 必须在源代码里静态写死，
 * 没法随 {@code application.yml} 动态变。这里只提供 {@link #build(Provider)} 工厂方法，
 * 由 {@code ProviderAutoConfiguration} 在启动期按 {@code Provider.name()} 逐个
 * {@code beanFactory.registerSingleton(...)} 注册。
 */
@Component
public class ChatModelConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatModelConfig.class);

    private static final Map<String, String> DEFAULT_BASE_URLS = Map.of(
        "deepseek", "https://api.deepseek.com",
        "qwen",     "https://dashscope.aliyuncs.com/compatible-mode",
        "minimax",  "https://api.minimax.chat/v1"
    );

    private final CredentialResolver credentialResolver;

    public ChatModelConfig(CredentialResolver credentialResolver) {
        this.credentialResolver = credentialResolver;
    }

    /** 该 Provider 配置的最后生效 base URL（默认表兜底）。 */
    public static String defaultBaseUrlFor(Provider provider) {
        return DEFAULT_BASE_URLS.getOrDefault(provider.name(), DEFAULT_BASE_URLS.get("minimax"));
    }

    /**
     * 为单条 {@link Provider} 配置构造 {@link ChatModel}。
     *
     * <p>调用方负责解决凭证；{@link CredentialResolver#resolve(String)} 已 fail-fast。
     */
    public ChatModel build(Provider provider) {
        String apiKey = credentialResolver.resolve(provider.credentialRef());
        String baseUrl = provider.endpoint() != null && !provider.endpoint().isBlank()
            ? provider.endpoint()
            : defaultBaseUrlFor(provider);

        OpenAiApi api = OpenAiApi.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .restClientBuilder(restClientBuilder())
            .build();

        OpenAiChatOptions.Builder opts = OpenAiChatOptions.builder()
            .model(provider.model());

        Object temperature = provider.options().get("temperature");
        if (temperature instanceof Number n) {
            opts.temperature(n.doubleValue());
        }
        Object maxTokens = provider.options().get("maxTokens");
        if (maxTokens instanceof Number n) {
            opts.maxTokens(n.intValue());
        }

        OpenAiChatModel chatModel = new OpenAiChatModel(api, opts.build(), null, RetryTemplate.builder()
            .maxAttempts(1)
            .retryOn(Exception.class)
            .build());
        log.info("Built ChatModel for provider '{}' (model={}, baseUrl={})",
            provider.name(), provider.model(), baseUrl);
        return chatModel;
    }

    /**
     * 构 {@link RestClient} 用 JDK HttpClient 强制 HTTP/1.1 + 合理超时。
     *
     * <p>JDK HttpClient 默认 HTTP/2；WireMock 等测试服务默认 HTTP/1.1 时会抛 {@code Stream cancelled}。
     * 生产同样受益——HTTP/1.1 兼容性优于 HTTP/2（LLM 供应商网关普遍 HTTP/1.1）。
     */
    private static RestClient.Builder restClientBuilder() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder().requestFactory(factory);
    }
}
