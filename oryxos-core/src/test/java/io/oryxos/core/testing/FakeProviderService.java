package io.oryxos.core.testing;

import io.oryxos.core.LlmRequest;
import io.oryxos.core.LlmResponse;
import io.oryxos.core.ProviderService;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * {@link ProviderService} 的测试替身 —— 按预先排好的 {@link LlmResponse} 队列依次返回。
 *
 * <p>每次 {@link #invoke(String, LlmRequest)} 调用从队首 poll 一个 LlmResponse；队列耗尽时抛
 * {@link IllegalStateException}（让测试代码无法静默漏判真实调用次数）。
 *
 * <p>典型用法：
 * <pre>{@code
 *   FakeProviderService fake = new FakeProviderService(List.of(
 *       LlmResponse("hi", List.of(), emptyUsage, "stop"),
 *       LlmResponse(null, List.of(toolCall_a), emptyUsage, "tool_calls")
 *   ));
 * }</pre>
 */
public final class FakeProviderService implements ProviderService {

    private final Deque<LlmResponse> queue = new ArrayDeque<>();

    /** 调用次数计数器 —— 测试断言用。 */
    private int invocationCount = 0;

    public FakeProviderService() {
    }

    public FakeProviderService(List<LlmResponse> preset) {
        if (preset != null) {
            queue.addAll(preset);
        }
    }

    /** 追加一个预设响应（用于多次"setup"). */
    public void enqueue(LlmResponse response) {
        if (response == null) {
            throw new IllegalArgumentException("response must not be null");
        }
        queue.addLast(response);
    }

    public int invocationCount() {
        return invocationCount;
    }

    public int queueSize() {
        return queue.size();
    }

    @Override
    public LlmResponse invoke(String providerName, LlmRequest request) {
        invocationCount++;
        LlmResponse next = queue.pollFirst();
        if (next == null) {
            throw new IllegalStateException(
                "test stub empty: FakeProviderService queue exhausted at invocation "
                    + invocationCount + " for provider='" + providerName
                    + "' (check test expectations match ReActLoop behavior)");
        }
        return next;
    }
}
