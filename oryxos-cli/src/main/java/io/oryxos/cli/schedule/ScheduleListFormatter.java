package io.oryxos.cli.schedule;

import io.oryxos.core.scheduler.AgentScheduler;
import io.oryxos.core.scheduler.AgentScheduler.ScheduleView;

import java.io.PrintWriter;
import java.time.Instant;
import java.util.List;

/**
 * 008-agent-scheduler 阶段 —— {@code oryxos schedule list} 的表格格式化器。
 *
 * <p>列头：{@code TASK_ID} / {@code PROFILE} / {@code CRON} / {@code ZONE} / {@code ENABLED}
 *       / {@code NEXT_RUN_AT_UTC}（per contracts §7.1 字节级契约）。
 *
 * <p>列宽自适应 —— 每列取数据最长值，列头至少 8 字符宽；空 schedule 走
 * {@code "(no schedules)"} 占位（与 {@code profile list} / {@code session list} 一致）。
 *
 * <p>无 Spring / 无 IO 依赖 —— 纯函数（{@link #format}），便于 unit test 直接覆盖。
 */
public final class ScheduleListFormatter {

    private ScheduleListFormatter() {}

    /**
     * 格式化输出 schedule 列表到 {@link PrintWriter}。
     *
     * @param out       输出流（{@code System.out} 或测试里的 {@code StringWriter}）
     * @param schedules 来自 {@link AgentScheduler#listSchedules()} 的视图集合（已按 taskId 排序）
     */
    public static void format(PrintWriter out, List<ScheduleView> schedules) {
        if (schedules == null || schedules.isEmpty()) {
            out.println("(no schedules)");
            out.flush();
            return;
        }

        // 列宽自适应：每列取 (列头, 数据) 最长值
        int wTaskId = Math.max("TASK_ID".length(), longest(schedules, ScheduleView::taskId));
        int wProfile = Math.max("PROFILE".length(), longest(schedules, ScheduleView::profileName));
        int wCron = Math.max("CRON".length(), longest(schedules, ScheduleView::cron));
        int wZone = Math.max("ZONE".length(), longest(schedules, ScheduleView::zone, ""));
        int wEnabled = Math.max("ENABLED".length(), 5);
        int wNextRun = Math.max("NEXT_RUN_AT_UTC".length(),
            longest(schedules, s -> formatInstant(s.nextRunAtUtc())));

        // 表头
        out.printf("%-" + wTaskId + "s  %-" + wProfile + "s  %-" + wCron + "s  %-"
                + wZone + "s  %-" + wEnabled + "s  %-" + wNextRun + "s%n",
            "TASK_ID", "PROFILE", "CRON", "ZONE", "ENABLED", "NEXT_RUN_AT_UTC");
        // 数据行
        for (ScheduleView s : schedules) {
            out.printf("%-" + wTaskId + "s  %-" + wProfile + "s  %-" + wCron + "s  %-"
                    + wZone + "s  %-" + wEnabled + "s  %-" + wNextRun + "s%n",
                nullSafe(s.taskId()),
                nullSafe(s.profileName()),
                nullSafe(s.cron()),
                nullSafe(s.zone()),
                s.enabled() ? "true" : "false",
                formatInstant(s.nextRunAtUtc()));
        }
        out.flush();
    }

    // --- helpers ---

    private static int longest(List<ScheduleView> schedules,
                               java.util.function.Function<ScheduleView, String> getter) {
        return longest(schedules, getter, null);
    }

    private static int longest(List<ScheduleView> schedules,
                               java.util.function.Function<ScheduleView, String> getter,
                               String fallback) {
        int max = 0;
        for (ScheduleView s : schedules) {
            String v = getter.apply(s);
            if (v == null) v = fallback;
            if (v != null && v.length() > max) {
                max = v.length();
            }
        }
        return max;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** ISO-8601 UTC（{@code null} → {@code "-"}）便于列对齐 + 阅读。 */
    private static String formatInstant(Instant i) {
        return i == null ? "-" : i.toString();
    }
}