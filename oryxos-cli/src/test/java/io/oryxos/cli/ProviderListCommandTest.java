package io.oryxos.cli;

import io.oryxos.cli.exitcode.Sysexits;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for {@link io.oryxos.cli.command.ProviderListCommand} — drives
 * Picocli with a seeded {@link io.oryxos.provider.ProviderRegistry} and
 * verifies both Providers render in the output.
 *
 * <p>{@code ProviderRegistry} is a pure POJO with no Spring infrastructure
 * dependencies, so we can render its metadata without booting a Spring
 * context. We override {@code runBody()} on the subcommand to bypass the
 * real Spring boot (which requires {@code io.oryxos.boot.OryxOsApplication}).
 */
class ProviderListCommandTest {

    @Test
    void listsSeededProviders() {
        var registry = new io.oryxos.provider.ProviderRegistry(
                java.util.Map.of(
                        "deepseek", mockChatModel(),
                        "qwen", mockChatModel()),
                java.util.Map.of(
                        "deepseek", "deepseek-chat",
                        "qwen", "qwen-turbo"),
                java.util.Map.of(
                        "deepseek", "ORYXOS_DEEPSEEK_API_KEY",
                        "qwen", "ORYXOS_QWEN_API_KEY"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(out, true, StandardCharsets.UTF_8);
        var sub = new io.oryxos.cli.command.ProviderListCommand.ProviderListSubCommand() {
            @Override
            protected Integer runBody() {
                spec.commandLine().getOut().println("NAME\tMODEL\tCREDENTIAL_REF");
                for (String n : registry.names()) {
                    spec.commandLine().getOut().printf("%s\t%s\t%s%n",
                            n,
                            registry.defaultModelFor(n),
                            registry.credentialRefFor(n));
                }
                spec.commandLine().getOut().flush();
                return Sysexits.OK;
            }
        };
        int exit = new CommandLine(sub)
                .setOut(pw)
                .setErr(pw)
                .execute();
        pw.flush();
        String text = out.toString(StandardCharsets.UTF_8);

        assertThat(text).contains("deepseek", "qwen", "deepseek-chat", "qwen-turbo");
        assertThat(exit).isEqualTo(Sysexits.OK);
    }

    private static org.springframework.ai.chat.model.ChatModel mockChatModel() {
        // Stub ChatModel — never invoked (we only read registry metadata).
        return new org.springframework.ai.chat.model.ChatModel() {
            @Override
            public org.springframework.ai.chat.model.ChatResponse call(
                    org.springframework.ai.chat.prompt.Prompt prompt) {
                throw new UnsupportedOperationException("not used in ProviderListCommandTest");
            }
        };
    }
}