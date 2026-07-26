package io.oryxos.boot.config;

import io.oryxos.core.tool.ToolDefinition;
import io.oryxos.core.tool.ToolRegistration;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.tool.notify.NotifyTool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Notify 出站推送的 Spring 装配 —— {@code T037}。
 *
 * <p>职责：
 * <ol>
 *   <li>把 {@link NotifyTool}（{@code @Component} 在 oryxos-tool）打包成
 *       {@link ToolRegistration}（{@code definition + tool + beanName}）</li>
 *   <li>在 {@code ToolRegistry} 容器里覆盖默认空注册表，用 {@link ToolRegistry#of}
 *       构建包含 {@code notify} 的注册表 —— 用 {@code @Primary} 标记让
 *       {@code DefaultToolExecutor} 注入时优先取这一个</li>
 * </ol>
 *
 * <p>为什么在 {@code oryxos-boot} 而不是 {@code oryxos-tool}：
 * <ul>
 *   <li>{@code ToolRegistry} 同时是 {@code @Component}（空注册表，给单测用）和
 *       本 config 的 {@code @Bean}（生产用）—— 装配"用哪个"的决策归 boot，
 *       工具模块只提供实现</li>
 *   <li>后续 US-4 还会加入 MCP / SKILL.md 工具到注册表，集中一处管理比散在多个模块干净</li>
 * </ul>
 *
 * <p>参考：[PromptBuilderConfig.java](../../../../../../../../oryxos-core/src/main/java/io/oryxos/core/config/PromptBuilderConfig.java)
 * 同模式（{@code @Configuration} 显式 {@code @Bean} 避免构造歧义）。
 */
@Configuration
public class NotifyToolConfig {

    /**
     * notify 工具的 {@link ToolRegistration} —— 把 {@link NotifyTool} 与它的
     * {@link ToolDefinition} 打包，给 {@link ToolRegistry} 用。
     */
    @Bean
    public ToolRegistration notifyToolRegistration(NotifyTool notifyTool) {
        ToolDefinition def = new ToolDefinition(
            NotifyTool.NAME,
            notifyTool.description(),
            "builtin"
        );
        return new ToolRegistration(def, notifyTool, "notifyTool");
    }

    /**
     * 覆盖 {@link ToolRegistry} 的默认空实现：用 {@link ToolRegistry#of} 构建包含
     * notify 在内的注册表。用 {@code @Primary} 标记，让任何 {@code @Autowired ToolRegistry}
     * 注入（包括 {@code DefaultToolExecutor}）走这个 Bean。
     *
     * <p>注意：原 {@code @Component} 的 {@link ToolRegistry} 仍会留在容器里，但被本 Bean
     * 通过 {@code @Primary} 覆盖；这与 [PromptBuilderConfig] 中保留 Noop 桩的策略一致。
     */
    @Bean
    @Primary
    public ToolRegistry notifyToolRegistry(@Qualifier("notifyToolRegistration")
                                          ToolRegistration notifyToolRegistration) {
        return ToolRegistry.of(java.util.Map.of(
            NotifyTool.NAME, notifyToolRegistration
        ));
    }
}