package io.oryxos.core.config;

import io.oryxos.core.DefaultToolExecutor;
import io.oryxos.core.ToolAuditWriter;
import io.oryxos.core.ToolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * US-2 主线程（非测试）的默认 {@link ToolExecutor} 装配。
 *
 * <p>本 config 显式提供 {@link DefaultToolExecutor} Bean，避免 container 扫描歧义（Constitution §IV。
 * Spring 同时存在 OAuth 自动扫描到的 {@code ToolExecutor} 候选会冲突）。
 *
 * <p>{@link ToolAuditWriter} 在 core 阶段使用 {@link ToolAuditWriter.NoopToolAuditWriter}（不写库）；
 * US-4 / US-5 接入 stage 时换成 oryxos-storage 的 {@code JpaToolAuditWriter}。
 *
 * <p>测试环境通过 {@code @Profile("test")} 的 FakeToolExecutor（in testing pkg）覆盖本默认 Bean。
 */
@Configuration
public class ToolExecutorConfig {

    @Bean
    public ToolAuditWriter toolAuditWriter() {
        return new ToolAuditWriter.NoopToolAuditWriter();
    }

    @Bean
    public ToolExecutor toolExecutor(ToolAuditWriter toolAuditWriter) {
        return new DefaultToolExecutor(toolAuditWriter);
    }
}