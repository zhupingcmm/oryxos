package io.oryxos.core.scheduler;

import io.oryxos.core.Session;

/**
 * 008-agent-scheduler 阶段 —— Schedule 触发时创建新 Session 的工厂接口。
 *
 * <p>实现归 SessionFactoryImpl（oryxos-storage 模块 + ProfileLoader），按
 * {@code profileName} 解析 Profile → 创建 {@code SessionEntity}（带 UUID v7）并持久化。
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>返回的 {@link Session} MUST 已被持久化（{@code sessions} 表有一行）</li>
 *   <li>{@code profileName} 不存在 → {@link IllegalArgumentException}（C-AS-3 同款语义）</li>
 *   <li>provider 未配置 → {@link IllegalArgumentException}（C-AS-4 同款语义）</li>
 * </ul>
 */
public interface SessionFactory {

    /**
     * 按 profile 创建一个新的、已持久化的 Session。
     *
     * @param profileName 已注册的 Profile 名（{@code ^[a-z][a-z0-9-]{0,63}$}）
     * @return 新 Session
     */
    Session create(String profileName);

    /**
     * 按 profile + 触发任务 ID 创建一个新的、已持久化的 Session。
     *
     * <p>{@code taskId} 写入 {@code sessions.metadata.task_id}（per [data-model.md §实体 4](../../../../../../specs/008-agent-scheduler/data-model.md)），
     * 允许跨表 {@code task_executions.task_id} ↔ {@code sessions.metadata.task_id} 关联（SC-005 双向关联）。
     *
     * <p>未指定 taskId 的入口（CLI / Web）走 {@link #create(String)}；指定 taskId 的入口
     * （Scheduler）走本重载 —— 这是 US-2 spec §FR-001 的字节级契约。
     *
     * @param profileName 已注册的 Profile 名
     * @param taskId     {@code <profileName>:<scheduleId>} 形式；非空
     * @return 新 Session（{@code metadata.task_id} = {@code taskId}）
     */
    default Session create(String profileName, String taskId) {
        // 默认实现兼容旧接口 —— 不写入 taskId（保持向后兼容）
        return create(profileName);
    }
}