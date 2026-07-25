package io.oryxos.cli.config;

/**
 * Thrown by {@link ConfigLoader} when a Profile YAML contains a
 * {@code ${ENV_VAR}} placeholder and the variable is not defined in the
 * process environment at load time.
 *
 * <p>CLI catches this and translates to exit code
 * {@link io.oryxos.cli.exitcode.Sysexits#EX_UNAVAILABLE} (69) for the
 * {@code chat} command (the variable was needed to dispatch to a Provider)
 * or {@link io.oryxos.cli.exitcode.Sysexits#EX_CONFIG} (78) for the
 * {@code init} / {@code status} commands (the variable was needed to render
 * the workspace state). See FR-014 / FR-020 / SC-007.
 */
public final class MissingEnvVarException extends RuntimeException {

    private final String envVarName;
    private final String profileName;

    public MissingEnvVarException(String envVarName, String profileName) {
        super("API key missing: environment variable `" + envVarName
                + "` is not set" + (profileName == null ? "" : " (profile `" + profileName + "`)"));
        this.envVarName = envVarName;
        this.profileName = profileName;
    }

    public String envVarName() {
        return envVarName;
    }

    public String profileName() {
        return profileName;
    }
}