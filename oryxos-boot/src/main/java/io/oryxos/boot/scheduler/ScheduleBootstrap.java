package io.oryxos.boot.scheduler;

import io.oryxos.core.scheduler.AgentScheduler;
import io.oryxos.core.scheduler.Schedule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 008-agent-scheduler 阶段 —— Profile YAML → {@link AgentScheduler#bootstrap} 桥。
 *
 * <p>{@code @PostConstruct}：
 * <ol>
 *   <li>扫 {@code .oryxos/profiles/*.yaml}</li>
 *   <li>SnakeYAML 解析每个 Profile → 提取 {@code schedules[]} 数组</li>
 *   <li>把每个 {@code schedule} 转成 {@link Schedule}（带 {@code profileName} 兜底）</li>
 *   <li>调用 {@link AgentScheduler#bootstrap(List)}</li>
 * </ol>
 *
 * <p>{@code @PreDestroy}：{@link AgentScheduler#shutdown()}（优雅退出）。
 *
 * <h2>Profile YAML schedules 段 schema</h2>
 * <pre>
 * schedules:
 *   - id: morning
 *     cron: "0 8 * * *"
 *     zone: "Asia/Shanghai"
 *     message: "今天天气怎么样？"
 *     enabled: true
 * </pre>
 *
 * <p>缺 {@code schedules} 段 / 空数组 → 调度器空启动（warn log，不报错）。
 */
@Component
public class ScheduleBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ScheduleBootstrap.class);

    private final AgentScheduler agentScheduler;
    private final String profilesDir;

    @Autowired
    public ScheduleBootstrap(AgentScheduler agentScheduler) {
        this(agentScheduler, defaultProfilesDir());
    }

    /** 测试钩子：可注入 {@code profilesDir}（相对 working dir 或绝对路径）。 */
    public ScheduleBootstrap(AgentScheduler agentScheduler, String profilesDir) {
        this.agentScheduler = agentScheduler;
        this.profilesDir = profilesDir;
    }

    private static String defaultProfilesDir() {
        // 默认 .oryxos/profiles；可通过 system property oryxos.profiles.dir 覆盖
        String override = System.getProperty("oryxos.profiles.dir");
        if (override != null && !override.isBlank()) {
            return override;
        }
        String env = System.getenv("ORYXOS_PROFILES_DIR");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return ".oryxos/profiles";
    }

    @PostConstruct
    public void bootstrap() {
        log.info("ScheduleBootstrap: scanning '{}' for Profile YAML schedules", profilesDir);
        List<Schedule> schedules = loadAll();
        if (schedules.isEmpty()) {
            log.info("ScheduleBootstrap: no schedules found, scheduler will idle");
        }
        agentScheduler.bootstrap(schedules);
    }

    @PreDestroy
    public void onShutdown() {
        agentScheduler.shutdown();
    }

    /** 扫描 profilesDir 下所有 *.yaml / *.yml，按 SnakeYAML 解析 → 提取 schedules。 */
    @SuppressWarnings("unchecked")
    List<Schedule> loadAll() {
        Path dir = Path.of(profilesDir);
        if (!Files.isDirectory(dir)) {
            log.warn("ScheduleBootstrap: profiles dir '{}' not found, skipping", profilesDir);
            return List.of();
        }
        List<Schedule> out = new ArrayList<>();
        Yaml yaml = new Yaml();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.y*ml")) {
            for (Path file : stream) {
                try {
                    Map<String, Object> doc = yaml.load(Files.newBufferedReader(file));
                    if (doc == null) continue;
                    String profileName = (String) doc.get("name");
                    if (profileName == null || profileName.isBlank()) {
                        log.warn("ScheduleBootstrap: '{}' missing 'name', skipping", file);
                        continue;
                    }
                    Object schedulesNode = doc.get("schedules");
                    if (!(schedulesNode instanceof List<?> list)) {
                        continue;
                    }
                    for (Object item : list) {
                        if (!(item instanceof Map<?, ?> m)) {
                            log.warn("ScheduleBootstrap: '{}' has non-map schedule entry, skipping",
                                file);
                            continue;
                        }
                        Schedule s = parseSchedule(profileName, (Map<String, Object>) m);
                        if (s != null) {
                            out.add(s);
                        }
                    }
                } catch (IOException e) {
                    log.error("ScheduleBootstrap: failed to read '{}': {}", file, e.toString());
                }
            }
        } catch (IOException e) {
            log.error("ScheduleBootstrap: failed to open profiles dir '{}': {}", profilesDir, e.toString());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Schedule parseSchedule(String profileName, Map<String, Object> m) {
        try {
            String id = (String) m.get("id");
            String cron = (String) m.get("cron");
            String zone = (String) m.get("zone");
            String message = (String) m.get("message");
            Boolean enabled = (Boolean) m.getOrDefault("enabled", Boolean.TRUE);
            if (id == null || id.isBlank()) {
                log.warn("ScheduleBootstrap: profile='{}' schedule missing id, skipping", profileName);
                return null;
            }
            if (cron == null || cron.isBlank()) {
                log.warn("ScheduleBootstrap: profile='{}' schedule id='{}' missing cron, skipping",
                    profileName, id);
                return null;
            }
            if (message == null) {
                message = "";  // 允许空消息（用户显式喂空串）
            }
            return new Schedule(profileName, id, cron, zone, message, enabled);
        } catch (RuntimeException e) {
            log.warn("ScheduleBootstrap: profile='{}' schedule parse failed: {}", profileName, e.toString());
            return null;
        }
    }
}