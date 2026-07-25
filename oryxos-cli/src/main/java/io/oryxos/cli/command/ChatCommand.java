package io.oryxos.cli.command;

import io.oryxos.cli.exitcode.Sysexits;
import io.oryxos.cli.spring.SpringContextHandle;
import io.oryxos.core.AgentService;
import io.oryxos.core.LoopResult;
import io.oryxos.core.Message;
import io.oryxos.core.Session;
import io.oryxos.storage.entity.SessionEntity;
import io.oryxos.storage.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code oryxos chat} — triggers an Agent run via {@link AgentService#process(Session, String)}
 * and prints the final response to stdout.
 *
 * <p>This is US-1 (P1, MVP) of the CLI module and shares its entry point
 * with {@code POST /api/v1/agents/{name}/invoke} (US-5) and the AgentScheduler
 * (cron) — all three feed into the same {@code AgentService.process()} method,
 * per FR-002 / FR-021.
 *
 * <h2>Behaviour</h2>
 * <ol>
 *   <li>Boots Spring (via {@link CommandSpringBase}) to obtain the
 *       {@link AgentService} and {@link SessionRepository} beans.</li>
 *   <li>Validates {@code <profile-name>} against the
 *       {@code ^[a-z][a-z0-9-]{0,63}$} regex (FR-015).</li>
 *   <li>Resolves the user message from {@code --message} or, if absent,
 *       reads a single line from stdin.</li>
 *   <li>Creates a fresh {@link SessionEntity} via the public factory
 *       {@link SessionEntity#create(UUID, String)}, persists it, then calls
 *       {@code agentService.process(session, message)}.</li>
 *   <li>Prints {@link LoopResult#finalText()} as a single line on stdout.
 *       Stack traces and errors go to stderr only (FR-010, FR-018).</li>
 * </ol>
 *
 * <h2>Exit codes (FR-009 / SC-007)</h2>
 * <table>
 *   <tr><th>Scenario</th><th>Exit</th></tr>
 *   <tr><td>Success</td><td>0</td></tr>
 *   <tr><td>Profile name regex / not found</td><td>64 (EX_USAGE)</td></tr>
 *   <tr><td>Profile YAML parse error</td><td>78 (EX_CONFIG)</td></tr>
 *   <tr><td>API key missing</td><td>69 (EX_UNAVAILABLE)</td></tr>
 *   <tr><td>Spring startup / LLM 4xx-5xx</td><td>1 (GENERIC)</td></tr>
 * </table>
 */
@CommandLine.Command(
        name = "chat",
        mixinStandardHelpOptions = true,
        description = "Trigger an Agent run for the given Profile and print the final response.")
@Component
public class ChatCommand extends CommandSpringBase {

    private static final Logger LOG = LoggerFactory.getLogger(ChatCommand.class);

    /** Profile name regex from FR-015. */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

    /**
     * The Spring {@code @SpringBootApplication} primary source. We pass it
     * as a fully-qualified name to {@link SpringContextHandle#boot(String, String[])}
     * so the CLI does not depend on the {@code oryxos-boot} module at compile
     * time (would otherwise be a cycle).
     */
    static final String PRIMARY_SOURCE = "io.oryxos.boot.OryxOsApplication";

    @CommandLine.Parameters(
            index = "0",
            paramLabel = "<profile-name>",
            description = "Profile name (must match ^[a-z][a-z0-9-]{0,63}$).")
    String profileName;

    @CommandLine.Option(
            names = {"-m", "--message"},
            description = "Single-turn user message. If omitted, read one line from stdin.")
    String message;

    @CommandLine.Option(
            names = {"-s", "--session-id"},
            description = "Resume an existing Session by id; otherwise a new one is created.")
    UUID sessionId;

    @Override
    protected Integer runBody() throws Exception {
        if (profileName == null || !NAME_PATTERN.matcher(profileName).matches()) {
            throw new IllegalArgumentException(
                    "Invalid profile name '" + profileName
                            + "' — must match ^[a-z][a-z0-9-]{0,63}$");
        }

        String userMessage = resolveUserMessage();
        if (userMessage.isEmpty()) {
            throw new IllegalArgumentException("User message must not be empty");
        }

        try (SpringContextHandle ctx = acquireContext(PRIMARY_SOURCE)) {
            AgentService agentService = bean(ctx, AgentService.class);
            SessionRepository sessionRepo = bean(ctx, SessionRepository.class);

            SessionEntity entity;
            if (sessionId != null) {
                entity = sessionRepo.findById(sessionId).orElseGet(() ->
                        SessionEntity.create(UUID.randomUUID(), profileName));
            } else {
                entity = SessionEntity.create(UUID.randomUUID(), profileName);
            }
            entity.appendMessage(Message.user(userMessage));
            Session session = sessionRepo.save(entity);

            LOG.info("chat.start profile={} session_id={}", profileName, session.id());
            LoopResult result = agentService.process(session, userMessage);
            LOG.info("chat.completed profile={} iterations={}",
                    profileName, result.iterations());

            // FR-002 / FR-010: stdout is the final text only — no prefix, no decoration.
            spec.commandLine().getOut().println(result.finalText());
            spec.commandLine().getOut().flush();
            return Sysexits.OK;
        }
    }

    /**
     * Resolve the user message from {@code --message} if present, else read
     * one line from stdin. Returns an empty string when stdin is EOF.
     */
    private String resolveUserMessage() {
        if (message != null) {
            return message;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line != null ? line : "";
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to read user message from stdin: " + e.getMessage(), e);
        }
    }
}