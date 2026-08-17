package sdd.cli.progress;

import sdd.core.progress.Progress;

import java.io.PrintWriter;

/**
 * The piped/CI renderer (design doc, "Renderers"): <b>no thread, no timer, no state.</b> {@link
 * #phase}, {@link #start} and {@link #detail} are deliberately silent — rendering any of them
 * would mean remembering something (a start instant to compute elapsed, a current item name to
 * attach a detail to) that this class does not carry, on purpose, so nobody is tempted to grow a
 * ticker for CI. The only line this renderer ever produces per item is on {@link #finish}: one
 * {@code println}, append-only. {@link #note} is passed straight through unchanged, so {@code
 * RebuildPass}'s {@code warn:} lines read identically whether progress reporting is on or off.
 *
 * <p>Never uses a {@code <repo>: } prefix ({@code RebuildPass.java:88-89}: that exact shape is
 * load-bearing for {@code ReviewReport}/{@code InteractiveReview.replaceForRepos} to parse) — the
 * finished-item line is {@code "<item>  done"} (two spaces, no colon), and {@link #note} never
 * adds a prefix of its own at all.
 *
 * <p><b>Never throws (P5).</b> Every method wraps its body in a catch-all, modelled on {@code
 * sdd.core.diagnostics.Diagnostics}: a closed pipe or full disk on the underlying writer must
 * degrade this renderer to silence, not abort the command it is reporting on.
 */
public final class PlainProgress implements Progress {
    private final PrintWriter writer;

    public PlainProgress(PrintWriter writer) {
        this.writer = writer;
    }

    @Override
    public void phase(String name, int total) {
        // No state to render a phase transition with — see class javadoc.
    }

    @Override
    public void start(String item) {
        // Silent: this renderer has no start instant to remember, and printing here (with
        // finish() also printing) would double every item's line for no information gained.
    }

    @Override
    public void finish(String item) {
        try {
            writer.println(item + "  done");
            writer.flush();
        } catch (RuntimeException e) {
            // P5: never fail the caller over a progress line.
        }
    }

    @Override
    public void detail(String text) {
        // No current-item state to attach this to — see class javadoc.
    }

    @Override
    public void note(String text) {
        try {
            writer.println(text);
            writer.flush();
        } catch (RuntimeException e) {
            // P5: never fail the caller over a progress line.
        }
    }

    @Override
    public void stop() {
        // No line to erase, no thread to stop.
    }
}
