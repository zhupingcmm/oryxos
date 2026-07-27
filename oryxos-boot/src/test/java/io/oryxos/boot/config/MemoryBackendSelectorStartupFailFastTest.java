package io.oryxos.boot.config;

import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryException;
import io.oryxos.memory.MemoryProperties;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.backend.LongTermMemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MemoryBackendSelector 启动期 fail-fast 测试（不走 Spring 上下文，手动构造 selector）。
 *
 * <p>三场景：
 * <ol>
 *   <li>backend="unknown" → 抛 MemoryException 含 backend 名 + available backends 列表</li>
 *   <li>Bean 集合缺 sqlite → backend="sqlite" fail-fast</li>
 *   <li>{@code isHealthy()=false} → 启动期健康检查 fail-fast</li>
 * </ol>
 *
 * <p>用 {@code @SpringBootTest} 配非法 backend 会让整个 context 启动失败，不便单测，
 * 故直接 {@code new MemoryBackendSelector(...)} 然后调 {@code validateAtStartup()}。
 */
@DisplayName("MemoryBackendSelector 启动期 fail-fast")
class MemoryBackendSelectorStartupFailFastTest {

    @Test
    @DisplayName("backend=\"unknown\" → validateAtStartup 抛 MemoryException")
    void unknown_backend_fails_fast() {
        MemoryBackendSelector selector = new MemoryBackendSelector(
            Map.of(
                "markdownMemoryStore", (LongTermMemoryStore) new StubStore(true),
                "sqliteMemoryStore",   (LongTermMemoryStore) new StubStore(true),
                "mem0MemoryStore",     (LongTermMemoryStore) new StubStore(true)
            ),
            new MemoryProperties("unknown", null, null, null)
        );

        assertThatThrownBy(selector::validateAtStartup)
            .isInstanceOf(MemoryException.class)
            .hasMessageContaining("unknown")
            .hasMessageContaining("Available backends");
    }

    @Test
    @DisplayName("Bean 集合缺 sqlite → backend=\"sqlite\" fail-fast")
    void missing_bean_fails_fast() {
        MemoryBackendSelector selector = new MemoryBackendSelector(
            Map.of("markdownMemoryStore", (LongTermMemoryStore) new StubStore(true)),
            new MemoryProperties("sqlite", null, null, null)
        );

        assertThatThrownBy(selector::validateAtStartup)
            .isInstanceOf(MemoryException.class)
            .hasMessageContaining("sqlite");
    }

    @Test
    @DisplayName("isHealthy()=false → 启动期 fail-fast（健康检查拦截）")
    void unhealthy_backend_fails_fast() {
        MemoryBackendSelector selector = new MemoryBackendSelector(
            Map.of(
                "markdownMemoryStore", (LongTermMemoryStore) new StubStore(true),
                "sqliteMemoryStore",   (LongTermMemoryStore) new StubStore(false),
                "mem0MemoryStore",     (LongTermMemoryStore) new StubStore(true)
            ),
            new MemoryProperties("sqlite", null, null, null)
        );

        assertThatThrownBy(selector::validateAtStartup)
            .isInstanceOf(MemoryException.class)
            .hasMessageContaining("health")
            .hasMessageContaining("sqlite");
    }

    /**
     * 桩 LongTermMemoryStore：只暴露 isHealthy() 与 no-op 6 方法。
     */
    static class StubStore implements LongTermMemoryStore {
        private final boolean healthy;

        StubStore(boolean healthy) {
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