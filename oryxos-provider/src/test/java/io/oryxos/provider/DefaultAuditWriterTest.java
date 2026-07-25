package io.oryxos.provider;

import io.oryxos.storage.entity.LlmCallRecord;
import io.oryxos.storage.repository.LlmCallRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US-2: DefaultAuditWriter 三条路径全部覆盖。
 *
 * <p>覆盖：
 * <ul>
 *   <li>成功路径：写一行 success=true + 全部字段</li>
 *   <li>失败路径：写一行 success=false + errorMessage 非空 + durationMs 有值</li>
 *   <li>写库失败：兜底写一行 "audit write failed"；最终失败仅日志，<strong>不抛</strong></li>
 * </ul>
 */
@DisplayName("DefaultAuditWriter")
class DefaultAuditWriterTest {

    private LlmCallRecordRepository repository;
    private DefaultAuditWriter writer;

    @BeforeEach
    void setUp() {
        repository = mock(LlmCallRecordRepository.class);
        writer = new DefaultAuditWriter(repository);
    }

    @Nested
    @DisplayName("成功路径")
    class Success {

        @Test
        @DisplayName("写一行 success=true，promptTokens / completionTokens / durationMs 都有值")
        void writesSuccessRow() {
            LlmCallRecord record = sampleRecord(true, null, 100, 50, 1234L);
            writer.write(record);

            ArgumentCaptor<LlmCallRecord> captor = ArgumentCaptor.forClass(LlmCallRecord.class);
            verify(repository, times(1)).save(captor.capture());
            LlmCallRecord saved = captor.getValue();
            assertThat(saved.isSuccess()).isTrue();
            assertThat(saved.getErrorMessage()).isNull();
            assertThat(saved.getPromptTokens()).isEqualTo(100);
            assertThat(saved.getCompletionTokens()).isEqualTo(50);
            assertThat(saved.getDurationMs()).isEqualTo(1234L);
        }
    }

    @Nested
    @DisplayName("失败路径")
    class Failure {

        @Test
        @DisplayName("写一行 success=false，errorMessage 非空，durationMs 有值")
        void writesFailureRow() {
            LlmCallRecord record = sampleRecord(false, "401 unauthorized", null, null, 999L);
            writer.write(record);

            ArgumentCaptor<LlmCallRecord> captor = ArgumentCaptor.forClass(LlmCallRecord.class);
            verify(repository, times(1)).save(captor.capture());
            LlmCallRecord saved = captor.getValue();
            assertThat(saved.isSuccess()).isFalse();
            assertThat(saved.getErrorMessage()).isEqualTo("401 unauthorized");
            assertThat(saved.getDurationMs()).isEqualTo(999L);
        }
    }

    @Nested
    @DisplayName("写库失败的弹性（resilience）")
    class Resilience {

        @Test
        @DisplayName("repo 抛异常 → 兜底写一行 success=false + audit write failed 错误信息")
        void fallbackRowWritten() {
            when(repository.save(any()))
                .thenThrow(new RuntimeException("SQLite disk full"))
                .thenAnswer(inv -> inv.getArgument(0));

            LlmCallRecord original = sampleRecord(true, null, 100, 50, 1234L);
            writer.write(original);  // 不应抛异常

            ArgumentCaptor<LlmCallRecord> captor = ArgumentCaptor.forClass(LlmCallRecord.class);
            verify(repository, atLeastOnce()).save(captor.capture());
            // 第一次失败 + 第二次成功（兜底）= 2 次 save
            assertThat(captor.getAllValues()).hasSize(2);

            LlmCallRecord fallback = captor.getAllValues().get(1);
            assertThat(fallback.isSuccess()).isFalse();
            assertThat(fallback.getErrorMessage()).contains("audit write failed");
            assertThat(fallback.getErrorMessage()).contains("SQLite disk full");
            assertThat(fallback.getProvider()).isEqualTo(original.getProvider());
        }

        @Test
        @DisplayName("repo 两次都抛异常 → 仅日志告警，不向上抛")
        void doesNotPropagate() {
            when(repository.save(any())).thenThrow(new RuntimeException("disk full"));

            LlmCallRecord original = sampleRecord(true, null, 100, 50, 1234L);
            // 调用方不应收到异常
            writer.write(original);

            verify(repository, times(2)).save(any());
        }
    }

    // --- helpers ---

    private LlmCallRecord sampleRecord(boolean success, String errorMessage,
                                       Integer prompt, Integer completion, long durationMs) {
        return new LlmCallRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "route-demo",
            "deepseek",
            "deepseek-chat",
            success,
            errorMessage,
            prompt,
            completion,
            durationMs,
            Instant.now(),
            Map.of()
        );
    }
}