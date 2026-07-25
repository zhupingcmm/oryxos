package io.oryxos.cli.command;

import io.oryxos.cli.config.ConfigLoader;
import io.oryxos.cli.diag.ApiKeyMask;
import io.oryxos.cli.diag.ProviderStatusReport;
import io.oryxos.cli.exitcode.Sysexits;
import io.oryxos.cli.workspace.WorkspaceLayout;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code oryxos status} — workspace health report (FR-004 / SC-003 / SC-007).
 *
 * <p>Zero-Spring (FR-011). Reads the on-disk layout and (optionally) the
 * {@code .oryxos/application.yaml} to render a Provider matrix — no Spring
 * context is booted, no SQLite connection is opened, no LLM call is made.
 *
 * <h2>Exit code mapping</h2>
 * <table>
 *   <tr><th>Health</th><th>Exit</th></tr>
 *   <tr><td>All Provider API keys resolved</td><td>{@link Sysexits#OK} (0)</td></tr>
 *   <tr><td>One or more Provider API keys missing</td><td>{@link Sysexits#WARNING} (2)</td></tr>
 *   <tr><td>{@code .oryxos/} missing</td><td>{@link Sysexits#GENERIC} (1)</td></tr>
 * </table>
 */
@CommandLine.Command(
        name = "status",
        mixinStandardHelpOptions = true,
        description = "Show OryxOS workspace health (FR-004).")
public class StatusCommand extends CommandBase {

    @CommandLine.Option(
            names = {"-f", "--format"},
            description = "Output format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})",
            defaultValue = "table")
    String format;

    @CommandLine.Option(
            names = {"-v", "--verbose"},
            description = "Show extended details (SQLite row counts, profile tools).")
    boolean verbose;

    @Override
    protected Integer runBody() throws Exception {
        Path oryxos = workspaceRoot();
        if (!Files.exists(oryxos)) {
            // FR-004: missing workspace = error (1)
            throw new io.oryxos.cli.workspace.NotInitializedException(
                    oryxos.toAbsolutePath().toString());
        }

        WorkspaceLayout layout = WorkspaceLayout.probe(oryxos.getParent());
        List<ProviderStatusReport> providers = readProviderMatrix(oryxos);
        int mcpServerCount = readMcpServerCount(oryxos);

        renderTableOrJson(layout, providers, mcpServerCount);

        // Health grading (FR-004 / SC-007)
        if (providers.stream().anyMatch(p -> !p.apiKeyResolved())) {
            return Sysexits.WARNING;
        }
        return Sysexits.OK;
    }

    /**
     * Read {@code .oryxos/application.yaml} if it exists and extract the
     * {@code oryxos.providers.<name>} block. Missing file → empty list
     * (status still succeeds; "no providers configured" is informational).
     */
    private List<ProviderStatusReport> readProviderMatrix(Path oryxos) {
        Path yaml = oryxos.resolve("application.yaml");
        if (!Files.exists(yaml)) {
            return List.of();
        }
        try {
            Map<String, Object> root = ConfigLoader.loadYaml(yaml);
            // Application.yaml layout: oryxos.providers.<name>.<...>
            // (Spring's relaxed binding flattens to that nested map.)
            Object oryxosBlock = root.get("oryxos");
            if (!(oryxosBlock instanceof Map<?, ?> oryxosMap)) {
                return List.of();
            }
            Object providersBlock = oryxosMap.get("providers");
            if (!(providersBlock instanceof Map<?, ?> providersMap)) {
                return List.of();
            }
            return ProviderStatusReport.fromApplicationYaml(toStringMap(providersMap));
        } catch (Exception e) {
            // Bad YAML or unresolved env var — surface as a single warning
            // line on stderr and continue with empty matrix (status is
            // best-effort; the operator can fix the file later).
            spec.commandLine().getErr().println(
                    "Warning: could not parse " + yaml + ": " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Read {@code .oryxos/mcp_servers.yaml} and return the number of MCP
     * servers it declares (FR-004). The skeleton produced by
     * {@code InitCommand} has shape {@code servers: [...]}; we accept both
     * a list and a map (where keys are server names) for forward
     * compatibility. Missing file → 0; unparseable file → 0 + a warning to
     * stderr (status is best-effort).
     */
    private int readMcpServerCount(Path oryxos) {
        Path yaml = oryxos.resolve("mcp_servers.yaml");
        if (!Files.exists(yaml)) {
            return 0;
        }
        try {
            Map<String, Object> root = ConfigLoader.loadYaml(yaml);
            Object servers = root.get("servers");
            if (servers instanceof List<?> list) {
                return list.size();
            }
            if (servers instanceof Map<?, ?> map) {
                return map.size();
            }
            return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println(
                    "Warning: could not parse " + yaml + ": " + e.getMessage());
            return 0;
        }
    }

    private void renderTableOrJson(WorkspaceLayout layout, List<ProviderStatusReport> providers,
                                   int mcpServerCount) {
        if ("json".equalsIgnoreCase(format)) {
            String json = layout.renderJson()
                    + " | providers=" + providers.size()
                    + " | missing_keys=" + providers.stream()
                            .filter(p -> !p.apiKeyResolved()).count()
                    + " | mcp_servers=" + mcpServerCount;
            spec.commandLine().getOut().println(json);
        } else {
            // Table
            spec.commandLine().getOut().println(layout.renderHumanReadable());
            if (!providers.isEmpty()) {
                spec.commandLine().getOut().println("Providers (" + providers.size() + " configured):");
                spec.commandLine().getOut().println("  " + ProviderStatusReport.tableHeader());
                for (ProviderStatusReport p : providers) {
                    spec.commandLine().getOut().println("  " + p.toTableRow());
                }
            } else {
                spec.commandLine().getOut().println("Providers: (none configured — no .oryxos/application.yaml)");
            }
            // MCP server count is part of the FR-004 contract — render it
            // even when the providers section is empty (the two are independent).
            spec.commandLine().getOut().println("MCP servers: " + mcpServerCount);
            if (verbose) {
                // FR-020 — `--verbose` MAY display first-four + "..." for
                // each provider's API key as an ops debug signal. Default
                // (non-verbose) output never shows any of this. SQLite /
                // profile tool counts remain deferred to US-3 (kept as
                // a placeholder so the section header still prints).
                spec.commandLine().getOut().println("Verbose:");
                spec.commandLine().getOut().println("  SQLite row counts and profile tool lists");
                spec.commandLine().getOut().println("    (deferred to US-3 session/profile commands; this stub is intentional)");
                if (!providers.isEmpty()) {
                    spec.commandLine().getOut().println("  api_key_masked (first 4 chars only):");
                    for (ProviderStatusReport p : providers) {
                        String masked = (p.apiKeyResolved())
                                ? ApiKeyMask.mask(System.getenv(p.credentialRef()))
                                : "unresolved";
                        spec.commandLine().getOut().println("    " + p.name()
                                + " (" + p.credentialRef() + ") = " + masked);
                    }
                }
            }
        }
        spec.commandLine().getOut().flush();
    }

    private static Map<String, Object> toStringMap(Map<?, ?> raw) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(raw.size());
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                continue;
            }
            if (!(e.getValue() instanceof Map<?, ?> nested)) {
                continue;
            }
            Map<String, Object> typed = new java.util.LinkedHashMap<>(nested.size());
            for (Map.Entry<?, ?> n : nested.entrySet()) {
                if (n.getKey() instanceof String nk) {
                    typed.put(nk, n.getValue());
                }
            }
            out.put(key, typed);
        }
        return out;
    }
}