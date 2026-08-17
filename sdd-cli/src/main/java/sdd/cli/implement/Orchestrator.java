package sdd.cli.implement;

import sdd.agent.run.InfraClassifier;
import sdd.agent.run.RepoStep;
import sdd.agent.run.RepoStepRunner;
import sdd.agent.run.RunnerSettings;
import sdd.agent.run.StepOutcome;
import sdd.agent.run.StepResult;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ModelException;
import sdd.core.progress.Progress;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Drives an N-tier escalation ladder per repo across the plan's execution order (design Component 3,
 * generalized by the 2026-08-13 amendment): branch off base, run the 4B agent with the ladder's first
 * tier, and on a failed attempt escalate to the next tier from a hard-reset tree (as long as the run
 * token budget allows and a next tier exists) — defaulting to the original two-tier coder/planner
 * shape — checkpoint-commit on success, cascade failures to downstream skips, and pause the run
 * (exit 3) on endpoint outage, infrastructure failure, or run-budget exhaustion.
 * Repos run parallel-within-layer on virtual threads (M8 staleness recovery is 4C-3c); all shared
 * state is guarded by a single lock, and the first pause wins.
 * MAVEN_LOCAL propagation (4C-2b): bump edits re-applied after every branch reset; providers
 * publish to the run-scoped m2 after their checkpoint commit.
 * Contract actualization + japicmp gate (design line 62): baseline jars from the pinned base tree,
 * candidates after green, breaking drift fails the provider before consumers start.
 */
public final class Orchestrator {
    /** Escalation triggers. BLOCKED asked for a human; INFRA pauses; SUCCESS needs nothing. */
    private static final Set<StepResult> ESCALATE = Set.of(StepResult.VERIFY_FAILED,
            StepResult.EXHAUSTED, StepResult.BUDGET, StepResult.MALFORMED, StepResult.WEDGED);

    /** One rung of the escalation ladder: the model to call and the name it's referred to by in
     *  events/details (design line 59's "escalation to DeepSeek", generalized). */
    public record ModelTier(ChatModel model, String modelName) {
    }

    private final RepoStepRunner runner;
    private final List<ModelTier> ladder;
    private final Function<String, RunnerSettings> settingsFor;
    private final RunStore store;
    private final long runTokenBudget;
    private final Map<String, RepoPropagation> propagation;
    private final MavenLocalPublisher publisher;
    private final JarBuilder jarBuilder;
    /** Where node lives, for the npm substitution; null takes it from PATH. */
    private final java.nio.file.Path nodeHome;
    /** Never null (design's P5: a caller must never have to guard a {@link Progress} call) —
     *  defaults to {@link Progress#noOp()} via the two narrower constructors below, the same
     *  trailing-parameter-overload convention {@code nodeHome} already established just above. */
    private final Progress progress;

    public record RunResult(int exitCode, RunState state) {
    }

    public Orchestrator(RepoStepRunner runner, List<ModelTier> ladder,
                        Function<String, RunnerSettings> settingsFor, RunStore store, long runTokenBudget,
                        Map<String, RepoPropagation> propagation, MavenLocalPublisher publisher,
                        JarBuilder jarBuilder) {
        this(runner, ladder, settingsFor, store, runTokenBudget, propagation, publisher, jarBuilder, null);
    }

    public Orchestrator(RepoStepRunner runner, List<ModelTier> ladder,
                        Function<String, RunnerSettings> settingsFor, RunStore store, long runTokenBudget,
                        Map<String, RepoPropagation> propagation, MavenLocalPublisher publisher,
                        JarBuilder jarBuilder, java.nio.file.Path nodeHome) {
        this(runner, ladder, settingsFor, store, runTokenBudget, propagation, publisher, jarBuilder,
                nodeHome, Progress.noOp());
    }

    /**
     * The full constructor — every narrower one above delegates here with {@link Progress#noOp()},
     * exactly the convention {@code nodeHome} already used one parameter over, so every existing
     * {@code OrchestratorTest} call site (8-arg or 9-arg) keeps compiling without ever mentioning
     * progress (design doc, "implement" row: "Add via a constructor overload delegating to the
     * existing one").
     */
    public Orchestrator(RepoStepRunner runner, List<ModelTier> ladder,
                        Function<String, RunnerSettings> settingsFor, RunStore store, long runTokenBudget,
                        Map<String, RepoPropagation> propagation, MavenLocalPublisher publisher,
                        JarBuilder jarBuilder, java.nio.file.Path nodeHome, Progress progress) {
        if (ladder.isEmpty()) {
            throw new IllegalArgumentException("escalation ladder must not be empty");
        }
        this.runner = runner;
        this.ladder = List.copyOf(ladder);
        this.settingsFor = settingsFor;
        this.store = store;
        this.runTokenBudget = runTokenBudget;
        this.propagation = Map.copyOf(propagation);
        this.publisher = publisher;
        this.jarBuilder = jarBuilder;
        this.nodeHome = nodeHome;
        this.progress = progress != null ? progress : Progress.noOp();
    }

    public RunResult run(Path runDir, PlanModel plan, Map<String, RepoStep> steps) {
        String runId = runDir.getFileName().toString();
        // Only repos with a runnable step are tracked. Step-less affected repos (bom / bump-only sites,
        // whose version-bump edits are 4C-2b) would otherwise orphan at PENDING and force a spurious exit 2.
        List<String> runnable = Scheduler.sequence(plan.order()).stream()
                .filter(steps::containsKey).toList();
        return run(runDir, plan, steps, new RunState(runId, runnable));
    }

    private final Object lock = new Object();

    /** Resume entry: repos already SUCCEEDED or FAILED in the passed state are not re-run. */
    public RunResult run(Path runDir, PlanModel plan, Map<String, RepoStep> steps, RunState state) {
        String runId = runDir.getFileName().toString();
        // One estate-wide declaration up front, same as sdd index's per-repo loop — so the parallel
        // line can show "N/total done" from the first frame instead of just a bare count. On a
        // --resume, state.repos() is the plan's full runnable set as recorded at the ORIGINAL run's
        // creation, so total is stable across resumes; repos already SUCCEEDED/FAILED before this
        // process started are never start()/finish()d again (runRepo's own early return, below,
        // skips transitionLocked entirely for them), so a resume's "done" count under-reports work
        // finished in an earlier process — a real but minor cosmetic gap, not a correctness one
        // (state.json itself, which sdd status reads, is unaffected), left as-is rather than adding
        // a second, transitionLocked-independent way to seed "done".
        progress.phase("implement", state.repos().size());
        AtomicReference<RuntimeException> fatal = new AtomicReference<>();
        try {
            synchronized (lock) {
                store.writeState(runDir, state);
            }
            for (List<List<String>> layer : Scheduler.levels(plan.order(), plan.edges())) {
                synchronized (lock) {
                    if (state.pausedReason() != null) {
                        break;
                    }
                }
                if (fatal.get() != null) {
                    break;
                }
                List<List<String>> units = layer.stream()
                        .map(unit -> unit.stream().filter(steps::containsKey).toList())
                        .filter(unit -> !unit.isEmpty())
                        .toList();
                if (units.isEmpty()) {
                    continue;
                }
                try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (List<String> unit : units) {
                        pool.submit(() -> {
                            try {
                                for (String repo : unit) {   // cycle units stay internally sequential
                                    if (fatal.get() != null
                                            || !runRepo(runDir, runId, plan, steps, state, repo)) {
                                        break;
                                    }
                                }
                            } catch (RuntimeException e) {
                                if (!fatal.compareAndSet(null, e)) {   // 4xx config errors et al: stop + rethrow
                                    fatal.get().addSuppressed(e);   // keep secondary failures visible, don't drop them
                                }
                            }
                        });
                    }
                }   // ExecutorService.close() waits for every submitted unit to finish
            }
        } finally {
            store.releaseLock(runDir);
        }
        if (fatal.get() != null) {
            throw fatal.get();
        }
        boolean paused = state.pausedReason() != null;
        boolean allSucceeded = state.repos().stream().allMatch(r -> r.state() == RepoState.SUCCEEDED);
        return new RunResult(paused ? 3 : allSucceeded ? 0 : 2, state);
    }

    /** One repo, walking the escalation ladder. Returns false when the walk must stop (a pause landed). */
    private boolean runRepo(Path runDir, String runId, PlanModel plan, Map<String, RepoStep> steps,
                            RunState state, String repo) {
        Runnable entryPaint;
        boolean skippedUpstream;
        synchronized (lock) {
            if (state.pausedReason() != null) {
                return false;
            }
            RepoState already = state.stateOf(repo);
            if (already == RepoState.SUCCEEDED || already == RepoState.FAILED) {
                return true;
            }
            if (state.tokensSpent() >= runTokenBudget) {
                pauseLocked(runDir, state,
                        "run token budget exhausted (" + state.tokensSpent() + " tokens)");
                return false;
            }
            skippedUpstream = Scheduler.blockedByUpstream(repo, plan.edges(), state);
            entryPaint = skippedUpstream
                    ? transitionLocked(runDir, state, repo, RepoState.SKIPPED_UPSTREAM_FAILED, null,
                            null, "upstream failed")
                    : transitionLocked(runDir, state, repo, RepoState.IN_PROGRESS, null, null, "");
        }
        entryPaint.run();
        if (skippedUpstream) {
            return true;
        }
        RepoStep step = steps.get(repo);
        String branch = "sdd/" + runId + "/" + slug(repo);
        String base = plan.repo(repo).map(PlanModel.PlanRepo::baseSha).orElse("");
        List<String> events = new ArrayList<>();
        List<String> transcript = new ArrayList<>();
        List<String> edits = new ArrayList<>();
        List<StepOutcome> history = new ArrayList<>();
        StepOutcome outcome;
        int usedTier = 0;
        Path baselineDir = runDir.resolve("contracts").resolve(slug(repo) + "-baseline");
        Path dtsBaselineDir = runDir.resolve("contracts").resolve(slug(repo) + "-dts-baseline");
        boolean compatGate = false;
        DtsBuilder.Result typeCompatBaseline = null;
        // What actually happened to each declared gate. Seeded SKIPPED the moment a gate is
        // DECLARED, so the only way a repo ends up with no record is by declaring nothing —
        // every path that abandons a gate part-way therefore leaves the truth behind by default,
        // rather than by remembering to write it.
        Map<String, CompatGate> gates = new LinkedHashMap<>();
        List<NpmOverlay.Applied> overlaysApplied = List.of();
        try {
            RunGit.startBranch(step.repoRoot(), branch, base);
            applyBumps(repo, step, events);
            // Overlays live only for as long as this repo is being built. They are filesystem
            // state rather than a flag, so unlike Gradle's substitution they have to be undone —
            // the finally below does that on every path out of this block, including a model
            // failure or an escalation that never reaches SUCCESS.
            overlaysApplied = applyOverlays(repo, step, events);
            if (needsCompatGate(plan, repo)) {
                gates.put(BINARY_COMPATIBLE, new CompatGate(BINARY_COMPATIBLE,
                        CompatGate.Outcome.SKIPPED, "the gate did not reach a verdict"));
                JarBuilder.Result baseline = buildJars(step, repo, baselineDir);
                if (baseline.ok() && !baseline.jars().isEmpty()) {
                    compatGate = true;
                } else {
                    String detail = "baseline build failed — " + summarize(baseline.log());
                    events.add("japicmp skipped: " + detail);
                    gates.put(BINARY_COMPATIBLE, new CompatGate(BINARY_COMPATIBLE,
                            CompatGate.Outcome.SKIPPED, detail));
                }
            }
            if (needsTypeCompatGate(plan, repo)) {
                gates.put(TYPE_COMPATIBLE, new CompatGate(TYPE_COMPATIBLE,
                        CompatGate.Outcome.SKIPPED, "the gate did not reach a verdict"));
                // Same lifecycle as the baseline jars, and for the same reason: the candidate's
                // sources overwrite the baseline's in this very tree, so the baseline has to exist
                // as an artifact before the agent touches anything.
                DtsBuilder.Result baseline = new DtsBuilder(nodeHome).build(step.repoRoot(), dtsBaselineDir);
                if (baseline.ok()) {
                    typeCompatBaseline = baseline;
                } else {
                    String detail = "baseline declaration emit failed — " + summarize(baseline.log());
                    events.add("type-compat skipped: " + detail);
                    gates.put(TYPE_COMPATIBLE, new CompatGate(TYPE_COMPATIBLE,
                            CompatGate.Outcome.SKIPPED, detail));
                }
            }
            String contracts = contractDigest(runDir, step);
            for (int i = 0; i < ladder.size(); i++) {
                ModelTier tier = ladder.get(i);
                String priorDigest = contracts;
                if (i > 0) {
                    events.add("attempt " + (i + 1) + ": hard reset to base, escalating to " + tier.modelName());
                    RunGit.startBranch(step.repoRoot(), branch, base);
                    applyBumps(repo, step, events);
                    priorDigest = contracts + attemptDigest(history);
                }
                StepOutcome attempt = runner.run(step, tier.model(), tier.modelName(),
                        settingsFor.apply(repo), priorDigest);
                events.addAll(attempt.events());
                transcript.addAll(attempt.transcript());
                edits.addAll(attempt.edits());
                boolean escalationAllowed;
                synchronized (lock) {
                    state.addTokens(attempt.tokens());
                    escalationAllowed = state.tokensSpent() < runTokenBudget;
                }
                history.add(attempt);
                usedTier = i;
                boolean hasNextTier = i + 1 < ladder.size();
                if (!ESCALATE.contains(attempt.result()) || !hasNextTier || !escalationAllowed) {
                    break;
                }
            }
            outcome = history.get(history.size() - 1);
        } catch (ModelException e) {
            synchronized (lock) {
                state.addTokens(e.tokensSoFar());
                store.writeState(runDir, state);   // persist the partial spend even on the rethrow path
            }
            // The in-flight call (whichever tier was underway when the model call failed) is lost,
            // but every earlier attempt's completed transcript/edits — already accumulated in
            // events/transcript/edits across prior tiers — must still be persisted.
            store.writeCompatGates(runDir, repo, List.copyOf(gates.values()));
            store.writeAgentEvents(runDir, repo, events);
            store.writeTranscript(runDir, repo, transcript);
            store.writeEdits(runDir, repo, edits);
            if (endpointTrouble(e)) {
                Runnable paint;
                synchronized (lock) {
                    pauseLocked(runDir, state, "model endpoint unavailable: " + e.getMessage());
                    paint = transitionLocked(runDir, state, repo, RepoState.PAUSED_ENDPOINT, branch,
                            null, e.getMessage());
                }
                paint.run();
                return false;
            }
            throw e;   // 4xx configuration errors: captured by the unit task into fatal
        } finally {
            restoreOverlays(overlaysApplied, events);
        }
        store.writeCompatGates(runDir, repo, List.copyOf(gates.values()));
        store.writeAgentEvents(runDir, repo, events);
        store.writeTranscript(runDir, repo, transcript);
        store.writeEdits(runDir, repo, edits);
        String attemptTag = usedTier > 0
                ? "attempt " + (usedTier + 1) + " (" + ladder.get(usedTier).modelName() + ") " : "";
        if (outcome.result() == StepResult.SUCCESS) {
            // Before the checkpoint, so a pin the agent moved never reaches the commit a human
            // reviews or the release runbook publishes from.
            reassertBumps(repo, step, events);
            store.writeAgentEvents(runDir, repo, events);
            String sha = RunGit.commitAll(step.repoRoot(), "sdd: " + runId + " " + repo);
            RepoPropagation prop = propagation.getOrDefault(repo, RepoPropagation.none());
            if (prop.publish() != null) {
                RunnerSettings settings = settingsFor.apply(repo);
                java.util.concurrent.Semaphore permits = settings.gradlePermits();
                if (permits != null) {
                    permits.acquireUninterruptibly();
                }
                MavenLocalPublisher.Result published;
                try {
                    published = publisher.publish(step.repoRoot(), settings.javaHome(),
                            prop.publish().version(), prop.publish().m2Dir());
                } finally {
                    if (permits != null) {
                        permits.release();
                    }
                }
                events.add("publish " + prop.publish().version() + ": " + summarize(published.log()));
                store.writeCompatGates(runDir, repo, List.copyOf(gates.values()));
                store.writeAgentEvents(runDir, repo, events);
                store.writeTranscript(runDir, repo, transcript);
                store.writeEdits(runDir, repo, edits);
                if (!published.ok()) {
                    if (InfraClassifier.isInfra(published.log())) {
                        Runnable paint;
                        synchronized (lock) {
                            pauseLocked(runDir, state, "infrastructure failure publishing " + repo
                                    + " — fix the environment and resume");
                            paint = transitionLocked(runDir, state, repo, RepoState.PAUSED_INFRA, branch,
                                    null, attemptTag + "publish: " + summarize(published.log()));
                        }
                        paint.run();
                        return false;
                    }
                    Runnable paint;
                    synchronized (lock) {
                        paint = transitionLocked(runDir, state, repo, RepoState.FAILED, branch, null,
                                attemptTag + "publish failed: " + summarize(published.log()));
                    }
                    paint.run();
                    return true;
                }
            }
            if (!packNpm(repo, step, events)) {
                store.writeCompatGates(runDir, repo, List.copyOf(gates.values()));
                store.writeAgentEvents(runDir, repo, events);
                Runnable paint;
                synchronized (lock) {
                    paint = transitionLocked(runDir, state, repo, RepoState.FAILED, branch, null,
                            attemptTag + "npm pack failed");
                }
                paint.run();
                return true;
            }
            List<PlanModel.PlanContract> provided = providedContracts(plan, repo);
            Map<String, String> actualized = ContractActualizer.actualize(step.repoRoot(), provided, nodeHome);
            for (PlanModel.PlanContract contract : provided) {
                String body = actualized.get(contract.id());
                if (body == null) {
                    // Extraction found nothing for this contract — with a declared block present
                    // the whole-surface fallback is suppressed, so a declaration whose types do not
                    // exist (renamed, typo'd, or not fully qualified) yields a blank body that
                    // ContractActualizer drops. Nothing is written to contracts/, and every
                    // consumer's work order silently loses the actualized section that is supposed
                    // to supersede the drafted delta. Say it out loud instead.
                    events.add("contract " + contract.id() + " actualized to nothing — no "
                            + contract.kind() + " surface"
                            + (contract.declared().isEmpty() ? "" : " matching its declared types")
                            + " was found in " + repo + "; consumers' work orders will not carry it");
                    continue;
                }
                store.writeContract(runDir, contract.id(), body);
                events.add("contract " + contract.id() + " actualized");
            }
            if (typeCompatBaseline != null) {
                DtsBuilder.Result candidate = new DtsBuilder(nodeHome).build(step.repoRoot(),
                        runDir.resolve("contracts").resolve(slug(repo) + "-dts-candidate"));
                if (!candidate.ok()) {
                    String detail = "candidate declaration emit failed — " + summarize(candidate.log());
                    events.add("type-compat skipped: " + detail);
                    gates.put(TYPE_COMPATIBLE, new CompatGate(TYPE_COMPATIBLE,
                            CompatGate.Outcome.SKIPPED, detail));
                } else {
                    DtsCompatCheck.Verdict verdict =
                            DtsCompatCheck.compare(nodeHome, typeCompatBaseline, candidate);
                    // The count goes in the event alongside the verdict: a gate that probed
                    // nothing and a gate that probed forty exports and found nothing wrong are
                    // the same three words otherwise.
                    String probed = verdict.probed() + " export(s) probed";
                    events.add("type-compat: " + (verdict.typeCompatible() ? "compatible" : "BREAKING")
                            + " (" + probed + ")");
                    gates.put(TYPE_COMPATIBLE, new CompatGate(TYPE_COMPATIBLE,
                            verdict.typeCompatible() ? CompatGate.Outcome.PASSED : CompatGate.Outcome.BROKEN,
                            verdict.typeCompatible() ? probed : summarize(verdict.report())));
                    if (!verdict.typeCompatible()) {
                        store.writeCompatGates(runDir, repo, List.copyOf(gates.values()));
                        store.writeAgentEvents(runDir, repo, events);
                        store.writeTranscript(runDir, repo, transcript);
                        store.writeEdits(runDir, repo, edits);
                        Runnable paint;
                        synchronized (lock) {
                            paint = transitionLocked(runDir, state, repo, RepoState.FAILED, branch, null,
                                    attemptTag + "type-incompatible drift: " + summarize(verdict.report()));
                        }
                        paint.run();
                        return true;
                    }
                }
            }
            if (compatGate) {
                JarBuilder.Result candidate = buildJars(step, repo,
                        runDir.resolve("contracts").resolve(slug(repo) + "-candidate"));
                if (!candidate.ok() || candidate.jars().isEmpty()) {
                    String detail = "candidate build failed — " + summarize(candidate.log());
                    events.add("japicmp skipped: " + detail);
                    gates.put(BINARY_COMPATIBLE, new CompatGate(BINARY_COMPATIBLE,
                            CompatGate.Outcome.SKIPPED, detail));
                } else {
                    Drift result = compatDrift(baselineDir, candidate.jars(), events);
                    String drift = result.drift();
                    // Zero comparisons is not a pass. Every pair can be skipped — no matching
                    // baseline jar, a comparator that threw — and the drift report is empty in
                    // exactly the same way it is when the jars genuinely match.
                    CompatGate.Outcome gateOutcome = drift != null ? CompatGate.Outcome.BROKEN
                            : result.compared() > 0 ? CompatGate.Outcome.PASSED
                            : CompatGate.Outcome.SKIPPED;
                    gates.put(BINARY_COMPATIBLE, new CompatGate(BINARY_COMPATIBLE, gateOutcome,
                            drift != null ? drift
                                    : result.compared() > 0 ? result.compared() + " jar pair(s) compared"
                                    : "no jar pair could be compared"));
                    if (drift != null) {
                        store.writeCompatGates(runDir, repo, List.copyOf(gates.values()));
                        store.writeAgentEvents(runDir, repo, events);
                        store.writeTranscript(runDir, repo, transcript);
                        store.writeEdits(runDir, repo, edits);
                        Runnable paint;
                        synchronized (lock) {
                            paint = transitionLocked(runDir, state, repo, RepoState.FAILED, branch, null,
                                    attemptTag + "binary-incompatible drift: " + drift);
                        }
                        paint.run();
                        return true;
                    }
                }
            }
            store.writeCompatGates(runDir, repo, List.copyOf(gates.values()));
            store.writeAgentEvents(runDir, repo, events);
            store.writeTranscript(runDir, repo, transcript);
            store.writeEdits(runDir, repo, edits);
            Runnable paint;
            synchronized (lock) {
                paint = transitionLocked(runDir, state, repo, RepoState.SUCCEEDED, branch, sha,
                        attemptTag + outcome.summary());
            }
            paint.run();
        } else if (outcome.result() == StepResult.INFRA) {
            Runnable paint;
            synchronized (lock) {
                pauseLocked(runDir, state, "infrastructure failure in " + repo
                        + " — fix the environment and resume");
                paint = transitionLocked(runDir, state, repo, RepoState.PAUSED_INFRA, branch, null,
                        attemptTag + outcome.summary(), outcome.result().name());
            }
            paint.run();
            return false;
        } else {
            Runnable paint;
            synchronized (lock) {
                // outcome.result() is no longer folded into the detail text itself — it is now
                // carried as failureCode, and ReviewReport renders that in its own bracket ahead of
                // this detail. Repeating it here would print it twice on every FAILED line.
                paint = transitionLocked(runDir, state, repo, RepoState.FAILED, branch, null,
                        attemptTag + outcome.summary(), outcome.result().name());
            }
            paint.run();
        }
        return true;
    }

    /**
     * Packs every npm package this repo owes its consumers, at its planned version.
     *
     * <p>The npm counterpart of publishing to the run-scoped m2, and it fails the repo the same
     * way: a consumer that goes on to build against the provider's PREVIOUS published version
     * while the report says the provider succeeded is the specific dishonesty this prevents.
     */
    private boolean packNpm(String repo, RepoStep step, List<String> events) {
        List<RepoPropagation.PackSpec> packs =
                propagation.getOrDefault(repo, RepoPropagation.none()).packs();
        if (packs.isEmpty()) {
            return true;
        }
        NpmOverlay overlay = new NpmOverlay(nodeHome);
        for (RepoPropagation.PackSpec pack : packs) {
            NpmOverlay.PackResult result =
                    overlay.pack(pack.packageDir(), pack.version(), pack.storeDir());
            if (!result.ok()) {
                events.add("npm pack " + pack.packageName() + "@" + pack.version() + " failed: "
                        + summarize(result.log()));
                return false;
            }
            events.add("npm pack " + pack.packageName() + "@" + pack.version() + ": "
                    + result.tarball().getFileName());
        }
        return true;
    }

    /**
     * Overlays every provider this repo consumes through NPM_OVERLAY.
     *
     * <p>A provider whose tarball is missing is reported and skipped rather than failing the repo:
     * that happens when the provider was not part of this run, in which case building against its
     * published version is exactly right.
     */
    private List<NpmOverlay.Applied> applyOverlays(String repo, RepoStep step, List<String> events) {
        List<RepoPropagation.OverlaySpec> specs =
                propagation.getOrDefault(repo, RepoPropagation.none()).overlays();
        if (specs.isEmpty()) {
            return List.of();
        }
        NpmOverlay overlay = new NpmOverlay(nodeHome);
        List<NpmOverlay.Applied> applied = new ArrayList<>();
        for (RepoPropagation.OverlaySpec spec : specs) {
            java.nio.file.Path tarball = NpmOverlay.findTarball(spec.storeDir(), spec.packageName());
            if (tarball == null) {
                events.add("overlay skipped: no packed " + spec.packageName() + " from "
                        + spec.providerRepo() + " — building against its published version");
                continue;
            }
            try {
                // Backups live beside the tarball store in the RUN directory. Putting them inside
                // the checkout would leave an untracked directory in the diff a reviewer reads.
                applied.add(overlay.apply(step.repoRoot(), spec.packageName(), tarball,
                        spec.storeDir().resolveSibling("overlay-backup")));
                events.add("overlay " + spec.packageName() + " from " + spec.providerRepo());
            } catch (java.io.IOException e) {
                events.add("overlay " + spec.packageName() + " failed: " + e.getMessage());
            }
        }
        return applied;
    }

    private void restoreOverlays(List<NpmOverlay.Applied> applied, List<String> events) {
        NpmOverlay overlay = new NpmOverlay(nodeHome);
        for (NpmOverlay.Applied one : applied) {
            try {
                overlay.restore(one);
            } catch (RuntimeException e) {
                // Reported, never rethrown: a failed restore must not mask the repo's real verdict,
                // but it leaves the checkout altered and a human has to know.
                events.add("WARNING: could not restore " + one.installed() + ": " + e.getMessage());
            }
        }
    }


    private void applyBumps(String repo, RepoStep step, List<String> events) {
        for (RepoPropagation.BumpEdit bump : propagation.getOrDefault(repo, RepoPropagation.none()).bumps()) {
            events.addAll(BumpEdits.apply(step.repoRoot(), bump));
        }
    }

    /**
     * Re-asserts the planned pins over whatever the agent left behind, before the checkpoint commit.
     *
     * <p>Which version a consumer declares is the plan's answer, not the agent's: it is computed
     * from the provider's version_action and is what the release runbook will publish. An agent
     * told to "update the dependency" has no way to know that number and will guess — observed
     * live guessing {@code ^0.4.0} against a planned {@code 0.3.0} — so the guess is overwritten
     * rather than trusted. A bump the agent left alone re-applies to a no-op, so the common case
     * costs nothing and records nothing.
     */
    private void reassertBumps(String repo, RepoStep step, List<String> events) {
        for (RepoPropagation.BumpEdit bump : propagation.getOrDefault(repo, RepoPropagation.none()).bumps()) {
            for (String event : BumpEdits.apply(step.repoRoot(), bump)) {
                if (event.contains(" -> ")) {
                    events.add("bump reasserted after the agent — " + event);
                }
            }
        }
    }

    private List<PlanModel.PlanContract> providedContracts(PlanModel plan, String repo) {
        return plan.contracts().stream().filter(c -> c.provider().equals(repo)).toList();
    }

    private static final String BINARY_COMPATIBLE = "binary-compatible";
    private static final String TYPE_COMPATIBLE = "type-compatible";

    private boolean needsCompatGate(PlanModel plan, String repo) {
        return providedContracts(plan, repo).stream()
                .anyMatch(c -> BINARY_COMPATIBLE.equals(c.compat()));
    }

    private boolean needsTypeCompatGate(PlanModel plan, String repo) {
        return providedContracts(plan, repo).stream()
                .anyMatch(c -> TYPE_COMPATIBLE.equals(c.compat()));
    }

    private String contractDigest(Path runDir, RepoStep step) {
        StringBuilder digest = new StringBuilder();
        for (sdd.agent.run.ContractRef consumed : step.consumes()) {
            String actual = store.readContract(runDir, consumed.id());
            if (actual != null) {
                digest.append("\n### ").append(consumed.id()).append(" (actualized)\n")
                        .append(actual).append('\n');
            }
        }
        return digest.isEmpty() ? "" : "\n\n## Actualized contracts (re-extracted from green "
                + "upstreams — these supersede the drafted deltas)\n" + digest;
    }

    private JarBuilder.Result buildJars(RepoStep step, String repo, Path outDir) {
        RunnerSettings settings = settingsFor.apply(repo);
        java.util.concurrent.Semaphore permits = settings.gradlePermits();
        if (permits != null) {
            permits.acquireUninterruptibly();
        }
        try {
            return jarBuilder.build(step.repoRoot(), settings.javaHome(), outDir, settings.gradleExtraArgs());
        } finally {
            if (permits != null) {
                permits.release();
            }
        }
    }

    private static String summarize(String log) {
        String flat = log.replace('\n', ' ').strip();
        return flat.length() > 200 ? flat.substring(0, 200) : flat;
    }

    private static boolean endpointTrouble(ModelException e) {
        int status = e.statusCode();
        return status == 0 || status == 429 || status >= 500;
    }

    /** Digest handed to the next tier: a one-line summary of EVERY prior attempt (so a tier deep in a
     *  3+-tier ladder knows what already failed), plus the full verification output of the most recent
     *  one. Capped at 4000 chars overall, recency-aware: when over budget, the OLDEST per-attempt lines
     *  are dropped first (one at a time) so the newest attempt's line and the latest verification output
     *  — the two things a fresh tier most needs — always survive, rather than a flat tail truncation
     *  silently amputating exactly that. */
    private String attemptDigest(List<StepOutcome> priorAttempts) {
        String header = "\n\n## Previous attempts (all failed)\n";
        List<String> attemptLines = new ArrayList<>();
        for (int i = 0; i < priorAttempts.size(); i++) {
            StepOutcome prior = priorAttempts.get(i);
            attemptLines.add("- attempt " + (i + 1) + " (" + ladder.get(i).modelName() + "): "
                    + prior.result() + ": " + prior.summary() + '\n');
        }
        StepOutcome last = priorAttempts.get(priorAttempts.size() - 1);
        String verification = last.verificationOutput().isEmpty() ? "none" : last.verificationOutput();
        String footer = "The tree has been hard-reset to base, so its edits are gone. Do not repeat its "
                + "mistakes. Its last verification output:\n" + verification;
        while (attemptLines.size() > 1
                && header.length() + linesLength(attemptLines) + footer.length() > 4000) {
            attemptLines.remove(0);   // oldest first — the newest (last) line is never dropped
        }
        String result = header + String.join("", attemptLines) + footer;
        return result.length() > 4000 ? result.substring(0, 4000) : result;
    }

    private static int linesLength(List<String> lines) {
        return lines.stream().mapToInt(String::length).sum();
    }

    private static String slug(String repo) {
        return repo.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    /** Caller must hold lock. First pause wins; every pause is also a run-level event line. */
    private void pauseLocked(Path runDir, RunState state, String reason) {
        if (state.pausedReason() == null) {
            state.pause(reason);
            store.appendRunEvent(runDir, reason);
            store.writeState(runDir, state);
        }
    }

    /** Caller must hold lock. failureCode defaults to null, which does NOT mean "the repo never ran
     *  or its final attempt was SUCCESS": it means no {@code StepResult} name describes this
     *  transition. Alongside the benign transitions (IN_PROGRESS, SUCCEEDED, SKIPPED_UPSTREAM_FAILED)
     *  four post-agent failure paths deliberately come through here — FAILED on a failed maven-local
     *  publish and FAILED on binary-incompatible japicmp drift, both AFTER the agent's own last
     *  attempt returned SUCCESS; PAUSED_INFRA when that publish failure is infrastructural; and
     *  PAUSED_ENDPOINT, where the model call never produced a result at all. None of those reasons
     *  exists in {@code StepResult}'s vocabulary, and inventing a code for them would be worse than
     *  leaving the field absent (design 4C amendment; see {@link RepoRun#failureCode()}) — so an
     *  absent code in {@code state.json} or in the Gate-2 report means "not attributable to a step
     *  result", never "nothing failed". */
    private Runnable transitionLocked(Path runDir, RunState state, String repo, RepoState to,
                                      String branch, String sha, String detail) {
        return transitionLocked(runDir, state, repo, to, branch, sha, detail, null);
    }

    /**
     * Caller must hold lock. failureCode is the {@code StepResult} name of the repo's final
     * attempt (design 4C amendment; see {@link RepoRun#failureCode()}), or null when there was
     * none to report.
     *
     * <p><b>Ruling P3:</b> the {@code state.json}/event-log write happens here, still under {@link
     * #lock} — unchanged from before progress existed. What IS new is the return value: which
     * {@link Progress} call (if any) this landed transition corresponds to is decided right here,
     * right after {@link RunStore#writeState} returns — "the event [is determined] inside {@code
     * transitionLocked}" — but handed back as a {@link Runnable} rather than invoked. Every call
     * site runs it only after its own {@code synchronized (lock)} block has closed — "PAINT
     * off-lock" — so a slow terminal write (or a live renderer's own internal monitor, briefly held
     * by the scheduled tick) can never serialize behind, or be serialized by, {@link #lock}, which
     * is the one lock every genuinely-parallel repo thread in this class contends on. Reusing
     * {@code lock} itself for rendering — the alternative this avoids — would couple "persist
     * state.json" to "write to stderr" and invent a lock-ordering hazard in the one command here
     * that is actually parallel.
     */
    private Runnable transitionLocked(Path runDir, RunState state, String repo, RepoState to,
                                      String branch, String sha, String detail, String failureCode) {
        RepoState from = state.stateOf(repo);
        state.set(repo, to, branch, sha, detail, failureCode);
        store.appendEvent(runDir, repo, from, to, detail);
        store.writeState(runDir, state);
        return paintAction(repo, to, detail);
    }

    /**
     * The design doc's mapping table, as code: {@code IN_PROGRESS} starts an item, every terminal
     * outcome finishes it (advancing "done" — {@link Progress#finish} is idempotent per item, so a
     * repo that somehow reached a terminal state twice cannot double-count). Every {@link
     * RepoState} is listed explicitly, {@code PENDING} included even though {@link
     * #transitionLocked} never targets it — an exhaustive switch over a repo's own state enum is
     * the one place a future eighth state cannot silently fall through unhandled.
     *
     * <p><b>Corrected mapping for a pause (was {@code progress::stop}, a UX bug a review caught):
     * </b> {@code PAUSED_INFRA}/{@code PAUSED_ENDPOINT} is ONE repo's outcome, not the whole run's —
     * {@code pauseLocked} only refuses repos not yet started ({@code runRepo}'s own
     * {@code state.pausedReason() != null} check), so sibling repos already in flight in the same
     * parallel layer keep running to their own conclusion after this one pauses. Calling {@code
     * progress.stop()} here would permanently kill the renderer (erase the line, shut down the
     * ticker, set a one-way {@code stopped} flag) the instant the FIRST repo paused, silently
     * turning every sibling's subsequent {@code start}/{@code finish} into a no-op while work
     * visibly continued — the live line would vanish mid-run. A pause is recorded as a {@link
     * Progress#note} instead (a line that survives on its own, exactly what {@code note} is for),
     * and the line keeps running for whoever is still working. The real, run-wide {@link
     * Progress#stop()} belongs to — and already happens at — the command's own call once {@link
     * #run} fully returns, not to any single repo's transition.
     */
    private Runnable paintAction(String repo, RepoState to, String detail) {
        return switch (to) {
            case IN_PROGRESS -> () -> progress.start(repo);
            case SUCCEEDED, FAILED, SKIPPED_UPSTREAM_FAILED -> () -> progress.finish(repo);
            case PAUSED_INFRA, PAUSED_ENDPOINT -> () -> progress.note(pauseNote(repo, detail));
            case PENDING -> () -> { };
        };
    }

    /** Never a {@code <repo>: } prefix (standing constraint — {@code RebuildPass.java:88-89}
     *  documents why that exact shape is load-bearing elsewhere, and this seam obeys the same rule
     *  even though nothing here parses it): leads with "paused:" instead. */
    private static String pauseNote(String repo, String detail) {
        return detail == null || detail.isBlank() ? "paused: " + repo : "paused: " + repo + " — " + detail;
    }

    /**
     * @param drift    a short drift report, or null when nothing incompatible was found
     * @param compared how many jar PAIRS were actually put through japicmp. Reported separately
     *                 because "no drift" and "nothing was compared" are the same null otherwise:
     *                 every pair can be skipped — an unmatched jar, a comparator that threw — and
     *                 the result still reads as a clean gate. The same distinction
     *                 {@code DtsCompatCheck} draws with its probe count.
     */
    private record Drift(String drift, int compared) {
    }

    /** Compares matched baseline/candidate jars; returns a short drift report or null when clean.
     * Also sweeps baselineDir once for orphans — a baseline jar with no candidate counterpart means
     * its module/artifact was deleted (the maximal breaking change) and must still be evented, per
     * ratified interpretation (e): unmatched jars are skipped with an event, not silently dropped. */
    private static Drift compatDrift(Path baselineDir, List<Path> candidates, List<String> events) {
        List<Path> baselineJars;
        try (var jars = Files.list(baselineDir)) {
            baselineJars = jars.sorted().toList();
        } catch (java.io.IOException e) {
            baselineJars = List.of();
        }
        Set<String> matchedBaselineKeys = new HashSet<>();
        StringBuilder drift = new StringBuilder();
        int compared = 0;
        for (Path candidate : candidates) {
            String key = versionless(candidate.getFileName().toString());
            Path baseline = baselineJars.stream()
                    .filter(p -> versionless(p.getFileName().toString()).equals(key))
                    .findFirst().orElse(null);
            if (baseline == null) {
                events.add("japicmp skipped for " + candidate.getFileName() + ": no matching baseline jar");
                continue;
            }
            matchedBaselineKeys.add(key);
            // A gate that cannot run is SKIPPED with a loud event, never fatal (ratified interpretation
            // (d)) — mirrors how a baseline/candidate BUILD failure is handled above. Without this, a
            // comparator exception (e.g. an unreadable jar, or japicmp's own internal failures) propagates
            // out of compatDrift -> runRepo -> the orchestrator's `fatal` AtomicReference and aborts the
            // whole run, as happened on a live real-estate run.
            try {
                JapicmpCheck.Verdict verdict = JapicmpCheck.compare(baseline, candidate);
                compared++;
                events.add("japicmp " + candidate.getFileName() + ": "
                        + (verdict.binaryCompatible() ? "binary-compatible" : "BREAKING"));
                if (!verdict.binaryCompatible()) {
                    drift.append(verdict.report());
                }
            } catch (RuntimeException e) {
                events.add("japicmp skipped for " + candidate.getFileName()
                        + ": comparison failed — " + summarize(String.valueOf(e.getMessage())));
            }
        }
        for (Path baseline : baselineJars) {
            if (!matchedBaselineKeys.contains(versionless(baseline.getFileName().toString()))) {
                events.add("japicmp skipped for " + baseline.getFileName() + ": no matching candidate jar");
            }
        }
        if (drift.isEmpty()) {
            return new Drift(null, compared);
        }
        String report = drift.toString().replace('\n', ' ').strip();
        return new Drift(report.length() > 200 ? report.substring(0, 200) : report, compared);
    }

    private static String versionless(String jarName) {
        return jarName.replaceAll("-[0-9][^/]*\\.jar$", "");
    }
}
