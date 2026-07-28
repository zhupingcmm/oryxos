package io.oryxos.boot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 008-agent-scheduler 阶段 JPA 装配 —— 激活条件：
 *
 * <ul>
 *   <li>{@code oryxos.scheduler.enabled} 默认 {@code true}（核心阶段默认开，
 *       与 {@code MemoryJpaConfig} 不同 —— scheduler 是核心能力补完，
 *       而非可选后端）</li>
 *   <li>{@code spring.autoconfigure.exclude} 不含 JPA 三项（见
 *       {@code application.yml} 默认值）</li>
 * </ul>
 *
 * <p>激活时它做两件事：
 * <ol>
 *   <li>{@link EnableJpaRepositories} —— 让 Spring Data JPA 在
 *       {@code io.oryxos.storage.scheduler} + {@code io.oryxos.storage.taskexecutions}
 *       包下扫描 {@code @Repository} 接口（{@code TaskExecutionRepository}）。</li>
 *   <li>{@link EntityScan} —— 让 Hibernate 在上述两个包下扫描
 *       {@code @Entity} 类（{@code ScheduledTaskRecord} + {@code TaskExecutionRecord}）。</li>
 * </ol>
 *
 * <p>与 {@link MemoryJpaConfig} 的区别：memory 是后端可选（markdown / sqlite / mem0），
 * scheduler 是核心能力——一旦 enable 就必须能扫到任务表 + 执行表。fail-closed 在
 * Spring 上下文启动失败时由 {@code ScheduleBootstrap}（{@code @PostConstruct} +
 * {@code try/catch}）兜底，本 config 不做运行时降级。
 *
 * <p>008-agent-scheduler 实施范围：本 config 仅扫描新增的 2 个子包，
 * 不与 {@link MemoryJpaConfig}（扫 {@code io.oryxos.memory.repository}）
 * 冲突 —— Spring Data JPA 允许多 {@code @EnableJpaRepositories}。
 */
@Configuration
@ConditionalOnProperty(
    name = "oryxos.scheduler.enabled",
    havingValue = "true",
    matchIfMissing = true
)
@EnableJpaRepositories(basePackages = {
    "io.oryxos.storage.scheduler",
    "io.oryxos.storage.taskexecutions"
})
@EntityScan(basePackages = {
    "io.oryxos.storage.scheduler",
    "io.oryxos.storage.taskexecutions"
})
public class SchedulerJpaConfig {
}