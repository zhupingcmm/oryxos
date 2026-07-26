package io.oryxos.boot.config;

import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryProperties;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.backend.LongTermMemoryStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * T028 共享测试 fixture —— 提供 3 个 mock LongTermMemoryStore Bean + @EnableConfigurationProperties。
 *
 * <p>位于 {@code oryxos-boot} 模块 —— {@link MemoryBackendSelector} 在此模块装配（CLAUDE.md §5 模块边界）。
 *
 * <p>使用方式：在自己的 {@code @SpringBootTest(classes = {MemoryBackendSelectorTest.SelectorTestConfig.class, MemoryBackendSelector.class})} 引用本 fixture。
 *
 * <p>实际断言测试在：
 * <ul>
 *   <li>{@link MemoryBackendSelectorMarkdownIT} —— backend="markdown" 选定</li>
 *   <li>{@link MemoryBackendSelectorSqliteIT} —— backend="sqlite" 选定</li>
 *   <li>{@link MemoryBackendSelectorMem0IT} —— backend="mem0" 选定</li>
 *   <li>{@link MemoryBackendSelectorStartupFailFastTest} —— 启动期 fail-fast</li>
 * </ul>
 */
public class MemoryBackendSelectorTest {

    @Configuration
    @EnableConfigurationProperties(MemoryProperties.class)
    public static class SelectorTestConfig {
        @Bean("markdownMemoryStore")
        public LongTermMemoryStore markdownMock() { return new StubStore(true); }
        @Bean("sqliteMemoryStore")
        public LongTermMemoryStore sqliteMock() { return new StubStore(true); }
        @Bean("mem0MemoryStore")
        public LongTermMemoryStore mem0Mock() { return new StubStore(true); }
    }

    /**
     * 桩 LongTermMemoryStore：只暴露 isHealthy() 与 no-op 6 方法。
     */
    public static class StubStore implements LongTermMemoryStore {
        private final boolean healthy;

        public StubStore(boolean healthy) {
            this.healthy = healthy;
        }

        @Override public MemoryEntry save(MemoryScope scope, String content, List<String> tags) { return null; }
        @Override public List<MemoryEntry> recallByKeyword(String query, int topK, MemoryScope scopeFilter) { return List.of(); }
        @Override public List<MemoryEntry> recallByScope(MemoryScope scope, int topK) { return List.of(); }
        @Override public boolean delete(String entryId) { return false; }
        @Override public void clear(MemoryScope scope) { }
        @Override public boolean isHealthy() { return healthy; }
    }
}