package io.oryxos.storage.scheduler;

import io.oryxos.core.scheduler.ScheduleEntry;
import io.oryxos.core.scheduler.ScheduleStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 008-agent-scheduler 阶段 —— {@link ScheduleStore} 的 JPA + JdbcTemplate 实现。
 *
 * <p>upsertAll 走 SQLite 原生 {@code INSERT ... ON CONFLICT(task_id) DO UPDATE SET ...}
 * 语法（research.md R-004），保证 task_id 主键冲突原子覆盖。其余方法走 JPA 标准 API。
 *
 * <p>DTO ↔ JPA entity 翻译在本类内部完成；core 模块只看到 {@link ScheduleEntry}。
 *
 * <h2>线程安全</h2>
 * <p>Spring bean 默认单例；{@code JdbcTemplate} / {@code ScheduledTaskRepository} 均线程安全。
 */
@Repository
public class ScheduleStoreImpl implements ScheduleStore {

    private static final String UPSERT_SQL = """
        INSERT INTO scheduled_tasks
          (task_id, profile_name, cron_expr, timezone, message, enabled,
           last_run_at_utc, next_run_at_utc, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(task_id) DO UPDATE SET
          profile_name    = excluded.profile_name,
          cron_expr       = excluded.cron_expr,
          timezone        = excluded.timezone,
          message         = excluded.message,
          enabled         = excluded.enabled,
          next_run_at_utc = excluded.next_run_at_utc,
          updated_at      = excluded.updated_at
        """;

    private final ScheduledTaskRepository repository;
    private final JdbcTemplate jdbc;

    public ScheduleStoreImpl(ScheduledTaskRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public int upsertAll(List<ScheduleEntry> schedules) {
        if (schedules == null || schedules.isEmpty()) {
            return 0;
        }
        int n = 0;
        String now = Instant.now().toString();
        for (ScheduleEntry s : schedules) {
            jdbc.update(
                UPSERT_SQL,
                s.taskId(),
                s.profileName(),
                s.cron(),
                s.zone(),
                s.message(),
                s.enabled() ? 1 : 0,
                s.lastRunAtUtc() == null ? null : s.lastRunAtUtc().toString(),
                s.nextRunAtUtc() == null ? now : s.nextRunAtUtc().toString(),
                now,
                now
            );
            n++;
        }
        return n;
    }

    @Override
    public List<ScheduleEntry> findAllEnabled() {
        List<ScheduledTaskRecord> rows = repository.findByEnabledTrue();
        return mapAll(rows);
    }

    @Override
    public Optional<ScheduleEntry> findByTaskId(String taskId) {
        return repository.findById(taskId).map(ScheduleStoreImpl::toEntry);
    }

    @Override
    @Transactional
    public void updateRunTimes(String taskId, Instant lastRunAtUtc, Instant nextRunAtUtc) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (lastRunAtUtc == null || nextRunAtUtc == null) {
            throw new IllegalArgumentException("lastRunAtUtc and nextRunAtUtc must not be null");
        }
        jdbc.update("""
            UPDATE scheduled_tasks
            SET last_run_at_utc = ?, next_run_at_utc = ?, updated_at = ?
            WHERE task_id = ?
            """,
            lastRunAtUtc.toString(),
            nextRunAtUtc.toString(),
            Instant.now().toString(),
            taskId
        );
    }

    @Override
    @Transactional
    public void deleteByTaskId(String taskId) {
        repository.deleteById(taskId);
    }

    // --- DTO ↔ entity 翻译 ---

    public static List<ScheduleEntry> mapAll(List<ScheduledTaskRecord> rows) {
        List<ScheduleEntry> out = new ArrayList<>(rows.size());
        for (ScheduledTaskRecord r : rows) {
            out.add(toEntry(r));
        }
        return out;
    }

    public static ScheduleEntry toEntry(ScheduledTaskRecord r) {
        String taskId = r.getTaskId();
        int colon = taskId.indexOf(':');
        String profileName = colon >= 0 ? taskId.substring(0, colon) : r.getProfileName();
        String id = colon >= 0 ? taskId.substring(colon + 1) : taskId;
        return new ScheduleEntry(
            profileName,
            id,
            r.getCronExpr(),
            r.getTimezone(),
            r.getMessage(),
            r.isEnabled(),
            parseInstant(r.getNextRunAtUtc()),
            parseInstant(r.getLastRunAtUtc())
        );
    }

    /** ISO-8601 string → Instant。 */
    public static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        return Instant.parse(iso);
    }

    /** Instant → ISO-8601（UTC offset 显式写出）。 */
    public static String formatInstant(Instant instant) {
        if (instant == null) {
            return null;
        }
        return instant.atOffset(ZoneOffset.UTC).toString();
    }
}