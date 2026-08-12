package sdd.cli.implement;

import sdd.agent.run.RepoStep;
import sdd.agent.run.RepoStepRunner;
import sdd.agent.run.RunnerSettings;
import sdd.agent.run.StepOutcome;
import sdd.agent.run.StepResult;
import sdd.core.llm.ChatModel;

import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Drives one attempt per repo across the plan's execution order (design Component 3 orchestration,
 * single-attempt slice): branch off base, run the 4B agent, checkpoint-commit on success, cascade a
 * failure to downstream skips, persisting state + events throughout. Multi-attempt/escalation,
 * propagation, and resilience are later 4C sub-phases.
 */
public final class Orchestrator {
    private final RepoStepRunner runner;
    private final ChatModel coder;
    private final String coderModelName;
    private final Function<String, RunnerSettings> settingsFor;
    private final RunStore store;
    private final InstantSource clock;

    public record RunResult(int exitCode, RunState state) {
    }

    public Orchestrator(RepoStepRunner runner, ChatModel coder, String coderModelName,
                        Function<String, RunnerSettings> settingsFor, RunStore store, InstantSource clock) {
        this.runner = runner;
        this.coder = coder;
        this.coderModelName = coderModelName;
        this.settingsFor = settingsFor;
        this.store = store;
        this.clock = clock;
    }

    public RunResult run(Path runDir, PlanModel plan, Map<String, RepoStep> steps) {
        String runId = runDir.getFileName().toString();
        // Only repos with a runnable step are tracked. Step-less affected repos (bom / bump-only sites,
        // whose version-bump edits are 4C-2) would otherwise orphan at PENDING and force a spurious exit 2.
        List<String> runnable = Scheduler.sequence(plan.order()).stream()
                .filter(steps::containsKey).toList();
        RunState state = new RunState(runId, runnable);
        try {
            store.writeState(runDir, state);   // inside the try so an IO failure still releases the lock
            for (String repo : runnable) {
                RepoStep step = steps.get(repo);
                if (Scheduler.blockedByUpstream(repo, plan.edges(), state)) {
                    transition(runDir, state, repo, RepoState.SKIPPED_UPSTREAM_FAILED, null, null,
                            "upstream failed");
                    continue;
                }
                transition(runDir, state, repo, RepoState.IN_PROGRESS, null, null, "");
                String branch = "sdd/" + runId + "/" + slug(repo);
                String base = plan.repo(repo).map(PlanModel.PlanRepo::baseSha).orElse("");
                RunGit.startBranch(step.repoRoot(), branch, base);
                StepOutcome outcome = runner.run(step, coder, coderModelName, settingsFor.apply(repo));
                store.writeAgentEvents(runDir, repo, outcome.events());
                if (outcome.result() == StepResult.SUCCESS) {
                    String sha = RunGit.commitAll(step.repoRoot(), "sdd: " + runId + " " + repo);
                    transition(runDir, state, repo, RepoState.SUCCEEDED, branch, sha, outcome.summary());
                } else {
                    transition(runDir, state, repo, RepoState.FAILED, branch, null,
                            outcome.result() + ": " + outcome.summary());
                }
            }
        } finally {
            store.releaseLock(runDir);
        }
        boolean allSucceeded = state.repos().stream().allMatch(r -> r.state() == RepoState.SUCCEEDED);
        return new RunResult(allSucceeded ? 0 : 2, state);
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
