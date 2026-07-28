package io.oryxos.cli.schedule;

import io.oryxos.core.scheduler.AgentScheduler.ScheduleView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 008-agent-scheduler / T033 — {@link ScheduleListFormatter} 字节级契约测试。
 *
 * <p>5 个场景对齐 contracts §7.2：
 * <ol>
 *   <li>无 schedule → {@code "(no schedules)"} 占位</li>
 *   <li>1 条 schedule → 表头 + 1 行数据</li>
 *   <li>N 条 schedule → 表头 + N 行</li>
 *   <li>字段含 null（nextRunAtUtc=null + zone=null）→ 不崩、显示 {@code "-"} / 空字符串</li>
 *   <li>{@code enabled=false} 行 → 第 5 列输出 {@code "false"}</li>
 * </ol>
 */
class ScheduleListFormatterTest {

    @Test
    @DisplayName("ScheduleListFormatter 契约场景 1：空列表 → (no schedules) 占位")
    void emptySchedulesShowsPlaceholder() {
        String text = format(List.of());
        assertTrue(text.contains("(no schedules)"),
            "空列表必须输出 (no schedules) 占位; got: " + text);
    }

    @Test
    @DisplayName("ScheduleListFormatter 契约场景 2：1 条 schedule → 表头 + 1 行数据")
    void singleScheduleRendersHeaderAndRow() {
        String text = format(List.of(view(
            "weather-bot:morning",
            "weather-bot",
            "0 8 * * *",
            "Asia/Shanghai",
            true,
            Instant.parse("2026-07-29T01:00:00Z"),
            null
        )));
        assertTrue(text.contains("TASK_ID"), "表头含 TASK_ID");
        assertTrue(text.contains("PROFILE"), "表头含 PROFILE");
        assertTrue(text.contains("CRON"), "表头含 CRON");
        assertTrue(text.contains("ZONE"), "表头含 ZONE");
        assertTrue(text.contains("ENABLED"), "表头含 ENABLED");
        assertTrue(text.contains("NEXT_RUN_AT_UTC"), "表头含 NEXT_RUN_AT_UTC");
        assertTrue(text.contains("weather-bot:morning"), "数据行含 taskId");
        assertTrue(text.contains("0 8 * * *"), "数据行含 cron");
        assertTrue(text.contains("Asia/Shanghai"), "数据行含 zone");
        assertTrue(text.contains("true"), "data row 含 enabled=true");
        assertTrue(text.contains("2026-07-29T01:00:00Z"), "data row 含 nextRunAtUtc ISO-8601");
    }

    @Test
    @DisplayName("ScheduleListFormatter 契约场景 3：3 条 schedule → 表头 + 3 行")
    void multipleSchedulesRenderRows() {
        String text = format(List.of(
            view("a:morning", "a", "0 8 * * *", "UTC", true, Instant.parse("2026-07-29T08:00:00Z"), null),
            view("b:noon",   "b", "0 12 * * *", "UTC", true, Instant.parse("2026-07-29T12:00:00Z"), null),
            view("c:evening","c", "0 20 * * *", "UTC", true, Instant.parse("2026-07-29T20:00:00Z"), null)
        ));
        assertTrue(text.contains("a:morning"));
        assertTrue(text.contains("b:noon"));
        assertTrue(text.contains("c:evening"));
        // 至少 4 行（1 表头 + 3 数据）
        long rows = text.lines().filter(l -> !l.isBlank()).count();
        assertTrue(rows >= 4, "≥4 行 (1 表头 + 3 数据); got: " + rows);
    }

    @Test
    @DisplayName("ScheduleListFormatter 契约场景 4：nextRunAtUtc=null / zone=null → 显示 - / 空")
    void nullableFieldsHandledGracefully() {
        String text = format(List.of(view(
            "x:unknown", "x", "0 0 * * *", null, true, /* nextRunAtUtc */ null, null
        )));
        assertTrue(text.contains("x:unknown"), "taskId 仍渲染");
        assertTrue(text.contains("-"), "null nextRunAtUtc → '-' 占位");
        assertTrue(!text.contains("NullPointerException"),
            "null zone 必不崩；got: " + text);
    }

    @Test
    @DisplayName("ScheduleListFormatter 契约场景 5：enabled=false → 第 5 列输出 false")
    void disabledScheduleShowsFalseInEnabledColumn() {
        String text = format(List.of(view(
            "x:off", "x", "0 0 * * *", "UTC", false, Instant.parse("2026-07-29T00:00:00Z"), null
        )));
        assertTrue(text.contains("false"), "enabled=false 应渲染 'false'; got: " + text);
        assertTrue(!text.contains("true\n"), "不该有孤悬的 true");
    }

    @Test
    @DisplayName("ScheduleListFormatter 契约场景 6（额外）：列宽自适应 → 长 taskId 把 TASK_ID 列撑宽")
    void columnWidthAdaptsToLongestValue() {
        // 长 taskId
        String text = format(List.of(view(
            "long-profile-name:long-schedule-id",
            "long-profile-name",
            "* * * * *",
            "Asia/Shanghai",
            true,
            Instant.parse("2026-07-29T00:00:00Z"),
            null
        )));
        // 表头 + 数据 —— 数据行字段必不被截断
        assertTrue(text.contains("long-profile-name:long-schedule-id"));
        assertTrue(text.contains("long-profile-name"));
    }

    @Test
    @DisplayName("ScheduleListFormatter 契约场景 7（额外）：列宽自适应 → 当列头比数据长时，列宽取列头宽度")
    void columnWidthTakesHeaderMinWhenDataShort() {
        // 短 taskId → 表头宽度"TASK_ID"(7) 应该让列至少 7 字符宽
        String text = format(List.of(view(
            "ab", "p", "0 0 * * *", "UTC", true, Instant.parse("2026-07-29T00:00:00Z"), null
        )));
        String header = text.lines().findFirst().orElseThrow();
        // TASK_ID 7 + 2 空格 → 至少 7 字符 + 2
        assertTrue(header.startsWith("TASK_ID"),
            "表头首列必为 TASK_ID; got: " + header);
        // 表头长度 ≥ 30（6 列 × 列宽 + 5 个 2 空格间隔）
        assertTrue(header.length() >= 30, "表头总宽 ≥ 30; got: " + header.length());
    }

    @Test
    @DisplayName("ScheduleListFormatter 契约场景 8（额外）：null 输入 → (no schedules) 占位，不抛 NPE")
    void nullInputShowsPlaceholder() {
        String text = format(null);
        assertEquals("(no schedules)", text.trim(),
            "null 输入 → (no schedules) 占位; got: " + text);
    }

    // --- helpers ---

    private static String format(List<ScheduleView> views) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(out, true, StandardCharsets.UTF_8);
        ScheduleListFormatter.format(pw, views);
        pw.flush();
        return out.toString(StandardCharsets.UTF_8);
    }

    private static ScheduleView view(
        String taskId, String profile, String cron, String zone,
        boolean enabled, Instant nextRunAtUtc, Instant lastRunAtUtc
    ) {
        // ScheduleView 是 AgentScheduler 的内部 record —— 走其访问器构造
        return new ScheduleView(taskId, profile, cron, zone, "msg", enabled, nextRunAtUtc, lastRunAtUtc);
    }
}