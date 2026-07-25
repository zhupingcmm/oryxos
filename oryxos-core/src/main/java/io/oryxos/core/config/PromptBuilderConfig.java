package io.oryxos.core.config;

import io.oryxos.core.BootstrapLoader;
import io.oryxos.core.MemoryInjector;
import io.oryxos.core.PromptBuilder;
import io.oryxos.core.ToolSchemaProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * US-2 production wiring for {@link PromptBuilder}.
 *
 * <p>背景：{@link PromptBuilder} 自身带 {@code @Component}，但其 4 个依赖
 * {@link MemoryInjector} / {@link ToolSchemaProvider} / {@link BootstrapLoader} /
 * {@link java.time.Clock} 在 US-2 阶段**没有** Spring bean —— 它们是接口 + 嵌套的
 * {@code Noop*} 桩实现（{@link MemoryInjector.NoopMemoryInjector} 等）。
 *
 * <p>如果直接让 {@code PromptBuilder}（{@code @Component}）自己被 Spring 装配，Spring
 * 会因为 {@link PromptBuilder} 提供了 2 个 public 构造（4 参 + 2 参）而无法决定用哪一个
 * —— 退回去找无参构造 → {@code NoSuchMethodException}（典型错误日志：
 * {@code Failed to instantiate [io.oryxos.core.PromptBuilder]: No default constructor found}）。
 *
 * <p>本 config 提供 US-2 阶段所需的 4 个桩 bean，让 {@code PromptBuilder} 的 4 参构造能
 * 顺利被 Spring 装配。这些 Noop bean 都**不带** {@code @Primary} —— 等 US-3（MemoryService
 * 桥接） / US-4（ToolRegistry + 文件系统 BootstrapLoader）落地真实实现时，只需给真实
 * 实现加 {@code @Primary} 即可自动覆盖，本 config 不需要再改。
 *
 * <h2>US-3 / US-4 切换路径</h2>
 * <ul>
 *   <li>US-3：加 {@code @Primary @Component public class MemoryServiceBridge
 *       implements MemoryInjector} —— Spring 自动选这个，Noop 不再生效（但仍然在
 *       容器里，无害）。</li>
 *   <li>US-4：加 {@code @Primary @Component FilesystemBootstrapLoader implements
 *       BootstrapLoader} 与 {@code @Primary @Component ToolRegistrySchemaAdapter
 *       implements ToolSchemaProvider}。</li>
 *   <li>{@link java.time.Clock} 没有"真实"实现路径；测试 / US-5 通过
 *       {@code @Primary @Bean Clock} 注入固定时钟覆盖。</li>
 * </ul>
 *
 * <p>参考：[ProfileRegistryConfig](ProfileRegistryConfig.java) 同样的 Noop 注册模式；
 * [ToolExecutorConfig](ToolExecutorConfig.java) 显式 {@code @Bean} 避免歧义。
 */
@Configuration
public class PromptBuilderConfig {

    /** US-2 桩 —— US-3 {@code MemoryServiceBridge} 落地后被覆盖。 */
    @Bean
    public MemoryInjector memoryInjector() {
        return new MemoryInjector.NoopMemoryInjector();
    }

    /** US-2 桩 —— US-4 {@code ToolRegistrySchemaAdapter} 落地后被覆盖。 */
    @Bean
    public ToolSchemaProvider toolSchemaProvider() {
        return new ToolSchemaProvider.NoopToolSchemaProvider();
    }

    /** US-2 桩 —— US-4 {@code FilesystemBootstrapLoader} 落地后被覆盖。 */
    @Bean
    public BootstrapLoader bootstrapLoader() {
        return new BootstrapLoader.NoopBootstrapLoader();
    }

    /** 系统默认时钟 —— 测试可加 {@code @Primary @Bean Clock} 覆盖。 */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    /**
     * 显式暴露 {@link PromptBuilder} 工厂方法 —— 即使 {@code PromptBuilder} 自身带
     * {@code @Component}，Spring 仍可能因两个 public 构造 + 无 {@code @Autowired}
     * 标记而解析失败。本方法让 Spring 直接拿到一个明确构造好的实例（无需走构造注入路径）。
     */
    @Bean
    public PromptBuilder promptBuilder(
        MemoryInjector memoryInjector,
        ToolSchemaProvider toolSchemaProvider,
        BootstrapLoader bootstrapLoader,
        Clock clock
    ) {
        return new PromptBuilder(memoryInjector, toolSchemaProvider, bootstrapLoader, clock);
    }
}