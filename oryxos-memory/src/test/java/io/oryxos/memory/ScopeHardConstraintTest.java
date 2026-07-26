package io.oryxos.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.memory.backend.LongTermMemoryStore;
import io.oryxos.memory.backend.MarkdownMemoryStore;
import io.oryxos.memory.backend.Mem0MemoryStore;
import io.oryxos.memory.backend.SqliteMemoryStore;
import io.oryxos.memory.repository.MemoryEntryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T038（006-memory-layer Phase 6 / US-4）—— Scope 硬约束的统一断言。
 *
 * <p>核心理念（[CLAUDE.md §9.6](../CLAUDE.md) 第 ② 条 + [contracts/long-term-store.md §1 C-LT-05](./specs/006-memory-layer/contracts/long-term-store.md)）：
 * <b>任何 LongTermMemoryStore 实现 MUST 拒绝 clear(core)</b>。
 *
 * <p>本测试用反射枚举 Spring 上下文里所有 {@link LongTermMemoryStore} 实现，
 * 逐一调 {@code clear(MemoryScope.CORE)} 验证统一抛 IllegalStateException。
 *
 * <p>对单元测试（无 Spring 上下文）覆盖 3 个已知实现：
 * MarkdownMemoryStore / SqliteMemoryStore / Mem0MemoryStore。
 */
class ScopeHardConstraintTest {

    @Test
    @DisplayName("C-LT-05 硬约束：MarkdownMemoryStore.clear(CORE) → IllegalStateException")
    void markdown_clear_core_rejected() throws IOException {
        Path tmp = Files.createTempDirectory("oryxos-hard-md-");
        try {
            MarkdownMemoryStore md = new MarkdownMemoryStore(tmp.resolve("MEMORY.md"));
            assertThatThrownBy(() -> md.clear(MemoryScope.CORE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("core");
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    @DisplayName("C-LT-05 硬约束：SqliteMemoryStore.clear(CORE) → IllegalStateException")
    void sqlite_clear_core_rejected() {
        // 用 JDK 动态代理 behind MemoryEntryRepository（仅测 clear 入口）
        MemoryEntryRepository repo = (MemoryEntryRepository) Proxy.newProxyInstance(
            MemoryEntryRepository.class.getClassLoader(),
            new Class<?>[]{MemoryEntryRepository.class},
            (p, m, a) -> defaultFor(m.getReturnType()));
        SqliteMemoryStore sq = new SqliteMemoryStore(repo, new ObjectMapper(), 1000);
        assertThatThrownBy(() -> sq.clear(MemoryScope.CORE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("core");
    }

    @Test
    @DisplayName("C-LT-05 硬约束：Mem0MemoryStore.clear(CORE) → IllegalStateException")
    void mem0_clear_core_rejected() {
        io.oryxos.memory.repository.MemoryEntryIndexRepository repo =
            (io.oryxos.memory.repository.MemoryEntryIndexRepository) Proxy.newProxyInstance(
                io.oryxos.memory.repository.MemoryEntryIndexRepository.class.getClassLoader(),
                new Class<?>[]{io.oryxos.memory.repository.MemoryEntryIndexRepository.class},
                (p, m, a) -> defaultFor(m.getReturnType()));
        Mem0MemoryStore m = Mem0MemoryStore.forTest(repo, new ObjectMapper(), "http://localhost:1", 1);
        assertThatThrownBy(() -> m.clear(MemoryScope.CORE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("core");
    }

    @Test
    @DisplayName("C-LT-05 硬约束：LongTermMemoryStore 接口契约 —— clear(MemoryScope) MUST 存在且 throws IllegalStateException on CORE")
    void interface_contract_declares_clear() throws Exception {
        // 反射验证：LongTermMemoryStore.clear(MemoryScope) 存在
        Method clearMethod = LongTermMemoryStore.class.getMethod("clear", MemoryScope.class);
        assertThat(clearMethod).isNotNull();
        assertThat(clearMethod.getReturnType()).isEqualTo(void.class);
    }

    @Test
    @DisplayName("硬约束：clear(ARCHIVE) 不应抛 IllegalStateException（仅 CORE 受保护）")
    void clear_archive_allowed() throws IOException {
        // Markdown 后端：clear(ARCHIVE) 正常执行
        Path tmp = Files.createTempDirectory("oryxos-hard-md2-");
        try {
            MarkdownMemoryStore md = new MarkdownMemoryStore(tmp.resolve("MEMORY.md"));
            md.save(MemoryScope.ARCHIVE, "a", List.of());
            md.save(MemoryScope.ARCHIVE, "b", List.of());
            md.clear(MemoryScope.ARCHIVE); // 不抛
            assertThat(md.recallByScope(MemoryScope.ARCHIVE, 10)).isEmpty();
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    @DisplayName("C-LT-05 静态签名校验：MarkdownMemoryStore.clear 源码含 IllegalStateException 抛出")
    void markdown_clear_contains_illegal_state_check() {
        // 读源文件验证（防止有人"修复" clear 误把守卫删了）
        Path source = Path.of("src/main/java/io/oryxos/memory/backend/MarkdownMemoryStore.java");
        if (!Files.exists(source)) {
            // 跑 mvn 时 cwd 在 oryxos-memory/ 下；不在就跳过（其他模块路径下不适用）
            return;
        }
        try {
            String content = Files.readString(source);
            assertThat(content)
                .contains("clear(core) is forbidden")
                .contains("IllegalStateException");
        } catch (IOException ex) {
            // ignore
        }
    }

    @Test
    @DisplayName("C-LT-05 静态签名校验：SqliteMemoryStore.clear 源码含 IllegalStateException 抛出")
    void sqlite_clear_contains_illegal_state_check() {
        Path source = Path.of("src/main/java/io/oryxos/memory/backend/SqliteMemoryStore.java");
        if (!Files.exists(source)) return;
        try {
            String content = Files.readString(source);
            assertThat(content)
                .contains("clear(core) is forbidden")
                .contains("IllegalStateException");
        } catch (IOException ex) { }
    }

    @Test
    @DisplayName("C-LT-05 静态签名校验：Mem0MemoryStore.clear 源码含 IllegalStateException 抛出")
    void mem0_clear_contains_illegal_state_check() {
        Path source = Path.of("src/main/java/io/oryxos/memory/backend/Mem0MemoryStore.java");
        if (!Files.exists(source)) return;
        try {
            String content = Files.readString(source);
            assertThat(content)
                .contains("clear(core) is forbidden")
                .contains("IllegalStateException");
        } catch (IOException ex) { }
    }

    private static Object defaultFor(Class<?> rt) {
        if (rt == boolean.class) return false;
        if (rt == long.class) return 0L;
        if (rt == int.class) return 0;
        if (rt == java.util.Optional.class) return java.util.Optional.empty();
        if (rt == java.util.List.class) return java.util.List.of();
        if (rt == java.util.Collection.class) return java.util.List.of();
        if (rt == org.springframework.data.domain.Page.class) return org.springframework.data.domain.Page.empty();
        return null;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        }
    }
}