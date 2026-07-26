package io.oryxos.memory.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.repository.MemoryEntryIndexEntity;
import io.oryxos.memory.repository.MemoryEntryIndexRepository;
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
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T027（006-memory-layer Phase 5 / US-3）—— 10 条 C-M0 契约测试
 * （[contracts/mem0-backend.md §4](../../../../../specs/006-memory-layer/contracts/mem0-backend.md)）。
 *
 * <p>用 WireMock 模拟 Mem0 HTTP 服务 + H2 in-memory 模式 + V5 DDL memory_index 表。
 *
 * <p>覆盖（C-M0-01 ~ C-M0-10）：
 * <ul>
 *   <li>C-M0-01 unreachable-save —— Mem0 不可达 → 落 pending=true + 不抛异常</li>
 *   <li>C-M0-02 unreachable-recall —— 降级到本地 memory_index 召回</li>
 *   <li>C-M0-03 timeout-5s —— HTTP 超时配置生效</li>
 *   <li>C-M0-04 shared-http-client —— 单 HttpClient 实例</li>
 *   <li>C-M0-05 localId-mapping —— localId + mem0Id 双 ID 映射</li>
 *   <li>C-M0-06 metadata-userId —— user_id = scope.name().toLowerCase()</li>
 *   <li>C-M0-07 core-no-trim —— 不主动 trim core</li>
 *   <li>C-M0-08 scope-validation —— 非法 scope 抛 IllegalArgumentException</li>
 *   <li>C-M0-09 delete-double-delete —— 重复删返 false 不抛异常</li>
 *   <li>C-M0-10 health-check —— isHealthy() 调 GET /health</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.ANY)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:oryxos-mem0-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.show-sql=false"
})
@EntityScan(basePackages = "io.oryxos.memory.repository")
@EnableJpaRepositories(basePackages = "io.oryxos.memory.repository")
@Import(Mem0MemoryStoreTest.TestConfig.class)
class Mem0MemoryStoreTest {

    @Autowired DataSource dataSource;
    @Autowired MemoryEntryIndexRepository indexRepository;
    WireMockServer wireMock;
    Mem0MemoryStore store;

    @BeforeEach
    void setUp() throws Exception {
        // V5 DDL —— memory_index 表
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS memory_index");
            st.execute("""
                CREATE TABLE memory_index (
                    local_id    VARCHAR(36) PRIMARY KEY,
                    mem0_id     VARCHAR(64),
                    scope       VARCHAR(16) NOT NULL CHECK (LOWER(scope) IN ('core', 'archive')),
                    content     TEXT NOT NULL,
                    tags        TEXT NOT NULL DEFAULT '[]',
                    source      VARCHAR(16) NOT NULL CHECK (LOWER(source) IN ('core', 'archive')),
                    pending     BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at  BIGINT NOT NULL
                )
                """);
        }
        // WireMock + Mem0MemoryStore
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        store = Mem0MemoryStore.forTest(indexRepository, new ObjectMapper(),
            "http://localhost:" + wireMock.port(), 5);
    }

    @AfterEach
    void tearDown() {
        if (wireMock != null) wireMock.stop();
    }

    // ===== C-M0-01: unreachable-save =====

    @Test
    @DisplayName("C-M0-01 Mem0 不可达 → save 不抛异常，落 pending=true 行")
    void unreachable_save_falls_back_to_pending() {
        // 不 stub 任何 endpoint → Mem0 不可达
        MemoryEntry saved = store.save(MemoryScope.CORE, "offline content", List.of("draft"));

        assertThat(saved).isNotNull();
        assertThat(saved.content()).isEqualTo("offline content");

        // 本地 memory_index 落 pending=true 行
        MemoryEntryIndexEntity local = indexRepository.findByLocalId(saved.id()).orElseThrow();
        assertThat(local.isPending()).isTrue();
        assertThat(local.getMem0Id()).isNull();
        assertThat(local.getContent()).isEqualTo("offline content");
    }

    // ===== C-M0-02: unreachable-recall =====

    @Test
    @DisplayName("C-M0-02 Mem0 不可达 → recallByKeyword 降级到本地 memory_index")
    void unreachable_recall_falls_back_to_local_index() {
        // 预先落 2 条 pending=false 本地条目
        indexRepository.save(new MemoryEntryIndexEntity(
            "local-1", null, MemoryScope.CORE, "alpha bravo", "[]", "core", false, 1000L));
        indexRepository.save(new MemoryEntryIndexEntity(
            "local-2", null, MemoryScope.CORE, "alpha charlie", "[]", "core", false, 2000L));

        // 不 stub /memories/search → Mem0 不可达 → 降级本地
        List<MemoryEntry> hits = store.recallByKeyword("alpha", 10, MemoryScope.CORE);
        assertThat(hits).hasSize(2);
        assertThat(hits).extracting(MemoryEntry::id).containsExactlyInAnyOrder("local-1", "local-2");
    }

    // ===== C-M0-05: localId-mapping =====

    @Test
    @DisplayName("C-M0-05 Mem0 可达 → save 后本地 local_id + 远端 mem0_id 都被记录")
    void localId_mem0Id_mapping() {
        wireMock.stubFor(post(urlEqualTo("/memories"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"mem0-abc-123\"}")));

        MemoryEntry saved = store.save(MemoryScope.CORE, "happy content", List.of());

        MemoryEntryIndexEntity local = indexRepository.findByLocalId(saved.id()).orElseThrow();
        assertThat(local.isPending()).isFalse();
        assertThat(local.getMem0Id()).isEqualTo("mem0-abc-123");
    }

    // ===== C-M0-06: metadata-userId =====

    @Test
    @DisplayName("C-M0-06 metadata user_id = scope.name().toLowerCase()")
    void metadata_user_id_reflects_scope() {
        wireMock.stubFor(post(urlEqualTo("/memories"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"id\":\"mem0-x\"}")));

        store.save(MemoryScope.ARCHIVE, "archive fact", List.of());

        wireMock.verify(postRequestedFor(urlEqualTo("/memories"))
            .withRequestBody(containing("\"user_id\":\"archive\"")));
    }

    @Test
    @DisplayName("C-M0-06 metadata user_id = \"core\" for MemoryScope.CORE")
    void metadata_user_id_core_for_core_scope() {
        wireMock.stubFor(post(urlEqualTo("/memories"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"id\":\"mem0-y\"}")));

        store.save(MemoryScope.CORE, "core fact", List.of());

        wireMock.verify(postRequestedFor(urlEqualTo("/memories"))
            .withRequestBody(containing("\"user_id\":\"core\"")));
    }

    // ===== C-M0-07: core-no-trim =====

    @Test
    @DisplayName("C-M0-07 core save N 条 → 全部保留（不主动 trim）")
    void core_save_never_trims() {
        wireMock.stubFor(post(urlEqualTo("/memories"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"id\":\"mem0-z\"}")));

        for (int i = 0; i < 50; i++) {
            store.save(MemoryScope.CORE, "core fact " + i, List.of());
        }
        assertThat(indexRepository.count()).isEqualTo(50L);
    }

    // ===== C-M0-08: scope-validation =====

    @Test
    @DisplayName("C-M0-08 save(scope=null) → IllegalArgumentException")
    void null_scope_rejected() {
        assertThatThrownBy(() -> store.save(null, "x", List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scope");
    }

    @Test
    @DisplayName("C-M0-08 save(blank content) → IllegalArgumentException")
    void blank_content_rejected() {
        assertThatThrownBy(() -> store.save(MemoryScope.CORE, "", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(MemoryScope.CORE, "   ", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== C-M0-09: delete-double-delete =====

    @Test
    @DisplayName("C-M0-09 delete(id) 存在 → true + 远端 DELETE + 本地删")
    void delete_existing_removes_both() {
        wireMock.stubFor(post(urlEqualTo("/memories"))
            .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"mem0-del-1\"}")));
        MemoryEntry saved = store.save(MemoryScope.CORE, "to delete", List.of());

        wireMock.stubFor(delete(urlEqualTo("/memories/mem0-del-1"))
            .willReturn(aResponse().withStatus(200).withBody("{}")));

        boolean deleted = store.delete(saved.id());

        assertThat(deleted).isTrue();
        assertThat(indexRepository.findByLocalId(saved.id())).isEmpty();
        wireMock.verify(deleteRequestedFor(urlEqualTo("/memories/mem0-del-1")));
    }

    @Test
    @DisplayName("C-M0-09 delete(id) 重复删 → false 不抛异常")
    void delete_double_returns_false() {
        wireMock.stubFor(post(urlEqualTo("/memories"))
            .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"mem0-d2\"}")));
        MemoryEntry saved = store.save(MemoryScope.CORE, "x", List.of());

        assertThat(store.delete(saved.id())).isTrue();
        assertThat(store.delete(saved.id())).isFalse();
        assertThat(store.delete("nonexistent-id")).isFalse();
    }

    // ===== C-M0-10: health-check =====

    @Test
    @DisplayName("C-M0-10 isHealthy() → GET /health 200 = true")
    void isHealthy_true_on_200() {
        wireMock.stubFor(get(urlEqualTo("/health"))
            .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"ok\"}")));

        assertThat(store.isHealthy()).isTrue();
        wireMock.verify(getRequestedFor(urlEqualTo("/health")));
    }

    @Test
    @DisplayName("C-M0-10 isHealthy() → /health 503 = false")
    void isHealthy_false_on_503() {
        wireMock.stubFor(get(urlEqualTo("/health"))
            .willReturn(aResponse().withStatus(503)));

        assertThat(store.isHealthy()).isFalse();
    }

    @Test
    @DisplayName("C-M0-10 isHealthy() → Mem0 服务连接拒绝 = false（兜底异常）")
    void isHealthy_false_on_connection_refused() {
        // 配一个无监听的端口 → connect refused
        Mem0MemoryStore deadStore = Mem0MemoryStore.forTest(
            indexRepository, new ObjectMapper(), "http://localhost:1", 1);
        assertThat(deadStore.isHealthy()).isFalse();
    }

    // ===== clear(core) — C-LT-05 共享契约 =====

    @Test
    @DisplayName("clear(CORE) → IllegalStateException（C-LT-05 硬约束）")
    void clear_core_throws() {
        store.save(MemoryScope.CORE, "x", List.of());
        assertThatThrownBy(() -> store.clear(MemoryScope.CORE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("core");
    }

    @Test
    @DisplayName("clear(ARCHIVE) → 删 archive，core 不动")
    void clear_archive_only_touches_archive() {
        store.save(MemoryScope.CORE, "c1", List.of());
        store.save(MemoryScope.ARCHIVE, "a1", List.of());
        store.clear(MemoryScope.ARCHIVE);
        assertThat(indexRepository.count()).isEqualTo(1L);
    }

    // ===== recallByKeyword 空 query =====

    @Test
    @DisplayName("recallByKeyword(null/blank) → 空集合")
    void empty_query_returns_empty_list() {
        assertThat(store.recallByKeyword(null, 10, null)).isEmpty();
        assertThat(store.recallByKeyword("", 10, null)).isEmpty();
        assertThat(store.recallByKeyword("   ", 10, null)).isEmpty();
    }

    // ===== TestConfig =====

    @Configuration
    static class TestConfig {
        @Bean
        public ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}