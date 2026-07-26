package io.oryxos.tool.javabean;

import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code echo} Java Bean Tool 示例 —— 演示 Plugin Tool 重代码接入（spec FR-008 第 3 档）。
 *
 * <p>对应 [research.md R-06](../../../../../../../specs/005-tool-system/research.md)：
 * 用户写 {@code @Component implements OryxTool} → Spring 自动发现 → {@link ToolRegistry}
 * 走 {@code resolveSource("...io.oryxos.tool.javabean.EchoTool") == "java_bean"}。
 *
 * <p>总行数（含 license header / imports）应 ≤ 100（spec SC-007）。
 *
 * <p>这类 Tool 的协议：{@code args.text: String} → success 返 {@code payload.text} 与调用次数；
 * 异常走 {@link DefaultToolExecutor} 兜底 → ToolResult.error。
 */
@Component
public class EchoTool implements OryxTool {

    public static final String NAME = "echo";
    private final java.util.concurrent.atomic.AtomicLong calls =
        new java.util.concurrent.atomic.AtomicLong();

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "回显输入字符串（Java Bean Tool 示例，演示重代码接入）";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        long n = calls.incrementAndGet();
        Object raw = arguments == null ? null : arguments.get("text");
        if (raw == null) {
            return ToolResult.error("echo: missing required argument 'text'");
        }
        String text = raw.toString();
        return ToolResult.ok(Map.of(
            "text", text,
            "calls", n
        ));
    }

    /** 暴露给测试做断言。 */
    public long calls() { return calls.get(); }
}
