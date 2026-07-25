package io.oryxos.cli.command;

import io.oryxos.cli.exitcode.Sysexits;
import io.oryxos.cli.spring.SpringContextHandle;
import io.oryxos.storage.entity.SessionEntity;
import io.oryxos.storage.repository.SessionRepository;
import picocli.CommandLine;

import java.util.List;

/**
 * {@code oryxos session list} — enumerate Session rows from SQLite.
 *
 * <p>Must-Spring (FR-012). Reads {@link SessionRepository} which is backed by
 * the JPA {@code sessions} table populated by US-2 (ReAct loop) and US-5
 * (Web Service). Without Spring-boot, this command cannot access the DB.
 *
 * <p>Columns: {@code ID | PROFILE | UPDATED_AT | MESSAGE_COUNT}.
 */
@CommandLine.Command(
        name = "session",
        mixinStandardHelpOptions = true,
        description = "Inspect persisted Sessions (Spring-boot, reads SessionRepository).",
        subcommands = { SessionListCommand.SessionListSubCommand.class })
public class SessionListCommand extends CommandSpringBase {

    @Override
    protected Integer runBody() {
        spec.commandLine().getOut().println("Use 'oryxos session list' to list sessions.");
        return Sysexits.OK;
    }

    @CommandLine.Command(
            name = "list",
            mixinStandardHelpOptions = true,
            description = "List persisted Sessions, newest first.")
    public static class SessionListSubCommand extends CommandSpringBase {

        static final String PRIMARY_SOURCE = "io.oryxos.boot.OryxOsApplication";

        @CommandLine.Option(
                names = {"-p", "--profile"},
                description = "Filter by Profile name.")
        String profileName;

        @CommandLine.Option(
                names = {"--limit"},
                defaultValue = "50",
                description = "Maximum number of rows to print (default: ${DEFAULT-VALUE}).")
        int limit;

        @Override
        protected Integer runBody() {
            try (SpringContextHandle ctx = acquireContext(PRIMARY_SOURCE)) {
                SessionRepository repo = ctx.context().getBean(SessionRepository.class);
                List<SessionEntity> sessions = (profileName != null)
                        ? repo.findByProfileNameOrderByUpdatedAtDesc(profileName)
                        : repo.findAll();
                if (sessions.isEmpty()) {
                    spec.commandLine().getOut().println("(no sessions)");
                } else {
                    spec.commandLine().getOut().println(
                            "ID\tPROFILE\tUPDATED_AT\tMESSAGE_COUNT");
                    int count = 0;
                    for (SessionEntity s : sessions) {
                        if (count++ >= limit) {
                            break;
                        }
                        spec.commandLine().getOut().printf("%s\t%s\t%s\t%d%n",
                                s.id(),
                                nullSafe(s.profileName()),
                                nullSafe(s.updatedAt() == null ? "" : s.updatedAt().toString()),
                                s.messages() == null ? 0 : s.messages().size());
                    }
                }
                spec.commandLine().getOut().flush();
            }
            return Sysexits.OK;
        }

        private static String nullSafe(String s) {
            return s == null ? "" : s;
        }
    }
}