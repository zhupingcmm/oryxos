package io.oryxos.storage.repository;

import io.oryxos.storage.entity.LlmCallRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code llm_calls} 表的 Spring Data JPA 仓库。
 *
 * <p>按 spec FR-007 + 宪法 §VI "day-one 审计"约束：写入路径必须经过本仓库，
 * 由 {@code oryxos-provider} 模块的 {@code AuditWriter} 包装为
 * {@code REQUIRES_NEW} 事务。
 */
@Repository
public interface LlmCallRecordRepository extends JpaRepository<LlmCallRecord, UUID> {

    /** 列出某 session 的所有调用（按时间升序）。 */
    List<LlmCallRecord> findBySessionIdOrderByTimestampAsc(UUID sessionId);

    /** 列出某 Profile 的所有调用（按时间倒序）。 */
    List<LlmCallRecord> findByProfileNameOrderByTimestampDesc(String profileName);

    /** 按 Provider + 时间窗口过滤（用于运维仪表："今天 deepseek 调了多少"）。 */
    List<LlmCallRecord> findByProviderAndTimestampBetweenOrderByTimestampDesc(
        String provider, Instant from, Instant to
    );
}