package sdd.cli.implement;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;

import java.nio.file.Path;

/**
 * The orchestrator's write-capable git facade (design: orchestrator owns git via JGit —
 * branch/checkout/add/commit/reset-to-recorded-SHA only, never push/remote). LiveGit reads;
 * this writes.
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

    public static void resetHard(Path repo, String sha) {
        try (Git git = Git.open(repo.toFile())) {
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(sha).call();
        } catch (Exception e) {
            throw new IllegalStateException("cannot reset " + repo + " to " + sha + ": " + e.getMessage(), e);
        }
    }
}
