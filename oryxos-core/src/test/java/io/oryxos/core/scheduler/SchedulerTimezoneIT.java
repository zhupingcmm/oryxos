package io.oryxos.core.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 008-agent-scheduler US-4 / FR-009 —— IANA 时区 + DST IT。
 *
 * <p>策略：不依赖 {@code user.timezone} JVM flag（CI 上不可控），改用
 * {@link Instant#atZone(ZoneId)} 直接喂入 zone + UTC Instant —— 在 zone 内的"凌晨 0 点"
 * 触发必然落在我期望的 UTC 时刻。
 *
 * <p>三个 case 字节级断言：
 * <ol>
 *   <li>Asia/Shanghai + {@code 0 9 * * *} → nextRunAt = 01:00:00Z（Shanghai 是 UTC+8）</li>
 *   <li>America/New_York + {@code 0 9 * * *} + DST 切换日 → cron tick 落 EDT 而非 EST</li>
 *   <li>非法 zone 字符串 → {@link IllegalArgumentException}（C-CE-2 fail-closed）</li>
 * </ol>
 */
class SchedulerTimezoneIT {

    @Test
    @DisplayName("FR-009：Asia/Shanghai + 0 9 * * * → nextRunAt = 01:00:00Z")
    void shanghaiDailyNineAm() {
        CronEvaluator evaluator = new CronEvaluatorImpl("0 9 * * *", "Asia/Shanghai");

        // 从 2026-06-15 00:00:00 UTC 出发（Shanghai = 08:00，下一次 09:00 = 1 小时后）
        Instant from = Instant.parse("2026-06-15T00:00:00Z");
        Instant next = evaluator.nextRunAt(from);

        assertEquals(Instant.parse("2026-06-15T01:00:00Z"), next,
            "Shanghai 09:00 → UTC+8 offset → 01:00:00Z; got " + next);
    }

    @Test
    @DisplayName("FR-009：Asia/Shanghai + 0 9 * * * 从 Shanghai 16:00 后出发 → 跳到次日 01:00:00Z")
    void shanghaiDailyNineAmRollover() {
        CronEvaluator evaluator = new CronEvaluatorImpl("0 9 * * *", "Asia/Shanghai");

        // 2026-06-15 12:00:00 UTC = Shanghai 20:00；下一次 09:00 = 次日 Shanghai 时间 → 01:00:00Z
        Instant from = Instant.parse("2026-06-15T12:00:00Z");
        Instant next = evaluator.nextRunAt(from);

        assertEquals(Instant.parse("2026-06-16T01:00:00Z"), next,
            "Shanghai 09:00 次日 → UTC 01:00:00Z 次日; got " + next);
    }

    @Test
    @DisplayName("FR-009：America/New_York DST 切换后 cron tick 走 EDT 而非 EST")
    void newYorkDstTransition() {
        CronEvaluator evaluator = new CronEvaluatorImpl("0 9 * * *", "America/New_York");

        // 2026-03-08 是 DST spring-forward（02:00 EST → 03:00 EDT）
        // 当天 09:00 EDT = 13:00 UTC（不是 14:00）
        Instant from = Instant.parse("2026-03-08T00:00:00Z");
        Instant next = evaluator.nextRunAt(from);

        assertEquals(Instant.parse("2026-03-08T13:00:00Z"), next,
            "2026-03-08 是 DST spring-forward；NY 09:00 EDT = 13:00 UTC（不是 14:00 UTC）; got " + next);

        // 对照：DST 前一天 (2026-03-07) 09:00 EST = 14:00 UTC（EDT 未生效）
        Instant fromDayBefore = Instant.parse("2026-03-07T00:00:00Z");
        Instant nextDayBefore = evaluator.nextRunAt(fromDayBefore);
        assertEquals(Instant.parse("2026-03-07T14:00:00Z"), nextDayBefore,
            "DST 前 NY 09:00 EST = 14:00 UTC; got " + nextDayBefore);
    }

    @Test
    @DisplayName("FR-009：DST fall-back（秋退）后 cron tick 不丢不双 — 2026-11-01 09:00 EST = 14:00 UTC")
    void newYorkDstFallBack() {
        CronEvaluator evaluator = new CronEvaluatorImpl("0 9 * * *", "America/New_York");

        // 2026-11-01 是 DST fall-back（02:00 EDT → 01:00 EST）
        // 当天 09:00 EST（已回退）= 14:00 UTC
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant next = evaluator.nextRunAt(from);

        assertEquals(Instant.parse("2026-11-01T14:00:00Z"), next,
            "2026-11-01 DST fall-back 后 NY 09:00 EST = 14:00 UTC; got " + next);
    }

    @Test
    @DisplayName("FR-009：null zone → JVM 默认 zone（业务允许的快捷写法）")
    void nullZoneUsesJvmDefault() {
        ZoneId jvmZone = ZoneId.systemDefault();
        CronEvaluator evaluator = new CronEvaluatorImpl("0 0 * * *", null);

        Instant from = Instant.now();
        Instant next = evaluator.nextRunAt(from);

        // 验证 next 在 zone 内的 00:00 时刻 — 通过 atZone 转回再校验
        ZonedDateTime nextLocal = next.atZone(jvmZone);
        assertEquals(0, nextLocal.getMinute(), "分钟 = 0; got " + nextLocal.getMinute());
        assertEquals(0, nextLocal.getHour(), "小时 = 0; got " + nextLocal.getHour());
        assertTrue(next.isAfter(from),
            "nextRunAt must be strictly after fromUtc; from=" + from + " next=" + next);
        assertTrue(ChronoUnit.SECONDS.between(from, next) <= 86400 + 60,
            "nextRunAt 必须在 24h 内（cron 0 0 * * * = 每天 0 点）; got delta="
                + ChronoUnit.SECONDS.between(from, next));
    }

    @Test
    @DisplayName("FR-009 + C-CE-2：非法 zone 字符串 → IllegalArgumentException（fail-closed）")
    void invalidZoneFailsClosed() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> new CronEvaluatorImpl("0 9 * * *", "Not/A_Real_Zone"),
            "非法 zone 应抛 IllegalArgumentException per C-CE-2");
        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().contains("invalid IANA timezone")
                || e.getMessage().contains("Not/A_Real_Zone"),
            "异常 message 应含 zone 名或 'invalid IANA timezone'; got: " + e.getMessage());
    }
}