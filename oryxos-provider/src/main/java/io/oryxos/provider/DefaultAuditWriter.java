package io.oryxos.provider;

import io.oryxos.storage.entity.LlmCallRecord;
import io.oryxos.storage.repository.LlmCallRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * 默认审计写入器（day-one 可审计地基）。
 *
 * <p>关键约束（spec SC-002 + 宪法 §VI + research.md R-05）：
 * <ol>
 *   <li>每次调用<strong>必须</strong>产出一行 {@code llm_calls} 记录，调用方不可绕过</li>
 *   <li>使用 {@link Propagation#REQUIRES_NEW} 独立事务，避免被业务事务回滚"吃掉"</li>
 *   <li>双层 try/catch：写库失败 → 构造"audit write failed"兜底记录再写一次 →
 *       连这次都失败仅 ERROR 日志，<strong>不</strong>抛给调用方</li>
 * </ol>
 *
 * <p>本 Bean 由 {@code DefaultProviderService} 在 {@code invoke} 方法返回前调用，
 * 写入失败被吞后仅记日志——审计写库的失败<strong>不能</strong>阻塞业务调用方。
 */
@Component
public class DefaultAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(DefaultAuditWriter.class);

    private final LlmCallRecordRepository repository;

    public DefaultAuditWriter(@Lazy LlmCallRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * 写入一条 LLM 调用审计记录。
     *
     * <p>独立事务 + 双层 try/catch：写库失败 → 兜底记录再写一次 → 连这次都失败仅日志。
     *
     * @param record 待写入的记录（{@code id} / {@code timestamp} 由调用方填充）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(LlmCallRecord record) {
        try {
            repository.save(record);
            log.debug("LLM audit row written: id={} provider={} model={} success={}",
                record.getId(), record.getProvider(), record.getModel(), record.isSuccess());
        } catch (Exception primaryFailure) {
            log.error("Primary audit write failed for provider={} model={}: {}",
                record.getProvider(), record.getModel(), primaryFailure.toString(), primaryFailure);

            // 兜底：写一行 success=false + errorMessage 包含原因；用原 record 的其他字段
            LlmCallRecord fallback = new LlmCallRecord(
                UUID.randomUUID(),
                record.getSessionId(),
                record.getProfileName(),
                record.getProvider(),
                record.getModel(),
                false,
                "audit write failed: " + primaryFailure.getClass().getSimpleName()
                    + ": " + safeMessage(primaryFailure),
                null,
                null,
                record.getDurationMs(),
                record.getTimestamp(),
                Map.of("originalId", record.getId().toString())
            );
            try {
                repository.save(fallback);
                log.warn("Fallback audit row written (success=false, errorMessage=audit write failed): id={}",
                    fallback.getId());
            } catch (Exception fallbackFailure) {
                // 兜底也失败：仅告警日志，**不抛**给调用方
                log.error("Fallback audit write ALSO failed for provider={} model={}: {}",
                    fallback.getProvider(), fallback.getModel(), fallbackFailure.toString(), fallbackFailure);
            }
        }
    }

    private static String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return msg == null ? "<no message>" : msg;
    }
}