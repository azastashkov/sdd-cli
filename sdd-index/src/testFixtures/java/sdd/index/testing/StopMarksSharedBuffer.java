package sdd.index.testing;

import sdd.core.progress.Progress;

import java.io.StringWriter;

/**
 * A {@link Progress} double whose {@link #stop()} appends a marker into a {@link StringWriter}
 * that is ALSO the command's own {@code out}/{@code err} sink — so a test can prove ORDERING
 * ("{@code stop()} fired before the report's first line"), not merely that both happened.
 * {@link RecordingProgress}'s flat event list cannot express this on its own: {@link
 * Progress#stop()} and a command's own report printing are two different sinks in production
 * (stderr vs. stdout), and only a shared buffer puts both on one timeline a test can index into.
 *
 * <p>Shared across {@code IndexCommandProgressTest}/{@code ImplementCommandProgressTest}/
 * {@code ReviewCommandProgressTest} rather than each defining its own private copy — the
 * duplication a Task 3 review flagged. Every other {@link Progress} method is a no-op:
 * {@code stop()} ordering is the one thing this double exists to prove.
 */
public final class StopMarksSharedBuffer implements Progress {
    private final StringWriter shared;

    public StopMarksSharedBuffer(StringWriter shared) {
        this.shared = shared;
    }

    @Override public void phase(String name, int total) { }
    @Override public void start(String item) { }
    @Override public void finish(String item) { }
    @Override public void detail(String text) { }
    @Override public void note(String text) { }

    @Override
    public void stop() {
        shared.append("<<progress stopped>>\n");
    }
}
