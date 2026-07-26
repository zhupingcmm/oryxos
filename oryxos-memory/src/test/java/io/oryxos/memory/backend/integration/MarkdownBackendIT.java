package io.oryxos.memory.backend.integration;

import io.oryxos.memory.DefaultMemoryService;
import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.MemoryService;
import io.oryxos.memory.backend.MarkdownMemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T024（006-memory-layer Phase 4 / US-2）—— MarkdownMemoryStore + DefaultMemoryService 端到端集成测试。
 *
 * <p>Spring-less（不依赖 Spring 容器）但跑真实文件系统 + 真实的 {@link DefaultMemoryService} 门面：
 * <ol>
 *   <li>save 3 条 + recall 全部命中（跨 facade 调用）</li>
 *   <li>外部删除 {@code ## Core} 段 → 下次 save 自动重建（lenient recovery 集成）</li>
 *   <li>N=10 线程并发 save 100 次 → 文件含 100 行无丢失（同步串行化集成）</li>
 *   <li>DefaultMemoryService 守卫层 + MarkdownMemoryStore 写入层组合行为（save(scope=null) → IAE）</li>
 *   <li>DefaultMemoryService.clear(core) → IllegalStateException（不动 backend）</li>
 * </ol>
 *
 * <p>用 {@link TempDir} 让每次运行拿独立目录，避免 Spring + JPA 上下文开销。
 */
class MarkdownBackendIT {

    @TempDir Path tmpDir;

    private MarkdownMemoryStore newStore() {
        return new MarkdownMemoryStore(tmpDir.resolve("MEMORY.md"));
    }

    private MemoryService newFacade(MarkdownMemoryStore store) {
        return new DefaultMemoryService(store);
    }

    @Test
    @DisplayName("save 3 条 → recall 全部命中（facade + backend 端到端）")
    void save_three_then_recall_all() {
        MarkdownMemoryStore store = newStore();
        MemoryService facade = newFacade(store);

        MemoryEntry e1 = facade.save(MemoryScope.CORE, "first preference", List.of());
        MemoryEntry e2 = facade.save(MemoryScope.CORE, "second preference", List.of("p"));
        MemoryEntry e3 = facade.save(MemoryScope.ARCHIVE, "archived preference", List.of());

        List<MemoryEntry> core = facade.recallByKeyword("preference", 10, MemoryScope.CORE);
        assertThat(core).hasSize(2);
        assertThat(core).extracting(MemoryEntry::id).containsExactlyInAnyOrder(e1.id(), e2.id());

        List<MemoryEntry> arch = facade.recallByKeyword("preference", 10, MemoryScope.ARCHIVE);
        assertThat(arch).hasSize(1);
        assertThat(arch.get(0).id()).isEqualTo(e3.id());

        // 跨 scope filter = null 拿到全部 3 条
        List<MemoryEntry> all = facade.recallByKeyword("preference", 10, null);
        assertThat(all).hasSize(3);
    }

    @Test
    @DisplayName("外部删除 ## Core 段 → 下次 save(core) 自动重建（集成 facade + store）")
    void missing_core_section_rebuilt_via_facade() throws Exception {
        MarkdownMemoryStore store = newStore();
        MemoryService facade = newFacade(store);

        facade.save(MemoryScope.CORE, "alpha", List.of());
        facade.save(MemoryScope.ARCHIVE, "beta", List.of());

        // 外部删除 ## Core 段及其条目（运维脚本 / 用户手动操作模拟）
        List<String> lines = Files.readAllLines(tmpDir.resolve("MEMORY.md"));
        List<String> trimmed = new ArrayList<>();
        boolean inCore = false;
        for (String l : lines) {
            if (l.trim().equalsIgnoreCase("## Core")) { inCore = true; continue; }
            if (l.trim().equalsIgnoreCase("## Archive")) { inCore = false; }
            if (!inCore) trimmed.add(l);
        }
        Files.writeString(tmpDir.resolve("MEMORY.md"),
            String.join(System.lineSeparator(), trimmed) + System.lineSeparator());

        // facade 调用 → store 重建 ## Core
        facade.save(MemoryScope.CORE, "gamma", List.of());

        // 验证：gamma 落在 CORE 段（lenient recovery 集成）
        List<MemoryEntry> coreHits = facade.recallByKeyword("gamma", 10, MemoryScope.CORE);
        assertThat(coreHits).hasSize(1);
        assertThat(coreHits.get(0).scope()).isEqualTo(MemoryScope.CORE);
        // 旧 alpha 已删 → 查不到
        assertThat(facade.recallByKeyword("alpha", 10, MemoryScope.CORE)).isEmpty();
        // archive 区不受影响
        assertThat(facade.recallByKeyword("beta", 10, MemoryScope.ARCHIVE)).hasSize(1);
    }

    @Test
    @DisplayName("N=10 线程并发 save 100 次（facade 转发） → 文件含 100 行无丢失")
    void concurrent_save_via_facade_serializes() throws Exception {
        MarkdownMemoryStore store = newStore();
        MemoryService facade = newFacade(store);

        int threadCount = 10;
        int savesPerThread = 10;
        int totalSaves = threadCount * savesPerThread;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        Set<String> seenIds = ConcurrentHashMap.newKeySet();

        for (int t = 0; t < threadCount; t++) {
            int tid = t;
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < savesPerThread; i++) {
                        MemoryEntry e = facade.save(MemoryScope.CORE,
                            "thread-" + tid + "-entry-" + i, List.of("t" + tid));
                        seenIds.add(e.id());
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        assertThat(doneGate.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // 100 条 entry 唯一 ID + 文件 entry 行数 == 100
        assertThat(seenIds).hasSize(totalSaves);
        long entryLines = Files.readAllLines(tmpDir.resolve("MEMORY.md")).stream()
            .filter(l -> l.startsWith("- "))
            .count();
        assertThat(entryLines).isEqualTo(totalSaves);

        // facade recall 全部 100 条
        List<MemoryEntry> all = facade.recallByKeyword("entry", 200, MemoryScope.CORE);
        assertThat(all).hasSize(totalSaves);
    }

    @Test
    @DisplayName("DefaultMemoryService 守卫：save(scope=null) → IllegalArgumentException")
    void facade_rejects_null_scope() {
        MarkdownMemoryStore store = newStore();
        MemoryService facade = newFacade(store);
        try {
            facade.save(null, "x", List.of());
            org.junit.jupiter.api.Assertions.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            assertThat(ex.getMessage()).contains("scope");
        }
    }

    @Test
    @DisplayName("DefaultMemoryService 守卫：clear(CORE) → IllegalStateException（backend 不动）")
    void facade_rejects_clear_core_and_backend_untouched() {
        MarkdownMemoryStore store = newStore();
        MemoryService facade = newFacade(store);
        facade.save(MemoryScope.CORE, "x", List.of());
        try {
            facade.clear(MemoryScope.CORE);
            org.junit.jupiter.api.Assertions.fail("Expected IllegalStateException");
        } catch (IllegalStateException ex) {
            assertThat(ex.getMessage()).contains("core");
        }
        // 守卫在前 —— backend 的 core 数据保留
        assertThat(facade.recallByKeyword("x", 10, MemoryScope.CORE)).hasSize(1);
    }

    @Test
    @DisplayName("DefaultMemoryService 守卫：topK 上限 / 下限归一化（即使 backend 拿到 200 / 0 也安全）")
    void facade_normalizes_topk() {
        MarkdownMemoryStore store = newStore();
        MemoryService facade = newFacade(store);
        // 写 3 条
        for (int i = 0; i < 3; i++) {
            facade.save(MemoryScope.CORE, "x" + i, List.of());
        }
        // topK = 200 → normalize 到 100 → 3 条全返（≤100）
        assertThat(facade.recallByKeyword("x", 200, null)).hasSize(3);
        // topK = 0 → normalize 到 1 → 仅 1 条
        assertThat(facade.recallByKeyword("x", 0, null)).hasSize(1);
        // topK = 1 → 仅 1 条
        assertThat(facade.recallByKeyword("x", 1, null)).hasSize(1);
        // topK = -5 → normalize 到 1
        assertThat(facade.recallByKeyword("x", -5, null)).hasSize(1);
    }

    @Test
    @DisplayName("MarkdownMemoryStore.isHealthy() 在集成上下文中返回 true")
    void store_is_healthy_in_integration() {
        MarkdownMemoryStore store = newStore();
        MemoryService facade = newFacade(store);
        facade.save(MemoryScope.CORE, "x", List.of());
        assertThat(store.isHealthy()).isTrue();
    }
}