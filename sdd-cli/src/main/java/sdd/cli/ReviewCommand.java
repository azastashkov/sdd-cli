package sdd.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.Scheduler;
import sdd.cli.review.ContractRecheck;
import sdd.cli.review.DecisionCommand;
import sdd.cli.review.DecisionRecord;
import sdd.cli.review.EstateRebuild;
import sdd.cli.review.InteractiveReview;
import sdd.cli.review.RebuildPass;
import sdd.cli.review.RebuildScope;
import sdd.cli.review.ReviewReport;
import sdd.cli.review.RunContext;
import sdd.cli.review.SkippedGates;
import sdd.plan.source.SourceBullet;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Gate-2 review (design line 66-67): rebuild the estate in topo order against its checkpoints,
 * re-check actualized contracts against fresh extraction, render the release runbook, and write
 * the report + per-repo diffs. Every repo the rebuild checks out is restored to its original
 * branch/commit in a {@code finally}, even when the rebuild or the checkout itself fails.
 *
 * <p>No lock is taken, but a LIVE one refuses the command (exit 4) on every path, not just the
 * mutating ones: even a plain review checks the whole estate out to its checkpoints and reads a
 * {@code state.json} that {@code sdd implement} is still rewriting, so racing it produces a report
 * about an estate that no longer exists. A STALE lock only warns — the run whose {@code implement}
 * crashed is exactly the run a human most needs to see.
 *
 * <p>The {@code approve}/{@code reject}/{@code redo} subcommands (design line 68) are the mutating
 * half of the same gate; both halves read the run and write their artifacts through
 * {@link RunContext}, so a decision leaves behind the same artifacts a plain review does.
 */
@Command(name = "review",
        description = "Rebuild the estate against its checkpoints and render the Gate-2 review report",
        exitCodeOnInvalidInput = 4,
        subcommands = {DecisionCommand.Approve.class, DecisionCommand.Reject.class,
                DecisionCommand.Redo.class})
public final class ReviewCommand implements Callable<Integer> {
    // scope = INHERIT because picocli does NOT inherit parent options: without it,
    // "review --workspace w approve lib p.plan.json" leaves the subcommand reading the CURRENT
    // directory, silently reviewing the wrong estate instead of erroring.
    @Option(names = "--workspace", scope = CommandLine.ScopeType.INHERIT,
            description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Option(names = "--no-rebuild", description = "Skip the estate rebuild verification pass")
    boolean noRebuild;

    @Option(names = "--interactive", description = "Walk PENDING repos and prompt approve/reject/"
            + "redo/view/skip/quit after the report is written")
    boolean interactive;

    @Option(names = "--no-comment", description = "Suppress the Jira write-back comment even when "
            + "atlassian.write_back: comment is configured")
    boolean noComment;

    /** Test-only injection point: {@code null} in real use, where {@link #call} falls back to
     *  {@code System.in}. {@link InteractiveReview} itself never touches {@code System.in} — this is
     *  the one place the real terminal is wired in, so the interactive loop stays fully testable
     *  without one. */
    BufferedReader in;

    // arity 0..1, not the default 1: picocli validates a PARENT's required positionals BEFORE it
    // recurses into a subcommand, so a required <planJsonPath> here made every
    // "review approve lib p.plan.json" die with "Missing required parameter" and exit 4 — an error
    // indistinguishable from genuine bad input. The null check in call() replaces the enforcement.
    @Parameters(index = "0", arity = "0..1", description = "The approved <spec>.plan.json")
    Path planJsonPath;

    @Spec CommandSpec spec;

    /** The subcommands live in {@code sdd.cli.review} and reach the inherited option through
     *  {@code @ParentCommand}, so the field itself cannot simply be package-private. */
    public Path workspace() {
        return workspace;
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        try {
            if (planJsonPath == null) {
                err.println("error: missing <spec>.plan.json");
                return 4;
            }
            RunContext run = RunContext.load(workspace, planJsonPath, err);
            if (run == null) {
                return 4;
            }
            // Every path, not just --interactive: the rebuild pass checks the whole estate out to
            // its checkpoints and back, which would fight sdd implement over the same working
            // trees, and the state.json a concurrent run is rewriting cannot be reported on
            // truthfully. Same refusal DecisionCommand already applies to the scripted decisions.
            if (run.store().isLockHeld(run.runDir())) {
                err.println("error: run " + run.runId() + " is in progress (lock held) — wait for "
                        + "sdd implement to finish");
                return 4;
            }
            if (run.store().isLockStale(run.runDir())) {
                // Never a refusal: a crashed implement leaves this behind, and that run is the one
                // a human most needs to review. But the report is about a run that did not finish
                // cleanly, so the reader is told.
                err.println("warn: run " + run.runId() + " has a stale lock (the process that held "
                        + "it is gone) — reviewing anyway; sdd implement may have crashed");
            }

            RunContext.Diffs diffs = run.collectDiffs();

            Map<String, EstateRebuild.Result> rebuilds;
            List<String> notLocallyVerified;
            List<String> stagingFailures;
            List<String> restoreFailures;
            List<ContractRecheck.Finding> contracts;
            if (noRebuild) {
                // Nothing is checked out, so this reads whatever branch the human happens to
                // be standing on — the caller (report) still needs the findings either way.
                rebuilds = Map.of();
                notLocallyVerified = List.of();
                stagingFailures = List.of();
                restoreFailures = List.of();
                contracts = ContractRecheck.check(run.plan(), run.state(), run.paths(),
                        run.store(), run.runDir(), run.config().nodeHome());
            } else {
                RebuildPass.Outcome outcome = RebuildPass.run(Scheduler.sequence(run.plan().order()),
                        run.plan(), run.state(), run.paths(), run.config(), run.runDir(), run.store(),
                        true, err);
                rebuilds = outcome.rebuilds();
                notLocallyVerified = outcome.notLocallyVerified();
                stagingFailures = outcome.stagingFailures();
                restoreFailures = outcome.restoreFailures();
                contracts = outcome.contracts();
            }

            RebuildScope scope = noRebuild ? RebuildScope.skipped() : RebuildScope.estate();
            out.println("review written: " + run.writeReport(diffs, rebuilds, notLocallyVerified,
                    stagingFailures, restoreFailures, contracts, scope));
            // Task 4 (Gate 2 write-back): strictly AFTER report.md is durably written above, and
            // via JiraWriteBack — which never throws — so a Jira outage can never turn this
            // review into a failed one. Only the base `sdd review` write, not every re-render a
            // `sdd review approve/reject/redo` decision triggers (RunContext's own javadoc: those
            // re-run writeReport so the artifact reflects the run as it stands) — the brief names
            // exactly two call sites, and repeating this per decision was not one of them.
            commentOnJiraSources(run, out, err);

            int interactiveExit = 0;
            if (interactive) {
                BufferedReader reader = in != null ? in
                        : new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                RebuildPass.Outcome baseline = new RebuildPass.Outcome(rebuilds, notLocallyVerified,
                        stagingFailures, restoreFailures, contracts);
                interactiveExit = InteractiveReview.run(reader, out, err,
                        new InteractiveReview.Context(run, workspace, planJsonPath, baseline, scope));
            }

            boolean allSucceeded = Scheduler.sequence(run.plan().order()).stream()
                    .allMatch(repo -> run.state().stateOf(repo) == RepoState.SUCCEEDED);
            boolean anyRebuildFailed = rebuilds.values().stream().anyMatch(r -> !r.ok());
            // Read AFTER the interactive walk, not before: an approve squashes the branch and
            // rewrites the checkpoint, so drift computed earlier would describe a run that has
            // since been decided.
            List<String> drift = run.checkpoints(run.store().readDecisions(run.runDir())).drift();
            // A declared compatibility guarantee whose gate never ran is the fourth. Exit 0 on it
            // would be this command asserting the guarantee holds on the strength of a check that
            // did not happen — the one claim a review must never make. Reported rather than
            // silently tolerated even though it can fail a run that is otherwise green, because
            // the alternative is a green review that means less than a reader takes it to mean.
            List<SkippedGates.Skipped> skippedGates = run.skippedGates();
            // A failed restore leaves a repo stranded off its original branch — the report's own
            // legend calls that a failed checkout, so it must fail the review too. A staging
            // failure is a failed checkout by another name, and worse: it silently invalidates
            // every verdict downstream of it, so it can never be reported as a clean pass. Drift
            // is the third: every diff and runbook line below describes a checkpoint the branch no
            // longer carries, so a human acting on this report would act on the wrong tree.
            int baseExit = allSucceeded && !anyRebuildFailed && restoreFailures.isEmpty()
                    && stagingFailures.isEmpty() && drift.isEmpty() && skippedGates.isEmpty()
                    ? 0 : 2;
            for (SkippedGates.Skipped gate : skippedGates) {
                err.println("warn: " + gate.repo() + " declared " + gate.compat()
                        + " but its gate did not run — " + gate.detail());
            }
            // Worse wins: a squash refusal or a failed downstream re-verify inside the interactive
            // walk must not be masked by an otherwise-clean base review, or vice versa.
            return Math.max(baseExit, interactiveExit);
        } catch (RuntimeException | IOException e) {
            err.println("error: " + e.getMessage());
            return 4;
        }
    }

    /** Task 4 brief section 3's Gate-2 instruction — reuse {@link ReviewReport#decisionsSummaryLine}
     *  rather than composing a second per-repo summary that can drift out of sync with the report
     *  itself. The spec's Jira sources are re-read from {@code <runDir>/spec.md} (written once at
     *  {@code sdd implement} time, see {@code RunStore.create}) rather than threaded through
     *  {@link RunContext}, which carries the plan/state/config a review needs but not the spec — a
     *  no-op (no file read, no config load) when there are no Jira source keys, same as Gate 1.
     *
     *  <p>Reading/parsing {@code spec.md} is wrapped in its own try/catch, separate from
     *  {@link JiraWriteBack#post}'s own internal one, and fails SILENTLY (unlike that one) rather
     *  than warning: {@code spec.md} is the text an already-approved plan was built from (written
     *  once at {@code sdd implement} time — see {@code RunStore.create} — from a spec {@code sdd
     *  plan approve} already parsed and validated), so in real use this can never fail; treating an
     *  unreadable/unparseable {@code spec.md} the same as "no Jira sources found" rather than as a
     *  reportable failure avoids inventing a warning for a state that only a test fixture (an
     *  empty/placeholder spec text passed to {@code RunStore.create} where the sources are
     *  irrelevant to what is being tested) actually produces. This is called after {@code
     *  report.md} is already on disk either way, so nothing here may propagate and turn an
     *  otherwise-successful review into exit 4. */
    private void commentOnJiraSources(RunContext run, PrintWriter out, PrintWriter err) {
        List<String> jiraKeys;
        try {
            String specText = Files.readString(run.runDir().resolve("spec.md"));
            NormalizedSpec parsedSpec = SpecParser.parse(specText);
            jiraKeys = SourceBullet.jiraIssueKeys(parsedSpec.sources());
        } catch (RuntimeException | IOException e) {
            return;
        }
        if (jiraKeys.isEmpty()) {
            return;
        }
        Map<String, DecisionRecord> decisions = run.store().readDecisions(run.runDir());
        String body = "sdd: review report for `" + run.plan().specId() + "`" + System.lineSeparator()
                + ReviewReport.decisionsSummaryLine(run.plan(), decisions);
        JiraWriteBack.post(workspace, jiraKeys, noComment, body, out, err);
    }
}
