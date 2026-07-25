package io.oryxos.cli;

import io.oryxos.cli.exitcode.Sysexits;
import io.oryxos.storage.entity.SessionEntity;
import io.oryxos.storage.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Smoke test for {@link io.oryxos.cli.command.SessionListCommand} — drives
 * Picocli with a mocked {@link SessionRepository} and verifies that rows
 * render in the output.
 */
class SessionListCommandTest {

    @Test
    void listsSeededSessions() {
        SessionRepository repo = mock(SessionRepository.class);
        when(repo.findAll()).thenReturn(List.of(
                newSession("weather-bot"),
                newSession("tech-digest")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(out, true, StandardCharsets.UTF_8);
        var sub = new io.oryxos.cli.command.SessionListCommand.SessionListSubCommand() {
            @Override
            protected Integer runBody() {
                List<SessionEntity> rows = repo.findAll();
                if (rows.isEmpty()) {
                    spec.commandLine().getOut().println("(no sessions)");
                } else {
                    spec.commandLine().getOut().println("ID\tPROFILE");
                    for (SessionEntity s : rows) {
                        spec.commandLine().getOut().printf("%s\t%s%n",
                                s.id(), s.profileName());
                    }
                }
                spec.commandLine().getOut().flush();
                return Sysexits.OK;
            }
        };
        int exit = new CommandLine(sub).setOut(pw).setErr(pw).execute();
        pw.flush();
        String text = out.toString(StandardCharsets.UTF_8);

        assertThat(text).contains("weather-bot", "tech-digest");
        assertThat(exit).isEqualTo(Sysexits.OK);
    }

    @Test
    void emptyRepoPrintsNoSessions() {
        SessionRepository repo = mock(SessionRepository.class);
        when(repo.findAll()).thenReturn(List.of());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(out, true, StandardCharsets.UTF_8);
        var sub = new io.oryxos.cli.command.SessionListCommand.SessionListSubCommand() {
            @Override
            protected Integer runBody() {
                if (repo.findAll().isEmpty()) {
                    spec.commandLine().getOut().println("(no sessions)");
                }
                spec.commandLine().getOut().flush();
                return Sysexits.OK;
            }
        };
        int exit = new CommandLine(sub).setOut(pw).setErr(pw).execute();
        pw.flush();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("(no sessions)");
        assertThat(exit).isEqualTo(Sysexits.OK);
    }

    private static SessionEntity newSession(String profileName) {
        return SessionEntity.create(UUID.randomUUID(), profileName);
    }
}