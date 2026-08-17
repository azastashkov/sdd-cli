package sdd.cli.progress;

import org.junit.jupiter.api.Test;
import sdd.core.progress.Progress;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The TTY renderer: a single self-updating line, {@code \r} + space-padding only (design doc,
 * "Renderers") — no ANSI, no library. Every test here drives {@link LiveProgress} through a
 * {@link StringWriter} and an injected {@link InstantSource}, so none of it needs a real
 * terminal, matching {@code InteractiveReview}'s reader/writer-driven idiom.
 */
class LiveProgressTest {
    private static final Instant T0 = Instant.parse("2026-08-17T00:00:00Z");

    /** A hand-rolled mutable clock — not a mock, just a test double with a settable {@link
     *  Instant}, so elapsed time is asserted deterministically rather than by racing a real
     *  1-second scheduler tick. */
    private static final class TestClock implements InstantSource {
        private Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    private static String lastFrame(StringWriter sw) {
        String out = sw.toString();
        int lastCr = out.lastIndexOf('\r');
        return out.substring(lastCr + 1).stripTrailing();
    }

    /**
     * Fix 1 (root cause): a caller can resolve a real {@link LiveProgress} — starting its
     * once-a-second ticker thread — and then never call {@link #phase}/{@link #start} at all
     * (the un-wired-renderer bug: {@code RebuildPass} does exactly this). Before this fix, every
     * tick still painted a bare {@code \r} + 80 spaces, which on a live TTY could land mid-line
     * and wipe out unrelated output the ticker knows nothing about. {@code paintLocked}'s early
     * return on empty rendered content makes an event-less renderer genuinely inert: this waits
     * for at least one real tick (via the same background scheduler {@code stop()} shuts down,
     * not a fixed sleep racing wall-clock scheduling) and asserts nothing was written at all.
     */
    @Test
    void anEventLessRendererWritesNothingAtAllEvenAfterATickFires() throws Exception {
        StringWriter sw = new StringWriter();
        LiveProgress progress = new LiveProgress(new PrintWriter(sw), InstantSource.system());
        try {
            // The scheduler is single-threaded and runs scheduled tasks in order: a task queued
            // for ~1.2s is guaranteed to run strictly after the first scheduleWithFixedDelay tick
            // (queued for 1s) has completed.
            CountDownLatch afterFirstTick = new CountDownLatch(1);
            progress.scheduler.schedule(afterFirstTick::countDown, 1200, TimeUnit.MILLISECONDS);
            assertThat(afterFirstTick.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(sw.toString()).isEmpty();
        } finally {
            progress.stop();
        }
    }

    @Test
    void framesUseCarriageReturnAndNoNewlineBeforeStop() {
        StringWriter sw = new StringWriter();
        LiveProgress progress = new LiveProgress(new PrintWriter(sw), new TestClock(T0));
        try {
            progress.phase("indexing", 11);
            progress.start("order-service");
            progress.detail("gradle extract");
            progress.finish("order-service");

            String out = sw.toString();
            assertThat(out).contains("\r");
            assertThat(out).doesNotContain("\n");
        } finally {
            progress.stop();
        }
    }

    @Test
    void stopErasesTheLineWithNoTrailingNewline() {
        StringWriter sw = new StringWriter();
        LiveProgress progress = new LiveProgress(new PrintWriter(sw), new TestClock(T0));
        progress.start("order-service");

        progress.stop();

        String out = sw.toString();
        assertThat(out).doesNotContain("\n");
        assertThat(out).endsWith("\r" + " ".repeat(80) + "\r");
    }

    @Test
    void everyFrameIsExactlyEightyColumnsEvenForAMuchLongerLine() {
        StringWriter sw = new StringWriter();
        LiveProgress progress = new LiveProgress(new PrintWriter(sw), new TestClock(T0));
        try {
            String longItem = "a-repository-with-a-name-that-is-deliberately-far-too-long-to-fit-on-one-terminal-line";
            progress.start(longItem);

            String frame = lastFrame(sw);
            assertThat(frame).hasSize(80);
        } finally {
            progress.stop();
        }
    }

    @Test
    void elapsedRendersDeterministicallyFromTheInjectedClock() {
        StringWriter sw = new StringWriter();
        TestClock clock = new TestClock(T0);
        LiveProgress progress = new LiveProgress(new PrintWriter(sw), clock);
        try {
            progress.phase("indexing", 11);
            progress.start("order-service");
            clock.advance(Duration.ofSeconds(42));
            progress.detail("gradle extract");

            String frame = lastFrame(sw);
            assertThat(frame).contains("1/11").contains("order-service").contains("gradle extract").contains("0:42");
        } finally {
            progress.stop();
        }
    }

    @Test
    void sequentialCounterAdvancesAsEachItemFinishes() {
        StringWriter sw = new StringWriter();
        LiveProgress progress = new LiveProgress(new PrintWriter(sw), new TestClock(T0));
        try {
            progress.phase("indexing", 11);
            for (String item : new String[] {"a", "b", "c"}) {
                progress.start(item);
                progress.finish(item);
            }
            progress.start("order-service");

            assertThat(lastFrame(sw)).contains("4/11").contains("order-service");
        } finally {
            progress.stop();
        }
    }

    /** Fix 3: a repeated finish() on the same item must not double-count "done" — the parallel
     *  implement caller (a later task) drives this off state transitions where a repo can
     *  plausibly reach a terminal state by more than one path. */
    @Test
    void finishIsIdempotentPerItemRepeatingItDoesNotDoubleCountDone() {
        StringWriter sw = new StringWriter();
        LiveProgress progress = new LiveProgress(new PrintWriter(sw), new TestClock(T0));
        try {
            progress.phase("implement", 11);
            progress.start("order-service");
            progress.finish("order-service");
            progress.finish("order-service"); // repeated — must not double-count

            progress.start("billing");

            assertThat(lastFrame(sw)).contains("2/11").contains("billing");
        } finally {
            progress.stop();
        }
    }

    /** Fix 3: a finish() with no matching start() (never in flight) must not count either. */
    @Test
    void finishWithNoMatchingStartDoesNotIncrementDone() {
        StringWriter sw = new StringWriter();
        LiveProgress progress = new LiveProgress(new PrintWriter(sw), new TestClock(T0));
        try {
            progress.phase("implement", 11);
            progress.finish("never-started");

            progress.start("order-service");

            assertThat(lastFrame(sw)).contains("1/11").contains("order-service");
        } finally {
            progress.stop();
        }
    }

    /** Fix 2 / P5: construction itself must never throw, even when the injected clock does —
     *  otherwise "never throws" would be a property of the sole call site (SddCli.resolve's try
     *  block), not of this class. */
    @Test
    void aThrowingClockAtConstructionDoesNotFailConstruction() {
        InstantSource throwing = () -> {
            throw new RuntimeException("boom");
        };

        assertThatCode(() -> {
            LiveProgress progress = new LiveProgress(new PrintWriter(new StringWriter()), throwing);
            progress.start("order-service"); // still must not throw: every later read of the
            progress.stop();                 // same clock goes through its own catch-all too.
        }).doesNotThrowAnyException();
    }

    @Test
    void withNoTotalKnownTheCounterIsOmittedRatherThanShowingAFractionOverZero() {
        StringWriter sw = new StringWriter();
        LiveProgress progress = new LiveProgress(new PrintWriter(sw), new TestClock(T0));
        try {
            progress.phase("linking");
            progress.start("order-service");

            assertThat(lastFrame(sw)).doesNotContain("/0").contains("order-service");
        } finally {
            progress.stop();
        }
    }

    @Test
    void neverUsesTheRepoColonPrefixReviewPartsRelyOnBeingLoadBearing() {
        StringWriter sw = new StringWriter();
        LiveProgress progress = new LiveProgress(new PrintWriter(sw), new TestClock(T0));
        try {
            progress.phase("indexing", 11);
            progress.start("order-service");

            assertThat(lastFrame(sw)).doesNotContain("order-service: ");
        } finally {
            progress.stop();
        }
    }

    @Test
    void parallelFrameShowsDoneCountAndOrdersRunningReposOldestFirst() {
        StringWriter sw = new StringWriter();
        TestClock clock = new TestClock(T0);
        LiveProgress progress = new LiveProgress(new PrintWriter(sw), clock);
        try {
            progress.phase("implement", 11);
            for (int i = 0; i < 3; i++) {
                progress.start("finished-" + i);
                progress.finish("finished-" + i);
            }
            progress.start("order-service"); // oldest running repo
            clock.advance(Duration.ofMinutes(2));
            progress.start("billing");
            clock.advance(Duration.ofMinutes(1));
            progress.start("payments"); // third concurrent repo: collapses into (+1)

            String frame = lastFrame(sw);
            assertThat(frame).contains("3/11").contains("done").contains("(+1)");
            assertThat(frame).doesNotContain("payments");
            assertThat(frame.indexOf("order-service")).isLessThan(frame.indexOf("billing"));
        } finally {
            progress.stop();
        }
    }

    @Test
    void moreThanThreeRunningReposCollapseWithTheRightRemainderCount() {
        StringWriter sw = new StringWriter();
        LiveProgress progress = new LiveProgress(new PrintWriter(sw), new TestClock(T0));
        try {
            progress.phase("implement", 11);
            progress.start("a");
            progress.start("b");
            progress.start("c");
            progress.start("d");

            assertThat(lastFrame(sw)).contains("(+2)");
        } finally {
            progress.stop();
        }
    }

    @Test
    void oldestFirstOrderingIsStableAcrossRepeatedFramesWithNoNewEvents() {
        StringWriter sw = new StringWriter();
        TestClock clock = new TestClock(T0);
        LiveProgress progress = new LiveProgress(new PrintWriter(sw), clock);
        try {
            progress.phase("implement", 11);
            progress.start("order-service");
            clock.advance(Duration.ofMinutes(1));
            progress.start("billing");

            String first = lastFrame(sw);
            progress.detail("irrelevant in parallel mode"); // forces a repaint, no model change
            String second = lastFrame(sw);

            assertThat(first.indexOf("order-service")).isLessThan(first.indexOf("billing"));
            assertThat(second.indexOf("order-service")).isLessThan(second.indexOf("billing"));
        } finally {
            progress.stop();
        }
    }

    @Test
    void schedulerIsADaemonThreadNamedSddProgress() {
        LiveProgress progress = new LiveProgress(new PrintWriter(new StringWriter()), InstantSource.system());
        try {
            Thread t = Thread.getAllStackTraces().keySet().stream()
                    .filter(th -> "sdd-progress".equals(th.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no sdd-progress thread found"));
            assertThat(t.isDaemon()).isTrue();
        } finally {
            progress.stop();
        }
    }

    @Test
    void stopShutsDownTheSchedulerSoANonDaemonJvmStillExits() {
        LiveProgress progress = new LiveProgress(new PrintWriter(new StringWriter()), InstantSource.system());

        progress.stop();

        assertThat(progress.scheduler.isShutdown()).isTrue();
    }

    @Test
    void flushesOnEveryFrameSinceACrFrameNeverTriggersAutoflush() {
        AtomicInteger flushes = new AtomicInteger();
        Writer counting = new Writer() {
            @Override public void write(char[] cbuf, int off, int len) {
            }
            @Override public void flush() {
                flushes.incrementAndGet();
            }
            @Override public void close() {
            }
        };
        LiveProgress progress = new LiveProgress(new PrintWriter(counting), new TestClock(T0));
        try {
            progress.start("a");
            progress.detail("b");
            progress.finish("a");

            assertThat(flushes.get()).isGreaterThanOrEqualTo(3);
        } finally {
            progress.stop();
        }
    }

    /** P5: catches broadly around its own body — a writer that fails synchronously must degrade
     *  this renderer to silence, not abort the command it is reporting on. */
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
        LiveProgress progress = new LiveProgress(new PrintWriter(throwing), InstantSource.system());

        assertThatCode(() -> {
            progress.phase("indexing", 11);
            progress.start("order-service");
            progress.detail("gradle extract");
            progress.finish("order-service");
            progress.note("warn: something");
            progress.suspend(() -> {
                throw new RuntimeException("the action itself can throw too");
            });
            progress.stop();
        }).doesNotThrowAnyException();
    }

    @Test
    void nullArgumentsDoNotThrow() {
        LiveProgress progress = new LiveProgress(new PrintWriter(new StringWriter()), InstantSource.system());
        assertThatCode(() -> {
            progress.phase(null);
            progress.phase(null, -5);
            progress.start(null);
            progress.finish(null);
            progress.detail(null);
            progress.note(null);
            progress.suspend(null);   // a null Runnable NPEs on .run() — P5 must still swallow it
        }).doesNotThrowAnyException();
        progress.stop();
    }

    /**
     * {@link Progress#suspend} is the counterpart to {@link #note} for a caller that owns its own
     * writer and must print through it unconditionally — same erase/repaint choreography, but the
     * caller's own action supplies the text rather than this renderer owning it (design rationale
     * on {@code sdd.core.progress.Progress#suspend}, the fix for RebuildPass's "could not stage
     * ... at its checkpoint" silently dropping under a no-op/stopped renderer).
     */
    @Test
    void suspendErasesRunsTheActionThenRepaints() {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        LiveProgress progress = new LiveProgress(pw, new TestClock(T0));
        try {
            progress.phase("review", 3);
            progress.start("lib");
            int beforeSuspend = sw.toString().length();

            String warnLine = "warn: could not stage lib at its checkpoint: conflict";
            progress.suspend(() -> pw.println(warnLine));

            String written = sw.toString().substring(beforeSuspend);
            // Erase first: a \r-led blank frame, with nothing visible in it.
            assertThat(written).startsWith("\r");
            int warnIndex = written.indexOf(warnLine);
            assertThat(warnIndex).as("the action's own text must appear").isPositive();
            assertThat(written.substring(0, warnIndex).strip()).isEmpty();
            // The action's own newline-terminated line, then a repaint (another \r-led frame).
            String afterWarn = written.substring(warnIndex + warnLine.length());
            assertThat(afterWarn).startsWith(System.lineSeparator() + "\r");
        } finally {
            progress.stop();
        }
    }

    /** Unlike {@link #note}, {@link Progress#suspend}'s action must run even once this renderer
     *  is stopped — the caller's own finding is not this seam's to drop (design rationale on
     *  {@code Progress#suspend}). */
    @Test
    void suspendStillRunsTheActionAfterStop() {
        StringWriter sw = new StringWriter();
        AtomicInteger ran = new AtomicInteger();
        LiveProgress progress = new LiveProgress(new PrintWriter(sw), new TestClock(T0));
        progress.stop();

        progress.suspend(ran::incrementAndGet);

        assertThat(ran.get()).isEqualTo(1);
    }

    @Test
    void concurrentEventsFromMultipleThreadsDoNotCorruptStateOrThrow() throws InterruptedException {
        StringWriter sw = new StringWriter();
        LiveProgress progress = new LiveProgress(new PrintWriter(sw), InstantSource.system());
        progress.phase("implement", 20);
        int threadCount = 8;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                String item = "repo-" + i;
                pool.submit(() -> {
                    try {
                        progress.start(item);
                        progress.detail("working");
                        progress.finish(item);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
            progress.stop();
        }
    }
}
