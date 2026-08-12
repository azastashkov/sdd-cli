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
}
