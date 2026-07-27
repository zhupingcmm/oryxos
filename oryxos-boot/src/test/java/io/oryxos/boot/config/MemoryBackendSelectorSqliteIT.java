package io.oryxos.boot.config;

import io.oryxos.memory.backend.LongTermMemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * backend="sqlite" 选定契约。
 */
@SpringBootTest(
    classes = {
        MemoryBackendSelectorTest.SelectorTestConfig.class,
        MemoryBackendSelector.class
    },
    properties = "oryxos.memory.backend=sqlite"
)
@DisplayName("backend=\"sqlite\" → sqliteMock 选定")
class MemoryBackendSelectorSqliteIT {

    @Autowired MemoryBackendSelector selector;
    @Autowired @Qualifier("sqliteMemoryStore") LongTermMemoryStore sqliteMock;

    @Test
    void sqlite_backend_selected() {
        assertThat(selector.selectedBackendName()).isEqualTo("sqlite");
        assertThat(selector.select()).isSameAs(sqliteMock);
    }
}