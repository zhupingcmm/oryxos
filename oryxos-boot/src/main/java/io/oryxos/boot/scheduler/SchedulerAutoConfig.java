package io.oryxos.boot.scheduler;

import io.oryxos.core.AgentService;
import io.oryxos.core.scheduler.AgentScheduler;
import io.oryxos.core.scheduler.AgentSchedulerImpl;
import io.oryxos.core.scheduler.ScheduleStore;
import io.oryxos.core.scheduler.SessionFactory;
import io.oryxos.core.scheduler.TaskExecutionRecorder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 008-agent-scheduler 阶段 —— Spring 装配。
 *
 * <p>把所有 {@code scheduler.*} 接口的 {@code @Bean} 集中起来，避免散在多处：
 * <ul>
 *   <li>{@link AgentScheduler}（core 模块的接口 + impl 也由 core 提供）</li>
 *   <li>{@code SessionFactoryImpl}（在 storage 模块，本 @Configuration 走 @ComponentScan 自动发现）</li>
 *   <li>{@code ScheduleStoreImpl} / {@code TaskExecutionRecorderImpl} 同上</li>
 * </ul>
 *
 * <p>{@code Profile.schedules[]} 解析归 {@link ScheduleBootstrap}（{@code @PostConstruct}）。
 *
 * <p>默认开启（{@code matchIfMissing=true}）；如需禁用某次部署（开发态），设
 * {@code oryxos.scheduler.enabled=false} 即可。
 */
@Configuration
@ConditionalOnProperty(
    name = "oryxos.scheduler.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class SchedulerAutoConfig {

    /**
     * 装配 {@link AgentSchedulerImpl}。
     *
     * <p>{@link AgentService} / {@link ScheduleStore} / {@link TaskExecutionRecorder} /
     * {@link SessionFactory} 4 个依赖由 Spring 容器从 {@code io.oryxos.storage} /
     * {@code io.oryxos.boot} 包扫描自动注入；任一缺失 → 启动失败（fail-fast）。
     */
    @Bean
    @ConditionalOnBean({
        AgentService.class, ScheduleStore.class, TaskExecutionRecorder.class, SessionFactory.class
    })
    public AgentScheduler agentScheduler(
        AgentService agentService,
        ScheduleStore scheduleStore,
        TaskExecutionRecorder taskExecutionRecorder,
        SessionFactory sessionFactory
    ) {
        return new AgentSchedulerImpl(
            agentService, scheduleStore, taskExecutionRecorder, sessionFactory);
    }
}