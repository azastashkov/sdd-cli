package sdd.cli.implement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mutable per-run status, seeded PENDING for every repo, in execution order. */
public final class RunState {
    private final String runId;
    private final Map<String, RepoRun> repos = new LinkedHashMap<>();
    private String pausedReason;   // null while the run is live; set exactly once, at the pause site
    private long tokensSpent;

    public RunState(String runId, List<String> repoNames) {
        this.runId = runId;
        for (String repo : repoNames) {
            repos.put(repo, new RepoRun(repo, RepoState.PENDING, null, null, "", null));
        }
    }

    public RunState(String runId, List<RepoRun> repos, String pausedReason, long tokensSpent) {
        this.runId = runId;
        for (RepoRun repo : repos) {
            this.repos.put(repo.repo(), repo);
        }
        this.pausedReason = pausedReason;
        this.tokensSpent = tokensSpent;
    }

    public String runId() {
        return runId;
    }

    public void pause(String reason) {
        this.pausedReason = reason;
    }

    public String pausedReason() {
        return pausedReason;
    }

    public void addTokens(long tokens) {
        this.tokensSpent += tokens;
    }

    public long tokensSpent() {
        return tokensSpent;
    }

    public void set(String repo, RepoState state, String branch, String checkpointSha, String detail) {
        set(repo, state, branch, checkpointSha, detail, null);
    }

    /** Like {@link #set(String, RepoState, String, String, String)}, but also records the
     *  {@code StepResult} name of the repo's final agent attempt (null for SUCCESS or a repo that
     *  never ran) — see {@link RepoRun#failureCode()}.
     *
     *  <p>Carries forward this repo's previously-recorded {@code prId}/{@code prUrl} (Task 5)
     *  rather than nulling them out: every call site of this overload — the orchestrator's own
     *  state transitions, {@code approve}'s post-squash checkpoint write-back, {@code Resume}'s
     *  reconciliation — knows nothing about Bitbucket and must not be made to thread a PR through
     *  just to avoid erasing one a prior {@code sdd review} opened. Only
     *  {@link #set(String, RepoState, String, String, String, String, Integer, String)} — called
     *  from the Bitbucket integration itself — ever changes these two fields. */
    public void set(String repo, RepoState state, String branch, String checkpointSha, String detail,
                    String failureCode) {
        RepoRun previous = repos.get(repo);
        Integer prId = previous == null ? null : previous.prId();
        String prUrl = previous == null ? null : previous.prUrl();
        repos.put(repo, new RepoRun(repo, state, branch, checkpointSha, detail, failureCode, prId, prUrl));
    }

    /** Like {@link #set(String, RepoState, String, String, String, String)}, but also (over)writes
     *  the Bitbucket pull request recorded for this repo — the one overload the Task 5 write path
     *  (opening/updating a PR) uses; every other caller uses the six-argument overload above, which
     *  preserves whatever this one last wrote. */
    public void set(String repo, RepoState state, String branch, String checkpointSha, String detail,
                    String failureCode, Integer prId, String prUrl) {
        repos.put(repo, new RepoRun(repo, state, branch, checkpointSha, detail, failureCode, prId, prUrl));
    }

    public RepoState stateOf(String repo) {
        RepoRun run = repos.get(repo);
        return run == null ? null : run.state();
    }

    public List<RepoRun> repos() {
        return List.copyOf(repos.values());
    }
}
