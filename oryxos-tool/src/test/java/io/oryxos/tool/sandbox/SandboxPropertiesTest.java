package io.oryxos.tool.sandbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SandboxProperties 单测 —— T005 阶段创建（[tasks.md T005](../../../../../../../specs/007-sandbox-whitelist/tasks.md)）。
 *
 * <p>覆盖（[specs/007-sandbox-whitelist/contracts/sandbox-whitelist.md §12](../../../../../../../specs/007-sandbox-whitelist/contracts/sandbox-whitelist.md)）：
 * <ul>
 *   <li>{@link SandboxProperties.Http} / {@link SandboxProperties.File} / {@link SandboxProperties.Shell} 默认值（{@code List.of()}）</li>
 *   <li>Setter null 兜底</li>
 *   <li>YAML {@code @ConfigurationProperties} 绑定（{@code oryxos.tool.sandbox.http.allowed-domains[0..N]} 等）</li>
 * </ul>
 */
class SandboxPropertiesTest {

    @Test
    void httpDefaultsToEmptyList() {
        SandboxProperties props = new SandboxProperties();
        assertThat(props.getHttp().getAllowedDomains()).isEmpty();
    }

    @Test
    void fileDefaultsToEmptyList() {
        SandboxProperties props = new SandboxProperties();
        assertThat(props.getFile().getAllowedPaths()).isEmpty();
    }

    @Test
    void shellAllowedCommandsDefaultsToEmptyList() {
        SandboxProperties props = new SandboxProperties();
        assertThat(props.getShell().getAllowedCommands()).isEmpty();
    }

    @Test
    void shellDangerousCommandsDefaultsToEmptyList() {
        SandboxProperties props = new SandboxProperties();
        assertThat(props.getShell().getDangerousCommands()).isEmpty();
    }

    @Test
    void httpSetterNullIsCoercedToEmptyList() {
        SandboxProperties.Http http = new SandboxProperties.Http();
        http.setAllowedDomains(null);
        assertThat(http.getAllowedDomains()).isEmpty();
    }

    @Test
    void fileSetterNullIsCoercedToEmptyList() {
        SandboxProperties.File file = new SandboxProperties.File();
        file.setAllowedPaths(null);
        assertThat(file.getAllowedPaths()).isEmpty();
    }

    @Test
    void shellAllowedCommandsSetterNullIsCoercedToEmptyList() {
        SandboxProperties.Shell shell = new SandboxProperties.Shell();
        shell.setAllowedCommands(null);
        assertThat(shell.getAllowedCommands()).isEmpty();
    }

    @Test
    void shellDangerousCommandsSetterNullIsCoercedToEmptyList() {
        SandboxProperties.Shell shell = new SandboxProperties.Shell();
        shell.setDangerousCommands(null);
        assertThat(shell.getDangerousCommands()).isEmpty();
    }

    @Test
    void setterNullIsCoercedOnAggregateProperties() {
        // 顶层 setter null 兜底
        SandboxProperties props = new SandboxProperties();
        props.setHttp(null);
        props.setFile(null);
        props.setShell(null);
        assertThat(props.getHttp()).isNotNull();
        assertThat(props.getFile()).isNotNull();
        assertThat(props.getShell()).isNotNull();
        assertThat(props.getHttp().getAllowedDomains()).isEmpty();
        assertThat(props.getFile().getAllowedPaths()).isEmpty();
        assertThat(props.getShell().getAllowedCommands()).isEmpty();
    }

    @Test
    void yamlBindingForAllSubConfigs() {
        // 验证 @ConfigurationProperties 绑定：application.yaml 多类型同时配
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        Map<String, Object> backing = new HashMap<>();
        backing.put("oryxos.tool.sandbox.http.allowed-domains[0]", "api.example.com");
        backing.put("oryxos.tool.sandbox.http.allowed-domains[1]", "localhost");
        backing.put("oryxos.tool.sandbox.file.allowed-paths[0]", "/home/agent/workspace");
        backing.put("oryxos.tool.sandbox.shell.allowed-commands[0]", "git");
        backing.put("oryxos.tool.sandbox.shell.allowed-commands[1]", "ls");
        backing.put("oryxos.tool.sandbox.shell.dangerous-commands[0]", "rm");
        sources.addFirst(new MapPropertySource("test", backing));

        Binder binder = new Binder(ConfigurationPropertySources.get(env));
        SandboxProperties props = binder.bind("oryxos.tool.sandbox", SandboxProperties.class)
            .orElseThrow(() -> new AssertionError("binding failed"));

        assertThat(props.getHttp().getAllowedDomains())
            .containsExactly("api.example.com", "localhost");
        assertThat(props.getFile().getAllowedPaths())
            .containsExactly("/home/agent/workspace");
        assertThat(props.getShell().getAllowedCommands())
            .containsExactly("git", "ls");
        assertThat(props.getShell().getDangerousCommands())
            .containsExactly("rm");
    }

    @Test
    void yamlBindingWithOnlyAggregateKey() {
        // fail-closed 默认：仅配 oryxos.tool.sandbox 不带任何子项 → 子项保持 List.of()
        // （注：完全空 YAML 时 Binder.bind 返回 Optional.empty()，由 @ConfigurationProperties 默认构造器兜底
        //  —— 默认值由 httpDefaultsToEmptyList 等覆盖。本测试验证只配顶层 key 时的子项默认行为。）
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        Map<String, Object> backing = new HashMap<>();
        backing.put("oryxos.tool.sandbox.http.allowed-domains", java.util.List.of());
        sources.addFirst(new MapPropertySource("test", backing));

        Binder binder = new Binder(ConfigurationPropertySources.get(env));
        SandboxProperties props = binder.bind("oryxos.tool.sandbox", SandboxProperties.class)
            .orElseThrow(() -> new AssertionError("binding failed"));

        assertThat(props.getHttp().getAllowedDomains()).isEmpty();
        assertThat(props.getFile().getAllowedPaths()).isEmpty();
        assertThat(props.getShell().getAllowedCommands()).isEmpty();
        assertThat(props.getShell().getDangerousCommands()).isEmpty();
    }

    @Test
    void yamlBindingWithHttpOnly() {
        // 既有 005 阶段仅配 HTTP 域名 → 其他子项保持 List.of()
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        Map<String, Object> backing = new HashMap<>();
        backing.put("oryxos.tool.sandbox.http.allowed-domains[0]", "localhost");
        sources.addFirst(new MapPropertySource("test", backing));

        Binder binder = new Binder(ConfigurationPropertySources.get(env));
        SandboxProperties props = binder.bind("oryxos.tool.sandbox", SandboxProperties.class)
            .orElseThrow(() -> new AssertionError("binding failed"));

        assertThat(props.getHttp().getAllowedDomains()).containsExactly("localhost");
        assertThat(props.getFile().getAllowedPaths()).isEmpty();
        assertThat(props.getShell().getAllowedCommands()).isEmpty();
    }
}