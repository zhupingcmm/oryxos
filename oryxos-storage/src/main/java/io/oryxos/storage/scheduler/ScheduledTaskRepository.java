package io.oryxos.storage.scheduler;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 008-agent-scheduler 阶段 —— {@code scheduled_tasks} 表 Spring Data JPA Repository。
 *
 * <p>复合操作（upsert / updateRunTimes）用 @Query 走 JPQL；upsert 走 JdbcTemplate 走
 * SQLite 的 {@code INSERT ... ON CONFLICT DO UPDATE} 语法（ScheduleStoreImpl 内部用
 * JdbcTemplate 调用）。
 */
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTaskRecord, String> {

    /** 查全部 enabled=true 的行（调度器 tick 用）—— C-SS-2。 */
    List<ScheduledTaskRecord> findByEnabledTrue();

    /** 更新 next_run_at_utc（启动期批量写入用）—— 单条原子。 */
    @Modifying
    @Query("UPDATE ScheduledTaskRecord r SET r.nextRunAtUtc = :nextRunAtUtc, "
        + "r.updatedAt = :updatedAt WHERE r.taskId = :taskId")
    int updateNextRunAt(@Param("taskId") String taskId,
                        @Param("nextRunAtUtc") String nextRunAtUtc,
                        @Param("updatedAt") String updatedAt);
}