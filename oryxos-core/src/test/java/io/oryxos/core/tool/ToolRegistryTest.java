package io.oryxos.core.tool;

import io.oryxos.core.OryxTool;
import io.oryxos.test.AEcho;
import io.oryxos.test.Alpha;
import io.oryxos.test.BEcho;
import io.oryxos.test.Beta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * US-4 ToolRegistry fail-fast 验证 —— spec FR-015（[research.md R-08](../../../../../../../specs/005-tool-system/research.md)）。
 *
 * <p>两个 {@link ToolRegistration} 声明同名 Tool 时 {@link ToolRegistry#of(Map)} 必须抛
 * {@link IllegalStateException}，且错误消息含两个冲突类的全限定名。
 *
 * <p>Fixture 必须是独立顶层类（{@code io.oryxos.test.AEcho}/{@code BEcho} 等），不能用内部类，
 * 否则 {@code getClass().getName()} 拿不到稳定的 FQCN。
 */
class ToolRegistryTest {

    @Test
    @DisplayName("of() 在两个 Tool 重名时抛 IllegalStateException，错误消息含两个类名")
    void conflict_fails_at_construction() {
        ToolDefinition defA = new ToolDefinition("echo", "first", "java_bean");
        ToolDefinition defB = new ToolDefinition("echo", "second", "java_bean");
        OryxTool toolA = new AEcho();
        OryxTool toolB = new BEcho();

        ToolRegistration regA = new ToolRegistration(defA, toolA, "aBean");
        ToolRegistration regB = new ToolRegistration(defB, toolB, "bBean");

        assertThatExceptionOfType(IllegalStateException.class)
            .isThrownBy(() -> ToolRegistry.of(Map.of(
                "echo", regA,
                "echo-2", regB
            )))
            .withMessageContaining("'echo'")
            .withMessageContaining("io.oryxos.test.AEcho")
            .withMessageContaining("io.oryxos.test.BEcho");
    }

    @Test
    @DisplayName("of() 在没有冲突时正常构造，并保留插入顺序")
    void no_conflict_succeeds() {
        ToolDefinition defA = new ToolDefinition("alpha", "a", "java_bean");
        ToolDefinition defB = new ToolDefinition("beta", "b", "java_bean");
        OryxTool toolA = new Alpha();
        OryxTool toolB = new Beta();

        java.util.LinkedHashMap<String, ToolRegistration> orderedMap = new java.util.LinkedHashMap<>();
        orderedMap.put("alpha", new ToolRegistration(defA, toolA, "alphaBean"));
        orderedMap.put("beta",  new ToolRegistration(defB, toolB, "betaBean"));
        ToolRegistry registry = ToolRegistry.of(orderedMap);

        assertThat(registry.names()).containsExactly("alpha", "beta");
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.find("alpha")).isPresent();
        assertThat(registry.find("beta")).isPresent();
    }
}

