package io.oryxos.memory.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.memory.DefaultMemoryService;
import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.MemoryService;
import io.oryxos.memory.backend.SqliteMemoryStore;
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
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T056 SQLITE 部分（006-memory-layer Phase 8 / Polish）—— NFR-001：
 * SqliteMemoryStore N=100 save+recall P95 ≤ 200ms（H2 in-memory）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.ANY)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:oryxos-perf-sq;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.show-sql=false",
    "oryxos.memory.archive.max-entries=1000"
})
@EntityScan(basePackages = "io.oryxos.memory.repository")
@EnableJpaRepositories(basePackages = "io.oryxos.memory.repository")
@Import(SqlitePerfIT.TestConfig.class)
class SqlitePerfIT {

    private static final int N = 100;
    private static final long P95_BUDGET_MS = 200L;

    @Autowired DataSource dataSource;
    @Autowired SqliteMemoryStore sqliteStore;

    @BeforeEach
    void applyDdl() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS agent_memories");
            st.execute("""
                CREATE TABLE agent_memories (
                    id VARCHAR(36) PRIMARY KEY,
                    scope VARCHAR(16) NOT NULL CHECK (LOWER(scope) IN ('core', 'archive')),
                    content TEXT NOT NULL,
                    tags TEXT NOT NULL DEFAULT '[]',
                    source VARCHAR(16) NOT NULL CHECK (LOWER(source) IN ('core', 'archive')),
                    created_at BIGINT NOT NULL
                )
                """);
        }
    }

    @Test
    @DisplayName("NFR-001: SqliteMemoryStore N=100 save P95 ≤ 200ms")
    void sqlite_save_p95_under_200ms() {
        MemoryService svc = new DefaultMemoryService(sqliteStore);
        for (int i = 0; i < 5; i++) svc.save(MemoryScope.CORE, "warmup " + i, List.of());

        long[] saveMs = new long[N];
        for (int i = 0; i < N; i++) {
            long t0 = System.nanoTime();
            svc.save(MemoryScope.CORE, "perf entry " + i, List.of("perf"));
            saveMs[i] = (System.nanoTime() - t0) / 1_000_000L;
        }
        long p95 = percentile(saveMs, 95);
        System.out.printf("  Sqlite save P95 = %dms%n", p95);
        assertThat(p95).as("Sqlite save P95 (ms)").isLessThanOrEqualTo(P95_BUDGET_MS);
    }

    @Test
    @DisplayName("NFR-001: SqliteMemoryStore N=100 recall P95 ≤ 200ms")
    void sqlite_recall_p95_under_200ms() {
        MemoryService svc = new DefaultMemoryService(sqliteStore);
        for (int i = 0; i < 5; i++) svc.save(MemoryScope.CORE, "warmup " + i, List.of());
        for (int i = 0; i < N; i++) svc.save(MemoryScope.CORE, "perf entry " + i, List.of());

        long[] recallMs = new long[N];
        for (int i = 0; i < N; i++) {
            long t0 = System.nanoTime();
            List<MemoryEntry> hits = svc.recallByKeyword("perf", 10, MemoryScope.CORE);
            recallMs[i] = (System.nanoTime() - t0) / 1_000_000L;
            assertThat(hits).isNotEmpty();
        }
        long p95 = percentile(recallMs, 95);
        System.out.printf("  Sqlite recall P95 = %dms%n", p95);
        assertThat(p95).as("Sqlite recall P95 (ms)").isLessThanOrEqualTo(P95_BUDGET_MS);
    }

    static long percentile(long[] arr, int p) {
        long[] sorted = arr.clone();
        Arrays.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    @Configuration
    static class TestConfig {
        @Bean public ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean public SqliteMemoryStore sqliteMemoryStore(MemoryEntryRepository repo, ObjectMapper mapper) {
            return new SqliteMemoryStore(repo, mapper, 1000);
        }
    }
}