package io.oryxos.cli.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.Objects;

/**
 * {@link AutoCloseable} wrapper around the {@link ConfigurableApplicationContext}
 * that the CLI boots when a Spring-required command runs
 * ({@code chat}, {@code provider list}, {@code tool list}, {@code session list}).
 *
 * <p>Closing this handle closes the underlying context, freeing all bean
 * instances. Commands acquire a handle via {@link #boot(Class, String[])} and
 * use try-with-resources so an exception in the command body still releases
 * the context.
 *
 * <p>The {@code WEB} application type is explicitly disabled to avoid the
 * embedded servlet container that Spring Boot would otherwise try to start
 * (we have no port to bind during a one-shot CLI invocation).
 *
 * <p>See {@code data-model.md §4} and {@code research.md} decision 2
 * (zero-Spring vs must-Spring startup paths).
 */
public final class SpringContextHandle implements AutoCloseable {

    private final ConfigurableApplicationContext context;
    private final long bootDurationMs;

    private SpringContextHandle(ConfigurableApplicationContext context, long bootDurationMs) {
        this.context = Objects.requireNonNull(context);
        this.bootDurationMs = bootDurationMs;
    }

    /**
     * Boot the Spring context for {@code primarySourceClassName} — the fully
     * qualified class name of the {@code @SpringBootApplication} (typically
     * {@code "io.oryxos.boot.OryxOsApplication"}).
     *
     * <p>The class is loaded via reflection so the CLI module does not have
     * to depend on the {@code oryxos-boot} module at compile time, avoiding
     * a circular dependency. The CLI passes the class name as a string and
     * Spring Boot instantiates the primary source via its own constructor
     * {@code SpringApplication(Class<?>...)} which already performs the
     * reflective load.
     *
     * <p>FR-013 requires &le; 5 s to first output for Spring-required
     * commands. The caller should arrange for the LLM / DB work to start
     * only after {@link #bootDurationMs()} returns.
     *
     * @throws IllegalArgumentException if the class cannot be resolved
     */
    public static SpringContextHandle boot(String primarySourceClassName, String[] args) {
        Objects.requireNonNull(primarySourceClassName, "primarySourceClassName");
        Class<?> primarySource;
        try {
            primarySource = Class.forName(primarySourceClassName);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    "Spring primary source class not found: " + primarySourceClassName, e);
        }
        long t0 = System.nanoTime();
        SpringApplication app = new SpringApplication(primarySource);
        app.setWebApplicationType(WebApplicationType.NONE);
        ConfigurableApplicationContext ctx = app.run(args);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        return new SpringContextHandle(ctx, elapsedMs);
    }

    /**
     * Convenience overload when the caller already holds a {@link Class}.
     * Equivalent to {@code boot(source.getName(), args)}.
     */
    public static SpringContextHandle boot(Class<?> primarySource, String[] args) {
        Objects.requireNonNull(primarySource, "primarySource");
        return boot(primarySource.getName(), args);
    }

    /** Underlying Spring context, for {@code ctx.getBean(...)} lookups. */
    public ConfigurableApplicationContext context() {
        return context;
    }

    /** Time taken to bring up the context (excludes command body work). */
    public long bootDurationMs() {
        return bootDurationMs;
    }

    /**
     * Test-only factory: wrap an externally-managed
     * {@link ConfigurableApplicationContext} (typically an
     * {@code AnnotationConfigApplicationContext} built inside a unit test)
     * so a {@code ChatCommand} subclass can override {@code acquireContext(...)}
     * and inject mocked beans (e.g. Mockito-stubbed {@code AgentService},
     * {@code SessionRepository}, {@code LlmCallRecordRepository}) without
     * paying the Spring Boot startup tax.
     *
     * <p>Used by {@code ChatCommandAuditGuardTest} to verify the
     * US1-AC3 / FR-018 fail-fast invariant: when {@code AgentService} throws
     * before any LLM call, the CLI must exit non-zero with a one-line stderr
     * message and <em>must not</em> have written an {@code llm_calls} row.
     */
    public static SpringContextHandle wrapForTesting(ConfigurableApplicationContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        return new SpringContextHandle(ctx, 0L);
    }

    @Override
    public void close() {
        context.close();
    }
}