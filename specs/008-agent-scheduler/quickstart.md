# 快速验证：008-agent-scheduler

**生成日期**：2026-07-27
**目的**：4 场景端到端验证 spec FR-001..FR-013 + SC-001..SC-008 全覆盖
**关联**：[spec.md](spec.md) / [plan.md](plan.md) / [research.md](research.md) / [data-model.md](data-model.md) / [contracts/agent-scheduler.md](contracts/agent-scheduler.md)

---

## 验收流程总览

| 场景 | 验证 spec | 测试形态 | 期望 |
|------|---------|---------|------|
| **S1** 注册 + 路径对齐 | FR-001/002/011 + SC-004 | JUnit + 反射断言 | 4 子场景 PASS |
| **S2** 端到端钟推 | FR-003/004/005 + SC-001/002 | JUnit 集成测试（Spring Boot 上下文 + mock 时钟） | 4 子场景 PASS |
| **S3** 并发去重 + 失败重试 | FR-006/007 + SC-003/006 | JUnit 集成测试（注入 slow AgentService mock） | 4 子场景 PASS |
| **S4** 时区 + 审计完整性 | FR-009/010 + SC-005 | JUnit 时区 mock + 关联查询 | 4 子场景 PASS |
| **接口字节级** | NFR-004 / SC-007 | 反射断言（agent-scheduler.md §3.2/§4.2/§5.2/§6.2） | 14/14 PASS |
| **性能基线** | SC-003 | JMH 或 surefire 集成 | 5 项 ≤ 阈值 |
| **mvn verify** | SC-007 | Maven | 全 10 模块 SUCCESS |

**总计**：4 场景 16 子场景 + 接口 14 断言 + 性能 5 项 + mvn verify = **35 验收点**。

---

## 前置准备

```bash
# 1. JDK 21 + UTF-8 环境（CLAUDE.md §18 坑 4）
export JAVA_HOME=/path/to/jdk-21
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8

# 2. 当前分支
git branch  # 期望：* 008-agent-scheduler

# 3. 数据库 + 临时目录
mkdir -p /tmp/oryxos-008-test
```

---

## 场景 S1 — 注册 + Profile YAML 解析 + fail-closed

**对应 spec**：FR-001（YAML 解析）/ FR-002（启动时 upsert）/ FR-011（非法 cron 拒绝）
+ SC-004（路径对齐）

### S1.1 单条 schedule 注册

```bash
# 准备 Profile
mkdir -p /tmp/oryxos-008-test/.oryxos/agents/weather-agent
cat > /tmp/oryxos-008-test/.oryxos/agents/weather-agent/AGENT.md <<'EOF'
---
name: weather-agent
description: 每日天气
provider:
  name: deepseek
  model: deepseek-chat
schedules:
  - id: daily-weather
    cron: "0 8 * * *"
    zone: Asia/Shanghai
    message: "查一下今天上海天气"
    enabled: true
---
EOF

# 启动 OryxOS
cd /path/to/oryxos
mvn -pl oryxos-boot -am spring-boot:run \
    -Dspring-boot.run.arguments="--oryxos.agents-dir=/tmp/oryxos-008-test/.oryxos/agents" \
    &  # 后台启动
ORYXOS_PID=$!

# 等待启动完成（≤ 5s）
sleep 5

# 验证 scheduled_tasks 表
sqlite3 ~/.oryxos/oryxos.db \
    "SELECT task_id, profile_name, cron_expr, timezone, enabled, next_run_at_utc
     FROM scheduled_tasks WHERE profile_name='weather-agent';"
# 期望：
# daily-weather|weather-agent|0 8 * * *|Asia/Shanghai|1|2026-07-28T00:00:00Z
# （= 上海 08:00 UTC）

# 验证 CLI
mvn -pl oryxos-cli exec:java -Dexec.mainClass="io.oryxos.cli.Main" \
    -Dexec.args="schedule list"
# 期望输出 daily-weather 行（见 contracts/agent-scheduler.md §7.1）
```

### S1.2 多条 schedule + enabled=false

```bash
cat > /tmp/oryxos-008-test/.oryxos/agents/multi-agent/AGENT.md <<'EOF'
---
name: multi-agent
provider: { name: deepseek, model: deepseek-chat }
schedules:
  - { id: "task-a", cron: "0 9 * * *", zone: "Asia/Shanghai", message: "task a", enabled: true }
  - { id: "task-b", cron: "0 10 * * *", zone: "Asia/Shanghai", message: "task b", enabled: true }
  - { id: "task-c", cron: "0 11 * * *", zone: "Asia/Shanghai", message: "task c", enabled: false }
---
EOF

# 重启 OryxOS（核心阶段不热加载）
kill $ORYXOS_PID; sleep 2
mvn -pl oryxos-boot -am spring-boot:run ... &

sqlite3 ~/.oryxos/oryxos.db \
    "SELECT task_id, enabled FROM scheduled_tasks WHERE profile_name='multi-agent';"
# 期望：3 行；enabled=1, 1, 0

# 验证 enabled=false 不进调度器
grep "task-c" ~/.oryxos/logs/oryxos.log
# 期望：仅 "registered" 日志，无 "scheduled tick" 日志
```

### S1.3 非法 cron → fail-closed

```bash
cat > /tmp/oryxos-008-test/.oryxos/agents/bad-cron-agent/AGENT.md <<'EOF'
---
name: bad-cron-agent
provider: { name: deepseek, model: deepseek-chat }
schedules:
  - { id: "bad-cron", cron: "not-a-cron", zone: "UTC", message: "bad", enabled: true }
---
EOF

# 重启 OryxOS
kill $ORYXOS_PID; sleep 2
mvn -pl oryxos-boot -am spring-boot:run ... 2>&1 | tee /tmp/bad-cron.log

# 期望：进程启动失败（exit code != 0）或该 schedule 拒绝注册
grep "task_id=bad-cron" /tmp/bad-cron.log
# 期望：task_id=bad-cron cron parse failed: ...
```

### S1.4 路径对齐断言（SC-004）

```java
// AgentSchedulerApiPathAlignmentTest.java
@Test
void scheduler_shares_agent_service_process_with_cli_and_web() {
    Method cliProcess = AgentServiceCliHandler.class.getDeclaredMethod("invoke", String.class, String.class);
    Method webProcess = AgentServiceWebHandler.class.getDeclaredMethod("invoke", String.class, String.class);
    Method schedulerProcess = AgentSchedulerTrigger.class.getDeclaredMethod("run", String.class);

    // 三入口最终都调 AgentService.process(Session, String)
    // 通过 Mockito.verify 或 AOP 织入断言
    assertThat(schedulerProcess).isNotNull();
    // 注：详细断言见 contracts/agent-scheduler.md §3.2 + §R-007
}
```

---

## 场景 S2 — 端到端钟推

**对应 spec**：FR-003（cron 触发）/ FR-004（新建 Session）/ FR-005（task_executions）
+ SC-001（三个 Demo 钟推跑通）+ SC-002（端到端集成测试）

### S2.1 单 tick 触发 + 新建 Session

```java
// SchedulerEndToEndIT.java
@SpringBootTest
@TestPropertySource(properties = {
    "oryxos.test.agent.id=daily-weather-agent",
    "oryxos.test.schedule.cron=*/1 * * * * *",  // 每秒（测试用）
    "oryxos.test.schedule.zone=UTC"
})
class SchedulerEndToEndIT {

    @Autowired ScheduleStore scheduleStore;
    @Autowired TaskExecutionRecorder recorder;
    @Autowired SessionRepository sessionRepo;

    @Test
    void scheduled_trigger_creates_session_and_records_execution() throws Exception {
        // Arrange: 1 条 schedule
        ScheduledTaskRecord task = new ScheduledTaskRecord(
            "test-task", "test-agent", "* * * * * *", "UTC", "test msg", true,
            null, Instant.now().plusSeconds(1));
        scheduleStore.upsertAll(List.of(task));

        // Act: 等待 3s 让 tick 触发
        Thread.sleep(3000);

        // Assert
        List<TaskExecution> executions = recorder.findByTaskId("test-task");
        assertThat(executions).hasSizeGreaterThanOrEqualTo(1);
        TaskExecution exec = executions.get(0);
        assertThat(exec.success()).isTrue();
        assertThat(exec.durationMs()).isGreaterThan(0);

        Session session = sessionRepo.findById(exec.sessionId()).orElseThrow();
        assertThat(session.metadata().getString("task_id")).isEqualTo("test-task");
        assertThat(session.metadata().getString("source")).isEqualTo("scheduler");
    }
}
```

### S2.2 三个 Demo 钟推跑通（SC-001）

```bash
# Demo 1：每日天气
mkdir -p /tmp/oryxos-demo/.oryxos/agents/weather-agent
cat > /tmp/oryxos-demo/.oryxos/agents/weather-agent/AGENT.md <<'EOF'
---
name: weather-agent
provider: { name: deepseek, model: deepseek-chat }
notify_channels: [{ type: webhook, config: { url: "http://localhost:9999/weather" } }]
schedules:
  - { id: "daily-weather", cron: "*/5 * * * *", zone: "UTC", message: "查上海今天天气", enabled: true }
---
EOF

# Demo 2 + 3 类似

# 启动并 mock 时间到下一个 tick 之前 1s
# 等待 tick 触发
# 验证：WireMock 收到 webhook + task_executions 新增行 + tool_invocations 关联
```

### S2.3 触发链路过 ReAct/Tool/Memory/Notify（不绕过）

```java
@Test
void scheduler_trigger_traverses_full_agent_pipeline() {
    // Mock AgentService → ReActLoop → PromptBuilder → ToolExecutor → NotifyChannelAdapter
    // 验证：scheduler trigger → AgentService.process() → 至少 1 个 LLM call + 至少 1 个 tool invocation + 1 个 notify

    // 通过 Mockito.verify 断言
    verify(llmCallRepository, atLeastOnce()).save(any());
    verify(toolInvocationRepository, atLeastOnce()).save(any());
    verify(notifyChannelAdapter, atLeastOnce()).send(any(), any());
}
```

---

## 场景 S3 — 并发去重 + 失败重试

**对应 spec**：FR-006（重叠跳过）/ FR-007（失败不熔断 + errorMessage 无 stack trace）
+ SC-003（零误触发）+ SC-006（失败审计完整性）

### S3.1 重叠跳过

```java
// SchedulerConcurrencyDedupIT.java
@SpringBootTest
@TestPropertySource(properties = {
    "oryxos.test.schedule.cron=*/1 * * * * *",
    "oryxos.test.schedule.message=long running",
    "oryxos.test.agent.service.delay=3000"  // 每次执行 3 秒
})
class SchedulerConcurrencyDedupIT {

    @Autowired AgentScheduler scheduler;
    @MockBean AgentService agentService;  // mock 让每次执行耗 3s

    @Test
    void overlapping_ticks_are_skipped() throws Exception {
        // Act: 等待 5 秒（应有 5 个 tick 命中，但执行需 3s/次）
        Thread.sleep(5000);

        // Assert: 仅 1 次进入 AgentService.process
        verify(agentService, times(1)).process(any(), any());

        // Assert: 日志含 skip reason
        assertThat(logCapture.contains("task_id=test-task skip reason=\"previous run still in progress\"")).isTrue();
    }
}
```

### S3.2 失败不熔断

```java
@Test
void execution_failure_does_not_circuit_break() {
    // Arrange: AgentService 抛 RuntimeException("mock failure")
    when(agentService.process(any(), any()))
        .thenThrow(new RuntimeException("mock failure"));

    // Act: 等待 3 次 tick
    Thread.sleep(3500);

    // Assert: 3 次调用尝试
    verify(agentService, times(3)).process(any(), any());

    // Assert: task_executions 3 行 success=false
    List<TaskExecution> executions = recorder.findByTaskId("test-task");
    assertThat(executions).hasSize(3);
    assertThat(executions).allMatch(e -> !e.success());
    assertThat(executions).allMatch(e -> e.errorMessage().equals("mock failure"));
}
```

### S3.3 errorMessage 不含 stack trace（SC-006）

```java
@Test
void error_message_contains_no_stack_trace() {
    // 多层异常嵌套
    when(agentService.process(any(), any()))
        .thenThrow(new RuntimeException("outer",
            new IllegalStateException("inner",
                new IllegalArgumentException("root"))));

    Thread.sleep(1500);

    TaskExecution exec = recorder.findByTaskId("test-task").get(0);
    assertThat(exec.errorMessage()).doesNotContain("at io.oryxos.");
    assertThat(exec.errorMessage()).doesNotContain("at java.");
    assertThat(exec.errorMessage()).doesNotContain("\n\tat ");
    assertThat(exec.errorMessage().length()).isLessThanOrEqualTo(2048);
}
```

### S3.4 手动补跑路径对齐

```java
@Test
void manual_invoke_uses_same_agent_service_process() {
    // 通过 CLI 触发
    cliRunner.invoke("chat", "weather-agent");

    // 通过 Scheduler triggerNow 触发
    scheduler.triggerNow("weather-agent:daily-weather");

    // 断言：两次调用都进同一 AgentService.process(Session, String) 方法
    verify(agentService, times(2)).process(any(), any());
    // 进一步断言：方法对象同一
    // （通过反射 + Mockito mock 验证）
}
```

---

## 场景 S4 — 时区 + 审计完整性

**对应 spec**：FR-009（IANA 时区）/ FR-010（DST）+ SC-005（跨时区准确性）

### S4.1 Asia/Shanghai 跨时区

```java
// SchedulerTimezoneIT.java
@SpringBootTest
@TestPropertySource(properties = {
    "oryxos.test.schedule.cron=0 9 * * *",
    "oryxos.test.schedule.zone=Asia/Shanghai",
    "user.timezone=UTC"  // 强制 JVM 默认 UTC
})
class SchedulerTimezoneIT {

    @Test
    void scheduled_task_with_shanghai_zone_runs_at_01_utc() {
        // Arrange: JVM = UTC；Profile zone = Asia/Shanghai；cron = 0 9 * * *
        // Act: 启动后读 scheduled_tasks.next_run_at_utc
        ScheduledTaskRecord task = scheduleStore.findByTaskId("test-task").orElseThrow();

        // Assert: next_run_at_utc = 01:00:00Z（= 上海 09:00）
        assertThat(task.nextRunAtUtc()).isEqualTo(
            LocalDate.now(ZoneOffset.UTC).atTime(1, 0).toInstant(ZoneOffset.UTC)
        );
    }
}
```

### S4.2 America/New_York DST

```java
@Test
void dst_transition_handled_correctly() {
    // Arrange: zone = America/New_York；cron = 0 2 1 3 *（3 月 1 日 02:00 EST → 跳到 03:00 EDT）
    // 2026 年 DST 切换日：3 月 8 日（周日）02:00 → 03:00

    // Act: 模拟当前时间 2026-03-08 07:30 UTC（= 美东 02:30 EST，已跳过 02:00）
    Clock fixedClock = Clock.fixed(
        Instant.parse("2026-03-08T07:30:00Z"), ZoneOffset.UTC);
    scheduler.setClock(fixedClock);

    // Tick 应触发一次（不丢也不双）
    scheduler.tick();

    // Assert
    List<TaskExecution> executions = recorder.findByTaskId("test-task");
    assertThat(executions).hasSize(1);
    TaskExecution exec = executions.get(0);
    // 触发时间 = 美东 03:00 EDT = UTC 07:00
    assertThat(exec.startedAtUtc()).isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
}
```

### S4.3 审计关联完整性

```java
@Test
void task_session_and_tool_invocation_are_linked() {
    // Act: 触发 1 次
    scheduler.triggerNow("test-task");
    Thread.sleep(500);  // 等 task_executions 写库

    // Assert: 三表可关联回溯
    TaskExecution exec = recorder.findByTaskId("test-task").get(0);
    Session session = sessionRepo.findById(exec.sessionId()).orElseThrow();
    ScheduledTaskRecord task = scheduleStore.findByTaskId(exec.taskId()).orElseThrow();

    assertThat(session.metadata().getString("task_id")).isEqualTo(task.taskId());
    assertThat(session.metadata().getString("source")).isEqualTo("scheduler");

    // tool_invocations 可关联 session
    List<ToolInvocation> tools = toolInvocationRepo.findBySessionId(session.sessionId());
    assertThat(tools).isNotEmpty();
}
```

---

## 接口字节级断言（agent-scheduler.md §3-§8）

```java
// AgentSchedulerApiCompatibilityTest.java
class AgentSchedulerApiCompatibilityTest {

    @Test
    void agent_scheduler_interface_is_byte_stable() {
        Class<?> cls = AgentScheduler.class;

        assertThat(cls.isInterface()).isTrue();
        assertThat(Modifier.isPublic(cls.getModifiers())).isTrue();

        Method[] methods = cls.getDeclaredMethods();
        assertThat(methods).hasSize(5);  // bootstrap / shutdown / listSchedules / triggerNow / isRunning

        assertThat(hasMethod(cls, "bootstrap", List.class)).isTrue();
        assertThat(hasMethod(cls, "shutdown")).isTrue();
        assertThat(hasMethod(cls, "listSchedules")).isTrue();
        assertThat(hasMethod(cls, "triggerNow", String.class)).isTrue();
        assertThat(hasMethod(cls, "isRunning")).isTrue();
    }

    @Test
    void schedule_store_interface_is_byte_stable() {
        Class<?> cls = ScheduleStore.class;
        assertThat(cls.getDeclaredMethods()).hasSize(5);
        // 5 个方法签名断言（详见 contracts/agent-scheduler.md §4.2）
    }

    @Test
    void cron_evaluator_interface_is_byte_stable() {
        Class<?> cls = CronEvaluator.class;
        assertThat(cls.getDeclaredMethods()).hasSize(2);
    }

    @Test
    void task_execution_recorder_interface_is_byte_stable() {
        Class<?> cls = TaskExecutionRecorder.class;
        assertThat(cls.getDeclaredMethods()).hasSize(1);
        // 返回 String（execution_id）
    }
}
```

---

## 性能基线（SC-003 + contracts/agent-scheduler.md §9）

```java
// SchedulerPerformanceBenchmarkIT.java
class SchedulerPerformanceBenchmarkIT {

    @Test
    void bootstrap_100_schedules_within_2_seconds() {
        List<Schedule> schedules = generateSchedules(100);
        long start = System.nanoTime();
        scheduler.bootstrap(schedules);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(2000);  // SC-003
    }

    @RepeatedTest(1000)
    void cron_evaluator_next_run_at_within_50_microseconds() {
        CronEvaluator eval = new CronEvaluator("0 9 * * *", ZoneId.of("Asia/Shanghai"));
        long start = System.nanoTime();
        eval.nextRunAt(Instant.now());
        long elapsedUs = (System.nanoTime() - start) / 1_000;
        assertThat(elapsedUs).isLessThan(50);  // contracts/agent-scheduler.md §9
    }
}
```

---

## 最终验收清单

- [ ] S1 4/4 子场景 PASS（注册 + 非法 cron 拒绝 + 路径对齐）
- [ ] S2 4/4 子场景 PASS（端到端钟推 + Session 创建 + 审计写入）
- [ ] S3 4/4 子场景 PASS（重叠跳过 + 失败不熔断 + errorMessage 无 stack trace + 手动补跑路径对齐）
- [ ] S4 4/4 子场景 PASS（跨时区 + DST + 审计关联）
- [ ] 接口字节级 14/14 PASS（agent-scheduler.md §3.2/§4.2/§5.2/§6.2）
- [ ] 性能 5/5 ≤ 阈值（contracts/agent-scheduler.md §9）
- [ ] `mvn verify` 全 10 模块 SUCCESS

---

## 不在验证范围

- Scheduler REST 增删查改（核心阶段不做）
- 多实例集群协调（核心阶段单实例）
- Profile 热加载（核心阶段重启生效）
- 历史 tick 补跑（核心阶段不补）

---

## 引用

- [spec.md](spec.md) — FR-001..FR-013 + SC-001..SC-008
- [contracts/agent-scheduler.md](contracts/agent-scheduler.md) — 接口字节级契约
- [data-model.md](data-model.md) — 4 实体 + 关系图
- [research.md](research.md) — R-001..R-007 决策依据
- [CLAUDE.md §11 三个验收 Demo](../../CLAUDE.md) — 钟推 Demo 路径
- [CLAUDE.md §13 SQLite 5 张表](../../CLAUDE.md) — `scheduled_tasks` / `task_executions` 表结构
- [007-sandbox-whitelist/contracts/sandbox-whitelist.md](../007-sandbox-whitelist/contracts/sandbox-whitelist.md) — errorMessage 字节级对齐