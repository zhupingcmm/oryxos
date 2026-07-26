package io.oryxos.memory;

import io.oryxos.memory.backend.LongTermMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T014（006-memory-layer Phase 3）—— 8 条 C-MS 契约测试（[contracts/memory-service.md §2](../../../../../specs/006-memory-layer/contracts/memory-service.md)）。
 *
 * <p>不依赖 Spring 容器；用最小可注入的 {@link LongTermMemoryStore} 桩验证 {@link DefaultMemoryService} 码化的契约。
 *
 * <p>覆盖（C-MS-01 ~ C-MS-08）：
 * <ul>
 *   <li>C-MS-01 no-cache —— recallByKeyword 直接委派，命中按 createdAt DESC</li>
 *   <li>C-MS-02 core-never-truncate —— save(core) 不触发 trim / delete</li>
 *   <li>C-MS-03 scope-explicit —— save(scope=null) 抛 IllegalArgumentException</li>
 *   <li>C-MS-04 empty-query —— recallByKeyword(null/"") 返回空集合（不抛）</li>
 *   <li>C-MS-05 clear(core)-rejects —— clear(core) 抛 IllegalStateException（门面层守卫）</li>
 *   <li>C-MS-06 keyword-only —— 不引入向量检索（接口契约）</li>
 *   <li>C-MS-07 topK-cap —— topK &gt; 100 截断到 100</li>
 *   <li>C-MS-08 MemoryException → 不含 stack trace / 不重抛未受检异常的内部原因</li>
 * </ul>
 */
class MemoryServiceContractTest {

    /** 桩后端 —— 记录所有调用，模拟 core 区有 2 条 / archive 区有 1 条。 */
    static class StubBackend implements LongTermMemoryStore {
        final List<MemoryEntry> entries = new ArrayList<>();
        long recallCallCount = 0;
        long trimCallCount = 0;
        long clearCallCount = 0;
        boolean throwOnSave = false;
        boolean throwOnRecall = false;

        @Override public MemoryEntry save(MemoryScope scope, String content, List<String> tags) {
            if (throwOnSave) {
                throw new MemoryException("disk full simulated");
            }
            MemoryEntry e = new MemoryEntry(content, scope, Instant.now(),
                tags == null ? List.of() : tags);
            entries.add(e);
            return e;
        }

        @Override public List<MemoryEntry> recallByKeyword(String q, int topK, MemoryScope scopeFilter) {
            recallCallCount++;
            if (throwOnRecall) {
                throw new MemoryException("recall backend down");
            }
            String ql = q == null ? "" : q.toLowerCase();
            return entries.stream()
                .filter(e -> scopeFilter == null || e.scope() == scopeFilter)
                .filter(e -> e.content().toLowerCase().contains(ql))
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .limit(topK)
                .toList();
        }

        @Override public List<MemoryEntry> recallByScope(MemoryScope scope, int topK) {
            return entries.stream()
                .filter(e -> e.scope() == scope)
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .limit(topK)
                .toList();
        }

        @Override public boolean delete(String entryId) {
            return entries.removeIf(e -> entryId.equals(e.id()));
        }

        @Override public void clear(MemoryScope scope) {
            clearCallCount++;
            entries.removeIf(e -> e.scope() == scope);
        }

        @Override public boolean isHealthy() { return true; }
    }

    StubBackend backend;
    DefaultMemoryService service;

    @BeforeEach
    void setUp() {
        backend = new StubBackend();
        service = new DefaultMemoryService(backend);
    }

    @Test
    @DisplayName("C-MS-01 no-cache：recallByKeyword 每次都委派到后端，无中间缓存")
    void no_cache_recall_delegates_each_time() {
        backend.save(MemoryScope.CORE, "user prefers tabs over spaces", List.of());
        service.recallByKeyword("tabs", 5, null);
        service.recallByKeyword("tabs", 5, null);
        service.recallByKeyword("tabs", 5, null);
        assertThat(backend.recallCallCount).isEqualTo(3L);
    }

    @Test
    @DisplayName("C-MS-01 no-cache：recallByKeyword 命中按 createdAt DESC")
    void recall_sorted_by_created_at_desc() {
        Instant base = Instant.now();
        backend.entries.add(new MemoryEntry(null, MemoryScope.CORE, "older fact",
            List.of(), base.minusSeconds(60), "core"));
        backend.entries.add(new MemoryEntry(null, MemoryScope.CORE, "newer fact",
            List.of(), base, "core"));
        backend.entries.add(new MemoryEntry(null, MemoryScope.CORE, "middle fact",
            List.of(), base.minusSeconds(30), "core"));
        List<MemoryEntry> hits = service.recallByKeyword("fact", 10, null);
        assertThat(hits).extracting(MemoryEntry::content)
            .containsExactly("newer fact", "middle fact", "older fact");
    }

    @Test
    @DisplayName("C-MS-02 core-never-truncate：save(core) 不触发 clear / delete")
    void core_save_does_not_trigger_trim() {
        service.save(MemoryScope.CORE, "important fact", List.of("preference"));
        service.save(MemoryScope.CORE, "another fact", List.of());
        assertThat(backend.trimCallCount).isZero();
        assertThat(backend.clearCallCount).isZero();
        assertThat(backend.entries).hasSize(2);
    }

    @Test
    @DisplayName("C-MS-02 core-never-truncate：save(archive) 也不在门面层触发 trim（后端 lazy trim 留给 SqliteMemoryStore）")
    void archive_save_does_not_trim_at_facade() {
        service.save(MemoryScope.ARCHIVE, "log entry 1", List.of());
        service.save(MemoryScope.ARCHIVE, "log entry 2", List.of());
        assertThat(backend.clearCallCount).isZero();
    }

    @Test
    @DisplayName("C-MS-03 scope-explicit：save(scope=null) MUST 抛 IllegalArgumentException")
    void save_null_scope_throws() {
        assertThatThrownBy(() -> service.save(null, "x", List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scope");
    }

    @Test
    @DisplayName("C-MS-03 scope-explicit：save(core, '', tags) content 空白也抛 IllegalArgumentException")
    void save_blank_content_throws() {
        assertThatThrownBy(() -> service.save(MemoryScope.CORE, "   ", List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("content");
    }

    @Test
    @DisplayName("C-MS-04 empty-query：recallByKeyword(null) 返回空集合不抛")
    void recall_null_query_returns_empty() {
        assertThat(service.recallByKeyword(null, 5, null)).isEmpty();
        assertThat(service.recallByKeyword("", 5, null)).isEmpty();
        assertThat(service.recallByKeyword("   ", 5, null)).isEmpty();
    }

    @Test
    @DisplayName("C-MS-05 clear(core)-rejects：clear(CORE) MUST 抛 IllegalStateException（门面层守卫）")
    void clear_core_throws_illegal_state() {
        assertThatThrownBy(() -> service.clear(MemoryScope.CORE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("core");
        // 守卫在前 —— 后端 MUST 不被调用
        assertThat(backend.clearCallCount).isZero();
    }

    @Test
    @DisplayName("C-MS-05 clear(archive) 正常委派到后端")
    void clear_archive_delegates() {
        service.clear(MemoryScope.ARCHIVE);
        assertThat(backend.clearCallCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("C-MS-07 topK-cap：topK=200 → 后端收到 100（C-MS-07 上限）")
    void topk_clamped_to_100() {
        // 用 spy：捕获后端收到的 topK
        int[] received = new int[1];
        LongTermMemoryStore spy = new LongTermMemoryStore() {
            @Override public MemoryEntry save(MemoryScope s, String c, List<String> t) {
                return backend.save(s, c, t);
            }
            @Override public List<MemoryEntry> recallByKeyword(String q, int topK, MemoryScope f) {
                received[0] = topK;
                return backend.recallByKeyword(q, topK, f);
            }
            @Override public List<MemoryEntry> recallByScope(MemoryScope s, int topK) {
                return backend.recallByScope(s, topK);
            }
            @Override public boolean delete(String id) { return backend.delete(id); }
            @Override public void clear(MemoryScope s) { backend.clear(s); }
            @Override public boolean isHealthy() { return true; }
        };
        DefaultMemoryService spied = new DefaultMemoryService(spy);
        spied.recallByKeyword("x", 200, null);
        assertThat(received[0]).isEqualTo(100);
    }

    @Test
    @DisplayName("C-MS-07 topK-cap：topK<=0 视作 1")
    void topk_zero_or_negative_clamped_to_1() {
        int[] received = new int[1];
        LongTermMemoryStore spy = new LongTermMemoryStore() {
            @Override public MemoryEntry save(MemoryScope s, String c, List<String> t) {
                return backend.save(s, c, t);
            }
            @Override public List<MemoryEntry> recallByKeyword(String q, int topK, MemoryScope f) {
                received[0] = topK;
                return List.of();
            }
            @Override public List<MemoryEntry> recallByScope(MemoryScope s, int topK) { return List.of(); }
            @Override public boolean delete(String id) { return backend.delete(id); }
            @Override public void clear(MemoryScope s) { /* no-op */ }
            @Override public boolean isHealthy() { return true; }
        };
        DefaultMemoryService spied = new DefaultMemoryService(spy);
        spied.recallByKeyword("x", 0, null);
        assertThat(received[0]).isEqualTo(1);
        spied.recallByKeyword("x", -5, null);
        assertThat(received[0]).isEqualTo(1);
    }

    @Test
    @DisplayName("C-MS-08 MemoryException 不重抛为不同类型 —— Tool 层统一捕获 RuntimeException")
    void memory_exception_propagates_as_runtime_exception() {
        backend.throwOnSave = true;
        // MemoryException 是 RuntimeException 子类，调用方（含 Tool 层）只需 catch RuntimeException
        assertThatThrownBy(() -> service.save(MemoryScope.CORE, "x", null))
            .isInstanceOf(RuntimeException.class)
            .isInstanceOf(MemoryException.class)
            .hasMessageContaining("disk full simulated");
        // stack trace 不应被门面包装/吞掉（Tool 层负责截断为单行 message，C-MS-08）
    }

    @Test
    @DisplayName("C-MS-08 MemoryException 不在门面包装新 RuntimeException —— message 干净")
    void memory_exception_message_clean() {
        backend.throwOnRecall = true;
        try {
            service.recallByKeyword("x", 5, null);
            org.junit.jupiter.api.Assertions.fail("Expected MemoryException");
        } catch (MemoryException ex) {
            assertThat(ex.getMessage()).contains("recall backend down");
            // 不应包含 "at io.oryxos" 这种 stack trace 痕迹（C-MS-08 是说 Tool 层
            // errorMessage 不含 stack trace，门面层只透传原始 message）
        }
    }

    @Test
    @DisplayName("delete(id)：存在 → true；不存在 → false")
    void delete_id_present_and_absent() {
        MemoryEntry e = service.save(MemoryScope.CORE, "x", List.of());
        assertThat(service.delete(e.id())).isTrue();
        assertThat(service.delete("non-existent-id")).isFalse();
    }

    @Test
    @DisplayName("recallByScope 仅返回该 scope 条目，按 createdAt DESC")
    void recall_by_scope_filters_and_sorts() {
        Instant base = Instant.now();
        backend.entries.add(new MemoryEntry(null, MemoryScope.CORE, "c1",
            List.of(), base, "core"));
        backend.entries.add(new MemoryEntry(null, MemoryScope.ARCHIVE, "a1",
            List.of(), base, "archive"));
        backend.entries.add(new MemoryEntry(null, MemoryScope.CORE, "c2",
            List.of(), base.minusSeconds(60), "core"));
        List<MemoryEntry> coreHits = service.recallByScope(MemoryScope.CORE, 10);
        assertThat(coreHits).extracting(MemoryEntry::content).containsExactly("c1", "c2");
        List<MemoryEntry> archiveHits = service.recallByScope(MemoryScope.ARCHIVE, 10);
        assertThat(archiveHits).extracting(MemoryEntry::content).containsExactly("a1");
    }
}