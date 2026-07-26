package io.oryxos.memory.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.backend.MarkdownMemoryStore;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T036 第二步 —— markdown → SQLite 迁移后端切换验证（SC-004）。
 *
 * <p>流程：
 * <ol>
 *   <li>markdown 写 3 条到 MEMORY.md（tmp 目录）</li>
 *   <li>模拟 migrate-markdown-to-sqlite.sh：把 MEMORY.md 解析后写到 SQLite agent_memories</li>
 *   <li>用 SqliteMemoryStore 召回 → 3 条都在 + 内容一致</li>
 * </ol>
 *
 * <p>实际脚本 {@code scripts/migrate-markdown-to-sqlite.sh}（T048）会做相同的迁移逻辑，
 * 本测试是脚本行为的 Java 镜像验证。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.ANY)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:oryxos-bsw-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@EntityScan(basePackages = "io.oryxos.memory.repository")
@EnableJpaRepositories(basePackages = "io.oryxos.memory.repository")
@Import(MarkdownToSqliteMigrationIT.TestConfig.class)
class MarkdownToSqliteMigrationIT {

    @Autowired DataSource dataSource;
    @Autowired MemoryEntryRepository agentMemories;
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

    @Test
    @DisplayName("SC-004 markdown → SQLite 迁移后：SqliteMemoryStore 召回 3 条 + 内容一致")
    void migration_then_sqlite_recall() throws IOException {
        Path tmp = Files.createTempDirectory("oryxos-bsw-mig-");
        try {
            Path memoryFile = tmp.resolve("MEMORY.md");
            MarkdownMemoryStore mdStore = new MarkdownMemoryStore(memoryFile);
            mdStore.save(MemoryScope.CORE, "fact one", List.of("a"));
            mdStore.save(MemoryScope.CORE, "fact two", List.of("b"));
            mdStore.save(MemoryScope.ARCHIVE, "archived fact", List.of());

            // 模拟迁移脚本：把 MEMORY.md 内容写到 SQLite
            // 用 recallByScope 不带 query 过滤（recallByKeyword("",...) 会返空）
            List<MemoryEntry> coreMigrated = mdStore.recallByScope(MemoryScope.CORE, 100);
            List<MemoryEntry> archMigrated = mdStore.recallByScope(MemoryScope.ARCHIVE, 100);
            assertThat(coreMigrated).hasSize(2);
            assertThat(archMigrated).hasSize(1);
            List<MemoryEntry> migrated = new ArrayList<>();
            migrated.addAll(coreMigrated);
            migrated.addAll(archMigrated);
            for (MemoryEntry e : migrated) {
                MemoryEntryEntity entity = new MemoryEntryEntity(
                    UUID.randomUUID().toString(),
                    e.scope(),
                    e.content(),
                    "[]",
                    e.source(),
                    e.createdAt().toEpochMilli());
                agentMemories.save(entity);
            }

            // SqliteMemoryStore 召回 → 3 条都在
            assertThat(agentMemories.count()).isEqualTo(3L);
            List<MemoryEntry> coreHits = sqliteStore.recallByKeyword("fact", 10, MemoryScope.CORE);
            assertThat(coreHits).hasSize(2);

            List<MemoryEntry> archHits = sqliteStore.recallByKeyword("archived", 10, MemoryScope.ARCHIVE);
            assertThat(archHits).hasSize(1);
            assertThat(archHits.get(0).content()).isEqualTo("archived fact");
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