package io.oryxos.boot.config;

import io.oryxos.tool.sandbox.SandboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Sandbox 配置接入点 —— 把 {@link SandboxProperties}（{@code @ConfigurationProperties(prefix="oryxos.tool.sandbox")}）
 * 暴露为 Spring Bean，给 {@link io.oryxos.tool.sandbox.WhitelistSandbox} 的双参构造器注入。
 *
 * <p>{@link SandboxProperties} 定义在 {@code oryxos-tool} 模块（核心阶段的"基础设施层"），
 * 但配置接入决策归 {@code oryxos-boot}（与 {@link NotifyToolConfig} / {@link HttpClientConfig} 同模式）。
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
 * <p>T012 落地；具体权限校验规则（host 后缀匹配 + IP 拒绝）见
 * [Sandbox.md §3.1](../../../../../../../specs/005-tool-system/contracts/sandbox.md)。
 */
@Configuration
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxConfig {
}

