package io.oryxos.core.scheduler;

import io.oryxos.core.AgentService;
import io.oryxos.core.LoopResult;
import io.oryxos.core.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 008-agent-scheduler 阶段 US-1 —— AgentSchedulerImpl 单测（mock 依赖）。
 *
 * <h2>US-1 验收场景</h2>
 * <ul>
 *   <li>AS-1：cron 表达式触发 → tick 走 AgentService.process(message) 同源</li>
 *   <li>AS-2：bootstrap 加载 schedules → listSchedules 返回（按 task_id 升序）</li>
 *   <li>AS-3：triggerNow 手动补跑 → 同源 tick 路径</li>
 * </ul>
 *
 * <p>本测试为 mock-based，不依赖 Spring 容器或 cron 实际触发延迟。
 * 真实调度延迟验证见 SchedulerEndToEndIT（Phase 4）。
 *
 * <p>不用 awaitility —— 用一个内置 pollUntil() 轮询断言（避免新增 test 依赖）。
 */
class AgentSchedulerTest {

    /** 简单的轮询断言：until 谓词为 true 或超时抛 AssertionError。 */
    static void pollUntil(java.time.Duration timeout, java.util.function.BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(20); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted");
            }
        }
        throw new AssertionError("pollUntil timeout after " + timeout);
    }

    /** Fake AgentService —— 记录 process 调用，可配置抛异常。 */
    static class FakeAgentService implements AgentService {
        final List<String> messagesReceived = new CopyOnWriteArrayList<>();
        final AtomicReference<RuntimeException> toThrow = new AtomicReference<>();

        @Override
        public LoopResult process(Session session, String userMessage) {
            if (toThrow.get() != null) {
                throw toThrow.get();
            }
            messagesReceived.add(userMessage);
            return new LoopResult(
                "echo: " + userMessage,
                1,
                false,
                session.profileName(),
                session.id() == null ? UUID.randomUUID() : session.id()
            );
        }
    }

    /** Fake SessionFactory —— 不真正持久化，构造 in-memory Session。 */
    static class FakeSessionFactory implements SessionFactory {
        @Override
        public Session create(String profileName) {
            return new FakeSession(UUID.randomUUID(), profileName);
        }
    }

    /** Fake Session —— 不走 JPA，纯内存。 */
    record FakeSession(UUID id, String profileName) implements Session {
        @Override public java.util.List<io.oryxos.core.Message> messages() { return List.of(); }
        @Override public void appendMessage(io.oryxos.core.Message m) {}
        @Override public Instant createdAt() { return Instant.now(); }
        @Override public Instant updatedAt() { return Instant.now(); }
    }

    /** Fake ScheduleStore —— 不写 DB，纯内存 map。 */
    static class FakeScheduleStore implements ScheduleStore {
        final Map<String, ScheduleEntry> data = new ConcurrentHashMap<>();

        @Override public int upsertAll(List<ScheduleEntry> schedules) {
            schedules.forEach(s -> data.put(s.taskId(), s));
            return schedules.size();
        }
        @Override public List<ScheduleEntry> findAllEnabled() {
            List<ScheduleEntry> out = new ArrayList<>();
            data.values().forEach(e -> { if (e.enabled()) out.add(e); });
            return out;
        }
        @Override public java.util.Optional<ScheduleEntry> findByTaskId(String taskId) {
            return java.util.Optional.ofNullable(data.get(taskId));
        }
        @Override public void updateRunTimes(String taskId, Instant last, Instant next) {
            ScheduleEntry prev = data.get(taskId);
            if (prev != null) {
                data.put(taskId, new ScheduleEntry(
                    prev.profileName(), prev.id(), prev.cron(), prev.zone(),
                    prev.message(), prev.enabled(), next, last));
            }
        }
        @Override public void deleteByTaskId(String taskId) {
            data.remove(taskId);
        }
    }

    /** Fake TaskExecutionRecorder —— 不写 DB，统计调用次数。 */
    static class FakeTaskExecutionRecorder implements TaskExecutionRecorder {
        final List<ExecutionContext> contexts = new CopyOnWriteArrayList<>();
        final List<Boolean> successes = new CopyOnWriteArrayList<>();

        @Override
        public String record(ExecutionContext ctx, Instant startedAtUtc,
                             long durationMs, boolean success, String errorMessage) {
            contexts.add(ctx);
            successes.add(success);
            return UUID.randomUUID().toString();
        }
    }

    FakeAgentService agentService;
    FakeScheduleStore store;
    FakeTaskExecutionRecorder recorder;
    FakeSessionFactory sessionFactory;
    AgentSchedulerImpl scheduler;

    @BeforeEach
    void setUp() {
        agentService = new FakeAgentService();
        store = new FakeScheduleStore();
        recorder = new FakeTaskExecutionRecorder();
        sessionFactory = new FakeSessionFactory();
        scheduler = new AgentSchedulerImpl(agentService, store, recorder, sessionFactory);
    }

    @AfterEach
    void tearDown() {
        if (scheduler.isRunning()) {
            scheduler.shutdown();
        }
    }

    private static Schedule mkSchedule(String profile, String id, String cron, String msg, boolean enabled) {
        return new Schedule(profile, id, cron, "UTC", msg, enabled);
    }

    @Test
    @DisplayName("bootstrap 注册 schedules + listSchedules 按 task_id 升序")
    void bootstrapAndListSchedules() {
        List<Schedule> schedules = List.of(
            mkSchedule("p1", "b", "0 8 * * *", "msg-b", true),
            mkSchedule("p1", "a", "0 9 * * *", "msg-a", true),
            mkSchedule("p2", "x", "0 10 * * *", "msg-x", false)
        );
        scheduler.bootstrap(schedules);

        assertTrue(scheduler.isRunning());
        List<AgentScheduler.ScheduleView> views = scheduler.listSchedules();
        assertEquals(3, views.size());
        // 按 task_id 升序 → "p1:a" < "p1:b" < "p2:x"
        assertEquals("p1:a", views.get(0).taskId());
        assertEquals("p1:b", views.get(1).taskId());
        assertEquals("p2:x", views.get(2).taskId());
        assertFalse(views.get(2).enabled());  // disabled 也在列表中

        // DB 持久化
        assertEquals(3, store.data.size());
    }

    @Test
    @DisplayName("triggerNow 立即触发 tick → AgentService.process 收到 schedule.message")
    void triggerNowRunsTick() {
        Schedule s = mkSchedule("p1", "a", "0 8 * * *", "今天天气怎么样？", true);
        scheduler.bootstrap(List.of(s));

        scheduler.triggerNow("p1:a");

        pollUntil(Duration.ofSeconds(3), () -> agentService.messagesReceived.size() == 1);
        assertEquals("今天天气怎么样？", agentService.messagesReceived.get(0));
        // 触发完成后 task_execution 写一行
        pollUntil(Duration.ofSeconds(2), () -> recorder.contexts.size() == 1);
        assertEquals("p1:a", recorder.contexts.get(0).taskId());
        assertEquals("scheduler", recorder.contexts.get(0).triggerSource());
        assertTrue(recorder.successes.get(0));
    }

    @Test
    @DisplayName("bootstrap 拒绝重复 task_id → 抛 IllegalStateException (FR-012)")
    void bootstrapRejectsDuplicateTaskId() {
        Schedule a = mkSchedule("p1", "x", "0 8 * * *", "a", true);
        Schedule b = mkSchedule("p1", "x", "0 9 * * *", "b", true);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> scheduler.bootstrap(List.of(a, b)));
        assertTrue(ex.getMessage().contains("duplicate task_id"));
        assertFalse(scheduler.isRunning());  // bootstrap 失败 → state 干净
    }

    @Test
    @DisplayName("bootstrap 拒绝非法 cron → 抛 IllegalArgumentException (FR-011)")
    void bootstrapRejectsInvalidCron() {
        Schedule bad = mkSchedule("p1", "x", "INVALID CRON", "msg", true);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> scheduler.bootstrap(List.of(bad)));
        assertTrue(ex.getMessage().contains("invalid cron"));
        assertFalse(scheduler.isRunning());
    }

    @Test
    @DisplayName("triggerNow 未知 taskId → IllegalArgumentException")
    void triggerNowUnknownTaskId() {
        scheduler.bootstrap(List.of(mkSchedule("p1", "a", "0 8 * * *", "msg", true)));
        assertThrows(IllegalArgumentException.class, () -> scheduler.triggerNow("unknown"));
    }

    @Test
    @DisplayName("shutdown 后 isRunning = false + triggerNow 抛 IllegalStateException")
    void shutdownDisables() {
        scheduler.bootstrap(List.of(mkSchedule("p1", "a", "0 8 * * *", "msg", true)));
        assertTrue(scheduler.isRunning());
        scheduler.shutdown();
        assertFalse(scheduler.isRunning());
        assertThrows(IllegalStateException.class, () -> scheduler.triggerNow("p1:a"));
    }

    @Test
    @DisplayName("bootstrap 拒绝非法 timezone → 抛 IllegalArgumentException (FR-009)")
    void bootstrapRejectsInvalidTimezone() {
        Schedule bad = mkSchedule("p1", "x", "0 8 * * *", "msg", true);
        Schedule withZone = new Schedule(bad.profileName(), bad.id(), bad.cron(),
            "Not/A_Real_Zone", bad.message(), bad.enabled());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> scheduler.bootstrap(List.of(withZone)));
        assertTrue(ex.getMessage().contains("invalid IANA timezone")
                || ex.getMessage().toLowerCase().contains("timezone"));
        assertFalse(scheduler.isRunning());
    }
}