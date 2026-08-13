package sdd.cli.implement;

import sdd.agent.run.InfraClassifier;
import sdd.agent.run.RepoStep;
import sdd.agent.run.RepoStepRunner;
import sdd.agent.run.RunnerSettings;
import sdd.agent.run.StepOutcome;
import sdd.agent.run.StepResult;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ModelException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Drives up to two attempts per repo across the plan's execution order (design Component 3): branch off
 * base, run the 4B agent, escalate a failed attempt to the planner-tier model from a hard-reset tree,
 * checkpoint-commit on success, cascade failures to downstream skips, and pause the run (exit 3) on
 * endpoint outage, infrastructure failure, or run-budget exhaustion.
 * Repos run parallel-within-layer on virtual threads (M8 staleness recovery is 4C-3c); all shared
 * state is guarded by a single lock, and the first pause wins.
 * MAVEN_LOCAL propagation (4C-2b): bump edits re-applied after every branch reset; providers
 * publish to the run-scoped m2 after their checkpoint commit.
 */
public final class Orchestrator {
    /** Attempt-2 triggers. BLOCKED asked for a human; INFRA pauses; SUCCESS needs nothing. */
    private static final Set<StepResult> ESCALATE = Set.of(StepResult.VERIFY_FAILED,
            StepResult.EXHAUSTED, StepResult.BUDGET, StepResult.MALFORMED, StepResult.WEDGED);

    private final RepoStepRunner runner;
    private final ChatModel coder;
    private final String coderModelName;
    private final ChatModel escalation;
    private final String escalationModelName;
    private final Function<String, RunnerSettings> settingsFor;
    private final RunStore store;
    private final long runTokenBudget;
    private final Map<String, RepoPropagation> propagation;
    private final MavenLocalPublisher publisher;

    public record RunResult(int exitCode, RunState state) {
    }

    public Orchestrator(RepoStepRunner runner, ChatModel coder, String coderModelName,
                        ChatModel escalation, String escalationModelName,
                        Function<String, RunnerSettings> settingsFor, RunStore store, long runTokenBudget,
                        Map<String, RepoPropagation> propagation, MavenLocalPublisher publisher) {
        this.runner = runner;
        this.coder = coder;
        this.coderModelName = coderModelName;
        this.escalation = escalation;
        this.escalationModelName = escalationModelName;
        this.settingsFor = settingsFor;
        this.store = store;
        this.runTokenBudget = runTokenBudget;
        this.propagation = Map.copyOf(propagation);
        this.publisher = publisher;
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

    /** One repo, both attempts. Returns false when the walk must stop (a pause landed). */
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
        StepOutcome outcome;
        boolean escalated = false;
        try {
            RunGit.startBranch(step.repoRoot(), branch, base);
            applyBumps(repo, step, events);
            outcome = runner.run(step, coder, coderModelName, settingsFor.apply(repo), "");
            events.addAll(outcome.events());
            boolean escalationAllowed;
            synchronized (lock) {
                state.addTokens(outcome.tokens());
                escalationAllowed = state.tokensSpent() < runTokenBudget;
            }
            if (ESCALATE.contains(outcome.result()) && escalationAllowed) {
                escalated = true;
                events.add("attempt 2: hard reset to base, escalating to " + escalationModelName);
                RunGit.startBranch(step.repoRoot(), branch, base);
                applyBumps(repo, step, events);
                StepOutcome second = runner.run(step, escalation, escalationModelName,
                        settingsFor.apply(repo), attemptDigest(outcome));
                events.addAll(second.events());
                synchronized (lock) {
                    state.addTokens(second.tokens());
                }
                outcome = second;
            }
        } catch (ModelException e) {
            synchronized (lock) {
                state.addTokens(e.tokensSoFar());
                store.writeState(runDir, state);   // persist the partial spend even on the rethrow path
            }
            store.writeAgentEvents(runDir, repo, events);
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
        String attemptTag = escalated ? "attempt 2 (" + escalationModelName + ") " : "";
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
            synchronized (lock) {
                transitionLocked(runDir, state, repo, RepoState.SUCCEEDED, branch, sha,
                        attemptTag + outcome.summary());
            }
        } else if (outcome.result() == StepResult.INFRA) {
            synchronized (lock) {
                pauseLocked(runDir, state, "infrastructure failure in " + repo
                        + " — fix the environment and resume");
                transitionLocked(runDir, state, repo, RepoState.PAUSED_INFRA, branch, null,
                        attemptTag + outcome.summary());
            }
            return false;
        } else {
            synchronized (lock) {
                transitionLocked(runDir, state, repo, RepoState.FAILED, branch, null,
                        attemptTag + outcome.result() + ": " + outcome.summary());
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

    private static String summarize(String log) {
        String flat = log.replace('\n', ' ').strip();
        return flat.length() > 200 ? flat.substring(0, 200) : flat;
    }

    private static boolean endpointTrouble(ModelException e) {
        int status = e.statusCode();
        return status == 0 || status == 429 || status >= 500;
    }

    private static String attemptDigest(StepOutcome first) {
        String verification = first.verificationOutput().isEmpty() ? "none" : first.verificationOutput();
        return "\n\n## A previous attempt by a smaller model failed — you are the escalation\n"
                + "It ended " + first.result() + ": " + first.summary() + "\n"
                + "The tree has been hard-reset to base, so its edits are gone. Do not repeat its "
                + "mistakes. Its last verification output:\n" + verification;
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

    /** Caller must hold lock. */
    private void transitionLocked(Path runDir, RunState state, String repo, RepoState to,
                                  String branch, String sha, String detail) {
        RepoState from = state.stateOf(repo);
        state.set(repo, to, branch, sha, detail);
        store.appendEvent(runDir, repo, from, to, detail);
        store.writeState(runDir, state);
    }
}
