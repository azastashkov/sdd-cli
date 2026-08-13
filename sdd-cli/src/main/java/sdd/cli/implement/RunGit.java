package sdd.cli.implement;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

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

    /** Whether {@code branch}'s current HEAD is still the recorded checkpoint — both nullable
     *  (a repo that never ran, or ran but never reached a checkpoint, has neither). */
    public static boolean isAtCheckpoint(Path repo, String branch, String checkpointSha) {
        if (branch == null || checkpointSha == null) {
            return false;
        }
        return branchHead(repo, branch).equals(checkpointSha);
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
        try (Git git = Git.open(repo.toFile());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Repository repository = git.getRepository();
            try (RevWalk walk = new RevWalk(repository);
                 DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.setRepository(repository);
                formatter.format(entries(repository, walk, formatter, fromSha, toSha));
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot diff " + repo + " " + fromSha + ".." + toSha
                    + ": " + e.getMessage(), e);
        }
    }

    public static DiffStat diffStat(Path repo, String fromSha, String toSha) {
        try (Git git = Git.open(repo.toFile());
             ByteArrayOutputStream sink = new ByteArrayOutputStream()) {
            Repository repository = git.getRepository();
            try (RevWalk walk = new RevWalk(repository);
                 DiffFormatter formatter = new DiffFormatter(sink)) {
                formatter.setRepository(repository);
                List<DiffEntry> entries = entries(repository, walk, formatter, fromSha, toSha);
                int insertions = 0;
                int deletions = 0;
                for (DiffEntry entry : entries) {
                    for (var edit : formatter.toFileHeader(entry).toEditList()) {
                        insertions += edit.getEndB() - edit.getBeginB();
                        deletions += edit.getEndA() - edit.getBeginA();
                    }
                }
                return new DiffStat(entries.size(), insertions, deletions);
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot diffstat " + repo + ": " + e.getMessage(), e);
        }
    }

    private static List<DiffEntry> entries(Repository repository, RevWalk walk,
                                           DiffFormatter formatter, String fromSha, String toSha)
            throws java.io.IOException {
        CanonicalTreeParser from = new CanonicalTreeParser();
        from.reset(walk.getObjectReader(), walk.parseCommit(ObjectId.fromString(fromSha)).getTree());
        CanonicalTreeParser to = new CanonicalTreeParser();
        to.reset(walk.getObjectReader(), walk.parseCommit(ObjectId.fromString(toSha)).getTree());
        return formatter.scan(from, to);
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
}
