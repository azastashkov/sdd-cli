package sdd.index.testing;

import sdd.core.progress.Progress;

import java.util.ArrayList;
import java.util.List;

/**
 * Records every {@link Progress} event as a single string, in call order, so a test can assert
 * the exact sequence {@code IndexService}/{@code RepoCardGenerator} emit — including that a
 * cache hit produces no matching {@link #start}/{@link #finish} pair at all, which is the one
 * property a mock-style "was this called" assertion cannot express as directly as a flat,
 * ordered event log can.
 *
 * <p>Not a mock: this repo uses JUnit 5 + AssertJ only (no Mockito), and a mock would still need
 * some way to assert relative order across different methods, which AssertJ's
 * {@code containsExactly}/{@code containsSubsequence} already do for free against a
 * {@code List<String>}.
 */
public final class RecordingProgress implements Progress {
    private final List<String> events = new ArrayList<>();

    public List<String> events() {
        return List.copyOf(events);
    }

    @Override
    public void phase(String name, int total) {
        events.add("phase:" + name + ":" + total);
    }

    @Override
    public void start(String item) {
        events.add("start:" + item);
    }

    @Override
    public void finish(String item) {
        events.add("finish:" + item);
    }

    @Override
    public void detail(String text) {
        events.add("detail:" + text);
    }

    @Override
    public void note(String text) {
        events.add("note:" + text);
    }

    /** Recorded distinctly from {@code note} so a test can assert a caller genuinely routed
     *  through this method (not merely that its own writer received the expected text, which
     *  could happen even if {@code action} were invoked directly, bypassing {@link Progress}
     *  entirely and defeating the point of the seam). {@code action} is run un-guarded,
     *  deliberately NOT wrapped in a catch — a test double has no P5 obligation of its own, and
     *  swallowing the action's exception would hide a real assertion failure inside it. */
    @Override
    public void suspend(Runnable action) {
        events.add("suspend");
        action.run();
    }

    @Override
    public void stop() {
        events.add("stop");
    }
}
