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
import sdd.cli.implement.NpmOverlay;
import sdd.cli.implement.VerificationTasks;
import sdd.core.progress.Progress;
import sdd.core.toolchain.Mechanism;
import sdd.index.extract.BuildModel;
import sdd.index.npm.NpmExtractor;

import java.io.IOException;
import sdd.core.config.SddConfig;
import sdd.core.toolchain.Toolchain;
import sdd.index.gradle.GradleExtractor;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gate-2 estate rebuild pass (design line 66-67): check every SUCCEEDED repo out to its checkpoint
 * branch, rebuild+verify the ones asked for, and — while the whole estate is simultaneously sitting
 * on its checkpoints — re-check actualized contracts against fresh extraction. Every repo checked
 * out is restored to its original branch/commit in a single {@code finally}, even when the
 * rebuild, the checkout, or the contract re-check itself fails.
 *
 * <p><b>{@code repos} selects what to REBUILD, not what to check out.</b> Staging is always
 * estate-wide, because a consumer's verification composes its providers through
 * {@code --include-build}, which points at the provider's live working tree
 * ({@link #extraArgsFor}) — not at any recorded sha. Staging only the subset would therefore build
 * it against whatever pre-run code its providers happen to be sitting on and report a green verdict
 * that means nothing. The two sets coincide for a full {@code sdd review}; they diverge for
 * {@code sdd review redo}, which re-verifies one downstream subtree.
 */
public final class RebuildPass {
    /** {@code stagingFailures} is the repos that could not be put on their checkpoint at all. It is
     *  separate from a failed rebuild because it invalidates OTHER repos' verdicts, not its own:
     *  everything downstream of an unstaged repo was verified against pre-run code. Callers must
     *  surface it and must not report a pass while it is non-empty. */
    public record Outcome(Map<String, EstateRebuild.Result> rebuilds, List<String> notLocallyVerified,
                          List<String> stagingFailures, List<String> restoreFailures,
                          List<ContractRecheck.Finding> contracts) {
    }

    private RebuildPass() {
    }

    /** Every pre-existing caller ({@code RebuildPassTest}, {@code ReviewCommand} before this task)
     *  keeps compiling against {@link Progress#noOp()} — the same trailing-parameter-overload
     *  convention {@code Orchestrator}'s {@code nodeHome}/{@code progress} constructors use. */
    public static Outcome run(Collection<String> repos, PlanModel plan, RunState state,
                              Map<String, Path> paths, SddConfig config, Path runDir, RunStore store,
                              boolean recheckContracts, PrintWriter err) {
        return run(repos, plan, state, paths, config, runDir, store, recheckContracts, err,
                Progress.noOp());
    }

    public static Outcome run(Collection<String> repos, PlanModel plan, RunState state,
                              Map<String, Path> paths, SddConfig config, Path runDir, RunStore store,
                              boolean recheckContracts, PrintWriter err, Progress progress) {
        Progress safeProgress = progress != null ? progress : Progress.noOp();
        Map<String, RepoRun> byName = new LinkedHashMap<>();
        for (RepoRun run : state.repos()) {
            byName.put(run.repo(), run);
        }

        EstateRebuild rebuild = new EstateRebuild();
        Map<String, EstateRebuild.Result> rebuilds = new LinkedHashMap<>();
        List<String> notLocallyVerified = new ArrayList<>();
        List<String> stagingFailures = new ArrayList<>();
        List<String> restoreFailures = new ArrayList<>();
        List<ContractRecheck.Finding> contracts = new ArrayList<>();
        // Original position per repo: a branch name, or "detached:<sha>" when the user had a
        // detached HEAD (restoring by sha keeps the estate exactly where review found it).
        Map<String, String> originalPositions = new LinkedHashMap<>();
        // One pack per provider, reused by every consumer of it in this pass.
        Map<String, List<PackedPackage>> packedProviders = new LinkedHashMap<>();
        try {
            // Topo order matters twice over: a provider is staged before the consumer that
            // composes its working tree, and rebuilt before it too.
            for (String repo : Scheduler.sequence(plan.order())) {
                // A repo with no checkpoint to stage is NOT a repo this pass may quietly skip.
                // Propagation.includeBuildArgs composes every INCLUDE_BUILD provider's path
                // unconditionally, whatever its run state, so its consumers are about to be
                // verified against its PRE-RUN working tree — the identical finding a failed
                // checkout produces below, and recorded the same way so unstagedRepos → voidedBy →
                // the exit code pick it up. The "<repo>: " prefix is load-bearing: that is how
                // ReviewReport and InteractiveReview.replaceForRepos parse these lines.
                if (state.stateOf(repo) != RepoState.SUCCEEDED) {
                    unstageable(repo, "not SUCCEEDED in this run, composed from its working tree",
                            stagingFailures, err);
                    continue;
                }
                Path root = paths.get(repo);
                RepoRun run = byName.get(repo);
                if (root == null || run == null || run.branch() == null) {
                    unstageable(repo, root == null ? "no repo path on record" : "no run branch on record",
                            stagingFailures, err);
                    continue;
                }
                // A checkout can legitimately fail (uncommitted conflicting changes at review
                // time). Record it and keep going — the report must still be produced
                // (ratified (c)/(f)).
                try {
                    String branch = RunGit.currentBranch(root);
                    originalPositions.putIfAbsent(repo,
                            branch.isEmpty() ? "detached:" + RunGit.head(root) : branch);
                    RunGit.checkout(root, run.branch());
                } catch (RuntimeException e) {
                    // Recorded whether or not this repo was going to be verified: it is now sitting
                    // on pre-run code that every downstream verdict composes, so the finding has to
                    // reach the report and the exit code, not just this warn line. Inside the
                    // subset it is ALSO its own failed verdict.
                    stagingFailures.add(repo + ": " + e.getMessage());
                    // Ruling P4 (binding, scoped by the task to exactly this line): this checkout
                    // failure is the one warn: in this pass that fires mid-loop, so it is routed
                    // through Progress.note rather than straight to err — unlike unstageable()'s
                    // warn (repos skipped before any checkout is attempted) and the restore-failure
                    // warn in the finally below (after every repo's rebuild is already done), which
                    // stay on err directly, unchanged. This pass does not itself call phase()/
                    // start() on progress (out of scope for this task — see the task report), so in
                    // the CURRENT wiring none of the three actually paints over a live frame yet;
                    // this call site is where that plumbing lands regardless, so a future caller
                    // that does drive start()/finish() around this loop gets the collision handling
                    // for free. note()'s wording and stream are identical to the direct err.println
                    // this replaces under PlainProgress (its own javadoc: "passed straight through
                    // unchanged"); under a truly no-op Progress (--quiet, SDD_PROGRESS=off) this one
                    // heads-up goes quiet along with everything else progress renders — the finding
                    // itself is not lost, since stagingFailures (added just above) still drives the
                    // durable report.md and this review's exit code either way.
                    safeProgress.note("warn: could not stage " + repo + " at its checkpoint: "
                            + e.getMessage() + " — verdicts for its consumers do not reflect "
                            + "this run's upstream code");
                    if (repos.contains(repo)) {
                        rebuilds.put(repo, new EstateRebuild.Result(false,
                                "checkout failed: " + e.getMessage()));
                    }
                    continue;
                }
                if (!repos.contains(repo)) {
                    continue;   // staged as an upstream tree only; not asked to be verified
                }
                Toolchain toolchain = Toolchain.detect(root);
                List<String> tasks = tasksFor(plan, config, repo, root);
                if (tasks.isEmpty()) {
                    notLocallyVerified.add(repo);
                    continue;
                }
                // Gradle substitution rides in as flags on the command; npm substitution is
                // filesystem state that has to be put in place and taken away again. Without it a
                // consumer is rebuilt against its provider's PUBLISHED version while the report
                // says the estate was verified as a whole — which is the one thing this pass exists
                // to be able to say.
                List<NpmOverlay.Applied> overlays = toolchain == Toolchain.NPM
                        ? stageNpmProviders(repo, plan, paths, config, runDir, packedProviders,
                                stagingFailures)
                        : List.of();
                try {
                    rebuilds.put(repo, rebuild.verify(root, toolchain,
                            toolchain == Toolchain.NPM ? null : javaHomeFor(config, root),
                            config.nodeHome(), tasks,
                            toolchain == Toolchain.NPM ? List.of()
                                    : extraArgsFor(plan, repo, paths, runDir)));
                } catch (RuntimeException e) {
                    // Same rule as a failed checkout: one repo's blow-up is a verdict, not the end
                    // of the pass — the remaining repos still get verified and the report still
                    // gets written.
                    rebuilds.put(repo, new EstateRebuild.Result(false,
                            "verification threw: " + e.getMessage()));
                } finally {
                    restoreNpmProviders(overlays, config, restoreFailures);
                }
            }
            // Every SUCCEEDED repo in scope is now simultaneously sitting on its checkpoint
            // branch — the only point at which contract extraction reads the trees the run
            // actually produced, instead of whatever branch the human happened to be standing on.
            if (recheckContracts) {
                contracts.addAll(ContractRecheck.check(plan, state, paths, store, runDir, config.nodeHome()));
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
        return new Outcome(rebuilds, notLocallyVerified, stagingFailures, restoreFailures, contracts);
    }

    /** Records a repo that could not be staged because there was no checkpoint to stage it at — as
     *  opposed to {@link #run}'s inline catch, which handles a checkout that was attempted and
     *  failed. Both are staging failures: the consequence for every downstream verdict is identical. */
    private static void unstageable(String repo, String reason, List<String> stagingFailures,
                                    PrintWriter err) {
        stagingFailures.add(repo + ": " + reason);
        err.println("warn: could not stage " + repo + " at a checkpoint: " + reason
                + " — verdicts for its consumers do not reflect this run's upstream code");
    }

    /** Mirrors {@code ImplementCommand}'s settingsFor verification-task resolution exactly. */
    /**
     * Delegates to the one resolver {@code sdd implement} also uses. This method used to carry its
     * own copy of the logic — its javadoc admitted as much — which is precisely the arrangement
     * where a fix lands on one side and Gate 2 verifies something different from what the agent was
     * held to.
     */
    static List<String> tasksFor(PlanModel plan, SddConfig config, String repo, Path root) {
        List<String> rawVerification = plan.step(repo)
                .map(PlanModel.PlanStep::verification).orElse(List.of());
        return VerificationTasks.resolve(Toolchain.detect(root), root, rawVerification,
                config.verificationExclusions().getOrDefault(repo, List.of()));
    }

    /** Mirrors {@code ImplementCommand}'s settingsFor extraArgs resolution exactly. */
    /** A provider package packed from its checkpointed tree, ready to overlay. */
    private record PackedPackage(String packageName, Path tarball) {
    }

    /**
     * Packs every NPM_OVERLAY provider of {@code repo} from the tree it is currently staged at, and
     * overlays the result into the consumer.
     *
     * <p>Packing here rather than reusing the implement run's tarballs is deliberate: this pass
     * verifies the estate as it stands at its CHECKPOINTS, which a {@code review redo} or a later
     * approval can move. A tarball from earlier in the run would verify a tree that is no longer
     * the one under review.
     *
     * <p>The package names come from each provider's staged {@code package.json} rather than the
     * knowledge base, for the same reason — the tree being verified is the authority on what it
     * publishes.
     *
     * <p>A substitution that cannot be made is recorded in {@code stagingFailures}, which already
     * means "verdicts that depend on this are not trustworthy" and which callers must not report a
     * pass alongside. Verification still runs, so the report has data; the exit code is already
     * committed by then.
     */
    private static List<NpmOverlay.Applied> stageNpmProviders(
            String repo, PlanModel plan, Map<String, Path> paths, SddConfig config, Path runDir,
            Map<String, List<PackedPackage>> packedProviders, List<String> stagingFailures) {
        List<PlanModel.PlanEdge> overlayEdges = plan.edges().stream()
                .filter(e -> e.fromRepo().equals(repo))
                .filter(e -> Mechanism.of(e.mechanism()) == Mechanism.NPM_OVERLAY)
                .toList();
        if (overlayEdges.isEmpty()) {
            return List.of();
        }
        Path consumerRoot = paths.get(repo);
        Path store = runDir.resolve("review-npm-store");
        NpmOverlay overlay = new NpmOverlay(config.nodeHome());
        List<NpmOverlay.Applied> applied = new ArrayList<>();
        for (PlanModel.PlanEdge edge : overlayEdges) {
            Path providerRoot = paths.get(edge.toRepo());
            if (providerRoot == null) {
                stagingFailures.add(repo + ": provider " + edge.toRepo() + " has no checkout, so its"
                        + " change could not be staged into this rebuild");
                continue;
            }
            List<PackedPackage> packed = packedProviders.computeIfAbsent(edge.toRepo(),
                    provider -> packProvider(provider, providerRoot, overlay, store, stagingFailures));
            for (PackedPackage one : packed) {
                try {
                    applied.add(overlay.apply(consumerRoot, one.packageName(), one.tarball(),
                            store.resolveSibling("review-overlay-backup")));
                } catch (IOException e) {
                    stagingFailures.add(repo + ": could not overlay " + one.packageName() + " from "
                            + edge.toRepo() + " (" + e.getMessage() + "), so this rebuild used its"
                            + " published version");
                }
            }
        }
        return applied;
    }

    private static List<PackedPackage> packProvider(String provider, Path providerRoot,
                                                    NpmOverlay overlay, Path store,
                                                    List<String> stagingFailures) {
        List<PackedPackage> packed = new ArrayList<>();
        BuildModel.Extract extract;
        try {
            extract = new NpmExtractor().extract(providerRoot);
        } catch (RuntimeException e) {
            stagingFailures.add(provider + ": could not read its packages to stage them ("
                    + e.getMessage() + ")");
            return packed;
        }
        for (BuildModel.Module module : extract.modules()) {
            if (!"LIBRARY".equals(module.kind()) || module.name() == null) {
                continue;
            }
            // The version in the staged tree, which is what a release from this checkpoint ships.
            String version = module.version() == null ? "0.0.0-sdd-review" : module.version();
            NpmOverlay.PackResult result = overlay.pack(module.moduleDir(), version, store);
            if (!result.ok()) {
                stagingFailures.add(provider + ": could not pack " + module.name() + " ("
                        + result.log().lines().findFirst().orElse("") + "), so consumers were"
                        + " rebuilt against its published version");
                continue;
            }
            packed.add(new PackedPackage(module.name(), result.tarball()));
        }
        return packed;
    }

    private static void restoreNpmProviders(List<NpmOverlay.Applied> applied, SddConfig config,
                                            List<String> restoreFailures) {
        NpmOverlay overlay = new NpmOverlay(config.nodeHome());
        for (NpmOverlay.Applied one : applied) {
            try {
                overlay.restore(one);
            } catch (RuntimeException e) {
                // The estate is left altered, which is exactly what restoreFailures is for.
                restoreFailures.add("could not restore " + one.installed() + ": " + e.getMessage());
            }
        }
    }

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
