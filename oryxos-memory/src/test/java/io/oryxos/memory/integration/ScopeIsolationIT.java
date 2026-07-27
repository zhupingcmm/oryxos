package io.oryxos.memory.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.memory.DefaultMemoryService;
import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.MemoryService;
import io.oryxos.memory.backend.MarkdownMemoryStore;
import io.oryxos.memory.backend.SqliteMemoryStore;
import io.oryxos.memory.repository.MemoryEntryRepository;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T041（006-memory-layer Phase 6 / US-4）—— 场景 4 Scope 隔离集成测试。
 *
 * <p>3 后端 × 2 scope × 1500 条写入 → 验证 core 全保留 + archive 按 maxEntries 裁剪；
 * 调 {@code memoryService.clear(MemoryScope.CORE)} → 抛 IllegalStateException（SC-003 / FR-009 / FR-010 / C-LT-05）。
 *
 * <p>SQLite 后端用真 JPA + H2（V4 DDL 自建）；Markdown / Mem0 用本地实例（无需 Spring）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.ANY)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:oryxos-scope-iso;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.show-sql=false",
    "oryxos.memory.archive.max-entries=1000"
})
@EntityScan(basePackages = "io.oryxos.memory.repository")
@EnableJpaRepositories(basePackages = "io.oryxos.memory.repository")
@Import(ScopeIsolationIT.TestConfig.class)
class ScopeIsolationIT {

    @Autowired DataSource dataSource;
    @Autowired MemoryEntryRepository repository;
    @Autowired SqliteMemoryStore sqliteStore;

    Path tmpDir;
    MarkdownMemoryStore mdStore;
    MemoryService mdService;
    MemoryService sqliteService;

    @BeforeEach
    void setUp() throws Exception {
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
        tmpDir = Files.createTempDirectory("oryxos-scope-iso-");
        mdStore = new MarkdownMemoryStore(tmpDir.resolve("MEMORY.md"));
        mdService = new DefaultMemoryService(mdStore);
        sqliteService = new DefaultMemoryService(sqliteStore);
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteRecursively(tmpDir);
    }

    // ===== SC-003: 三后端 core 全保留（1500 条） =====

    @Test
    @DisplayName("SC-003 SqliteMemoryStore: 1500 条 core 写入 → count = 1500")
    void sqlite_core_1500_records_all_kept() throws InterruptedException {
        for (int i = 0; i < 1500; i++) {
            sqliteService.save(MemoryScope.CORE, "core " + i, List.of());
            if (i % 100 == 0) Thread.sleep(1);
        }
        assertThat(repository.countByScope(MemoryScope.CORE)).isEqualTo(1500L);
    }

    @Test
    @DisplayName("SC-003 MarkdownMemoryStore: 1500 条 core 写入 → 文件含 1500 行")
    void markdown_core_1500_records_all_kept() throws IOException {
        for (int i = 0; i < 1500; i++) {
            mdService.save(MemoryScope.CORE, "core " + i, List.of());
        }
        int lineCount = (int) Files.readAllLines(tmpDir.resolve("MEMORY.md")).stream()
            .filter(l -> l.startsWith("- ["))
            .count();
        assertThat(lineCount).isEqualTo(1500);
    }

    // ===== FR-010: SQLite 后端 archive lazy trim =====

    @Test
    @DisplayName("FR-010 SqliteMemoryStore: 1500 条 archive → count = 1000（lazy trim）")
    void sqlite_archive_1500_records_trimmed_to_1000() throws InterruptedException {
        for (int i = 0; i < 1500; i++) {
            sqliteService.save(MemoryScope.ARCHIVE, "arch " + i, List.of());
            if (i % 50 == 0) Thread.sleep(1);
        }
        assertThat(repository.countByScope(MemoryScope.ARCHIVE)).isEqualTo(1000L);
        // 保留最新 1000 条（recallByScope 取 5 → 1499, 1498, 1497, 1496, 1495）
        List<MemoryEntry> top = sqliteService.recallByScope(MemoryScope.ARCHIVE, 5);
        assertThat(top).hasSize(5);
        assertThat(top.get(0).content()).isEqualTo("arch 1499");
    }

    @Test
    @DisplayName("MarkdownMemoryStore: 1500 条 archive → 文件含 1500 行（Markdown 不主动 trim）")
    void markdown_archive_1500_records_no_trim() throws IOException {
        for (int i = 0; i < 1500; i++) {
            mdService.save(MemoryScope.ARCHIVE, "arch " + i, List.of());
        }
        int lineCount = (int) Files.readAllLines(tmpDir.resolve("MEMORY.md")).stream()
            .filter(l -> l.startsWith("- ["))
            .count();
        assertThat(lineCount).isEqualTo(1500);
    }

    // ===== C-LT-05: clear(CORE) 抛 IllegalStateException（两个后端） =====

    @Test
    @DisplayName("C-LT-05 SqliteMemoryStore: memoryService.clear(CORE) → IllegalStateException")
    void sqlite_clear_core_rejected() {
        sqliteService.save(MemoryScope.CORE, "core fact", List.of());
        assertThatThrownBy(() -> sqliteService.clear(MemoryScope.CORE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("core");
        // 数据保留
        assertThat(repository.countByScope(MemoryScope.CORE)).isEqualTo(1L);
    }

    @Test
    @DisplayName("C-LT-05 MarkdownMemoryStore: memoryService.clear(CORE) → IllegalStateException")
    void markdown_clear_core_rejected() {
        mdService.save(MemoryScope.CORE, "core fact", List.of());
        assertThatThrownBy(() -> mdService.clear(MemoryScope.CORE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("core");
        // 文件保留
        assertThat(mdService.recallByScope(MemoryScope.CORE, 10)).hasSize(1);
    }

    // ===== C-LT-04: clear(ARCHIVE) 行为正确 =====

    @Test
    @DisplayName("C-LT-04 SqliteMemoryStore: clear(ARCHIVE) 删 archive 不动 core")
    void sqlite_clear_archive_does_not_touch_core() {
        sqliteService.save(MemoryScope.CORE, "c1", List.of());
        sqliteService.save(MemoryScope.ARCHIVE, "a1", List.of());
        sqliteService.save(MemoryScope.ARCHIVE, "a2", List.of());
        sqliteService.clear(MemoryScope.ARCHIVE);
        assertThat(repository.countByScope(MemoryScope.CORE)).isEqualTo(1L);
        assertThat(repository.countByScope(MemoryScope.ARCHIVE)).isZero();
    }

    @Test
    @DisplayName("C-LT-04 MarkdownMemoryStore: clear(ARCHIVE) 删 archive 不动 core")
    void markdown_clear_archive_does_not_touch_core() {
        mdService.save(MemoryScope.CORE, "c1", List.of());
        mdService.save(MemoryScope.ARCHIVE, "a1", List.of());
        mdService.save(MemoryScope.ARCHIVE, "a2", List.of());
        mdService.clear(MemoryScope.ARCHIVE);
        List<MemoryEntry> cores = mdService.recallByScope(MemoryScope.CORE, 10);
        List<MemoryEntry> archs = mdService.recallByScope(MemoryScope.ARCHIVE, 10);
        assertThat(cores).hasSize(1);
        assertThat(archs).isEmpty();
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