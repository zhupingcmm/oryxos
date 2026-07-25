package io.oryxos.cli.exitcode;

/**
 * BSD sysexits exit-code constants — see FR-009 / SC-007.
 *
 * <p>Every {@code oryxos} subcommand maps its terminal outcome to one of these
 * values. The mapping lives in {@code CommandBase} (zero-Spring) and
 * {@code CommandSpringBase} (Spring-required) so commands return the symbolic
 * constant and the base classes translate it to the process exit code.
 *
 * <p>Reference: <a href="https://man.openbsd.org/sysexits">BSD sysexits(3)</a>.
 */
public final class Sysexits {

    /** Successful termination. */
    public static final int OK = 0;

    /** Generic failure (Spring startup, LLM 4xx/5xx, unhandled exception). */
    public static final int GENERIC = 1;

    /** Warning — command ran but a non-blocking condition was detected
     *  (e.g. {@code status} detected an un-resolved API key). */
    public static final int WARNING = 2;

    /** EX_USAGE — command-line usage error (unknown subcommand, missing
     *  required arg, profile does not exist, name regex mismatch). */
    public static final int EX_USAGE = 64;

    /** EX_UNAVAILABLE — a required service is unavailable (API key missing
     *  for a Profile that needs it, SQLite DB locked, etc.). */
    public static final int EX_UNAVAILABLE = 69;

    /** EX_CONFIG — configuration parse error (Profile YAML broken,
     *  {@code mcp_servers.yaml} schema invalid). */
    public static final int EX_CONFIG = 78;

    private Sysexits() {
        // utility holder
    }
}