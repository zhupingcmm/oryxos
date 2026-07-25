package io.oryxos.cli.command;

import io.oryxos.cli.exitcode.Sysexits;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * Stub for {@code oryxos serve} — see {@code contracts/serve.md} and FR-008.
 *
 * <p>The real Web Service implementation belongs to the {@code 005-web-service}
 * user story. Until then, this command parses its arguments (so Picocli
 * surfaces flag errors as exit 64) and prints {@code not yet implemented (US-5)}
 * without booting Spring.
 */
@CommandLine.Command(
        name = "serve",
        mixinStandardHelpOptions = true,
        description = "Start the OryxOS web service (US-5; stub in 003)")
public class ServeCommand implements Callable<Integer> {

    @CommandLine.Option(
            names = {"-p", "--port"},
            description = "Port to bind the web service (default: 8080)")
    Integer port;

    @Override
    public Integer call() {
        // Persist the parsed args for the future US-5 implementation so they
        // are not silently dropped (contracts/serve.md §4).
        if (port != null) {
            System.setProperty("oryxos.cli.us5.placeholder", "serve.port=" + port);
        } else {
            System.setProperty("oryxos.cli.us5.placeholder", "serve.port=8080");
        }
        System.out.println("not yet implemented (US-5)");
        return Sysexits.OK;
    }
}