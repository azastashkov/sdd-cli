package sdd.cli.review;

import picocli.CommandLine.Command;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only support for the concurrent-decision-write tests in
 * {@code sdd.cli.ReviewDecisionsCommandTest}: a {@code redo} whose {@code decide()} — the exact
 * extension point every real subcommand ({@link DecisionCommand.Approve},
 * {@link DecisionCommand.Reject}, {@link DecisionCommand.Redo}) already overrides — writes a
 * conflicting decision for a DIFFERENT repo directly to disk immediately before returning, on every
 * attempt up to {@code injectUpToAttempt}. Because {@link DecisionCommand#applyWithRetry} calls
 * {@code decide()} strictly between its own read and its own write, this lands the "someone else
 * decided concurrently" race in exactly the window the retry loop has to detect — deterministically,
 * with no thread timing and no seam added to production code.
 *
 * <p>{@code injectUpToAttempt = 1} models one other writer sneaking in once (the retry then
 * converges on attempt 2). {@code injectUpToAttempt} at or above the retry cap models a writer that
 * NEVER stops conflicting, forcing the loop's attempt cap to fire.
 *
 * <p>Lives in {@code sdd.cli.review} (not the test's own {@code sdd.cli}) because
 * {@code repo}/{@code planJsonPath}/{@code parent}/{@code spec} are package-private on
 * {@link DecisionCommand}.
 */
@Command(name = "conflict-injecting-redo", exitCodeOnInvalidInput = 4)
public final class ConflictInjectingRedo extends DecisionCommand {
    private final AtomicInteger callCount = new AtomicInteger();
    private final int injectUpToAttempt;
    private final String conflictingRepo;

    public ConflictInjectingRedo(String conflictingRepo, int injectUpToAttempt) {
        this.conflictingRepo = conflictingRepo;
        this.injectUpToAttempt = injectUpToAttempt;
    }

    @Override
    protected Decisions.Outcome decide(Decisions decisions, RunContext run) {
        int attempt = callCount.incrementAndGet();
        if (attempt <= injectUpToAttempt) {
            // A DIFFERENT reason every time: the fingerprint must actually change on every
            // injection, or a repeat injection with IDENTICAL bytes would leave the file's
            // fingerprint unchanged and our own write would wrongly appear to still match.
            run.store().writeDecisions(run.runDir(), Map.of(conflictingRepo,
                    new DecisionRecord(Decision.REJECTED, "conflict #" + attempt)));
        }
        return decisions.redo(repo, run.plan(), "needs rework");
    }
}
