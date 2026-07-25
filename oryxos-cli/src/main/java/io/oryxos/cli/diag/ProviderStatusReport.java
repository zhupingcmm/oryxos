package io.oryxos.cli.diag;

import java.util.List;
import java.util.Objects;

/**
 * Pre-rendered Provider matrix row for {@code oryxos status} (US-2,
 * FR-004 / FR-020).
 *
 * <p>{@link StatusCommand} populates this without booting Spring by reading
 * {@code .oryxos/application.yaml} (via {@code ConfigLoader}) and looking up
 * each {@code credentialRef} against the live process environment. The
 * resolved API key is never stored on this record — only the boolean
 * {@link #apiKeyResolved} — so the value can be safely echoed to the user
 * or written to {@code oryxos-cli.log}.
 *
 * @param name             routing key (matches the {@code name} in application.yaml)
 * @param model            default model declared in application.yaml
 * @param credentialRef    env-var name declared in application.yaml (e.g. {@code DEEPSEEK_API_KEY})
 * @param apiKeyResolved   true iff the env var is set and non-empty in the live process
 */
public record ProviderStatusReport(
        String name,
        String model,
        String credentialRef,
        boolean apiKeyResolved) {

    public ProviderStatusReport {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(credentialRef, "credentialRef");
    }

    /** Compact one-line representation safe for stdout. */
    public String toTableRow() {
        return String.format("%-12s %-22s %-22s %s",
                name, model, credentialRef,
                apiKeyResolved ? "true" : "false");
    }

    /** Stable header for {@link #toTableRow()} output. */
    public static String tableHeader() {
        return String.format("%-12s %-22s %-22s %s",
                "NAME", "MODEL", "CREDENTIAL_REF", "API_KEY_RESOLVED");
    }

    /**
     * Convenience collector — given the {@code oryxos.providers.<name>}
     * sub-tree of a parsed {@code application.yaml}, return one row per
     * configured provider.
     *
     * @param providers map keyed by provider name; values are maps with
     *                  at least {@code model} and {@code credentialRef}
     * @return list of reports, one per provider, in iteration order
     */
    public static List<ProviderStatusReport> fromApplicationYaml(java.util.Map<String, Object> providers) {
        if (providers == null || providers.isEmpty()) {
            return List.of();
        }
        List<ProviderStatusReport> out = new java.util.ArrayList<>(providers.size());
        for (var entry : providers.entrySet()) {
            String name = entry.getKey();
            if (!(entry.getValue() instanceof java.util.Map<?, ?> cfg)) {
                continue;
            }
            String model = stringOf(cfg, "model");
            String credentialRef = stringOf(cfg, "credentialRef");
            boolean resolved = credentialRef != null
                    && !credentialRef.isEmpty()
                    && System.getenv(credentialRef) != null
                    && !System.getenv(credentialRef).isEmpty();
            out.add(new ProviderStatusReport(
                    name,
                    model == null ? "" : model,
                    credentialRef == null ? "" : credentialRef,
                    resolved));
        }
        return out;
    }

    private static String stringOf(java.util.Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }
}