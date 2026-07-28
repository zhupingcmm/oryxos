package io.oryxos.cli;

import io.oryxos.cli.command.ChatCommand;
import io.oryxos.cli.command.GatewayCommand;
import io.oryxos.cli.command.InitCommand;
import io.oryxos.cli.command.ProfileCommand;
import io.oryxos.cli.command.ServeCommand;
import io.oryxos.cli.schedule.ScheduleListCommand;
import io.oryxos.cli.command.StatusCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * OryxOS command-line entry point.
 *
 * <p>This root command owns the banner printer and the Picocli
 * {@link CommandLine} registry. Subcommands are grouped into:
 * <ul>
 *   <li><strong>Zero-Spring</strong> — registered statically here
 *       ({@code init}, {@code status}, {@code profile ...}).
 *       They never boot a Spring context, per FR-011.</li>
 *   <li><strong>Spring-required</strong> — registered dynamically after
 *       Spring boots via {@code BootCommandLineRegistrar}
 *       ({@code chat}, {@code provider list}, {@code tool list},
 *       {@code session list}). Per FR-012.</li>
 * </ul>
 *
 * <p>The must-Spring commands ({@code chat}, {@code serve}, {@code gateway})
 * are also listed statically so {@code --help} can render without booting
 * Spring — Spring is only booted by their {@code runBody} on actual invocation.
 *
 * <p>Run with: {@code mvn -pl oryxos-cli exec:java} (after a parent
 * {@code mvn install}), or {@code java -jar oryxos-cli/target/oryxos-cli-*.jar}.
 */
@Command(
    name = "oryxos",
    mixinStandardHelpOptions = true,
    version = "OryxOS 1.0.0-SNAPSHOT",
    description = "OryxOS — Enterprise Agent OS runtime kernel CLI",
    subcommands = {
        // US-2 (P2) init + status — zero-Spring (FR-011)
        InitCommand.class,
        StatusCommand.class,
        // US-3 (P3) profile (list/show/create/delete) — zero-Spring (FR-005/011)
        ProfileCommand.class,
        // US-1 (P1) chat — must-Spring, statically listed so --help shows it
        // without booting the Spring context (Spring is only booted by
        // ChatCommand#runBody on actual invocation).
        ChatCommand.class,
        // US-5 stubs
        ServeCommand.class,
        GatewayCommand.class,
        // US-3 Spring-required leaf commands registered dynamically by
        // BootCommandLineRegistrar (T013) after Spring boots:
        // ProviderListCommand / ToolListCommand / SessionListCommand.
        // 008-agent-scheduler: schedule list (US-3 spec)
        ScheduleListCommand.class,
    }
)
public class OryxOsCli implements Runnable {

    @Option(
        names = {"-V", "--version"},
        versionHelp = true,
        description = "Print version information and exit"
    )
    boolean versionInfoRequested;

    @Override
    public void run() {
        // No subcommand → print banner (Picocli handles -V/--version automatically).
        printBanner();
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new OryxOsCli()).execute(args);
        System.exit(exitCode);
    }

    private static void printBanner() {
        String version = resolveVersion();
        String javaVersion = System.getProperty("java.version");
        String os = System.getProperty("os.name") + " " + System.getProperty("os.arch");

        System.out.println();
        System.out.println("  ____                  __  __ ");
        System.out.println(" / __ \\___  ____  ____  / / / /");
        System.out.println("/ / / / _ \\/ __ \\/ __ \\/ /_/ / ");
        System.out.println("/ /_/ /  __/ /_/ / /_/ / __  /  ");
        System.out.println("\\____/\\___/ .___/ .___/_/ /_/   ");
        System.out.println("         /_/   /_/             ");
        System.out.println();
        System.out.println("  OryxOS " + version);
        System.out.println("  Enterprise Agent OS — Java/Spring Boot Runtime Kernel");
        System.out.println();
        System.out.println("  Java:    " + javaVersion);
        System.out.println("  Runtime: " + os);
        System.out.println("  Status:  Core Stage (under construction)");
        System.out.println();
        System.out.println("  Run 'oryxos --help' to see available commands.");
        System.out.println("  See  docs/DemandAnalysis.md §5.11 for the full command list.");
        System.out.println();
    }

    /**
     * Resolve the version from the JAR's {@code Implementation-Version} manifest entry
     * (injected by maven-jar-plugin from {@code ${project.version}}). Falls back to a
     * hard-coded literal when running from {@code target/classes} (IDE / unpacked).
     */
    private static String resolveVersion() {
        String v = OryxOsCli.class.getPackage().getImplementationVersion();
        return v != null ? v : "1.0.0-SNAPSHOT (dev)";
    }
}