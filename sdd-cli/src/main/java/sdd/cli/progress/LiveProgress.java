package sdd.cli.progress;

import sdd.core.progress.Progress;

import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The TTY renderer: a single self-updating line, {@code \r} + space-padding + {@code flush()}
 * only — no ANSI, no colour, no new dependency (design doc, "Renderers"; there is no JLine/Jansi
 * in {@code libs.versions.toml} and none is wanted).
 *
 * <p><b>One model, two line shapes, chosen by how many items are in flight.</b> The design's
 * sequential format ({@code index}/{@code review}: {@code "4/11  order-service  gradle extract
 * 0:42"}) and parallel format ({@code implement}: {@code "3/11 done  running: order-service
 * 4:12, billing 1:07 (+1)   12:45"}) are driven off the exact same five {@link Progress} events
 * — the design's per-command wiring table never asks a caller to declare which shape it wants.
 * The distinguishing signal that already exists is how many items {@link #start} has opened
 * without a matching {@link #finish}: at most one (sequential callers process one item at a
 * time) renders the single-item line; more than one (only {@code implement}'s parallel scheduler
 * produces this) renders the "running:" line. This is disclosed here rather than left implicit
 * because it means {@code index} and {@code review} get a correct frame with zero renderer-side
 * special-casing in their own code — the same reason the model exists at all before Task 3 is the
 * first caller to exercise the parallel branch.
 *
 * <p><b>Counter semantics differ between the two shapes on purpose, matching the design's own
 * examples.</b> Sequential's numerator is the 1-based position of the item currently in flight
 * (items started so far, including this one) — it must show {@code 4} while item 4 is still
 * being worked, not {@code 3}. Parallel's numerator is the {@code done} count, exactly as
 * labelled ({@code "3/11 done"}) — items still running are not yet counted. {@link #phase(String,
 * int)} resets both the in-flight map and the done counter, since each estate-wide phase counts
 * its own items from zero.
 *
 * <p><b>One monitor</b> ({@link #lock}) guards the mutable model and every write+flush — the
 * scheduled tick and every {@link Progress} event synchronize on it, so a frame is always
 * rendered from one consistent snapshot, never a torn read across two events.
 *
 * <p><b>Never throws (P5).</b> Every method's body — including the scheduled tick, whose own
 * uncaught exception would silently suppress every future tick per {@link
 * ScheduledExecutorService}'s contract — is wrapped in a catch-all, modelled on {@code
 * sdd.core.diagnostics.Diagnostics}: a failing writer degrades this renderer to doing nothing,
 * never aborts the command it reports on.
 */
public final class LiveProgress implements Progress {
    private static final int WIDTH = 80;
    private static final int MAX_NAMED_RUNNING = 2;

    private final PrintWriter writer;
    private final InstantSource clock;

    /** Package-private, not {@code ForTest}-suffixed: not an injected fake, just the real
     *  scheduler, exposed so {@code LiveProgressTest} can assert {@link #stop} actually shuts
     *  the daemon ticker down (a non-daemon thread left running would hang the JVM — see the
     *  class javadoc and design doc, "Renderers"). {@code null} only on the P5 fallback path
     *  below, when the ticker itself could not be created — every use of it is guarded. */
    final ScheduledExecutorService scheduler;

    private final Object lock = new Object();
    private boolean stopped;

    private String phaseName = "";
    private Instant phaseStart;
    private int total;
    private int done;
    /** Insertion order IS start order IS oldest-first — the design's required ordering falls out
     *  of {@link LinkedHashMap} for free, with no per-frame sort needed (and so nothing that
     *  could make the order flicker between ticks). */
    private final Map<String, Instant> inFlight = new LinkedHashMap<>();
    private String currentDetail = "";

    /**
     * P5 applies to construction too, not just the per-event methods below — the fact that the
     * sole call site ({@code SddCli.resolve}) happens to sit inside a try block, and that
     * production only ever passes the non-throwing {@link InstantSource#system()}, would make
     * "never throws" a property of the caller rather than of this class, which is exactly the
     * shape the ruling exists to rule out. {@link #safeInitialInstant} and {@link #safeScheduler}
     * are the two operations here that can throw ({@code clock.instant()}, thread/executor
     * creation) — both degrade rather than propagate.
     */
    public LiveProgress(PrintWriter writer, InstantSource clock) {
        this.writer = writer;
        this.clock = clock;
        this.phaseStart = safeInitialInstant(clock);
        this.scheduler = safeScheduler();
    }

    private static Instant safeInitialInstant(InstantSource clock) {
        try {
            return clock.instant();
        } catch (RuntimeException e) {
            // P5: a throwing clock must not fail construction. Every later read of `clock` goes
            // through a method already wrapped in its own catch-all (mutateAndPaint/tick/note),
            // so this fallback only ever affects the very first frame's phase-elapsed baseline.
            return Instant.EPOCH;
        }
    }

    private ScheduledExecutorService safeScheduler() {
        try {
            ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sdd-progress");
                t.setDaemon(true);
                return t;
            });
            // scheduleWithFixedDelay, not at-a-fixed-rate: a GC pause or a long paint must not
            // queue up a catch-up burst of ticks once the JVM is free again (design doc,
            // "Renderers").
            created.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
            return created;
        } catch (RuntimeException e) {
            // P5: if the ticker cannot even be created (e.g. the JVM refuses new threads), this
            // renderer must still not fail construction. Every Progress event still paints
            // synchronously on its own call; only the between-events tick is lost. Left null
            // rather than substituted with a dummy executor, since creating ANY executor here —
            // including a non-scheduling fallback — is exactly the operation that just failed;
            // every use of #scheduler below is guarded accordingly.
            return null;
        }
    }

    @Override
    public void phase(String name, int total) {
        mutateAndPaint(() -> {
            phaseName = name == null ? "" : name;
            this.total = Math.max(0, total);
            this.done = 0;
            inFlight.clear();
            currentDetail = "";
            phaseStart = clock.instant();
        });
    }

    @Override
    public void start(String item) {
        mutateAndPaint(() -> {
            inFlight.put(item, clock.instant());
            currentDetail = "";
        });
    }

    /**
     * Idempotent per item: {@code done} advances only when {@code item} was actually in flight
     * ({@link Map#remove} returns non-null only the first time). Without this, a repeated {@code
     * finish} on an already-finished item — or one with no matching {@link #start} at all — would
     * inflate {@code done} past what the estate actually did. This matters most for {@code
     * implement} (a later task), whose scheduler drives this off {@code Orchestrator} state
     * transitions, where a repo can plausibly reach a terminal state by more than one path.
     */
    @Override
    public void finish(String item) {
        mutateAndPaint(() -> {
            if (inFlight.remove(item) != null) {
                done++;
            }
        });
    }

    @Override
    public void detail(String text) {
        mutateAndPaint(() -> currentDetail = text == null ? "" : text);
    }

    @Override
    public void note(String text) {
        try {
            synchronized (lock) {
                if (stopped) {
                    return;
                }
                eraseLocked();
                writer.println(text);
                writer.flush();
                paintLocked();
            }
        } catch (RuntimeException e) {
            // P5: a note must survive on its own line when it can, but must never fail the run.
        }
    }

    /**
     * The counterpart to {@link #note} for a caller that owns its own writer and must print
     * through it UNCONDITIONALLY (design rationale on {@link Progress#suspend}) — same
     * erase/repaint choreography as {@link #note}, all under {@link #lock} so a scheduled tick
     * landing mid-action still sees a consistent "erased" model rather than racing a partial
     * repaint, but {@code action} always runs, even once {@link #stopped} — unlike {@code note},
     * which is this renderer's own optional commentary and may legitimately go silent once
     * stopped, {@code action} is the caller's own finding, which this renderer has no authority to
     * drop.
     */
    @Override
    public void suspend(Runnable action) {
        try {
            synchronized (lock) {
                if (stopped) {
                    action.run();
                    return;
                }
                eraseLocked();
                action.run();
                paintLocked();
            }
        } catch (RuntimeException e) {
            // P5.
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (stopped) {
                return;
            }
            stopped = true;
            try {
                eraseLocked();
            } catch (RuntimeException e) {
                // P5 — fall through: the scheduler must still be shut down below regardless.
            }
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void tick() {
        try {
            synchronized (lock) {
                if (!stopped) {
                    paintLocked();
                }
            }
        } catch (RuntimeException e) {
            // P5, and load-bearing here specifically: an uncaught exception from a
            // scheduleWithFixedDelay task suppresses every future execution of it.
        }
    }

    private void mutateAndPaint(Runnable mutation) {
        try {
            synchronized (lock) {
                if (stopped) {
                    return;
                }
                mutation.run();
                paintLocked();
            }
        } catch (RuntimeException e) {
            // P5.
        }
    }

    private void paintLocked() {
        String content = renderLineLocked();
        if (content.length() > WIDTH) {
            content = content.substring(0, WIDTH);
        }
        writer.print('\r');
        writer.print(padRight(content));
        writer.flush();
    }

    private void eraseLocked() {
        writer.print('\r');
        writer.print(" ".repeat(WIDTH));
        writer.print('\r');
        writer.flush();
    }

    private static String padRight(String s) {
        if (s.length() >= WIDTH) {
            return s;
        }
        return s + " ".repeat(WIDTH - s.length());
    }

    private String renderLineLocked() {
        Instant now = clock.instant();
        if (inFlight.size() == 1) {
            return renderSequentialLocked(now);
        }
        if (inFlight.size() > 1) {
            return renderParallelLocked(now);
        }
        return renderIdleLocked(now);
    }

    private String renderSequentialLocked(Instant now) {
        Map.Entry<String, Instant> only = inFlight.entrySet().iterator().next();
        String counter = total > 0 ? (done + 1) + "/" + total : "";
        String elapsed = Elapsed.format(Duration.between(only.getValue(), now));
        return joinColumns(counter, only.getKey(), currentDetail, elapsed);
    }

    private String renderParallelLocked(Instant now) {
        // Oldest-first: LinkedHashMap's iteration order already IS insertion (= start) order.
        List<Map.Entry<String, Instant>> ordered = new ArrayList<>(inFlight.entrySet());
        int shown = Math.min(MAX_NAMED_RUNNING, ordered.size());
        StringBuilder running = new StringBuilder("running: ");
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                running.append(", ");
            }
            Map.Entry<String, Instant> entry = ordered.get(i);
            running.append(entry.getKey()).append(' ').append(Elapsed.format(Duration.between(entry.getValue(), now)));
        }
        int remainder = ordered.size() - shown;
        if (remainder > 0) {
            running.append(" (+").append(remainder).append(')');
        }
        String counter = (total > 0 ? done + "/" + total : Integer.toString(done)) + " done";
        String wallElapsed = Elapsed.format(Duration.between(phaseStart, now));
        return joinColumns(counter, running.toString(), wallElapsed);
    }

    private String renderIdleLocked(Instant now) {
        if (phaseName.isEmpty()) {
            return "";
        }
        return joinColumns(phaseName, Elapsed.format(Duration.between(phaseStart, now)));
    }

    private static String joinColumns(String... columns) {
        StringBuilder sb = new StringBuilder();
        for (String c : columns) {
            if (c == null || c.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
