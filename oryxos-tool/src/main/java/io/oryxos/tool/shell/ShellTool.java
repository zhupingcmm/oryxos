package io.oryxos.tool.shell;

import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@code shell} —— 在受限白名单 + 黑名单内执行 shell 命令。
 *
 * <p>核心安全策略（[research.md R-03](../../../../../../../specs/005-tool-system/research.md)）：
 * <ol>
 *   <li><strong>黑名单优先</strong>：{@code tokens[0]} 在 {@code dangerousCommands} 内 → {@link ToolResult#error}</li>
 *   <li>{@link Sandbox#enforce(SandboxAction) sandbox.enforce(SHELL_COMMAND, command)}（核心阶段 no-op）</li>
 *   <li>{@link ProcessBuilder} 异步读 stdout/stderr（避免进程因满 buffer 阻塞）</li>
 *   <li>{@code process.waitFor(timeout)}；超时则 {@code destroyForcibly()} + error</li>
 * </ol>
 *
 * <p>退出码非零仍返回 {@code success=true}（payload 含 stdout/stderr），由 LLM 自行判断。
 * 真正失败（黑名单 / 超时 / 命令不存在）返回 {@code success=false}。
 */
@Component
public class ShellTool implements OryxTool {

    public static final String NAME = "shell";

    private final ShellToolProperties properties;
    private final Sandbox sandbox;

    public ShellTool() {
        this(new ShellToolProperties(30, 65_536, List.of()), action -> { });
    }

    @Autowired
    public ShellTool(ShellToolProperties properties, Sandbox sandbox) {
        this.properties = properties;
        this.sandbox = sandbox;
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "在受限白名单内执行 shell 命令";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Object rawCmd = arguments.get("command");
        if (!(rawCmd instanceof String command) || command.isBlank()) {
            return ToolResult.error("shell: missing required argument 'command'");
        }
        int timeoutSec = properties.timeoutSeconds();
        Object rawTimeout = arguments.get("timeout_seconds");
        if (rawTimeout instanceof Number n) {
            timeoutSec = n.intValue() > 0 ? n.intValue() : timeoutSec;
        }

        // 1. 黑名单优先（research.md R-03）
        String firstToken = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (properties.dangerousCommands().contains(firstToken)) {
            return ToolResult.error("shell command blocked: " + firstToken
                + " is in dangerous-commands");
        }

        // 2. Sandbox 校验（核心阶段 no-op）
        sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, command));

        // 3. 进程执行
        ProcessBuilder pb = new ProcessBuilder(command.split("\\s+"));
        long start = System.nanoTime();
        Process process;
        try {
            process = pb.start();
        } catch (IOException ex) {
            return ToolResult.error("shell command not found: " + firstToken);
        }

        // 异步读 stdout/stderr 避免 buffer 满阻塞
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outThread = new Thread(() -> drain(process.getInputStream(), stdout),
            "shell-stdout-reader");
        Thread errThread = new Thread(() -> drain(process.getErrorStream(), stderr),
            "shell-stderr-reader");
        outThread.setDaemon(true);
        errThread.setDaemon(true);
        outThread.start();
        errThread.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return ToolResult.error("shell command interrupted: " + command);
        }

        if (!finished) {
            process.destroyForcibly();
            return ToolResult.error("shell command timeout after " + timeoutSec + " seconds: " + command);
        }

        try {
            outThread.join(500);
            errThread.join(500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        String outStr = truncate(stdout.toString(), properties.maxOutputBytes());
        String errStr = truncate(stderr.toString(), properties.maxOutputBytes());
        int exitCode = process.exitValue();

        boolean success = exitCode == 0;
        Map<String, Object> payload = Map.of(
            "command", command,
            "exit_code", exitCode,
            "stdout", outStr,
            "stderr", errStr,
            "duration_ms", durationMs
        );
        if (success) {
            return ToolResult.ok(payload);
        }
        // 退出码非 0 仍成功返回 payload（让 LLM 看 stderr），但 success=false
        return new ToolResult(false, payload, "shell exit code " + exitCode + ": " + command);
    }

    private static void drain(java.io.InputStream in, StringBuilder sink) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sink.append(buf, 0, n);
            }
        } catch (IOException ignored) {
            // 进程已结束
        }
    }

    private static String truncate(String s, int maxBytes) {
        if (s == null) return "";
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return s;
        }
        return new String(bytes, 0, maxBytes, StandardCharsets.UTF_8) + "...[truncated]";
    }
}

