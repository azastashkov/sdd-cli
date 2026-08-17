package sdd.core.progress;

/**
 * The seam every long-running command reports through: estate-wide phases, per-item work, a
 * sub-phase detail within the current item, and standalone notes — without ever naming a writer.
 * {@code sdd-core} has no writer (the same reason {@code
 * DoctorCommand.keyFilePermissionWarning} returns a warning as data rather than printing one
 * itself — see {@code DoctorCommand.java:235-236}), and {@code sdd-index}'s per-repo loop, which
 * needs to emit these events, sits below {@code sdd-cli} in the dependency graph
 * ({@code sdd-cli -> sdd-index -> sdd-core}). The renderers that actually hold a {@link
 * java.io.PrintWriter} — {@code LiveProgress}, {@code PlainProgress} — live in {@code sdd-cli}
 * and are reached only through this interface.
 *
 * <p><b>An implementation must never throw.</b> Modelled on {@code
 * sdd.core.diagnostics.Diagnostics}: resolve at the boundary, wrap every path in a catch-all,
 * degrade rather than propagate. This is load-bearing, not hygiene — an exception out of a
 * progress call inside {@code Orchestrator.transitionLocked} would reach the unit task's catch
 * and land in {@code fatal} (design doc, "Arming" section), so a progress indicator could abort
 * an eight-repo implement run. Every real implementation therefore catches broadly around its
 * own body; callers are never expected to guard a {@link Progress} call themselves.
 *
 * <p>{@link #phase(String)} is a convenience default over {@link #phase(String, int)} with
 * {@code total = 0} ("no known item count") — the design's event list names only the one-arg
 * form, but a phase with a known number of upcoming {@link #start}/{@link #finish} items (an
 * indexing pass over N repos) needs to say so once, up front, rather than have every
 * implementation re-derive N by counting {@link #start} calls it has not seen yet.
 */
public interface Progress {
    /** An estate-wide step with no known item count — e.g. a fixed pass like linking or usage
     *  resolution that is not itself a loop over repos. Resets any in-flight item/phase state a
     *  renderer was tracking; see {@link #phase(String, int)}. */
    default void phase(String name) {
        phase(name, 0);
    }

    /** An estate-wide step that is about to process {@code total} items via {@link #start}/
     *  {@link #finish} calls — e.g. {@code sdd index} over N repos. {@code total <= 0} means "not
     *  known", identical to {@link #phase(String)}. */
    void phase(String name, int total);

    /** One item of the current phase has begun — a repo entering an index/rebuild pass, or a
     *  repo transitioning to RUNNING in {@code Orchestrator}. Multiple items may be in flight at
     *  once (the parallel {@code implement} case); a renderer decides how to draw that. */
    void start(String item);

    /** The item named in a prior {@link #start} has completed, successfully or not — completion
     *  is what advances the "done" count, not the outcome. */
    void finish(String item);

    /** A sub-phase within whichever item is currently in flight (e.g. "gradle extract" inside a
     *  repo's index pass). Meaningful only when exactly one item is in flight; a renderer may
     *  ignore it otherwise. */
    void detail(String text);

    /** A message, OWNED by this seam (the text is this call's only argument), that must survive
     *  on its own line rather than being overwritten by the next frame — e.g. an orchestrator-level
     *  pause note. Every implementation passes the text through unchanged: never a {@code <repo>: }
     *  prefix, which {@code ReviewReport}/{@code InteractiveReview.replaceForRepos} parse ({@code
     *  RebuildPass.java:100-101}) — that formatting is the caller's job, not this seam's. Unlike
     *  {@link #suspend}, a {@link #noOp()}/stopped renderer may legitimately drop this text
     *  entirely: it is progress-adjacent commentary, not data the caller has anywhere else to put. */
    void note(String text);

    /**
     * Runs {@code action} with the live line suspended — erased before, repainted after — so a
     * caller that already owns its OWN writer can print through it without a painted frame
     * clobbering it or being clobbered. This is the seam for a substantive, caller-owned finding
     * that must be printed UNCONDITIONALLY, in every {@link Progress} mode including {@link
     * #noOp()} — {@code RebuildPass}'s "could not stage ... at its checkpoint" is a review
     * finding, not progress chrome, and must never silently disappear just because progress
     * reporting happens to be off (an earlier version of this seam routed it through {@link #note}
     * instead, which is exactly the bug this method exists to avoid: {@code note} is this seam's
     * own optional commentary and a stopped/no-op renderer is allowed to drop it, but a caller's
     * own review finding is not this seam's to drop).
     *
     * <p>The default implementation — every renderer except one with an actual line to erase and
     * repaint — simply runs {@code action}, so {@link #noOp()} and a plain/CI renderer reproduce
     * this call's pre-existing behavior exactly: {@code action} runs, full stop, unconditionally.
     * An implementation must still never throw: {@code action} is run inside the same catch-all
     * every other method here uses, per this interface's own contract above.
     */
    default void suspend(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // P5: a caller's own action is held to the same "never fails the run" contract as
            // every other side effect this seam exposes.
        }
    }

    /** Ends this progress session. A live renderer erases its line so the next output starts
     *  clean and stops its background ticker; a plain or no-op renderer has nothing to undo.
     *  Callers pair this with {@code try}/{@code finally}, not try-with-resources — this
     *  interface deliberately does not extend {@link AutoCloseable}, whose {@code close()} is
     *  declared to throw. */
    void stop();

    /** The default every command gets until {@code SddCli.main} arms a real factory: every
     *  method above does nothing. */
    static Progress noOp() {
        return NoOpProgress.INSTANCE;
    }

    /**
     * What {@code SddCli.main} assigns — {@code sdd-cli}'s writer-holding renderers are built
     * behind this so the field the command tree carries (and that reaches down into {@code
     * sdd-index}) is still a {@code sdd-core} type with no writer in its own shape. {@link
     * #open()} takes no arguments precisely so a writer can never leak into this signature: the
     * real writer is captured in the lambda {@code SddCli.main} builds, not passed here.
     */
    @FunctionalInterface
    interface Factory {
        Progress open();
    }
}
