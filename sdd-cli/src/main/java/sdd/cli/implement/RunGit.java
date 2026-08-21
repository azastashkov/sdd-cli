package sdd.cli.implement;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import sdd.core.git.GitRead;

import java.nio.file.Path;
import java.util.List;

/**
 * The orchestrator's write-capable git facade (design: orchestrator owns git via JGit —
 * branch/checkout/add/commit/reset-to-recorded-SHA only, never push/remote). LiveGit reads;
 * this writes.
 *
 * <p><b>The reads now live in {@link GitRead}.</b> {@link #diff}, {@link #diffStat},
 * {@link #commitsBetween} and {@link #isAncestor} delegate there rather than keeping a second copy
 * of the tree-parser idiom, which {@code sdd explore}'s {@code git_history} tool also needs. They
 * pass {@code detectRenames=false} deliberately: the model-facing reads detect renames, but
 * counting a rename once instead of as an add plus a delete would change the file counts in Gate
 * 2's report.md, and that is a reporting change, not a refactor.
 *
 * <p><b>Still push-free (design amendment 2026-08-16).</b> Task 5 gives Gate 2 a Bitbucket pull
 * request, which needs a push — that push verb lives on {@code sdd.cli.review.RemoteGit}, reachable
 * only from Gate-2 code paths, NOT here. This class deliberately gains no push method: the point of
 * the original invariant was never "no class in this codebase may push", it was "the agent loop
 * (`sdd implement`) cannot reach the network" — and that property only holds while the orchestrator's
 * OWN git facade, the one the agent's tool-call surface routes every git operation through, stays
 * push-free. See the design doc's dated amendment for the full rationale.
 */
public final class RunGit {
    private static final PersonIdent IDENT = new PersonIdent("sdd", "sdd@local");

    private RunGit() {
    }

    public static String head(Path repo) {
        try (Git git = Git.open(repo.toFile())) {
            var id = git.getRepository().resolve("HEAD");
            return id == null ? "" : id.name();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read HEAD of " + repo + ": " + e.getMessage(), e);
        }
    }

    public static boolean isClean(Path repo) {
        try (Git git = Git.open(repo.toFile())) {
            return git.status().call().isClean();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read status of " + repo + ": " + e.getMessage(), e);
        }
    }

    /** Create the branch off baseSha and check it out; if it already exists, check out and hard-reset it to base. */
    public static void startBranch(Path repo, String branch, String baseSha) {
        try (Git git = Git.open(repo.toFile())) {
            boolean exists = git.branchList().call().stream()
                    .map(Ref::getName).anyMatch(("refs/heads/" + branch)::equals);
            if (exists) {
                git.checkout().setName(branch).call();
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(baseSha).call();
            } else {
                git.checkout().setCreateBranch(true).setName(branch).setStartPoint(baseSha).call();
            }
            git.clean().setCleanDirectories(true).setForce(true).call();
        } catch (Exception e) {
            throw new IllegalStateException("cannot start branch " + branch + " in " + repo + ": "
                    + e.getMessage(), e);
        }
    }

    /** Stage everything (including deletions) and commit; returns the new commit SHA. */
    public static String commitAll(Path repo, String message) {
        try (Git git = Git.open(repo.toFile())) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();   // stage deletions
            return git.commit().setMessage(message).setAuthor(IDENT).setCommitter(IDENT).call().getName();
        } catch (Exception e) {
            throw new IllegalStateException("cannot commit in " + repo + ": " + e.getMessage(), e);
        }
    }

    /** HEAD of refs/heads/<branch>, or "" if the branch does not exist. */
    public static String branchHead(Path repo, String branch) {
        try (Git git = Git.open(repo.toFile())) {
            var id = git.getRepository().resolve("refs/heads/" + branch);
            return id == null ? "" : id.name();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read branch " + branch + " of " + repo + ": "
                    + e.getMessage(), e);
        }
    }

    /** Whether {@code branch}'s current HEAD is still the recorded checkpoint — both nullable
     *  (a repo that never ran, or ran but never reached a checkpoint, has neither). */
    public static boolean isAtCheckpoint(Path repo, String branch, String checkpointSha) {
        if (branch == null || checkpointSha == null) {
            return false;
        }
        return branchHead(repo, branch).equals(checkpointSha);
    }

    /** How many commits {@code toSha} is ahead of {@code fromSha} — the number of checkpoint
     *  commits an approve collapses, and the idempotence check that tells "already squashed" apart
     *  from "has a real range to squash". */
    public static int commitsBetween(Path repo, String fromSha, String toSha) {
        return GitRead.commitsBetween(repo, fromSha, toSha);
    }

    public static void resetHard(Path repo, String sha) {
        try (Git git = Git.open(repo.toFile())) {
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(sha).call();
        } catch (Exception e) {
            throw new IllegalStateException("cannot reset " + repo + " to " + sha + ": " + e.getMessage(), e);
        }
    }

    /** Per-file line counts for a commit-to-commit diff (Gate-2 report input). */
    public record DiffStat(int filesChanged, int insertions, int deletions) {
    }

    /** Unified diff between two commits; empty when the trees are identical. */
    public static String diff(Path repo, String fromSha, String toSha) {
        return GitRead.diffText(repo, fromSha, toSha, null, false);
    }

    public static DiffStat diffStat(Path repo, String fromSha, String toSha) {
        int insertions = 0;
        int deletions = 0;
        List<GitRead.FileChange> changes = GitRead.diffFiles(repo, fromSha, toSha, null, false);
        for (GitRead.FileChange change : changes) {
            insertions += change.insertions();
            deletions += change.deletions();
        }
        return new DiffStat(changes.size(), insertions, deletions);
    }

    /** The checked-out branch, or "" when detached. */
    public static String currentBranch(Path repo) {
        try (Git git = Git.open(repo.toFile())) {
            String branch = git.getRepository().getBranch();
            return branch == null || ObjectId.isId(branch) ? "" : branch;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read branch of " + repo + ": " + e.getMessage(), e);
        }
    }

    /** Collapse everything on {@code branch} since {@code baseSha} into ONE commit. A soft reset
     *  leaves the checkpoint tree staged, so committing without any {@code add} reproduces that
     *  tree exactly — deletions included. Adding from the working tree would sweep in whatever the
     *  human happens to have lying around, so we deliberately do not. Never touches another branch
     *  and never pushes (design line 58). */
    public static String squashOnto(Path repo, String branch, String baseSha, String message) {
        try (Git git = Git.open(repo.toFile())) {
            String head = branchHead(repo, branch);   // BEFORE the reset — see the net-zero case
            git.checkout().setName(branch).call();
            var repository = git.getRepository();
            var headTree = repository.parseCommit(repository.resolve(head)).getTree();
            var baseTree = repository.parseCommit(repository.resolve(baseSha)).getTree();
            if (headTree.equals(baseTree)) {
                return head;   // nothing to squash; leave the branch exactly where it is
            }
            git.reset().setMode(ResetCommand.ResetType.SOFT).setRef(baseSha).call();
            return git.commit().setMessage(message).setAuthor(IDENT).setCommitter(IDENT)
                    .call().getName();
        } catch (Exception e) {
            throw new IllegalStateException("cannot squash " + branch + " in " + repo + ": "
                    + e.getMessage(), e);
        }
    }

    /** Plain checkout — deliberately NO reset and NO clean (unlike startBranch), so review can
     *  visit a checkpoint and return the estate exactly as it found it. */
    public static void checkout(Path repo, String branch) {
        try (Git git = Git.open(repo.toFile())) {
            git.checkout().setName(branch).call();
        } catch (Exception e) {
            throw new IllegalStateException("cannot checkout " + branch + " in " + repo + ": "
                    + e.getMessage(), e);
        }
    }

    /** Deletes {@code branch}; a no-op when it does not exist (JGit's branchDelete returns an empty
     *  list rather than throwing). Force so an unmerged run branch — the normal case for a rejected
     *  or redone repo — still deletes; force only bypasses the merged-ness check, NOT the "this is
     *  the currently checked out branch" guard: JGit throws CannotDeleteCurrentBranchException
     *  regardless of force, so a caller must check the repo out onto something else first whenever
     *  {@link #currentBranch} equals {@code branch} (the common case — Orchestrator never restores a
     *  repo's original branch after {@code sdd implement}). */
    /** Whether {@code ancestorSha} is an ancestor of (or equal to) {@code descendantSha} — Task 5's
     *  base-ancestry check: a plan's {@code base_sha} that is NOT an ancestor of the Bitbucket
     *  default branch's head means the pull request {@code sdd review} is about to open would show
     *  unrelated commits, which the caller must warn about rather than open silently. A pure read,
     *  so it lives here alongside the other read helpers ({@link #branchHead}, {@link #diff}) rather
     *  than on {@code RemoteGit} — that class exists to isolate the ONE write verb (push) the design
     *  amendment above is about, not every git operation Task 5 happens to need. */
    public static boolean isAncestor(Path repo, String ancestorSha, String descendantSha) {
        return GitRead.isAncestor(repo, ancestorSha, descendantSha);
    }

    public static void deleteBranch(Path repo, String branch) {
        try (Git git = Git.open(repo.toFile())) {
            git.branchDelete().setBranchNames(branch).setForce(true).call();
        } catch (Exception e) {
            throw new IllegalStateException("cannot delete branch " + branch + " in " + repo + ": "
                    + e.getMessage(), e);
        }
    }
}
