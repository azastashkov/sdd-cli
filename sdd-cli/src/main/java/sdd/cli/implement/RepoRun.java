package sdd.cli.implement;

/** One repo's status within a run. branch/checkpointSha are null until set. */
public record RepoRun(String repo, RepoState state, String branch, String checkpointSha, String detail) {
}
