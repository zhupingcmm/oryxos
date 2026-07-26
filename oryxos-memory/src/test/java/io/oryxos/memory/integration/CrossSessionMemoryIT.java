package io.oryxos.memory.integration;

import io.oryxos.memory.DefaultMemoryService;
import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.MemoryService;
import io.oryxos.memory.backend.LongTermMemoryStore;
import io.oryxos.memory.backend.MarkdownMemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T018（006-memory-layer Phase 3）—— 跨 Session 召回集成测试（spec US-1 / SC-002）。
 *
 * <p>场景（[quickstart.md §场景 1](../../../../../specs/006-memory-layer/quickstart.md)）：
 * <ol>
 *   <li>Spring 上下文启动 → {@link DefaultMemoryService} Bean 就绪</li>
 *   <li>在 Session A 模拟 Agent save 一条核心区记忆</li>
 *   <li>同一 Spring 上下文内、但新建 SessionManager（B 模拟独立 Session），调 recallByKeyword 召回前次记录</li>
 *   <li>断言 100% 命中 —— 跨 Session 召回成功</li>
 * </ol>
 *
 * <p>本集成测试**不**重启 Spring 上下文（Phase 3 MVP 范围）—— 验证：
 * <ul>
 *   <li>长期层（{@link LongTermMemoryStore} = MarkdownMemoryStore）持久化跨"虚拟 Session 边界"</li>
 *   <li>门面层（{@link MemoryService} = DefaultMemoryService）通过 SessionManager 边界独立</li>
 * </ul>
 *
 * <p>重启场景（两个独立 JVM 进程）由 Phase 8 的 {@code scripts/test-cross-session-memory.sh} 覆盖。
 */
class CrossSessionMemoryIT {

    Path tmpDir;
    MarkdownMemoryStore backend;
    DefaultMemoryService memoryService;
    // SessionManager 不通过 Spring DI —— 测试直接 new（spec FR-002 会话层独立）
    io.oryxos.memory.SessionManager sessionA;
    io.oryxos.memory.SessionManager sessionB;

    @BeforeEach
    void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("oryxos-memory-it-");
        backend = new MarkdownMemoryStore(tmpDir.resolve("MEMORY.md"));
        memoryService = new DefaultMemoryService(backend);
        sessionA = new io.oryxos.memory.SessionManager("sess-A");
        sessionB = new io.oryxos.memory.SessionManager("sess-B");
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
    @DisplayName("SC-002：Session A save → Session B recall 100% 命中（跨 Session 边界）")
    void cross_session_recall_works() {
        // 1. Session A：Agent save 一条核心区记忆（含 tags）
        sessionA.addMessage(io.oryxos.memory.SessionManager.Message.user("save my preference"));
        MemoryEntry saved = memoryService.save(
            MemoryScope.CORE,
            "用户偏好 PR 标签 = bug+enhancement",
            List.of("preference", "github"));
        assertThat(saved).isNotNull();
        assertThat(saved.scope()).isEqualTo(MemoryScope.CORE);
        assertThat(saved.id()).isNotBlank();

        // 2. Session B（独立 sessionId）调 recallByKeyword
        sessionB.addMessage(io.oryxos.memory.SessionManager.Message.user("what are my prefs"));
        List<MemoryEntry> hits = memoryService.recallByKeyword("PR 标签 偏好", 5, MemoryScope.CORE);

        // 3. 断言 100% 命中
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).id()).isEqualTo(saved.id());
        assertThat(hits.get(0).content()).contains("PR 标签");
        assertThat(hits.get(0).tags()).contains("preference", "github");
    }

    @Test
    @DisplayName("SC-002：跨 Session 召回命中按 createdAt DESC 排序")
    void cross_session_recall_order_desc() throws InterruptedException {
        // 写 3 条 —— 老 → 新
        MemoryEntry old = memoryService.save(MemoryScope.CORE,
            "older fact about weather", List.of());
        Thread.sleep(10);
        MemoryEntry middle = memoryService.save(MemoryScope.CORE,
            "middle fact about weather", List.of());
        Thread.sleep(10);
        MemoryEntry recent = memoryService.save(MemoryScope.CORE,
            "recent fact about weather", List.of());

        List<MemoryEntry> hits = memoryService.recallByKeyword("weather", 5, MemoryScope.CORE);
        assertThat(hits).extracting(MemoryEntry::id)
            .containsExactly(recent.id(), middle.id(), old.id());
    }

    @Test
    @DisplayName("SC-002：scope 过滤 — cross-session recallByKeyword(scopeFilter=ARCHIVE) 仅命中 archive")
    void cross_session_recall_scope_filter() {
        memoryService.save(MemoryScope.CORE, "core-only fact", List.of());
        MemoryEntry arch = memoryService.save(MemoryScope.ARCHIVE,
            "archive fact about meeting", List.of());
        List<MemoryEntry> coreHits = memoryService.recallByKeyword(
            "fact", 10, MemoryScope.CORE);
        assertThat(coreHits).allMatch(e -> e.scope() == MemoryScope.CORE);
        List<MemoryEntry> archHits = memoryService.recallByKeyword(
            "meeting", 10, MemoryScope.ARCHIVE);
        assertThat(archHits).hasSize(1);
        assertThat(archHits.get(0).id()).isEqualTo(arch.id());
    }

    @Test
    @DisplayName("SC-002：SessionManager 边界独立 —— A 的对话消息不进 B 的 getMessages()")
    void session_manager_isolation() {
        sessionA.addMessage(io.oryxos.memory.SessionManager.Message.user("A only"));
        sessionB.addMessage(io.oryxos.memory.SessionManager.Message.user("B only"));
        assertThat(sessionA.getMessages()).hasSize(1);
        assertThat(sessionB.getMessages()).hasSize(1);
        assertThat(sessionA.getMessages().get(0).content()).isEqualTo("A only");
        assertThat(sessionB.getMessages().get(0).content()).isEqualTo("B only");
    }
}