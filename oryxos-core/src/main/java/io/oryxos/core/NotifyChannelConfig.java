package io.oryxos.core;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Notify 单条通道配置 —— Profile YAML 中 {@code notify_channels[]} 的元素。
 *
 * <p>核心阶段仅支持 {@code type="webhook"}（出站 HTTP POST）；
 * 扩展阶段可加 SMTP / Slack native 等。
 *
 * <p>字段约束（来自
 * <a href="../../../../../../../specs/004-notify-channel/contracts/channel-config.md">specs/004-notify-channel/contracts/channel-config.md §1.2</a>）：
 * <ul>
 *   <li>{@code name} 匹配 {@code ^[a-z][a-z0-9-]{0,63}$}；Profile 内唯一（由 {@code ConfigLoader} 校验）</li>
 *   <li>{@code type} 仅支持 {@code "webhook"}</li>
 *   <li>{@code url} 合法 {@code http://} / {@code https://}；host 非空</li>
 *   <li>{@code secret} 可空；核心阶段忽略（仅做占位，避免 YAML 误读）</li>
 * </ul>
 *
 * @param name 通道名（Profile 内唯一；LLM 通过此名路由）
 * @param type 通道类型（核心阶段仅 {@code "webhook"}）
 * @param url  通道 URL；必须为合法 {@code http://} 或 {@code https://}
 * @param secret 可空；核心阶段忽略；预留 HMAC 签名扩展位
 */
public record NotifyChannelConfig(
    String name,
    String type,
    String url,
    String secret
) {
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

    public NotifyChannelConfig {
        Objects.requireNonNull(name, "name");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "notify_channel name '" + name + "' must match ^[a-z][a-z0-9-]{0,63}$");
        }
        Objects.requireNonNull(type, "type");
        if (!"webhook".equals(type)) {
            throw new IllegalArgumentException(
                "unsupported notify_channel type: " + type + " (core stage supports only 'webhook')");
        }
        Objects.requireNonNull(url, "url");
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("invalid notify_channel url: " + url);
        }
        if (url.length() <= ("http://x".length())) {
            // 至少要有非空 host
            throw new IllegalArgumentException("invalid notify_channel url (empty host): " + url);
        }
        // secret 可空；非空时不做格式校验（核心阶段忽略）
    }
}