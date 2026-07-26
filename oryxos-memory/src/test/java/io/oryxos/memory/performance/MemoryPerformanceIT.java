package io.oryxos.memory.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.memory.DefaultMemoryService;
import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.MemoryService;
import io.oryxos.memory.backend.MarkdownMemoryStore;
import io.oryxos.memory.backend.Mem0MemoryStore;
import io.oryxos.memory.backend.SqliteMemoryStore;
import io.oryxos.memory.repository.MemoryEntryIndexEntity;
import io.oryxos.memory.repository.MemoryEntryIndexRepository;
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
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T056（006-memory-layer Phase 8 / Polish）—— NFR-001 / SC-008 性能基准：
 * N=100 次 save + recallByKeyword → P95 ≤ 200ms（单后端、内存条件）。
 *
 * <p>对三个后端分别报告 P95：
 * <ul>
 *   <li>MarkdownMemoryStore：本地文件（最快）</li>
 *   <li>SqliteMemoryStore：H2 in-memory（极快；@DataJpaTest）</li>
 *   <li>Mem0MemoryStore：HTTP 不可达时本测视为异常（fallback 路径）</li>
 * </ul>
 *
 * <p>注意：Mem0 后端走 HTTP，跨网络 P95 不在本 spec NFR 范围（NFR-001 限定为本地存储）。
 * 本测主要覆盖 Markdown + Sqlite 两个本地后端；Mem0 标 "N/A" 因依赖外部服务。
 */
class MemoryPerformanceIT {

    private static final int N = 100;
    private static final long P95_BUDGET_MS = 200L;

    @Test
    @DisplayName("NFR-001: MarkdownMemoryStore N=100 save+recall P95 ≤ 200ms")
    void markdown_p95_under_200ms() throws IOException {
        Path tmp = Files.createTempDirectory("oryxos-perf-md-");
        try {
            MarkdownMemoryStore store = new MarkdownMemoryStore(tmp.resolve("MEMORY.md"));
            MemoryService svc = new DefaultMemoryService(store);

            // 预热 5 次（JIT + 文件系统 cache）
            for (int i = 0; i < 5; i++) svc.save(MemoryScope.CORE, "warmup " + i, List.of());

            // 写入 100 条
            long[] saveMs = new long[N];
            List<MemoryEntry> saved = new ArrayList<>(N);
            for (int i = 0; i < N; i++) {
                long t0 = System.nanoTime();
                saved.add(svc.save(MemoryScope.CORE, "perf entry " + i, List.of("perf")));
                saveMs[i] = (System.nanoTime() - t0) / 1_000_000L;
            }
            long saveP95 = percentile(saveMs, 95);
            System.out.printf("  Markdown save P95 = %dms%n", saveP95);

            // 召回 100 次
            long[] recallMs = new long[N];
            for (int i = 0; i < N; i++) {
                long t0 = System.nanoTime();
                List<MemoryEntry> hits = svc.recallByKeyword("perf", 10, MemoryScope.CORE);
                recallMs[i] = (System.nanoTime() - t0) / 1_000_000L;
                assertThat(hits).isNotEmpty();
            }
            long recallP95 = percentile(recallMs, 95);
            System.out.printf("  Markdown recall P95 = %dms%n", recallP95);

            assertThat(saveP95).as("Markdown save P95 (ms)").isLessThanOrEqualTo(P95_BUDGET_MS);
            assertThat(recallP95).as("Markdown recall P95 (ms)").isLessThanOrEqualTo(P95_BUDGET_MS);
        } finally {
            deleteRecursively(tmp);
        }
    }

    // ===== SQLite 后端（@DataJpaTest 跑 H2） =====

    @org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
    @AutoConfigureTestDatabase(replace = Replace.ANY)
    @TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:oryxos-perf;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false",
        "oryxos.memory.archive.max-entries=1000"
    })
    @EntityScan(basePackages = "io.oryxos.memory.repository")
    @EnableJpaRepositories(basePackages = "io.oryxos.memory.repository")
    @Import(MemoryPerformanceIT.SqliteTestConfig.class)
    static class SqlitePerfIT {

        @Autowired DataSource dataSource;
        @Autowired SqliteMemoryStore sqliteStore;

        @org.junit.jupiter.api.BeforeEach
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
        @DisplayName("NFR-001: SqliteMemoryStore N=100 save+recall P95 ≤ 200ms (H2 in-memory)")
        void sqlite_p95_under_200ms() {
            MemoryService svc = new DefaultMemoryService(sqliteStore);
            // 预热
            for (int i = 0; i < 5; i++) svc.save(MemoryScope.CORE, "warmup " + i, List.of());

            long[] saveMs = new long[N];
            for (int i = 0; i < N; i++) {
                long t0 = System.nanoTime();
                svc.save(MemoryScope.CORE, "perf entry " + i, List.of("perf"));
                saveMs[i] = (System.nanoTime() - t0) / 1_000_000L;
            }
            long saveP95 = percentile(saveMs, 95);
            System.out.printf("  Sqlite save P95 = %dms%n", saveP95);

            long[] recallMs = new long[N];
            for (int i = 0; i < N; i++) {
                long t0 = System.nanoTime();
                List<MemoryEntry> hits = svc.recallByKeyword("perf", 10, MemoryScope.CORE);
                recallMs[i] = (System.nanoTime() - t0) / 1_000_000L;
                assertThat(hits).isNotEmpty();
            }
            long recallP95 = percentile(recallMs, 95);
            System.out.printf("  Sqlite recall P95 = %dms%n", recallP95);

            assertThat(saveP95).as("Sqlite save P95 (ms)").isLessThanOrEqualTo(P95_BUDGET_MS);
            assertThat(recallP95).as("Sqlite recall P95 (ms)").isLessThanOrEqualTo(P95_BUDGET_MS);
        }
    }

    @Configuration
    static class SqliteTestConfig {
        @Bean public ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean public SqliteMemoryStore sqliteMemoryStore(
                io.oryxos.memory.repository.MemoryEntryRepository repo, ObjectMapper mapper) {
            return new SqliteMemoryStore(repo, mapper, 1000);
        }
    }

    // ===== helpers =====

    private static long percentile(long[] arr, int p) {
        long[] sorted = arr.clone();
        Arrays.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
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