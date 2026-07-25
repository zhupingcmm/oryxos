package io.oryxos.core;

import java.util.Map;

/**
 * Bootstrap 文件加载器 —— 按 {@link Profile#bootstrap()} 中的文件名（如 {@code AGENTS.md}、
 * {@code SOUL.md}、{@code USER.md}）返回文件内容。
 *
 * <p>返回 {@code Map<fileName, content>}；未引用到的 Bootstrap 文件不加载（渐进式披露）。
 *
 * <p>US-2 桩实现：{@link NoopBootstrapLoader} 返回空映射（所有 Bootstrap 文件视为空）。
 * US-4 引入文件系统版实现（扫 {@code .oryxos/AGENTS.md} 等根目录文件）。
 */
@FunctionalInterface
public interface BootstrapLoader {

    /**
     * @return 声明的 Bootstrap 文件名 → 内容；缺失或读取失败视作空字符串
     */
    Map<String, String> load(Profile profile);

    /** US-2 桩实现 —— 永远返回空映射；测试场景用 lambda 覆盖。 */
    final class NoopBootstrapLoader implements BootstrapLoader {
        @Override
        public Map<String, String> load(Profile profile) {
            return Map.of();
        }
    }
}