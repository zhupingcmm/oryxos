package io.oryxos.cli;

import io.oryxos.cli.exitcode.Sysexits;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-009 / SC-007 — exit codes follow BSD sysexits.
 *
 * <p>Stable contract for shell scripts and CI. Any change to these values
 * is a breaking change for downstream automation.
 */
class SysexitsTest {

    @Test
    void successIsZero() {
        assertThat(Sysexits.OK).isEqualTo(0);
    }

    @Test
    void genericFailureIsOne() {
        assertThat(Sysexits.GENERIC).isEqualTo(1);
    }

    @Test
    void warningIsTwo() {
        assertThat(Sysexits.WARNING).isEqualTo(2);
    }

    @Test
    void exUsageIsSixtyFour() {
        assertThat(Sysexits.EX_USAGE).isEqualTo(64);
    }

    @Test
    void exUnavailableIsSixtyNine() {
        assertThat(Sysexits.EX_UNAVAILABLE).isEqualTo(69);
    }

    @Test
    void exConfigIsSeventyEight() {
        assertThat(Sysexits.EX_CONFIG).isEqualTo(78);
    }

    @Test
    void allValuesAreDistinct() {
        int[] values = {Sysexits.OK, Sysexits.GENERIC, Sysexits.WARNING,
                Sysexits.EX_USAGE, Sysexits.EX_UNAVAILABLE, Sysexits.EX_CONFIG};
        for (int i = 0; i < values.length; i++) {
            for (int j = i + 1; j < values.length; j++) {
                assertThat(values[i])
                        .as("sysexits values must be distinct; got duplicate %d at %d/%d",
                                values[i], i, j)
                        .isNotEqualTo(values[j]);
            }
        }
    }
}