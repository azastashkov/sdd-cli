package sdd.cli;

import org.jdbi.v3.core.Jdbi;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.agent.tool.GradleTool;
import sdd.cli.implement.MavenLocalInit;
import sdd.cli.implement.PlanJsonReader;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.Propagation;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.cli.implement.Scheduler;
import sdd.cli.review.ContractRecheck;
import sdd.cli.review.EstateRebuild;
import sdd.cli.review.ReleaseRunbook;
import sdd.cli.review.ReviewReport;
import sdd.core.config.ConfigLoader;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.index.gradle.GradleExtractor;

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
 */
@Command(name = "review",
        description = "Rebuild the estate against its checkpoints and render the Gate-2 review report",
        exitCodeOnInvalidInput = 4)
public final class ReviewCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Option(names = "--no-rebuild", description = "Skip the estate rebuild verification pass")
    boolean noRebuild;

    @Parameters(index = "0", description = "The approved <spec>.plan.json")
    Path planJsonPath;

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        try {
            String name = planJsonPath.getFileName().toString();
            if (!name.endsWith(".plan.json")) {
                err.println("error: review expects a .plan.json file");
                return 4;
            }
            PlanModel cliPlan = PlanJsonReader.read(Files.readString(planJsonPath));
            String runId = sanitize(cliPlan.specId()) + "-v" + cliPlan.planVersion();
            Path runDir = workspace.resolve(".sdd/runs/" + runId);
            if (!Files.exists(runDir.resolve("state.json"))) {
                err.println("error: no run to review at " + runDir);
                return 4;
            }
            if (!Files.exists(workspace.resolve(".sdd/index.db"))) {
                err.println("error: knowledge base is empty — run sdd index first");
                return 4;
            }

            RunStore store = RunStore.system();
            PlanModel plan = PlanJsonReader.read(Files.readString(runDir.resolve("plan.json")));
            PlanJsonReader.validate(plan);
            RunState state = store.readState(runDir);

            SddConfig config = ConfigLoader.load(workspace);
            try (Database db = Database.open(workspace)) {
                Jdbi jdbi = db.jdbi();
                Map<String, Path> paths = new HashMap<>();
                jdbi.useHandle(h -> h.createQuery("SELECT name, path FROM repo").mapToMap()
                        .forEach(row -> paths.put(String.valueOf(row.get("name")),
                                Path.of(String.valueOf(row.get("path"))))));

                Map<String, RepoRun> byName = new LinkedHashMap<>();
                for (RepoRun run : state.repos()) {
                    byName.put(run.repo(), run);
                }

                Map<String, RunGit.DiffStat> diffStats = new LinkedHashMap<>();
                List<String> diffFailures = new ArrayList<>();
                for (String repo : Scheduler.sequence(plan.order())) {
                    RepoRun run = byName.get(repo);
                    Path root = paths.get(repo);
                    if (run == null || run.state() != RepoState.SUCCEEDED || run.checkpointSha() == null
                            || root == null) {
                        continue;
                    }
                    String baseSha = plan.repo(repo).orElseThrow().baseSha();
                    // An unresolvable checkpoint sha (pruned run branch, gc'd object, stale KB repo
                    // path) must not abort the whole review — it's a per-repo reporting gap, not a
                    // verification failure. Record it and keep going so the report still gets out.
                    try {
                        store.writeReview(runDir, repo + ".diff", RunGit.diff(root, baseSha, run.checkpointSha()));
                        diffStats.put(repo, RunGit.diffStat(root, baseSha, run.checkpointSha()));
                    } catch (RuntimeException e) {
                        diffFailures.add(repo + ": " + e.getMessage());
                    }
                }

                List<ContractRecheck.Finding> contracts =
                        ContractRecheck.check(plan, state, paths, store, runDir);

                Map<String, EstateRebuild.Result> rebuilds = new LinkedHashMap<>();
                List<String> notLocallyVerified = new ArrayList<>();
                List<String> restoreFailures = new ArrayList<>();
                if (!noRebuild) {
                    runRebuild(plan, state, byName, paths, config, runDir, rebuilds, notLocallyVerified,
                            restoreFailures, err);
                }

                String runbook = ReleaseRunbook.render(plan, state);
                String report = ReviewReport.render(runId, plan, state, diffStats, rebuilds,
                        notLocallyVerified, restoreFailures, diffFailures, contracts, runbook, !noRebuild);
                store.writeReview(runDir, "report.md", report);
                Path reportPath = store.reviewDir(runDir).resolve("report.md");
                out.println("review written: " + reportPath);

                boolean allSucceeded = Scheduler.sequence(plan.order()).stream()
                        .allMatch(repo -> state.stateOf(repo) == RepoState.SUCCEEDED);
                boolean anyRebuildFailed = rebuilds.values().stream().anyMatch(r -> !r.ok());
                return allSucceeded && !anyRebuildFailed ? 0 : 2;
            }
        } catch (RuntimeException | java.io.IOException e) {
            err.println("error: " + e.getMessage());
            return 4;
        }
    }

    private void runRebuild(PlanModel plan, RunState state, Map<String, RepoRun> byName,
                            Map<String, Path> paths, SddConfig config, Path runDir,
                            Map<String, EstateRebuild.Result> rebuilds, List<String> notLocallyVerified,
                            List<String> restoreFailures, PrintWriter err) {
        EstateRebuild rebuild = new EstateRebuild();
        // Original position per repo: a branch name, or "detached:<sha>" when the user had a
        // detached HEAD (restoring by sha keeps the estate exactly where review found it).
        Map<String, String> originalPositions = new LinkedHashMap<>();
        try {
            for (String repo : Scheduler.sequence(plan.order())) {
                if (state.stateOf(repo) != RepoState.SUCCEEDED) {
                    continue;
                }
                Path root = paths.get(repo);
                RepoRun run = byName.get(repo);
                if (root == null || run.branch() == null) {
                    continue;
                }
                List<String> tasks = tasksFor(plan, config, repo);
                if (tasks.isEmpty()) {
                    notLocallyVerified.add(repo);
                    continue;
                }
                // A checkout can legitimately fail (uncommitted conflicting changes at review
                // time). Record it as a failed rebuild and keep going — the report must still
                // be produced (ratified (c)/(f)).
                try {
                    String branch = RunGit.currentBranch(root);
                    originalPositions.putIfAbsent(repo,
                            branch.isEmpty() ? "detached:" + RunGit.head(root) : branch);
                    RunGit.checkout(root, run.branch());
                    rebuilds.put(repo, rebuild.verify(root, javaHomeFor(config, root), tasks,
                            extraArgsFor(plan, repo, paths, runDir)));
                } catch (RuntimeException e) {
                    rebuilds.put(repo, new EstateRebuild.Result(false,
                            "checkout failed: " + e.getMessage()));
                }
            }
        } finally {
            // One failed restore must not strand the remaining repos on checkpoint branches.
            for (Map.Entry<String, String> entry : originalPositions.entrySet()) {
                String target = entry.getValue().startsWith("detached:")
                        ? entry.getValue().substring("detached:".length()) : entry.getValue();
                try {
                    RunGit.checkout(paths.get(entry.getKey()), target);
                } catch (RuntimeException e) {
                    restoreFailures.add(entry.getKey() + ": " + e.getMessage());
                    err.println("warn: could not restore " + entry.getKey() + " to " + target
                            + ": " + e.getMessage());
                }
            }
        }
    }

    /** Mirrors {@code ImplementCommand}'s settingsFor verification-task resolution exactly. */
    private static List<String> tasksFor(PlanModel plan, SddConfig config, String repo) {
        List<String> rawVerification = plan.step(repo)
                .map(PlanModel.PlanStep::verification).orElse(List.of());
        List<String> tasks = new ArrayList<>(rawVerification.isEmpty() ? List.of("check") : rawVerification);
        tasks.retainAll(GradleTool.allowedTasks());   // prose verification entries are acceptance-only,
                                                       // not runnable tasks
        if (!rawVerification.isEmpty() && tasks.isEmpty()) {
            tasks = new ArrayList<>(List.of("check"));   // prose-only list still means "verify
                                                          // normally", not "skip"
        }
        tasks.removeAll(config.verificationExclusions().getOrDefault(repo, List.of()));
        return tasks;
    }

    /** Mirrors {@code ImplementCommand}'s settingsFor extraArgs resolution exactly. */
    private static List<String> extraArgsFor(PlanModel plan, String repo, Map<String, Path> paths,
                                             Path runDir) {
        List<String> extraArgs = new ArrayList<>(Propagation.includeBuildArgs(repo, plan.edges(), paths));
        extraArgs.addAll(Propagation.mavenLocalArgs(plan.edges(), MavenLocalInit.scriptPath(runDir)));
        return extraArgs;
    }

    /** Mirrors {@code ImplementCommand}'s settingsFor javaHome resolution exactly. */
    private static Path javaHomeFor(SddConfig config, Path root) {
        return config.jdkHomes().get(GradleExtractor.jdkMajorFor(GradleExtractor.wrapperVersion(root)));
    }

    private static String sanitize(String id) {
        String cleaned = id == null ? "" : id.replaceAll("[^A-Za-z0-9._-]", "-");
        return cleaned.isBlank() ? "run" : cleaned;
    }
}
