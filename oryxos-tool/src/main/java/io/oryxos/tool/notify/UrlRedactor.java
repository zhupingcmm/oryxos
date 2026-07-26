package io.oryxos.tool.notify;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * URL query 参数脱敏 —— 写审计前移除敏感 token。
 *
 * <p>核心策略（spec FR-012 / data-model §7）：
 * <ul>
 *   <li>敏感参数名集合：{@code key} / {@code access_token} / {@code secret} / {@code api_key} / {@code token}（大小写不敏感）</li>
 *   <li>命中 → 值替换为 {@code REDACTED}</li>
 *   <li>非敏感参数保留原值</li>
 *   <li>URL 解析失败 → 原样返回（保守策略：不丢失审计可见性）</li>
 *   <li>host / path / scheme 不参与脱敏（这些本身不是凭证）</li>
 * </ul>
 */
public final class UrlRedactor {

    /** 敏感 query 参数名（统一小写做匹配；输入也先 lowercase 比较） */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
        "key", "access_token", "secret", "api_key", "token"
    );

    /** 替换值（保持审计可读但不可逆） */
    public static final String REDACTED = "REDACTED";

    private UrlRedactor() {
        // utility
    }

    /**
     * 对 URL 中的敏感 query 参数做脱敏。
     *
     * <p>示例：
     * <pre>
     *   https://open.feishu.cn/hook?key=ABCDEFG
     *     → https://open.feishu.cn/hook?key=REDACTED
     *
     *   https://example.com/path?foo=bar&key=ABC&x=1
     *     → https://example.com/path?foo=bar&key=REDACTED&x=1
     * </pre>
     *
     * @param url 原始 URL（可含 query）
     * @return 脱敏后的 URL；解析失败时返原样
     */
    public static String redact(String url) {
        if (url == null || url.isBlank()) {
            return url == null ? null : "";
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            // 解析失败 → 原样返回，避免丢失信息（spec FR-012 保守策略）
            return url;
        }

        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return url;
        }

        // 拆分 query 为 name=value 对，保留顺序
        StringBuilder rebuilt = new StringBuilder();
        boolean first = true;
        for (String pair : rawQuery.split("&")) {
            if (!first) {
                rebuilt.append('&');
            }
            first = false;
            int eq = pair.indexOf('=');
            String rawName = eq < 0 ? pair : pair.substring(0, eq);
            String nameLower = rawName.toLowerCase(Locale.ROOT);
            if (SENSITIVE_KEYS.contains(nameLower)) {
                rebuilt.append(rawName).append('=').append(REDACTED);
            } else {
                rebuilt.append(pair);
            }
        }
        String newQuery = rebuilt.toString();

        try {
            return new URI(uri.getScheme(), uri.getRawAuthority(),
                uri.getRawPath(), newQuery, uri.getRawFragment()).toString();
        } catch (URISyntaxException ex) {
            return url;
        }
    }
}