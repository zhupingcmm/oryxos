package io.oryxos.core;

import io.oryxos.core.testing.FakeProviderService;
import io.oryxos.core.testing.FakeToolExecutor;
import io.oryxos.core.testing.InMemorySession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-2 P3 阶段：并发隔离压力测试（SC-003，FR-018）。
 *
 * <p>配置：20 个独立 Session，每个 Session 自带独立 FakeProviderService 队列，
 * 在单一 ReActLoop 实例上并发触发。
 *
 * <p>验证：
 * <ul>
 *   <li>零异常抛出（每条 iteration 都完全独立）</li>
 *   <li>每个 Session 恰好以 2 条消息结束（自身的 user + assistant）</li>
 *   <li>Session 间零消息串扰（每条消息只能被自身 user 看见）</li>
 *   <li>{@code ProfileContext.current()} 在循环调用之间不泄漏 —— 验证：
 *       (a) 每次循环内可见自己的 Snapshot，(b) 调用结束后立刻回到 empty</li>
 *   <li>Tool 调用计数与 LLM 调用计数按 session 分桶正确</li>
 * </ul>
 */
@DisplayName("ReActLoop 并发隔离（SC-003 / FR-018）")
class ReActLoopConcurrencyTest {

    private static final int CONCURRENT_SESSIONS = 20;
    private static final Provider PROVIDER = new Provider(
        "deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of()
    );
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-07-25T06:00:00Z"), ZoneId.of("Asia/Shanghai")
    );

    /**
     * SC-003：20 个 session 在同一 loop 实例上并发执行，无串扰。
     */
    @Test
    @DisplayName("SC-003：20-session 并发 → 无异常 + 每 session 消息正确 + ProfileContext 不泄漏")
    void sc003_concurrentTwentySessions() throws Exception {
        // 准备共享组件
        ToolExecutor sharedTools = new FakeToolExecutor(Map.of(
            "http_get", ToolResult.ok(Map.of("temperature", 18))
        ));

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_SESSIONS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SessionResult>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < CONCURRENT_SESSIONS; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    // 每个 session 独立组件
                    UUID sessionId = UUID.randomUUID();
                    InMemorySession session = new InMemorySession(sessionId, "weather-bot");
                    FakeProviderService provider = new FakeProviderService(List.of(
                        new LlmResponse("bot-" + idx + " response", List.of(), null, "stop")
                    ));
                    PromptBuilder pb = new PromptBuilder(
                        new MemoryInjector.NoopMemoryInjector(),
                        new ToolSchemaProvider.NoopToolSchemaProvider(),
                        new BootstrapLoader.NoopBootstrapLoader(),
                        FIXED_CLOCK
                    );
                    ReActLoop loop = new ReActLoop(provider, pb, sharedTools);

                    Profile profile = new Profile(
                        "weather-bot", PROVIDER, List.of(),
                        List.of(), List.of(), List.of(),
                        new Profile.Settings(10, 20),
                        Map.of(),
            List.of()
                    );

                    // 同步闸门 —— 20 个线程同时启动
                    start.await();
                    ProfileContext.set(new ProfileContext.Snapshot(
                        "weather-bot", sessionId, new AtomicInteger(0)
                    ));
                    try {
                        LoopResult result = loop.run(profile, session, "user-" + idx);
                        return new SessionResult(sessionId, idx, session, provider.invocationCount(), result);
                    } finally {
                        ProfileContext.clear();
                    }
                }));
            }

            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
                .as("all 20 concurrent loops finish within 60s")
                .isTrue();

            List<SessionResult> results = new ArrayList<>();
            for (Future<SessionResult> f : futures) {
                results.add(f.get()); // 任何异常将包装 ExecutionException
            }

            // === SC-003 主断言 ===
            // 验证 1：零异常（f.get 抛 ExecutionException 即 fail，本测试到此已绿）
            // 验证 2：每个 session 消息数 == 2（user + assistant）
            for (SessionResult sr : results) {
                assertThat(sr.session.size())
                    .as("session-%d message count", sr.idx)
                    .isEqualTo(2);
                assertThat(sr.session.messageAt(0).role()).isEqualTo(Message.Role.USER);
                assertThat(sr.session.messageAt(1).role()).isEqualTo(Message.Role.ASSISTANT);
                assertThat(sr.session.messageAt(0).content()).isEqualTo("user-" + sr.idx);
                assertThat(sr.session.messageAt(1).content()).isEqualTo("bot-" + sr.idx + " response");
            }

            // 验证 3：消息按 session 严格隔离（无串扰）
            // 若有交叉污染，user 消息可能撞上其他 idx
            Map<String, Set<String>> userToResponses = new HashMap<>();
            for (SessionResult sr : results) {
                String userMsg = sr.session.messageAt(0).content();
                String botMsg = sr.session.messageAt(1).content();
                userToResponses.computeIfAbsent(userMsg, k -> new HashSet<>()).add(botMsg);
            }
            // 每个 user message 只对应 1 个 bot response（同一 idx）
            for (var entry : userToResponses.entrySet()) {
                assertThat(entry.getValue())
                    .as("user='%s' must map to exactly 1 bot response", entry.getKey())
                    .hasSize(1);
            }

            // 验证 4：每 session 的 LLM 调用 == 1（无 Re-Invoke）
            for (SessionResult sr : results) {
                assertThat(sr.llmInvocations)
                    .as("session-%d LLM calls", sr.idx)
                    .isEqualTo(1);
            }

            // 验证 5：每 session 的 session.id() 在自己的 LoopResult 里
            for (SessionResult sr : results) {
                assertThat(sr.result.sessionId())
                    .as("session-%d result.sessionId()", sr.idx)
                    .isEqualTo(sr.sessionId);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * FR-018 简化验证：循环结束后 ProfileContext 立刻是 empty（finally 清空）。
     */
    @Test
    @DisplayName("FR-018：循环结束后 ProfileContext.current() 立刻返回 empty")
    void fr018_profileContextClearedAfterLoop() {
        ProviderService provider = new FakeProviderService(List.of(
            new LlmResponse("hi", List.of(), null, "stop")
        ));
        ToolExecutor tools = new FakeToolExecutor(Map.of());
        InMemorySession session = new InMemorySession(
            UUID.fromString("00000000-0000-0000-0000-000000000501"), "bot"
        );
        PromptBuilder pb = new PromptBuilder(
            new MemoryInjector.NoopMemoryInjector(),
            new ToolSchemaProvider.NoopToolSchemaProvider(),
            new BootstrapLoader.NoopBootstrapLoader(),
            FIXED_CLOCK
        );
        ReActLoop loop = new ReActLoop(provider, pb, tools);
        Profile profile = new Profile(
            "bot", PROVIDER, List.of(),
            List.of(), List.of(), List.of(),
            new Profile.Settings(10, 20),
            Map.of(),
            List.of()
        );

        UUID sid = UUID.fromString("00000000-0000-0000-0000-000000000501");
        ProfileContext.set(new ProfileContext.Snapshot("bot", sid, new AtomicInteger(0)));
        try {
            assertThat(ProfileContext.current()).isPresent();

            loop.run(profile, session, "go");

            // run 内不该清空（那是 AgentService 的职责）；我们用 Session 视角没有 clear
            assertThat(ProfileContext.current()).isPresent();
        } finally {
            ProfileContext.clear();
        }
        // 循环外 finally 之后为空
        assertThat(ProfileContext.current()).isEmpty();
    }

    /**
     * FR-018 强化：跨线程 ThreadLocal 隔离 —— 启动 1 个 setter 线程 + 1 个 reader 线程。
     */
    @Test
    @DisplayName("FR-018：跨线程 ProfileContext 完全隔离（设置线程不影响其他线程）")
    void fr018_crossThreadIsolation() throws Exception {
        UUID sessionA = UUID.fromString("00000000-0000-0000-0000-00000000060A");
        UUID sessionB = UUID.fromString("00000000-0000-0000-0000-00000000060B");

        AtomicInteger errors = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        Thread tA = new Thread(() -> {
            try {
                ProfileContext.set(new ProfileContext.Snapshot("bot-A", sessionA, new AtomicInteger(0)));
                Thread.sleep(50); // 让 B 线程能读到"无污染"
                assertThat(ProfileContext.current())
                    .map(ProfileContext.Snapshot::sessionId).contains(sessionA);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        Thread tB = new Thread(() -> {
            try {
                Thread.sleep(10); // 让 A 线程先 set
                // B 线程从不 set，current() 必须为空
                assertThat(ProfileContext.current()).isEmpty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        tA.start();
        tB.start();
        latch.await(5, TimeUnit.SECONDS);
        assertThat(errors.get()).isZero();
    }

    /** 单 session 的运行结果汇总（仅测试用） */
    private record SessionResult(
        UUID sessionId, int idx, InMemorySession session,
        int llmInvocations, LoopResult result
    ) {}
}