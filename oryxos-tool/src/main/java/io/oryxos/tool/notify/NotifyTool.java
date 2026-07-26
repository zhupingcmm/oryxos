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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * {@code notify} Tool —— LLM 通过 Function Calling 触发 Notify 出站推送。
 *
 * <p>核心阶段路由规则（spec FR-006 / FR-007 §3 + §3.1）：
 * <ul>
 *   <li><b>显式 channel</b>：{@code channel="<name>"} → 仅发到名为 <name> 的通道；不存在 → 报错</li>
 *   <li><b>单通道默认</b>：{@code channel=null} + Profile 仅 1 条通道 → 发到该通道</li>
 *   <li><b>广播</b>：{@code channel=null} + Profile 配 N 条通道 + Profile.extra.broadcast=true → 并行发到全部 N 条</li>
 *   <li><b>未声明广播</b>：{@code channel=null} + N>=2 + 未声明 broadcast → 报错要求显式 channel</li>
 *   <li><b>未配置通道</b>：Profile 未配 → 报错</li>
 * </ul>
 *
 * <p>广播路径用 JDK 21 virtual threads + {@link CompletableFuture#allOf} 聚合（spec NFR-003）：
 * <ul>
 *   <li>所有 N 条并行发；wall-time ≈ 最慢那条</li>
 *   <li>聚合语义：全成功 → {@code success=true, errorMessage=null}；部分成功 → {@code success=true, errorMessage="partial: ..."}；
 *       全失败 → {@code success=false, errorMessage="all failed: ..."}</li>
 *   <li>审计字段（被 {@link io.oryxos.core.DefaultToolExecutor} 读取）：
 *       {@code channel} = "{@code a;b;c}"（{@code ;} 分隔）；{@code status_code} = 最差那条（非 2xx 优先于 2xx；多条非 2xx 取 max；全网络错误 → null）</li>
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

    /** Profile.extra.broadcast key（spec FR-007 §3.1 广播开关）。 */
    public static final String BROADCAST_KEY = "broadcast";

    /**
     * 默认通道名（spec FR-006 优先级 #1）。
     *
     * <p>当 Profile.notifyChannels 含有名为 {@code "default"} 的通道且 LLM 不显式指定
     * {@code channel} 参数时，NotifyTool MUST 路由到该通道（不论 N 是否大于 1）。
     * 这条规则优先于 MVP 单通道（N==1）路径与 N>=2 未声明 broadcast 的报错路径；
     * 仅被显式 {@code extra.broadcast=true} 在 N>=2 时覆盖（走广播分支）。
     */
    public static final String DEFAULT_CHANNEL_NAME = "default";

    /** 审计 channel 字段分隔符（spec §7 I-NT-4）。 */
    static final String CHANNEL_DELIM = ";";

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
            + "路由规则（spec FR-006 优先级表）: "
            + "(a) channel 缺省 + Profile 含名为 default 的通道 → 发到 default（不论 N）；"
            + "(b) 指定 channel=\"<name>\" → 仅发到名为 <name> 的通道; <name> 不存在则报错；"
            + "(c) channel 缺省 + Profile 仅 1 条通道（且无 default 命名） → 发到该唯一通道；"
            + "(d) channel 缺省 + Profile 配 N>=2 条通道 + Profile.extra.broadcast=true → 并行广播到全部 N 条；"
            + "(e) channel 缺省 + N>=2 但未声明 broadcast → 报错要求显式 channel；"
            + "(f) Profile 未配 notify_channels → 报错。"
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

        // --- 3. 路由 ---
        return routeAndSend(profile, channels, requestedChannel, content);
    }

    /**
     * 路由 + 发送（T047 US-4 阶段固化 + T062 Phase 8 收敛补 FR-006 优先级 #1）。
     *
     * <p>决策顺序（spec FR-006 路由优先级表）：
     * <ol>
     *   <li>{@code requestedChannel != null} → 按名查找；找不到 → 错误</li>
     *   <li>channels 为空 → "no_channels" 错误</li>
     *   <li>channels 含名为 {@code "default"} 的通道 → 路由到 default（spec FR-006 优先级 #1，
     *       主语义；不论 N 是否大于 1；T062 阶段补；broadcast=true 时此步仍优先生效，因为
     *       {@link #isBroadcast} 仅在 N>=2 时触发分支，{@code default} 单条走单通道结果）</li>
     *   <li>channels.size() == 1 → 发到该唯一通道（无 default 命名时的 MVP 单通道语义）</li>
     *   <li>channels.size() >= 2 + broadcast=true → 并行广播</li>
     *   <li>其他（无 default + N>=2 + 未声明 broadcast） → "channel 不能省略" 错误</li>
     * </ol>
     */
    private ToolResult routeAndSend(Profile profile,
                                    List<NotifyChannelConfig> channels,
                                    String requestedChannel,
                                    String content) {
        // (1) 显式 channel
        if (requestedChannel != null) {
            NotifyChannelConfig target = findByName(channels, requestedChannel);
            if (target == null) {
                return errorWithPayload(null, null,
                    "未知通道: " + requestedChannel,
                    "unknown_channel", null);
            }
            NotifyResult result = adapter.send(target, content);
            logDispatch(profile, result);
            return toSingleToolResult(result);
        }

        // (2) 空 channels
        if (channels.isEmpty()) {
            return errorWithPayload(null, null,
                "profile 未配置 notify_channels",
                "no_channels", null);
        }

        // (3) 命名 default 通道（spec FR-006 优先级 #1，T062 阶段补；不论 N）
        NotifyChannelConfig namedDefault = findByName(channels, DEFAULT_CHANNEL_NAME);
        if (namedDefault != null) {
            NotifyResult result = adapter.send(namedDefault, content);
            logDispatch(profile, result);
            return toSingleToolResult(result);
        }

        // (4) 单通道默认（无 default 命名时的 MVP 语义）
        if (channels.size() == 1) {
            NotifyResult result = adapter.send(channels.get(0), content);
            logDispatch(profile, result);
            return toSingleToolResult(result);
        }

        // (5) 多通道 + broadcast 标记
        if (isBroadcast(profile)) {
            return broadcastAndAggregate(profile, channels, content);
        }

        // (6) 多通道但未声明广播
        return errorWithPayload(null, null,
            "channel 不能省略: profile 配了 " + channels.size() + " 条通道"
                + "（广播需 Profile.extra.broadcast=true）",
            "no_channels", null);
    }

    /**
     * 广播路径 —— 用 JDK 21 virtual threads 并行提交 N 条 send，
     * {@link CompletableFuture} 等齐后按 channels 输入顺序 join 聚合
     * （spec NFR-003 / FR-007 §3.1）。
     *
     * <p>结果顺序保证与输入 channels 一致：{@code results[i]} 对应 {@code channels[i]}。
     * 即使 virtual thread 完成顺序非确定，最终 results list 仍按输入顺序排，
     * 便于审计落库 channel = "{@code a;b;c}" 与 per-channel results 索引对齐。
     */
    private ToolResult broadcastAndAggregate(Profile profile,
                                             List<NotifyChannelConfig> channels,
                                             String content) {
        log.info("notify.broadcast.start profile={} channels={}",
            profile.name(), channels.size());

        List<NotifyResult> results = new ArrayList<>(channels.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<NotifyResult>> futures = new ArrayList<>(channels.size());
            for (NotifyChannelConfig c : channels) {
                futures.add(CompletableFuture.supplyAsync(
                    () -> adapter.send(c, content), executor));
            }
            // 按 channels 输入顺序 join —— 保证 results[i] = channels[i] 的结果
            // 即使 virtual thread 完成顺序非确定，最终顺序仍稳定
            for (CompletableFuture<NotifyResult> f : futures) {
                results.add(f.join());
            }
        }

        log.info("notify.broadcast.done profile={} success={}/{}",
            profile.name(),
            results.stream().filter(NotifyResult::success).count(),
            results.size());

        return aggregate(results);
    }

    /**
     * 聚合 N 条 {@link NotifyResult} 为单条 {@link ToolResult}（T048 US-4 阶段固化）。
     *
     * <p>聚合语义（spec §4.4-4.6）：
     * <ul>
     *   <li>全部成功 → {@code success=true, errorMessage=null}</li>
     *   <li>部分成功 → {@code success=true, errorMessage="partial: <失败明细>"}</li>
     *   <li>全部失败 → {@code success=false, errorMessage="all failed: <失败明细>"}</li>
     * </ul>
     *
     * <p>审计字段写入 payload（{@link io.oryxos.core.DefaultToolExecutor} 读取）：
     * <ul>
     *   <li>{@code channel} = "{@code name1;name2;...}"</li>
     *   <li>{@code status_code} = 最差（spec FR-007 §3.1 优先级规则）</li>
     *   <li>{@code duration_ms} = 最长那条</li>
     * </ul>
     */
    static ToolResult aggregate(List<NotifyResult> results) {
        Objects.requireNonNull(results, "results");
        if (results.isEmpty()) {
            // 防御性：调用方应保证至少一条
            return new ToolResult(false, Map.of("error", "no results"), "no results");
        }

        boolean allSuccess = results.stream().allMatch(NotifyResult::success);
        boolean anySuccess = results.stream().anyMatch(NotifyResult::success);
        int successCount = (int) results.stream().filter(NotifyResult::success).count();

        // payload
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("broadcast", true);
        payload.put("channel", results.stream()
            .map(NotifyResult::channelName)
            .collect(Collectors.joining(CHANNEL_DELIM)));
        Integer worstCode = worstStatusCode(results);
        if (worstCode != null) {
            payload.put("status_code", worstCode);
        }
        long maxDuration = results.stream()
            .mapToLong(NotifyResult::durationMs)
            .max().orElse(0L);
        payload.put("duration_ms", maxDuration);
        payload.put("success_count", successCount);

        // per-channel results（spec §4.4 payload shape）
        List<Map<String, Object>> perChannel = new ArrayList<>(results.size());
        for (NotifyResult r : results) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("channel", r.channelName());
            entry.put("success", r.success());
            entry.put("duration_ms", r.durationMs());
            if (r.statusCode() != null) entry.put("status_code", r.statusCode());
            if (!r.success() && r.errorMessage() != null) {
                entry.put("error", r.errorMessage());
            }
            perChannel.add(entry);
        }
        payload.put("results", perChannel);

        // errorMessage
        if (allSuccess) {
            return ToolResult.ok(payload);
        }
        String summary = failureSummary(results);
        if (!anySuccess) {
            // 全失败
            return new ToolResult(false, payload, "all failed: " + summary);
        }
        // 部分失败 → success=true（聚合语义）
        return new ToolResult(true, payload, "partial: " + summary);
    }

    /**
     * "最差" status code 选择（spec FR-007 §3.1）：
     * <ul>
     *   <li>全 2xx → null（"无最差值"，spec §4.4）</li>
     *   <li>全 null（网络错误）→ null</li>
     *   <li>2xx + 非 2xx 混合 → 非 2xx（避免 {@code Math.max(Integer, null)} 把 null 当最小）</li>
     *   <li>多条非 2xx → 数字最大</li>
     * </ul>
     */
    static Integer worstStatusCode(List<NotifyResult> results) {
        Integer worst = null;
        for (NotifyResult r : results) {
            Integer code = r.statusCode();
            if (code == null) continue;
            // 只把非 2xx 视为"worst"；2xx 视为 OK
            if (code < 400) {
                continue;
            }
            if (worst == null) {
                worst = code;
            } else if (code >= 400 && worst >= 400) {
                // 两条都非 2xx → 取 max
                worst = Math.max(worst, code);
            }
            // 不会从 null 跳到 2xx（continue 过滤了 2xx）
        }
        return worst;
    }

    /** 失败明细聚合（spec §4.4-4.6 errorMessage 格式）。 */
    static String failureSummary(List<NotifyResult> results) {
        return results.stream()
            .filter(r -> !r.success())
            .map(r -> {
                String codeOrClass = r.statusCode() != null
                    ? r.statusCode().toString()
                    : classify(r);
                return r.channelName() + "=" + codeOrClass;
            })
            .collect(Collectors.joining("; "));
    }

    private static boolean isBroadcast(Profile profile) {
        Object v = profile.extra() == null ? null : profile.extra().get(BROADCAST_KEY);
        return Boolean.TRUE.equals(v);
    }

    private static NotifyChannelConfig findByName(List<NotifyChannelConfig> channels,
                                                  String name) {
        for (NotifyChannelConfig c : channels) {
            if (name.equals(c.name())) {
                return c;
            }
        }
        return null;
    }

    private static void logDispatch(Profile profile, NotifyResult result) {
        log.info("notify.tool.dispatched profile={} channel={} success={} status={} durationMs={}",
            profile.name(), result.channelName(), result.success(),
            result.statusCode(), result.durationMs());
    }

    /** 单通道结果转 ToolResult（payload 含 channel + status_code + duration_ms + success）。 */
    private static ToolResult toSingleToolResult(NotifyResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
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