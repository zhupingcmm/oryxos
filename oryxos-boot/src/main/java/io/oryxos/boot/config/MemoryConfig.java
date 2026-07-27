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
 * <p>其他职责（后端选择器、MarkdownMemoryStore 默认路径注入）由
 * {@link MemoryBackendSelector} 与 {@code MarkdownMemoryStore} 自身承担。
 */
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfig {
}