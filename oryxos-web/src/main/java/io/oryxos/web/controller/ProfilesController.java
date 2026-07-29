package io.oryxos.web.controller;

import io.oryxos.core.Profile;
import io.oryxos.core.ProfileRegistry;
import io.oryxos.web.dto.ProfileDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * T031 + data-model.md §实体 5 + contracts/web-api.md §端点 6 — GET /api/v1/profiles.
 *
 * <p>列已注册 Profile 列表（只读）。不暴露完整 Profile YAML (per spec FR-011 +
 * data-model.md §"为什么不暴露完整 Profile YAML").
 *
 * <p>字段映射:
 * <ul>
 *   <li>{@code description}  ← {@code Profile.extra["description"]}</li>
 *   <li>{@code agentName}    ← {@code Profile.extra["identity.agent_name"]}</li>
 *   <li>{@code scheduleCount} ← {@code Profile.extra["schedules[].length"]}</li>
 *   <li>{@code notifyChannelCount} ← {@code Profile.notifyChannels[].length}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class ProfilesController {

    private final ProfileRegistry profileRegistry;

    public ProfilesController(ProfileRegistry profileRegistry) {
        this.profileRegistry = profileRegistry;
    }

    @GetMapping("/profiles")
    public List<ProfileDto> list() {
        List<ProfileDto> out = new ArrayList<>();
        for (String name : profileRegistry.names()) {
            Profile p = profileRegistry.find(name).orElse(null);
            if (p == null) continue;
            Map<String, Object> extra = p.extra();
            out.add(new ProfileDto(
                p.name(),
                asString(extra.get("description")),
                asString(deepGet(extra, "identity", "agent_name")),
                p.provider().name(),
                p.provider().model(),
                p.tools() != null ? p.tools().size() : 0,
                countList(extra.get("schedules")),
                p.notifyChannels() != null ? p.notifyChannels().size() : 0,
                p.bootstrap() != null ? List.copyOf(p.bootstrap()) : List.of()
            ));
        }
        return out;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static int countList(Object o) {
        if (o instanceof List<?> l) return l.size();
        if (o instanceof Map<?, ?> m) return m.size();
        return 0;
    }

    /** 简单嵌套 map 路径 getter;{@code keys[0..n]} 走到最后返回 toString;中间 null → null. */
    @SuppressWarnings("unchecked")
    private static Object deepGet(Object root, String... keys) {
        Object cur = root;
        for (String k : keys) {
            if (cur instanceof Map<?, ?> m) {
                cur = m.get(k);
            } else {
                return null;
            }
            if (cur == null) return null;
        }
        return cur;
    }
}
