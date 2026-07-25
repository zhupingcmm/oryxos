package io.oryxos.cli;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-017 / FR-018 — {@code oryxos-cli/src/main/resources/logback.xml} must
 * parse without XML errors <em>and</em> the {@code ORYXOS_CLI} /
 * {@code ORYXOS_CLI_ERROR} FileAppenders must actually be attached to the
 * {@code io.oryxos.cli} logger so structured logs land at
 * {@code .oryxos/logs/}.
 *
 * <p>This test guards against a regression observed in 2026-07-25:
 * an XML comment in {@code logback.xml} contained {@code --version},
 * which Logback's SAX parser rejected with "注释中不允许出现字符串 --".
 * The whole {@code <configuration>} failed to load; both FileAppenders
 * were silently skipped and logs fell through to the {@code STDOUT_FALLBACK}
 * ConsoleAppender. Tests passed (they only asserted on stderr), but
 * FR-017 / FR-018's file-destination contract was violated at runtime.
 *
 * <p>Approach: use a fresh {@link LoggerContext} so this test cannot
 * poison the rest of the suite. Load {@code logback.xml} via
 * {@link JoranConfigurator} and assert (1) no {@code XML_PARSING} status
 * is recorded and (2) both file appenders are registered on the
 * {@code io.oryxos.cli} logger.
 */
class LogbackConfigParsesTest {

    @Test
    void logbackXmlParsesCleanlyAndRegistersFileAppenders() throws JoranException {
        LoggerContext testCtx = new LoggerContext();
        testCtx.setName("test-LogbackConfigParsesTest");

        URL configUrl = Thread.currentThread().getContextClassLoader().getResource("logback.xml");
        assertThat(configUrl)
                .as("logback.xml must be on the test classpath")
                .isNotNull();

        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(testCtx);
        configurator.doConfigure(configUrl);

        // (1) No XML_PARSING error recorded. The previous regression produced
        //     "XML_PARSING - Parsing fatal error on line 15 and column 80;
        //     注释中不允许出现字符串 "--".".
        List<Status> statuses = testCtx.getStatusManager().getCopyOfStatusList();
        boolean hasXmlError = statuses.stream()
                .anyMatch(s -> s.getMessage() != null
                        && (s.getMessage().contains("XML_PARSING")
                                || s.getMessage().contains("注释中不允许出现字符串")));
        if (hasXmlError) {
            // Surface the full status dump in the test failure log so the
            // regression message is clear (non-deprecated path — iterate
            // StatusManager directly rather than call StatusPrinter which
            // is deprecated as of Logback 1.4).
            statuses.forEach(s -> System.err.println(s));
        }
        assertThat(hasXmlError)
                .as("logback.xml must not contain XML parse errors (no '--' inside XML comments)")
                .isFalse();

        // (2) Both file appenders must be attached to the io.oryxos.cli logger.
        //     <logger name="io.oryxos.cli" ...><appender-ref ref="ORYXOS_CLI"/> ...
        ch.qos.logback.classic.Logger cliLogger = testCtx.getLogger("io.oryxos.cli");
        assertThat(cliLogger.getAppender("ORYXOS_CLI"))
                .as("ORYXOS_CLI FileAppender must be attached (FR-017 — structured logs to .oryxos/logs/oryxos-cli.log)")
                .isNotNull();
        assertThat(cliLogger.getAppender("ORYXOS_CLI_ERROR"))
                .as("ORYXOS_CLI_ERROR FileAppender must be attached (FR-018 — stack traces to .oryxos/logs/oryxos-cli-error.log)")
                .isNotNull();

        testCtx.stop();
    }
}