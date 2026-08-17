package sdd.cli.progress;

import java.io.Console;
import java.lang.reflect.Method;

/**
 * Whether stdio is attached to a real terminal — the version-fragile final rung of the
 * live/plain/off ladder ({@link ProgressEnvironment}), deliberately checked last so it can only
 * narrow an already-{@code auto} answer, never widen one that {@code SDD_PROGRESS}/{@code TERM}/
 * {@code CI} already settled.
 *
 * <p><b>{@code System.console() != null} alone is correct on Java 21 and silently wrong on 22+.</b>
 * JDK-8295803 changed {@link System#console()} to return a non-null {@link Console} even when a
 * stream is redirected, adding {@code Console.isTerminal()} — a method that does not exist on 21
 * — as the real discriminator. The project pins Java 21 ({@code build.gradle.kts:3}), so {@code
 * isTerminal()} cannot be called directly without breaking that pin's compile target; it is
 * looked up and invoked reflectively instead, so this class compiles and behaves correctly on
 * both 21 and any later toolchain. A {@link ReflectiveOperationException} means the method does
 * not exist, i.e. this IS Java 21, where a non-null {@link Console} already means a real
 * terminal — so that path returns {@code true} rather than treating "cannot look it up" as
 * "cannot be a terminal".
 */
final class ConsoleSupport {
    private ConsoleSupport() {
    }

    static boolean isTerminal() {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        try {
            Method isTerminal = Console.class.getMethod("isTerminal");
            return (boolean) isTerminal.invoke(console);
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }
}
