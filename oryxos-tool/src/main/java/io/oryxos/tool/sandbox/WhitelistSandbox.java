package io.oryxos.tool.sandbox;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * 应用层白名单实现 —— host 后缀匹配 + IP 拒绝。
 *
 * <p>匹配规则（spec FR-007 / data-model §6）：
 * <ul>
 *   <li>从 {@link SandboxAction#target()} 解析 host（仅对 {@code HTTP_REQUEST} 生效）</li>
 *   <li>host 在 {@code allowed-domains} 列表任一项的后缀匹配 → 允许</li>
 *   <li>host 为 IPv4 / IPv6 字面量 → 拒绝（出站必须走域名）</li>
 *   <li>host 解析失败 / 为 null / 为空 → 拒绝</li>
 *   <li>其他 {@link ActionType} 暂不校验（核心阶段仅 HTTP_REQUEST 走白名单）</li>
 * </ul>
 *
 * <p>其他 ActionType 在核心阶段为 no-op；扩展阶段按 file / shell 分别加校验。
 *
 * <p>详见 <a href="../../../../../../../specs/004-notify-channel/data-model.md">specs/004-notify-channel/data-model.md §6</a>。
 */
@Component
public class WhitelistSandbox implements Sandbox {

    private final List<String> allowedDomains;

    public WhitelistSandbox() {
        this(List.of());
    }

    public WhitelistSandbox(SandboxProperties properties) {
        this(properties == null ? List.of()
            : properties.getHttp().getAllowedDomains());
    }

    public WhitelistSandbox(List<String> allowedDomains) {
        this.allowedDomains = allowedDomains == null
            ? List.of()
            : List.copyOf(allowedDomains);
    }

    @Override
    public void enforce(SandboxAction action) {
        if (action.type() != ActionType.HTTP_REQUEST) {
            // 核心阶段仅校验 HTTP_REQUEST；其他 ActionType 暂放过
            return;
        }

        String host = extractHost(action.target());
        if (host == null || host.isBlank()) {
            throw new SandboxViolationException(action,
                "sandbox violation: cannot extract host from '" + action.target() + "'");
        }
        if (isIpLiteral(host)) {
            throw new SandboxViolationException(action,
                "sandbox violation: IP-literal hosts are not allowed: " + host);
        }
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

    private static boolean isIpLiteral(String host) {
        // 简单判定：纯 ASCII digit + '.' + ':'，或 IPv6 含 ':' 且无字母
        if (host.indexOf(':') >= 0) {
            return host.chars().allMatch(c -> Character.isDigit(c) || c == ':' || c == '.');
        }
        return host.chars().allMatch(Character::isDigit) || host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }
}