package io.oryxos.tool.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Sandbox 核心契约 API 字节级稳定性验证 —— T015 [P] [US4]。
 *
 * <p>5 个核心契约 face（CLAUDE.md §9.4）MUST 字节级不变（spec NFR-004 / SC-007）：
 * <ol>
 *   <li>{@link Sandbox} 接口方法签名</li>
 *   <li>{@link SandboxAction} record 字段（type, target） + compact constructor NPE/blank 校验</li>
 *   <li>{@link ActionType} enum 4 值（FILE_READ / FILE_WRITE / SHELL_COMMAND / HTTP_REQUEST）</li>
 *   <li>{@link SandboxViolationException} ctor 签名 + getter</li>
 *   <li>{@link SandboxProperties} 3 子类 + getter/setter + null 兜底</li>
 * </ol>
 *
 * <p>另：{@link WhitelistSandbox#enforce(SandboxAction)} 公开签名（含构造器）。这些断言不允许静默改：
 * 任何运行时对外部可见的签名变化必须经过显式契约升级流程（spec FR-014 / 宪法 §VII）。
 */
class SandboxApiCompatibilityTest {

    // ============ 1. Sandbox 接口 ============

    @Test
    @DisplayName("Sandbox 接口有且仅有 1 个 enforce(SandboxAction) 公开方法")
    void sandboxHasExactlyOneEnforceMethod() {
        Method[] publicMethods = Arrays.stream(Sandbox.class.getDeclaredMethods())
            .filter(m -> !m.isSynthetic())
            .toArray(Method[]::new);
        assertThat(publicMethods).hasSize(1);
        assertThat(publicMethods[0].getName()).isEqualTo("enforce");
        assertThat(publicMethods[0].getParameterCount()).isEqualTo(1);
        assertThat(publicMethods[0].getParameterTypes()[0]).isEqualTo(SandboxAction.class);
        assertThat(publicMethods[0].getReturnType()).isEqualTo(void.class);
    }

    @Test
    @DisplayName("Sandbox 是 public interface")
    void sandboxIsPublicInterface() {
        int mods = Sandbox.class.getModifiers();
        assertThat(java.lang.reflect.Modifier.isPublic(mods)).isTrue();
        assertThat(java.lang.reflect.Modifier.isInterface(mods)).isTrue();
    }

    // ============ 2. SandboxAction record ============

    @Test
    @DisplayName("SandboxAction record 字段 type, target")
    void sandboxActionHasTwoComponents() {
        assertThat(SandboxAction.class.getRecordComponents())
            .extracting(rc -> rc.getName())
            .containsExactly("type", "target");
    }

    @Test
    @DisplayName("SandboxAction.reject null type → NPE")
    void sandboxActionRejectsNullType() {
        assertThrows(NullPointerException.class,
            () -> new SandboxAction(null, "https://example.com"));
    }

    @Test
    @DisplayName("SandboxAction.reject null target → NPE")
    void sandboxActionRejectsNullTarget() {
        assertThrows(NullPointerException.class,
            () -> new SandboxAction(ActionType.HTTP_REQUEST, null));
    }

    @Test
    @DisplayName("SandboxAction.reject blank target → IllegalArgumentException")
    void sandboxActionRejectsBlankTarget() {
        assertThrows(IllegalArgumentException.class,
            () -> new SandboxAction(ActionType.HTTP_REQUEST, "  "));
    }

    // ============ 3. ActionType enum ============

    @Test
    @DisplayName("ActionType 4 值，顺序固定 FILE_READ / FILE_WRITE / SHELL_COMMAND / HTTP_REQUEST")
    void actionTypeHasFourValuesInFixedOrder() {
        assertThat(ActionType.values()).containsExactly(
            ActionType.FILE_READ,
            ActionType.FILE_WRITE,
            ActionType.SHELL_COMMAND,
            ActionType.HTTP_REQUEST);
    }

    // ============ 4. SandboxViolationException ============

    @Test
    @DisplayName("SandboxViolationException extends RuntimeException")
    void sandboxViolationExceptionIsRuntimeException() {
        // RuntimeException.isAssignableFrom(SandboxViolationException.class) = true
        // 即 SandboxViolationException can be cast to RuntimeException
        assertThat(RuntimeException.class.isAssignableFrom(SandboxViolationException.class)).isTrue();
    }

    @Test
    @DisplayName("SandboxViolationException 含 (SandboxAction, String) ctor")
    void sandboxViolationExceptionAcceptsSandboxActionAndMessage() {
        Constructor<?>[] ctors = SandboxViolationException.class.getDeclaredConstructors();
        assertThat(ctors).isNotEmpty();

        boolean hasExpectedCtor = Arrays.stream(ctors).anyMatch(c -> {
            Class<?>[] params = c.getParameterTypes();
            return params.length == 2
                && params[0] == SandboxAction.class
                && params[1] == String.class;
        });
        assertThat(hasExpectedCtor).isTrue();
    }

    // ============ 5. SandboxProperties ============

    @Test
    @DisplayName("SandboxProperties 三个子类实例 + @ConfigurationProperties prefix")
    void sandboxPropertiesHasThreeSubConfigsAndPrefix() {
        SandboxProperties props = new SandboxProperties();
        assertThat(props.getHttp()).isInstanceOf(SandboxProperties.Http.class);
        assertThat(props.getFile()).isInstanceOf(SandboxProperties.File.class);
        assertThat(props.getShell()).isInstanceOf(SandboxProperties.Shell.class);

        org.springframework.boot.context.properties.ConfigurationProperties ann =
            SandboxProperties.class.getAnnotation(
                org.springframework.boot.context.properties.ConfigurationProperties.class);
        assertThat(ann).isNotNull();
        assertThat(ann.prefix()).isEqualTo("oryxos.tool.sandbox");
    }

    @Test
    @DisplayName("HTTP / File / Shell 子类的 getter + null 兜底")
    void subConfigGettersAndNullCoercion() {
        SandboxProperties.Http http = new SandboxProperties.Http();
        assertThat(http.getAllowedDomains()).isNotNull().isEmpty();
        http.setAllowedDomains(null);
        assertThat(http.getAllowedDomains()).isEmpty();

        SandboxProperties.File file = new SandboxProperties.File();
        assertThat(file.getAllowedPaths()).isNotNull().isEmpty();
        file.setAllowedPaths(null);
        assertThat(file.getAllowedPaths()).isEmpty();

        SandboxProperties.Shell shell = new SandboxProperties.Shell();
        assertThat(shell.getAllowedCommands()).isNotNull().isEmpty();
        assertThat(shell.getDangerousCommands()).isNotNull().isEmpty();
        shell.setAllowedCommands(null);
        shell.setDangerousCommands(null);
        assertThat(shell.getAllowedCommands()).isEmpty();
        assertThat(shell.getDangerousCommands()).isEmpty();
    }

    // ============ 6. WhitelistSandbox 公开面 ============

    @Test
    @DisplayName("WhitelistSandbox implements Sandbox")
    void whitelistSandboxImplementsSandbox() {
        assertThat(Sandbox.class).isAssignableFrom(WhitelistSandbox.class);
    }

    @Test
    @DisplayName("WhitelistSandbox.enforce(SandboxAction) 返回 void")
    void whitelistSandboxPublicEnforceSignature() throws NoSuchMethodException {
        Method m = WhitelistSandbox.class.getMethod("enforce", SandboxAction.class);
        assertThat(m.getReturnType()).isEqualTo(void.class);
    }

    @Test
    @DisplayName("WhitelistSandbox 有 4 公开 ctor（零参/三白名单/配置/仅HTTP）")
    void whitelistSandboxConstructorOverloadsCount() {
        Constructor<?>[] publicCtors = Arrays.stream(WhitelistSandbox.class.getDeclaredConstructors())
            .filter(c -> java.lang.reflect.Modifier.isPublic(c.getModifiers()))
            .toArray(Constructor[]::new);
        assertThat(publicCtors)
            .as("WhitelistSandbox 4 公开 ctor 入口")
            .hasSize(4);
    }
}