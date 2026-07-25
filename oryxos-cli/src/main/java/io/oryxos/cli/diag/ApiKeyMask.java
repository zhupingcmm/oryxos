package io.oryxos.cli.diag;

/**
 * Mask an API key for {@code oryxos status --verbose} display (FR-020).
 *
 * <p>FR-020 contract: by default API keys MUST NOT appear in stdout / log
 * output. When an operator passes {@code --verbose}, they may see the
 * first four characters followed by {@code "..."} as a debugging aid
 * (e.g. "is the env var pointing at the right project?").
 *
 * <p>This helper is a pure function so it can be unit-tested without
 * standing up a real env var or workspace:
 * <ul>
 *   <li>{@code null} / empty → {@code "<empty>"}</li>
 *   <li>Shorter than 4 chars → {@code "<short>"} (key was nearly empty)</li>
 *   <li>≥ 4 chars → {@code firstFour + "..."} (e.g. {@code "sk-1..."})</li>
 * </ul>
 *
 * <p>It NEVER echoes the full key. It NEVER logs. It does no I/O.
 */
public final class ApiKeyMask {

    private ApiKeyMask() {
        // utility class
    }

    public static String mask(String rawKey) {
        if (rawKey == null || rawKey.isEmpty()) {
            return "<empty>";
        }
        if (rawKey.length() < 4) {
            return "<short>";
        }
        return rawKey.substring(0, 4) + "...";
    }
}