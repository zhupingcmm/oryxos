package io.oryxos.storage.repository;

import io.oryxos.storage.entity.ToolInvocationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * {@code tool_invocations} 表的 Spring Data JPA 仓库。
 *
 * <p>详见 [data-model.md §3.9](../../../../../specs/002-react-loop/data-model.md)。
 */
@Repository
public interface ToolInvocationRepository extends JpaRepository<ToolInvocationRecord, UUID> {

    /** 统计某 session 的 Tool 调用次数（用于 SC-004 一致性断言）。 */
    long countBySessionId(UUID sessionId);

    /** 列某 session 的全部 Tool 调用，按 started_at 升序。 */
    List<ToolInvocationRecord> findBySessionIdOrderByStartedAtAsc(UUID sessionId);
}
