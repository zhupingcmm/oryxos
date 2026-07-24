package io.oryxos.provider.e2e;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.oryxos.provider.Provider;
import io.oryxos.provider.config.ProviderProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;
import java.util.Map;

/**
 * ProviderRoutingE2ETest 的最小可启动 Spring 上下文。
 *
 * <ul>
 *   <li>{@link #deepseekWireMock()} / {@link #qwenWireMock()} 在 Spring 启动时启动 WireMock，
 *       端口以 bean 形式暴露</li>
 *   <li>{@link #providerProperties()} 通过 {@link DependsOn} 等 WireMock 先就绪，
 *       端口动态读出</li>
 *   <li>排除 Spring AI 单实例 ChatModel bean（破坏显式 name 路由）</li>
 *   <li>静态代码块注入 system property，由 {@code TestCredentialResolver}（{@code @Primary}）兜底读取</li>
 * </ul>
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
    org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration.class
})
@EntityScan(basePackages = "io.oryxos.storage.entity")
@EnableJpaRepositories(basePackages = "io.oryxos.storage.repository")
@ComponentScan(basePackages = {
    "io.oryxos.provider",
    "io.oryxos.provider.e2e"
})
public class E2ETestApp {

    static {
        if (System.getProperty("DEEPSEEK_API_KEY") == null) {
            System.setProperty("DEEPSEEK_API_KEY", "test-deepseek-key");
        }
        if (System.getProperty("QWEN_API_KEY") == null) {
            System.setProperty("QWEN_API_KEY", "test-qwen-key");
        }
        if (System.getProperty("MINIMAX_API_KEY") == null) {
            System.setProperty("MINIMAX_API_KEY", "test-minimax-key");
        }
    }

    @Bean(destroyMethod = "stop")
    public WireMockServer deepseekWireMock() {
        WireMockServer s = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        s.start();
        return s;
    }

    @Bean(destroyMethod = "stop")
    public WireMockServer qwenWireMock() {
        WireMockServer s = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        s.start();
        return s;
    }

    @Bean(destroyMethod = "stop")
    public WireMockServer minimaxWireMock() {
        WireMockServer s = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        s.start();
        return s;
    }

    @Bean
    @Primary
    @DependsOn({ "deepseekWireMock", "qwenWireMock", "minimaxWireMock" })
    public ProviderProperties providerProperties(
            @Qualifier("deepseekWireMock") WireMockServer deepseekServer,
            @Qualifier("qwenWireMock") WireMockServer qwenServer,
            @Qualifier("minimaxWireMock") WireMockServer minaimaxServer) {
        ProviderProperties pp = new ProviderProperties();
        pp.setProviders(List.of(
            new Provider("deepseek", "deepseek-chat",
                // Spring AI's OpenAiApi hits {baseUrl}/v1/chat/completions;
                // baseUrl must NOT end with /v1
                "http://localhost:" + deepseekServer.port(),
                "${DEEPSEEK_API_KEY}", Map.of("temperature", 0.5)),
            new Provider("qwen", "qwen-plus",
                "http://localhost:" + qwenServer.port(),
                "${QWEN_API_KEY}", Map.of("temperature", 0.5)),
            new Provider("minimax", "MiniMax-M3",
                "http://localhost:" + minaimaxServer.port(),
                "${MINIMAX_API_KEY}", Map.of("temperature", 0.5))
        ));
        return pp;
    }
}
