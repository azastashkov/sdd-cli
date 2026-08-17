package sdd.cli.progress;

import java.time.Duration;

/**
 * {@code m:ss} formatting for {@code LiveProgress}'s elapsed column — {@code 0:42}, {@code
 * 4:12}, {@code 12:45}. Package-private: the design doc calls this out explicitly ("keep it
 * private; the tree has no such utility and does not need a public one"), and {@code
 * System.nanoTime} appearing exactly twice in the whole main tree confirms there is no existing
 * elapsed-formatting idiom this should generalize into a public one.
 */
final class Elapsed {
    private Elapsed() {
    }

    /** Minutes are unpadded, seconds are always two digits. A negative duration (should not
     *  happen with a monotonic {@link java.time.InstantSource}, but a renderer must never throw
     *  on it) clamps to {@code 0:00} rather than printing a minus sign. */
    static String format(Duration elapsed) {
        long totalSeconds = Math.max(0, elapsed.getSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes + ":" + (seconds < 10 ? "0" + seconds : Long.toString(seconds));
    }
}
