package sdd.cli.progress;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code m:ss} formatter behind {@code LiveProgress}'s elapsed column — private to this
 * package by design (the design doc: "keep it private; the tree has no such utility and does not
 * need a public one").
 */
class ElapsedTest {
    @Test
    void formatsSecondsAlonePadded() {
        assertThat(Elapsed.format(Duration.ofSeconds(42))).isEqualTo("0:42");
    }

    @Test
    void formatsMinutesAndSecondsUnpaddedMinutes() {
        assertThat(Elapsed.format(Duration.ofSeconds(4 * 60 + 12))).isEqualTo("4:12");
        assertThat(Elapsed.format(Duration.ofSeconds(12 * 60 + 45))).isEqualTo("12:45");
    }

    @Test
    void zeroIsZeroColonZeroZero() {
        assertThat(Elapsed.format(Duration.ZERO)).isEqualTo("0:00");
    }

    @Test
    void aNegativeDurationClampsToZeroRatherThanPrintingAMinusSign() {
        assertThat(Elapsed.format(Duration.ofSeconds(-5))).isEqualTo("0:00");
    }

    @Test
    void secondsUnderTenAreZeroPadded() {
        assertThat(Elapsed.format(Duration.ofSeconds(65))).isEqualTo("1:05");
    }
}
