package io.oryxos.provider.e2e;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.oryxos.provider.LlmRequest;
import io.oryxos.provider.LlmResponse;
import io.oryxos.provider.ProviderService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-4 Profile 热切换模型端到端。
 *
 * <p>同一 Profile 在两个阶段先后指向不同 model：
 * <ol>
 *   <li>阶段 A：{@code model=deepseek-chat} → invoke → 审计行 {@code model='deepseek-chat'}</li>
 *   <li>阶段 B：{@code model=deepseek-coder} → invoke → 审计行 {@code model='deepseek-coder'}</li>
 * </ol>
 * 对应 SC-003：Profile 改 model，下次 invoke 立即用新 model，原 Provider 配置不动。
 *
 * <p>用 WireMock 的 {@code matchingJsonPath("$.model", equalTo("..."))} 校验请求体里的 model
 * 字段确实发生了切换，而不是后端看哪个 stub 命中来猜。
 */
@SpringBootTest(
    classes = E2ETestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Hot-swap model 端到端（Profile.provider.model 透传）")
class HotSwapModelE2ETest {

    @Autowired ProviderService providerService;
    @Autowired @Qualifier("deepseekWireMock") WireMockServer deepseekServer;
    @PersistenceContext EntityManager em;

    @Test
    @Order(1)
    @DisplayName("阶段 A：model=deepseek-chat → 审计行 model='deepseek-chat'，请求体 model 也对得上")
    void phaseA_chatModel() {
        deepseekServer.stubFor(post(urlMatching("/v1/chat/completions"))
            .withRequestBody(matchingJsonPath("$.model", equalTo("deepseek-chat")))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(stubBody("PHASE-A"))));

        LlmResponse r = providerService.invoke("deepseek",
            new LlmRequest(UUID.randomUUID(), "swap-profile", "deepseek-chat",
                List.of(Map.of("role", "user", "content", "phase A message")),
                List.of(), 0.3, 800));

        assertThat(r.textContent()).isEqualTo("PHASE-A");
        deepseekServer.verify(1,
            postRequestedFor(urlMatching("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("deepseek-chat"))));

        Object[] row = latestAuditRow();
        assertThat(row[0]).asString().isEqualTo("deepseek");
        assertThat(row[1]).asString().isEqualTo("deepseek-chat");
        assertThat(((Boolean) row[2]).booleanValue()).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("阶段 B：同一个 Profile 改 model=deepseek-coder → 审计行 model='deepseek-coder'")
    void phaseB_coderModel() {
        deepseekServer.stubFor(post(urlMatching("/v1/chat/completions"))
            .withRequestBody(matchingJsonPath("$.model", equalTo("deepseek-coder")))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(stubBody("PHASE-B"))));

        LlmResponse r = providerService.invoke("deepseek",
            new LlmRequest(UUID.randomUUID(), "swap-profile", "deepseek-coder",
                List.of(Map.of("role", "user", "content", "phase B message")),
                List.of(), 0.3, 800));

        assertThat(r.textContent()).isEqualTo("PHASE-B");
        deepseekServer.verify(1,
            postRequestedFor(urlMatching("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("deepseek-coder"))));

        // 阶段 B 新增了一行；最新行的 model 应是 deepseek-coder
        Object[] row = latestAuditRow();
        assertThat(row[0]).asString().isEqualTo("deepseek");
        assertThat(row[1]).asString().isEqualTo("deepseek-coder");
        assertThat(((Boolean) row[2]).booleanValue()).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("阶段 C：Profile 不指定 model → 回落到 application.yml 默认（deepseek-chat）")
    void phaseC_fallbackToYmlDefault() {
        deepseekServer.stubFor(post(urlMatching("/v1/chat/completions"))
            .withRequestBody(matchingJsonPath("$.model", equalTo("deepseek-chat")))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(stubBody("PHASE-C"))));

        LlmResponse r = providerService.invoke("deepseek",
            new LlmRequest(UUID.randomUUID(), "swap-profile", null,    // ← model=null 走 fallback
                List.of(Map.of("role", "user", "content", "phase C message")),
                List.of(), null, null));

        assertThat(r.textContent()).isEqualTo("PHASE-C");

        Object[] row = latestAuditRow();
        assertThat(row[0]).asString().isEqualTo("deepseek");
        assertThat(row[1]).asString().isEqualTo("deepseek-chat");
    }

    // --- helpers ---

    private Object[] latestAuditRow() {
        // 选最新一行 → (provider, model, success)
        return (Object[]) em.createNativeQuery(
                "SELECT provider, model, success FROM llm_calls " +
                "WHERE id = (SELECT id FROM llm_calls ORDER BY timestamp DESC LIMIT 1)")
            .getSingleResult();
    }

    private static String stubBody(String content) {
        return """
            {
              "id": "cmpl-stub",
              "object": "chat.completion",
              "created": 1700000000,
              "model": "deepseek-chat",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "%s"}, "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 4, "completion_tokens": 4, "total_tokens": 8}
            }
            """.formatted(content);
    }
}
