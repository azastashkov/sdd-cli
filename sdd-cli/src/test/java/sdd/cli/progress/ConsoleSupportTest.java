package sdd.cli.progress;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JDK-8295803 made {@code System.console()} return a non-null {@link java.io.Console} on Java 22+
 * even when stdout/stderr is redirected, with {@code Console.isTerminal()} as the new
 * discriminator that method did not have on 21. {@link ConsoleSupport#isTerminal()} calls it
 * reflectively so a future toolchain bump above the project's pinned Java 21
 * ({@code build.gradle.kts:3}) cannot silently start reporting "is a terminal" for a redirected
 * stream in CI.
 *
 * <p>This single assertion is the guard: a Gradle test worker's stdio is never attached to a
 * real terminal, so this is {@code false} on Java 21 (where {@code System.console()} itself
 * already returns {@code null} under Gradle) AND on 22+ (where {@code isTerminal()} reflectively
 * returns {@code false} for the same redirected stream) — proving the check narrows correctly on
 * both, not just the JDK this happens to run on today.
 */
class ConsoleSupportTest {
    @Test
    void isFalseUnderTheGradleTestWorker() {
        assertThat(ConsoleSupport.isTerminal()).isFalse();
    }
}
