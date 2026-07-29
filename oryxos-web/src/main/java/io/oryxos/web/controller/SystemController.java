package io.oryxos.web.controller;

import io.oryxos.core.ProfileRegistry;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.web.dto.HealthDto;
import io.oryxos.web.dto.InfoDto;
import io.oryxos.web.util.StartupInfoHolder;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * T034-T036 + data-model.md §实体 8/9 + contracts/web-api.md §端点 9-10.
 *
 * <p>{@code GET /api/v1/health} 调 Spring Boot Actuator {@link HealthEndpoint};
 * DOWN 状态 → HTTP 503.
 * <p>{@code GET /api/v1/info} 取 {@code name/version/javaVersion/osName/agents/tools/uptimeMs}.
 */
@RestController
@RequestMapping("/api/v1")
public class SystemController {

    private static final String APP_NAME = "oryxos";
    private static final String APP_VERSION = readVersion();

    private final HealthEndpoint healthEndpoint;
    private final ProfileRegistry profileRegistry;
    private final ToolRegistry toolRegistry;
    private final StartupInfoHolder startupInfo;

    public SystemController(
        HealthEndpoint healthEndpoint,
        ProfileRegistry profileRegistry,
        ToolRegistry toolRegistry,
        StartupInfoHolder startupInfo
    ) {
        this.healthEndpoint = healthEndpoint;
        this.profileRegistry = profileRegistry;
        this.toolRegistry = toolRegistry;
        this.startupInfo = startupInfo;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthDto> health() {
        HealthComponent hc = healthEndpoint.health();
        Status status = hc != null ? hc.getStatus() : Status.UP;
        Map<String, Object> components = new HashMap<>();
        if (hc != null) {
            // 简化: 把 StatusAggregator 后的 status 暴露为 components map
            components.put("status", status.getCode());
            // 完整 components 树渲染需要 healthForPath 遍历 —— 留给扩展阶段
        }
        HealthDto body = new HealthDto(
            status.getCode(),
            startupInfo.uptimeMs(),
            APP_VERSION,
            components
        );
        HttpStatus http = Status.UP.equals(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(http).body(body);
    }

    @GetMapping("/info")
    public InfoDto info() {
        return new InfoDto(
            APP_NAME,
            APP_VERSION,
            System.getProperty("java.version", "unknown"),
            System.getProperty("os.name", "unknown"),
            profileRegistry.names().size(),
            toolRegistry.size(),
            startupInfo.uptimeMs()
        );
    }

    /**
     * 从 classpath:application.properties 读 {@code oryxos.version} (pom.xml 的 ${project.version}).
     * 失败回退 {@code "0.1.0-SNAPSHOT"}.
     */
    private static String readVersion() {
        try {
            var props = new java.util.Properties();
            try (var is = SystemController.class.getResourceAsStream(
                "/application.properties")) {
                if (is != null) props.load(is);
            }
            // 也读 application-web.yaml 不便 (YAML 需 snakeyaml) — 走 properties 简单
            String v = props.getProperty("oryxos.version");
            return v != null ? v : "0.1.0-SNAPSHOT";
        } catch (Exception e) {
            return "0.1.0-SNAPSHOT";
        }
    }
}
