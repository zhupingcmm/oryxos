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
 * <p>背景：{@link PromptBuilder} 自身**没有** {@code @Component}（[PromptBuilder.java §2]
 * 显式声明），Spring 不会自动构造它；本 config 通过显式 {@code @Bean} 工厂方法
 * （{@link #promptBuilder}）完成装配，避免 4 参 vs 2 便捷构造的歧义。
 *
 * <p>本 config 同时为 {@link MemoryInjector} / {@link ToolSchemaProvider} /
 * {@link BootstrapLoader} / {@link java.time.Clock} 这 4 个依赖提供 US-2 阶段
 * 的 Noop 桩 —— 让 {@code promptBuilder} 工厂方法能直接拿到 4 个参数。
 *
 * <h2>US-3 / US-4 切换路径</h2>
 * <ul>
 *   <li>US-3：加 {@code @Primary @Component public class MemoryServiceBridge
 *       implements MemoryInjector} —— Spring 自动选这个，Noop 不再生效（但仍然在
 *       容器里，无害）。</li>
 *   <li>US-4：{@code ToolRegistrySchemaAdapter implements ToolSchemaProvider} 的真实
 *       {@code @Primary @Bean} 已经在 {@code oryxos-boot/.../ToolSystemConfig}
 *       注册 —— 本 config 的 Noop 桩**改名**为 {@code noopToolSchemaProvider}，
 *       以避免同名 bean definition 冲突；type-resolution 时 {@code @Primary} 胜出，
 *       Noop 仅在 slice test（只加载本 config）场景作为 fallback 生效。</li>
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

    /**
     * US-2 桩 —— US-4 后由 {@code boot/config/ToolSystemConfig.toolSchemaProvider()}
     * 的 {@code @Primary @Bean} 覆盖；本 bean 改名避免同名冲突，slice test 场景作 fallback。
     */
    @Bean
    public ToolSchemaProvider noopToolSchemaProvider() {
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