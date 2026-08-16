package sdd.cli.implement;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One repo's status within a run. branch/checkpointSha are null until set. failureCode is the
 *  {@code StepResult} name of the repo's final agent attempt — null for a SUCCEEDED repo and for
 *  any repo that never ran (PENDING, or skipped by an upstream cascade). Serialized as
 *  {@code failure_code}; a pre-5C-2 {@code state.json} with no such key reads it as null.
 *
 *  <p>{@code prId}/{@code prUrl} (Task 5) are the Bitbucket pull request {@code sdd review} opened
 *  for this repo's run branch, null until it does. Recorded here — not re-derived — so the
 *  Gate-2 decision commands ({@code review approve/reject}) can merge/decline the right PR without
 *  re-querying Bitbucket by source branch on every invocation. Serialized as {@code pr_id}/
 *  {@code pr_url}; a pre-Task-5 {@code state.json} with neither key reads both as null. */
public record RepoRun(String repo, RepoState state, String branch, String checkpointSha, String detail,
                       @JsonProperty("failure_code") String failureCode,
                       @JsonProperty("pr_id") Integer prId, @JsonProperty("pr_url") String prUrl) {

    /** Pre-Task-5 six-argument shape, kept so every existing construction site (main and test)
     *  keeps compiling untouched: {@code prId}/{@code prUrl} default to null, i.e. no pull request
     *  recorded — the state of every repo before Task 5's Bitbucket integration existed at all. */
    public RepoRun(String repo, RepoState state, String branch, String checkpointSha, String detail,
                   String failureCode) {
        this(repo, state, branch, checkpointSha, detail, failureCode, null, null);
    }
}
