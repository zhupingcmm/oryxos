package io.oryxos.storage.taskexecutions;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 008-agent-scheduler 阶段 —— {@code task_executions} 表 Spring Data JPA Repository。
 *
 * <p>提供按 {@code task_id} / {@code session_id} 查询，用于审计关联（SC-005 + quickstart S4.3）。
 */
public interface TaskExecutionRepository extends JpaRepository<TaskExecutionRecord, String> {

    /** 按 {@code task_id} 查询（某条 schedule 的全部执行历史）。 */
    List<TaskExecutionRecord> findByTaskId(String taskId);

    /** 按 {@code session_id} 查询（某次执行的审计反查）。 */
    List<TaskExecutionRecord> findBySessionId(String sessionId);
}