package io.oryxos.cli;

import io.oryxos.cli.command.ChatCommand;
import io.oryxos.cli.command.GatewayCommand;
import io.oryxos.cli.command.ServeCommand;
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
 *       ({@code init}, {@code status}, {@code serve}, {@code gateway}).
 *       They never boot a Spring context, per FR-011.</li>
 *   <li><strong>Spring-required</strong> — registered dynamically after
 *       Spring boots via {@code BootCommandLineRegistrar}
 *       ({@code chat}, {@code provider list}, {@code tool list},
 *       {@code session list}). Per FR-012.</li>
 * </ul>
 *
 * <p>{@code ProfileCommand} is added in its own phase (US-3, T033) so is
 * not listed in {@code subcommands} here yet — Picocli requires the class
 * to exist at registration time.
 *
 * <p>Run with: {@code mvn -pl oryxos-cli exec:java} (after a parent
 * {@code mvn install}), or {@code java -cp <classpath> io.oryxos.cli.OryxOsCli}.
 */
@Command(
    name = "oryxos",
    mixinStandardHelpOptions = true,
    version = "OryxOS 1.0.0-SNAPSHOT",
    description = "OryxOS — Enterprise Agent OS runtime kernel CLI",
    subcommands = {
        // US-2 (P2): init + status — zero-Spring
        // InitCommand.class,
        // StatusCommand.class,
        // US-1 (P1) chat — must-Spring, statically listed so --help shows it
        // without booting the Spring context (Spring is only booted by
        // ChatCommand#runBody on actual invocation).
        ChatCommand.class,
        // US-5 stub
        ServeCommand.class,
        GatewayCommand.class,
        // US-3 (P3) profile/provider/tool/session: registered dynamically
        // by BootCommandLineRegistrar (T013) after Spring boots, or
        // statically once their classes land.
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