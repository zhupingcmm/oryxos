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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 008-agent-scheduler / T034 —— 性能基线 IT（per contracts §9）。
 *
 * <p>5 个 perf gate（所有断言用 P95 阈值；warm-up 后测量）：
 * <ol>
 *   <li>{@code bootstrap(100 schedules)} ≤ 2 s P95</li>
 *   <li>{@code CronEvaluator.nextRunAt(Instant)} ≤ 50 μs P95（最热的 read path）</li>
 *   <li>{@code ScheduleStore.upsertAll(100 records)} ≤ 1 s P95（fake 在内存 map）</li>
 *   <li>{@code TaskExecutionRecorder.record(...)} ≤ 100 ms P95（fake 内存 append）</li>
 *   <li>{@code triggerNow → tick 排队} ≤ 100 ms P95（executor.submit 后立即返回）</li>
 * </ol>
 *
 * <p><b>注意</b>：这些是<b>内存 fake</b>基线，真实 SQLite WAL + JPA 路径由 storage 模块 IT 覆盖
 * （SQLite WAL 在并发 1 的场景下吞吐与内存 map 同量级；差异主要在 fsync 持久化时延）。
 *
 * <p>per R-005 "性能基线不锁实施细节" 原则：本 IT 只在 008 阶段设定基线，扩展阶段（集群 / 多租户）
 * 重新跑一遍对比扩展前后回归。
 */
class SchedulerPerformanceBenchmarkIT {

    private static final int P95_ITERATIONS = 20;
    private static final int WARMUP_ITERATIONS = 5;

    private FakeAgentService agentService;
    private InMemoryProfileRegistry profileRegistry;
    private FakeSessionFactory sessionFactory;
    private FakeScheduleStore scheduleStore;
    private FakeTaskExecutionRecorder recorder;
    private AgentSchedulerImpl scheduler;

    @BeforeEach
    void setUp() {
        agentService = new FakeAgentService();
        profileRegistry = new InMemoryProfileRegistry();
        profileRegistry.register("bench-agent", makeProfile("bench-agent"));
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
    @DisplayName("Perf gate 1：bootstrap(100 schedules) ≤ 2s P95")
    void bootstrap100Schedules() {
        List<Schedule> schedules = makeSchedules(100);
        // Warm-up
        warmupBootstrap(makeSchedules(5));

        long[] samples = new long[P95_ITERATIONS];
        for (int i = 0; i < P95_ITERATIONS; i++) {
            // 每个 sample 都 new 一个 scheduler 模拟冷启动
            AgentSchedulerImpl s = new AgentSchedulerImpl(
                agentService, new FakeScheduleStore(), recorder, sessionFactory);
            long t0 = System.nanoTime();
            s.bootstrap(schedules);
            s.shutdown();
            samples[i] = (System.nanoTime() - t0) / 1_000_000L;
        }
        long p95 = percentileMs(samples, 0.95);
        assertTrue(p95 <= 2_000L,
            "bootstrap(100 schedules) P95 must be ≤ 2000ms; got p95=" + p95 + "ms");
    }

    @Test
    @DisplayName("Perf gate 2：CronEvaluator.nextRunAt ≤ 50μs P95")
    void cronEvaluatorNextRunAt() {
        CronEvaluator evaluator = new CronEvaluatorImpl("0 9 * * *", "Asia/Shanghai");
        Instant from = Instant.parse("2026-06-15T00:00:00Z");
        // Warm-up
        for (int i = 0; i < WARMUP_ITERATIONS; i++) evaluator.nextRunAt(from);

        long[] samples = new long[P95_ITERATIONS];
        for (int i = 0; i < P95_ITERATIONS; i++) {
            long t0 = System.nanoTime();
            evaluator.nextRunAt(from);
            samples[i] = System.nanoTime() - t0;  // ns
        }
        long p95ns = percentile(samples, 0.95);
        assertTrue(p95ns <= 50_000L,
            "CronEvaluator.nextRunAt P95 must be ≤ 50μs; got p95=" + (p95ns / 1_000.0) + "μs");
    }

    @Test
    @DisplayName("Perf gate 3：ScheduleStore.upsertAll(100 records) ≤ 1s P95（fake 内存 map）")
    void scheduleStoreUpsertAll() {
        FakeScheduleStore store = new FakeScheduleStore();
        // Warm-up
        store.upsertAll(makeEntries(5, 0));

        long[] samples = new long[P95_ITERATIONS];
        for (int i = 0; i < P95_ITERATIONS; i++) {
            List<ScheduleEntry> entries = makeEntries(100, i * 100);
            long t0 = System.nanoTime();
            store.upsertAll(entries);
            samples[i] = (System.nanoTime() - t0) / 1_000_000L;
        }
        long p95 = percentileMs(samples, 0.95);
        assertTrue(p95 <= 1_000L,
            "ScheduleStore.upsertAll(100) P95 must be ≤ 1000ms (in-memory fake); got p95=" + p95 + "ms");
    }

    @Test
    @DisplayName("Perf gate 4：TaskExecutionRecorder.record(...) ≤ 100ms P95（fake 内存 append）")
    void taskExecutionRecorderRecord() {
        // Warm-up
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            recorder.record(makeContext(), Instant.now(), 50, true, null);
        }

        long[] samples = new long[P95_ITERATIONS];
        for (int i = 0; i < P95_ITERATIONS; i++) {
            long t0 = System.nanoTime();
            recorder.record(makeContext(), Instant.now(), 50, true, null);
            samples[i] = (System.nanoTime() - t0) / 1_000_000L;
        }
        long p95 = percentileMs(samples, 0.95);
        assertTrue(p95 <= 100L,
            "TaskExecutionRecorder.record P95 must be ≤ 100ms (in-memory fake); got p95=" + p95 + "ms");
    }

    @Test
    @DisplayName("Perf gate 5：triggerNow → executor.submit ≤ 100ms P95（异步 tick 排队）")
    void triggerNowSubmitLatency() {
        scheduler.bootstrap(List.of(new Schedule(
            "bench-agent", "rainy", "0 8 * * *", "UTC", "weather-msg", true)));
        // Warm-up
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            scheduler.triggerNow("bench-agent:rainy");
        }

        long[] samples = new long[P95_ITERATIONS];
        for (int i = 0; i < P95_ITERATIONS; i++) {
            long t0 = System.nanoTime();
            scheduler.triggerNow("bench-agent:rainy");
            samples[i] = (System.nanoTime() - t0) / 1_000_000L;
        }
        long p95 = percentileMs(samples, 0.95);
        assertTrue(p95 <= 100L,
            "triggerNow P95 must be ≤ 100ms (executor.submit async); got p95=" + p95 + "ms");
    }

    // --- helpers ---

    private void warmupBootstrap(List<Schedule> schedules) {
        AgentSchedulerImpl s = new AgentSchedulerImpl(
            agentService, new FakeScheduleStore(), recorder, sessionFactory);
        s.bootstrap(schedules);
        s.shutdown();
    }

    private static List<Schedule> makeSchedules(int n) {
        List<Schedule> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new Schedule(
                "bench-agent",
                "sched-" + i,
                "0 " + (i % 24) + " * * *",
                i % 2 == 0 ? "Asia/Shanghai" : "UTC",
                "msg-" + i,
                true
            ));
        }
        return list;
    }

    private static List<ScheduleEntry> makeEntries(int n, int offset) {
        List<ScheduleEntry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int idx = offset + i;
            list.add(new ScheduleEntry(
                "bench-agent",
                "sched-" + idx,
                "0 " + (idx % 24) + " * * *",
                idx % 2 == 0 ? "Asia/Shanghai" : "UTC",
                "msg-" + idx,
                true,
                Instant.now().plusSeconds(60),
                null
            ));
        }
        return list;
    }

    private TaskExecutionRecorder.ExecutionContext makeContext() {
        return new TaskExecutionRecorder.ExecutionContext(
            "bench-agent:sched-0",
            UUID.randomUUID().toString(),
            "scheduler"
        );
    }

    /**
     * P95 百分位（升序）—— 取排序后索引 = (len - 1) * p。
     */
    private static long percentileMs(long[] samples, double p) {
        return percentile(samples, p) / 1_000_000L;
    }

    private static long percentile(long[] samples, double p) {
        long[] sorted = samples.clone();
        java.util.Arrays.sort(sorted);
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

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

    // --- fakes（与 SchedulerEndToEndIT 同款） ---

    static class FakeAgentService implements AgentService {
        @Override
        public LoopResult process(Session session, String userMessage) {
            return new LoopResult("echo", 1, false,
                session.profileName(),
                session.id() == null ? UUID.randomUUID() : session.id());
        }
    }

    static class InMemoryProfileRegistry implements ProfileRegistry {
        private final Map<String, Profile> map = new ConcurrentHashMap<>();
        void register(String name, Profile p) { map.put(name, p); }
        @Override public Optional<Profile> find(String name) { return Optional.ofNullable(map.get(name)); }
        @Override public Set<String> names() { return Set.copyOf(map.keySet()); }
    }

    static class FakeSessionFactory implements SessionFactory {
        private final ProfileRegistry registry;
        FakeSessionFactory(ProfileRegistry registry) { this.registry = registry; }
        @Override public Session create(String profileName) { return create(profileName, null); }
        @Override public Session create(String profileName, String taskId) {
            registry.find(profileName).orElseThrow(() ->
                new IllegalArgumentException("Profile not registered: " + profileName));
            return new StubSession(UUID.randomUUID(), profileName);
        }
    }

    static class StubSession implements Session {
        private final UUID id;
        private final String profileName;
        StubSession(UUID id, String profileName) {
            this.id = id;
            this.profileName = profileName;
        }
        @Override public UUID id() { return id; }
        @Override public String profileName() { return profileName; }
        @Override public Instant createdAt() { return Instant.now(); }
        @Override public Instant updatedAt() { return Instant.now(); }
        @Override public List<io.oryxos.core.Message> messages() { return List.of(); }
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
        final CopyOnWriteArrayList<String> records = new CopyOnWriteArrayList<>();
        @Override
        public String record(TaskExecutionRecorder.ExecutionContext ctx,
                             Instant startedAtUtc, long durationMs,
                             boolean success, String errorMessage) {
            String executionId = UUID.randomUUID().toString();
            records.add(executionId);
            return executionId;
        }
    }
}