package io.oryxos.core;

import io.oryxos.core.ProfileContext.Snapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-2 阶段基础契约测试 —— {@link ProfileContext} ThreadLocal 行为。
 *
 * <p>覆盖（来自 tasks.md T024 + [contracts/ProfileContext.md §5](../../../../../specs/002-react-loop/contracts/ProfileContext.md)）：
 * <ol>
 *   <li>setAndClear —— set 后 current() 有值；clear 后 current() 为 {@link Optional#empty()}</li>
 *   <li>doubleSetThrows —— 同线程连续两次 set 抛 {@link IllegalStateException}（C-PC-1）</li>
 *   <li>isolatedAcrossThreads —— 线程 A set "profile-A"，线程 B current() 是空（R-7 / SC-003）</li>
 *   <li>clearWithoutSetIsNoop —— 不 set 直接 clear 不抛异常（C-PC-3）</li>
 * </ol>
 */
@DisplayName("ProfileContext 线程局部契约")
class ProfileContextTest {

    @AfterEach
    void cleanup() {
        // 确保不影响后续测试
        ProfileContext.clear();
    }

    @Test
    @DisplayName("setAndClear：set 后 current() 有值，clear 后为 Optional.empty()")
    void setAndClear() {
        AtomicInteger iter = new AtomicInteger(0);
        Snapshot snap = new Snapshot("profile-A", UUID.randomUUID(), iter);

        ProfileContext.set(snap);
        Optional<Snapshot> read = ProfileContext.current();
        assertThat(read).isPresent();
        assertThat(read.get().profileName()).isEqualTo("profile-A");
        assertThat(read.get().currentIteration()).isSameAs(iter);

        ProfileContext.clear();
        assertThat(ProfileContext.current()).isEmpty();
    }

    @Test
    @DisplayName("doubleSetThrows：同线程连续两次 set 第二次抛 IllegalStateException（C-PC-1 / I-06）")
    void doubleSetThrows() {
        ProfileContext.set(new Snapshot("first", UUID.randomUUID(), new AtomicInteger(0)));

        assertThatThrownBy(() ->
            ProfileContext.set(new Snapshot("second", UUID.randomUUID(), new AtomicInteger(1)))
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already set");

        // 第一个 set 仍在
        assertThat(ProfileContext.current()).isPresent();
        assertThat(ProfileContext.current().get().profileName()).isEqualTo("first");
    }

    @Test
    @DisplayName("isolatedAcrossThreads：线程 A 的 set 对线程 B 不可见（SC-003 / R-7）")
    void isolatedAcrossThreads() throws InterruptedException {
        CountDownLatch aHasSet = new CountDownLatch(1);
        CountDownLatch bHasRead = new CountDownLatch(1);
        AtomicReference<String> bSaw = new AtomicReference<>();

        Thread a = new Thread(() -> {
            try {
                ProfileContext.set(new Snapshot("profile-A", UUID.randomUUID(), new AtomicInteger(0)));
                aHasSet.countDown();
                bHasRead.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                ProfileContext.clear();
            }
        }, "thread-A");

        Thread b = new Thread(() -> {
            try {
                aHasSet.await(2, TimeUnit.SECONDS);
                bSaw.set(ProfileContext.current().map(Snapshot::profileName).orElse("<empty>"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                bHasRead.countDown();
            }
        }, "thread-B");

        a.start();
        b.start();
        a.join(3000);
        b.join(3000);

        assertThat(bSaw.get())
            .as("Thread-B 在 Thread-A 设置 ProfileContext 后读取，必须看到的是 <empty>")
            .isEqualTo("<empty>");
    }

    @Test
    @DisplayName("clearWithoutSetIsNoop：未 set 直接 clear 不抛（C-PC-3 / ThreadLocalMap.remove 语义）")
    void clearWithoutSetIsNoop() {
        // 显式清理（哪怕没 set）不抛
        ProfileContext.clear();
        ProfileContext.clear();
        ProfileContext.clear();

        assertThat(ProfileContext.current()).isEmpty();
    }
}
