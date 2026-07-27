package io.oryxos.boot.config;

import io.oryxos.memory.MemoryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Memory 层装配入口（006-memory-layer Phase 4 / US-2）。
 *
 * <p>启用 {@link MemoryProperties}（绑定 {@code oryxos.memory.*} 配置块，
 * 含 backend 选择 + markdown path + sqlite/mem0 预留字段）。
 *
 * <p>JPA 扫描（{@code @EnableJpaRepositories} + {@code @EntityScan}）由
 * {@link MemoryJpaConfig} 单独承担 —— 它只在 {@code oryxos.memory.backend=sqlite|mem0}
 * 时激活；默认 markdown 后端不触发 JPA 装配，避开了
 * {@code application.yml} 对 {@code DataSourceAutoConfiguration} 的 exclusion
 * （scaffold 期"先能起来再说"的设计，US-3 落地后 markdown 路径仍然适用）。
 *
 * <p>三个 {@code LongTermMemoryStore} 实现（{@code MarkdownMemoryStore} /
 * {@code SqliteMemoryStore} / {@code Mem0MemoryStore}）自身带
 * {@code @ConditionalOnProperty(name="oryxos.memory.backend", havingValue=<own-name>)}，
 * Spring 启动期只为当前 backend 实例化对应 store —— 其它后端的 store 不会被注册，
 * 它们的 repository 构造器参数也不会触发依赖解析（彻底消除本场景的
 * {@code UnsatisfiedDependencyException}）。
 *
 * <p>其他职责（后端选择器、MarkdownMemoryStore 默认路径注入）由
 * {@link MemoryBackendSelector} 与 {@code MarkdownMemoryStore} 自身承担。
 */
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfig {
}