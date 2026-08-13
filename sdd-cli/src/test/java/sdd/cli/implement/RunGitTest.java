package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.testing.FixtureRepo;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RunGitTest {
    @TempDir Path tmp;

    @Test
    void branchesCommitsAndResets() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();

        RunGit.startBranch(repo.path(), "sdd/RUN/lib", base);
        Files.writeString(repo.path().resolve("A.java"), "class A { int x; }\n");
        String checkpoint = RunGit.commitAll(repo.path(), "sdd: RUN lib");

        assertThat(checkpoint).isNotEqualTo(base);
        assertThat(RunGit.head(repo.path())).isEqualTo(checkpoint);
        assertThat(RunGit.isClean(repo.path())).isTrue();
        assertThat(Files.readString(repo.path().resolve("A.java"))).contains("int x;");

        RunGit.resetHard(repo.path(), base);
        assertThat(RunGit.head(repo.path())).isEqualTo(base);
        assertThat(Files.readString(repo.path().resolve("A.java"))).isEqualTo("class A {}\n");
    }

    @Test
    void startBranchOnAnExistingBranchResetsItToBase() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), "sdd/RUN/lib", base);
        Files.writeString(repo.path().resolve("A.java"), "class A { int z; }\n");
        RunGit.commitAll(repo.path(), "first");   // branch now ahead (real change, not an empty commit)

        RunGit.startBranch(repo.path(), "sdd/RUN/lib", base);   // re-entry hard-resets to base

        assertThat(RunGit.head(repo.path())).isEqualTo(base);
        assertThat(Files.readString(repo.path().resolve("A.java"))).isEqualTo("class A {}\n");
    }

    @Test
    void startBranchRemovesUntrackedFilesFromAPriorAttempt() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");   // NB: RunGitTest's @TempDir field is named tmp
        RunGit.startBranch(repo.path(), "sdd/run/lib", repo.headSha());
        Files.writeString(repo.path().resolve("Debris.java"), "class Debris {}\n");   // agent-created, never committed
        Files.createDirectories(repo.path().resolve("build/tmp"));

        RunGit.startBranch(repo.path(), "sdd/run/lib", repo.headSha());

        assertThat(Files.exists(repo.path().resolve("Debris.java"))).isFalse();
        assertThat(Files.exists(repo.path().resolve("build"))).isFalse();
    }

    @Test
    void diffAndDiffStatBetweenTwoCommits() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        repo.file("A.java", "class A { int x; }\n").file("B.java", "class B {}\n").commit("work");
        String head = repo.headSha();

        String diff = RunGit.diff(repo.path(), base, head);
        RunGit.DiffStat stat = RunGit.diffStat(repo.path(), base, head);

        assertThat(diff).contains("A.java").contains("B.java").contains("+class B {}");
        assertThat(stat.filesChanged()).isEqualTo(2);
        assertThat(stat.insertions()).isGreaterThan(0);
        assertThat(RunGit.diff(repo.path(), base, base)).isEmpty();
    }

    @Test
    void checkoutSwitchesBranchWithoutDestroyingWork() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String main = RunGit.currentBranch(repo.path());
        RunGit.startBranch(repo.path(), "sdd/run/lib", repo.headSha());
        java.nio.file.Files.writeString(repo.path().resolve("A.java"), "class A { int y; }\n");
        RunGit.commitAll(repo.path(), "checkpoint");
        String checkpoint = repo.headSha();

        RunGit.checkout(repo.path(), main);
        assertThat(RunGit.currentBranch(repo.path())).isEqualTo(main);
        RunGit.checkout(repo.path(), "sdd/run/lib");

        assertThat(RunGit.currentBranch(repo.path())).isEqualTo("sdd/run/lib");
        assertThat(RunGit.head(repo.path())).isEqualTo(checkpoint);   // work intact, not reset
        assertThat(java.nio.file.Files.readString(repo.path().resolve("A.java"))).contains("int y;");
    }
}
