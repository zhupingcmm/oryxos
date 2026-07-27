package io.oryxos.tool.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
    void rejectsFileAndShellWhenNoWhitelistConfigured() {
        // 007 阶段 fail-closed 默认：空白名单 = 全部拒绝
        // ALLOWED 只配 HTTP 域名；FILE_READ / SHELL_COMMAND 应走 fail-closed 抛 SandboxViolationException
        // 注：FILE_READ 用相对路径（"../etc/passwd"）确保 cross-platform 都先走到 allowed-paths 分支
        // （绝对路径在 Windows 上变成 drive-relative 如 \etc\passwd，会先触发 absolute 检测）
        SandboxViolationException ex1 = assertThrows(SandboxViolationException.class,
            () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, "../etc/passwd")));
        assertThat(ex1.getMessage()).contains("not in allowed-paths");

        SandboxViolationException ex2 = assertThrows(SandboxViolationException.class,
            () -> sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "rm -rf /")));
        assertThat(ex2.getMessage()).contains("not in allowed-commands");
    }

    // T041 / contracts/sandbox.md §3.1 step 1：scheme 必须 http / https
    @Test
    void rejectsFileScheme() {
        SandboxViolationException ex = assertThrows(SandboxViolationException.class,
            () -> sandbox.enforce(new SandboxAction(
                ActionType.HTTP_REQUEST, "file:///etc/passwd")));
        String msg = ex.getMessage();
        assertThat(msg.contains("unsupported scheme")).as("message: %s", msg).isTrue();
        assertThat(msg.contains("file")).as("message: %s", msg).isTrue();
    }

    @Test
    void rejectsGopherScheme() {
        assertThrows(SandboxViolationException.class, () -> sandbox.enforce(new SandboxAction(
            ActionType.HTTP_REQUEST, "gopher://example.com/")));
    }

    @Test
    void rejectsFtpScheme() {
        assertThrows(SandboxViolationException.class, () -> sandbox.enforce(new SandboxAction(
            ActionType.HTTP_REQUEST, "ftp://example.com/secret")));
    }

    @Test
    void acceptsHttpsScheme() {
        assertDoesNotThrow(() -> sandbox.enforce(new SandboxAction(
            ActionType.HTTP_REQUEST, "https://qyapi.weixin.qq.com/hook")));
    }

    // ============ T017 [US4]：007 IPv6补强场景 + fail-closed HTTP 默认 ============

    @Test
    @DisplayName("IPv6 字面 [fe80::1] → IP-literal 拒绝（007 阶段补强）")
    void rejectsIpLiteralV6WithBrackets() {
        SandboxViolationException ex = assertThrows(SandboxViolationException.class,
            () -> sandbox.enforce(new SandboxAction(
                ActionType.HTTP_REQUEST, "http://[fe80::1]/hook")));
        assertThat(ex.getMessage()).contains("IP-literal");
    }

    @Test
    @DisplayName("IPv6 字面 [::1] 环回 → IP-literal 拒绝")
    void rejectsIpLiteralV6Loopback() {
        assertThrows(SandboxViolationException.class, () -> sandbox.enforce(new SandboxAction(
            ActionType.HTTP_REQUEST, "http://[::1]:8080/hook")));
    }

    @Test
    @DisplayName("IPv6 字面含 zone-id '[fe80::1%eth0]' → IP-literal 拒绝（zone ID 不影响判定）")
    void rejectsIpLiteralV6WithZoneId() {
        assertThrows(SandboxViolationException.class, () -> sandbox.enforce(new SandboxAction(
            ActionType.HTTP_REQUEST, "http://[fe80::1%eth0]/api")));
    }

    @Test
    @DisplayName("IPv4-mapped IPv6 '[::ffff:192.168.1.1]' → IP-literal 拒绝（混合字面）")
    void rejectsIpLiteralV6MappedIpv4() {
        assertThrows(SandboxViolationException.class, () -> sandbox.enforce(new SandboxAction(
            ActionType.HTTP_REQUEST, "http://[::ffff:192.168.1.1]/hook")));
    }

    @Test
    @DisplayName("fail-closed HTTP：空白名单 → 任何 URL 拒绝")
    void failClosedHttpBlocksAll() {
        Sandbox closed = new WhitelistSandbox(java.util.List.of());

        SandboxViolationException ex1 = assertThrows(SandboxViolationException.class,
            () -> closed.enforce(new SandboxAction(
                ActionType.HTTP_REQUEST, "https://api.example.com/hook")));
        assertThat(ex1.getMessage()).contains("not in allowed-domains");

        SandboxViolationException ex2 = assertThrows(SandboxViolationException.class,
            () -> closed.enforce(new SandboxAction(
                ActionType.HTTP_REQUEST, "http://localhost:8089/anything")));
        assertThat(ex2.getMessage()).contains("not in allowed-domains");
    }

    @Test
    @DisplayName("HTTP 后缀匹配大小写不敏感：QYAPI.WEIXIN.QQ.COM 应命中 qyapi.weixin.qq.com 白名单")
    void httpSuffixMatchIsCaseInsensitive() {
        // sandbox.enforceHttp 把 host.toLowerCase(Locale.ROOT) 后再匹配 suffix；
        // ALLOWED 列表含 "qyapi.weixin.qq.com"。全大写 hostname 应同样命中。
        assertDoesNotThrow(() -> sandbox.enforce(new SandboxAction(
            ActionType.HTTP_REQUEST, "https://QYAPI.WEIXIN.QQ.COM/cgi-bin/webhook/send")));
    }
}