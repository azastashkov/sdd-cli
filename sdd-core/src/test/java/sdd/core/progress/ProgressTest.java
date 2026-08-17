package sdd.core.progress;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link Progress#noOp()} is what every command gets when nothing armed it (see {@code
 * SddCli}'s {@code progress} field, default unset) — it must be a genuine no-op, not merely
 * "usually harmless", since {@code Orchestrator.transitionLocked} calls into it from inside a
 * lock (design doc, "Arming" section) and any failure there would abort the run.
 */
class ProgressTest {
    @Test
    void noOpIsASingleton() {
        assertThat(Progress.noOp()).isSameAs(Progress.noOp());
    }

    @Test
    void noOpToleratesEveryMethodIncludingNullArguments() {
        Progress p = Progress.noOp();
        assertThatCode(() -> {
            p.phase(null);
            p.phase(null, -1);
            p.start(null);
            p.finish(null);
            p.detail(null);
            p.note(null);
            p.stop();
        }).doesNotThrowAnyException();
    }

    /**
     * {@link Progress#phase(String)} is a default method delegating to {@link
     * Progress#phase(String, int)} with {@code total = 0} — the plan's event list names only
     * {@code phase(name)}, but every implementation (live/plain) needs both a "just a label"
     * phase and one that carries a known item count for the "N/total" line, and duplicating
     * that delegation in every implementer would be exactly the undisclosed-duplication shape
     * prior reviews here have flagged. Proven against a recording test double, not {@code
     * NoOpProgress} (whose bodies are empty and would not distinguish "delegated" from
     * "independently implemented the same way").
     */
    @Test
    void singleArgPhaseDelegatesToTheTwoArgOverloadWithZeroTotal() {
        List<Object> calls = new ArrayList<>();
        Progress recording = new Progress() {
            @Override public void phase(String name, int total) {
                calls.add(name + ":" + total);
            }
            @Override public void start(String item) {}
            @Override public void finish(String item) {}
            @Override public void detail(String text) {}
            @Override public void note(String text) {}
            @Override public void stop() {}
        };

        recording.phase("indexing");

        assertThat(calls).containsExactly("indexing:0");
    }
}
