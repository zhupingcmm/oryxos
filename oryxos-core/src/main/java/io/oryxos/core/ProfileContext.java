package io.oryxos.core;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程局部上下文 —— 记录"当前正在处理哪个 Profile / Session / 迭代轮次"。
 *
 * <p>由 {@code AgentService.process(...)} 在入口 set、在 finally 块 clear（spec FR-017 / I-06）。
 * 循环层（{@code ReActLoop}）和工具实现（{@code OryxTool}）在执行期间读取；
 * 任何代码若在循环外部访问 {@link #current()} 会得到 {@link Optional#empty()} 而非抛异常。
 *
 * <p>详见 [contracts/ProfileContext.md](../../../../../specs/002-react-loop/contracts/ProfileContext.md)。
 */
public final class ProfileContext {

    /** 当前 Agent 的不可变快照（{@code currentIteration} 例外 —— 故意可变）。 */
    public record Snapshot(
        String profileName,
        UUID sessionId,
        AtomicInteger currentIteration
    ) {
        public Snapshot {
            Objects.requireNonNull(profileName, "profileName");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(currentIteration, "currentIteration");
        }
    }

    private static final ThreadLocal<Snapshot> CTX = new ThreadLocal<>();

    private ProfileContext() {
        // 静态工具类，禁止实例化
    }

    /**
     * 由 {@code AgentService.process} 唯一调用一次；其他模块禁止 set（违反 R-7）。
     *
     * @throws IllegalStateException 同一线程内 set 两次（表示遗漏 clear，潜在内存/语义泄漏）
     */
    public static void set(Snapshot s) {
        if (CTX.get() != null) {
            throw new IllegalStateException(
                "ProfileContext already set on this thread; clear() first to avoid leakage");
        }
        CTX.set(Objects.requireNonNull(s, "snapshot"));
    }

    /** 任何代码可读取；缺省时返回 {@link Optional#empty()} 而非抛异常（C-PC-3）。 */
    public static Optional<Snapshot> current() {
        return Optional.ofNullable(CTX.get());
    }

    /** 由 {@code AgentService.process} 的 finally 块调用一次（C-PC-2）。 */
    public static void clear() {
        CTX.remove();    // remove 而非 set(null) —— 释放 ThreadLocalMap entry 防止 OOM
    }
}
