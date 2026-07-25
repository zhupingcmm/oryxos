package io.oryxos.cli.command;

import io.oryxos.cli.exitcode.Sysexits;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * Stub for {@code oryxos gateway} — see {@code contracts/serve.md} and FR-008.
 *
 * <p>Symmetric counterpart to {@link ServeCommand}; same US-5 stub semantics.
 */
@CommandLine.Command(
        name = "gateway",
        mixinStandardHelpOptions = true,
        description = "Start the OryxOS API gateway (US-5; stub in 003)")
public class GatewayCommand implements Callable<Integer> {

    @CommandLine.Option(
            names = {"-p", "--port"},
            description = "Port to bind the gateway (default: 9090)")
    Integer port;

    @Override
    public Integer call() {
        if (port != null) {
            System.setProperty("oryxos.cli.us5.placeholder", "gateway.port=" + port);
        } else {
            System.setProperty("oryxos.cli.us5.placeholder", "gateway.port=9090");
        }
        System.out.println("not yet implemented (US-5)");
        return Sysexits.OK;
    }
}