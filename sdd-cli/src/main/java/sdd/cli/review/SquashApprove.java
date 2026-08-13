package sdd.cli.review;

import org.eclipse.jgit.api.Git;
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
 * than assumed. The repo's original branch (or detached commit) is restored in a {@code finally}
 * regardless of outcome, mirroring {@code ReviewCommand.runRebuild}.
 */
public final class SquashApprove {
    private SquashApprove() {
    }

    /** {@code applied=false} means the repo was refused and left completely untouched — {@code
     *  message} carries the reason. {@code squashed=false} with {@code applied=true} means the
     *  branch already was (or already collapsed to) a single commit since {@code baseSha}, so
     *  {@code sha} is simply the existing head — re-approving an already-squashed branch is a
     *  no-op, not an error. */
    public record Result(boolean applied, boolean squashed, String sha, String message) {
        private static Result refused(String message) {
            return new Result(false, false, null, message);
        }
    }

    public static Result approve(Path repoRoot, String repo, String runId, String specId,
                                 RepoRun run, String baseSha) {
        // Recorded up front, before either precondition check, so the finally can put the repo
        // back exactly where it found it no matter which branch below returns.
        String currentBranch = RunGit.currentBranch(repoRoot);
        String original = currentBranch.isEmpty() ? "detached:" + RunGit.head(repoRoot) : currentBranch;
        try {
            if (!RunGit.isClean(repoRoot)) {
                return Result.refused(repo + " has uncommitted changes; commit or stash them before approving");
            }
            if (!RunGit.isAtCheckpoint(repoRoot, run.branch(), run.checkpointSha())) {
                return Result.refused(repo + " branch " + run.branch() + " is no longer at its checkpoint "
                        + shortSha(run.checkpointSha()));
            }
            // Idempotence lives here, not in squashOnto: a second squash of a real delta would
            // legitimately mint a fresh sha, so "already squashed" must be caught by the commit
            // count rather than by asking squashOnto to be a no-op on repeat calls.
            int commits = commitsBetween(repoRoot, baseSha, run.checkpointSha());
            if (commits <= 1) {
                return new Result(true, false, run.checkpointSha(),
                        repo + " is already a single commit since " + shortSha(baseSha) + "; nothing to squash");
            }
            String message = "sdd: " + repo + " for " + specId + "\n\n"
                    + "Squashed " + commits + " checkpoint commit(s) from run " + runId + ".\n\n"
                    + "Sdd-Run: " + runId + "\n";
            String sha = RunGit.squashOnto(repoRoot, run.branch(), baseSha, message);
            return new Result(true, true, sha, message);
        } finally {
            String target = original.startsWith("detached:")
                    ? original.substring("detached:".length()) : original;
            RunGit.checkout(repoRoot, target);
        }
    }

    private static int commitsBetween(Path repo, String fromSha, String toSha) {
        try (Git git = Git.open(repo.toFile())) {
            var repository = git.getRepository();
            var from = repository.resolve(fromSha);
            var to = repository.resolve(toSha);
            int n = 0;
            for (var ignored : git.log().addRange(from, to).call()) {
                n++;
            }
            return n;
        } catch (Exception e) {
            throw new IllegalStateException("cannot count commits in " + repo + ": " + e.getMessage(), e);
        }
    }

    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(7, sha.length()));
    }
}
