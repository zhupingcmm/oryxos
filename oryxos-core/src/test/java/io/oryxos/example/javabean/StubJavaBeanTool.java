package io.oryxos.example.javabean;

import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;

import java.util.Map;

/**
 * US-4 / 005-tool-system 测试桩 —— 模拟用户自定义 Java Bean Tool。
 *
 * <p>关键：{@code getClass().getName()} MUST 不以 {@code io.oryxos.tool.*} 开头，
 * {@code DefaultToolExecutor.resolveSource()} 据此识别为 {@code "java_bean"}。
 * 因此本桩独立成文件并放在 {@code io.oryxos.example.*} 命名空间下。
 */
public class StubJavaBeanTool implements OryxTool {

    @Override public String name() { return "stub-javabean"; }

    @Override public String description() { return "stub javabean"; }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        return ToolResult.ok(Map.of());
    }
}