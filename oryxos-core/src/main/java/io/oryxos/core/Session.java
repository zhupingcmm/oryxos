package io.oryxos.core;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 会话抽象 —— 包含一轮多轮用户消息、追加的 assistant/tool 回复。
 *
 * <p>本接口在 {@code oryxos-core}，实现归 {@code oryxos-storage} 的 {@link io.oryxos.storage.entity.SessionEntity}（JPA
 * + JSON 列）。
 *
 * <p>详见 [data-model.md §3.2.1](../../../../../specs/002-react-loop/data-model.md)。
 */
public interface Session {

    /** 会话 UUID（全局唯一）。 */
    UUID id();

    /** 关联的 Profile 名。 */
    String profileName();

    /**
     * 当前会话的全部消息（含本轮初始的用户消息 + 所有 assistant/tool 消息）。
     *
     * <p>返回不可变视图 —— 调用方不得尝试修改。
     */
    List<Message> messages();

    /**
     * 把 {@link Message} 追加到 Session 末尾，并按实现语义持久化。
     *
     * <p>实现 MUST：
     * <ul>
     *   <li>用 {@code List.copyOf(...)} 维护不可变性（purity）</li>
     *   <li>触发对应持久化（{@code SessionEntity} 实现走 Spring Data JPA 脏检查 + 事务）</li>
     *   <li>同时刷新 {@link #updatedAt()} 为本地当前时间</li>
     * </ul>
     */
    void appendMessage(Message m);

    /** 创建时间（UTC）。 */
    Instant createdAt();

    /** 最近一次 {@link #appendMessage(Message)} 的时间（UTC）。 */
    Instant updatedAt();
}
