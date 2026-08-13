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
import sdd.cli.review.EstateRebuild;
import sdd.cli.review.InteractiveReview;
import sdd.cli.review.RebuildPass;
import sdd.cli.review.RunContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Gate-2 review (design line 66-67): rebuild the estate in topo order against its checkpoints,
 * re-check actualized contracts against fresh extraction, render the release runbook, and write
 * the report + per-repo diffs — all read-only with respect to the run itself (no lock is taken;
 * {@code implement} already released it). Every repo the rebuild checks out is restored to its
 * original branch/commit in a {@code finally}, even when the rebuild or the checkout itself fails.
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
                        run.store(), run.runDir());
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

            out.println("review written: " + run.writeReport(diffs, rebuilds, notLocallyVerified,
                    stagingFailures, restoreFailures, contracts, !noRebuild));

            if (interactive) {
                BufferedReader reader = in != null ? in
                        : new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                InteractiveReview.run(reader, out, err, run);
            }

            boolean allSucceeded = Scheduler.sequence(run.plan().order()).stream()
                    .allMatch(repo -> run.state().stateOf(repo) == RepoState.SUCCEEDED);
            boolean anyRebuildFailed = rebuilds.values().stream().anyMatch(r -> !r.ok());
            // A failed restore leaves a repo stranded off its original branch — the report's own
            // legend calls that a failed checkout, so it must fail the review too. A staging
            // failure is a failed checkout by another name, and worse: it silently invalidates
            // every verdict downstream of it, so it can never be reported as a clean pass.
            return allSucceeded && !anyRebuildFailed && restoreFailures.isEmpty()
                    && stagingFailures.isEmpty() ? 0 : 2;
        } catch (RuntimeException | IOException e) {
            err.println("error: " + e.getMessage());
            return 4;
        }
    }
}
