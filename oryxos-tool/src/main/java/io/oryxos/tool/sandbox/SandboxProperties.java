package io.oryxos.tool.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Sandbox 配置 —— 从 {@code oryxos.tool.sandbox.*} 绑定。
 *
 * <p>当前仅暴露 {@code http.allowed-domains} 一项；扩展阶段可加
 * {@code file.allowed-paths} / {@code shell.allowed-commands} 等。
 *
 * <p>配置示例（{@code .oryxos/application.yaml}）：
 * <pre>{@code
 * oryxos:
 *   tool:
 *     sandbox:
 *       http:
 *         allowed-domains:
 *           - qyapi.weixin.qq.com
 *           - oapi.dingtalk.com
 *           - open.feishu.cn
 *           - localhost              # 本地测试 / WireMock
 * }</pre>
 *
 * <p>详见 <a href="../../../../../../../specs/004-notify-channel/contracts/channel-config.md">specs/004-notify-channel/contracts/channel-config.md §4</a>。
 */
@ConfigurationProperties(prefix = "oryxos.tool.sandbox")
public class SandboxProperties {

    /** HTTP 出站白名单子配置。 */
    private Http http = new Http();

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http;
    }

    public static class Http {
        /** 允许的 host 后缀列表（小写；匹配 {@code URL.getHost()} 后缀） */
        private List<String> allowedDomains = List.of();

        public List<String> getAllowedDomains() {
            return allowedDomains;
        }

        public void setAllowedDomains(List<String> allowedDomains) {
            this.allowedDomains = allowedDomains == null ? List.of() : allowedDomains;
        }
    }
}