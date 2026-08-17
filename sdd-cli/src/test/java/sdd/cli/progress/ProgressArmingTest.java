package sdd.cli.progress;

import org.junit.jupiter.api.Test;
import sdd.core.progress.Progress;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code SddCli.main} calls to build the one {@link Progress.Factory} it ever assigns —
 * {@link ProgressEnvironment#decide} plus the one rule the ladder itself does not know about:
 * {@code --quiet} wins outright, before {@code SDD_PROGRESS} is even consulted.
 */
class ProgressArmingTest {
    @Test
    void quietProducesANoOpFactoryEvenOnALiveTerminal() {
        Progress.Factory factory = ProgressArming.factory(true, Map.of("SDD_PROGRESS", "live"),
                new PrintWriter(new StringWriter()), () -> true);

        assertThat(factory.open()).isSameAs(Progress.noOp());
    }

    @Test
    void liveModeProducesALiveProgress() {
        Progress.Factory factory = ProgressArming.factory(false, Map.of("TERM", "xterm-256color"),
                new PrintWriter(new StringWriter()), () -> true);

        Progress opened = factory.open();
        try {
            assertThat(opened).isInstanceOf(LiveProgress.class);
        } finally {
            opened.stop();
        }
    }

    @Test
    void plainModeProducesAPlainProgress() {
        Progress.Factory factory = ProgressArming.factory(false, Map.of("CI", "1"),
                new PrintWriter(new StringWriter()), () -> true);

        assertThat(factory.open()).isInstanceOf(PlainProgress.class);
    }

    @Test
    void offModeProducesTheSameNoOpSingleton() {
        Progress.Factory factory = ProgressArming.factory(false, Map.of("SDD_PROGRESS", "off"),
                new PrintWriter(new StringWriter()), () -> true);

        assertThat(factory.open()).isSameAs(Progress.noOp());
    }
}
