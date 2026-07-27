package io.oryxos.tool.sandbox;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 应用层白名单实现 —— host 后缀匹配 + IP 拒绝 + scheme 校验 + 文件路径白名单 + Shell 命令白名单。
 *
 * <p>007 阶段扩展为 4 类 ActionType 真实拦截；详见
 * <a href="../../../../../../../specs/007-sandbox-whitelist/contracts/sandbox-whitelist.md">specs/007-sandbox-whitelist/contracts/sandbox-whitelist.md §13</a>。
 *
 * <p>匹配规则（spec FR-002..007 / data-model §2.2）：
 * <ol>
 *   <li>{@code HTTP_REQUEST} —— scheme 必须是 {@code http} / {@code https}；URL 解析失败 → 拒绝；
 *       host 在 {@code http.allowed-domains} 列表任一项的后缀匹配 → 允许；host 为 IPv4 / IPv6 字面量 → 拒绝</li>
 *   <li>{@code FILE_READ} / {@code FILE_WRITE} —— 路径经 {@code Path.normalize()} 规范化后与原值不等 → 拒绝（traversal）；
 *       绝对路径 → 拒绝；不在 {@code file.allowed-paths} 严格前缀匹配内 → 拒绝</li>
 *   <li>{@code SHELL_COMMAND} —— 首 token 经 lower-case 后不在 {@code shell.allowed-commands} 列表 → 拒绝</li>
 * </ol>
 *
 * <p>fail-closed 默认（spec FR-011 / 宪法 §VII）：空白名单 = 全部拒绝。
 *
 * <p>升级路径：白名单 → 容器（namespace + cgroups + seccomp）→ microVM（Firecracker / Kata / gVisor）；
 * 接口不变，扩展阶段只需替换实现。
 *
 * <p>详见 <a href="../../../../../../../CLAUDE.md">CLAUDE.md §9.4</a>
 * + <a href="../../../../../../../specs/005-tool-system/contracts/sandbox.md">specs/005-tool-system/contracts/sandbox.md §3</a>
 * + <a href="../../../../../../../specs/007-sandbox-whitelist/contracts/sandbox-whitelist.md">specs/007-sandbox-whitelist/contracts/sandbox-whitelist.md §13</a>。
 */
@Component
public class WhitelistSandbox implements Sandbox {

    private final List<String> allowedDomains;
    private final List<String> allowedPaths;
    private final List<String> allowedCommands;

    public WhitelistSandbox() {
        this(List.of(), List.of(), List.of());
    }

    /**
     * 三类白名单注入入口 —— 007 阶段扩展（既有 {@code SandboxProperties} 注入 + 新增 file / shell）。
     *
     * @param allowedDomains   HTTP 域名白名单（后缀匹配）
     * @param allowedPaths     文件读写白名单（严格前缀匹配）
     * @param allowedCommands  Shell 命令首 token 白名单（lower-case 后精确匹配）
     */
    public WhitelistSandbox(List<String> allowedDomains, List<String> allowedPaths, List<String> allowedCommands) {
        this.allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
        this.allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
        this.allowedCommands = allowedCommands == null ? List.of() : List.copyOf(allowedCommands);
    }

    /**
     * 兼容入口 —— 既有 005 阶段 HTTP-only 注入路径（保留给 {@code WhitelistSandboxTest} 等既有单测）。
     *
     * <p>FILE / SHELL 在该入口下走 fail-closed 默认（空白名单 = 全部拒绝）。
     */
    public WhitelistSandbox(SandboxProperties properties) {
        this(
            properties == null ? List.of() : properties.getHttp().getAllowedDomains(),
            properties == null ? List.of() : properties.getFile().getAllowedPaths(),
            properties == null ? List.of() : properties.getShell().getAllowedCommands()
        );
    }

    /**
     * 兼容入口 —— 既有 005 阶段只注入 HTTP 白名单的构造器（保留给 {@code WhitelistSandboxTest}）。
     *
     * <p>FILE / SHELL 在该入口下走 fail-closed 默认（空白名单 = 全部拒绝）。
     */
    public WhitelistSandbox(List<String> allowedDomains) {
        this(allowedDomains, List.of(), List.of());
    }

    @Override
    public void enforce(SandboxAction action) {
        Objects.requireNonNull(action, "action");
        switch (action.type()) {
            case HTTP_REQUEST -> enforceHttp(action);
            case FILE_READ, FILE_WRITE -> enforceFile(action);
            case SHELL_COMMAND -> enforceShell(action);
        }
    }

    // ============ HTTP_REQUEST（既有 005 行为，007 阶段字节级不变 + IPv6 补强）============

    private void enforceHttp(SandboxAction action) {
        // 1. scheme 必须是 http / https
        String scheme = extractScheme(action.target());
        if (scheme == null) {
            throw new SandboxViolationException(action,
                "sandbox violation: cannot extract scheme from '" + action.target() + "'");
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new SandboxViolationException(action,
                "sandbox violation: unsupported scheme: " + scheme
                    + " (only http / https allowed)");
        }

        // 2. 抽取 host
        String host = extractHost(action.target());
        if (host == null || host.isBlank()) {
            throw new SandboxViolationException(action,
                "sandbox violation: cannot extract host from '" + action.target() + "'");
        }
        if (isIpLiteral(host)) {
            throw new SandboxViolationException(action,
                "sandbox violation: IP-literal hosts are not allowed: " + host);
        }

        // 3. 后缀匹配（含 fail-closed 默认）
        String normalized = host.toLowerCase(Locale.ROOT);
        for (String suffix : allowedDomains) {
            if (suffix == null || suffix.isBlank()) {
                continue;
            }
            String normSuffix = suffix.toLowerCase(Locale.ROOT);
            if (normalized.equals(normSuffix) || normalized.endsWith("." + normSuffix)) {
                return;
            }
        }
        throw new SandboxViolationException(action,
            "sandbox violation: host '" + host + "' not in allowed-domains");
    }

    // ============ FILE_READ / FILE_WRITE（007 阶段新增）============

    private void enforceFile(SandboxAction action) {
        String target = action.target();
        java.nio.file.Path raw = java.nio.file.Path.of(target);
        java.nio.file.Path normalized = raw.normalize();

        // 1. `..` / `.` 段检测：Path.normalize() 后与原值不等 → 视为 traversal
        if (!normalized.equals(raw)) {
            throw new SandboxViolationException(action,
                "sandbox violation: path traversal detected: " + raw + " -> " + normalized);
        }

        // 2. 绝对路径拒绝（核心阶段只允许相对路径）。
        // 使用 getRoot() != null 而非 isAbsolute()，跨平台覆盖：
        //   Linux/Mac: /etc/passwd → root=/
        //   Windows:   \etc\passwd → root=\ (drive-relative 仍视为带根路径 → 拒绝)
        if (normalized.getRoot() != null) {
            throw new SandboxViolationException(action,
                "sandbox violation: absolute path not allowed: " + normalized);
        }

        // 3. fail-closed 默认：空白名单 = 全部拒绝
        if (allowedPaths.isEmpty()) {
            throw new SandboxViolationException(action,
                "sandbox violation: path '" + normalized + "' not in allowed-paths");
        }

        // 4. 严格前缀匹配（防 /home/agent/workspace 被 /home/agent/workspace-evil 绕过）
        for (String allowed : allowedPaths) {
            if (allowed == null || allowed.isBlank()) {
                continue;
            }
            java.nio.file.Path allowedPath = java.nio.file.Path.of(allowed);
            java.nio.file.Path resolved = allowedPath.resolve(normalized).normalize();
            if (resolved.equals(allowedPath) || resolved.startsWith(allowedPath.toString() + java.io.File.separator)) {
                return;
            }
        }
        throw new SandboxViolationException(action,
            "sandbox violation: path '" + normalized + "' not in allowed-paths");
    }

    // ============ SHELL_COMMAND（007 阶段新增）============

    private void enforceShell(SandboxAction action) {
        String command = action.target();
        String trimmed = command.trim();

        // 1. 空命令拒绝
        if (trimmed.isEmpty()) {
            throw new SandboxViolationException(action, "sandbox violation: empty command");
        }

        // 2. fail-closed 默认：空白名单 = 全部拒绝
        if (allowedCommands.isEmpty()) {
            String first = trimmed.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            throw new SandboxViolationException(action,
                "sandbox violation: command '" + first + "' not in allowed-commands");
        }

        // 3. 首 token 提取 + 大小写不敏感匹配
        String first = trimmed.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        for (String cmd : allowedCommands) {
            if (cmd == null || cmd.isBlank()) {
                continue;
            }
            if (first.equals(cmd.toLowerCase(Locale.ROOT))) {
                return;
            }
        }
        throw new SandboxViolationException(action,
            "sandbox violation: command '" + first + "' not in allowed-commands");
    }

    // ============ 既有 helper 方法（007 阶段字节级不变，仅 isIpLiteral 补强 IPv6 字面）============

    private static String extractScheme(String target) {
        try {
            URI uri = new URI(target);
            String s = uri.getScheme();
            if (s != null && !s.isBlank()) {
                return s;
            }
            int colon = target.indexOf(':');
            if (colon < 0) {
                return null;
            }
            return target.substring(0, colon);
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private static String extractHost(String target) {
        try {
            URI uri = new URI(target);
            String h = uri.getHost();
            if (h != null && !h.isBlank()) {
                return h;
            }
            // 容忍 "host/path" 这种无 scheme 的写法
            int slash = target.indexOf('/');
            String candidate = slash < 0 ? target : target.substring(0, slash);
            int colon = candidate.indexOf(':');
            return colon < 0 ? candidate : candidate.substring(0, colon);
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    /**
     * IP 字面识别 —— 007 阶段补强 IPv6。
     *
     * <p>既有 005 阶段仅识别 IPv4（{@code :digit:.} 简单模式）；007 阶段把判定改为"含 {@code :} AND
     * （全部由 hex digit + {@code :} + {@code .} 组成 OR 含 {@code [} / {@code ]} 包装）"，
     * 覆盖 {@code [::1]} / {@code [fe80::1%eth0]} / {@code [::ffff:192.168.1.1]} 等纯 IPv6 字面
     * （spec FR-007 + research.md R-04）。
     */
    private static boolean isIpLiteral(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        // IPv6 字面（带 [ ] 包装 或 裸 IPv6 地址）：含 ':' 且（全部由 hex digit + ':' + '.' + '%' 组成 OR 含 [ ]）
        if (host.indexOf(':') >= 0) {
            // [::1] / [fe80::1%eth0] 这种带方括号包装的形式
            if (host.indexOf('[') >= 0 || host.indexOf(']') >= 0) {
                return true;
            }
            // 裸 IPv6（纯 hex digit + ':' + '.' + '%' 组成，无字母域名）
            return host.chars().allMatch(c ->
                Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                    || c == ':' || c == '.' || c == '%');
        }
        // IPv4 字面
        return host.chars().allMatch(Character::isDigit) || host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }
}