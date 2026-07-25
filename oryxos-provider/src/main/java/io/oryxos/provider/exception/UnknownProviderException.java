package io.oryxos.provider.exception;

/**
 * Provider 名称未在实例目录中配置。
 *
 * <p>启动期校验可发现此错误时直接 fail-fast（spec FR-003）；
 * 运行期出现通常意味着配置漂移（Profile 在运行中改 {@code provider.name} 到一个未配置项）。
 *
 * <p>触发时调用方已收到 {@code llm_calls} 一行审计记录（{@code success=false}）。
 */
public class UnknownProviderException extends RuntimeException {

    public UnknownProviderException(String name) {
        super("Unknown provider: '" + name + "'. " +
              "Check oryxos.providers.* in application.yml.");
    }
}