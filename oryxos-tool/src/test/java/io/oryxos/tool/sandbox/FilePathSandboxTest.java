package io.oryxos.tool.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WhitelistSandbox FILE 白名单单测 —— T006 阶段创建（[tasks.md T006](../../../../../../../specs/007-sandbox-whitelist/tasks.md)）。
 *
 * <p>覆盖（spec FR-003 + research.md R-01）：
 * <ol>
 *   <li>路径在白名单内 + workspace root 解析通过</li>
 *   <li>workspace root 之外路径 → 抛 "path '...' not in allowed-paths"</li>
 *   <li>{@code ../etc/passwd} → 抛 "path traversal detected"</li>
 *   <li>绝对路径 {@code /etc/passwd} → 抛 "absolute path not allowed"</li>
 *   <li>workspace root 含 trailing slash + 子路径仍通过</li>
 *   <li>前缀绕过 {@code /home/agent/workspace-evil/secret.md} 不被 workspace root 包含</li>
 *   <li>{@code ./notes.md} → 抛 traversal（{@code Path.normalize()} 后与原值不等）</li>
 *   <li>{@code file.allowed-paths=[]} 时任何路径抛 "not in allowed-paths"（fail-closed 默认）</li>
 * </ol>
 */
class FilePathSandboxTest {

    private static final List<String> HTTP_ONLY = List.of();
    private static final List<String> WORKSPACE = List.of("/home/agent/workspace");

    @Test
    @DisplayName("路径在白名单内（相对 notes.md）→ 通过")
    void allowsRelativePathInWorkspace() {
        Sandbox sandbox = new WhitelistSandbox(HTTP_ONLY, WORKSPACE, List.of());
        assertDoesNotThrow(() -> sandbox.enforce(new SandboxAction(
            ActionType.FILE_READ, "notes.md")));
    }

    @Test
    @DisplayName("workspace root 之外路径（relative ../etc/passwd）→ 抛 'not in allowed-paths'")
    void rejectsRelativePathOutsideWorkspace() {
        Sandbox sandbox = new WhitelistSandbox(HTTP_ONLY, WORKSPACE, List.of());
        SandboxViolationException ex = assertThrows(SandboxViolationException.class,
            () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, "../etc/passwd")));
        // 注：../etc/passwd 先过 traversal 检测（normalize() 后等于 ../etc/passwd，等值通过），
        // 然后过 absolute 检测（不是绝对路径），最后前缀匹配 → ../etc/passwd 不在 /home/agent/workspace 子树
        assertThat(ex.getMessage()).contains("not in allowed-paths");
    }

    @Test
    @DisplayName("'../etc/passwd' → 抛 'path traversal detected'")
    void rejectsParentTraversalFromRoot() {
        Sandbox sandbox = new WhitelistSandbox(HTTP_ONLY, List.of("/home"), List.of());
        // /home/../etc/passwd 跨平台：Linux normalize→/etc/passwd；Windows normalize→\etc\passwd
        // 任一形式都应被 Path.normalize() != Path.of(raw) 触发 traversal 检测
        SandboxViolationException ex = assertThrows(SandboxViolationException.class,
            () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, "/home/../etc/passwd")));
        assertThat(ex.getMessage()).contains("path traversal detected");
        assertThat(ex.getMessage()).contains("etc");
    }

    @Test
    @DisplayName("绝对路径 '/etc/passwd' → 抛 'absolute path not allowed'")
    void rejectsAbsolutePath() {
        Sandbox sandbox = new WhitelistSandbox(HTTP_ONLY, WORKSPACE, List.of());
        SandboxViolationException ex = assertThrows(SandboxViolationException.class,
            () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, "/etc/passwd")));
        assertThat(ex.getMessage()).contains("absolute path not allowed");
    }

    @Test
    @DisplayName("workspace root 含 trailing slash '/home/agent/workspace/' + 子路径 'notes.md' 仍通过")
    void allowsSubPathWithTrailingSlashWorkspace() {
        Sandbox sandbox = new WhitelistSandbox(HTTP_ONLY,
            List.of("/home/agent/workspace/"), List.of());
        assertDoesNotThrow(() -> sandbox.enforce(new SandboxAction(
            ActionType.FILE_READ, "notes.md")));
    }

    @Test
    @DisplayName("前缀绕过 '/home/agent/workspace-evil/secret.md' 不被 workspace root 包含")
    void rejectsPrefixBypassAttack() {
        // 业务方配 workspace root = /home/agent/workspace（精确字符串，不含 / 后缀）
        // 攻击方建 /home/agent/workspace-evil/secret.md
        // 攻击者通过绝对路径直接访问 → "absolute path not allowed"（getRoot() != null 跨平台）
        Sandbox sandbox = new WhitelistSandbox(HTTP_ONLY, WORKSPACE, List.of());
        SandboxViolationException ex = assertThrows(SandboxViolationException.class,
            () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ,
                "/home/agent/workspace-evil/secret.md")));
        assertThat(ex.getMessage()).contains("absolute path not allowed");
    }

    @Test
    @DisplayName("'./notes.md' → 抛 traversal（Path.normalize() 后与原值不等）")
    void rejectsDotPrefixAsTraversal() {
        Sandbox sandbox = new WhitelistSandbox(HTTP_ONLY, WORKSPACE, List.of());
        SandboxViolationException ex = assertThrows(SandboxViolationException.class,
            () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, "./notes.md")));
        // Path.of("./notes.md").normalize() = "notes.md"；与原值不等 → 视为 traversal
        assertThat(ex.getMessage()).contains("path traversal detected");
    }

    @Test
    @DisplayName("fail-closed 默认：file.allowed-paths=[] 时任何路径抛 'not in allowed-paths'")
    void rejectsAllWhenFileAllowedPathsEmpty() {
        Sandbox sandbox = new WhitelistSandbox(HTTP_ONLY, List.of(), List.of());

        SandboxViolationException ex1 = assertThrows(SandboxViolationException.class,
            () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, "notes.md")));
        assertThat(ex1.getMessage()).contains("not in allowed-paths");

        SandboxViolationException ex2 = assertThrows(SandboxViolationException.class,
            () -> sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, "output.md")));
        assertThat(ex2.getMessage()).contains("not in allowed-paths");
    }

    @Test
    @DisplayName("FILE_WRITE 同样走白名单校验（与 FILE_READ 共用 enforceFile）")
    void fileWriteFollowsSameWhitelist() {
        Sandbox sandbox = new WhitelistSandbox(HTTP_ONLY, WORKSPACE, List.of());

        // 路径在白名单内
        assertDoesNotThrow(() -> sandbox.enforce(new SandboxAction(
            ActionType.FILE_WRITE, "output.md")));

        // 绝对路径拒绝
        SandboxViolationException ex = assertThrows(SandboxViolationException.class,
            () -> sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, "/etc/passwd")));
        assertThat(ex.getMessage()).contains("absolute path not allowed");
    }
}