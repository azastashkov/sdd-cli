package sdd.core.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.testing.FixtureRepo;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitReadTest {
    @TempDir Path dir;

    private FixtureRepo fixture;
    private Path repo;
    private String first;
    private String second;
    private String third;

    /** main: c1 -> c2 -> c3, with a "release" branch and a v1 tag both sitting at c2. */
    @BeforeEach
    void setUp() {
        fixture = FixtureRepo.in(dir, "r")
                .file("A.java", "class A {}\n")
                .commit("c1 add A", Instant.parse("2026-01-01T00:00:00Z"));
        first = fixture.headSha();

        fixture.file("A.java", "class A { int x; }\n")
                .file("B.java", "class B {}\n")
                .commit("c2 add B and a field", Instant.parse("2026-01-02T00:00:00Z"));
        second = fixture.headSha();
        fixture.tag("v1").branch("release").checkout("main");

        fixture.file("A.java", "class A { int x; int y; }\n")
                .commit("c3 another field", Instant.parse("2026-01-03T00:00:00Z"));
        third = fixture.headSha();
        repo = fixture.path();
    }

    @Test
    void resolveAcceptsShaBranchTagAndRelativeRevisions() {
        assertThat(GitRead.resolve(repo, third)).isEqualTo(third);
        assertThat(GitRead.resolve(repo, "HEAD")).isEqualTo(third);
        assertThat(GitRead.resolve(repo, "HEAD~1")).isEqualTo(second);
        assertThat(GitRead.resolve(repo, "HEAD~2")).isEqualTo(first);
        assertThat(GitRead.resolve(repo, "main")).isEqualTo(third);
        assertThat(GitRead.resolve(repo, "release")).isEqualTo(second);
        assertThat(GitRead.resolve(repo, "v1")).isEqualTo(second);
    }

    /** The whole reason this is not RunGit's ObjectId.fromString helper. */
    @Test
    void anUnknownRevisionSaysSoRatherThanReturningTheDefault() {
        assertThatThrownBy(() -> GitRead.resolve(repo, "no-such-branch"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no such revision")
                .hasMessageContaining("no-such-branch");
    }

    @Test
    void logIsNewestFirstAndHonoursTheLimit() {
        List<GitRead.Commit> all = GitRead.log(repo, "HEAD", null, 10);
        assertThat(all).extracting(GitRead.Commit::sha).containsExactly(third, second, first);
        assertThat(all).extracting(GitRead.Commit::subject)
                .containsExactly("c3 another field", "c2 add B and a field", "c1 add A");
        assertThat(all.get(0).shortSha()).isEqualTo(third.substring(0, GitRead.SHORT_SHA));
        assertThat(all.get(0).author()).isEqualTo("sdd-fixture");
        assertThat(all.get(0).when()).isEqualTo(Instant.parse("2026-01-03T00:00:00Z"));

        assertThat(GitRead.log(repo, "HEAD", null, 2))
                .extracting(GitRead.Commit::sha).containsExactly(third, second);
    }

    @Test
    void aRangeLogYieldsOnlyWhatTheSecondEndAdds() {
        assertThat(GitRead.log(repo, first + ".." + third, null, 10))
                .extracting(GitRead.Commit::sha).containsExactly(third, second);
        assertThat(GitRead.log(repo, "release..main", null, 10))
                .extracting(GitRead.Commit::sha).containsExactly(third);
    }

    @Test
    void logCanBeRestrictedToOnePath() {
        assertThat(GitRead.log(repo, "HEAD", "B.java", 10))
                .extracting(GitRead.Commit::sha).containsExactly(second);
    }

    @Test
    void rangeResolvesBothEndsAndTreatsABareRefAsRefToHead() {
        assertThat(GitRead.range(repo, first + ".." + second))
                .isEqualTo(new GitRead.Range(first, second));
        assertThat(GitRead.range(repo, "release")).isEqualTo(new GitRead.Range(second, third));
        assertThat(GitRead.range(repo, "v1..main")).isEqualTo(new GitRead.Range(second, third));
    }

    @Test
    void diffFilesNamesEveryChangedPathWithItsKindAndLineCounts() {
        List<GitRead.FileChange> changes = GitRead.diffFiles(repo, first, second, null);
        assertThat(changes).extracting(GitRead.FileChange::path)
                .containsExactlyInAnyOrder("A.java", "B.java");
        GitRead.FileChange a = changes.stream()
                .filter(c -> c.path().equals("A.java")).findFirst().orElseThrow();
        assertThat(a.changeKind()).isEqualTo("MODIFY");
        assertThat(a.insertions()).isEqualTo(1);
        assertThat(a.deletions()).isEqualTo(1);
        GitRead.FileChange b = changes.stream()
                .filter(c -> c.path().equals("B.java")).findFirst().orElseThrow();
        assertThat(b.changeKind()).isEqualTo("ADD");
        assertThat(b.deletions()).isZero();
    }

    @Test
    void diffFilesCanBeRestrictedToOnePath() {
        assertThat(GitRead.diffFiles(repo, first, second, "B.java"))
                .extracting(GitRead.FileChange::path).containsExactly("B.java");
    }

    @Test
    void identicalTreesDiffToNothing() {
        assertThat(GitRead.diffFiles(repo, third, third, null)).isEmpty();
        assertThat(GitRead.diffText(repo, third, third, null)).isEmpty();
    }

    @Test
    void diffTextIsAUnifiedPatch() {
        String patch = GitRead.diffText(repo, second, third, "A.java");
        assertThat(patch).contains("diff --git a/A.java b/A.java")
                .contains("-class A { int x; }")
                .contains("+class A { int x; int y; }");
    }

    @Test
    void aDeleteIsReportedUnderTheVanishedPath() {
        fixture.delete("B.java").commit("c4 drop B", Instant.parse("2026-01-04T00:00:00Z"));
        assertThat(GitRead.diffFiles(repo, third, fixture.headSha(), null)).singleElement()
                .satisfies(c -> {
                    assertThat(c.path()).isEqualTo("B.java");
                    assertThat(c.changeKind()).isEqualTo("DELETE");
                });
    }

    @Test
    void aRenameIsDetectedRatherThanReportedAsAnAddAndADelete() {
        fixture.delete("B.java").file("C.java", "class B {}\n")
                .commit("c4 move B to C", Instant.parse("2026-01-04T00:00:00Z"));
        assertThat(GitRead.diffFiles(repo, third, fixture.headSha(), null)).singleElement()
                .satisfies(c -> {
                    assertThat(c.changeKind()).isEqualTo("RENAME");
                    assertThat(c.path()).isEqualTo("C.java");
                    assertThat(c.oldPath()).isEqualTo("B.java");
                });
    }

    /** A root commit has no parent, and "show" of it must still work. */
    @Test
    void parentOfARootCommitIsNullAndDiffsAgainstTheEmptyTree() {
        assertThat(GitRead.parentOf(repo, first)).isNull();
        assertThat(GitRead.parentOf(repo, third)).isEqualTo(second);
        assertThat(GitRead.diffFiles(repo, null, first, null))
                .extracting(GitRead.FileChange::path).containsExactly("A.java");
    }

    @Test
    void commitReadsOneCommitsMetadata() {
        GitRead.Commit c = GitRead.commit(repo, "v1");
        assertThat(c.sha()).isEqualTo(second);
        assertThat(c.subject()).isEqualTo("c2 add B and a field");
        assertThat(c.when()).isEqualTo(Instant.parse("2026-01-02T00:00:00Z"));
    }

    @Test
    void blameAttributesEachLineToTheCommitThatWroteIt() {
        assertThat(GitRead.blame(repo, "A.java", "HEAD", 1, 10)).singleElement().satisfies(l -> {
            assertThat(l.line()).isEqualTo(1);
            assertThat(l.shortSha()).isEqualTo(third.substring(0, GitRead.SHORT_SHA));
            assertThat(l.text()).isEqualTo("class A { int x; int y; }");
            assertThat(l.author()).isEqualTo("sdd-fixture");
        });
        assertThat(GitRead.blame(repo, "B.java", "v1", 1, 10)).singleElement()
                .satisfies(l -> assertThat(l.shortSha())
                        .isEqualTo(second.substring(0, GitRead.SHORT_SHA)));
    }

    @Test
    void aBlameWindowIsClampedToTheFileRatherThanThrowing() {
        assertThat(GitRead.blame(repo, "A.java", "HEAD", 5, 100)).isEmpty();
        assertThat(GitRead.lineCount(repo, "A.java", "HEAD")).isEqualTo(1);
    }

    /** A file untouched since the commit that created it still blames to that commit. */
    @Test
    void blameReachesPastTheMostRecentCommit() {
        assertThat(GitRead.blame(repo, "B.java", "HEAD", 1, 10)).singleElement()
                .satisfies(l -> assertThat(l.shortSha())
                        .isEqualTo(second.substring(0, GitRead.SHORT_SHA)));
    }

    @Test
    void blamingAPathThatIsNotThereSaysSo() {
        assertThatThrownBy(() -> GitRead.blame(repo, "nope.java", "HEAD", 1, 10))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> GitRead.blame(repo, "B.java", first, 1, 10))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refsListsBranchesAndTags() {
        assertThat(GitRead.refs(repo)).containsExactlyInAnyOrder("main", "release", "v1");
    }

    @Test
    void commitsBetweenAndAncestryAcceptRevisionsNotJustShas() {
        assertThat(GitRead.commitsBetween(repo, "v1", "main")).isEqualTo(1);
        assertThat(GitRead.commitsBetween(repo, first, "HEAD")).isEqualTo(2);
        assertThat(GitRead.isAncestor(repo, "release", "main")).isTrue();
        assertThat(GitRead.isAncestor(repo, "main", "release")).isFalse();
    }

    @Test
    void aDirectoryThatIsNotARepoFailsLoudly() {
        assertThatThrownBy(() -> GitRead.resolve(dir.resolve("nope"), "HEAD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot open git repo");
        assertThatThrownBy(() -> GitRead.log(dir.resolve("nope"), "HEAD", null, 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot read log of");
    }
}
