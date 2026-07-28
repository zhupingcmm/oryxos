package io.oryxos.cli.schedule;

import io.oryxos.cli.command.CommandSpringBase;
import io.oryxos.cli.exitcode.Sysexits;
import io.oryxos.core.scheduler.AgentScheduler;
import io.oryxos.core.scheduler.AgentScheduler.ScheduleView;
import picocli.CommandLine;

import java.util.List;

/**
 * {@code oryxos schedule list} —— 列出 {@code scheduled_tasks} 表全部 schedule
 * + 内存中已注册的 schedule 视图。
 *
 * <p>必须 Spring（FR-012） —— 走 {@link AgentScheduler} bean，从
 * {@link AgentScheduler#listSchedules()} 拿 {@link ScheduleView}。
 *
 * <p>列：{@code TASK_ID} / {@code PROFILE} / {@code CRON} / {@code ZONE} /
 * {@code ENABLED} / {@code NEXT_RUN_AT_UTC}（per contracts §7.1）。
 *
 * <p>5 验收场景（per contracts §7.2 + T033）：
 * <ol>
 *   <li>无 schedule → {@code "(no schedules)"} 占位</li>
 *   <li>1 条 schedule → 1 行数据 + 表头</li>
 *   <li>N 条 schedule → N 行数据 + 表头，按 taskId 升序（listSchedules 已 sort）</li>
 *   <li>调度器未启动（{@code isRunning()==false}） → 报 "scheduler not running"</li>
 *   <li>Spring 启动失败 → 透传 {@link Sysexits#EX_UNAVAILABLE}</li>
 * </ol>
 */
@CommandLine.Command(
    name = "schedule",
    mixinStandardHelpOptions = true,
    description = "Inspect registered scheduled tasks (Spring-boot, reads AgentScheduler bean).",
    subcommands = { ScheduleListCommand.ScheduleListSubCommand.class }
)
public class ScheduleListCommand extends CommandSpringBase {

    @Override
    protected Integer runBody() {
        // No subcommand provided → show help (Picocli 默认行为，参考 SessionListCommand)
        spec.commandLine().getOut().println("Use 'oryxos schedule list' to list scheduled tasks.");
        return Sysexits.OK;
    }

    @CommandLine.Command(
        name = "list",
        mixinStandardHelpOptions = true,
        description = "List registered schedules, sorted by task_id."
    )
    public static class ScheduleListSubCommand extends CommandSpringBase {

        static final String PRIMARY_SOURCE = "io.oryxos.boot.OryxOsApplication";

        @Override
        protected Integer runBody() {
            try (var ctx = acquireContext(PRIMARY_SOURCE)) {
                AgentScheduler scheduler = bean(ctx, AgentScheduler.class);
                if (!scheduler.isRunning()) {
                    spec.commandLine().getErr().println(
                        "Error: scheduler not running (call bootstrap first)");
                    spec.commandLine().getErr().flush();
                    return Sysexits.GENERIC;
                }
                List<ScheduleView> schedules = scheduler.listSchedules();
                ScheduleListFormatter.format(spec.commandLine().getOut(), schedules);
                return Sysexits.OK;
            }
        }
    }
}