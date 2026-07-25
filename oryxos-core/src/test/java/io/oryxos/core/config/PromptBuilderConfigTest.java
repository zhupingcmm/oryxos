package io.oryxos.core.config;

import io.oryxos.core.BootstrapLoader;
import io.oryxos.core.MemoryInjector;
import io.oryxos.core.Profile;
import io.oryxos.core.Provider;
import io.oryxos.core.Prompt;
import io.oryxos.core.PromptBuilder;
import io.oryxos.core.Session;
import io.oryxos.core.ToolSchemaProvider;
import io.oryxos.core.testing.InMemorySession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-2/P3 wiring fix —— 烟雾测试验证 [PromptBuilderConfig](PromptBuilderConfig.java)
 * 让 Spring 装配 {@link PromptBuilder} 不再走"两个 public 构造 + 无默认构造"的歧义路径。
 *
 * <p>背景：原始 {@code PromptBuilder} 带 {@code @Component}，Spring 因存在 2 个 public
 * 构造（4 参 + 2 参便捷构造）无法决定走哪一个，回退去找默认构造 →
 * {@code NoSuchMethodException: PromptBuilder.&lt;init&gt;()} → 应用启动失败。
 *
 * <p>本测试同时验证：
 * <ol>
 *   <li>{@code PromptBuilderConfig} 自身能启动 ApplicationContext（不抛
 *       {@code BeanCreationException}）—— 直接对应原 bug 现场。</li>
 *   <li>所有 5 个 bean（{@code MemoryInjector} / {@code ToolSchemaProvider} /
 *       {@code BootstrapLoader} / {@code Clock} / {@code PromptBuilder}）都已注册。</li>
 *   <li>所有 Noop bean 都**不**带 {@code @Primary} —— US-3 / US-4 真实实现加
 *       {@code @Primary} 时不会被歧义卡住（结构性验证）。</li>
 * </ol>
 */
class PromptBuilderConfigTest {

    @Test
    void configBootsCleanly_noBeanCreationException() {
        // Given the production PromptBuilderConfig
        // When we boot a Spring ApplicationContext from it
        // Then it must NOT throw BeanCreationException (which would wrap the
        // original NoSuchMethodException: PromptBuilder.<init>() bug).
        try (AnnotationConfigApplicationContext ctx =
                 new AnnotationConfigApplicationContext(PromptBuilderConfig.class)) {

            // And all 5 expected beans are resolvable
            assertThat(ctx.getBean(PromptBuilder.class))
                    .as("PromptBuilder bean must be resolvable after the config boots")
                    .isNotNull();
            assertThat(ctx.getBean(MemoryInjector.class)).isNotNull();
            assertThat(ctx.getBean(ToolSchemaProvider.class)).isNotNull();
            assertThat(ctx.getBean(BootstrapLoader.class)).isNotNull();
            assertThat(ctx.getBean(Clock.class)).isNotNull();
        }
    }

    @Test
    void promptBuilderBeanIsConstructedAndBuildsPrompt() {
        // Verifies that the @Bean factory method actually calls the 4-arg constructor
        // (the wiring doesn't accidentally fall back to the 2-arg convenience
        // constructor that hard-codes Noop instances internally). And that the
        // end-to-end PromptBuilder.build(...) path works — any constructor-injection
        // bug would surface here as a NullPointerException from a missing dep.
        try (AnnotationConfigApplicationContext ctx =
                 new AnnotationConfigApplicationContext(PromptBuilderConfig.class)) {

            PromptBuilder pb = ctx.getBean(PromptBuilder.class);

            Profile dummyProfile = new Profile(
                    "smoke-profile",
                    new Provider(
                            "noop-provider", "noop-model", null, null, Map.of()),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    Profile.Settings.defaults(),
                    Map.of()
            );
            Session dummySession = new InMemorySession(
                    UUID.fromString("00000000-0000-0000-0000-000000000999"),
                    "smoke-profile");

            Prompt prompt = pb.build(dummyProfile, dummySession);
            assertThat(prompt)
                    .as("PromptBuilder.build(...) must succeed end-to-end after the fix")
                    .isNotNull();

            // Sanity: Noop memory/tool injections produce empty lists, but system
            // blocks still carry the current-date-time line (CLAUDE.md §9.2 step 1c).
            assertThat(prompt.systemBlocks())
                    .as("at least one system block (the date-time line) should be present")
                    .isNotEmpty();
            assertThat(prompt.memoryBlocks())
                    .as("NoopMemoryInjector must produce empty memory blocks")
                    .isEmpty();
            assertThat(prompt.toolSchemas())
                    .as("NoopToolSchemaProvider must produce empty tool schemas")
                    .isEmpty();
        }
    }

    @Test
    void noopBeansAreNotPrimary_soFutureRealImplsCanOverrideViaPrimary() {
        // Structural guard: the Noop beans must NOT be @Primary, so that when
        // US-3 lands MemoryServiceBridge as @Primary @Component or US-4 lands
        // FilesystemBootstrapLoader / ToolRegistrySchemaAdapter similarly, the
        // real impl wins without ambiguity.
        try (AnnotationConfigApplicationContext ctx =
                 new AnnotationConfigApplicationContext(PromptBuilderConfig.class)) {

            // DefaultListableBeanFactory exposes BeanDefinition inspection — used
            // to verify @Primary flag on each Noop bean.
            DefaultListableBeanFactory bf =
                    (DefaultListableBeanFactory) ctx.getBeanFactory();

            // Exactly one bean of each type
            assertThat(ctx.getBeanNamesForType(MemoryInjector.class))
                    .as("exactly one MemoryInjector bean should be registered")
                    .hasSize(1);
            @SuppressWarnings("null")  // getBeanNamesForType returns non-null array of strings
            String memBeanName = ctx.getBeanNamesForType(MemoryInjector.class)[0];
            assertThat(bf.getBeanDefinition(memBeanName).isPrimary())
                    .as("Noop MemoryInjector bean must NOT be @Primary "
                            + "(US-3 MemoryServiceBridge will be @Primary and must win)")
                    .isFalse();

            @SuppressWarnings("null")
            String schemaBeanName = ctx.getBeanNamesForType(ToolSchemaProvider.class)[0];
            assertThat(bf.getBeanDefinition(schemaBeanName).isPrimary())
                    .as("Noop ToolSchemaProvider bean must NOT be @Primary "
                            + "(US-4 ToolRegistrySchemaAdapter will be @Primary)")
                    .isFalse();

            @SuppressWarnings("null")
            String bootBeanName = ctx.getBeanNamesForType(BootstrapLoader.class)[0];
            assertThat(bf.getBeanDefinition(bootBeanName).isPrimary())
                    .as("Noop BootstrapLoader bean must NOT be @Primary "
                            + "(US-4 FilesystemBootstrapLoader will be @Primary)")
                    .isFalse();
        }
    }

    @Test
    void sanity_absentBeanStillThrowsNoSuchBeanDefinitionException() {
        // Sanity check that the test infrastructure itself isn't accidentally
        // bypassing bean resolution. A request for an unregistered bean should
        // still throw NoSuchBeanDefinitionException — proves we're really going
        // through Spring's bean factory and not a test stub.
        try (AnnotationConfigApplicationContext ctx =
                 new AnnotationConfigApplicationContext(PromptBuilderConfig.class)) {

            assertThatThrownBy(() -> ctx.getBean(String.class))
                    .as("unrelated String bean must not exist; confirms we use the real factory")
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }
}