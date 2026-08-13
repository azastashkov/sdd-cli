package sdd.cli.review;

import sdd.agent.tool.GradleTool;
import sdd.cli.implement.MavenLocalInit;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.Propagation;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.cli.implement.Scheduler;
import sdd.core.config.SddConfig;
import sdd.index.gradle.GradleExtractor;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gate-2 estate rebuild pass (design line 66-67): check every SUCCEEDED repo in scope out to its
 * checkpoint branch, rebuild+verify it, and — while every repo in scope is simultaneously sitting
 * on its checkpoint — re-check actualized contracts against fresh extraction. Every repo checked
 * out is restored to its original branch/commit in a single {@code finally}, even when the
 * rebuild, the checkout, or the contract re-check itself fails.
 */
public final class RebuildPass {
    public record Outcome(Map<String, EstateRebuild.Result> rebuilds, List<String> notLocallyVerified,
                          List<String> restoreFailures, List<ContractRecheck.Finding> contracts) {
    }

    private RebuildPass() {
    }

    public static Outcome run(Collection<String> repos, PlanModel plan, RunState state,
                              Map<String, Path> paths, SddConfig config, Path runDir, RunStore store,
                              boolean recheckContracts, PrintWriter err) {
        Map<String, RepoRun> byName = new LinkedHashMap<>();
        for (RepoRun run : state.repos()) {
            byName.put(run.repo(), run);
        }

        EstateRebuild rebuild = new EstateRebuild();
        Map<String, EstateRebuild.Result> rebuilds = new LinkedHashMap<>();
        List<String> notLocallyVerified = new ArrayList<>();
        List<String> restoreFailures = new ArrayList<>();
        List<ContractRecheck.Finding> contracts = new ArrayList<>();
        // Original position per repo: a branch name, or "detached:<sha>" when the user had a
        // detached HEAD (restoring by sha keeps the estate exactly where review found it).
        Map<String, String> originalPositions = new LinkedHashMap<>();
        try {
            for (String repo : Scheduler.sequence(plan.order())) {
                if (!repos.contains(repo) || state.stateOf(repo) != RepoState.SUCCEEDED) {
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
            // Every SUCCEEDED repo in scope is now simultaneously sitting on its checkpoint
            // branch — the only point at which contract extraction reads the trees the run
            // actually produced, instead of whatever branch the human happened to be standing on.
            if (recheckContracts) {
                contracts.addAll(ContractRecheck.check(plan, state, paths, store, runDir));
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
        return new Outcome(rebuilds, notLocallyVerified, restoreFailures, contracts);
    }

    /** Mirrors {@code ImplementCommand}'s settingsFor verification-task resolution exactly. */
    static List<String> tasksFor(PlanModel plan, SddConfig config, String repo) {
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
    static List<String> extraArgsFor(PlanModel plan, String repo, Map<String, Path> paths, Path runDir) {
        List<String> extraArgs = new ArrayList<>(Propagation.includeBuildArgs(repo, plan.edges(), paths));
        extraArgs.addAll(Propagation.mavenLocalArgs(plan.edges(), MavenLocalInit.scriptPath(runDir)));
        return extraArgs;
    }

    /** Mirrors {@code ImplementCommand}'s settingsFor javaHome resolution exactly. */
    static Path javaHomeFor(SddConfig config, Path root) {
        return config.jdkHomes().get(GradleExtractor.jdkMajorFor(GradleExtractor.wrapperVersion(root)));
    }
}
