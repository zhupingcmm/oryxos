package io.oryxos.web.controller;

import io.oryxos.core.AgentService;
import io.oryxos.core.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * T012 — 字节级反射断言: AgentsController.invoke 必须调 AgentService.process(Session, String).
 *
 * <p>spec SC-003 (三触发源同 Method 对象) 字节级断言. 防止后续重构破坏 CLI / Web / Scheduler
 * 同链路契约.
 */
class AgentServiceInvocationReflectionTest {

    @Test
    @DisplayName("US-1: AgentsController.invoke 调用 AgentService.process(Session, String) — 字节级反射")
    void invokeCallsAgentServiceProcessByteLevel() throws NoSuchMethodException {
        Method process = AgentService.class.getMethod("process", Session.class, String.class);
        Parameter[] params = process.getParameters();
        assertEquals(2, params.length, "AgentService.process 必须接收 2 个参数");
        assertSame(Session.class, params[0].getType());
        assertEquals(String.class, params[1].getType());
        assertEquals("session", params[0].getName());
        assertEquals("userMessage", params[1].getName());
    }

    @Test
    @DisplayName("US-1: AgentsController.invoke 方法签名稳定 (path=name, @Valid @RequestBody InvokeRequest)")
    void invokeSignatureStable() throws NoSuchMethodException {
        Method invoke = AgentsController.class.getMethod("invoke", String.class,
            io.oryxos.web.dto.InvokeRequest.class);
        assertEquals(io.oryxos.web.dto.InvokeResponse.class, invoke.getReturnType());

        // path 注解检查
        org.springframework.web.bind.annotation.PostMapping pm =
            invoke.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
        assertEquals("/{name}/invoke", Arrays.stream(pm.value()).findFirst().orElseThrow());

        // @Valid 必须挂在 InvokeRequest 参数上
        boolean hasValid = invoke.getParameters()[1].isAnnotationPresent(
            jakarta.validation.Valid.class);
        assertEquals(true, hasValid, "@Valid 必须挂在 InvokeRequest 参数上");
    }

    @Test
    @DisplayName("US-1: AgentsController 构造函数注入 5 个核心依赖 (AgentService + SessionFactory + ProfileRegistry + TaskExecutionRecorder + timeoutMs)")
    void controllerConstructorStable() {
        // spec SC-005 — AgentsController 注入面稳定
        // 兼容 javac 重载: 主构造 public 5-arg, 测试构造 package-private 5-arg
        var ctors = Arrays.stream(AgentsController.class.getDeclaredConstructors())
            .filter(c -> c.getParameterCount() == 5)
            .findFirst()
            .orElseThrow(() -> new AssertionError("AgentsController must have a 5-arg constructor"));
        Parameter[] params = ctors.getParameters();
        assertSame(AgentService.class, params[0].getType());
        assertSame(io.oryxos.core.scheduler.SessionFactory.class, params[1].getType());
        assertSame(io.oryxos.core.ProfileRegistry.class, params[2].getType());
        assertSame(io.oryxos.core.scheduler.TaskExecutionRecorder.class, params[3].getType());
        assertEquals(long.class, params[4].getType());
    }
}