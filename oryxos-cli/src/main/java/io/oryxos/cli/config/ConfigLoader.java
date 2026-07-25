package io.oryxos.cli.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SnakeYAML-backed Profile / config loader that resolves
 * {@code ${ENV_VAR}} placeholders against the live process environment.
 *
 * <p>Two loading modes are exposed:
 * <ul>
 *   <li>{@link #loadYaml(Path)} — for arbitrary YAML files where the result
 *       is a generic {@code Map<String,Object>} (used by {@code status} for
 *       {@code .oryxos/application.yaml}).</li>
 *   <li>{@link #loadProfileYaml(Path, String)} — for the Profile frontmatter,
 *       which is the YAML block delimited by {@code ---} at the head of an
 *       {@code AGENT.md} file.</li>
 * </ul>
 *
 * <p>Placeholder grammar:
 * <pre>
 *   ${NAME}            — required, throws {@link MissingEnvVarException} if not set
 *   ${NAME:-default}   — uses "default" when NAME is unset or empty (FR-014)
 * </pre>
 *
 * <p>This class is intentionally JVM-only (no Spring beans); it is reused by
 * both the zero-Spring commands ({@code init}, {@code profile *}, {@code status})
 * and the Spring-required commands ({@code chat}) via {@link CommandSpringBase}.
 *
 * <p>See FR-014 (ConfigLoader + {@code ${ENV_VAR}}) and
 * {@code research.md} decision 4 (ConfigLoader owns substitution).
 */
public final class ConfigLoader {

    /** Matches {@code ${NAME}} or {@code ${NAME:-default}}. */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)(?::-([^}]*))?}");

    private static final Yaml YAML = new Yaml();

    private ConfigLoader() {
        // utility holder
    }

    /**
     * Load a YAML file and return its document as a {@code Map<String,Object>}.
     * Environment-variable placeholders are substituted in every scalar leaf.
     *
     * @throws IOException               if the file cannot be read
     * @throws MissingEnvVarException    if any placeholder has no env var
     *                                    and no default
     */
    public static Map<String, Object> loadYaml(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        try (Reader reader = Files.newBufferedReader(file)) {
            Object root = YAML.load(reader);
            if (root == null) {
                return new LinkedHashMap<>();
            }
            if (!(root instanceof Map<?, ?> rootMap)) {
                throw new IllegalStateException(
                        "YAML root in " + file + " is not a mapping: " + root.getClass());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) rootMap;
            return substituteDeep(typed, null, file);
        }
    }

    /**
     * Load a Profile's frontmatter block from an {@code AGENT.md} file.
     *
     * <p>The file is expected to begin with a {@code ---}-delimited YAML block;
     * everything after the closing {@code ---} is the agent body and is NOT
     * returned by this method (callers can read the body separately).
     *
     * @throws IOException               if the file cannot be read
     * @throws MissingEnvVarException    on unresolvable placeholders
     */
    public static Map<String, Object> loadProfileYaml(Path agentMd, String profileName)
            throws IOException {
        Objects.requireNonNull(agentMd, "agentMd");
        String content = Files.readString(agentMd);
        int firstDash = content.indexOf("---");
        if (firstDash != 0) {
            throw new IllegalStateException(
                    "AGENT.md at " + agentMd + " must start with a YAML frontmatter '---'");
        }
        int secondDash = content.indexOf("---", 3);
        if (secondDash < 0) {
            throw new IllegalStateException(
                    "AGENT.md at " + agentMd + " has unterminated YAML frontmatter");
        }
        String frontmatter = content.substring(3, secondDash);
        Object root = YAML.load(frontmatter);
        if (root == null) {
            return new LinkedHashMap<>();
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new IllegalStateException(
                    "Profile frontmatter in " + agentMd + " is not a mapping");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) rootMap;
        return substituteDeep(typed, profileName, agentMd);
    }

    // --- internals ---------------------------------------------------------

    private static Map<String, Object> substituteDeep(
            Map<String, Object> map, String profileName, Path source) {
        Map<String, Object> result = new LinkedHashMap<>(map.size());
        for (Map.Entry<String, Object> e : map.entrySet()) {
            result.put(e.getKey(), substitute(e.getValue(), profileName, source));
        }
        return result;
    }

    private static Object substitute(Object value, String profileName, Path source) {
        if (value instanceof String s) {
            return resolveString(s, profileName);
        }
        if (value instanceof Map<?, ?> nested) {
            return substituteDeep(toStringMap(nested), profileName, source);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> substitute(item, profileName, source))
                    .toList();
        }
        return value;
    }

    private static String resolveString(String input, String profileName) {
        Matcher m = PLACEHOLDER.matcher(input);
        if (!m.find()) {
            return input;
        }
        // reset + replace loop
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String def = m.group(2);
            String envValue = System.getenv(name);
            String resolved = (envValue != null && !envValue.isEmpty())
                    ? envValue
                    : (def != null ? def : null);
            if (resolved == null) {
                throw new MissingEnvVarException(name, profileName);
            }
            // Escape regex replacement chars in `resolved`.
            m.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Map<String, Object> toStringMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>(raw.size());
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw new IllegalStateException(
                        "YAML keys must be strings; got " + e.getKey());
            }
            out.put(key, e.getValue());
        }
        return out;
    }
}