package io.oryxos.web.controller;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Test-only stub — {@code @WebMvcTest} needs a {@code @SpringBootConfiguration} anchor.
 *
 * <p>Real main class lives in {@code oryxos-boot} module; {@code oryxos-web} is library-only.
 * This stub satisfies the WebMvcTest bootstrap without dragging in the full app context.
 *
 * <p>{@code ComponentScan} picks up {@code GlobalExceptionHandler} so 4xx / 5xx flow into
 * the unified {@code ErrorResponse} mapper (per spec FR-006).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "io.oryxos.web")
public class StubBootApp {
}