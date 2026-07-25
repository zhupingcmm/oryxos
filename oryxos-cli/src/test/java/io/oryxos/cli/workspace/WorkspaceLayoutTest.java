package io.oryxos.cli.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceLayoutTest {

    @Test
    void initializeCreatesFullLayout(@TempDir Path tmp) throws IOException {
        // When: init a fresh workspace
        WorkspaceLayout layout = new WorkspaceLayout(
                tmp.resolve(".oryxos"),
                java.util.List.of(),
                java.util.List.of(),
                System.currentTimeMillis(),
                0, 0, 0);
        layout.initialize();

        // Then: 4 required dirs + oryxos.db exist
        assertThat(Files.isDirectory(tmp.resolve(".oryxos/agents"))).isTrue();
        assertThat(Files.isDirectory(tmp.resolve(".oryxos/memory"))).isTrue();
        assertThat(Files.isDirectory(tmp.resolve(".oryxos/sessions"))).isTrue();
        assertThat(Files.isDirectory(tmp.resolve(".oryxos/logs"))).isTrue();
        assertThat(Files.isRegularFile(tmp.resolve(".oryxos/oryxos.db"))).isTrue();
    }

    @Test
    void probeThrowsNotInitializedWhenAbsent(@TempDir Path tmp) {
        // Given: empty directory
        // Then: probe throws NotInitializedException
        assertThatThrownBy(() -> WorkspaceLayout.probe(tmp))
                .isInstanceOf(NotInitializedException.class)
                .hasMessageContaining("not initialized");
    }

    @Test
    void probeReadsRealpathAndCountsProfiles(@TempDir Path tmp) throws IOException {
        // Given: initialised workspace + 2 profiles
        Path oryxos = tmp.resolve(".oryxos");
        Files.createDirectories(oryxos.resolve("agents/weather-bot"));
        Files.createDirectories(oryxos.resolve("agents/tech-digest"));
        for (String dir : WorkspaceLayout.REQUIRED_DIR_NAMES) {
            Files.createDirectories(oryxos.resolve(dir));
        }
        Files.createFile(oryxos.resolve("oryxos.db"));

        // When: probe
        WorkspaceLayout layout = WorkspaceLayout.probe(tmp);

        // Then
        assertThat(layout.root().toRealPath(LinkOption.NOFOLLOW_LINKS))
                .isEqualTo(oryxos.toRealPath(LinkOption.NOFOLLOW_LINKS));
        assertThat(layout.profileCount()).isEqualTo(2);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void initializeRefusesSymlinkedRoot(@TempDir Path tmp) throws IOException {
        // Given: a symlink pointing at .oryxos
        Path link = tmp.resolve("link");
        Files.createSymbolicLink(link, tmp.resolve(".oryxos"));

        // Then: initialising via the symlink is rejected
        WorkspaceLayout layout = new WorkspaceLayout(
                link,
                java.util.List.of(),
                java.util.List.of(),
                System.currentTimeMillis(),
                0, 0, 0);
        assertThatThrownBy(layout::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("symlink");
    }

    @Test
    void renderHumanReadableContainsSections(@TempDir Path tmp) throws IOException {
        Path oryxos = tmp.resolve(".oryxos");
        for (String dir : WorkspaceLayout.REQUIRED_DIR_NAMES) {
            Files.createDirectories(oryxos.resolve(dir));
        }
        WorkspaceLayout layout = WorkspaceLayout.probe(tmp);
        String text = layout.renderHumanReadable();
        assertThat(text).contains("Workspace:");
        assertThat(text).contains("Required dirs");
        assertThat(text).contains("Required files");
    }
}