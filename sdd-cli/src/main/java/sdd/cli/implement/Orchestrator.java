package sdd.cli.implement;

import sdd.agent.run.InfraClassifier;
import sdd.agent.run.RepoStep;
import sdd.agent.run.RepoStepRunner;
import sdd.agent.run.RunnerSettings;
import sdd.agent.run.StepOutcome;
import sdd.agent.run.StepResult;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ModelException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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

    public record RunResult(int exitCode, RunState state) {
    }

    public Orchestrator(RepoStepRunner runner, List<ModelTier> ladder,
                        Function<String, RunnerSettings> settingsFor, RunStore store, long runTokenBudget,
                        Map<String, RepoPropagation> propagation, MavenLocalPublisher publisher,
                        JarBuilder jarBuilder) {
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
            if (Scheduler.blockedByUpstream(repo, plan.edges(), state)) {
                transitionLocked(runDir, state, repo, RepoState.SKIPPED_UPSTREAM_FAILED, null, null,
                        "upstream failed");
                return true;
            }
            transitionLocked(runDir, state, repo, RepoState.IN_PROGRESS, null, null, "");
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
        boolean compatGate = false;
        try {
            RunGit.startBranch(step.repoRoot(), branch, base);
            applyBumps(repo, step, events);
            if (needsCompatGate(plan, repo)) {
                JarBuilder.Result baseline = buildJars(step, repo, baselineDir);
                if (baseline.ok() && !baseline.jars().isEmpty()) {
                    compatGate = true;
                } else {
                    events.add("japicmp skipped: baseline build failed — "
                            + summarize(baseline.log()));
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
            store.writeAgentEvents(runDir, repo, events);
            store.writeTranscript(runDir, repo, transcript);
            store.writeEdits(runDir, repo, edits);
            if (endpointTrouble(e)) {
                synchronized (lock) {
                    pauseLocked(runDir, state, "model endpoint unavailable: " + e.getMessage());
                    transitionLocked(runDir, state, repo, RepoState.PAUSED_ENDPOINT, branch, null,
                            e.getMessage());
                }
                return false;
            }
            throw e;   // 4xx configuration errors: captured by the unit task into fatal
        }
        store.writeAgentEvents(runDir, repo, events);
        store.writeTranscript(runDir, repo, transcript);
        store.writeEdits(runDir, repo, edits);
        String attemptTag = usedTier > 0
                ? "attempt " + (usedTier + 1) + " (" + ladder.get(usedTier).modelName() + ") " : "";
        if (outcome.result() == StepResult.SUCCESS) {
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
                store.writeAgentEvents(runDir, repo, events);
                store.writeTranscript(runDir, repo, transcript);
                store.writeEdits(runDir, repo, edits);
                if (!published.ok()) {
                    if (InfraClassifier.isInfra(published.log())) {
                        synchronized (lock) {
                            pauseLocked(runDir, state, "infrastructure failure publishing " + repo
                                    + " — fix the environment and resume");
                            transitionLocked(runDir, state, repo, RepoState.PAUSED_INFRA, branch, null,
                                    attemptTag + "publish: " + summarize(published.log()));
                        }
                        return false;
                    }
                    synchronized (lock) {
                        transitionLocked(runDir, state, repo, RepoState.FAILED, branch, null,
                                attemptTag + "publish failed: " + summarize(published.log()));
                    }
                    return true;
                }
            }
            List<PlanModel.PlanContract> provided = providedContracts(plan, repo);
            Map<String, String> actualized = ContractActualizer.actualize(step.repoRoot(), provided);
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
            if (compatGate) {
                JarBuilder.Result candidate = buildJars(step, repo,
                        runDir.resolve("contracts").resolve(slug(repo) + "-candidate"));
                if (!candidate.ok() || candidate.jars().isEmpty()) {
                    events.add("japicmp skipped: candidate build failed — " + summarize(candidate.log()));
                } else {
                    String drift = compatDrift(baselineDir, candidate.jars(), events);
                    if (drift != null) {
                        store.writeAgentEvents(runDir, repo, events);
                        store.writeTranscript(runDir, repo, transcript);
                        store.writeEdits(runDir, repo, edits);
                        synchronized (lock) {
                            transitionLocked(runDir, state, repo, RepoState.FAILED, branch, null,
                                    attemptTag + "binary-incompatible drift: " + drift);
                        }
                        return true;
                    }
                }
            }
            store.writeAgentEvents(runDir, repo, events);
            store.writeTranscript(runDir, repo, transcript);
            store.writeEdits(runDir, repo, edits);
            synchronized (lock) {
                transitionLocked(runDir, state, repo, RepoState.SUCCEEDED, branch, sha,
                        attemptTag + outcome.summary());
            }
        } else if (outcome.result() == StepResult.INFRA) {
            synchronized (lock) {
                pauseLocked(runDir, state, "infrastructure failure in " + repo
                        + " — fix the environment and resume");
                transitionLocked(runDir, state, repo, RepoState.PAUSED_INFRA, branch, null,
                        attemptTag + outcome.summary(), outcome.result().name());
            }
            return false;
        } else {
            synchronized (lock) {
                // outcome.result() is no longer folded into the detail text itself — it is now
                // carried as failureCode, and ReviewReport renders that in its own bracket ahead of
                // this detail. Repeating it here would print it twice on every FAILED line.
                transitionLocked(runDir, state, repo, RepoState.FAILED, branch, null,
                        attemptTag + outcome.summary(), outcome.result().name());
            }
        }
        return true;
    }

    private void applyBumps(String repo, RepoStep step, List<String> events) {
        for (RepoPropagation.BumpEdit bump : propagation.getOrDefault(repo, RepoPropagation.none()).bumps()) {
            List<java.nio.file.Path> edited = VersionBump.apply(step.repoRoot(), bump.group(),
                    bump.name(), bump.oldVersion(), bump.newVersion());
            String coordinate = bump.group() + ":" + bump.name();
            if (edited.isEmpty()) {
                events.add("bump: no declaration of " + coordinate + ":" + bump.oldVersion()
                        + " found — left unedited");
            } else {
                events.add("bump: " + coordinate + " " + bump.oldVersion() + " -> " + bump.newVersion()
                        + " in " + edited.size() + " file(s)");
            }
        }
    }

    private List<PlanModel.PlanContract> providedContracts(PlanModel plan, String repo) {
        return plan.contracts().stream().filter(c -> c.provider().equals(repo)).toList();
    }

    private boolean needsCompatGate(PlanModel plan, String repo) {
        return providedContracts(plan, repo).stream()
                .anyMatch(c -> "binary-compatible".equals(c.compat()));
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

    /** Caller must hold lock. failureCode defaults to null — the repo either never ran or its
     *  final attempt was SUCCESS. */
    private void transitionLocked(Path runDir, RunState state, String repo, RepoState to,
                                  String branch, String sha, String detail) {
        transitionLocked(runDir, state, repo, to, branch, sha, detail, null);
    }

    /** Caller must hold lock. failureCode is the {@code StepResult} name of the repo's final
     *  attempt (design 4C amendment; see {@link RepoRun#failureCode()}), or null when there was
     *  none to report. */
    private void transitionLocked(Path runDir, RunState state, String repo, RepoState to,
                                  String branch, String sha, String detail, String failureCode) {
        RepoState from = state.stateOf(repo);
        state.set(repo, to, branch, sha, detail, failureCode);
        store.appendEvent(runDir, repo, from, to, detail);
        store.writeState(runDir, state);
    }

    /** Compares matched baseline/candidate jars; returns a short drift report or null when clean.
     * Also sweeps baselineDir once for orphans — a baseline jar with no candidate counterpart means
     * its module/artifact was deleted (the maximal breaking change) and must still be evented, per
     * ratified interpretation (e): unmatched jars are skipped with an event, not silently dropped. */
    private static String compatDrift(Path baselineDir, List<Path> candidates, List<String> events) {
        List<Path> baselineJars;
        try (var jars = Files.list(baselineDir)) {
            baselineJars = jars.sorted().toList();
        } catch (java.io.IOException e) {
            baselineJars = List.of();
        }
        Set<String> matchedBaselineKeys = new HashSet<>();
        StringBuilder drift = new StringBuilder();
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
            return null;
        }
        String report = drift.toString().replace('\n', ' ').strip();
        return report.length() > 200 ? report.substring(0, 200) : report;
    }

    private static String versionless(String jarName) {
        return jarName.replaceAll("-[0-9][^/]*\\.jar$", "");
    }
}
