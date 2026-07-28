package io.oryxos.core.scheduler;

import io.oryxos.core.AgentService;
import io.oryxos.core.LoopResult;
import io.oryxos.core.Profile;
import io.oryxos.core.ProfileRegistry;
import io.oryxos.core.Provider;
import io.oryxos.core.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 008-agent-scheduler US-3 / FR-007 / SC-006 —— error_message 字节级净化 IT。
 *
 * <p>三层校验（C-TER-2 + C-TER-3 + C-TER-4 字节级对齐 007-sandbox-whitelist FR-007）：
 * <ol>
 *   <li>多层嵌套异常 → error_message 不含 byte-level 模式
 *       {@code \n\tat io\.oryxos\.} / {@code \n\tat java\.} / {@code \nCaused by: }</li>
 *   <li>长度 &gt; 2KB → 截断 + {@code ...&lt;truncated&gt;} 后缀（C-TER-3）</li>
 *   <li>{@code success=true} → error_message 为 {@code null}（<b>不</b>为 {@code ""}，C-TER-4）</li>
 * </ol>
 *
 * <p>与 [007-sandbox-whitelist FR-007](../../../../specs/007-sandbox-whitelist/spec.md) 字节级对齐
 * —— 同一规则、同一正则断言，确保调度审计链路与 sandbox 审计链路语义一致。
 */
class SchedulerErrorMessageIT {

    private SlowAgentService agentService;
    private InMemoryProfileRegistry profileRegistry;
    private FakeSessionFactory sessionFactory;
    private FakeScheduleStore scheduleStore;
    private FakeTaskExecutionRecorder recorder;
    private AgentSchedulerImpl scheduler;

    @BeforeEach
    void setUp() {
        agentService = new SlowAgentService();
        profileRegistry = new InMemoryProfileRegistry();
        profileRegistry.register("error-test-agent", makeProfile("error-test-agent"));
        sessionFactory = new FakeSessionFactory(profileRegistry);
        scheduleStore = new FakeScheduleStore();
        recorder = new FakeTaskExecutionRecorder();
        scheduler = new AgentSchedulerImpl(
            agentService, scheduleStore, recorder, sessionFactory);
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null && scheduler.isRunning()) {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName("FR-007 + SC-006：多层嵌套异常 → error_message 不含 \\n\\tat / \\nCaused by: 模式")
    void nestedExceptionByteLevelSanitize() throws Exception {
        // 多层嵌套 RuntimeException —— 含 io.oryxos. / java. stack frames + Caused by:
        agentService.exceptionToThrow.set(new RuntimeException(
            "boom: outer\n\tat io.oryxos.core.scheduler.AgentSchedulerImpl.tick(AgentSchedulerImpl.java:200)\n\tat java.base/java.lang.Thread.run\nCaused by: java.lang.IllegalStateException: nested\n\tat io.oryxos.foo.Bar.baz(Bar.java:42)"));

        scheduler.bootstrap(List.of(new Schedule(
            "error-test-agent", "morning", "0 8 * * *", "UTC", "trigger-msg", true)));
        scheduler.triggerNow("error-test-agent:morning");

        // 等 task_executions 写入完成（≤30s）
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && recorder.records.isEmpty()) {
            Thread.sleep(50);
        }
        assertEquals(1, recorder.records.size(), "应写 1 行 task_executions");
        var rec = recorder.records.get(0);
        String msg = rec.errorMessage();
        assertNotNull(msg, "success=false 时 error_message 必非 null");

        // 字节级断言（C-TER-2 / 007-sandbox-whitelist FR-007 对齐）
        assertTrue(msg.contains("boom: outer"),
            "error_message 含原始 message 头; got: " + msg);
        assertTrue(!msg.contains("\n\tat io.oryxos."),
            "error_message 不含 stack frame (io.oryxos.); got: " + msg);
        assertTrue(!msg.contains("\n\tat java."),
            "error_message 不含 stack frame (java.); got: " + msg);
        assertTrue(!msg.contains("Caused by:"),
            "error_message 不含 nested cause; got: " + msg);
    }

    @Test
    @DisplayName("FR-007 + C-TER-3：error_message > 2KB → 截断 + ...<truncated> 后缀")
    void longMessageTruncated() throws Exception {
        // 构造 3KB 异常 message（无 stack trace，纯文本）—— 应被截断到 2KB
        StringBuilder big = new StringBuilder("huge: ");
        for (int i = 0; i < 3_000; i++) {
            big.append((char) ('a' + (i % 26)));
        }
        agentService.exceptionToThrow.set(new RuntimeException(big.toString()));

        scheduler.bootstrap(List.of(new Schedule(
            "error-test-agent", "big", "0 8 * * *", "UTC", "trigger-msg", true)));
        scheduler.triggerNow("error-test-agent:big");

        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && recorder.records.isEmpty()) {
            Thread.sleep(50);
        }
        var rec = recorder.records.get(0);
        String msg = rec.errorMessage();
        assertNotNull(msg);
        assertTrue(msg.length() <= 2048 + "...<truncated>".length(),
            "error_message 长度应 ≤ 2KB + truncated 后缀; got: " + msg.length());
        assertTrue(msg.endsWith("...<truncated>"),
            "error_message 以 ...<truncated> 结尾; got: ..." + msg.substring(Math.max(0, msg.length() - 30)));
    }

    @Test
    @DisplayName("FR-007 + C-TER-4：success=true → error_message = null（不为 \"\"）")
    void successTrueErrorMessageNull() throws Exception {
        // agentService 不抛异常 → success=true
        scheduler.bootstrap(List.of(new Schedule(
            "error-test-agent", "happy", "0 8 * * *", "UTC", "happy-msg", true)));
        scheduler.triggerNow("error-test-agent:happy");

        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && recorder.records.isEmpty()) {
            Thread.sleep(50);
        }
        var rec = recorder.records.get(0);
        assertTrue(rec.success(), "happy path 必 success=true");
        assertNull(rec.errorMessage(),
            "success=true 时 error_message 必为 null（不为 \"\" / 不为 null-or-blank），per C-TER-4");
    }

    // --- fakes ---

    /** AgentService 替身 —— 可配置抛异常；可模拟慢调用（用于并发去重测试）。 */
    static class SlowAgentService implements AgentService {
        final AtomicReference<RuntimeException> exceptionToThrow = new AtomicReference<>();
        /** 可选 latch —— 调用 process() 时 await（让 caller 等）。 */
        CountDownLatch holdLatch;
        final CopyOnWriteArrayList<Long> callDurations = new CopyOnWriteArrayList<>();
        @Override
        public LoopResult process(Session session, String userMessage) {
            long start = System.nanoTime();
            try {
                RuntimeException toThrow = exceptionToThrow.get();
                if (toThrow != null) {
                    throw toThrow;
                }
                if (holdLatch != null) {
                    try {
                        holdLatch.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                return new LoopResult("echo: " + userMessage, 1, false,
                    session.profileName(),
                    session.id() == null ? UUID.randomUUID() : session.id());
            } finally {
                callDurations.add((System.nanoTime() - start) / 1_000_000L);
            }
        }
    }

    static class InMemoryProfileRegistry implements ProfileRegistry {
        private final Map<String, Profile> map = new ConcurrentHashMap<>();
        void register(String name, Profile p) { map.put(name, p); }
        @Override public Optional<Profile> find(String name) { return Optional.ofNullable(map.get(name)); }
        @Override public Set<String> names() { return Set.copyOf(map.keySet()); }
    }

    static class FakeSessionFactory implements SessionFactory {
        final List<Session> created = new CopyOnWriteArrayList<>();
        private final ProfileRegistry registry;
        FakeSessionFactory(ProfileRegistry registry) { this.registry = registry; }
        @Override public Session create(String profileName) { return create(profileName, null); }
        @Override public Session create(String profileName, String taskId) {
            if (profileName == null || profileName.isBlank()) {
                throw new IllegalArgumentException("profileName must not be blank");
            }
            registry.find(profileName).orElseThrow(() ->
                new IllegalArgumentException("Profile not registered: " + profileName));
            Map<String, Object> meta = new ConcurrentHashMap<>();
            meta.put("source", "scheduler");
            if (taskId != null) meta.put("task_id", taskId);
            meta.put("started_at", Instant.now().toString());
            Session s = new StubSession(UUID.randomUUID(), profileName, meta);
            created.add(s);
            return s;
        }
    }

    static class StubSession implements Session {
        private final UUID id;
        private final String profileName;
        private final Instant createdAt;
        private final Instant updatedAt;
        private final List<io.oryxos.core.Message> messages = List.of();
        private final Map<String, Object> meta;
        StubSession(UUID id, String profileName, Map<String, Object> meta) {
            this.id = id;
            this.profileName = profileName;
            Instant now = Instant.now();
            this.createdAt = now;
            this.updatedAt = now;
            this.meta = meta;
        }
        @Override public UUID id() { return id; }
        @Override public String profileName() { return profileName; }
        @Override public Instant createdAt() { return createdAt; }
        @Override public Instant updatedAt() { return updatedAt; }
        @Override public List<io.oryxos.core.Message> messages() { return messages; }
        @Override public void appendMessage(io.oryxos.core.Message m) {}
    }

    static class FakeScheduleStore implements ScheduleStore {
        final Map<String, ScheduleEntry> entries = new ConcurrentHashMap<>();
        @Override public int upsertAll(List<ScheduleEntry> schedules) {
            int n = 0;
            for (ScheduleEntry e : schedules) { entries.put(e.taskId(), e); n++; }
            return n;
        }
        @Override public List<ScheduleEntry> findAllEnabled() {
            return entries.values().stream().filter(ScheduleEntry::enabled).toList();
        }
        @Override public Optional<ScheduleEntry> findByTaskId(String taskId) {
            return Optional.ofNullable(entries.get(taskId));
        }
        @Override public void updateRunTimes(String taskId, Instant lastRunAtUtc, Instant nextRunAtUtc) {
            ScheduleEntry e = entries.get(taskId);
            if (e == null) return;
            entries.put(taskId, new ScheduleEntry(
                e.profileName(), e.id(), e.cron(), e.zone(),
                e.message(), e.enabled(), nextRunAtUtc, lastRunAtUtc));
        }
        @Override public void deleteByTaskId(String taskId) { entries.remove(taskId); }
    }

    static class FakeTaskExecutionRecorder implements TaskExecutionRecorder {
        final List<RecordedExec> records = new CopyOnWriteArrayList<>();
        @Override
        public String record(TaskExecutionRecorder.ExecutionContext ctx,
                             Instant startedAtUtc, long durationMs,
                             boolean success, String errorMessage) {
            String executionId = UUID.randomUUID().toString();
            records.add(new RecordedExec(executionId, ctx, startedAtUtc, durationMs,
                success, errorMessage, "scheduler"));
            return executionId;
        }
    }

    record RecordedExec(
        String executionId,
        TaskExecutionRecorder.ExecutionContext ctx,
        Instant startedAt,
        long durationMs,
        boolean success,
        String errorMessage,
        String triggerSource
    ) {}

    private static Profile makeProfile(String name) {
        return new Profile(
            name,
            new Provider("deepseek", "deepseek-chat", null, "TEST_CRED", Map.of()),
            List.of(), List.of(), List.of(), List.of(),
            Profile.Settings.defaults(),
            Map.of(),
            List.of()
        );
    }
}