package io.oryxos.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * OryxOS command-line entry point.
 *
 * <p>Default behavior (no subcommand, no flag): print the version banner and a pointer
 * to {@code oryxos --help}. The 12 sub-commands listed in
 * {@code docs/DemandAnalysis.md §5.11} are wired in subsequent user stories (US-1, US-2,
 * US-5, etc.) — this scaffold delivers only the entry point and version printer so the
 * CLI module is runnable from day one.
 *
 * <p>Run with: {@code mvn -pl oryxos-cli exec:java} (after a parent {@code mvn install}),
 * or with an explicit classpath: {@code java -cp <classpath> io.oryxos.cli.OryxOsCli}.
 */
@Command(
    name = "oryxos",
    mixinStandardHelpOptions = true,
    version = "OryxOS 1.0.0-SNAPSHOT",
    description = "OryxOS — Enterprise Agent OS runtime kernel CLI"
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