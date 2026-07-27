package io.oryxos.core.scheduler;

import io.oryxos.core.AgentService;
import io.oryxos.core.LoopResult;
import io.oryxos.core.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 008-agent-scheduler 阶段 —— {@link AgentScheduler} 的 JDK 21 单线程实现。
 *
 * <p>使用 {@link ScheduledExecutorService} 配单线程（spec FR-007）；
 * tick 处理在主线程串行化执行（计划任务的"同一时刻触发"走同线程顺序执行）。
 *
 * <h2>tick 处理流程</h2>
 * <pre>
 * tick(taskId) {
 *   1. 找到注册的 Schedule + CronEvaluator
 *   2. 创建新 Session（triggerSource="scheduler"）
 *   3. start = now
 *   4. try: agentService.process(session, schedule.message()) → 写 task_execution success=true
 *      catch: 写 task_execution success=false + sanitize
 *   5. 计算 nextRunAt = cronEvaluator.nextRunAt(now)
 *   6. scheduleStore.updateRunTimes(taskId, start, nextRunAt)
 *   7. self-reschedule = executor.schedule(() -> tick(taskId), delay, MILLISECONDS)
 * }
 * </pre>
 *
 * <h2>线程安全</h2>
 * <p>{@code registeredSchedules} / {@code cronEvaluators} / {@code runningFutures} 用
 * {@link ConcurrentHashMap}；{@code executor} 用 {@link AtomicReference}，保证
 * bootstrap / shutdown / triggerNow 三方对执行器的可见性一致。
 */
public class AgentSchedulerImpl implements AgentScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentSchedulerImpl.class);

    private final AgentService agentService;
    private final ScheduleStore scheduleStore;
    private final TaskExecutionRecorder taskExecutionRecorder;
    private final SessionFactory sessionFactory;

    private final AtomicReference<ScheduledExecutorService> executor =
        new AtomicReference<>();

    private final Map<String, ScheduleEntry> registeredSchedules = new ConcurrentHashMap<>();
    private final Map<String, CronEvaluator> cronEvaluators = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> runningFutures = new ConcurrentHashMap<>();

    public AgentSchedulerImpl(
        AgentService agentService,
        ScheduleStore scheduleStore,
        TaskExecutionRecorder taskExecutionRecorder,
        SessionFactory sessionFactory
    ) {
        this.agentService = agentService;
        this.scheduleStore = scheduleStore;
        this.taskExecutionRecorder = taskExecutionRecorder;
        this.sessionFactory = sessionFactory;
    }

    @Override
    public void bootstrap(List<Schedule> schedules) {
        if (schedules == null || schedules.isEmpty()) {
            log.warn("AgentSchedulerImpl.bootstrap: empty schedule list, scheduler will idle");
            return;
        }
        ScheduledExecutorService newExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oryxos-scheduler-tick");
            t.setDaemon(true);
            return t;
        });
        if (!executor.compareAndSet(null, newExecutor)) {
            newExecutor.shutdownNow();
            throw new IllegalStateException(
                "AgentSchedulerImpl already bootstrapped (executor not null)");
        }

        try {
            // 1. 校验 + 注册（fail-closed：任一非法 → 整批拒绝）
            for (Schedule s : schedules) {
                String taskId = s.taskId();
                if (registeredSchedules.containsKey(taskId)) {
                    throw new IllegalStateException(
                        "duplicate task_id: " + taskId + " (FR-012)");
                }
                CronEvaluator evaluator = new CronEvaluatorImpl(s.cron(), s.zone());
                Instant now = Instant.now();
                Instant nextRun = evaluator.nextRunAt(now);
                ScheduleEntry entry = new ScheduleEntry(
                    s.profileName(),
                    s.id(),
                    s.cron(),
                    s.zone(),
                    s.message(),
                    s.enabled(),
                    nextRun,
                    null
                );
                registeredSchedules.put(taskId, entry);
                cronEvaluators.put(taskId, evaluator);
                scheduleStore.upsertAll(List.of(entry));
                log.info("AgentSchedulerImpl.bootstrap: registered taskId={} cron='{}' zone='{}' nextRunAtUtc={}",
                    taskId, s.cron(), s.zone(), nextRun);
            }

            // 2. 为每个 enabled=true 注册 tick future
            for (Schedule s : schedules) {
                if (!s.enabled()) {
                    log.info("AgentSchedulerImpl.bootstrap: skip disabled taskId={}", s.taskId());
                    continue;
                }
                Instant nextRun = cronEvaluators.get(s.taskId()).nextRunAt(Instant.now());
                long delayMs = Math.max(0L, Duration.between(Instant.now(), nextRun).toMillis());
                Future<?> f = executor.get().schedule(
                    () -> tick(s.taskId()),
                    delayMs,
                    TimeUnit.MILLISECONDS
                );
                runningFutures.put(s.taskId(), f);
            }
        } catch (RuntimeException e) {
            newExecutor.shutdownNow();
            executor.set(null);
            registeredSchedules.clear();
            cronEvaluators.clear();
            runningFutures.clear();
            throw e;
        }
    }

    @Override
    public void shutdown() {
        ScheduledExecutorService ex = executor.getAndSet(null);
        if (ex == null) {
            return;
        }
        for (Future<?> f : runningFutures.values()) {
            f.cancel(false);
        }
        runningFutures.clear();
        registeredSchedules.clear();
        cronEvaluators.clear();
        try {
            ex.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ex.shutdownNow();
        log.info("AgentSchedulerImpl.shutdown: done");
    }

    @Override
    public List<ScheduleView> listSchedules() {
        List<ScheduleView> result = new ArrayList<>(registeredSchedules.size());
        for (Map.Entry<String, ScheduleEntry> e : registeredSchedules.entrySet()) {
            String taskId = e.getKey();
            ScheduleEntry entry = e.getValue();
            Optional<ScheduleEntry> persisted = scheduleStore.findByTaskId(taskId);
            Instant nextRun = persisted.map(ScheduleEntry::nextRunAtUtc).orElse(entry.nextRunAtUtc());
            Instant lastRun = persisted.map(ScheduleEntry::lastRunAtUtc).orElse(null);
            result.add(new ScheduleView(
                taskId,
                entry.profileName(),
                entry.cron(),
                entry.zone(),
                entry.message(),
                entry.enabled(),
                nextRun,
                lastRun
            ));
        }
        result.sort(Comparator.comparing(ScheduleView::taskId));
        return result;
    }

    @Override
    public void triggerNow(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (!isRunning()) {
            throw new IllegalStateException("scheduler not running (call bootstrap first)");
        }
        if (!registeredSchedules.containsKey(taskId)) {
            throw new IllegalArgumentException("unknown taskId: " + taskId);
        }
        executor.get().submit(() -> tick(taskId));
    }

    @Override
    public boolean isRunning() {
        return executor.get() != null;
    }

    // --- tick 处理（核心循环，由 ScheduledExecutorService 调度） ---

    void tick(String taskId) {
        ScheduleEntry entry = registeredSchedules.get(taskId);
        CronEvaluator evaluator = cronEvaluators.get(taskId);
        if (entry == null || evaluator == null) {
            log.warn("AgentSchedulerImpl.tick: taskId={} not found (race with shutdown?)", taskId);
            return;
        }
        Instant start = Instant.now();
        String sessionId = null;
        boolean success = false;
        String errorMessage = null;
        try {
            // US-2 spec FR-001：sessions.metadata.task_id 必须是真实 <profileName>:<scheduleId>
            Session session = sessionFactory.create(entry.profileName(), taskId);
            sessionId = session.id() == null ? null : session.id().toString();
            LoopResult result = agentService.process(session, entry.message());
            success = result != null;
        } catch (RuntimeException e) {
            success = false;
            // C-TER-2 字节级契约：error_message 不含 stack trace
            // 1) 取根因 message；2) 截断到首个 \n；3) 截到 2KB
            errorMessage = sanitizeError(e);
            log.error("AgentSchedulerImpl.tick: taskId={} failed: {}", taskId, errorMessage);
        } catch (Throwable t) {
            success = false;
            errorMessage = sanitizeError(t);
            log.error("AgentSchedulerImpl.tick: taskId={} fatal", taskId, t);
        } finally {
            if (sessionId != null) {
                try {
                    taskExecutionRecorder.record(
                        new TaskExecutionRecorder.ExecutionContext(
                            taskId, sessionId, "scheduler"),
                        start,
                        Duration.between(start, Instant.now()).toMillis(),
                        success,
                        errorMessage
                    );
                } catch (RuntimeException e) {
                    log.warn("AgentSchedulerImpl.tick: recorder failed for taskId={}: {}",
                        taskId, e.toString());
                }
            }
            try {
                Instant next = evaluator.nextRunAt(Instant.now());
                scheduleStore.updateRunTimes(taskId, start, next);
                long delayMs = Math.max(0L, Duration.between(Instant.now(), next).toMillis());
                Future<?> f = executor.get().schedule(
                    () -> tick(taskId),
                    delayMs,
                    TimeUnit.MILLISECONDS
                );
                runningFutures.put(taskId, f);
            } catch (RuntimeException e) {
                log.error("AgentSchedulerImpl.tick: reschedule failed for taskId={}: {}",
                    taskId, e.toString());
            }
        }
    }

    // --- helpers ---

    /**
     * C-TER-2 字节级契约：error_message 不含 stack trace。
     *
     * <p>实现策略：
     * <ol>
     *   <li>取根因 message（{@code t.getMessage()} —— 当 exception 的 message 含 \n 时
     *       {@code getMessage()} 仍返回原始 message；问题在 message 本身被嵌入 stack trace）</li>
     *   <li>裁到首个 {@code \n}（stack trace 必以 {@code \n\tat} 开头）</li>
     *   <li>截断到 2KB（per data-model.md 实体 3 上限）</li>
     *   <li>覆盖 Caused by: → 保留根因 message 即可</li>
     * </ol>
     */
    private static String sanitizeError(Throwable t) {
        String raw = t.getMessage();
        if (raw == null || raw.isEmpty()) {
            return t.getClass().getSimpleName();
        }
        // 1) 取首个 \n 之前的内容（砍掉 stack trace 段）
        int nl = raw.indexOf('\n');
        String head = nl >= 0 ? raw.substring(0, nl) : raw;
        // 2) 截断到 2KB（per data-model.md 实体 3 上限 2048B）
        final int MAX = 2048;
        if (head.length() > MAX) {
            head = head.substring(0, MAX) + "...<truncated>";
        }
        return head;
    }
}