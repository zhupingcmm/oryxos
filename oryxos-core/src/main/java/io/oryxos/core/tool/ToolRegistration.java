package io.oryxos.core.tool;

import io.oryxos.core.OryxTool;

/**
 * 单条 Tool 注册项 —— 把 {@link ToolDefinition}（元数据，给 CLI 展示）与
 * {@link OryxTool}（实现，给 {@code DefaultToolExecutor} 派发）打包。
 *
 * <p>US-4 引入：把 Tool 注册的"对外展示契约"与"内部派发实现"解耦，
 * 但避免两个 Map 同步问题（详见 [research.md R-02](../../../../../../../specs/004-notify-channel/research.md)）。
 *
 * @param definition Tool 元数据（{@code name} + {@code description} + {@code origin}）
 * @param tool       Tool 实现（{@link OryxTool#execute} 派发入口）
 * @param beanName   Spring bean 名（诊断用：哪个 Bean 注册了这条 Tool）
 */
public record ToolRegistration(
    ToolDefinition definition,
    OryxTool tool,
    String beanName
) {
    public ToolRegistration {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        if (tool == null) {
            throw new IllegalArgumentException("tool must not be null");
        }
        if (!definition.name().equals(tool.name())) {
            throw new IllegalArgumentException(
                "definition.name() ('" + definition.name()
                    + "') must match tool.name() ('" + tool.name() + "')");
        }
        beanName = beanName == null ? "<unknown>" : beanName;
    }
}