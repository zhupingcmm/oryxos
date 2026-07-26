package io.oryxos.test;

import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;

import java.util.Map;

/** ToolRegistryTest 测试 fixture —— FQCN 必须是 {@code io.oryxos.test.Beta}。 */
public class Beta implements OryxTool {

    @Override public String name() { return "beta"; }

    @Override public String description() { return "Beta fixture"; }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        return ToolResult.ok(Map.of("stub", "Beta"));
    }
}