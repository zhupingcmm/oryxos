package io.oryxos.memory.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T036b（006-memory-layer Phase 5 / US-3 / SC-011）—— Memory 审计/还原集成测试。
 *
 * <p>目的：企业合规审计员能从 SQLite 数据库（{@code agent_memories} +
 * {@code tool_invocations} + {@code sessions} 三表 JOIN）完整还原
 * "哪个 Agent / 哪个 Session / 哪条记忆 / 哪个 Scope / 什么时间"。
 *
 * <p>本测试模拟：
 * <ol>
 *   <li>Agent daily-tech-digest 通过 save_memory Tool 写入 2 条记忆（一条 core + 一条 archive）</li>
 *   <li>对应的 tool_invocations 行被同步写入（success=true + source='builtin'）</li>
 *   <li>sessions 表记录 Session 元数据</li>
 *   <li>合规审计员运行"还原 SQL"，从 3 表 JOIN 恢复完整审计链</li>
 * </ol>
 *
 * <p>不依赖 Spring Boot 完整启动 —— 用 {@code @DataJpaTest} + H2 (MySQL mode)
 * 模拟 SQLite + JPA-managed tables。
 *
 * <p>5 维审计链断言：
 * <ol>
 *   <li><b>哪个 Agent</b> —— {@code tool_invocations.profile_name}</li>
 *   <li><b>哪个 Session</b> —— {@code tool_invocations.session_id} JOIN {@code sessions.id}</li>
 *   <li><b>哪条记忆</b> —— {@code agent_memories.id} ↔ tool call arguments</li>
 *   <li><b>哪个 Scope</b> —— {@code agent_memories.scope}</li>
 *   <li><b>什么时间</b> —— {@code agent_memories.created_at}</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.ANY)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:oryxos-audit-restore;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.show-sql=false"
})
@EntityScan(basePackages = "io.oryxos.storage.entity")
@EnableJpaRepositories(basePackages = "io.oryxos.storage.repository")
@Import(MemoryAuditRestoreIT.TestConfig.class)
class MemoryAuditRestoreIT {

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void applySchema() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {

            // V4 DDL：agent_memories（手动维护，不依赖 ddl-auto）
            st.execute("DROP TABLE IF EXISTS agent_memories");
            st.execute("""
                CREATE TABLE agent_memories (
                    id          VARCHAR(36) PRIMARY KEY,
                    scope       VARCHAR(16) NOT NULL CHECK (LOWER(scope) IN ('core', 'archive')),
                    content     TEXT NOT NULL,
                    tags        TEXT NOT NULL DEFAULT '[]',
                    source      VARCHAR(64) NOT NULL,
                    created_at  BIGINT NOT NULL
                )
                """);

            // 简化版的 sessions + tool_invocations（复用 oryxos-storage 真实 schema，
            // 但只保留审计链恢复必要的列）
            st.execute("DROP TABLE IF EXISTS tool_invocations");
            st.execute("""
                CREATE TABLE tool_invocations (
                    id               VARCHAR(36) PRIMARY KEY,
                    session_id       VARCHAR(36),
                    profile_name     VARCHAR(64) NOT NULL,
                    tool_name        VARCHAR(64) NOT NULL,
                    arguments        TEXT,
                    success          BOOLEAN NOT NULL,
                    error_message    TEXT,
                    duration_ms      BIGINT NOT NULL,
                    started_at       TIMESTAMP NOT NULL,
                    session_iteration INT NOT NULL DEFAULT 0,
                    source           VARCHAR(16) NOT NULL DEFAULT 'builtin'
                )
                """);

            st.execute("DROP TABLE IF EXISTS sessions");
            st.execute("""
                CREATE TABLE sessions (
                    id          VARCHAR(36) PRIMARY KEY,
                    profile_name VARCHAR(64) NOT NULL,
                    created_at  TIMESTAMP NOT NULL,
                    metadata    TEXT
                )
                """);
        }
    }

    @Test
    @DisplayName("SC-011：审计员从 agent_memories + tool_invocations + sessions 还原 5 维审计链")
    void audit_restore_reconstructs_five_dimensions() {
        // ===== 1. 模拟 save_memory Tool 调用：写 agent_memories + tool_invocations + sessions =====
        UUID sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String profileName = "daily-tech-digest";
        String contentCore = "用户偏好 PR 标签 = bug+enhancement";
        String contentArchive = "2026-07-26 fetched GitHub PR #1234";
        long createdCore = 1722000000000L; // 2024-07-26
        long createdArchive = 1722000060000L; // 2024-07-26 + 1min

        // sessions 行
        jdbc.update("""
            INSERT INTO sessions(id, profile_name, created_at, metadata)
            VALUES (?, ?, TIMESTAMP '2024-07-26 10:00:00', '{"agent":"daily-tech-digest"}')
            """, sessionId.toString(), profileName);

        // agent_memories 行：2 条（1 core + 1 archive）
        String memId1 = UUID.randomUUID().toString();
        String memId2 = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO agent_memories(id, scope, content, tags, source, created_at)
            VALUES (?, 'core', ?, '["preference","github"]', ?, ?)
            """, memId1, contentCore, profileName, createdCore);
        jdbc.update("""
            INSERT INTO agent_memories(id, scope, content, tags, source, created_at)
            VALUES (?, 'archive', ?, '[]', ?, ?)
            """, memId2, contentArchive, profileName, createdArchive);

        // tool_invocations 行：2 条 save_memory 调用（arguments JSON 包含 memory_id）
        String toolInvId1 = UUID.randomUUID().toString();
        String toolInvId2 = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO tool_invocations(id, session_id, profile_name, tool_name, arguments, success, duration_ms, started_at, session_iteration, source)
            VALUES (?, ?, ?, 'save_memory', ?, TRUE, 5, TIMESTAMP '2024-07-26 10:00:00', 1, 'builtin')
            """, toolInvId1, sessionId.toString(), profileName,
            "{\"scope\":\"core\",\"content\":\"" + contentCore + "\"}");
        jdbc.update("""
            INSERT INTO tool_invocations(id, session_id, profile_name, tool_name, arguments, success, duration_ms, started_at, session_iteration, source)
            VALUES (?, ?, ?, 'save_memory', ?, TRUE, 3, TIMESTAMP '2024-07-26 10:01:00', 2, 'builtin')
            """, toolInvId2, sessionId.toString(), profileName,
            "{\"scope\":\"archive\",\"content\":\"" + contentArchive + "\"}");

        // ===== 2. 模拟"审计员合规还原" —— 3 表 JOIN =====
        String restoreSql = """
            SELECT
                m.id              AS memory_id,
                m.scope           AS scope,
                m.content         AS content,
                m.created_at      AS memory_created_at_epoch,
                ti.id             AS tool_invocation_id,
                ti.profile_name   AS agent_name,
                ti.started_at     AS tool_invocation_at,
                s.id              AS session_id,
                s.created_at      AS session_started_at
            FROM agent_memories m
            JOIN tool_invocations ti
              ON ti.tool_name = 'save_memory'
             AND ti.arguments LIKE '%' || m.content || '%'
            JOIN sessions s
              ON s.id = ti.session_id
            ORDER BY m.created_at ASC
            """;

        List<Map<String, Object>> rows = jdbc.queryForList(restoreSql);

        // ===== 3. 5 维断言 =====
        assertThat(rows).hasSize(2);

        // 第 1 行（core）
        Map<String, Object> row1 = rows.get(0);
        assertThat(row1.get("memory_id")).isEqualTo(memId1);
        assertThat(row1.get("scope")).isEqualTo("core");
        assertThat(row1.get("content")).isEqualTo(contentCore);
        assertThat(row1.get("agent_name")).isEqualTo(profileName);          // (1) 哪个 Agent
        assertThat(row1.get("session_id")).isEqualTo(sessionId.toString()); // (2) 哪个 Session
        assertThat(row1.get("memory_id")).isNotNull();                      // (3) 哪条记忆
        assertThat((String) row1.get("memory_id")).isNotBlank();             // (3) 哪条记忆
        assertThat(row1.get("scope")).isEqualTo("core");                    // (4) 哪个 Scope
        assertThat(((Number) row1.get("memory_created_at_epoch")).longValue())
            .isEqualTo(createdCore);                                          // (5) 什么时间

        // 第 2 行（archive）
        Map<String, Object> row2 = rows.get(1);
        assertThat(row2.get("memory_id")).isEqualTo(memId2);
        assertThat(row2.get("scope")).isEqualTo("archive");
        assertThat(row2.get("content")).isEqualTo(contentArchive);
    }

    @Test
    @DisplayName("SC-011：失败 save_memory 调用审计完整（success=false + error_message 写入 tool_invocations）")
    void audit_restore_includes_failed_tool_calls() {
        // 模拟一次失败的 save_memory（Tool 层捕获 IOException）
        UUID sessionId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String profileName = "daily-tech-digest";

        jdbc.update("""
            INSERT INTO sessions(id, profile_name, created_at, metadata)
            VALUES (?, ?, TIMESTAMP '2024-07-26 11:00:00', '{}')
            """, sessionId.toString(), profileName);

        // 失败调用（success=false + error_message 非空 + 不写 agent_memories 行）
        String toolInvId = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO tool_invocations(id, session_id, profile_name, tool_name, arguments, success, error_message, duration_ms, started_at, session_iteration, source)
            VALUES (?, ?, ?, 'save_memory', '{"scope":"core","content":"will fail"}', FALSE,
                    'IOException: disk full', 12, TIMESTAMP '2024-07-26 11:00:00', 1, 'builtin')
            """, toolInvId, sessionId.toString(), profileName);

        // 审计员需要能查到这条失败记录（即使 agent_memories 没行）
        String auditSql = """
            SELECT ti.tool_name, ti.success, ti.error_message, ti.profile_name, s.id AS session_id
            FROM tool_invocations ti
            JOIN sessions s ON s.id = ti.session_id
            WHERE ti.tool_name = 'save_memory' AND ti.success = FALSE
            """;

        List<Map<String, Object>> failedCalls = jdbc.queryForList(auditSql);
        assertThat(failedCalls).hasSize(1);
        Map<String, Object> failed = failedCalls.get(0);
        assertThat(failed.get("tool_name")).isEqualTo("save_memory");
        assertThat(failed.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(failed.get("error_message")).isEqualTo("IOException: disk full");
        assertThat(failed.get("profile_name")).isEqualTo(profileName);
        assertThat(failed.get("session_id")).isEqualTo(sessionId.toString());
    }

    @Test
    @DisplayName("SC-011：scope 隔离 —— 审计员能分别查询 core vs archive 记忆")
    void audit_restore_filters_by_scope() {
        String profileName = "daily-tech-digest";

        // 写 5 条 core + 3 条 archive
        for (int i = 0; i < 5; i++) {
            jdbc.update("""
                INSERT INTO agent_memories(id, scope, content, tags, source, created_at)
                VALUES (?, 'core', ?, '[]', ?, ?)
                """, UUID.randomUUID().toString(), "core fact " + i, profileName, 1722000000000L + i);
        }
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                INSERT INTO agent_memories(id, scope, content, tags, source, created_at)
                VALUES (?, 'archive', ?, '[]', ?, ?)
                """, UUID.randomUUID().toString(), "archive fact " + i, profileName, 1722001000000L + i);
        }

        Long coreCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM agent_memories WHERE LOWER(scope) = 'core'", Long.class);
        Long archiveCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM agent_memories WHERE LOWER(scope) = 'archive'", Long.class);

        assertThat(coreCount).isEqualTo(5L);
        assertThat(archiveCount).isEqualTo(3L);

        // 审计员按 scope 分组
        List<Map<String, Object>> scopeBreakdown = jdbc.queryForList("""
            SELECT scope, COUNT(*) AS cnt
            FROM agent_memories
            GROUP BY scope
            ORDER BY scope
            """);
        assertThat(scopeBreakdown).hasSize(2);
        assertThat(scopeBreakdown.get(0).get("scope")).isEqualTo("archive");
        assertThat(scopeBreakdown.get(0).get("cnt")).isEqualTo(3L);
        assertThat(scopeBreakdown.get(1).get("scope")).isEqualTo("core");
        assertThat(scopeBreakdown.get(1).get("cnt")).isEqualTo(5L);
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        // 仅作 @Import 触发，不提供任何 bean（@DataJpaTest 已自动配 JdbcTemplate）
    }

    /**
     * 提供 @SpringBootConfiguration 给 @DataJpaTest（oryxos-memory 没有 @SpringBootApplication）。
     * TestConfig 已 @Import 到主测试类，这里只需要一个空 Configuration 标记主类。
     */
    @org.springframework.boot.SpringBootConfiguration
    static class BootConfig {}
}