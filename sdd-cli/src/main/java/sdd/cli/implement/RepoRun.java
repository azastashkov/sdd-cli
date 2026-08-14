package sdd.cli.implement;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One repo's status within a run. branch/checkpointSha are null until set. failureCode is the
 *  {@code StepResult} name of the repo's final agent attempt — null for a SUCCEEDED repo and for
 *  any repo that never ran (PENDING, or skipped by an upstream cascade). Serialized as
 *  {@code failure_code}; a pre-5C-2 {@code state.json} with no such key reads it as null. */
public record RepoRun(String repo, RepoState state, String branch, String checkpointSha, String detail,
                       @JsonProperty("failure_code") String failureCode) {
}
