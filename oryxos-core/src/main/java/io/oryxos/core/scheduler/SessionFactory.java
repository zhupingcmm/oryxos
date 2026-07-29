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
 *
 * <h2>source 取值（三选一，与 008-agent-scheduler 契约对齐）</h2>
 * <ul>
 *   <li>{@code "scheduler"} — 由 AgentScheduler 触发（{@code taskId} 必填）</li>
 *   <li>{@code "cli"} — 由 CLI 触发（{@code taskId} 为 null）</li>
 *   <li>{@code "web"} — 由 REST 触发（{@code taskId} 为 null）</li>
 * </ul>
 */
public interface SessionFactory {

    /** source 三选一白名单常量（per 008-agent-scheduler data-model.md §实体 4） */
    String SOURCE_SCHEDULER = "scheduler";
    String SOURCE_CLI = "cli";
    String SOURCE_WEB = "web";

    /**
     * 按 profile 创建一个新的、已持久化的 Session。
     *
     * <p>默认 source = {@code "scheduler"}（保留 008-agent-scheduler 阶段向后兼容契约）。
     * CLI / Web 入口应使用 {@link #create(String, String, String)} 显式传入 source。
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
     * <p>默认 source = {@code "scheduler"}（向后兼容）；CLI / Web 入口应使用
     * {@link #create(String, String, String)} 显式 source.
     *
     * @param profileName 已注册的 Profile 名
     * @param taskId     {@code <profileName>:<scheduleId>} 形式；非空
     * @return 新 Session（{@code metadata.task_id} = {@code taskId}）
     */
    default Session create(String profileName, String taskId) {
        return create(profileName, taskId, SOURCE_SCHEDULER);
    }

    /**
     * 按 profile + 触发任务 ID + source 三参创建一个新的、已持久化的 Session.
     *
     * <p>008-agent-web-service 阶段新增 —— CLI / Web 入口通过 {@code source}
     * 区分 audit 日志（per [spec.md FR-004 + data-model.md §实体 4](../../../../../../specs/008-agent-web-service/data-model.md)）.
     *
     * <p>默认实现走 {@link #create(String, String)}（source 默认 scheduler）以兼容既有
     * 测试桩. 真实实现归 SessionFactoryImpl —— 它覆盖本方法并按 source 写入 metadata.
     *
     * <h2>契约</h2>
     * <ul>
     *   <li>{@code source} 必须三选一：{@code "scheduler"} / {@code "cli"} / {@code "web"}；否则 {@link IllegalArgumentException}</li>
     *   <li>{@code source = "scheduler"} 时 {@code taskId} 非空；CLI / Web 时可空</li>
     *   <li>{@code sessions.metadata.source = source}；{@code metadata.task_id = taskId}（仅 scheduler）</li>
     * </ul>
     *
     * @param profileName 已注册的 Profile 名
     * @param taskId     {@code <profileName>:<scheduleId>} 形式；非空（仅 source=scheduler）
     * @param source     {@code "scheduler"} / {@code "cli"} / {@code "web"} 三选一
     * @return 新 Session
     */
    default Session create(String profileName, String taskId, String source) {
        // 默认实现兼容旧 stub —— 忽略 source 参数,走 2-arg 重载(source 默认 scheduler).
        // 真实实现 (SessionFactoryImpl) 覆盖本方法并按 source 写入 metadata.source.
        return create(profileName, taskId);
    }
}
