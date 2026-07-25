package io.oryxos.cli;

import io.oryxos.cli.command.ChatCommand;
import io.oryxos.cli.exitcode.Sysexits;
import io.oryxos.cli.spring.SpringContextHandle;
import io.oryxos.core.AgentService;
import io.oryxos.core.LoopResult;
import io.oryxos.core.Session;
import io.oryxos.storage.entity.SessionEntity;
import io.oryxos.storage.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import picocli.CommandLine;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EC-3 (spec.md §"边界情况") — concurrent {@code chat} invocations must not
 * deadlock and must produce independent session IDs.
 *
 * <p>The spec invariant is: {@code oryxos chat} boots one Spring Context per
 * process invocation; two concurrent invocations therefore each get a fresh
 * context. We simulate this by running two {@link ChatCommand} instances
 * through the {@code CommandLine} facade in two threads with separate
 * {@link SpringContextHandle}s wrapping separate
 * {@link AnnotationConfigApplicationContext}s — i.e. the same shape the OS
 * gives two CLI processes, but in-process for testability.
 *
 * <p>What we deliberately do NOT test here: OS-level process contention
 * (CPU/IO), picocli parsing concurrency, or shared static-state races
 * inside the JVM (those are the OS scheduler's job; see
 * {@code scripts/cli-smoke.sh} for the cross-platform equivalent).
 */
class ConcurrentChatTest {

    @Test
    void twoChatInvocationsInParallel_bothSucceedWithDistinctSessionIds() throws Exception {
        // Two distinct AgentService mocks → two distinct sessions → two
        // distinct (mock) SessionRepository.save() return values.
        SessionEntity savedA = SessionEntity.create(
                UUID.fromString("00000000-0000-0000-0000-0000000000B1"), "weather-bot");
        SessionEntity savedB = SessionEntity.create(
                UUID.fromString("00000000-0000-0000-0000-0000000000B2"), "weather-bot");

        SessionRepository repoA = Mockito.mock(SessionRepository.class);
        SessionRepository repoB = Mockito.mock(SessionRepository.class);
        Mockito.when(repoA.save(Mockito.any(SessionEntity.class))).thenReturn(savedA);
        Mockito.when(repoB.save(Mockito.any(SessionEntity.class))).thenReturn(savedB);

        AgentService agentA = Mockito.mock(AgentService.class);
        AgentService agentB = Mockito.mock(AgentService.class);
        Mockito.when(agentA.process(Mockito.any(Session.class), Mockito.anyString()))
                .thenReturn(new LoopResult("hello from A", 1, false, "weather-bot", savedA.id()));
        Mockito.when(agentB.process(Mockito.any(Session.class), Mockito.anyString()))
                .thenReturn(new LoopResult("hello from B", 1, false, "weather-bot", savedB.id()));

        AnnotationConfigApplicationContext ctxA = new AnnotationConfigApplicationContext();
        ctxA.getBeanFactory().registerSingleton("agentService", agentA);
        ctxA.getBeanFactory().registerSingleton("sessionRepository", repoA);
        ctxA.refresh();
        AnnotationConfigApplicationContext ctxB = new AnnotationConfigApplicationContext();
        ctxB.getBeanFactory().registerSingleton("agentService", agentB);
        ctxB.getBeanFactory().registerSingleton("sessionRepository", repoB);
        ctxB.refresh();

        try (SpringContextHandle handleA = SpringContextHandle.wrapForTesting(ctxA);
             SpringContextHandle handleB = SpringContextHandle.wrapForTesting(ctxB)) {

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger exits = new AtomicInteger(0);
            String[] errs = new String[2];

            // Submit two chat invocations that wait on `go` so they start
            // truly simultaneously (race window is the time between latch
            // countdown and runBody() reaching agentService.process).
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    int rc = runChat(handleA, "weather-bot", "ping-A", 0);
                    if (rc == Sysexits.OK) exits.incrementAndGet();
                } catch (Exception e) {
                    errs[0] = e.toString();
                }
            });
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    int rc = runChat(handleB, "weather-bot", "ping-B", 1);
                    if (rc == Sysexits.OK) exits.incrementAndGet();
                } catch (Exception e) {
                    errs[1] = e.toString();
                }
            });

            // Wait for both threads to be ready, then release.
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            // Both invocations succeeded (no deadlock, no shared-state corruption).
            assertThat(exits.get())
                    .as("both concurrent chats exited OK; errors=" + String.join("|", errs))
                    .isEqualTo(2);

            // Each AgentService received exactly one process() call with its own message.
            Mockito.verify(agentA).process(Mockito.any(Session.class), Mockito.eq("ping-A"));
            Mockito.verify(agentB).process(Mockito.any(Session.class), Mockito.eq("ping-B"));
        }
    }

    private static int runChat(SpringContextHandle handle, String profile, String msg,
                               int slot) throws Exception {
        ChatCommand cmd = new ChatCommand() {
            @Override
            protected SpringContextHandle acquireContext(String primarySourceClassName) {
                return handle;
            }
        };
        CommandLine cl = new CommandLine(cmd);
        Field specField = findField(cmd.getClass(), "spec");
        specField.setAccessible(true);
        specField.set(cmd, cl.getCommandSpec());
        Field profileField = findField(cmd.getClass(), "profileName");
        profileField.setAccessible(true);
        profileField.set(cmd, profile);
        Field msgField = findField(cmd.getClass(), "message");
        msgField.setAccessible(true);
        msgField.set(cmd, msg);

        // Capture stderr to a per-thread sink so we can read it back if needed.
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        cl.setOut(new java.io.PrintWriter(baos, true, StandardCharsets.UTF_8));
        cl.setErr(new java.io.PrintWriter(baos, true, StandardCharsets.UTF_8));
        return cmd.call();
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        while (cls != null) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}