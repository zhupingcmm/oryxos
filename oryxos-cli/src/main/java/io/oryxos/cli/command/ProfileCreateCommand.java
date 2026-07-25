package io.oryxos.cli.command;

import io.oryxos.cli.exitcode.Sysexits;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * {@code oryxos profile create <name> --template <tpl>} — copy a bundled
 * template into {@code .oryxos/agents/<name>/AGENT.md}.
 *
 * <p>Supported templates: {@code minimal}, {@code weather},
 * {@code tech-digest}, {@code github-pr-digest} — each ships as a classpath
 * resource under {@code templates/<name>.md} in this module. The placeholder
 * {@code __PROFILE_NAME__} is substituted with the user-supplied name.
 */
@CommandLine.Command(
        name = "create",
        mixinStandardHelpOptions = true,
        description = "Create a new agent Profile from a bundled template.")
public class ProfileCreateCommand extends CommandBase {

    private static final Set<String> SUPPORTED_TEMPLATES =
            Set.of("minimal", "weather", "tech-digest", "github-pr-digest");

    @CommandLine.Parameters(
            index = "0",
            paramLabel = "<name>",
            description = "Profile name (^[a-z][a-z0-9-]{0,63}$).")
    String name;

    @CommandLine.Option(
            names = {"-t", "--template"},
            defaultValue = "minimal",
            description = "Template to seed from: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})")
    String template;

    @Override
    protected Integer runBody() throws Exception {
        ProfileCommand.requireValidName(name);
        if (!SUPPORTED_TEMPLATES.contains(template)) {
            throw new IllegalArgumentException(
                    "Unknown template '" + template + "'. Supported: " + SUPPORTED_TEMPLATES);
        }

        Path target = ProfileCommand.profileDir(workspaceRoot(), name);
        if (Files.exists(target)) {
            throw new IllegalArgumentException(
                    "Profile '" + name + "' already exists at " + target
                            + " — refusing to overwrite (delete first or pick a new name)");
        }
        Files.createDirectories(target);
        Path agentMd = target.resolve("AGENT.md");

        String body = readTemplate(template);
        body = body.replace("__PROFILE_NAME__", name);
        Files.writeString(agentMd, body);

        spec.commandLine().getOut().println("created: " + agentMd);
        spec.commandLine().getOut().flush();
        return Sysexits.OK;
    }

    private String readTemplate(String template) throws IOException {
        String resource = "/templates/" + template + ".md";
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Template resource not found on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}