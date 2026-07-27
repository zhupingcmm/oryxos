package io.oryxos.storage.scheduler;

import io.oryxos.core.scheduler.ScheduleStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 008-agent-scheduler 阶段 —— {@code ScheduleStore} 接口的 API 兼容反射测试。
 *
 * <p>放在 {@code oryxos-storage} 模块（与被测接口同模块）以避免跨模块 test-scope 依赖。
 * 锚定 ScheduleStore 的 5 个方法签名 + ScheduledTaskRecord 主键字段名，
 * 防止后续阶段（如 JPA → MyBatis 迁移）误改方法签名 / 列名。
 */
class ScheduleStoreApiCompatibilityTest {

    @Test
    @DisplayName("ScheduleStore 5 个方法签名稳定")
    void methodSignaturesStable() throws NoSuchMethodException {
        Method upsertAll = ScheduleStore.class.getMethod("upsertAll", List.class);
        assertEquals(int.class, upsertAll.getReturnType());

        Method findAllEnabled = ScheduleStore.class.getMethod("findAllEnabled");
        assertEquals(List.class, findAllEnabled.getReturnType());

        Method findByTaskId = ScheduleStore.class.getMethod("findByTaskId", String.class);
        assertEquals(java.util.Optional.class, findByTaskId.getReturnType());

        Method updateRunTimes = ScheduleStore.class.getMethod(
            "updateRunTimes", String.class, Instant.class, Instant.class);
        assertEquals(void.class, updateRunTimes.getReturnType());

        Method deleteByTaskId = ScheduleStore.class.getMethod("deleteByTaskId", String.class);
        assertEquals(void.class, deleteByTaskId.getReturnType());
    }

    @Test
    @DisplayName("ScheduledTaskRecord @Id 主键字段名 = 'taskId' (JPA column task_id)")
    void primaryKeyFieldStable() throws NoSuchMethodException, NoSuchFieldException {
        // 反射拿 getter，再看 JPA @Id 注解 → 锚定主键 getter 形状
        Method getTaskId = ScheduledTaskRecord.class.getMethod("getTaskId");
        assertNotNull(getTaskId);
        jakarta.persistence.Id id = ScheduledTaskRecord.class.getDeclaredField("taskId")
            .getAnnotation(jakarta.persistence.Id.class);
        assertNotNull(id, "@Id must be on 'taskId' field");

        jakarta.persistence.Column col = ScheduledTaskRecord.class.getDeclaredField("taskId")
            .getAnnotation(jakarta.persistence.Column.class);
        assertNotNull(col, "@Column must be on 'taskId' field");
        assertEquals("task_id", col.name());
        assertTrue(!col.nullable(), "task_id column MUST be NOT NULL");
    }
}