/**
 * CLI Channel — interactive agent chat from the terminal.
 *
 * <p>{@code CliChannel} reads stdin, writes stdout, and drives
 * {@code AgentService.process} for each line of input. Implements
 * the {@code oryxos chat} command (with {@code --profile <name>} flag).
 *
 * <p>Future IM channels (WeCom, Feishu, DingTalk, Slack) plug in via additional
 * Channel modules that all delegate to {@code AgentService} in {@code oryxos-core}.
 */
package io.oryxos.channel.cli;