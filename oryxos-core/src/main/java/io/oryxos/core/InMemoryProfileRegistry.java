package io.oryxos.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Test + lightweight prod {@link ProfileRegistry} implementation: registers all Profiles
 * at construction time, then read-only.
 *
 * <p>US-2 self-tests use this implementation. Production (US-5) replaces it with
 * {@code FilesystemProfileRegistry} or {@code JpaProfileRegistry}.
 *
 * <p>Thread-safe: internal Map is filled once at construction and the reference is immutable.
 * {@link #find} / {@link #names} are read-only operations; safe for Spring singleton scope (FR-018).
 */
public final class InMemoryProfileRegistry implements ProfileRegistry {

    private final Map<String, Profile> byName;

    /** Constructor from mutable Map (defensive copy). */
    public InMemoryProfileRegistry(Map<String, Profile> profiles) {
        Objects.requireNonNull(profiles, "profiles");
        Map<String, Profile> copy = new HashMap<>();
        for (Map.Entry<String, Profile> e : profiles.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                throw new IllegalArgumentException("profile entries must not be null");
            }
            copy.put(e.getKey(), e.getValue());
        }
        this.byName = Collections.unmodifiableMap(copy);
    }

    /** Constructor that indexes by {@link Profile#name()} for convenience. */
    public static InMemoryProfileRegistry of(Profile... profiles) {
        Objects.requireNonNull(profiles, "profiles");
        Map<String, Profile> map = new HashMap<>();
        for (Profile p : profiles) {
            if (p == null) throw new IllegalArgumentException("profile must not be null");
            map.put(p.name(), p);
        }
        return new InMemoryProfileRegistry(map);
    }

    @Override
    public Optional<Profile> find(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(byName.get(name));
    }

    @Override
    public Set<String> names() {
        return new HashSet<>(byName.keySet());
    }
}