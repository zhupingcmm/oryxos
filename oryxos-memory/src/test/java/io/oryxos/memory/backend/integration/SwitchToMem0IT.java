package io.oryxos.memory.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.backend.Mem0MemoryStore;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T036 第三步 —— 切到 mem0 + WireMock 验证 save 成功 + memory_index 落 1 行（SC-004）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.ANY)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:oryxos-mem0-bsw;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@EntityScan(basePackages = "io.oryxos.memory.repository")
@EnableJpaRepositories(basePackages = "io.oryxos.memory.repository")
@Import(SwitchToMem0IT.TestConfig.class)
class SwitchToMem0IT {

    @Autowired DataSource dataSource;
    @Autowired MemoryEntryIndexRepository indexRepository;

    WireMockServer wireMock;
    Mem0MemoryStore mem0Store;

    @BeforeEach
    void setUp() throws Exception {
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
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(post(urlEqualTo("/memories"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"mem0-bsw-1\"}")));
        mem0Store = Mem0MemoryStore.forTest(indexRepository, new ObjectMapper(),
            "http://localhost:" + wireMock.port(), 5);
    }

    @AfterEach
    void tearDown() {
        if (wireMock != null) wireMock.stop();
    }

    @Test
    @DisplayName("SC-004 切到 mem0 + WireMock：save 成功 + memory_index 落 1 行 pending=false")
    void mem0_backend_save_and_indexed() {
        MemoryEntry saved = mem0Store.save(MemoryScope.CORE, "mem0 fact", List.of("x"));
        assertThat(saved).isNotNull();

        MemoryEntryIndexEntity local = indexRepository.findByLocalId(saved.id()).orElseThrow();
        assertThat(local.getMem0Id()).isEqualTo("mem0-bsw-1");
        assertThat(local.isPending()).isFalse();
    }

    @Configuration
    static class TestConfig {
        @Bean public ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}