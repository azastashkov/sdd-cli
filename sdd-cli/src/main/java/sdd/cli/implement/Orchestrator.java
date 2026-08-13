package sdd.cli.implement;

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
import java.util.function.Function;

/**
 * Drives up to two attempts per repo across the plan's execution order (design Component 3): branch off
 * base, run the 4B agent, escalate a failed attempt to the planner-tier model from a hard-reset tree,
 * checkpoint-commit on success, cascade failures to downstream skips, and pause the run (exit 3) on
 * endpoint outage, infrastructure failure, or run-budget exhaustion.
 * Concurrency and M8 staleness recovery are 4C-3b.
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

    public record RunResult(int exitCode, RunState state) {
    }

    public Orchestrator(RepoStepRunner runner, ChatModel coder, String coderModelName,
                        ChatModel escalation, String escalationModelName,
                        Function<String, RunnerSettings> settingsFor, RunStore store, long runTokenBudget) {
        this.runner = runner;
        this.coder = coder;
        this.coderModelName = coderModelName;
        this.escalation = escalation;
        this.escalationModelName = escalationModelName;
        this.settingsFor = settingsFor;
        this.store = store;
        this.runTokenBudget = runTokenBudget;
    }

    public RunResult run(Path runDir, PlanModel plan, Map<String, RepoStep> steps) {
        String runId = runDir.getFileName().toString();
        // Only repos with a runnable step are tracked. Step-less affected repos (bom / bump-only sites,
        // whose version-bump edits are 4C-2b) would otherwise orphan at PENDING and force a spurious exit 2.
        List<String> runnable = Scheduler.sequence(plan.order()).stream()
                .filter(steps::containsKey).toList();
        return run(runDir, plan, steps, new RunState(runId, runnable));
    }

    /** Resume entry: repos already SUCCEEDED or FAILED in the passed state are not re-run. */
    public RunResult run(Path runDir, PlanModel plan, Map<String, RepoStep> steps, RunState state) {
        String runId = runDir.getFileName().toString();
        try {
            store.writeState(runDir, state);   // inside the try so an IO failure still releases the lock
            for (String repo : Scheduler.sequence(plan.order())) {
                if (!steps.containsKey(repo)) {
                    continue;
                }
                RepoState already = state.stateOf(repo);
                if (already == RepoState.SUCCEEDED || already == RepoState.FAILED) {
                    continue;   // settled in a prior (resumed) walk
                }
                if (state.tokensSpent() >= runTokenBudget) {
                    state.pause("run token budget exhausted (" + state.tokensSpent() + " tokens)");
                    store.writeState(runDir, state);
                    break;
                }
                if (Scheduler.blockedByUpstream(repo, plan.edges(), state)) {
                    transition(runDir, state, repo, RepoState.SKIPPED_UPSTREAM_FAILED, null, null,
                            "upstream failed");
                    continue;
                }
                RepoStep step = steps.get(repo);
                transition(runDir, state, repo, RepoState.IN_PROGRESS, null, null, "");
                String branch = "sdd/" + runId + "/" + slug(repo);
                String base = plan.repo(repo).map(PlanModel.PlanRepo::baseSha).orElse("");
                List<String> events = new ArrayList<>();
                StepOutcome outcome;
                boolean escalated = false;
                try {
                    RunGit.startBranch(step.repoRoot(), branch, base);
                    outcome = runner.run(step, coder, coderModelName, settingsFor.apply(repo), "");
                    events.addAll(outcome.events());
                    state.addTokens(outcome.tokens());
                    if (ESCALATE.contains(outcome.result()) && state.tokensSpent() < runTokenBudget) {
                        escalated = true;
                        events.add("attempt 2: hard reset to base, escalating to " + escalationModelName);
                        RunGit.startBranch(step.repoRoot(), branch, base);
                        StepOutcome second = runner.run(step, escalation, escalationModelName,
                                settingsFor.apply(repo), attemptDigest(outcome));
                        events.addAll(second.events());
                        state.addTokens(second.tokens());
                        outcome = second;
                    }
                } catch (ModelException e) {
                    store.writeAgentEvents(runDir, repo, events);
                    if (endpointTrouble(e)) {
                        state.pause("model endpoint unavailable: " + e.getMessage());
                        transition(runDir, state, repo, RepoState.PAUSED_ENDPOINT, branch, null,
                                e.getMessage());
                        break;
                    }
                    throw e;   // 4xx configuration errors abort the run (exit 4 upstream)
                }
                store.writeAgentEvents(runDir, repo, events);
                String attemptTag = escalated ? "attempt 2 (" + escalationModelName + ") " : "";
                if (outcome.result() == StepResult.SUCCESS) {
                    String sha = RunGit.commitAll(step.repoRoot(), "sdd: " + runId + " " + repo);
                    transition(runDir, state, repo, RepoState.SUCCEEDED, branch, sha,
                            attemptTag + outcome.summary());
                } else if (outcome.result() == StepResult.INFRA) {
                    state.pause("infrastructure failure in " + repo + " — fix the environment and resume");
                    transition(runDir, state, repo, RepoState.PAUSED_INFRA, branch, null,
                            attemptTag + outcome.summary());
                    break;
                } else {
                    transition(runDir, state, repo, RepoState.FAILED, branch, null,
                            attemptTag + outcome.result() + ": " + outcome.summary());
                }
            }
        } finally {
            store.releaseLock(runDir);
        }
        boolean paused = state.pausedReason() != null;
        boolean allSucceeded = state.repos().stream().allMatch(r -> r.state() == RepoState.SUCCEEDED);
        return new RunResult(paused ? 3 : allSucceeded ? 0 : 2, state);
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

    private void transition(Path runDir, RunState state, String repo, RepoState to, String branch,
                            String sha, String detail) {
        RepoState from = state.stateOf(repo);
        state.set(repo, to, branch, sha, detail);
        store.appendEvent(runDir, repo, from, to, detail);
        store.writeState(runDir, state);
    }
}
