package io.oryxos.core.scheduler;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * 008-agent-scheduler 阶段 —— {@link CronEvaluator} 的 cron-utils 实现。
 *
 * <p>使用 cron-utils v9.2.1（research.md R-001）的 UNIX 增强型 cron 语法：
 * <ul>
 *   <li>5 段：{@code minute hour day-of-month month day-of-week}</li>
 *   <li>支持 {@code ,} / {@code -} / {@code /} / {@code *} / {@code ?} / {@code L} / {@code W} / {@code #}</li>
 *   <li>时区由外部传入（{@link ZoneId}）；{@code null} → JVM 默认</li>
 * </ul>
 *
 * <h2>时区与 DST</h2>
 * <p>{@link #nextRunAt(Instant)} 用传入的 {@code fromUtc} + 本地时区计算下次触发：
 * <ol>
 *   <li>{@code fromUtc} → {@code ZonedDateTime} (zone)</li>
 *   <li>调用 {@link ExecutionTime#nextExecution(ZonedDateTime)} → {@code Optional<ZonedDateTime>}</li>
 *   <li>结果 → {@code Instant}（UTC）</li>
 * </ol>
 * DST 切换由 JDK {@code ZonedDateTime} + IANA tzdata 自动处理（R-003）。
 *
 * <h2>线程安全</h2>
 * <p>cron-utils 的 {@code Cron} / {@code ExecutionTime} 不可变 + 无状态 → 本实现无状态。
 */
public class CronEvaluatorImpl implements CronEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CronEvaluatorImpl.class);

    private static final CronDefinition CRON_DEFINITION =
        CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);

    private final Cron cron;
    private final ExecutionTime executionTime;
    private final ZoneId zone;

    /**
     * 构造：立即校验 cron + zone；失败抛 {@link IllegalArgumentException}（C-CE-1 / C-CE-2）。
     *
     * @param cronExpr 5 段 cron 表达式（已 trim）
     * @param zone     IANA 时区名（{@code null} → JVM 默认）
     * @throws IllegalArgumentException cron 非法或 zone 非法
     */
    public CronEvaluatorImpl(String cronExpr, String zone) {
        if (cronExpr == null || cronExpr.isBlank()) {
            throw new IllegalArgumentException("cron must not be blank");
        }
        Cron parsed;
        try {
            parsed = new CronParser(CRON_DEFINITION).parse(cronExpr.trim());
            parsed.validate();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "invalid cron expression '" + cronExpr + "': " + e.getMessage(), e);
        }
        this.cron = parsed;
        this.executionTime = ExecutionTime.forCron(cron);

        ZoneId resolved;
        if (zone == null || zone.isBlank()) {
            resolved = ZoneId.systemDefault();
        } else {
            try {
                resolved = ZoneId.of(zone);
            } catch (java.time.DateTimeException e) {
                throw new IllegalArgumentException(
                    "invalid IANA timezone '" + zone + "': " + e.getMessage(), e);
            }
        }
        this.zone = resolved;
        log.debug("CronEvaluatorImpl: cron='{}' zone='{}' resolved_zone='{}'",
            cronExpr, zone, resolved);
    }

    @Override
    public Instant nextRunAt(Instant fromUtc) {
        if (fromUtc == null) {
            throw new IllegalArgumentException("fromUtc must not be null");
        }
        ZonedDateTime fromLocal = fromUtc.atZone(zone);
        Optional<ZonedDateTime> nextOpt = executionTime.nextExecution(fromLocal);
        if (nextOpt.isEmpty()) {
            throw new IllegalStateException(
                "cron has no future execution: from=" + fromUtc + " zone=" + zone);
        }
        Instant result = nextOpt.get().toInstant();
        if (!result.isAfter(fromUtc)) {
            throw new IllegalStateException(
                "computed nextRunAt is not after fromUtc: from=" + fromUtc
                    + " next=" + result + " cron=" + cron.asString());
        }
        return result;
    }

    @Override
    public void validate() {
        cron.validate();
    }

    /** 调试用：当前 cron 字符串（用于 listSchedules 输出）。 */
    public String cronExpression() {
        return cron.asString();
    }

    public ZoneId zone() {
        return zone;
    }
}