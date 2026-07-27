package io.oryxos.memory.integration;

import io.oryxos.memory.DefaultMemoryService;
import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.backend.MarkdownMemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T019（006-memory-layer Phase 3）—— 写后立刻读召回 100% 命中（C-LT-01 / C-MS-01）。
 *
 * <p>覆盖两条契约：
 * <ul>
 *   <li><b>C-LT-01</b> —— Markdown 后端 save 后立刻 read，100% 命中</li>
 *   <li><b>C-MS-01</b>（FR-007）—— MemoryService 不缓存；save 后下一次 recallByKeyword MUST 直接读底层 IO</li>
 * </ul>
 *
 * <p>N=100 次 save 立即 recall，断言所有 entry_id 都在 recall 命中集合内 —— 验证：
 * <ol>
 *   <li>save 后写文件原子完成（无丢失）</li>
 *   <li>recall 直接读最新文件（无缓存滞后）</li>
 * </ol>
 */
class ReadAfterWriteIT {

    Path tmpDir;
    MarkdownMemoryStore backend;
    DefaultMemoryService memoryService;

    @BeforeEach
    void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("oryxos-memory-raw-");
        backend = new MarkdownMemoryStore(tmpDir.resolve("MEMORY.md"));
        memoryService = new DefaultMemoryService(backend);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tmpDir != null && Files.exists(tmpDir)) {
            try (var stream = Files.walk(tmpDir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    @Test
    @DisplayName("C-LT-01：N=100 次 save → 立即 recall 100% 命中（C-MS-01 no-cache）")
    void write_then_read_100_percent() {
        int N = 100;
        List<String> savedIds = new ArrayList<>(N);

        // 1. save N 条
        for (int i = 0; i < N; i++) {
            MemoryEntry e = memoryService.save(MemoryScope.CORE,
                "user preference #" + i + " about project-X",
                List.of("preference"));
            savedIds.add(e.id());
        }

        // 2. recallByKeyword —— 用跨所有 i 的关键词
        List<MemoryEntry> hits = memoryService.recallByKeyword(
            "project-X", N + 10, MemoryScope.CORE);

        // 3. 断言 100% 命中
        assertThat(hits).hasSize(N);
        List<String> hitIds = hits.stream().map(MemoryEntry::id).toList();
        assertThat(hitIds).containsExactlyInAnyOrderElementsOf(savedIds);

        // 4. 验证 topK cap：recall topK=10 截断为 10
        List<MemoryEntry> capped = memoryService.recallByKeyword("project-X", 10, MemoryScope.CORE);
        assertThat(capped).hasSize(10);
    }

    @Test
    @DisplayName("C-LT-01：write 后 recallByScope 同样 100% 命中")
    void write_then_recall_by_scope() {
        int N = 50;
        for (int i = 0; i < N; i++) {
            memoryService.save(MemoryScope.CORE, "core entry #" + i, List.of());
        }
        for (int i = 0; i < N; i++) {
            memoryService.save(MemoryScope.ARCHIVE, "archive entry #" + i, List.of());
        }
        assertThat(memoryService.recallByScope(MemoryScope.CORE, 100)).hasSize(N);
        assertThat(memoryService.recallByScope(MemoryScope.ARCHIVE, 100)).hasSize(N);
    }

    @Test
    @DisplayName("C-LT-01：save → delete → recall 不应再命中（删除立即生效）")
    void delete_then_recall_excludes() {
        MemoryEntry e = memoryService.save(MemoryScope.CORE,
            "ephemeral fact about today", List.of());
        // 先确认能召回
        assertThat(memoryService.recallByKeyword("ephemeral", 5, MemoryScope.CORE))
            .hasSize(1);
        // 删除
        assertThat(memoryService.delete(e.id())).isTrue();
        // 召回应该空
        assertThat(memoryService.recallByKeyword("ephemeral", 5, MemoryScope.CORE))
            .isEmpty();
    }

    @Test
    @DisplayName("C-LT-01：clear(ARCHIVE) → recallByScope(ARCHIVE) 返回空，recallByScope(CORE) 不受影响")
    void clear_archive_does_not_touch_core() {
        memoryService.save(MemoryScope.CORE, "core fact", List.of());
        memoryService.save(MemoryScope.ARCHIVE, "archive fact 1", List.of());
        memoryService.save(MemoryScope.ARCHIVE, "archive fact 2", List.of());

        memoryService.clear(MemoryScope.ARCHIVE);
        assertThat(memoryService.recallByScope(MemoryScope.ARCHIVE, 10)).isEmpty();
        // core 区不受影响（C-MS-02 / FR-009 守卫）
        assertThat(memoryService.recallByScope(MemoryScope.CORE, 10)).hasSize(1);
    }

    @Test
    @DisplayName("C-LT-05：clear(CORE) MUST 抛 IllegalStateException（门面层守卫）")
    void clear_core_throws() {
        memoryService.save(MemoryScope.CORE, "core fact", List.of());
        try {
            memoryService.clear(MemoryScope.CORE);
            org.junit.jupiter.api.Assertions.fail("Expected IllegalStateException");
        } catch (IllegalStateException ex) {
            assertThat(ex.getMessage()).contains("core");
        }
        // core 条目仍在
        assertThat(memoryService.recallByScope(MemoryScope.CORE, 10)).hasSize(1);
    }
}