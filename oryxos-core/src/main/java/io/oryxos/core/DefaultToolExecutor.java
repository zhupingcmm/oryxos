package io.oryxos.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * US-2 阶段的 {@link ToolExecutor} 桩实现 —— 不执行真实 Tool（归 US-4）。
 *
 * <p>行为表（[contracts/ToolExecutor.md §4](../../../../../specs/002-react-loop/contracts/ToolExecutor.md)）：
 * <ul>
 *   <li>{@code toolName} 在 {@code profile.tools()} 内 → 抛 {@link UnsupportedOperationException}（US-2 桩语义）</li>
 *   <li>{@code toolName} 不在白名单 → 返回 {@link ToolResult#error}("tool not in profile: ...")</li>
 *   <li>无论哪种情况 → 日志 INFO 一行（day-one 审计路径可达；US-4 替换为 JPA 写入）</li>
 * </ul>
 *
 * <p>US-4 将替换为：
 * <ol>
 *   <li>白名单校验</li>
 *   <li>解析 {@code arguments}、调用 {@link OryxTool#execute}</li>
 *   <li>捕获 unchecked 异常 → {@link ToolResult#error}</li>
 *   <li>写入 {@link io.oryxos.storage.entity.ToolInvocationRecord} 行（审计 day-one 地基）</li>
 * </ol>
 */
@Component
public class DefaultToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolExecutor.class);

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments, Profile profile) {
        // C-TE-1：白名单拒绝
        if (!profile.tools().contains(toolName)) {
            String message = "tool not in profile: " + toolName;
            log.info("tool.refused profile={} tool={} reason={}",
                profile.name(), toolName, message);
            return ToolResult.error(message);
        }
        // 白名单通过但 US-2 尚未实现真实 Tool 派发
        log.info("tool.unsupported profile={} tool={} reason=US-2-stub",
            profile.name(), toolName);
        throw new UnsupportedOperationException(
            "Default stub — Tool '" + toolName + "' not implemented in US-2");
    }

    /** 内部 use：让 ReActLoop 在调用前可探测 iteration 取自 ProfileContext（C-TE-3）。 */
    @SuppressWarnings("unused")
    private static UUID currentSessionId() {
        return ProfileContext.current()
            .map(ProfileContext.Snapshot::sessionId)
            .orElse(null);
    }
}