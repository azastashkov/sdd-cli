package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.cli.implement.PlanJsonReader;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.cli.implement.Scheduler;
import sdd.cli.review.Decision;
import sdd.cli.review.DecisionRecord;
import sdd.cli.review.ReviewReport;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * A read-only view of what a run currently is (design line 21/94): run state and Gate-2 decisions,
 * without re-rendering the whole Gate-2 report. Unlike every other command from this phase, this one
 * carries no estate-safety obligations, because it never moves the working tree at all — it never
 * checks a repo out, never takes or clears the run lock, and never writes anything to the run dir.
 *
 * <p>Everything below (other than the run id itself) is read straight off the run dir's own frozen
 * artifacts — {@code plan.json}, {@code state.json}, {@code decisions.json}, the {@code lock} file —
 * rather than through {@link sdd.cli.review.RunContext#load}: that loader opens the knowledge-base
 * handle and requires {@code sdd index} to have been run, neither of which read-only status needs,
 * and it is built for reviewing one named run rather than scanning every run dir in the workspace.
 */
@Command(name = "status",
        description = "Show a run's state and Gate-2 decisions (read-only)",
        exitCodeOnInvalidInput = 4)
public final class StatusCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Parameters(index = "0", arity = "0..1",
            description = "A specific <spec>.plan.json; default: every run dir in the workspace")
    Path planJsonPath;

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        try {
            List<Path> runDirs;
            if (planJsonPath == null) {
                runDirs = newestFirst(RunDirs.all(workspace));
            } else {
                Path runDir = RunDirs.one(workspace, planJsonPath, "status", err);
                if (runDir == null) {
                    return 4;   // RunDirs.one already explained why
                }
                runDirs = List.of(runDir);
            }
            if (runDirs.isEmpty()) {
                out.println("no runs found");
                return 0;
            }

            RunStore store = RunStore.system();
            boolean first = true;
            for (Path runDir : runDirs) {
                if (!first) {
                    out.println();
                }
                first = false;
                printOne(runDir, store, out, err);
            }
            return 0;
        } catch (RuntimeException | IOException e) {
            err.println("error: " + e.getMessage());
            return 4;
        }
    }

    /** One run's block. A run this command cannot even read is reported and skipped rather than
     *  aborting the whole invocation — one corrupted run dir must not hide every other run's status,
     *  and status never judges (there is no exit-2 case here), so it stays exit 0. */
    private void printOne(Path runDir, RunStore store, PrintWriter out, PrintWriter err) {
        String runId = runDir.getFileName().toString();
        PlanModel plan;
        RunState state;
        Map<String, DecisionRecord> decisions;
        try {
            plan = PlanJsonReader.read(Files.readString(runDir.resolve("plan.json")));
            state = store.readState(runDir);
            // Same try as plan/state, not a separate one: a malformed decisions.json must isolate
            // to this one run dir exactly like a malformed plan.json or state.json does — none of
            // the three may abort the whole scan or claim the outer exit-4 (that's reserved for an
            // explicitly named plan with no run dir, or unusable input).
            decisions = store.readDecisions(runDir);
        } catch (RuntimeException | IOException e) {
            err.println("error: " + runId + ": " + e.getMessage());
            return;
        }
        Map<String, RepoRun> byName = new LinkedHashMap<>();
        for (RepoRun repoRun : state.repos()) {
            byName.put(repoRun.repo(), repoRun);
        }

        String lockStatus = store.isLockHeld(runDir) ? "in progress" : "idle";
        out.println("Run: " + runId + "  (" + lockStatus + ")");
        out.println("Total tokens: " + state.tokensSpent());
        for (String repo : Scheduler.sequence(plan.order())) {
            RepoRun repoRun = byName.get(repo);
            RepoState repoState = repoRun == null ? null : repoRun.state();
            DecisionRecord decisionRecord = decisions.get(repo);
            Decision decision = decisionRecord == null ? Decision.PENDING : decisionRecord.decision();
            String branch = repoRun == null || repoRun.branch() == null ? "-" : repoRun.branch();
            out.println(repo + "  " + (repoState == null ? "UNKNOWN" : repoState) + "  " + decision
                    + "  " + branch);
        }
        out.println(ReviewReport.decisionsSummaryLine(plan, decisions));
    }

    /** Newest run first, by the run dir's own last-modified time — it moves every time something is
     *  written into the run (a new checkpoint, a decision, a report), so "newest" tracks the run most
     *  recently touched, not merely the one most recently created. Ties (identical resolution, or a
     *  filesystem that doesn't track it) fall back to run id, descending, for determinism. */
    private static List<Path> newestFirst(List<Path> runDirs) {
        return runDirs.stream()
                .sorted(Comparator.comparingLong(StatusCommand::mtimeMillis).reversed()
                        .thenComparing(p -> p.getFileName().toString(), Comparator.reverseOrder()))
                .toList();
    }

    private static long mtimeMillis(Path runDir) {
        try {
            return Files.getLastModifiedTime(runDir).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
