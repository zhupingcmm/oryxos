package io.oryxos.core.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 008-agent-scheduler 阶段 —— core 模块内 4 个公共 API 的反射兼容测试。
 *
 * <p>目的：<b>锚定公共 API 形状</b>，防止后续阶段（如多实例 / cluster 化重构）误改签名，
 * 字节级破坏 {@code AgentScheduler} / {@code CronEvaluator} /
 * {@code TaskExecutionRecorder} / {@code Schedule} 的调用方。
 *
 * <p>{@code ScheduleStore} 位于 {@code oryxos-storage} 模块，单独一个测试文件
 * （{@code ScheduleStoreApiCompatibilityTest}）覆盖。
 *
 * <h2>测试粒度</h2>
 * <ul>
 *   <li>方法名 + 形参类型列表（不检实现）</li>
 *   <li>关键 record 组件顺序（与 YAML/JSON 序列化兼容）</li>
 * </ul>
 *
 * <p>SPEC: contracts §3.2（公共 API 形状不可变）。
 */
class AgentSchedulerApiCompatibilityTest {

    @Test
    @DisplayName("AgentScheduler 接口方法名 + 形参列表稳定")
    void agentSchedulerApiStable() throws NoSuchMethodException {
        Method bootstrap = AgentScheduler.class.getMethod("bootstrap", java.util.List.class);
        assertNotNull(bootstrap);

        Method shutdown = AgentScheduler.class.getMethod("shutdown");
        assertEquals(void.class, shutdown.getReturnType());

        Method list = AgentScheduler.class.getMethod("listSchedules");
        assertEquals(java.util.List.class, list.getReturnType());

        Method triggerNow = AgentScheduler.class.getMethod("triggerNow", String.class);
        assertEquals(void.class, triggerNow.getReturnType());

        Method isRunning = AgentScheduler.class.getMethod("isRunning");
        assertEquals(boolean.class, isRunning.getReturnType());
    }

    @Test
    @DisplayName("CronEvaluator 接口方法名 + 形参列表稳定")
    void cronEvaluatorApiStable() throws NoSuchMethodException {
        Method next = CronEvaluator.class.getMethod("nextRunAt", java.time.Instant.class);
        assertEquals(java.time.Instant.class, next.getReturnType());

        Method validate = CronEvaluator.class.getMethod("validate");
        assertEquals(void.class, validate.getReturnType());
    }

    @Test
    @DisplayName("TaskExecutionRecorder 接口方法名 + 形参列表稳定")
    void taskExecutionRecorderApiStable() {
        assertTrue(hasMethod(
            TaskExecutionRecorder.class,
            "record",
            TaskExecutionRecorder.ExecutionContext.class,
            java.time.Instant.class,
            long.class,
            boolean.class,
            String.class
        ));
        Method m = Arrays.stream(TaskExecutionRecorder.class.getDeclaredMethods())
            .filter(x -> x.getName().equals("record"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing record method"));
        assertEquals(String.class, m.getReturnType());
    }

    @Test
    @DisplayName("Schedule record 组件顺序稳定 (profileName, id, cron, zone, message, enabled)")
    void scheduleRecordComponentOrderStable() {
        Parameter[] params = primaryCtor(Schedule.class);
        assertEquals(6, params.length);
        assertEquals("profileName", params[0].getName());
        assertEquals("id", params[1].getName());
        assertEquals("cron", params[2].getName());
        assertEquals("zone", params[3].getName());
        assertEquals("message", params[4].getName());
        assertEquals("enabled", params[5].getName());
    }

    @Test
    @DisplayName("TaskExecutionRecorder.ExecutionContext 组件顺序稳定 (taskId, sessionId, triggerSource)")
    void executionContextComponentOrderStable() {
        Parameter[] params = primaryCtor(TaskExecutionRecorder.ExecutionContext.class);
        assertEquals(3, params.length);
        assertEquals("taskId", params[0].getName());
        assertEquals("sessionId", params[1].getName());
        assertEquals("triggerSource", params[2].getName());
    }

    @Test
    @DisplayName("AgentScheduler.ScheduleView 组件顺序稳定 (taskId, profileName, cron, zone, message, enabled, nextRunAtUtc, lastRunAtUtc)")
    void scheduleViewComponentOrderStable() {
        Parameter[] params = primaryCtor(AgentScheduler.ScheduleView.class);
        assertEquals(8, params.length);
        assertEquals("taskId", params[0].getName());
        assertEquals("profileName", params[1].getName());
        assertEquals("cron", params[2].getName());
        assertEquals("zone", params[3].getName());
        assertEquals("message", params[4].getName());
        assertEquals("enabled", params[5].getName());
        assertEquals("nextRunAtUtc", params[6].getName());
        assertEquals("lastRunAtUtc", params[7].getName());
    }

    // --- helpers ---

    private static Parameter[] primaryCtor(Class<?> record) {
        return Arrays.stream(record.getDeclaredConstructors())
            .max((a, b) -> Integer.compare(a.getParameterCount(), b.getParameterCount()))
            .orElseThrow(() -> new AssertionError("no ctor for " + record.getName()))
            .getParameters();
    }

    private static boolean hasMethod(Class<?> iface, String name, Class<?>... params) {
        try {
            iface.getMethod(name, params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}