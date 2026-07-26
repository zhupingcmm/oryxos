package io.oryxos.boot.config;

import io.oryxos.core.tool.ToolDefinition;
import io.oryxos.core.tool.ToolRegistration;
import io.oryxos.tool.notify.NotifyTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Notify 出站 Tool 的 {@link ToolRegistration} 装配 —— 仅暴露 notify 的注册项，
 * 真正的 {@code @Primary @Bean ToolRegistry}（含 9 个内置 Tool）由
 * {@link ToolSystemConfig} 统一管理。
 *
 * <p>为什么拆两个 config：
 * <ul>
 *   <li>004 阶段落地 notify 时 @Primary @Bean ToolRegistry 只含 notify（单 Bean 装配模式）</li>
 *   <li>005 / 005-tool-system 把全部 9 个 Tool 都装进注册表，集中到 {@link ToolSystemConfig}</li>
 *   <li>本类保留 {@code notifyToolRegistration} 作为最小装配单元；任何下游可注入 9 项注册表</li>
 * </ul>
 *
 * <p>为什么不放在 {@code oryxos-tool}：参考
 * {@link io.oryxos.core.config.PromptBuilderConfig} 同模式（{@code @Configuration}
 * 显式 {@code @Bean} 避免构造歧义）；装配决策归 {@code oryxos-boot}。
 */
@Configuration
public class NotifyToolConfig {

    /**
     * notify 工具的 {@link ToolRegistration} —— 由 {@link ToolSystemConfig} 收录。
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
}
