package sdd.cli.progress;

import org.junit.jupiter.api.Test;

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
        }).doesNotThrowAnyException();
        progress.stop();
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
