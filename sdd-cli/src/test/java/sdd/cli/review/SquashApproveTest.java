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
        return new RepoRun("lib", RepoState.SUCCEEDED, branch, RunGit.branchHead(repo, branch), "");
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

    @Test
    void aBranchThatMovedOffItsCheckpointIsRefused() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), "sdd/S-v1/lib", base);
        Files.writeString(repo.path().resolve("A.java"), "class A { int x; }\n");
        RunGit.commitAll(repo.path(), "sdd: change");
        RepoRun stale = new RepoRun("lib", RepoState.SUCCEEDED, "sdd/S-v1/lib", base, "");

        SquashApprove.Result result = SquashApprove.approve(repo.path(), "lib", "S-v1", "SPEC-9",
                stale, base);

        assertThat(result.applied()).isFalse();
        assertThat(result.message()).contains("checkpoint");
    }
}
