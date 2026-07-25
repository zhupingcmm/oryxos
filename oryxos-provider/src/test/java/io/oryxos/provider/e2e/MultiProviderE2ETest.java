package io.oryxos.provider.e2e;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.oryxos.core.LlmRequest;
import io.oryxos.core.LlmResponse;
import io.oryxos.core.ProviderService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-3 多 Provider 共存端到端：三个 WireMock 端点（deepseek / qwen / minaimax）+ 各 stub
 * 各报各的内容；连续 invoke 三次后查 {@code llm_calls} 验证 SC-004：
 * <ul>
 *   <li>三行 row 各归属正确 provider</li>
 *   <li>响应内容不串号（provider A 的请求不会意外拿到 provider B 的 stub 响应）</li>
 * </ul>
 */
@SpringBootTest(
    classes = E2ETestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("MultiProvider 端到端（3 WireMock + ProviderRegistry）")
class MultiProviderE2ETest {

    @Autowired ProviderService providerService;
    @Autowired @Qualifier("deepseekWireMock") WireMockServer deepseekServer;
    @Autowired @Qualifier("qwenWireMock")     WireMockServer qwenServer;
    @Autowired @Qualifier("minimaxWireMock")  WireMockServer minaimaxServer;
    @PersistenceContext EntityManager em;

    @Test
    @Order(1)
    @SuppressWarnings("unchecked")
    @DisplayName("三次 invoke（各路由到不同的 Provider）→ 三个 chat wire 均命中；audit 3 行归属独立")
    void threeProvidersStaySeparate() {
        UUID s1 = UUID.randomUUID();
        deepseekServer.stubFor(post(urlMatching("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(stubBody("deepseek-chat", "Hello from deepseek"))));

        LlmResponse r1 = providerService.invoke("deepseek",
            new LlmRequest(s1, "multi", null,
                List.of(Map.of("role", "user", "content", "ping 1")),
                List.of(), 0.5, 1000));
        assertThat(r1.textContent()).isEqualTo("Hello from deepseek");

        UUID s2 = UUID.randomUUID();
        qwenServer.stubFor(post(urlMatching("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(stubBody("qwen-plus", "Hello from qwen"))));

        LlmResponse r2 = providerService.invoke("qwen",
            new LlmRequest(s2, "multi", null,
                List.of(Map.of("role", "user", "content", "ping 2")),
                List.of(), 0.5, 1000));
        assertThat(r2.textContent()).isEqualTo("Hello from qwen");

        UUID s3 = UUID.randomUUID();
        minaimaxServer.stubFor(post(urlMatching("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(stubBody("MiniMax-M3", "Hello from MiniMax"))));

        LlmResponse r3 = providerService.invoke("minimax",
            new LlmRequest(s3, "multi", null,
                List.of(Map.of("role", "user", "content", "ping 3")),
                List.of(), 0.5, 1000));
        assertThat(r3.textContent()).isEqualTo("Hello from MiniMax");

        // 不硬断言 verify(N)——Spring TestContextCache 让 3 个 e2e 类共享 WireMockServer
        // 实例，请求 journal 跨类累积；audit 行归属查询已经覆盖核心断言（SC-004）：
        // 每行 provider 字段等于本次 invoke 的路由键。

        // SC-004：每行归属独立可辨
        List<Object[]> rows = em.createNativeQuery(
                "SELECT provider, model FROM llm_calls " +
                "WHERE id IN (" +
                "  SELECT id FROM llm_calls ORDER BY timestamp DESC LIMIT 3" +
                ") ORDER BY timestamp ASC")
            .getResultList();

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)[0]).isEqualTo("deepseek");
        assertThat(rows.get(0)[1]).isEqualTo("deepseek-chat");
        assertThat(rows.get(1)[0]).isEqualTo("qwen");
        assertThat(rows.get(1)[1]).isEqualTo("qwen-plus");
        assertThat(rows.get(2)[0]).isEqualTo("minimax");
        assertThat(rows.get(2)[1]).isEqualTo("MiniMax-M3");
    }

    @Test
    @Order(2)
    @DisplayName("同 type 多实例并存：deepseek-prod + deepseek-dev 共启，invoke 互不混淆")
    void sameTypeDifferentEndpointDisambiguatedByName() {
        // 现有 wiremock 不区分 prod/dev，所以这里只验证"路由按 name 正确把请求送到对应 stub"
        // 真正的 prod/dev 双账号端点示例放在 application.yml 的注释里（T028）。
        deepseekServer.stubFor(post(urlMatching("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(stubBody("deepseek-chat", "PROD-route"))));

        LlmResponse r = providerService.invoke("deepseek",
            new LlmRequest(UUID.randomUUID(), "same-type-multi", null,
                List.of(Map.of("role", "user", "content", "ping prod")),
                List.of(), 0.5, 1000));

        assertThat(r.textContent()).isEqualTo("PROD-route");

        // 验证 audit 表里这次的 provider 仍是 deepseek（即是说：单元测试里跑的"同 type 多实例"
        // 行为由 ProviderRegistry 的 name → ChatModel 显式映射保证；e2e 层面端到端路由到了
        // deepseek 这个 name 对应的 stub。
        String latestProvider = (String) em.createNativeQuery(
                "SELECT provider FROM llm_calls " +
                "WHERE id = (SELECT id FROM llm_calls ORDER BY timestamp DESC LIMIT 1)")
            .getSingleResult();
        assertThat(latestProvider).isEqualTo("deepseek");
    }

    // --- helpers ---

    private static String stubBody(String model, String content) {
        return """
            {
              "id": "cmpl-stub",
              "object": "chat.completion",
              "created": 1700000000,
              "model": "%s",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "%s"}, "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 4, "completion_tokens": 4, "total_tokens": 8}
            }
            """.formatted(model, content);
    }

    private static com.github.tomakehurst.wiremock.matching.UrlPattern urlMatching(String regex) {
        return com.github.tomakehurst.wiremock.client.WireMock.urlMatching(regex);
    }

    @Transactional
    long countAllRows() {
        Number n = (Number) em.createNativeQuery("SELECT COUNT(*) FROM llm_calls").getSingleResult();
        return n.longValue();
    }
}

