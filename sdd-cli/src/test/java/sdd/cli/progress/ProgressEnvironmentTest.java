package sdd.cli.progress;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live/plain/off ladder (design doc, "Deciding live vs plain vs off"), in the stated order:
 * {@code SDD_PROGRESS} (an explicit escape hatch, consistent with {@code SDD_NODE} — {@code
 * NodeLocator.java:36}), then {@code TERM}, then {@code CI}, then console detection — each rung
 * checked only once the ones before it decline to answer, so the version-fragile console check
 * ({@link ConsoleSupport}) can only narrow an {@code auto} decision, never override an explicit
 * one.
 */
class ProgressEnvironmentTest {
    @Test
    void sddProgressOffWinsEvenOnARealTerminal() {
        Map<String, String> env = Map.of("SDD_PROGRESS", "off", "TERM", "xterm-256color");
        assertThat(ProgressEnvironment.decide(env, () -> true)).isEqualTo(ProgressEnvironment.Mode.OFF);
    }

    @Test
    void sddProgressPlainWinsEvenOnARealTerminal() {
        Map<String, String> env = Map.of("SDD_PROGRESS", "plain", "TERM", "xterm-256color");
        assertThat(ProgressEnvironment.decide(env, () -> true)).isEqualTo(ProgressEnvironment.Mode.PLAIN);
    }

    @Test
    void sddProgressLiveWinsEvenWithoutATerminal() {
        Map<String, String> env = Map.of("SDD_PROGRESS", "live", "TERM", "dumb", "CI", "1");
        assertThat(ProgressEnvironment.decide(env, () -> false)).isEqualTo(ProgressEnvironment.Mode.LIVE);
    }

    @Test
    void sddProgressAutoFallsThroughToTheRestOfTheLadder() {
        Map<String, String> env = Map.of("SDD_PROGRESS", "auto", "TERM", "dumb");
        assertThat(ProgressEnvironment.decide(env, () -> true)).isEqualTo(ProgressEnvironment.Mode.PLAIN);
    }

    @Test
    void unsetTermIsPlain() {
        Map<String, String> env = Map.of();
        assertThat(ProgressEnvironment.decide(env, () -> true)).isEqualTo(ProgressEnvironment.Mode.PLAIN);
    }

    @Test
    void dumbTermIsPlain() {
        Map<String, String> env = Map.of("TERM", "dumb");
        assertThat(ProgressEnvironment.decide(env, () -> true)).isEqualTo(ProgressEnvironment.Mode.PLAIN);
    }

    @Test
    void ciSetIsPlainEvenOnARealTerminal() {
        Map<String, String> env = Map.of("TERM", "xterm-256color", "CI", "true");
        assertThat(ProgressEnvironment.decide(env, () -> true)).isEqualTo(ProgressEnvironment.Mode.PLAIN);
    }

    @Test
    void fallsThroughToConsoleDetectionWhenNothingElseDecides() {
        Map<String, String> env = Map.of("TERM", "xterm-256color");
        assertThat(ProgressEnvironment.decide(env, () -> true)).isEqualTo(ProgressEnvironment.Mode.LIVE);
        assertThat(ProgressEnvironment.decide(env, () -> false)).isEqualTo(ProgressEnvironment.Mode.PLAIN);
    }

    @Test
    void anUnrecognizedSddProgressValueFallsThroughAsIfAuto() {
        Map<String, String> env = Map.of("SDD_PROGRESS", "banana", "TERM", "dumb");
        assertThat(ProgressEnvironment.decide(env, () -> true)).isEqualTo(ProgressEnvironment.Mode.PLAIN);
    }

    @Test
    void sddProgressValueIsCaseInsensitive() {
        Map<String, String> env = Map.of("SDD_PROGRESS", "OFF");
        assertThat(ProgressEnvironment.decide(env, () -> true)).isEqualTo(ProgressEnvironment.Mode.OFF);
    }
}
