package io.oryxos.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * OryxOS Spring Boot bootstrap.
 *
 * <p>This is the main entry point of the entire project. Once implementation progresses
 * (US-1 onwards), every {@code @Component}, {@code @Service}, {@code @Configuration},
 * and {@code @RestController} declared under {@code io.oryxos.*} will be auto-discovered
 * by the scan below.
 *
 * <p>Run with: {@code java -jar oryxos.jar} (the fat JAR produced by
 * {@code mvn package} on the {@code oryxos-boot} module).
 */
@SpringBootApplication
@ComponentScan(basePackages = "io.oryxos")
public class OryxOsApplication {

    public static void main(String[] args) {
        SpringApplication.run(OryxOsApplication.class, args);
    }
}