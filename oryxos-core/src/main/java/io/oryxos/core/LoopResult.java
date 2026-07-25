package io.oryxos.core;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次 {@code ReActLoop.run(...)} 调用的最终结果（不可变 record）。
 *
 * <p>无论循环以哪种方式结束 —— 拿到 LLM 的无 tool_call 文本响应（{@code terminatedAtMax=false}），
 * 或触发 {@code MAX_ITERATIONS} 上限（{@code terminatedAtMax=true}）—— 都返回同样的 {@code LoopResult}。
 *
 * <p>契约条款详见 [contracts/LoopResult.md](../../../../../specs/002-react-loop/contracts/LoopResult.md) §2：
 * <ul>
 *   <li>{@code C-LR-1} finalText 必非 null、非空字符串</li>
 *   <li>{@code C-LR-2} iterations {@code >= 0}（{@code iter=0} 仅出现在
 *       {@code MAX_ITERATIONS==0} 的边界路径，对应 spec Edge case 5）</li>
 *   <li>{@code C-LR-3} {@code terminatedAtMax=true} 时 {@code finalText} 是最后一次
 *       {@code assistant(tool_call)} 内容或合成"loop terminated at max_iterations"标记</li>
 *   <li>{@code C-LR-7} {@code iterations == 0} ⇒
 *       {@code finalText == "loop not configured"} 且 {@code terminatedAtMax == true}</li>
 * </ul>
 */
public record LoopResult(
    String finalText,                // 用户最终可见的回复文本（必非 null、非空）
    int iterations,                  // 实际迭代次数；0 <= iterations <= MAX_ITERATIONS + 1
                                     //   iter=0 仅出现在 MAX_ITERATIONS==0 路径（spec Edge case 5 / C-LR-7）
                                     //   iter>=1 出现在正常 Reason 路径（spec FR-013 (a) 或 (b)）
    boolean terminatedAtMax,         // true = 循环因 MAX_ITERATIONS 截断（spec FR-013 (b) 路径 + Edge case 5 静态退出）
    String profileName,              // 当前 Profile 名（用于日志聚合）
    UUID sessionId                   // 当前 Session UUID
) {
    public LoopResult {
        Objects.requireNonNull(finalText, "finalText");
        if (finalText.isEmpty()) {
            throw new IllegalArgumentException("finalText empty");
        }
        if (iterations < 0) {
            throw new IllegalArgumentException(
                "iterations must be >= 0 (loop ran negative iterations)");
        }
        Objects.requireNonNull(profileName, "profileName");
        Objects.requireNonNull(sessionId, "sessionId");
        // C-LR-7：iter=0 仅在 MAX_ITERATIONS==0 的防御性退出路径出现
        if (iterations == 0 && !"loop not configured".equals(finalText)) {
            throw new IllegalArgumentException(
                "iterations=0 must be paired with finalText=\"loop not configured\" (C-LR-7)");
        }
    }
}
