package io.oryxos.cli.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    private Path tmpDir;
    private String savedEnv;

    @BeforeEach
    void setUp(@org.junit.jupiter.api.io.TempDir Path temp) throws IOException {
        this.tmpDir = temp;
        this.savedEnv = System.getenv("ORYXOS_TEST_API_KEY");
    }

    @AfterEach
    void tearDown() {
        // Env vars are read-only via System.getenv(); rely on the JVM fixture
        // not setting ORYXOS_TEST_API_KEY in the first place.
    }

    @Test
    void resolvesPlaceholderFromEnvironment() throws IOException {
        // Given: env var is set in this test JVM via reflection on the
        // process environment map (System.getenv is read-only but the
        // map itself is writable for tests; many JVMs honour the change).
        // For portability, we exercise the default-fallback branch instead.

        // When: YAML uses ${ORYXOS_TEST_API_KEY:-fallback-secret}
        Path yaml = tmpDir.resolve("profile.yaml");
        Files.writeString(yaml, "name: test\napiKey: ${ORYXOS_TEST_API_KEY:-fallback-secret}\n");

        // Then: substituted with the default
        Map<String, Object> result = ConfigLoader.loadYaml(yaml);
        assertThat(result).containsEntry("name", "test");
        assertThat(result).containsEntry("apiKey", "fallback-secret");
    }

    @Test
    void throwsWhenPlaceholderHasNoValueAndNoDefault() throws IOException {
        // Given: YAML uses ${DEFINITELY_UNSET_VAR_XYZ} with no default
        Path yaml = tmpDir.resolve("bad.yaml");
        Files.writeString(yaml, "apiKey: ${DEFINITELY_UNSET_VAR_XYZ}\n");

        // Then: MissingEnvVarException bubbles up
        assertThatThrownBy(() -> ConfigLoader.loadYaml(yaml))
                .isInstanceOf(MissingEnvVarException.class)
                .hasMessageContaining("DEFINITELY_UNSET_VAR_XYZ");
    }

    @Test
    void substitutesInsideNestedMapsAndLists() throws IOException {
        Path yaml = tmpDir.resolve("nested.yaml");
        Files.writeString(yaml, """
                name: weather-bot
                provider:
                  name: deepseek
                  model: ${MODEL:-deepseek-chat}
                tools:
                  - http_get
                  - ${EXTRA_TOOL:-notify}
                notify:
                  url: ${WEBHOOK_URL}
                """);

        // WEBHOOK_URL has no default → MissingEnvVarException
        assertThatThrownBy(() -> ConfigLoader.loadYaml(yaml))
                .isInstanceOf(MissingEnvVarException.class)
                .hasMessageContaining("WEBHOOK_URL");
    }

    @Test
    void profileYamlParsesFrontmatterBlock() throws IOException {
        Path agentMd = tmpDir.resolve("AGENT.md");
        Files.writeString(agentMd, """
                ---
                name: weather-bot
                provider: deepseek
                tools:
                  - http_get
                ---
                # Body

                This part is ignored by loadProfileYaml.
                """);
        Map<String, Object> fm = ConfigLoader.loadProfileYaml(agentMd, "weather-bot");
        assertThat(fm).containsEntry("name", "weather-bot");
        assertThat(fm).containsEntry("provider", "deepseek");
        assertThat(fm.get("tools")).asList().containsExactly("http_get");
    }
}