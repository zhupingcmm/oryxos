package io.oryxos.cli;

import io.oryxos.cli.command.ChatCommand;
import io.oryxos.cli.exitcode.Sysexits;
import io.oryxos.cli.spring.SpringContextHandle;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import picocli.CommandLine;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EC-6 (spec.md §"边界情况") — Spring Context startup failure (missing
 * bean, circular dependency, primary source class not found) must be
 * caught at the CLI top level and surfaced as a non-zero exit code
 * with a one-line stderr summary.
 *
 * <p>Spec text: <em>"Spring Context 启动失败（缺 bean、循环依赖）：CLI
 * 顶层捕获 + 打印精简 cause chain + 退出码 1。"</em>
 *
 * <p>We test the most upstream failure mode here — the primary source
 * class itself can't be resolved — by having {@code ChatCommand}'s
 * overridden {@code acquireContext} throw an {@link IllegalStateException}
 * wrapping a {@code ClassNotFoundException}. The CLI's
 * {@code CommandSpringBase.call()} catch-all must translate this into:
 *
 * <ul>
 *   <li>Exit {@link Sysexits#GENERIC} (1) per ChatCommand contract table.</li>
 *   <li>One-line stderr carrying the message; no stack trace.</li>
 * </ul>
 *
 * <p>Bean-level failures (missing bean, circular deps) hit the same
 * {@code Throwable} catch-all in {@code CommandSpringBase.call()}, so
 * covering the upstream failure mode transitively covers them too.
 */
class SpringStartupFailureTest {

    @Test
    void primarySourceClassMissing_exitsGeneric_stderrOneLine() throws Exception {
        // Simulate SpringContextHandle.boot() throwing — the real path
        // would throw IllegalArgumentException("Spring primary source
        // class not found: ..."). ChatCommand's overridden acquireContext
        // surfaces the same shape here. Real Spring bean failures
        // (missing bean, circular deps) hit Throwable catch-all with a
        // non-IllegalArgumentException type (e.g. BeanDefinitionStoreException
        // extends FatalBeanException extends RuntimeException) → exit 1.
        RuntimeException bootFailure = new RuntimeException(
                "Spring primary source class not found: io.oryxos.boot.OryxOsApplication");
        SpringContextHandle handle = Mockito.mock(SpringContextHandle.class);
        Mockito.when(handle.context()).thenThrow(bootFailure);

        ChatCommand cmd = new ChatCommand() {
            @Override
            protected SpringContextHandle acquireContext(String primarySourceClassName) {
                throw bootFailure;
            }
        };
        wireSpec(cmd);
        setField(cmd, "profileName", "weather-bot");
        setField(cmd, "message", "hi");

        java.io.ByteArrayOutputStream stderrSink = new java.io.ByteArrayOutputStream();
        java.io.PrintWriter errWriter =
                new java.io.PrintWriter(stderrSink, true, StandardCharsets.UTF_8);

        Field specField = findField(cmd.getClass(), "spec");
        specField.setAccessible(true);
        CommandLine.Model.CommandSpec spec =
                (CommandLine.Model.CommandSpec) specField.get(cmd);
        spec.commandLine().setErr(errWriter);

        Integer exit = cmd.call();
        errWriter.flush();

        // Then exit = GENERIC (1).
        assertThat(exit).isEqualTo(Sysexits.GENERIC);

        // And stderr has the one-line summary, no stack leak.
        String stderr = stderrSink.toString(StandardCharsets.UTF_8);
        assertThat(stderr)
                .as("one-line stderr mentions primary source, no stack trace")
                .contains("Spring primary source class not found")
                .doesNotContain("\tat ")
                .doesNotContain("RuntimeException")
                .doesNotContain("--- stack trace (--debug) ---");
    }

    @Test
    void circularDependency_exitsGeneric_stderrOneLine() throws Exception {
        // Same catch-all path, but the boot failure is wrapped deeper —
        // a BeanDefinitionStoreException-style message (we use a generic
        // IllegalStateException to model it; the CLI's catch-all is
        // exception-class-agnostic).
        IllegalStateException circularDep = new IllegalStateException(
                "Error creating bean 'agentService': requested bean is currently in creation: "
                        + "Is there an unresolvable circular reference?");
        SpringContextHandle handle = Mockito.mock(SpringContextHandle.class);
        Mockito.when(handle.context()).thenThrow(circularDep);

        ChatCommand cmd = new ChatCommand() {
            @Override
            protected SpringContextHandle acquireContext(String primarySourceClassName) {
                throw circularDep;
            }
        };
        wireSpec(cmd);
        setField(cmd, "profileName", "weather-bot");
        setField(cmd, "message", "hi");

        java.io.ByteArrayOutputStream stderrSink = new java.io.ByteArrayOutputStream();
        java.io.PrintWriter errWriter =
                new java.io.PrintWriter(stderrSink, true, StandardCharsets.UTF_8);

        Field specField = findField(cmd.getClass(), "spec");
        specField.setAccessible(true);
        CommandLine.Model.CommandSpec spec =
                (CommandLine.Model.CommandSpec) specField.get(cmd);
        spec.commandLine().setErr(errWriter);

        Integer exit = cmd.call();
        errWriter.flush();

        assertThat(exit).isEqualTo(Sysexits.GENERIC);

        String stderr = stderrSink.toString(StandardCharsets.UTF_8);
        assertThat(stderr)
                .as("circular dep mentioned in one-line stderr; no stack leak")
                .contains("circular reference")
                .doesNotContain("\tat ")
                .doesNotContain("IllegalStateException")
                .doesNotContain("--- stack trace (--debug) ---");
    }

    // --- helpers ---

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        while (cls != null) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void wireSpec(ChatCommand cmd) {
        CommandLine cl = new CommandLine(cmd);
        try {
            Field specField = findField(cmd.getClass(), "spec");
            specField.setAccessible(true);
            specField.set(cmd, cl.getCommandSpec());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}