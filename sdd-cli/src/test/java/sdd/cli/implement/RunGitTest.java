package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.testing.FixtureRepo;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void squashOntoNeverAddsFromTheWorkingTreeEvenWhenItIsDirty() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), "sdd/S-v1/lib", base);
        Files.writeString(repo.path().resolve("A.java"), "class A { int x; }\n");
        RunGit.commitAll(repo.path(), "sdd: first");
        Files.writeString(repo.path().resolve("B.java"), "class B {}\n");
        RunGit.commitAll(repo.path(), "sdd: second");
        String checkpoint = RunGit.head(repo.path());

        // Dirty the working tree AFTER the checkpoint: a modified tracked file plus an untracked
        // one. squashOnto is called directly here, bypassing SquashApprove's isClean gate, so a
        // regression to git.add(".") would sweep both into the "squashed" commit's tree.
        Files.writeString(repo.path().resolve("A.java"), "class A { int x; int rogue; }\n");
        Files.writeString(repo.path().resolve("Scratch.java"), "class Scratch {}\n");

        String sha = RunGit.squashOnto(repo.path(), "sdd/S-v1/lib", base, "sdd: lib for SPEC-9");

        assertThat(sha).isNotEqualTo(checkpoint);
        // The squashed commit's tree must equal the checkpoint tree exactly — an empty diff proves
        // neither the working-tree edit to A.java nor the untracked Scratch.java made it in.
        assertThat(RunGit.diff(repo.path(), checkpoint, sha)).isEmpty();
    }

    @Test
    void isAtCheckpointComparesBranchHeadAndToleratesNulls() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), "sdd/S-v1/lib", base);

        assertThat(RunGit.isAtCheckpoint(repo.path(), "sdd/S-v1/lib", base)).isTrue();
        assertThat(RunGit.isAtCheckpoint(repo.path(), "sdd/S-v1/lib", "0000000")).isFalse();
        assertThat(RunGit.isAtCheckpoint(repo.path(), null, base)).isFalse();
        assertThat(RunGit.isAtCheckpoint(repo.path(), "sdd/S-v1/lib", null)).isFalse();
    }

    @Test
    void deleteBranchIsANoOpWhenTheBranchIsAbsent() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");

        assertThatCode(() -> RunGit.deleteBranch(repo.path(), "sdd/never-existed/lib"))
                .doesNotThrowAnyException();
    }

    @Test
    void deleteBranchRemovesANonCurrentBranch() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        String main = RunGit.currentBranch(repo.path());
        RunGit.startBranch(repo.path(), "sdd/run/lib", base);
        RunGit.checkout(repo.path(), main);

        RunGit.deleteBranch(repo.path(), "sdd/run/lib");

        assertThat(RunGit.branchHead(repo.path(), "sdd/run/lib")).isEmpty();
    }

    @Test
    void deleteBranchOnTheCurrentlyCheckedOutBranchThrowsEvenWithForce() throws Exception {
        // Pins the empirically-reproduced JGit 6.10.0 fact the brief calls out: force bypasses only
        // the merged-ness check, not CannotDeleteCurrentBranchException. Callers must check out
        // something else first — RunGit.deleteBranch itself does not do that for them.
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), "sdd/run/lib", base);

        assertThatThrownBy(() -> RunGit.deleteBranch(repo.path(), "sdd/run/lib"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(RunGit.branchHead(repo.path(), "sdd/run/lib")).isNotEmpty();   // untouched
    }

    // --- isAncestor (Task 5: the review's base-ancestry check) --------------------------------

    @Test
    void isAncestorIsTrueWhenTheFirstShaIsAnAncestorOfTheSecond() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        repo.file("A.java", "class A { int x; }\n").commit("second");
        String tip = repo.headSha();

        assertThat(RunGit.isAncestor(repo.path(), base, tip)).isTrue();
    }

    @Test
    void isAncestorIsTrueForAShaComparedToItself() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();

        assertThat(RunGit.isAncestor(repo.path(), base, base)).isTrue();
    }

    @Test
    void isAncestorIsFalseWhenTheHistoriesHaveDiverged() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), "branch-a", base);
        repo.file("A.java", "class A { int a; }\n").commit("on branch-a");
        String tipA = repo.headSha();
        RunGit.startBranch(repo.path(), "branch-b", base);
        repo.file("A.java", "class A { int b; }\n").commit("on branch-b");
        String tipB = repo.headSha();

        assertThat(RunGit.isAncestor(repo.path(), tipA, tipB)).isFalse();
    }
}
