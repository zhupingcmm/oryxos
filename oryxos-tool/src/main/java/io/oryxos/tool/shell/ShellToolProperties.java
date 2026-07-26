package io.oryxos.tool.shell;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Shell Tool 配置 —— 从 {@code oryxos.tool.shell.*} 绑定。
 *
 * @param timeoutSeconds    命令超时秒数（默认 30；超时则 {@code process.destroyForcibly()}）
 * @param maxOutputBytes    stdout/stderr 截断上限（默认 65536 = 64 KB）
 * @param dangerousCommands 黑名单（首 token 命中即拒绝；参见 research.md R-03）
 */
@ConfigurationProperties(prefix = "oryxos.tool.shell")
public record ShellToolProperties(
    int timeoutSeconds,
    int maxOutputBytes,
    List<String> dangerousCommands
) {
    public ShellToolProperties {
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 30;
        }
        if (maxOutputBytes <= 0) {
            maxOutputBytes = 65_536;
        }
        if (dangerousCommands == null) {
            dangerousCommands = List.of();
        } else {
            dangerousCommands = List.copyOf(dangerousCommands);
        }
    }
}

