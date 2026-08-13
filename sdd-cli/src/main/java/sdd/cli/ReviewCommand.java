package sdd.cli;

import org.jdbi.v3.core.Jdbi;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.cli.implement.PlanJsonReader;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.cli.implement.Scheduler;
import sdd.cli.review.ContractRecheck;
import sdd.cli.review.DecisionCommand;
import sdd.cli.review.EstateRebuild;
import sdd.cli.review.RebuildPass;
import sdd.cli.review.ReleaseRunbook;
import sdd.cli.review.ReviewReport;
import sdd.core.config.ConfigLoader;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * half of the same gate; they share this command's run loading, diff collection and report writing
 * via the static helpers below so a decision leaves behind the same artifacts a plain review does.
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

    /** Everything a review path needs off disk: the frozen plan, the run's state, the estate's repo
     *  paths and the config. The knowledge-base handle is opened and closed here — nothing
     *  downstream of this needs it, and holding it open across a rebuild would pin the db for
     *  minutes. */
    public record LoadedRun(String runId, Path runDir, RunStore store, PlanModel plan, RunState state,
                            SddConfig config, Map<String, Path> paths) {
    }

    /** Per-repo diffs written to the review dir, plus the repos whose diff could not be produced. */
    public record Diffs(Map<String, RunGit.DiffStat> stats, List<String> failures) {
    }

    /** Loads the run named by {@code planJsonPath}, or returns null having already printed the
     *  reason to {@code err} — every caller turns that into exit 4. */
    public static LoadedRun load(Path workspace, Path planJsonPath, PrintWriter err) throws IOException {
        String name = planJsonPath.getFileName().toString();
        if (!name.endsWith(".plan.json")) {
            err.println("error: review expects a .plan.json file");
            return null;
        }
        PlanModel cliPlan = PlanJsonReader.read(Files.readString(planJsonPath));
        String runId = sanitize(cliPlan.specId()) + "-v" + cliPlan.planVersion();
        Path runDir = workspace.resolve(".sdd/runs/" + runId);
        if (!Files.exists(runDir.resolve("state.json"))) {
            err.println("error: no run to review at " + runDir);
            return null;
        }
        if (!Files.exists(workspace.resolve(".sdd/index.db"))) {
            err.println("error: knowledge base is empty — run sdd index first");
            return null;
        }

        RunStore store = RunStore.system();
        // The frozen copy, not the caller's file: the plan on disk may have been re-approved since.
        PlanModel plan = PlanJsonReader.read(Files.readString(runDir.resolve("plan.json")));
        PlanJsonReader.validate(plan);
        RunState state = store.readState(runDir);
        SddConfig config = ConfigLoader.load(workspace);

        Map<String, Path> paths = new HashMap<>();
        try (Database db = Database.open(workspace)) {
            Jdbi jdbi = db.jdbi();
            jdbi.useHandle(h -> h.createQuery("SELECT name, path FROM repo").mapToMap()
                    .forEach(row -> paths.put(String.valueOf(row.get("name")),
                            Path.of(String.valueOf(row.get("path"))))));
        }
        return new LoadedRun(runId, runDir, store, plan, state, config, paths);
    }

    /** Writes {@code <repo>.diff} for every SUCCEEDED repo with a resolvable checkpoint. */
    public static Diffs collectDiffs(LoadedRun run) {
        Map<String, RepoRun> byName = byName(run.state());
        Map<String, RunGit.DiffStat> diffStats = new LinkedHashMap<>();
        List<String> diffFailures = new ArrayList<>();
        for (String repo : Scheduler.sequence(run.plan().order())) {
            RepoRun repoRun = byName.get(repo);
            Path root = run.paths().get(repo);
            if (repoRun == null || repoRun.state() != RepoState.SUCCEEDED
                    || repoRun.checkpointSha() == null || root == null) {
                continue;
            }
            String baseSha = run.plan().repo(repo).orElseThrow().baseSha();
            // An unresolvable checkpoint sha (pruned run branch, gc'd object, stale KB repo
            // path) must not abort the whole review — it's a per-repo reporting gap, not a
            // verification failure. Record it and keep going so the report still gets out.
            try {
                run.store().writeReview(run.runDir(), repo + ".diff",
                        RunGit.diff(root, baseSha, repoRun.checkpointSha()));
                diffStats.put(repo, RunGit.diffStat(root, baseSha, repoRun.checkpointSha()));
            } catch (RuntimeException e) {
                diffFailures.add(repo + ": " + e.getMessage());
            }
        }
        return new Diffs(diffStats, diffFailures);
    }

    /** Renders and writes {@code report.md}, returning its path. Decisions re-run this so the
     *  artifact a human hands to a colleague reflects the run as it stands now, not a pre-decision
     *  snapshot. */
    public static Path writeReport(LoadedRun run, Diffs diffs, Map<String, EstateRebuild.Result> rebuilds,
                                   List<String> notLocallyVerified, List<String> restoreFailures,
                                   List<ContractRecheck.Finding> contracts, boolean rebuilt) {
        String runbook = ReleaseRunbook.render(run.plan(), run.state());
        String report = ReviewReport.render(run.runId(), run.plan(), run.state(), diffs.stats(), rebuilds,
                notLocallyVerified, restoreFailures, diffs.failures(), contracts, runbook, rebuilt);
        run.store().writeReview(run.runDir(), "report.md", report);
        return run.store().reviewDir(run.runDir()).resolve("report.md");
    }

    public static Map<String, RepoRun> byName(RunState state) {
        Map<String, RepoRun> byName = new LinkedHashMap<>();
        for (RepoRun repoRun : state.repos()) {
            byName.put(repoRun.repo(), repoRun);
        }
        return byName;
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
            LoadedRun run = load(workspace, planJsonPath, err);
            if (run == null) {
                return 4;
            }

            Diffs diffs = collectDiffs(run);

            Map<String, EstateRebuild.Result> rebuilds;
            List<String> notLocallyVerified;
            List<String> restoreFailures;
            List<ContractRecheck.Finding> contracts;
            if (noRebuild) {
                // Nothing is checked out, so this reads whatever branch the human happens to
                // be standing on — the caller (report) still needs the findings either way.
                rebuilds = Map.of();
                notLocallyVerified = List.of();
                restoreFailures = List.of();
                contracts = ContractRecheck.check(run.plan(), run.state(), run.paths(),
                        run.store(), run.runDir());
            } else {
                RebuildPass.Outcome outcome = RebuildPass.run(Scheduler.sequence(run.plan().order()),
                        run.plan(), run.state(), run.paths(), run.config(), run.runDir(), run.store(),
                        true, err);
                rebuilds = outcome.rebuilds();
                notLocallyVerified = outcome.notLocallyVerified();
                restoreFailures = outcome.restoreFailures();
                contracts = outcome.contracts();
            }

            out.println("review written: " + writeReport(run, diffs, rebuilds, notLocallyVerified,
                    restoreFailures, contracts, !noRebuild));

            boolean allSucceeded = Scheduler.sequence(run.plan().order()).stream()
                    .allMatch(repo -> run.state().stateOf(repo) == RepoState.SUCCEEDED);
            boolean anyRebuildFailed = rebuilds.values().stream().anyMatch(r -> !r.ok());
            // A failed restore leaves a repo stranded off its original branch — the report's own
            // legend calls that a failed checkout, so it must fail the review too.
            return allSucceeded && !anyRebuildFailed && restoreFailures.isEmpty() ? 0 : 2;
        } catch (RuntimeException | IOException e) {
            err.println("error: " + e.getMessage());
            return 4;
        }
    }

    private static String sanitize(String id) {
        String cleaned = id == null ? "" : id.replaceAll("[^A-Za-z0-9._-]", "-");
        return cleaned.isBlank() ? "run" : cleaned;
    }
}
