package sdd.cli.review;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.cli.ReviewCommand;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunStore;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;

/**
 * The human half of Gate 2 (design line 68): {@code sdd review approve|reject|redo <repo>
 * <plan.json>}. Everything the three share lives here — loading the run, refusing while
 * {@code sdd implement} still holds the lock, applying the transition through {@link Decisions}
 * (which owns every cross-repo invariant; the CLI layer deliberately re-implements none of them),
 * persisting it, recording the event, and re-rendering {@code report.md} so the artifact reflects
 * the decision rather than a pre-decision snapshot.
 *
 * <p><b>Optimistic retry, not a lock.</b> No lock is taken beyond the run lock {@code sdd implement}
 * holds. Instead, {@link #applyWithRetry} reads a fingerprinted snapshot, applies this command's one
 * transition, and writes back with {@link RunStore#writeDecisions(java.nio.file.Path, java.util.Map,
 * String)}, which refuses when the file changed underneath. On refusal it re-reads
 * and re-applies the SAME transition against the fresh state, up to {@link #MAX_ATTEMPTS} times —
 * capped so two humans deciding different repos of the same run at the same moment lose a race, not
 * a verdict, and so a livelock fails loudly instead of hanging. This is not serialization: a write
 * can still land in the gap between the fingerprint check and the rename, but that is caught on the
 * NEXT read, not silently lost. See {@code RunStore.writeDecisions}'s javadoc for exactly what that
 * guarantees.
 *
 * <p><b>That guarantee covers {@code decisions.json} and nothing else.</b> {@code approve}'s
 * post-squash write-back in {@link #squashAndRecord} is an unguarded read-modify-write of
 * {@code state.json}: {@link RunContext#load} snapshots the whole {@link
 * sdd.cli.implement.RunState} at command start, one repo's checkpoint sha is mutated on that
 * snapshot, and the entire document is republished. Two approves running at once therefore lose
 * one repo's new checkpoint — the loser's {@code state.json} still names the pre-squash sha, whose
 * branch head has moved, and the next {@code sdd implement --resume/--retry} hard-fails in
 * {@code Resume.prepare} with exit 4. That is a documented carried item, not a fixed one: it fails
 * loudly rather than lying, and fingerprinting a second file is a design change. Deciding two repos
 * of one run concurrently is safe for the verdicts; it is not safe for the checkpoints.
 */
public abstract class DecisionCommand implements Callable<Integer> {
    /** Only for {@code --workspace}, which is declared {@code scope = INHERIT} on the parent. */
    @ParentCommand ReviewCommand parent;

    @Parameters(index = "0", description = "The repo to decide on")
    String repo;

    // arity 0..1 with an explicit null check, mirroring the parent: it turns "review approve lib"
    // into one plain sentence instead of picocli's "Missing required parameter" wording, which on
    // this command tree has historically meant something else entirely.
    @Parameters(index = "1", arity = "0..1", description = "The approved <spec>.plan.json")
    Path planJsonPath;

    @Spec CommandSpec spec;

    /**
     * What a subcommand's post-decision work contributes: the exit code it demands, any rebuild
     * evidence the re-rendered report should carry, and lines to print after the report. The
     * decision itself is already applied and persisted by the time this is built — a non-zero
     * {@code exitCode} says the follow-up work failed, never that the decision did not stick.
     */
    protected record Followup(int exitCode, RebuildPass.Outcome rebuild, RebuildScope scope,
                              List<String> trailer) {
        static Followup none() {
            return new Followup(0, null, RebuildScope.none(), List.of());
        }

        static Followup exiting(int exitCode) {
            return new Followup(exitCode, null, RebuildScope.none(), List.of());
        }
    }

    /** Applies this command's transition to {@code decisions} (which mutates in place). */
    protected abstract Decisions.Outcome decide(Decisions decisions, RunContext run);

    /** Work that only makes sense once the decision has been applied and persisted. */
    protected Followup followUp(RunContext run, PrintWriter out, PrintWriter err) {
        return Followup.none();
    }

    /** A retry attempt's cap: high enough that two humans deciding different repos of the same run
     *  a moment apart never see it, low enough that a bug making every attempt conflict (or a truly
     *  pathological amount of contention) fails with a clear message instead of spinning forever. */
    static final int MAX_ATTEMPTS = 5;

    /** What one successful (or refused) application of a transition produced: the {@code Decisions}
     *  it was applied against (freshly rebuilt from disk on every retry, never the caller's stale
     *  copy), the value {@code repo} held immediately before this attempt, and the outcome. */
    protected record Applied(Decisions decisions, Decision before, Decisions.Outcome outcome) {
    }

    /**
     * Reads a fresh {@link RunStore.DecisionsSnapshot}, applies {@code transition} to the
     * {@code Decisions} built from it, and writes the result back with the snapshot's fingerprint.
     * On a refusal — the file changed underneath, per
     * {@link RunStore#writeDecisions(java.nio.file.Path, java.util.Map, String)} — this re-reads and
     * re-applies {@code transition} against the fresh state and tries again, up to
     * {@link #MAX_ATTEMPTS} times. A transition that itself refuses (only {@code approve} can) short
     * -circuits immediately without writing or retrying — a refusal is not a conflict.
     *
     * <p>This lives here, not on {@code RunStore}, because only the caller knows which ONE
     * transition to re-apply; a store-level retry could only replay an opaque map, which would
     * clobber whatever the other writer just added — the very bug this exists to fix. Shared by
     * {@link DecisionCommand#call} and {@link InteractiveReview}, whose walk applies the identical
     * one-transition-per-write shape across three different {@link Decisions} methods.
     */
    static Applied applyWithRetry(RunContext run, String repo,
                                  BiFunction<Decisions, RunContext, Decisions.Outcome> transition) {
        RunStore.DecisionsSnapshot snapshot = run.store().readDecisionsSnapshot(run.runDir());
        for (int attempt = 1; ; attempt++) {
            Decisions decisions = new Decisions(snapshot.decisions());
            Decision before = decisions.of(repo);
            Decisions.Outcome outcome = transition.apply(decisions, run);
            if (!outcome.applied()) {
                return new Applied(decisions, before, outcome);
            }
            if (run.store().writeDecisions(run.runDir(), decisions.asMap(), snapshot.fingerprint())) {
                return new Applied(decisions, before, outcome);
            }
            if (attempt >= MAX_ATTEMPTS) {
                throw new IllegalStateException("could not persist the decision for run " + run.runId()
                        + " after " + MAX_ATTEMPTS + " attempts — decisions.json kept changing "
                        + "underneath (concurrent writers); try again");
            }
            snapshot = run.store().readDecisionsSnapshot(run.runDir());
        }
    }

    @Override
    public final Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        try {
            if (planJsonPath == null) {
                err.println("error: missing <spec>.plan.json");
                return 4;
            }
            RunContext run = RunContext.load(parent.workspace(), planJsonPath, err);
            if (run == null) {
                return 4;
            }
            // Deciding mid-run would race sdd implement over state.json and the run branches, and
            // would judge work that is still being written.
            if (run.store().isLockHeld(run.runDir())) {
                err.println("error: run " + run.runId() + " is in progress (lock held) — wait for "
                        + "sdd implement to finish");
                return 4;
            }
            if (run.plan().repo(repo).isEmpty()) {
                err.println("error: " + repo + " is not in this plan");
                return 4;
            }

            // Persist before anything else observable happens: a crash in the follow-up work must
            // not lose the verdict a human just gave. applyWithRetry both applies decide() and
            // persists it, re-reading and re-applying against fresh state if another writer's
            // decision landed underneath us first.
            Applied applied = applyWithRetry(run, repo, this::decide);
            Decisions.Outcome outcome = applied.outcome();
            if (!outcome.applied()) {
                err.println("refused: " + outcome.message());
                return 2;
            }
            Decisions decisions = applied.decisions();
            Decision after = decisions.of(repo);
            run.store().appendEvent(run.runDir(), repo, applied.before(), after, decisions.reasonOf(repo));
            for (String downgraded : outcome.downgraded()) {
                run.store().appendEvent(run.runDir(), downgraded, Decision.APPROVED, Decision.PENDING,
                        "upstream " + repo + " is " + after);
            }

            out.println(outcome.message());
            if (!outcome.downgraded().isEmpty()) {
                out.println("downgraded to PENDING (re-decide): " + String.join(", ", outcome.downgraded()));
            }

            Followup followup = followUp(run, out, err);
            RebuildPass.Outcome rebuild = followup.rebuild();
            out.println("review written: " + run.writeReport(run.collectDiffs(),
                    rebuild == null ? Map.of() : rebuild.rebuilds(),
                    rebuild == null ? List.of() : rebuild.notLocallyVerified(),
                    rebuild == null ? List.of() : rebuild.stagingFailures(),
                    rebuild == null ? List.of() : rebuild.restoreFailures(),
                    rebuild == null ? List.of() : rebuild.contracts(),
                    followup.scope()));
            followup.trailer().forEach(out::println);
            return followup.exitCode();
        } catch (RuntimeException | IOException e) {
            err.println("error: " + e.getMessage());
            return 4;
        }
    }

    @Command(name = "approve",
            description = "Approve a repo's run branch and squash it into the one reviewed commit",
            exitCodeOnInvalidInput = 4)
    public static final class Approve extends DecisionCommand {
        @Override
        protected Decisions.Outcome decide(Decisions decisions, RunContext run) {
            return decisions.approve(repo, run.plan(), run.state());
        }

        @Override
        protected Followup followUp(RunContext run, PrintWriter out, PrintWriter err) {
            return squashAndRecord(run, repo, out, err);
        }
    }

    /**
     * The squash half of an approve: collapse the repo's run branch into the one reviewed commit
     * and, on a real squash, write the new checkpoint sha back into {@code state.json}. Shared by
     * {@link Approve} and {@link InteractiveReview} so a human walking the terminal loop and a
     * script calling {@code sdd review approve} leave the estate in identical shape — this is the
     * one piece of approve's behavior that is NOT expressed through {@link Decisions}.
     */
    static Followup squashAndRecord(RunContext run, String repo, PrintWriter out, PrintWriter err) {
        // repoRun is non-null in practice — decide() ran first and Decisions.approve refuses a
        // repo with no run state — but that is an ordering dependency two classes apart, so it
        // is checked here rather than assumed.
        RepoRun repoRun = run.byName().get(repo);
        Path root = run.paths().get(repo);
        if (repoRun == null || root == null || repoRun.branch() == null
                || repoRun.checkpointSha() == null) {
            // A stale knowledge base or a hand-edited state.json — the verdict still stands,
            // there is just no branch to collapse.
            err.println("warn: " + repo + " has no repo path or checkpoint on record; "
                    + "approved without squashing");
            return Followup.none();
        }
        String baseSha = run.plan().repo(repo).orElseThrow().baseSha();
        SquashApprove.Result result = SquashApprove.approve(root, repo, run.runId(),
                run.plan().specId(), repoRun, baseSha);
        if (!result.applied()) {
            // The decision stands; only the squash was refused (dirty tree, or the branch moved
            // off its checkpoint). Exit 2 so a script notices the repo still needs attention.
            err.println("squash refused: " + result.message());
            return afterRestore(result, 2, err);
        }
        if (!result.squashed()) {
            // Either the branch already was one commit or its range was a net no-op — in both
            // cases the sha did not move, so state.json must NOT be rewritten. The two cases
            // read very differently to a human ("already a single commit past <base>" vs "had
            // no net change since <base>"), and SquashApprove already knows which one fired —
            // so print its message rather than flattening both into one re-derived line.
            out.println(result.message());
            // Task 5: the squash was APPLIED (just a no-op), so this repo is genuinely approved —
            // reachable here, unlike the refusal branch above. See BitbucketDecisions' javadoc for
            // why this ordering (only after SquashApprove.approve granted the decision) is the
            // single most important property this task adds.
            BitbucketDecisions.afterApprove(run, repo, root, out, err);
            return afterRestore(result, 0, err);
        }
        // Load-bearing, not bookkeeping: Resume.prepare fails any SUCCEEDED repo whose branch
        // head differs from its recorded checkpoint with exit 4, so without this write-back the
        // very "sdd implement --retry" that redo prints would hard-fail once any sibling had
        // been approved. Written before the count is printed so nothing can lose it.
        run.state().set(repo, repoRun.state(), repoRun.branch(), result.sha(), repoRun.detail(),
                repoRun.failureCode());
        run.store().writeState(run.runDir(), run.state());
        // Counted against the PRE-squash checkpoint, whose objects are still resolvable (the
        // squash only moved the branch ref); SquashApprove made the identical call moments ago.
        out.println("squashed " + RunGit.commitsBetween(root, baseSha, repoRun.checkpointSha())
                + " commits into " + Shas.shortSha(result.sha()));
        // Task 5: strictly AFTER the state.json write-back above, never before — see
        // BitbucketDecisions' class javadoc for why the ordering is load-bearing, not incidental.
        BitbucketDecisions.afterApprove(run, repo, root, out, err);
        return afterRestore(result, 0, err);
    }

    /**
     * Turns {@link SquashApprove}'s restore failure into the {@link Followup} the caller returns.
     * Deliberately called only AFTER the {@code state.json} write-back above: the restore used to
     * throw out of {@code approve}'s own {@code finally}, which replaced the {@code Result} and with
     * it the write-back — leaving the branch squashed while {@code state.json} still named the old
     * checkpoint. That residue is invisible afterwards ({@link RunContext#checkpoints} excludes
     * APPROVED repos from drift, because approve legitimately rewrites the checkpoint) and hard-fails
     * the next {@code sdd implement --resume/--retry} in {@code Resume.prepare}. A stranded repo
     * still forces a non-zero exit, exactly as before — it just no longer costs the write-back to
     * say so, and it now reaches {@code report.md} through the same restore-failures section
     * {@link RebuildPass}'s findings use.
     */
    private static Followup afterRestore(SquashApprove.Result result, int exitCode, PrintWriter err) {
        if (result.restoreFailure() == null) {
            return exitCode == 0 ? Followup.none() : Followup.exiting(exitCode);
        }
        err.println("warn: could not restore " + result.restoreFailure()
                + " — it is left on its run branch");
        return new Followup(Math.max(exitCode, 2),
                new RebuildPass.Outcome(Map.of(), List.of(), List.of(),
                        List.of(result.restoreFailure()), List.of()),
                RebuildScope.none(), List.of());
    }

    @Command(name = "reject", description = "Reject a repo's run branch", exitCodeOnInvalidInput = 4)
    public static final class Reject extends DecisionCommand {
        @Option(names = "--reason", description = "Why the work was rejected")
        String reason = "";

        @Override
        protected Decisions.Outcome decide(Decisions decisions, RunContext run) {
            return decisions.reject(repo, run.plan(), reason);
        }

        @Override
        protected Followup followUp(RunContext run, PrintWriter out, PrintWriter err) {
            // Reached only once decide() above has already applied and persisted REJECTED (see
            // DecisionCommand#call) — no ordering hazard here the way approve's squash-then-merge
            // has: declining has no local git side effect to get out of order with.
            BitbucketDecisions.afterReject(run, repo, out, err);
            return Followup.none();
        }
    }

    @Command(name = "redo", description = "Mark a repo for re-implementation and re-verify its "
            + "downstream subtree", exitCodeOnInvalidInput = 4)
    public static final class Redo extends DecisionCommand {
        @Option(names = "--reason", description = "Why the work must be redone")
        String reason = "";

        @Option(names = "--no-reverify", description = "Skip re-verifying the downstream subtree")
        boolean noReverify;

        @Override
        protected Decisions.Outcome decide(Decisions decisions, RunContext run) {
            return decisions.redo(repo, run.plan(), reason);
        }

        @Override
        protected Followup followUp(RunContext run, PrintWriter out, PrintWriter err) {
            return redoFollowUp(run, repo, noReverify, parent.workspace(), planJsonPath, out, err);
        }
    }

    /**
     * The re-verify half of a redo: run {@link RebuildPass} over the repo's transitive downstream
     * closure, print a verdict per downstream repo, and escalate to exit 2 on a staging or restore
     * failure. Shared by {@link Redo} and {@link InteractiveReview} — design line 67 makes this pass
     * part of what "redo" MEANS, not optional diagnostics, so a human redoing a repo from the
     * terminal loop must get the identical re-verify a script calling {@code sdd review redo} gets,
     * not just the bare {@link Decisions#redo} state transition.
     */
    static Followup redoFollowUp(RunContext run, String repo, boolean noReverify, Path workspace,
                                 Path planJsonPath, PrintWriter out, PrintWriter err) {
        List<String> retry = List.of("then run: sdd implement --workspace " + workspace
                + " --retry " + repo + " " + planJsonPath);
        List<String> downstream = Decisions.transitiveDownstream(repo, run.plan());
        if (noReverify || downstream.isEmpty()) {
            return new Followup(0, null, RebuildScope.none(), retry);
        }
        // recheckContracts = false on purpose: contract drift is a whole-estate finding that
        // belongs to a full sdd review pass. A subset run would compare the checkpoint trees of
        // these repos against the working trees of every repo outside the subset.
        RebuildPass.Outcome rebuild = RebuildPass.run(downstream, run.plan(), run.state(),
                run.paths(), run.config(), run.runDir(), run.store(), false, err);
        // The repo being redone is itself always staged-but-outside-the-subset (it is excluded
        // from its own downstream closure) and is the most load-bearing provider of every
        // verdict below — and a human who was just reading its diff plausibly has uncommitted
        // edits in it. So a staging failure here is the common case, not the exotic one, and a
        // verdict computed over it must never read as a clean pass.
        boolean staged = rebuild.stagingFailures().isEmpty();
        if (!staged) {
            err.println("error: could not stage " + String.join("; ", rebuild.stagingFailures())
                    + " — the verdicts below were computed against pre-run upstream code");
        }
        for (String down : downstream) {
            EstateRebuild.Result result = rebuild.rebuilds().get(down);
            out.println("re-verify " + down + ": " + verdict(down, result, rebuild)
                    + (staged ? "" : " (UNRELIABLE — upstream not staged)"));
        }
        // A repo left stranded off its original branch demands human action — the same rule
        // ReviewCommand applies to its own rebuild pass. The scope is deliberately NOT "estate":
        // these verdicts cover one downstream subtree, and the report must never total them as if
        // they covered the whole estate.
        return new Followup(staged && rebuild.restoreFailures().isEmpty() ? 0 : 2,
                rebuild, RebuildScope.none().withReverifiedSubtreeOf(repo), retry);
    }

    private static String verdict(String repo, EstateRebuild.Result result, RebuildPass.Outcome rebuild) {
        if (result != null) {
            return result.ok() ? "OK" : "FAILED";
        }
        return rebuild.notLocallyVerified().contains(repo)
                ? "not locally verified (all verification tasks excluded)"
                : "skipped (not SUCCEEDED in this run)";
    }
}
