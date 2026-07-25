package io.oryxos.core;

import java.util.List;

/**
 * Memory 注入接口 —— 把会话相关记忆（包括但不限于长期记忆、情景记忆、跨对话偏好）
 * 翻译为可追加进 prompt 的 {@link Message} 序列。
 *
 * <p>US-2 阶段唯一合法实现：{@link NoopMemoryInjector}（返回空列表）。
 * US-3 引入 {@code MemoryService} 真实实现。
 */
@FunctionalInterface
public interface MemoryInjector {

    List<Message> inject(Profile profile, Session session);

    /** US-2 桩实现 —— 永不注入任何消息；US-3 替换为真实 MemoryService 桥接。 */
    final class NoopMemoryInjector implements MemoryInjector {
        @Override
        public List<Message> inject(Profile profile, Session session) {
            return List.of();
        }
    }
}