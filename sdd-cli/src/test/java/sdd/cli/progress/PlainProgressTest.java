package sdd.cli.progress;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The piped/CI renderer: no thread, no timer, no state (design doc, "Renderers") — {@code
 * start}/{@code detail}/{@code phase} are silent because rendering them would require
 * remembering something (a start instant, a current item) that this renderer deliberately does
 * not carry. One line per completed item, plus {@code note} passed straight through so {@code
 * RebuildPass}'s {@code warn:} lines are unaffected by which renderer is active.
 */
class PlainProgressTest {
    @Test
    void printsOneLinePerFinishedItem() {
        StringWriter sw = new StringWriter();
        PlainProgress progress = new PlainProgress(new PrintWriter(sw));

        progress.start("order-service");
        progress.finish("order-service");
        progress.start("billing");
        progress.finish("billing");

        String out = sw.toString();
        assertThat(out.lines()).containsExactly("order-service  done", "billing  done");
    }

    @Test
    void startDetailAndPhaseProduceNoOutput() {
        StringWriter sw = new StringWriter();
        PlainProgress progress = new PlainProgress(new PrintWriter(sw));

        progress.phase("indexing", 11);
        progress.start("order-service");
        progress.detail("gradle extract");

        assertThat(sw.toString()).isEmpty();
    }

    @Test
    void noteIsPassedThroughVerbatim() {
        StringWriter sw = new StringWriter();
        PlainProgress progress = new PlainProgress(new PrintWriter(sw));

        progress.note("warn: could not stage order-service: conflict");

        assertThat(sw.toString()).isEqualTo("warn: could not stage order-service: conflict" + System.lineSeparator());
    }

    @Test
    void neverUsesTheRepoColonPrefixReviewPartsRelyOnBeingLoadBearing() {
        StringWriter sw = new StringWriter();
        PlainProgress progress = new PlainProgress(new PrintWriter(sw));

        progress.start("order-service");
        progress.finish("order-service");

        assertThat(sw.toString()).doesNotContain("order-service: ");
    }

    @Test
    void stopIsANoOpItHasNoLineToErase() {
        StringWriter sw = new StringWriter();
        PlainProgress progress = new PlainProgress(new PrintWriter(sw));

        progress.start("order-service");
        progress.stop();

        assertThat(sw.toString()).isEmpty();
    }

    /** P5: catches broadly around its own body rather than trusting the writer never to throw —
     *  a writer whose underlying stream fails synchronously (a closed pipe, a full disk) must not
     *  turn a progress line into a fatal error for the run it is reporting on. */
    @Test
    void aThrowingWriterCannotFailTheCaller() {
        Writer throwing = new Writer() {
            @Override public void write(char[] cbuf, int off, int len) {
                throw new RuntimeException("boom");
            }
            @Override public void flush() {
                throw new RuntimeException("boom");
            }
            @Override public void close() {
            }
        };
        PlainProgress progress = new PlainProgress(new PrintWriter(throwing));

        assertThatCode(() -> {
            progress.phase("indexing", 11);
            progress.start("order-service");
            progress.detail("gradle extract");
            progress.finish("order-service");
            progress.note("warn: something");
            progress.stop();
        }).doesNotThrowAnyException();
    }
}
