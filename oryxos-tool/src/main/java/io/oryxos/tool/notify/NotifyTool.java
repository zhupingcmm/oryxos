package io.oryxos.tool.notify;

import io.oryxos.core.NotifyChannelConfig;
import io.oryxos.core.OryxTool;
import io.oryxos.core.Profile;
import io.oryxos.core.ProfileContext;
import io.oryxos.core.ProfileRegistry;
import io.oryxos.core.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code notify} Tool —— LLM 通过 Function Calling 触发 Notify 出站推送。
 *
 * <p>核心阶段 MVP（spec FR-003 §3）：
 * <ul>
 *   <li>单通道场景：{@code channel=null} + Profile 仅 1 条通道 → 发到该通道</li>
 *   <li>显式通道：{@code channel="<name>"} → 发到指定通道；不存在报错</li>
 *   <li>空通道：{@code channel=null} + 0/N>1 通道 → 报错（<strong>广播</strong>留 US-2 / US-3）</li>
 * </ul>
 *
 * <p>Schema 见 [contracts/notify-tool.md §2](../../../../../../specs/004-notify-channel/contracts/notify-tool.md)。
 *
 * <p>审计契约（spec §7）：{@link ToolResult} 的 {@code payload} 必含 {@code channel}（String）+ 可选
 * {@code status_code}（Integer），{@link io.oryxos.core.DefaultToolExecutor} 读取这两个字段写到
 * {@code tool_invocations} 表的 {@code channel} + {@code notify_status_code} 列。
 */
@Component
public class NotifyTool implements OryxTool {

    private static final Logger log = LoggerFactory.getLogger(NotifyTool.class);

    /** Tool 名（与 Profile {@code tools[]} 字符串一致）。 */
    public static final String NAME = "notify";

    /** 单条 content UTF-8 字节上限（spec §2.1 + research R-07）。 */
    public static final int MAX_CONTENT_BYTES = 4096;

    private final WebhookNotifyAdapter adapter;
    private final ProfileRegistry profileRegistry;

    public NotifyTool(WebhookNotifyAdapter adapter, ProfileRegistry profileRegistry) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.profileRegistry = Objects.requireNonNull(profileRegistry, "profileRegistry");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "向已配置的群机器人 webhook 推送一条文本消息。"
            + "channel 缺省且 Profile 仅 1 条通道时发到该通道；"
            + "指定 channel=\"<name>\" 时发到名为 <name> 的通道；"
            + "发送失败（HTTP 非 2xx / 超时 / Sandbox 拦截）会作为 tool 错误返回（success=false），不会中断 ReAct 循环。";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "arguments");

        // --- 1. 参数提取 + 校验 ---
        Object rawContent = arguments.get("content");
        if (rawContent == null || rawContent.toString().isEmpty()) {
            return errorWithPayload(null, null, "content 不能为空",
                "empty_content", null);
        }
        String content = rawContent.toString();
        int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (contentBytes > MAX_CONTENT_BYTES) {
            return errorWithPayload(null, null,
                "content 超长 (" + contentBytes + " bytes, limit=" + MAX_CONTENT_BYTES + ")",
                "content_too_long", null);
        }

        String requestedChannel = asString(arguments.get("channel"));

        // --- 2. 取 Profile + notifyChannels ---
        Profile profile = currentProfile();
        if (profile == null) {
            return errorWithPayload(null, null,
                "ProfileContext 未设置（AgentService 入口异常）",
                "no_profile_context", null);
        }
        List<NotifyChannelConfig> channels = profile.notifyChannels();

        // --- 3. 路由（单通道 MVP）---
        NotifyChannelConfig target = resolveSingleChannel(channels, requestedChannel);
        if (target == null) {
            if (channels.isEmpty()) {
                return errorWithPayload(null, null,
                    "profile 未配置 notify_channels",
                    "no_channels", null);
            }
            if (requestedChannel == null) {
                // 多通道但 LLM 未指定：MVP 阶段要求显式 channel
                return errorWithPayload(null, null,
                    "channel 不能省略: profile 配了 " + channels.size() + " 条通道"
                        + "（广播实现见 US-2 / US-3）",
                    "no_channels", null);
            }
            return errorWithPayload(null, null,
                "未知通道: " + requestedChannel,
                "unknown_channel", null);
        }

        // --- 4. 发送 ---
        NotifyResult result = adapter.send(target, content);
        log.info("notify.tool.dispatched profile={} channel={} success={} status={} durationMs={}",
            profile.name(), result.channelName(), result.success(),
            result.statusCode(), result.durationMs());

        // --- 5. 构造 ToolResult payload（含审计字段）---
        return toToolResult(result);
    }

    /**
     * 单通道路由（MVP 行为）。
     * <ul>
     *   <li>{@code requestedChannel=null} + channels.size()==1 → 返回该通道</li>
     *   <li>{@code requestedChannel="<name>"} + 命中 → 返回该通道</li>
     *   <li>其他 → 返回 null（调用方负责报错）</li>
     * </ul>
     */
    private static NotifyChannelConfig resolveSingleChannel(
            List<NotifyChannelConfig> channels, String requestedChannel) {
        if (channels.size() == 1 && requestedChannel == null) {
            return channels.get(0);
        }
        if (requestedChannel != null) {
            for (NotifyChannelConfig c : channels) {
                if (requestedChannel.equals(c.name())) {
                    return c;
                }
            }
        }
        return null;
    }

    private static ToolResult toToolResult(NotifyResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // channel 字段：成功失败都填（用于审计落库）
        payload.put("channel", result.channelName());
        payload.put("duration_ms", result.durationMs());
        payload.put("success", result.success());
        if (result.statusCode() != null) {
            payload.put("status_code", result.statusCode());
        }
        if (!result.success()) {
            payload.put("error", result.errorMessage());
            payload.put("error_class", classify(result));
            return new ToolResult(false, payload, result.errorMessage());
        }
        return ToolResult.ok(payload);
    }

    private static ToolResult errorWithPayload(String channelName, Integer statusCode,
                                               String errorMessage,
                                               String errorClass, Long durationMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (channelName != null) payload.put("channel", channelName);
        if (statusCode != null) payload.put("status_code", statusCode);
        if (durationMs != null) payload.put("duration_ms", durationMs);
        payload.put("success", false);
        payload.put("error_class", errorClass);
        payload.put("error", errorMessage);
        return new ToolResult(false, payload, errorMessage);
    }

    /**
     * 错误分类（spec §5）。仅基于 NotifyResult 的 statusCode + errorMessage 判断。
     */
    static String classify(NotifyResult result) {
        if (result.statusCode() != null) {
            return "http_error";
        }
        String err = result.errorMessage();
        if (err == null) {
            return "unknown";
        }
        String lower = err.toLowerCase();
        if (lower.contains("sandbox")) {
            return "sandbox_violation";
        }
        if (lower.contains("timeout")) {
            return "timeout";
        }
        return "network_error";
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString();
        return s.isEmpty() ? null : s;
    }

    /**
     * 从 {@link ProfileContext} + {@link ProfileRegistry} 解析当前 Profile。
     * <p>循环外调用会得到 null（调用方按"no_profile_context"错误处理）。
     */
    private Profile currentProfile() {
        Optional<ProfileContext.Snapshot> ctxOpt = ProfileContext.current();
        if (ctxOpt.isEmpty()) {
            return null;
        }
        String profileName = ctxOpt.get().profileName();
        return profileRegistry.find(profileName).orElse(null);
    }
}