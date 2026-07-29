package io.oryxos.web.util;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * T010 — 持有 Spring Boot 启动时刻;{@code HealthDto.uptimeMs} / {@code InfoDto.uptimeMs} 数据源.
 *
 * <p>{@code @Component} 在 Spring 容器启动时实例化 → {@code startupInstant} 锁定.
 */
@Component
public class StartupInfoHolder {

    private final Instant startupInstant = Instant.now();

    /** 启动到现在的毫秒数. */
    public long uptimeMs() {
        return java.time.Duration.between(startupInstant, Instant.now()).toMillis();
    }

    /** 启动时刻 (UTC);用于测试断言. */
    public Instant startupInstant() {
        return startupInstant;
    }
}