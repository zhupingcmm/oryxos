package io.oryxos.storage.scheduler;

import io.oryxos.core.ProfileRegistry;
import io.oryxos.core.Session;
import io.oryxos.core.scheduler.SessionFactory;
import io.oryxos.storage.entity.SessionEntity;
import io.oryxos.storage.repository.SessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 008-agent-scheduler 阶段 —— {@link SessionFactory} 实现。
 *
 * <p>按 {@code profileName} 走 {@link ProfileRegistry} 校验 → 创建
 * {@link SessionEntity}（UUID v7）→ 写入 metadata JSON（{@code source=scheduler|cli|web}
 * + {@code task_id}（仅 scheduler）+ {@code started_at}，per [data-model.md §实体 4 字节级契约](../../../../../../specs/008-agent-scheduler/data-model.md)）
 * → 走 {@link SessionRepository#save} 持久化 → 返回持久化后的实体。
 *
 * <p>Profile 未注册 → {@link IllegalArgumentException}（与 {@code AgentService.process}
 * 的 C-AS-3 同款语义）。
 *
 * <h2>Scheduler 触发的 Session 形态</h2>
 * <pre>
 * sessions.metadata = {
 *   "source":     "scheduler",
 *   "task_id":    "&lt;profileName&gt;:&lt;scheduleId&gt;",
 *   "started_at": "&lt;ISO-8601 UTC&gt;"
 * }
 * </pre>
 *
 * <h2>CLI / Web 触发的 Session 形态</h2>
 * <pre>
 * sessions.metadata = {
 *   "source":     "cli" | "web",
 *   "started_at": "&lt;ISO-8601 UTC&gt;"
 *   // task_id 键省略（per data-model.md §实体 4 "仅 source=scheduler 时必填"）
 * }
 * </pre>
 */
@Component
public class SessionFactoryImpl implements SessionFactory {

    private static final Set<String> VALID_SOURCES =
        Set.of(SOURCE_SCHEDULER, SOURCE_CLI, SOURCE_WEB);

    private final ProfileRegistry profileRegistry;
    private final SessionRepository sessionRepository;

    @Autowired
    public SessionFactoryImpl(
        ProfileRegistry profileRegistry,
        SessionRepository sessionRepository
    ) {
        this.profileRegistry = profileRegistry;
        this.sessionRepository = sessionRepository;
    }

    @Override
    public Session create(String profileName) {
        // 默认入口（保留 008 字节级向后兼容契约）—— source=scheduler
        return create(profileName, null, SOURCE_SCHEDULER);
    }

    @Override
    public Session create(String profileName, String taskId) {
        // 2-arg 重载 —— 默认 source=scheduler
        return create(profileName, taskId, SOURCE_SCHEDULER);
    }

    @Override
    public Session create(String profileName, String taskId, String source) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("profileName must not be blank");
        }
        if (source == null || !VALID_SOURCES.contains(source)) {
            throw new IllegalArgumentException(
                "source must be one of " + VALID_SOURCES + ", got: " + source);
        }
        if (SOURCE_SCHEDULER.equals(source) && (taskId == null || taskId.isBlank())) {
            throw new IllegalArgumentException(
                "taskId is required when source=\"scheduler\" (data-model.md §实体 4 字节级契约)");
        }
        // C-AS-3 同款：Profile 未注册 → 抛 IllegalArgumentException
        profileRegistry.find(profileName).orElseThrow(() ->
            new IllegalArgumentException("Profile not registered: " + profileName));
        // Scheduler 触发路径（source=scheduler）→ sessions.metadata.task_id = taskId
        // CLI / Web 路径（source=cli|web）→ 不写 task_id
        SessionEntity entity = SessionEntity.createWithMetadata(
            generateId(),
            profileName,
            taskId,
            source,
            Instant.now()
        );
        return sessionRepository.save(entity);
    }

    /**
     * 生成 session_id（UUID v7 形态时间有序，方便按 session_id 排序扫描）。
     *
     * <p>JDK 21 标准库尚未内置 v7；用时间戳前缀 + 随机后缀手工拼装；保持 RFC 4122 版本号位。
     */
    private static UUID generateId() {
        long timestampMs = System.currentTimeMillis();
        long msb = (timestampMs & 0xFFFFFFFFFFFFL) << 16;  // 48-bit 时间戳
        msb |= 0x7000L;  // version = 7
        msb |= UUID.randomUUID().getMostSignificantBits() & 0x0FFFL;
        return new UUID(msb, UUID.randomUUID().getLeastSignificantBits());
    }
}
