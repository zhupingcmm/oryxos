/**
 * Picocli-based CLI entry point.
 *
 * <p>Hosts the {@code OryxOsCli} main class and all 12 sub-commands:
 * <ul>
 *   <li><strong>Lifecycle:</strong> {@code init}, {@code status}, {@code chat}, {@code serve}, {@code gateway}</li>
 *   <li><strong>Profile:</strong> {@code profile list | create | show | delete}</li>
 *   <li><strong>Discovery:</strong> {@code provider list}, {@code tool list}, {@code session list}</li>
 * </ul>
 *
 * <p>Also contains {@code ConfigLoader} — environment-variable-injected configuration
 * loader (Profile YAMLs use {@code ${ENV_VAR}} placeholders).
 *
 * <p>Commands that don't require Spring ({@code init}, {@code profile list}) operate
 * on the filesystem directly for fast startup; commands that need LLM calls
 * ({@code chat}, {@code serve}, {@code gateway}) boot the Spring context.
 */
package io.oryxos.cli;