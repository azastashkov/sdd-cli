package sdd.cli.review;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.core.testing.FixtureRepo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

class SquashApproveTest {
    @TempDir Path tmp;

    private static int commitsSince(Path repo, String base) throws Exception {
        try (Git git = Git.open(repo.toFile())) {
            var from = git.getRepository().resolve(base);
            var to = git.getRepository().resolve("HEAD");
            int n = 0;
            for (var ignored : git.log().addRange(from, to).call()) {
                n++;
            }
            return n;
        }
    }

    private static RepoRun runOn(Path repo, String branch) {
        return new RepoRun("lib", RepoState.SUCCEEDED, branch, RunGit.branchHead(repo, branch), "", null);
    }

    @Test
    void collapsesEveryCheckpointCommitIntoOneTrailerCarryingCommit() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), "sdd/S-v1/lib", base);
        Files.writeString(repo.path().resolve("A.java"), "class A { int x; }\n");
        RunGit.commitAll(repo.path(), "sdd: first");
        Files.writeString(repo.path().resolve("B.java"), "class B {}\n");
        RunGit.commitAll(repo.path(), "sdd: second");

        SquashApprove.Result result = SquashApprove.approve(repo.path(), "lib", "S-v1", "SPEC-9",
                runOn(repo.path(), "sdd/S-v1/lib"), base);

        assertThat(result.applied()).isTrue();
        assertThat(result.squashed()).isTrue();
        assertThat(result.message()).contains("Sdd-Run: S-v1").contains("sdd: lib for SPEC-9");
        assertThat(commitsSince(repo.path(), base)).isEqualTo(1);
        assertThat(RunGit.diffStat(repo.path(), base, result.sha()).filesChanged()).isEqualTo(2);
        assertThat(Files.readString(repo.path().resolve("A.java"))).contains("int x;");
        assertThat(Files.readString(repo.path().resolve("B.java"))).contains("class B");
    }

    @Test
    void aDeletionInACheckpointSurvivesTheSquash() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib")
                .file("A.java", "class A {}\n").file("Gone.java", "class Gone {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), "sdd/S-v1/lib", base);
        Files.delete(repo.path().resolve("Gone.java"));
        RunGit.commitAll(repo.path(), "sdd: drop it");

        SquashApprove.approve(repo.path(), "lib", "S-v1", "SPEC-9",
                runOn(repo.path(), "sdd/S-v1/lib"), base);

        assertThat(repo.path().resolve("Gone.java")).doesNotExist();
    }

    @Test
    void aNetZeroCheckpointRangeKeepsItsHeadInsteadOfCollapsingToBase() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), "sdd/S-v1/lib", base);
        Files.writeString(repo.path().resolve("A.java"), "class A { int x; }\n");
        RunGit.commitAll(repo.path(), "sdd: change");
        Files.writeString(repo.path().resolve("A.java"), "class A {}\n");
        RunGit.commitAll(repo.path(), "sdd: revert it");
        String head = RunGit.branchHead(repo.path(), "sdd/S-v1/lib");

        SquashApprove.Result result = SquashApprove.approve(repo.path(), "lib", "S-v1", "SPEC-9",
                runOn(repo.path(), "sdd/S-v1/lib"), base);

        assertThat(result.sha()).isEqualTo(head);                                 // not base
        assertThat(result.squashed()).isFalse();                                 // no commit was created
        assertThat(result.message()).doesNotContain("Squashed");
        assertThat(RunGit.branchHead(repo.path(), "sdd/S-v1/lib")).isEqualTo(head);
        assertThat(commitsSince(repo.path(), base)).isEqualTo(2);                 // history intact
    }

    @Test
    void aDirtyWorkingTreeIsRefusedRatherThanSweptIntoTheApprovedCommit() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), "sdd/S-v1/lib", base);
        Files.writeString(repo.path().resolve("A.java"), "class A { int x; }\n");
        RunGit.commitAll(repo.path(), "sdd: change");
        RepoRun run = runOn(repo.path(), "sdd/S-v1/lib");
        Files.writeString(repo.path().resolve("scratch.txt"), "my notes\n");   // untracked junk

        SquashApprove.Result result = SquashApprove.approve(repo.path(), "lib", "S-v1", "SPEC-9",
                run, base);

        assertThat(result.applied()).isFalse();
        assertThat(result.message()).contains("uncommitted");
        assertThat(commitsSince(repo.path(), base)).isEqualTo(1);   // untouched
        assertThat(Files.readString(repo.path().resolve("scratch.txt"))).isEqualTo("my notes\n");
    }

    /**
     * The lever: JGit's {@code commit} runs the repo's {@code post-commit} hook, which is the only
     * point INSIDE {@code approve} at which a test can act — here it removes the branch the human
     * was standing on, simulating anything (another process, a colleague's {@code git branch -D})
     * that makes the restore checkout impossible after the squash has already happened.
     */
    private static void deleteBranchOnNextCommit(Path repo, String branch) throws Exception {
        Path hooks = repo.resolve(".git/hooks");
        Files.createDirectories(hooks);
        Path hook = hooks.resolve("post-commit");
        Files.writeString(hook, "#!/bin/sh\nrm -f .git/refs/heads/" + branch + "\n");
        Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void aFailedRestoreIsCarriedOnTheResultInsteadOfThrownOutOfTheFinally() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        String original = RunGit.currentBranch(repo.path());   // "main"
        RunGit.startBranch(repo.path(), "sdd/S-v1/lib", base);
        Files.writeString(repo.path().resolve("A.java"), "class A { int x; }\n");
        RunGit.commitAll(repo.path(), "sdd: first");
        Files.writeString(repo.path().resolve("B.java"), "class B {}\n");
        RunGit.commitAll(repo.path(), "sdd: second");
        RunGit.checkout(repo.path(), original);
        deleteBranchOnNextCommit(repo.path(), original);

        // Throwing here would replace the return value: the caller never learns that the squash
        // succeeded, so it can never write the new sha back into state.json.
        SquashApprove.Result result = SquashApprove.approve(repo.path(), "lib", "S-v1", "SPEC-9",
                runOn(repo.path(), "sdd/S-v1/lib"), base);

        assertThat(result.applied()).isTrue();
        assertThat(result.squashed()).isTrue();
        assertThat(result.sha()).isEqualTo(RunGit.branchHead(repo.path(), "sdd/S-v1/lib"));
        assertThat(commitsSince(repo.path(), base)).isEqualTo(1);   // the squash really happened
        // Reported in RebuildPass's "<repo>: <reason>" shape so the report and the exit code can
        // treat it exactly like an estate rebuild's restore failure.
        assertThat(result.restoreFailure()).startsWith("lib: ").contains("cannot checkout " + original);
    }

    @Test
    void aSuccessfulRestoreLeavesNoRestoreFailureOnTheResult() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        String original = RunGit.currentBranch(repo.path());
        RunGit.startBranch(repo.path(), "sdd/S-v1/lib", base);
        Files.writeString(repo.path().resolve("A.java"), "class A { int x; }\n");
        RunGit.commitAll(repo.path(), "sdd: first");
        Files.writeString(repo.path().resolve("B.java"), "class B {}\n");
        RunGit.commitAll(repo.path(), "sdd: second");
        RunGit.checkout(repo.path(), original);

        SquashApprove.Result result = SquashApprove.approve(repo.path(), "lib", "S-v1", "SPEC-9",
                runOn(repo.path(), "sdd/S-v1/lib"), base);

        assertThat(result.restoreFailure()).isNull();
        assertThat(RunGit.currentBranch(repo.path())).isEqualTo(original);
    }

    @Test
    void aBranchThatMovedOffItsCheckpointIsRefused() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), "sdd/S-v1/lib", base);
        Files.writeString(repo.path().resolve("A.java"), "class A { int x; }\n");
        RunGit.commitAll(repo.path(), "sdd: change");
        RepoRun stale = new RepoRun("lib", RepoState.SUCCEEDED, "sdd/S-v1/lib", base, "", null);

        SquashApprove.Result result = SquashApprove.approve(repo.path(), "lib", "S-v1", "SPEC-9",
                stale, base);

        assertThat(result.applied()).isFalse();
        assertThat(result.message()).contains("checkpoint");
    }
}
