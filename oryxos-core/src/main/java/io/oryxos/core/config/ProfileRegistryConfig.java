package io.oryxos.core.config;

import io.oryxos.core.InMemoryProfileRegistry;
import io.oryxos.core.Profile;
import io.oryxos.core.ProfileRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * US-2 production wiring for {@link ProfileRegistry}.
 *
 * <p>Registers an empty {@link InMemoryProfileRegistry} bean so that {@code DefaultAgentService}
 * has a non-null registry dependency in core-stage Spring contexts. This is intentionally
 * empty -- US-5 (005-web-service) replaces it with one of:
 * <ul>
 *   <li>{@code FilesystemProfileRegistry} -- scans {@code .oryxos/agents/* /AGENT.md} at startup
 *   <li>{@code JpaProfileRegistry} -- reads from a SQLite {@code profiles} table
 * </ul>
 *
 * <h2>Why a real Bean (not just an empty one)</h2>
 * Without this Bean, a production Spring context (e.g. {@code oryxos-web}) would fail to
 * bootstrap {@code DefaultAgentService} since {@code @Autowired} on a missing bean would
 * surface as {@code NoSuchBeanDefinitionException} at runtime, not at startup.
 *
 * <p>The empty registry means production calls to {@code AgentService.process(...)} in core
 * stage will throw {@code IllegalArgumentException("Unknown profile: ...")}. That is correct:
 * US-2 doesn't ship AGENT.md loading. CLI/Web/Scheduler that try to invoke agents in core
 * stage will get a clean "Unknown profile" error rather than a silent null deref.
 *
 * <h2>Future US-5 swap</h2>
 * Mark the new {@code FilesystemProfileRegistry} bean as {@code @Primary} and tag this class
 * {@code @ConditionalOnMissingBean(ProfileRegistry.class)}; the swap is a single-line change.
 */
@Configuration
public class ProfileRegistryConfig {

    /**
     * Placeholder production Bean: empty in-memory registry.
     *
     * <p>Optionally pre-seeded from a YAML list under {@code oryxos.profiles} (extension
     * wiring -- not used in US-2).
     */
    @Bean
    @Primary
    public ProfileRegistry profileRegistry(
        @Value("${oryxos.profiles:#{null}}") List<String> unusedYamlHint
    ) {
        // US-2: empty registry by design. CLI/Web/Scheduler that try to invoke agents
        // in core stage will get a clean "Unknown profile" error rather than a silent null deref.
        // US-5 swaps this bean for FilesystemProfileRegistry (or JpaProfileRegistry).
        return InMemoryProfileRegistry.of(new Profile[0]);
    }
}