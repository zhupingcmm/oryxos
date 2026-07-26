package io.oryxos.tool.notify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UrlRedactor 单测 —— T029。
 *
 * <p>覆盖：5 种敏感 query 名 + 大小写不敏感 + 非敏感保留 + 解析失败保守。
 */
class UrlRedactorTest {

    @Test
    void redactsKeyParam() {
        assertThat(UrlRedactor.redact("https://open.feishu.cn/hook?key=ABCDEFG12345"))
            .isEqualTo("https://open.feishu.cn/hook?key=REDACTED");
    }

    @Test
    void redactsAccessToken() {
        assertThat(UrlRedactor.redact("https://example.com/h?access_token=xyz"))
            .isEqualTo("https://example.com/h?access_token=REDACTED");
    }

    @Test
    void redactsSecret() {
        assertThat(UrlRedactor.redact("https://example.com/h?secret=shh"))
            .isEqualTo("https://example.com/h?secret=REDACTED");
    }

    @Test
    void redactsApiKey() {
        assertThat(UrlRedactor.redact("https://example.com/h?api_key=abc"))
            .isEqualTo("https://example.com/h?api_key=REDACTED");
    }

    @Test
    void redactsToken() {
        assertThat(UrlRedactor.redact("https://example.com/h?token=t"))
            .isEqualTo("https://example.com/h?token=REDACTED");
    }

    @Test
    void caseInsensitive() {
        assertThat(UrlRedactor.redact("https://example.com/h?KEY=abc"))
            .isEqualTo("https://example.com/h?KEY=REDACTED");
        assertThat(UrlRedactor.redact("https://example.com/h?Access_Token=abc"))
            .isEqualTo("https://example.com/h?Access_Token=REDACTED");
    }

    @Test
    void preservesNonSensitiveParams() {
        assertThat(UrlRedactor.redact("https://example.com/h?foo=bar&baz=qux"))
            .isEqualTo("https://example.com/h?foo=bar&baz=qux");
    }

    @Test
    void redactsOnlyMatchingAmongMixedParams() {
        assertThat(UrlRedactor.redact("https://example.com/h?foo=bar&key=secret&baz=qux"))
            .isEqualTo("https://example.com/h?foo=bar&key=REDACTED&baz=qux");
    }

    @Test
    void noQueryReturnsOriginal() {
        assertThat(UrlRedactor.redact("https://example.com/path"))
            .isEqualTo("https://example.com/path");
    }

    @Test
    void malformedUrlReturnedUnchanged() {
        // 解析失败 → 原样（保守策略，不丢失可见性）
        String ugly = "not a url at all!!";
        assertThat(UrlRedactor.redact(ugly)).isEqualTo(ugly);
    }

    @Test
    void nullAndBlankHandling() {
        assertThat(UrlRedactor.redact(null)).isNull();
        assertThat(UrlRedactor.redact("")).isEmpty();
    }
}