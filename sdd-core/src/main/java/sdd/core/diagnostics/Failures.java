package sdd.core.diagnostics;

/**
 * Gate review I3: a warning message built from {@code e.getMessage()} alone degrades badly for the
 * common JGit/JDK exception families this codebase's best-effort catch sites see —
 * {@code getMessage()} is {@code null} on plenty of them, so the naive concatenation prints the
 * literal string {@code "null"} to a human's terminal ({@code "  warn: bitbucket: trading-api:
 * null"}) instead of anything a person can act on. {@link #message} is the one place that
 * degradation is fixed, so every warn-line call site does it the same way rather than each growing
 * its own {@code == null ? ... : ...} ternary.
 */
public final class Failures {
    private Failures() {
    }

    /** {@code t.getMessage()}, or {@code t.getClass().getSimpleName()} when that is {@code null} —
     *  a bare class name ({@code "TransportException"}, {@code "NullPointerException"}) is still
     *  more useful on a terminal than the word "null", and it is exactly what a human would ask for
     *  next if all they saw was "null" anyway. */
    public static String message(Throwable t) {
        String message = t.getMessage();
        return message != null ? message : t.getClass().getSimpleName();
    }
}
