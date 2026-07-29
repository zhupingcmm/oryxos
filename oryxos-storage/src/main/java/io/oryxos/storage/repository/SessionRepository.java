package io.oryxos.storage.repository;

import io.oryxos.storage.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code sessions} 表的 Spring Data JPA 仓库。
 *
 * <p>详见 [data-model.md §3.2.2](../../../../../specs/002-react-loop/data-model.md)。
 */
@Repository
public interface SessionRepository extends JpaRepository<SessionEntity, UUID> {

    /** 列出某 Profile 的所有 Session（按 updated_at 倒序）。 */
    List<SessionEntity> findByProfileNameOrderByUpdatedAtDesc(String profileName);

    /** 列出在给定时间点之后被更新过的 Session（用于"最近活跃会话"查询）。 */
    List<SessionEntity> findByUpdatedAtAfter(Instant cutoff);

    /**
     * 008-agent-web-service 阶段新增 —— 按 ID 查活跃 Session.
     *
     * <p>{@code deleted_at IS NULL} 过滤掉已软删除的会话;REST 层走此方法保持
     * "软删除后 GET 返回 404 session_not_found" 的契约.
     */
    Optional<SessionEntity> findByIdAndDeletedAtIsNull(UUID id);
}
