package sdd.cli.review;

import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RunGit;

import java.nio.file.Path;

/**
 * Gate-2 "approve" for a single repo (design line 68): collapse its run branch — a series of
 * checkpoint commits — into the ONE commit a human actually reviewed, carrying an {@code Sdd-Run}
 * trailer back to the run that produced it. Unlike {@code sdd implement}, which relies on
 * {@code PreFlight} gating {@code isClean} before it ever touches a repo, {@code sdd review} runs
 * against a live checkout at an arbitrary time — the repo could be dirty, or the branch could have
 * moved since the run recorded its checkpoint — so both preconditions are re-checked here rather
 * than assumed. The repo's original branch (or detached commit) is restored whatever the outcome,
 * mirroring {@link RebuildPass} — and, like {@link RebuildPass}, a FAILED restore is reported as
 * data ({@link Result#restoreFailure}) rather than thrown: throwing out of a {@code finally} would
 * replace the return value, so the caller would never learn that the squash succeeded and could
 * never write the new checkpoint sha back into {@code state.json}.
 */
public final class SquashApprove {
    private SquashApprove() {
    }

    /** {@code applied=false} means the repo was refused and left completely untouched — {@code
     *  message} carries the reason. {@code squashed=false} with {@code applied=true} means no new
     *  commit was created — either the branch already was a single commit since {@code baseSha},
     *  or its net delta over {@code baseSha} was empty (a revert, or bump-then-unbump) — so {@code
     *  sha} is simply the existing head. The approval itself is still legitimate in both cases;
     *  only the squash was a no-op.
     *
     *  <p>{@code restoreFailure} is null when the repo was put back where {@code approve} found it,
     *  and otherwise a {@code "<repo>: <reason>"} line — {@link RebuildPass.Outcome#restoreFailures}'
     *  exact shape, so callers can hand it to the report and the exit code the same way. It is
     *  independent of the other three components: a restore can fail after a real squash, after a
     *  no-op squash, or after a refusal. */
    public record Result(boolean applied, boolean squashed, String sha, String message,
                         String restoreFailure) {
        private static Result refused(String message) {
            return new Result(false, false, null, message, null);
        }

        private Result withRestoreFailure(String failure) {
            return failure == null ? this
                    : new Result(applied, squashed, sha, message, failure);
        }
    }

    public static Result approve(Path repoRoot, String repo, String runId, String specId,
                                 RepoRun run, String baseSha) {
        // Recorded up front, before either precondition check, so the restore can put the repo
        // back exactly where it found it no matter which branch below returns.
        String currentBranch = RunGit.currentBranch(repoRoot);
        String original = currentBranch.isEmpty() ? "detached:" + RunGit.head(repoRoot) : currentBranch;
        Result result;
        try {
            result = squash(repoRoot, repo, runId, specId, run, baseSha);
        } catch (RuntimeException | Error e) {
            // Error is caught alongside RuntimeException only to keep exactly what the old
            // finally covered: the restore is unconditional, whatever went wrong above. Nothing
            // was decided, so there is no Result worth preserving — restore and let the original
            // failure propagate, carrying any restore failure alongside it, never in place of it.
            String failure = restore(repoRoot, repo, original);
            if (failure != null) {
                e.addSuppressed(new IllegalStateException(failure));
            }
            throw e;
        }
        return result.withRestoreFailure(restore(repoRoot, repo, original));
    }

    /** Puts the repo back where {@link #approve} found it. Returns null on success, or the
     *  {@code "<repo>: <reason>"} line to carry on the {@link Result}; never throws, so it cannot
     *  destroy the outcome of a squash that already happened. */
    private static String restore(Path repoRoot, String repo, String original) {
        String target = original.startsWith("detached:")
                ? original.substring("detached:".length()) : original;
        try {
            RunGit.checkout(repoRoot, target);
            return null;
        } catch (RuntimeException e) {
            return repo + ": " + e.getMessage();
        }
    }

    private static Result squash(Path repoRoot, String repo, String runId, String specId,
                                 RepoRun run, String baseSha) {
        if (!RunGit.isClean(repoRoot)) {
            return Result.refused(repo + " has uncommitted changes; commit or stash them before approving");
        }
        if (!RunGit.isAtCheckpoint(repoRoot, run.branch(), run.checkpointSha())) {
            return Result.refused(repo + " branch " + run.branch() + " is no longer at its checkpoint "
                    + Shas.shortSha(run.checkpointSha()));
        }
        // Idempotence lives here, not in squashOnto: a second squash of a real delta would
        // legitimately mint a fresh sha, so "already squashed" must be caught by the commit
        // count rather than by asking squashOnto to be a no-op on repeat calls.
        int commits = RunGit.commitsBetween(repoRoot, baseSha, run.checkpointSha());
        if (commits <= 1) {
            return new Result(true, false, run.checkpointSha(), repo + " is already "
                    + (commits == 0 ? "at " : "a single commit past ") + Shas.shortSha(baseSha)
                    + "; nothing to squash", null);
        }
        String message = "sdd: " + repo + " for " + specId + "\n\n"
                + "Squashed " + commits + " checkpoint commit(s) from run " + runId + ".\n\n"
                + "Sdd-Run: " + runId + "\n";
        String headBefore = run.checkpointSha();   // isAtCheckpoint above proved this == the branch head
        String sha = RunGit.squashOnto(repoRoot, run.branch(), baseSha, message);
        if (sha.equals(headBefore)) {
            // squashOnto's own net-zero short-circuit fired: the checkpoint tree equals base,
            // so it left the branch exactly where it was and minted no commit. Reporting
            // squashed=true here would tell the human (and Task 4's state.json rewrite) that a
            // squash happened when it did not — the existing head's message is still the last
            // checkpoint's, not this one.
            return new Result(true, false, sha, repo + " had no net change since " + Shas.shortSha(baseSha)
                    + "; branch left at its existing head " + Shas.shortSha(sha), null);
        }
        return new Result(true, true, sha, message, null);
    }
}
