package sdd.plan.impact;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.testing.FixtureRepo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code --since} says when a range does not resolve.
 *
 * <p>These are diagnosis messages an operator acts on, so being wrong about WHICH failure happened
 * is worse than being vague: "not a git repo" sends someone to re-clone a checkout that is fine.
 */
class ChangeSetTest {
    @TempDir Path ws;
    private Database db;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        FixtureRepo repo = FixtureRepo.in(ws, "payments-api")
                .file("src/A.java", "class A {}\n")
                .commit("release 7", Instant.parse("2026-01-01T00:00:00Z"));
        base = repo.headSha();
        repo.tag("release-7")
                .file("src/A.java", "class A { int x; }\n")
                .file("src/B.java", "class B {}\n")
                .commit("the change", Instant.parse("2026-01-05T00:00:00Z"));

        // A second REAL repo that simply does not carry the other's history.
        FixtureRepo other = FixtureRepo.in(ws, "billing-svc")
                .file("src/C.java", "class C {}\n")
                .commit("unrelated", Instant.parse("2026-01-02T00:00:00Z"));

        Files.createDirectories(ws.resolve("not-a-repo"));

        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES(?,?,'SERVICE')",
                    "payments-api", repo.path().toString());
            h.execute("INSERT INTO repo(name, path, kind) VALUES(?,?,'SERVICE')",
                    "billing-svc", other.path().toString());
            h.execute("INSERT INTO repo(name, path, kind) VALUES(?,?,'SERVICE')",
                    "not-a-repo", ws.resolve("not-a-repo").toString());
        });
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private ChangeSet.RepoChange one(String repo, String range) {
        return ChangeSet.compute(db.jdbi(), Map.of(repo, range)).get(0);
    }

    @Test
    void aResolvedRangeCarriesBothShasAndEveryChangedPath() {
        ChangeSet.RepoChange change = one("payments-api", "release-7");

        assertThat(change.resolution()).isEqualTo("RESOLVED");
        assertThat(change.fromSha()).isEqualTo(base.substring(0, 8));
        assertThat(change.files()).extracting(ChangeSet.ChangedFile::path)
                .containsExactly("src/A.java", "src/B.java");
    }

    /**
     * The defect. A full SHA absent from this repo makes JGit's {@code resolve} throw
     * {@code MissingObjectException} — an IOException — rather than return null, so it used to land
     * in the catch that reports NOT_A_GIT_REPO. billing-svc is a perfectly good checkout; it just
     * has never seen that commit, which is the NORMAL case for a bare {@code --since <sha>} across
     * an estate, since a SHA only exists in the repo it was made in.
     */
    @Test
    void aShaThatIsNotInThisRepoIsAMissingRefNotAMissingRepo() {
        ChangeSet.RepoChange change = one("billing-svc", base);

        assertThat(change.resolution()).isEqualTo("REF_NOT_FOUND");
        assertThat(change.fromSha()).isNull();
    }

    /** The half that always worked: an unknown ref NAME resolves to null rather than throwing. */
    @Test
    void aRefNameThatIsNotInThisRepoIsAlsoAMissingRef() {
        assertThat(one("billing-svc", "release-7").resolution()).isEqualTo("REF_NOT_FOUND");
    }

    @Test
    void bothEndsOfAnExplicitRangeAreChecked() {
        assertThat(one("payments-api", base + "..deadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
                .resolution()).isEqualTo("REF_NOT_FOUND");
        assertThat(one("payments-api", "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef.." + base)
                .resolution()).isEqualTo("REF_NOT_FOUND");
    }

    /** RevisionSyntaxException is an IllegalArgumentException, so it would escape as a crash. */
    @Test
    void aMalformedRevisionIsReportedRatherThanThrown() {
        assertThat(one("payments-api", "release-7^^^{bogus").resolution())
                .isEqualTo("REF_NOT_FOUND");
    }

    /** And the message still means what it says when the directory genuinely is not a checkout. */
    @Test
    void aDirectoryThatIsNotACheckoutStillReportsNotAGitRepo() {
        assertThat(one("not-a-repo", "release-7").resolution()).isEqualTo("NOT_A_GIT_REPO");
    }

    @Test
    void aRepoTheKnowledgeBaseDoesNotKnowIsItsOwnAnswer() {
        assertThat(one("ghost-svc", "release-7").resolution()).isEqualTo("REPO_NOT_IN_KB");
    }

    @Test
    void everyNamedRepoIsReportedAndTheyComeBackSorted() {
        List<ChangeSet.RepoChange> changes = ChangeSet.compute(db.jdbi(),
                Map.of("payments-api", "release-7", "billing-svc", "release-7"));
        assertThat(changes).extracting(ChangeSet.RepoChange::repo)
                .containsExactly("billing-svc", "payments-api");
    }
}
