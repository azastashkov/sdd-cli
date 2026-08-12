package sdd.plan.approve;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import java.nio.file.Path;

/** Live git facts for approve-time staleness checks — mirrors WorkspaceScanner's reads. */
public final class LiveGit {

    public record State(String head, boolean clean) {
    }

    private LiveGit() {
    }

    public static State state(Path repoDir) {
        try (Git git = Git.open(repoDir.toFile())) {
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve("HEAD");
            String headSha = head == null ? "" : head.name();
            boolean clean = git.status().call().isClean();
            return new State(headSha, clean);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "cannot read git state of " + repoDir + ": " + e.getMessage(), e);
        }
    }
}
