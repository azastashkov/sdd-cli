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

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The human half of Gate 2 (design line 68): {@code sdd review approve|reject|redo <repo>
 * <plan.json>}. Everything the three share lives here — loading the run, refusing while
 * {@code sdd implement} still holds the lock, applying the transition through {@link Decisions}
 * (which owns every cross-repo invariant; the CLI layer deliberately re-implements none of them),
 * persisting it, recording the event, and re-rendering {@code report.md} so the artifact reflects
 * the decision rather than a pre-decision snapshot.
 *
 * <p>No lock is taken: {@code RunStore.writeDecisions} is a temp+atomic-rename write precisely so
 * two humans deciding different repos concurrently cannot lose each other's verdicts.
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
    protected record Followup(int exitCode, RebuildPass.Outcome rebuild, boolean rebuilt,
                              List<String> trailer) {
        static Followup none() {
            return new Followup(0, null, false, List.of());
        }

        static Followup exiting(int exitCode) {
            return new Followup(exitCode, null, false, List.of());
        }
    }

    /** Applies this command's transition to {@code decisions} (which mutates in place). */
    protected abstract Decisions.Outcome decide(Decisions decisions, ReviewCommand.LoadedRun run);

    /** Work that only makes sense once the decision has been applied and persisted. */
    protected Followup followUp(ReviewCommand.LoadedRun run, PrintWriter out, PrintWriter err) {
        return Followup.none();
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
            ReviewCommand.LoadedRun run = ReviewCommand.load(parent.workspace(), planJsonPath, err);
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

            Decisions decisions = new Decisions(run.store().readDecisions(run.runDir()));
            Decision before = decisions.of(repo);
            Decisions.Outcome outcome = decide(decisions, run);
            if (!outcome.applied()) {
                err.println("refused: " + outcome.message());
                return 2;
            }
            // Persist before anything else observable happens: a crash in the follow-up work must
            // not lose the verdict a human just gave.
            run.store().writeDecisions(run.runDir(), decisions.asMap());
            Decision after = decisions.of(repo);
            run.store().appendEvent(run.runDir(), repo, before, after, decisions.reasonOf(repo));
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
            out.println("review written: " + ReviewCommand.writeReport(run,
                    ReviewCommand.collectDiffs(run),
                    rebuild == null ? Map.of() : rebuild.rebuilds(),
                    rebuild == null ? List.of() : rebuild.notLocallyVerified(),
                    rebuild == null ? List.of() : rebuild.restoreFailures(),
                    rebuild == null ? List.of() : rebuild.contracts(),
                    followup.rebuilt()));
            followup.trailer().forEach(out::println);
            return followup.exitCode();
        } catch (RuntimeException | IOException e) {
            err.println("error: " + e.getMessage());
            return 4;
        }
    }

    static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(7, sha.length()));
    }

    @Command(name = "approve",
            description = "Approve a repo's run branch and squash it into the one reviewed commit",
            exitCodeOnInvalidInput = 4)
    public static final class Approve extends DecisionCommand {
        @Override
        protected Decisions.Outcome decide(Decisions decisions, ReviewCommand.LoadedRun run) {
            return decisions.approve(repo, run.plan(), run.state());
        }

        @Override
        protected Followup followUp(ReviewCommand.LoadedRun run, PrintWriter out, PrintWriter err) {
            RepoRun repoRun = ReviewCommand.byName(run.state()).get(repo);
            Path root = run.paths().get(repo);
            if (root == null || repoRun.branch() == null || repoRun.checkpointSha() == null) {
                // Decisions.approve already proved the repo SUCCEEDED, so this is a stale knowledge
                // base or a hand-edited state.json — the verdict still stands, there is just no
                // branch to collapse.
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
                return Followup.exiting(2);
            }
            if (!result.squashed()) {
                // Either the branch already was one commit or its range was a net no-op — in both
                // cases the sha did not move, so state.json must NOT be rewritten.
                out.println("already a single commit (" + shortSha(result.sha()) + ")");
                return Followup.none();
            }
            // Load-bearing, not bookkeeping: Resume.prepare fails any SUCCEEDED repo whose branch
            // head differs from its recorded checkpoint with exit 4, so without this write-back the
            // very "sdd implement --retry" that redo prints would hard-fail once any sibling had
            // been approved. Written before the count is printed so nothing can lose it.
            run.state().set(repo, repoRun.state(), repoRun.branch(), result.sha(), repoRun.detail());
            run.store().writeState(run.runDir(), run.state());
            // Counted against the PRE-squash checkpoint, whose objects are still resolvable (the
            // squash only moved the branch ref); SquashApprove made the identical call moments ago.
            out.println("squashed " + RunGit.commitsBetween(root, baseSha, repoRun.checkpointSha())
                    + " commits into " + shortSha(result.sha()));
            return Followup.none();
        }
    }

    @Command(name = "reject", description = "Reject a repo's run branch", exitCodeOnInvalidInput = 4)
    public static final class Reject extends DecisionCommand {
        @Option(names = "--reason", description = "Why the work was rejected")
        String reason = "";

        @Override
        protected Decisions.Outcome decide(Decisions decisions, ReviewCommand.LoadedRun run) {
            return decisions.reject(repo, run.plan(), reason);
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
        protected Decisions.Outcome decide(Decisions decisions, ReviewCommand.LoadedRun run) {
            return decisions.redo(repo, run.plan(), reason);
        }

        @Override
        protected Followup followUp(ReviewCommand.LoadedRun run, PrintWriter out, PrintWriter err) {
            List<String> retry = List.of("then run: sdd implement --workspace " + parent.workspace()
                    + " --retry " + repo + " " + planJsonPath);
            List<String> downstream = Decisions.transitiveDownstream(repo, run.plan());
            if (noReverify || downstream.isEmpty()) {
                return new Followup(0, null, false, retry);
            }
            // recheckContracts = false on purpose: contract drift is a whole-estate finding that
            // belongs to a full sdd review pass. A subset run would compare the checkpoint trees of
            // these repos against the working trees of every repo outside the subset.
            RebuildPass.Outcome rebuild = RebuildPass.run(downstream, run.plan(), run.state(),
                    run.paths(), run.config(), run.runDir(), run.store(), false, err);
            for (String down : downstream) {
                EstateRebuild.Result result = rebuild.rebuilds().get(down);
                out.println("re-verify " + down + ": " + verdict(down, result, rebuild));
            }
            // A repo left stranded off its original branch demands human action — the same rule
            // ReviewCommand applies to its own rebuild pass.
            return new Followup(rebuild.restoreFailures().isEmpty() ? 0 : 2, rebuild, true, retry);
        }

        private static String verdict(String repo, EstateRebuild.Result result,
                                      RebuildPass.Outcome rebuild) {
            if (result != null) {
                return result.ok() ? "OK" : "FAILED";
            }
            return rebuild.notLocallyVerified().contains(repo)
                    ? "not locally verified (all verification tasks excluded)"
                    : "skipped (not SUCCEEDED in this run)";
        }
    }
}
