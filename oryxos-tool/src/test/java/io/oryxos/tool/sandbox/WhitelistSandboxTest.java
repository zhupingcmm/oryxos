package io.oryxos.tool.sandbox;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WhitelistSandbox 单测 —— TDD 验证 4 种路径。
 *
 * <p>覆盖（spec FR-007 / data-model §6）：
 * <ul>
 *   <li>精确匹配：host == allowedDomain</li>
 *   <li>子域匹配：host.endsWith(".allowedDomain")</li>
 *   <li>IP 字面量拒绝</li>
 *   <li>解析失败 / null / 空 host 拒绝</li>
 * </ul>
 */
class WhitelistSandboxTest {

    private static final List<String> ALLOWED = List.of(
        "qyapi.weixin.qq.com", "oapi.dingtalk.com", "open.feishu.cn", "localhost");

    private final Sandbox sandbox = new WhitelistSandbox(ALLOWED);

    @Test
    void allowsExactHostMatch() {
        assertDoesNotThrow(() -> sandbox.enforce(new SandboxAction(
            ActionType.HTTP_REQUEST, "https://qyapi.weixin.qq.com/cgi-bin/webhook/send")));
    }

    @Test
    void allowsSubdomainMatch() {
        assertDoesNotThrow(() -> sandbox.enforce(new SandboxAction(
            ActionType.HTTP_REQUEST, "https://hook.oapi.dingtalk.com/robot/send?access_token=xyz")));
    }

    @Test
    void allowsLocalhost() {
        assertDoesNotThrow(() -> sandbox.enforce(new SandboxAction(
            ActionType.HTTP_REQUEST, "http://localhost:8089/hook/default")));
    }

    @Test
    void rejectsUnknownHost() {
        assertThrows(SandboxViolationException.class, () -> sandbox.enforce(new SandboxAction(
            ActionType.HTTP_REQUEST, "https://evil.example.com/hook")));
    }

    @Test
    void rejectsIpLiteralV4() {
        assertThrows(SandboxViolationException.class, () -> sandbox.enforce(new SandboxAction(
            ActionType.HTTP_REQUEST, "http://192.168.1.100/hook")));
    }

    @Test
    void rejectsIpLiteralV6() {
        assertThrows(SandboxViolationException.class, () -> sandbox.enforce(new SandboxAction(
            ActionType.HTTP_REQUEST, "http://[fe80::1]/hook")));
    }

    @Test
    void rejectsMalformedTarget() {
        // 解析失败（非空白、但 URI host 抽取不到）走 SandboxViolationException
        assertThrows(SandboxViolationException.class, () -> sandbox.enforce(new SandboxAction(
            ActionType.HTTP_REQUEST, "not a url at all!!")));
    }

    @Test
    void rejectsBlankTargetAtConstruction() {
        // 空白 target 在 SandboxAction record 构造时即拒绝（IllegalArgumentException）
        assertThrows(IllegalArgumentException.class, () -> new SandboxAction(
            ActionType.HTTP_REQUEST, ""));
        assertThrows(NullPointerException.class, () -> new SandboxAction(
            ActionType.HTTP_REQUEST, null));
    }

    @Test
    void ignoresOtherActionTypes() {
        // 核心阶段 FILE_READ / SHELL_COMMAND / FILE_WRITE 不做白名单校验
        assertDoesNotThrow(() -> sandbox.enforce(new SandboxAction(
            ActionType.FILE_READ, "/etc/passwd")));
        assertDoesNotThrow(() -> sandbox.enforce(new SandboxAction(
            ActionType.SHELL_COMMAND, "rm -rf /")));
    }
}