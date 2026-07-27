package io.oryxos.tool.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Sandbox enforce() 性能基准 —— T016 [US4]。
 *
 * <p>性能预算（spec NFR-007 / contracts/sandbox-whitelist.md §7）：
 * <ul>
 *   <li>P95 latency ≤ 5ms（Application-layer 白名单校验，热路径，不应成为 ReAct 循环瓶颈）</li>
 *   <li>覆盖全部 4 类 ActionType：FILE_READ / FILE_WRITE / SHELL_COMMAND / HTTP_REQUEST</li>
 *   <li>每类型 1000 次 warm + 1000 次 measured；共 4000 次 enforce()</li>
 * </ul>
 *
 * <p>使用 {@link System#nanoTime()} 单线程测量；CI Windows runner 容许 ≤ 30ms P95（性能门禁）。
 * 真正硬约束 P95 ≤ 5ms 是 PRD 级目标，验收为热路径 5ms 同期；CI 资源不同时放宽。
 */
class SandboxPerformanceBenchmarkIT {

    private static final int WARMUP_ITERATIONS = 1000;
    private static final int MEASURE_ITERATIONS = 1000;
    /**
     * CI 性能门槛（远高于 PRD 5ms 目标；Jenkins/Docker runner 比本机热路径慢 5-10×）。
     * 仅供性能趋势监测；不能因为某次慢 runner 失败挂整体。
     */
    private static final long CI_P95_BUDGET_MS = 30;

    @Test
    @DisplayName("HTTP_REQUEST enforce() P95 latency ≤ CI 预算（典型 ≤ 5ms PRD）")
    void httpEnforceP95Latency() {
        List<String> allowed = List.of("api.example.com", "*.trusted.org", "localhost");
        Sandbox sb = new WhitelistSandbox(allowed);

        SandboxAction allowAction = new SandboxAction(ActionType.HTTP_REQUEST,
            "https://api.example.com/v1/users");
        SandboxAction rejectAction = new SandboxAction(ActionType.HTTP_REQUEST,
            "https://evil.example.com/hook");

        // warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            try { sb.enforce(allowAction); } catch (Exception ignored) { }
            try { sb.enforce(rejectAction); } catch (Exception ignored) { }
        }

        List<Long> samplesNs = new ArrayList<>(MEASURE_ITERATIONS * 2);
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            samplesNs.add(measureOnce(sb, allowAction));
            samplesNs.add(measureOnce(sb, rejectAction));
        }

        reportP95("HTTP_REQUEST", samplesNs);
    }

    @Test
    @DisplayName("FILE_READ enforce() P95 latency ≤ CI 预算")
    void fileReadEnforceP95Latency() {
        List<String> allowed = List.of("/home/agent/workspace", "/opt/data");
        Sandbox sb = new WhitelistSandbox(allowed);

        SandboxAction allowAction = new SandboxAction(ActionType.FILE_READ, "notes.md");
        SandboxAction rejectAction = new SandboxAction(ActionType.FILE_READ, "/etc/passwd");

        // warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            try { sb.enforce(allowAction); } catch (Exception ignored) { }
            try { sb.enforce(rejectAction); } catch (Exception ignored) { }
        }

        List<Long> samplesNs = new ArrayList<>(MEASURE_ITERATIONS * 2);
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            samplesNs.add(measureOnce(sb, allowAction));
            samplesNs.add(measureOnce(sb, rejectAction));
        }

        reportP95("FILE_READ", samplesNs);
    }

    @Test
    @DisplayName("FILE_WRITE enforce() P95 latency ≤ CI 预算")
    void fileWriteEnforceP95Latency() {
        List<String> allowed = List.of("/home/agent/workspace");
        Sandbox sb = new WhitelistSandbox(allowed);

        SandboxAction action = new SandboxAction(ActionType.FILE_WRITE, "out.md");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            try { sb.enforce(action); } catch (Exception ignored) { }
        }

        List<Long> samplesNs = new ArrayList<>(MEASURE_ITERATIONS);
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            samplesNs.add(measureOnce(sb, action));
        }

        reportP95("FILE_WRITE", samplesNs);
    }

    @Test
    @DisplayName("SHELL_COMMAND enforce() P95 latency ≤ CI 预算")
    void shellEnforceP95Latency() {
        List<String> allowed = List.of("git", "ls", "echo", "cat");
        Sandbox sb = new WhitelistSandbox(allowed);

        SandboxAction allowAction = new SandboxAction(ActionType.SHELL_COMMAND, "git status");
        SandboxAction rejectAction = new SandboxAction(ActionType.SHELL_COMMAND, "rm -rf /");

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            try { sb.enforce(allowAction); } catch (Exception ignored) { }
            try { sb.enforce(rejectAction); } catch (Exception ignored) { }
        }

        List<Long> samplesNs = new ArrayList<>(MEASURE_ITERATIONS * 2);
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            samplesNs.add(measureOnce(sb, allowAction));
            samplesNs.add(measureOnce(sb, rejectAction));
        }

        reportP95("SHELL_COMMAND", samplesNs);
    }

    /** 单次测量（忽略异常）。无 sandbox 级 GC / JIT 干扰外影响。 */
    private static long measureOnce(Sandbox sb, SandboxAction action) {
        long start = System.nanoTime();
        try { sb.enforce(action); } catch (Exception ignored) { }
        return System.nanoTime() - start;
    }

    private static void reportP95(String label, List<Long> samplesNs) {
        Collections.sort(samplesNs);
        // 简单 percentile：P95 = index = samples.length * 0.95（向下取整）
        int p95Idx = (int) Math.floor(samplesNs.size() * 0.95);
        if (p95Idx >= samplesNs.size()) p95Idx = samplesNs.size() - 1;
        long p95Ns = samplesNs.get(p95Idx);
        long p99Ns = samplesNs.get((int) Math.floor(samplesNs.size() * 0.99));
        long p50Ns = samplesNs.get(samplesNs.size() / 2);
        long p95Ms = p95Ns / 1_000_000L;
        long p95Us = p95Ns / 1_000L;

        // 输出到 surefire stdout（CI 抓取日志即可）
        System.out.printf("[perf] %s: samples=%d, P50=%dns, P95=%dns (%d.%03dms), P99=%dns%n",
            label, samplesNs.size(), p50Ns, p95Ns, p95Ms, p95Us % 1000, p99Ns);

        // CI 性能预算门禁
        if (p95Ms > CI_P95_BUDGET_MS) {
            throw new AssertionError(String.format(
                Locale.ROOT,
                "%s P95 %dms 超过 CI 预算 %dms（PRD 目标 ≤ 5ms —— 详见 contracts/sandbox-whitelist.md §7）",
                label, p95Ms, CI_P95_BUDGET_MS));
        }
    }
}