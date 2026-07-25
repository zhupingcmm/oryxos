package io.oryxos.core;

import java.util.Optional;
import java.util.Set;

/**
 * US-2 / US-5 stage: Agent Profile registry abstraction.
 *
 * <p>{@link AgentService#process(Session, String)} uses this interface to look up the Session's
 * referenced Profile. Three trigger sources (CLI / Web / Scheduler) share one lookup path,
 * implementing FR-001 / FR-021 plus C-AS-3.
 *
 * <h2>Tiers</h2>
 * <ul>
 *   <li>Core stage: {@link InMemoryProfileRegistry} (testing + light prod use)</li>
 *   <li>Extension: {@code FilesystemProfileRegistry} (startup scan of {@code .oryxos/agents/* /AGENT.md})
 *       or {@code JpaProfileRegistry} (US-5: SQLite {@code profiles} table)</li>
 * </ul>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #find(String)} MUST return {@link Optional#empty()} for unknown names (caller decides to throw)</li>
 *   <li>{@link #names()} returns an immutable snapshot of all registered Profile names</li>
 *   <li>Lookup MUST be thread-safe (concurrent Session processing per SC-003)</li>
 * </ul>
 */
public interface ProfileRegistry {

    /**
     * Look up a Profile by name.
     *
     * @param name Profile name (must match {@code ^[a-z][a-z0-9-]{0,63}$} -- validation is the caller's job)
     * @return Optional, empty if not found
     */
    Optional<Profile> find(String name);

    /** All registered Profile names (immutable snapshot). */
    Set<String> names();
}