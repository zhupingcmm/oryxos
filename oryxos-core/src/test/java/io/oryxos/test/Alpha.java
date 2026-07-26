package io.oryxos.test;

import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;

import java.util.Map;

/** ToolRegistryTest 测试 fixture —— FQCN 必须是 {@code io.oryxos.test.Alpha}。 */
public class Alpha implements OryxTool {

    @Override public String name() { return "alpha"; }

    @Override public String description() { return "Alpha fixture"; }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        return ToolResult.ok(Map.of("stub", "Alpha"));
    }
}