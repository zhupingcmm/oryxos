package io.oryxos.memory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 会话层管理器（006-memory-layer spec FR-002 + CLAUDE.md §9.6）。
 *
 * <p>职责：
 * <ul>
 *   <li>管理当前 Session 的对话消息（{@link Message} 列表 —— {@code role} + {@code content}）</li>
 *   <li>生命周期跟随 Session —— Session 创建 → SessionManager 持有该 Session 的消息；Session 结束 → 随 Session 释放</li>
 *   <li>提供 {@link #getMessages()} / {@link #addMessage(Message)} 给 ReAct 循环使用</li>
 * </ul>
 *
 * <p>边界（spec FR-002 / CLAUDE.md §9.6 契约 ②）：
 * <ul>
 *   <li>**不**暴露 {@link io.oryxos.memory.backend.LongTermMemoryStore} 引用 —— 避免会话层误调长期层</li>
 *   <li>**不**持久化对话消息到 SQLite —— 会话消息由 {@code oryxos-storage} 的 SessionRepository 负责（spec FR-002）</li>
 *   <li>**不**触发任何形式的"会话结束 → save 到长期" 副作用 —— Agent 必须在 ReAct 循环中显式调 {@code save_memory} Tool</li>
 * </ul>
 *
 * <p>{@link Message} 用 {@code role} + {@code content} 简单结构 —— 避免耦合具体 LLM SDK（Spring AI ChatMessage / LangChain4j
 * HumanMessage 等）的类路径。
 *
 * <p>详见 [specs/006-memory-layer/contracts/memory-service.md §4](../specs/006-memory-layer/contracts/memory-service.md)。
 */
public class SessionManager {

    /**
     * 单条对话消息 —— role 取值 {@code user} / {@code assistant} / {@code system} / {@code tool}。
     * 此处仅约定字符串，不绑定特定 LLM SDK。
     */
    public record Message(String role, String content) {
        public Message {
            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException("role must not be blank");
            }
            if (content == null) {
                throw new IllegalArgumentException("content must not be null");
            }
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }

        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message tool(String content) {
            return new Message("tool", content);
        }

        /** 兼容 Map.of("role", role, "content", content) 调用形式。 */
        public Map<String, String> toMap() {
            return Map.of("role", role, "content", content);
        }
    }

    /** 当前 Session 的对话消息 —— {@link CopyOnWriteArrayList} 保证并发读安全。 */
    private final List<Message> messages = new CopyOnWriteArrayList<>();

    /** 当前 Session 的 ID（用于 debug 日志；不影响逻辑）。 */
    private final String sessionId;

    public SessionManager(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        this.sessionId = sessionId;
    }

    /** 取得当前 Session 的对话消息（不可变快照）。 */
    public List<Message> getMessages() {
        return List.copyOf(messages);
    }

    /** 追加一条消息到当前 Session。 */
    public void addMessage(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        messages.add(message);
    }

    /** 当前消息条数（debug / 测试用）。 */
    public int size() {
        return messages.size();
    }

    /** 当前 Session ID（供 debug / 日志使用）。 */
    public String getSessionId() {
        return sessionId;
    }
}