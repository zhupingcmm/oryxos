package io.oryxos.core.testing;

import io.oryxos.core.Message;
import io.oryxos.core.Session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link Session} 的纯内存实现 —— 测试用，不写库。
 *
 * <p>构造参数：
 * <ul>
 *   <li>{@code id} —— 会话 UUID</li>
 *   <li>{@code profileName} —— 关联 Profile</li>
 * </ul>
 *
 * <p>线程安全：用 {@link CopyOnWriteArrayList} 存消息，append 安全；其他方法非并发优化。
 */
public final class InMemorySession implements Session {

    private final UUID id;
    private final String profileName;
    private final Instant createdAt;
    private final List<Message> messages;
    private volatile Instant updatedAt;

    public InMemorySession(UUID id, String profileName) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("profileName must not be blank");
        }
        this.id = id;
        this.profileName = profileName;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.messages = new CopyOnWriteArrayList<>();
    }

    @Override public UUID id()            { return id; }
    @Override public String profileName() { return profileName; }
    @Override public Instant createdAt()  { return createdAt; }
    @Override public Instant updatedAt()  { return updatedAt; }

    @Override
    public List<Message> messages() {
        return List.copyOf(messages);
    }

    @Override
    public void appendMessage(Message m) {
        if (m == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        messages.add(m);
        this.updatedAt = Instant.now();
    }

    /** 当前消息真实大小（测试断言用）；不是接口方法。 */
    public int size() {
        return messages.size();
    }

    /** 测试断言用 —— 取某索引消息（用于精确顺序检查）。 */
    public Message messageAt(int idx) {
        return messages.get(idx);
    }

    /** 测试断言用 —— 暴露内部 list 之外仍可枚举的入口（不可修改引用）。 */
    public List<Message> snapshot() {
        return new ArrayList<>(messages);
    }
}
