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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 008-agent-scheduler US-3 / FR-006 —— 并发去重 IT。
 *
 * <p>关键认知：{@link AgentSchedulerImpl} 内部用
 * {@link java.util.concurrent.ScheduledExecutorService} 单线程池，已天然把 tick 任务序列化
 * —— 任务 2 必须在任务 1 完成（即 executor 线程空闲）后才能跑。所以"10 次并发 triggerNow"
 * 场景下，{@code AtomicBoolean} dedup 实际不会触发（任务 2 进队列时任务 1 已释放 runningNow）。
 *
 * <p>本 IT 用<b>跨线程直接调用 {@code tick(taskId)}</b>（包级可见）来真正制造并发去重场景：
 * <ol>
 *   <li>线程 A 调 {@code tick} → 抢占成功 → 进入 AgentService 慢调用（latch.await 30s）</li>
 *   <li>线程 B..K 几乎同时调 {@code tick} → 抢占失败 → 跳过</li>
 *   <li>断言：AgentService.process() 实际只调 1 次</li>
 * </ol>
 *
 * <p>这也覆盖了 spec FR-006 的真正防御场景 —— cron tick 与 {@code triggerNow}
 * 同时落到同一 task 的边界（虽然调度器单线程池，{@code triggerNow} 提交到同一池会自然排队，
 * 但 AtomicBoolean 是<b>最后一道</b>防线）。
 */
class SchedulerConcurrencyDedupIT {

    private HoldingAgentService agentService;
    private InMemoryProfileRegistry profileRegistry;
    private FakeSessionFactory sessionFactory;
    private FakeScheduleStore scheduleStore;
    private FakeTaskExecutionRecorder recorder;
    private AgentSchedulerImpl scheduler;

    @BeforeEach
    void setUp() {
        agentService = new HoldingAgentService();
        profileRegistry = new InMemoryProfileRegistry();
        profileRegistry.register("dedup-agent", makeProfile("dedup-agent"));
        sessionFactory = new FakeSessionFactory(profileRegistry);
        scheduleStore = new FakeScheduleStore();
        recorder = new FakeTaskExecutionRecorder();
        scheduler = new AgentSchedulerImpl(
            agentService, scheduleStore, recorder, sessionFactory);
    }

    @AfterEach
    void tearDown() {
        // 释放任何还在 wait 的 AgentService
        if (agentService.holdLatch != null) {
            agentService.holdLatch.countDown();
        }
        if (scheduler != null && scheduler.isRunning()) {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName("FR-006：N 线程同时调 tick() → AtomicBoolean dedup → 恰好 1 次 AgentService.process()")
    void parallelTicksDedupeToOneCall() throws Exception {
        scheduler.bootstrap(List.of(new Schedule(
            "dedup-agent", "rainy", "0 8 * * *", "UTC", "weather-msg", true)));
        agentService.holdLatch = new CountDownLatch(1);

        // 用独立线程池调 tick(taskId) —— 制造真正并发
        int parallelThreads = 10;
        ExecutorService tickers = Executors.newFixedThreadPool(parallelThreads);
        CountDownLatch ready = new CountDownLatch(parallelThreads);
        CountDownLatch fire = new CountDownLatch(1);
        List<Future<?>> futures = new CopyOnWriteArrayList<>();
        for (int i = 0; i < parallelThreads; i++) {
            futures.add(tickers.submit(() -> {
                ready.countDown();
                try {
                    fire.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                // 直接调包级可见的 tick(taskId) —— 绕过 executor 单线程限制
                scheduler.tick("dedup-agent:rainy");
            }));
        }
        // 等所有线程就位
        assertTrue(ready.await(5, TimeUnit.SECONDS), "10 个 ticker 线程应就位");
        // 点火
        fire.countDown();

        // 等第 1 个 tick 进入 AgentService（≤5s）
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && agentService.inFlight.get() == 0) {
            Thread.sleep(50);
        }
        assertEquals(1, agentService.inFlight.get(),
            "至少应有 1 次 process 进入（在持锁状态）");

        // 释放 latch，让第 1 个 tick 走完
        agentService.holdLatch.countDown();

        // 等所有 ticker 线程结束（≤30s）
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        tickers.shutdown();
        assertTrue(tickers.awaitTermination(5, TimeUnit.SECONDS));

        // FR-006 字节级断言：去重后实际只调 1 次
        assertEquals(1, agentService.processCallCount.get(),
            "FR-006: 10 个并发 tick 应去重为恰好 1 次 AgentService.process() 调用; "
                + "actual calls=" + agentService.processCallCount.get());
    }

    @Test
    @DisplayName("FR-006：第 2 次 tick 在第 1 次 in-flight 时被跳过 → processCallCount 保持 1")
    void secondTickSkippedWhileFirstInFlight() throws Exception {
        scheduler.bootstrap(List.of(new Schedule(
            "dedup-agent", "rainy", "0 8 * * *", "UTC", "weather-msg", true)));
        agentService.holdLatch = new CountDownLatch(1);

        // 第 1 次 tick（独立线程，因 tick() 会在 process() 处阻塞）→ 进入 in-flight
        Thread t1 = new Thread(() -> scheduler.tick("dedup-agent:rainy"));
        t1.start();
        // 等第 1 次进入 in-flight
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && agentService.inFlight.get() == 0) {
            Thread.sleep(50);
        }
        assertEquals(1, agentService.inFlight.get(), "第 1 次 tick 应进入 in-flight");

        // 第 2 次 tick（独立线程）→ 应被 AtomicBoolean 跳过（快速返回）
        Thread t2 = new Thread(() -> scheduler.tick("dedup-agent:rainy"));
        t2.start();
        t2.join(5_000);
        assertTrue(!t2.isAlive(), "第 2 次 tick 应快速返回（dedup 跳过）");

        // 关键断言：第 2 次 tick 没有进入 AgentService
        assertEquals(1, agentService.processCallCount.get(),
            "FR-006: 第 2 次 tick 在第 1 次 in-flight 时应跳过 → 0 次额外 process(); "
                + "actual=" + agentService.processCallCount.get());

        // 释放 + 收尾
        agentService.holdLatch.countDown();
        t1.join(5_000);
    }

    // --- fakes ---

    /** AgentService 替身 —— 可配置 latch.await 卡住，让 caller 等。 */
    static class HoldingAgentService implements AgentService {
        CountDownLatch holdLatch;
        final AtomicInteger processCallCount = new AtomicInteger(0);
        final AtomicInteger inFlight = new AtomicInteger(0);
        @Override
        public LoopResult process(Session session, String userMessage) {
            processCallCount.incrementAndGet();
            inFlight.incrementAndGet();
            try {
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
                inFlight.decrementAndGet();
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
        StubSession(UUID id, String profileName, Map<String, Object> meta) {
            this.id = id;
            this.profileName = profileName;
            Instant now = Instant.now();
            this.createdAt = now;
            this.updatedAt = now;
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