package io.oryxos.tool;

import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;

import java.util.Map;

/**
 * US-4 / 005-tool-system 测试桩 —— 模拟业务内置 Tool。
 *
 * <p>关键：{@code getClass().getName()} MUST 以 {@code io.oryxos.tool.} 开头，
 * 否则 {@code DefaultToolExecutor.resolveSource()} 无法识别为 {@code "builtin"}。
 * 因此该桩独立成文件，不能作为 {@code DefaultToolExecutorTest} 的内部类。
 */
public class StubBuiltinTool implements OryxTool {

    @Override public String name() { return "stub-builtin"; }

    @Override public String description() { return "stub builtin"; }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        return ToolResult.ok(Map.of());
    }
}