package io.oryxos.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.memory.backend.LongTermMemoryStore;
import io.oryxos.memory.backend.MarkdownMemoryStore;
import io.oryxos.memory.backend.Mem0MemoryStore;
import io.oryxos.memory.backend.SqliteMemoryStore;
import io.oryxos.memory.repository.MemoryEntryEntity;
import io.oryxos.memory.repository.MemoryEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T037（006-memory-layer Phase 6 / US-4）—— 跨 3 后端验证 Scope 显式隔离契约。
 *
 * <p>3 后端 × 2 scope × 3 契约 = 9 条断言：
 * <ul>
 *   <li><b>C-LT-05 core-no-clear</b> —— 3 后端 clear(core) 全部抛 IllegalStateException</li>
 *   <li><b>C-SQ-02 archive-lazy-trim</b> —— SQLite 后端 save(archive) 1500 条 → 1000</li>
 *   <li><b>C-MD-09 / C-M0-07 core-no-trim</b> —— 三后端 save(core) 1500 条 → 全部保留</li>
 * </ul>
 *
 * <p>用 {@code @DataJpaTest} + H2 跑 SQLite 后端（Schema 自建 V4）；Markdown / Mem0 不依赖
 * JPA Spring 上下文——直接 new 出实例。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.ANY)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:oryxos-scope-ct;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.show-sql=false",
    "oryxos.memory.archive.max-entries=1000"
})
@EntityScan(basePackages = "io.oryxos.memory.repository")
@EnableJpaRepositories(basePackages = "io.oryxos.memory.repository")
@Import(ScopeContractTest.TestConfig.class)
class ScopeContractTest {

    @Autowired DataSource dataSource;
    @Autowired MemoryEntryRepository repository;
    @Autowired SqliteMemoryStore sqliteStore;

    @BeforeEach
    void applyV4Ddl() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS agent_memories");
            st.execute("""
                CREATE TABLE agent_memories (
                    id          VARCHAR(36) PRIMARY KEY,
                    scope       VARCHAR(16) NOT NULL CHECK (LOWER(scope) IN ('core', 'archive')),
                    content     TEXT NOT NULL,
                    tags        TEXT NOT NULL DEFAULT '[]',
                    source      VARCHAR(16) NOT NULL CHECK (LOWER(source) IN ('core', 'archive')),
                    created_at  BIGINT NOT NULL
                )
                """);
        }
    }

    // ===== C-LT-05: 三后端 clear(core) 统一拒绝 =====

    @Test
    @DisplayName("C-LT-05 SqliteMemoryStore.clear(CORE) → IllegalStateException")
    void sqlite_clear_core_rejected() {
        sqliteStore.save(MemoryScope.CORE, "core fact", List.of());
        assertThatThrownBy(() -> sqliteStore.clear(MemoryScope.CORE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("core");
        // 守卫在前 —— core 数据保留
        assertThat(repository.countByScope(MemoryScope.CORE)).isEqualTo(1L);
    }

    @Test
    @DisplayName("C-LT-05 MarkdownMemoryStore.clear(CORE) → IllegalStateException")
    void markdown_clear_core_rejected() throws IOException {
        Path tmp = Files.createTempDirectory("oryxos-scope-md-");
        try {
            MarkdownMemoryStore md = new MarkdownMemoryStore(tmp.resolve("MEMORY.md"));
            md.save(MemoryScope.CORE, "core fact", List.of());
            assertThatThrownBy(() -> md.clear(MemoryScope.CORE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("core");
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    @DisplayName("C-LT-05 Mem0MemoryStore.clear(CORE) → IllegalStateException")
    void mem0_clear_core_rejected() {
        // clear 入口在前 —— 守卫在 HTTP 之先；不需要 MemoryEntryIndexRepository
        // 用 JDK 动态代理生成 no-op 实现（不依赖 Mockito）
        io.oryxos.memory.repository.MemoryEntryIndexRepository repo = noopRepo();
        Mem0MemoryStore m = Mem0MemoryStore.forTest(repo, new ObjectMapper(), "http://localhost:1", 1);
        assertThatThrownBy(() -> m.clear(MemoryScope.CORE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("core");
    }

    /** JDK 动态代理 no-op repository（clear 入口不调用 repo 方法）。 */
    @SuppressWarnings("unchecked")
    static io.oryxos.memory.repository.MemoryEntryIndexRepository noopRepo() {
        return (io.oryxos.memory.repository.MemoryEntryIndexRepository) java.lang.reflect.Proxy.newProxyInstance(
            io.oryxos.memory.repository.MemoryEntryIndexRepository.class.getClassLoader(),
            new Class<?>[]{io.oryxos.memory.repository.MemoryEntryIndexRepository.class},
            (proxy, method, args) -> {
                Class<?> rt = method.getReturnType();
                if (rt == java.util.Optional.class) return java.util.Optional.empty();
                if (rt == boolean.class) return false;
                if (rt == long.class) return 0L;
                if (rt == int.class) return 0;
                if (rt == java.util.List.class) return java.util.List.of();
                if (rt == org.springframework.data.domain.Page.class) return org.springframework.data.domain.Page.empty();
                return null;
            });
    }

    // ===== C-SQ-02: SQLite 后端 archive lazy trim =====

    @Test
    @DisplayName("C-SQ-02 SqliteMemoryStore: save(archive) 1500 → count = 1000")
    void sqlite_archive_lazy_trim() throws InterruptedException {
        for (int i = 0; i < 1500; i++) {
            sqliteStore.save(MemoryScope.ARCHIVE, "arch " + i, List.of());
            if (i % 50 == 0) Thread.sleep(1);
        }
        assertThat(repository.countByScope(MemoryScope.ARCHIVE)).isEqualTo(1000L);
    }

    // ===== C-MD-09 / C-M0-07: 三后端 core 不 trim =====

    @Test
    @DisplayName("C-SQ-03 SqliteMemoryStore: save(core) 1500 → count = 1500（永不 trim）")
    void sqlite_core_no_trim() {
        for (int i = 0; i < 1500; i++) {
            sqliteStore.save(MemoryScope.CORE, "core " + i, List.of());
        }
        assertThat(repository.countByScope(MemoryScope.CORE)).isEqualTo(1500L);
    }

    @Test
    @DisplayName("C-MD-09 MarkdownMemoryStore: save(core) 1500 → 文件含 1500 行（永不 trim）")
    void markdown_core_no_trim() throws IOException {
        Path tmp = Files.createTempDirectory("oryxos-scope-md2-");
        try {
            MarkdownMemoryStore md = new MarkdownMemoryStore(tmp.resolve("MEMORY.md"));
            for (int i = 0; i < 1500; i++) {
                md.save(MemoryScope.CORE, "core " + i, List.of());
            }
            int lineCount = (int) Files.readAllLines(tmp.resolve("MEMORY.md")).stream()
                .filter(l -> l.startsWith("- ["))
                .count();
            assertThat(lineCount).isEqualTo(1500);
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    @DisplayName("C-MD-09 MarkdownMemoryStore: save(archive) 1500 → 文件含 1500 行（Markdown 不主动 trim）")
    void markdown_archive_no_trim() throws IOException {
        Path tmp = Files.createTempDirectory("oryxos-scope-md3-");
        try {
            MarkdownMemoryStore md = new MarkdownMemoryStore(tmp.resolve("MEMORY.md"));
            for (int i = 0; i < 1500; i++) {
                md.save(MemoryScope.ARCHIVE, "arch " + i, List.of());
            }
            int lineCount = (int) Files.readAllLines(tmp.resolve("MEMORY.md")).stream()
                .filter(l -> l.startsWith("- ["))
                .count();
            assertThat(lineCount).isEqualTo(1500);
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    @DisplayName("三后端 scope 隔离：clear(ARCHIVE) 不影响 CORE")
    void all_backends_clear_archive_does_not_touch_core() throws IOException {
        // SQLite: ARCHIVE 删，core 保留
        sqliteStore.save(MemoryScope.CORE, "sqlite-c", List.of());
        sqliteStore.save(MemoryScope.ARCHIVE, "sqlite-a", List.of());
        sqliteStore.save(MemoryScope.ARCHIVE, "sqlite-a2", List.of());
        sqliteStore.clear(MemoryScope.ARCHIVE);
        assertThat(repository.countByScope(MemoryScope.CORE)).isEqualTo(1L);
        assertThat(repository.countByScope(MemoryScope.ARCHIVE)).isZero();

        // Markdown: ARCHIVE 删，core 保留
        Path tmp = Files.createTempDirectory("oryxos-scope-md4-");
        try {
            MarkdownMemoryStore md = new MarkdownMemoryStore(tmp.resolve("MEMORY.md"));
            md.save(MemoryScope.CORE, "md-c", List.of());
            md.save(MemoryScope.ARCHIVE, "md-a", List.of());
            md.save(MemoryScope.ARCHIVE, "md-a2", List.of());
            md.clear(MemoryScope.ARCHIVE);

            List<MemoryEntry> cores = md.recallByScope(MemoryScope.CORE, 10);
            List<MemoryEntry> archs = md.recallByScope(MemoryScope.ARCHIVE, 10);
            assertThat(cores).hasSize(1);
            assertThat(cores.get(0).content()).isEqualTo("md-c");
            assertThat(archs).isEmpty();
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Configuration
    static class TestConfig {
        @Bean public ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean public SqliteMemoryStore sqliteMemoryStore(MemoryEntryRepository repo, ObjectMapper mapper) {
            return new SqliteMemoryStore(repo, mapper, 1000);
        }
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