package io.oryxos.cli.workspace;

/**
 * Bootstrap file templates written by {@code InitCommand} into a freshly
 * initialised workspace — see {@code contracts/init.md} and
 * [CLAUDE.md §12](../../../../../CLAUDE.md).
 *
 * <p>All four templates are deliberately <em>short</em>: they exist to give
 * a brand-new workspace a sane default and to make the files immediately
 * discoverable as "bootstrap configuration". Operators edit them in place.
 *
 * <p>The strings are intentionally <strong>literal</strong> (no
 * {@code ${ENV_VAR}} placeholders here) because bootstrap files are about
 * declaring what the user wants the agent to do, not about wiring secrets.
 * Secret-bearing config belongs in Profile {@code AGENT.md} / Profile YAML,
 * not in bootstrap files.
 */
public final class BootstrapContent {

    private BootstrapContent() {
        // utility holder
    }

    /** {@code .oryxos/AGENTS.md} — project-level agent behaviour notes. */
    public static final String AGENTS_MD = """
            # OryxOS Agents — project-level conventions

            Every agent defined under `.oryxos/agents/<name>/` reads this file
            before its own `AGENT.md`. Add project-wide guardrails here:

            - Never print API keys.
            - Always end a turn with a single Markdown block when calling Tools.
            - When the user asks for a deliverable, finish the run with a short
              text summary so the chat command prints useful stdout.

            Edit this file freely; it is read on every agent run.
            """;

    /** {@code .oryxos/SOUL.md} — default agent persona / voice. */
    public static final String SOUL_MD = """
            # OryxOS SOUL — default persona

            OryxOS agents are concise, technical, and pragmatic.

            - Plain language first; jargon only when it earns its keep.
            - When a task is ambiguous, ask **one** clarifying question before
              firing off Tool calls.
            - Never invent data — if a tool returns "no result", say so.

            Override per-agent by editing `.oryxos/agents/<name>/AGENT.md`.
            """;

    /** {@code .oryxos/USER.md} — user preferences and identity. */
    public static final String USER_MD = """
            # OryxOS USER — your preferences

            Tell OryxOS about yourself. Examples:

            - Preferred language for responses: 中文 / English / ...
            - Time zone: Asia/Shanghai (UTC+8)
            - Default notification channels:
              - webhook: <paste your webhook URL here>

            This file is read on every agent run; agents use it to adapt
            defaults without re-asking the user.
            """;

    /** {@code .oryxos/memory/MEMORY.md} — long-term memory core zone (MarkdownMemoryStore default). */
    public static final String MEMORY_MD = """
            # OryxOS Memory — long-term notes

            This file is the default `core` zone of the MarkdownMemoryStore.
            Agents call `save_memory` / `recall_memory` against it.

            Sections are free-form. Suggested starter structure:

            - ## User
              - preferences surfaced from USER.md that the agent should remember
            - ## Decisions
              - significant choices the user has approved
            - ## Archive pointers
              - references to entries moved to the archive zone
            """;
}