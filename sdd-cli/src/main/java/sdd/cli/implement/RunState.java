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
            repos.put(repo, new RepoRun(repo, RepoState.PENDING, null, null, ""));
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
        repos.put(repo, new RepoRun(repo, state, branch, checkpointSha, detail));
    }

    public RepoState stateOf(String repo) {
        RepoRun run = repos.get(repo);
        return run == null ? null : run.state();
    }

    public List<RepoRun> repos() {
        return List.copyOf(repos.values());
    }
}
