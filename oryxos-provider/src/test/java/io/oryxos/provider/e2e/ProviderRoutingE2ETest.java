package io.oryxos.provider.e2e;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.oryxos.core.LlmRequest;
import io.oryxos.core.LlmResponse;
import io.oryxos.core.ProviderService;
import io.oryxos.provider.exception.LlmInvocationException;
import io.oryxos.provider.exception.UnknownProviderException;
import io.oryxos.storage.repository.LlmCallRecordRepository;
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
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-1 + US-2 端到端验证：起 Spring 上下文、连真实 SQLite、写两个 WireMock Provider，
 * 通过 {@link ProviderService#invoke(String, LlmRequest)} 跑一次成功路径 + 一次失败路径，
 * 然后查 {@code llm_calls} 表断言 SC-001。
 *
 * <p>依赖图：
 * <pre>
 *   Spring Boot (test scope)
 *   ├─ DataSource: SQLite file = ${java.io.tmpdir}/oryxos-e2e.db
 *   ├─ JPA: dialect=SQLiteDialect, ddl-auto=create-drop
 *   ├─ LlmCallRecordRepository (oryxos-storage)
 *   ├─ WireMock @Bean × 2 (deepseek / qwen)  ← E2ETestApp
 *   └─ ProviderAutoConfiguration (oryxos-provider) — 显式 name 路由
 * </pre>
 *
 * <p><strong>约束保留</strong>：宪法 §IV 禁用 Spring AI 的自动 tool 执行——本测试只用
 * {@code ChatModel.call(Prompt)} 底层 API，不引入 {@code ChatClient}。
 */
@SpringBootTest(
    classes = E2ETestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("ProviderRouting 端到端（Spring + SQLite + WireMock）")
class ProviderRoutingE2ETest {

    @Autowired ProviderService providerService;
    @Autowired LlmCallRecordRepository auditRepo;
    @Autowired @Qualifier("deepseekWireMock") WireMockServer deepseekServer;
    @Autowired @Qualifier("qwenWireMock") WireMockServer qwenServer;
    @PersistenceContext EntityManager em;

    @Test
    @Order(1)
    @DisplayName("成功路径：deepseek 路由 → 200 OK → llm_calls 多了一行 success=true")
    void successPathWritesAuditRow() {
        UUID sessionId = UUID.randomUUID();
        long countBefore = countAllRows();

        deepseekServer.stubFor(post(urlMatching("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "id": "cmpl-deepseek-1",
                      "object": "chat.completion",
                      "created": 1700000000,
                      "model": "deepseek-chat",
                      "choices": [
                        {
                          "index": 0,
                          "message": {"role": "assistant", "content": "Hello from deepseek"},
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {"prompt_tokens": 12, "completion_tokens": 7, "total_tokens": 19}
                    }
                    """)));

        LlmResponse resp = providerService.invoke("deepseek",
            new LlmRequest(sessionId, "route-demo", null,
                List.of(Map.of("role", "user", "content", "ping")),
                List.of(), 0.5, 1000));

        assertThat(resp.textContent()).isEqualTo("Hello from deepseek");
        assertThat(resp.usage()).isNotNull();
        assertThat(resp.usage().promptTokens()).isEqualTo(12);
        assertThat(resp.usage().completionTokens()).isEqualTo(7);

        // 直接读 SQL：每次调用必须恰好增 1 行
        long countAfter = countAllRows();
        assertThat(countAfter - countBefore).isEqualTo(1);

        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT provider, model, success, prompt_tokens, completion_tokens, duration_ms, error_message " +
                "FROM llm_calls WHERE id = (SELECT id FROM llm_calls ORDER BY timestamp DESC LIMIT 1)")
            .getSingleResult();

        assertThat(row[0]).isEqualTo("deepseek");
        assertThat(row[1]).isEqualTo("deepseek-chat");
        // SQLite boolean columns come back as Boolean, not Number
        assertThat(((Boolean) row[2]).booleanValue()).isTrue();
        assertThat(((Number) row[3]).intValue()).isEqualTo(12);
        assertThat(((Number) row[4]).intValue()).isEqualTo(7);
        assertThat(((Number) row[5]).longValue()).isGreaterThanOrEqualTo(0L);
        assertThat(row[6]).isNull(); // success=true 时 error_message 必须 NULL（@Check 约束保底）
    }

    @Test
    @Order(2)
    @DisplayName("成功路径：qwen 路由同样能落库 + 三个 Provider 在 audit 表里独立可辨")
    void qwenPathKeepsAuditsSeparate() {
        UUID sessionId = UUID.randomUUID();

        qwenServer.stubFor(post(urlMatching("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "id": "cmpl-qwen-1",
                      "object": "chat.completion",
                      "created": 1700000000,
                      "model": "qwen-plus",
                      "choices": [
                        {"index": 0, "message": {"role": "assistant", "content": "Hi from qwen"}, "finish_reason": "stop"}
                      ],
                      "usage": {"prompt_tokens": 9, "completion_tokens": 5, "total_tokens": 14}
                    }
                    """)));

        LlmResponse resp = providerService.invoke("qwen",
            new LlmRequest(sessionId, "route-demo", null,
                List.of(Map.of("role", "user", "content", "ping")),
                List.of(), 0.5, 1000));

        assertThat(resp.textContent()).isEqualTo("Hi from qwen");

        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT provider, model FROM llm_calls " +
                "WHERE id = (SELECT id FROM llm_calls ORDER BY timestamp DESC LIMIT 1)")
            .getSingleResult();

        assertThat(row[0]).isEqualTo("qwen");
        assertThat(row[1]).isEqualTo("qwen-plus");
    }

    @Test
    @Order(3)
    @DisplayName("失败路径：deepseek 返回 500 → 包成 LlmInvocationException + 同样写一行 success=0")
    void failurePathWritesAuditRow() {
        UUID sessionId = UUID.randomUUID();
        long countBefore = countAllRows();

        deepseekServer.stubFor(post(urlMatching("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(500)
                .withHeader("Content-Type", "text/plain")
                .withFixedDelay(50)
                .withBody("internal error")));

        assertThatThrownBy(() -> providerService.invoke("deepseek",
            new LlmRequest(sessionId, "route-demo", null,
                List.of(Map.of("role", "user", "content", "ping")),
                List.of(), 0.5, 1000)))
            .isInstanceOf(LlmInvocationException.class)
            .hasMessageContaining("500");

        // 失败也必须落一行——day-one 审计承诺
        long countAfter = countAllRows();
        assertThat(countAfter - countBefore).isEqualTo(1);

        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT success, error_message FROM llm_calls WHERE id = (SELECT id FROM llm_calls ORDER BY timestamp DESC LIMIT 1)")
            .getSingleResult();

        assertThat(((Boolean) row[0]).booleanValue()).isFalse();
        assertThat(row[1]).asString().isNotBlank();
    }

    @Test
    @Order(4)
    @DisplayName("未知 Provider name 抛 UnknownProviderException，且 audit 落 success=0")
    void unknownProviderAuditRow() {
        UUID sessionId = UUID.randomUUID();
        long countBefore = countAllRows();

        assertThatThrownBy(() -> providerService.invoke("nonexistent-model",
            new LlmRequest(sessionId, "route-demo", null,
                List.of(Map.of("role", "user", "content", "ping")),
                List.of(), 0.5, 1000)))
            .isInstanceOf(UnknownProviderException.class);

        long countAfter = countAllRows();
        assertThat(countAfter - countBefore).isEqualTo(1);

        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT provider, success, error_message FROM llm_calls WHERE id = (SELECT id FROM llm_calls ORDER BY timestamp DESC LIMIT 1)")
            .getSingleResult();

        assertThat(row[0]).isEqualTo("nonexistent-model");
        assertThat(((Boolean) row[1]).booleanValue()).isFalse();
    }

    // --- helpers ---

    @Transactional
    long countAllRows() {
        Number n = (Number) em.createNativeQuery("SELECT COUNT(*) FROM llm_calls").getSingleResult();
        return n.longValue();
    }
}
