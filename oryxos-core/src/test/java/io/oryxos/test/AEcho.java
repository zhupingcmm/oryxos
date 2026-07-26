package io.oryxos.test;

import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;

import java.util.Map;

/**
 * ToolRegistryTest 测试 fixture —— FQCN 必须是 {@code io.oryxos.test.AEcho} 以便
 * {@code ToolRegistry.of()} 抛冲突时能区分两个 Stub。
 */
public class AEcho implements OryxTool {

    @Override public String name() { return "echo"; }

    @Override public String description() { return "AEcho fixture"; }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        return ToolResult.ok(Map.of("stub", "AEcho"));
    }
}