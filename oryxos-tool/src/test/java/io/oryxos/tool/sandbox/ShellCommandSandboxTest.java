package io.oryxos.tool.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WhitelistSandbox SHELL 白名单单测 —— T009 阶段创建（[tasks.md T009](../../../../../../../specs/007-sandbox-whitelist/tasks.md)）。
 *
 * <p>覆盖（spec FR-004 + research.md R-02）：
 * <ol>
 *   <li>{@code ls -la /tmp} → 不抛异常（首 token 命中白名单）</li>
 *   <li>{@code GIT status} 经 {@code toLowerCase} 后命中 {@code git} 不抛（大小写不敏感）</li>
 *   <li>{@code curl https://evil.com} → 抛 {@code "command 'curl' not in allowed-commands"}</li>
 *   <li>空字符串 {@code "   "} → {@link SandboxAction} record 构造时抛 {@code IllegalArgumentException}
 *       （{@code SandboxAction.target} 契约在 record 构造器层就拒绝 blank —— Sandbox 层的
 *       {@code enforceShell} 空命令分支是不可达的 belt-and-suspenders 防御）</li>
 *   <li>单空格 {@code " "} → 同上 record 层抛 {@code IllegalArgumentException}</li>
 *   <li>{@code shell.allowed-commands=[]} 时任何命令抛 {@code "not in allowed-commands"}（fail-closed）</li>
 *   <li>配 {@code allowed-commands=['git','ls']} 时 {@code cat} 抛 {@code "not in allowed-commands"}</li>
 * </ol>
 */
class ShellCommandSandboxTest {

    private static final List<String> HTTP_ONLY = List.of();
    private static final List<String> FILE_ONLY = List.of();
    private static final List<String> GIT_LS = List.of("git", "ls");
    private static final List<String> CAT_GIT = List.of("cat", "git");

    @Test
    @DisplayName("首 token 在白名单内 → 不抛异常")
    void allowsCommandInWhitelist() {
        Sandbox sandbox = new WhitelistSandbox(HTTP_ONLY, FILE_ONLY, GIT_LS);
        assertDoesNotThrow(() -> sandbox.enforce(new SandboxAction(
            ActionType.SHELL_COMMAND, "ls -la /tmp")));
    }

    @Test
    @DisplayName("大小写不敏感：'GIT status' 经 toLowerCase 后命中 'git' → 不抛")
    void allowsCaseInsensitiveMatch() {
        Sandbox sandbox = new WhitelistSandbox(HTTP_ONLY, FILE_ONLY, GIT_LS);
        assertDoesNotThrow(() -> sandbox.enforce(new SandboxAction(
            ActionType.SHELL_COMMAND, "GIT status")));
    }

    @Test
    @DisplayName("首 token 不在白名单（'curl'）→ 抛 'command 'curl' not in allowed-commands'")
    void rejectsCommandNotInWhitelist() {
        Sandbox sandbox = new WhitelistSandbox(HTTP_ONLY, FILE_ONLY, CAT_GIT);
        SandboxViolationException ex = assertThrows(SandboxViolationException.class,
            () -> sandbox.enforce(new SandboxAction(
                ActionType.SHELL_COMMAND, "curl https://evil.example.com")));
        assertThat(ex.getMessage()).contains("command 'curl'");
        assertThat(ex.getMessage()).contains("not in allowed-commands");
    }

    @Test
    @DisplayName("空白字符串 → SandboxAction record 构造即抛 IllegalArgumentException")
    void rejectsBlankTargetAtRecordLayer() {
        // SandboxAction.target 契约在 record compact constructor 中拒绝 blank target
        // （spec FR-004：空白命令无可执行语义，构造即拒绝）。
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> new SandboxAction(ActionType.SHELL_COMMAND, "   "));
        assertThat(ex.getMessage()).contains("target must not be blank");
    }

    @Test
    @DisplayName("单空格 ' ' → 同上 record 层抛 IllegalArgumentException")
    void rejectsSingleSpaceAtRecordLayer() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> new SandboxAction(ActionType.SHELL_COMMAND, " "));
        assertThat(ex.getMessage()).contains("target must not be blank");
    }

    @Test
    @DisplayName("fail-closed 默认：shell.allowed-commands=[] 时任何命令抛 'not in allowed-commands'")
    void rejectsAllWhenShellAllowedCommandsEmpty() {
        Sandbox sandbox = new WhitelistSandbox(HTTP_ONLY, FILE_ONLY, List.of());

        SandboxViolationException ex1 = assertThrows(SandboxViolationException.class,
            () -> sandbox.enforce(new SandboxAction(
                ActionType.SHELL_COMMAND, "ls")));
        assertThat(ex1.getMessage()).contains("not in allowed-commands");

        SandboxViolationException ex2 = assertThrows(SandboxViolationException.class,
            () -> sandbox.enforce(new SandboxAction(
                ActionType.SHELL_COMMAND, "echo hello")));
        assertThat(ex2.getMessage()).contains("not in allowed-commands");
    }

    @Test
    @DisplayName("白名单内不含 'cat' → 'cat' 抛 'command 'cat' not in allowed-commands'")
    void rejectsCatWhenOnlyGitLsConfigured() {
        Sandbox sandbox = new WhitelistSandbox(HTTP_ONLY, FILE_ONLY, GIT_LS);
        SandboxViolationException ex = assertThrows(SandboxViolationException.class,
            () -> sandbox.enforce(new SandboxAction(
                ActionType.SHELL_COMMAND, "cat README.md")));
        assertThat(ex.getMessage()).contains("command 'cat'");
        assertThat(ex.getMessage()).contains("not in allowed-commands");
    }

    @Test
    @DisplayName("首 token 后多余参数不影响匹配（'ls -la /tmp' 与 'ls /etc' 同命中 'ls'）")
    void allowsExtraArguments() {
        Sandbox sandbox = new WhitelistSandbox(HTTP_ONLY, FILE_ONLY, GIT_LS);
        assertDoesNotThrow(() -> sandbox.enforce(new SandboxAction(
            ActionType.SHELL_COMMAND, "ls -la /etc")));
        assertDoesNotThrow(() -> sandbox.enforce(new SandboxAction(
            ActionType.SHELL_COMMAND, "ls /home")));
    }
}