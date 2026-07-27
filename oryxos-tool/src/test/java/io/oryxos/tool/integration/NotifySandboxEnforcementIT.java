package io.oryxos.tool.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.oryxos.core.NotifyChannelConfig;
import io.oryxos.core.ProfileContext;
import io.oryxos.tool.notify.NotifyResult;
import io.oryxos.tool.notify.WebhookNotifyAdapter;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxProperties;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebhookNotifyAdapter 真实集成 —— Sandbox 在 HTTP 请求前拦截 URL（spec FR-007 / 007 阶段）。
 *
 * <p>覆盖 4 个场景（T012 [P] [US3]）：
 * <ol>
 *   <li>越域（白名单不含 evil.example.com）→ sandbox 拦截 + WireMock 零请求</li>
 *   <li>白名单内（localhost）→ HTTP POST 发出 + status 写入审计</li>
 *   <li>IPv6 字面 {@code [::1]} → sandbox IP-literal 拒绝 + WireMock 零请求</li>
 *   <li>fail-closed 空白名单 → 任何 URL 拦截（宪法 §VII）</li>
 * </ol>
 *
 * <p>架构（CLAUDE.md §9.5）：
 * <pre>
 *   NotifyTool.execute → routeAndSend → WebhookNotifyAdapter.send
 *                                       ↓ (line 88) sandbox.enforce(HTTP_REQUEST, url)
 *                                       ↓ (line 87-95) SandboxViolationException → NotifyResult(success=false, errorMessage)
 * </pre>
 */
@SpringBootTest(classes = NotifySandboxEnforcementIT.NotifyApp.class)
@TestPropertySource(properties = {
    "oryxos.tool.sandbox.http.allowed-domains[0]=localhost",
    "spring.main.allow-bean-definition-overriding=true"
})
class NotifySandboxEnforcementIT {

    /**
     * 静态 {@code @TestPropertySource} 已含 "localhost" 白名单 —— WireMock 绑定 {@code 127.0.0.1}，
     * 但 sandbox 校验的是 URL 中的 host（{@code http://localhost:PORT/hook}）而非 IP 绑定地址，故 "localhost" 已足够。
     * 不再用 {@link DynamicPropertySource}（索引冲突会让 binder 报 unbound）。
     */

    @Configuration
    @EnableConfigurationProperties(SandboxProperties.class)
    static class NotifyApp {

        @Bean
        @Primary
        public Sandbox sandbox(SandboxProperties props) {
            return new WhitelistSandbox(props);
        }

        @Bean
        public WebhookNotifyAdapter webhookNotifyAdapter(Sandbox sandbox) {
            return new WebhookNotifyAdapter(sandbox);
        }
    }

    static WireMockServer wm;
    static int wmPort;

    @BeforeAll
    static void startWireMock() {
        wm = new WireMockServer(WireMockConfiguration.options()
            .dynamicPort()
            .bindAddress("127.0.0.1"));
        wm.start();
        wmPort = wm.port();
        wm.stubFor(post(urlMatching("/.*"))
            .willReturn(aResponse().withStatus(200).withBody("ok")));
    }

    @AfterAll
    static void stopWireMock() {
        if (wm != null) wm.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wm.resetAll();
        wm.stubFor(post(urlMatching("/.*"))
            .willReturn(aResponse().withStatus(200).withBody("ok")));
        // ProfileContext 在每个测试入口 set；在 @AfterEach clear（每线程独立）
        ProfileContext.set(new ProfileContext.Snapshot(
            "notify-test", java.util.UUID.randomUUID(),
            new AtomicInteger(0)));
    }

    @AfterEach
    void clearContext() {
        ProfileContext.clear();
    }

    @Test
    @DisplayName("notify 越域（evil.example.com 不在白名单）→ sandbox 拦截 + WireMock 零请求 + errorClass=sandbox_violation")
    void notify_disallowed_host_blocked_no_side_effect() {
        NotifyResult result = defaultAdapter.send(
            new NotifyChannelConfig("default", "webhook", "https://evil.example.com/hook", null),
            "test-content");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("not in allowed-domains");
        // WireMock 没有 POST 命中
        wm.verify(exactly(0), postRequestedFor(urlMatching("/.*")));
    }

    @Test
    @DisplayName("notify 白名单内（localhost）→ HTTP POST 发出 + status_code=200 写入审计")
    void notify_allowed_host_succeeds_audit_captures_status() {
        String url = "http://localhost:" + wmPort + "/hook";
        NotifyResult result = defaultAdapter.send(
            new NotifyChannelConfig("default", "webhook", url, null),
            "test-content");

        assertThat(result.success()).isTrue();
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.errorMessage()).isNull();
        // WireMock 收到一次 POST
        wm.verify(exactly(1), postRequestedFor(urlMatching("/.*")));
    }

    @Test
    @DisplayName("notify IPv6 字面 '[::1]' → sandbox IP-literal 拒绝 + WireMock 零请求")
    void notify_ipv6_literal_blocked_no_side_effect() {
        NotifyResult result = defaultAdapter.send(
            new NotifyChannelConfig("default", "webhook", "http://[::1]:8080/hook", null),
            "test-content");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("IP-literal");
        wm.verify(exactly(0), postRequestedFor(urlMatching("/.*")));
    }

    @Test
    @DisplayName("notify fail-closed：空白名单 → 任何 URL 拦截")
    void notify_fail_closed_empty_whitelist_blocks_all() {
        // 临时构造一个空白名单 sandbox 直接走底层校验（绕开 YAML 配置）
        Sandbox closed = new WhitelistSandbox(java.util.List.of());
        WebhookNotifyAdapter directAdapter = new WebhookNotifyAdapter(closed);
        String url = "http://localhost:" + wmPort + "/hook";

        NotifyResult result = directAdapter.send(
            new NotifyChannelConfig("default", "webhook", url, null),
            "test-content");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("not in allowed-domains");
        wm.verify(exactly(0), postRequestedFor(urlMatching("/.*")));
    }

    @Autowired WebhookNotifyAdapter defaultAdapter;
}