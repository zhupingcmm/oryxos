package io.oryxos.boot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA 装配 —— 仅在 {@code oryxos.memory.backend} 是 {@code sqlite} 或 {@code mem0}
 * 时激活（006-memory-layer US-3 / T033-T034）。
 *
 * <p>激活条件用 {@link ConditionalOnExpression} 表达"sqlite 或 mem0"的语义
 * —— {@code @ConditionalOnProperty(havingValue=...)} 单值语义无法表达 OR，
 * 而同一份 {@code @EnableJpaRepositories} + {@code @EntityScan} 配置两个 store
 * 又会重复；{@code @ConditionalOnExpression} 是单 config 处理 OR 的最直接方式。
 *
 * <p>激活时它做两件事：
 * <ol>
 *   <li>{@link EnableJpaRepositories} —— 让 Spring Data JPA 在
 *       {@code io.oryxos.memory.repository} 包下扫描 {@code @Repository} 接口
 *       （{@code MemoryEntryRepository} / {@code MemoryEntryIndexRepository}）。</li>
 *   <li>{@link EntityScan} —— 让 Hibernate 在 {@code io.oryxos.memory.repository}
 *       下扫描 {@code @Entity} 类（{@code MemoryEntryEntity} /
 *       {@code MemoryEntryIndexEntity}）。</li>
 * </ol>
 *
 * <p>为什么不放 {@code oryxos-memory} 模块：按 CLAUDE.md §5 边界，
 * {@code oryxos-memory} 只暴露接口 + 实体 + 后端实现，由 {@code oryxos-boot}
 * 决定如何扫描/装配；测试用 {@code @DataJpaTest} + 显式
 * {@code @EnableJpaRepositories} / {@code @EntityScan} 是同样的原因
 * （[SwitchToMem0IT]
 * (../../../../../../../oryxos-memory/src/test/java/io/oryxos/memory/backend/integration/SwitchToMem0IT.java)
 * 已示范这个模式）。
 *
 * <h2>DataSource 仍需显式配置</h2>
 * <p>本 config 只负责 repo + entity 扫描；{@code spring.datasource.*} 与 JPA
 * autoconfig 是否启用仍由 {@code application.yml} 决定（核心阶段
 * scaffold 默认 exclude JPA autoconfig，本 config 配合使用需要后续 US 打开）。
 * 当前 markdown（默认）路径完全不经过本 config，自然不踩这个坑。
 */
@Configuration
@ConditionalOnExpression(
    "'${oryxos.memory.backend:markdown}'.equals('sqlite') "
        + "or '${oryxos.memory.backend:markdown}'.equals('mem0')"
)
@EnableJpaRepositories(basePackages = "io.oryxos.memory.repository")
@EntityScan(basePackages = "io.oryxos.memory.repository")
public class MemoryJpaConfig {
}