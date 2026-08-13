package sdd.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.RunSettings;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.testing.FixtureRepo;
import sdd.core.testing.ScriptedChatModel;
import sdd.index.gradle.ExtractionException;
import sdd.index.gradle.GradleModel;
import sdd.index.scan.RepoScan;
import sdd.index.store.IndexPersistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Gradle-free coverage of IndexService's failure handling. */
class IndexServiceTest {
    @TempDir Path ws;

    private SddConfig config() {
        return new SddConfig(ws, "fts", Map.of(), Map.of(), List.of(), Map.of(), List.of(), RunSettings.defaults(), Map.of());
    }

    private static GradleModel.Extract oneModule(String name) {
        return oneModuleAt(name, Path.of("/w/" + name));
    }

    private static GradleModel.Extract oneModuleAt(String name, Path projectDir) {
        return new GradleModel.Extract(List.of(new GradleModel.Project(
                ":", name, "com.acme", "1.0.0", projectDir,
                List.of("java"), false, List.of(),
                Map.of("compileClasspath", new GradleModel.DepConfig(
                        List.of(new GradleModel.DeclaredDep("com.acme", "lib-core", "2.3.0")),
                        List.of(), List.of())))),
                List.of());
    }

    private Path wreckedRepo(String name) throws Exception {
        Path dir = Files.createDirectories(ws.resolve(name));
        Files.writeString(dir.resolve(".git"), "this is not a gitdir link\n");
        return dir;
    }

    private String repoStatus(Database db, String name) {
        return db.jdbi().withHandle(h -> h.createQuery("SELECT gradle_status FROM repo WHERE name=:n")
                .bind("n", name).mapTo(String.class).one());
    }

    private int moduleCount(Database db, String repo) {
        return db.jdbi().withHandle(h -> h.createQuery("""
                        SELECT count(*) FROM module m JOIN repo r ON r.id=m.repo_id WHERE r.name=:n""")
                .bind("n", repo).mapTo(Integer.class).one());
    }

    private int typeCount(Database db, String repo) {
        return db.jdbi().withHandle(h -> h.createQuery("""
                        SELECT count(*) FROM java_type jt JOIN module m ON m.id=jt.module_id
                        JOIN repo r ON r.id=m.repo_id WHERE r.name=:n""")
                .bind("n", repo).mapTo(Integer.class).one());
    }

    @Test
    void unscannableRepoWithNoStoredRowsIsReportedFailedAndTheRunSurvives() throws Exception {
        wreckedRepo("wrecked");
        try (Database db = Database.open(ws)) {
            List<IndexService.RepoResult> results = new IndexService().run(config(), db);

            assertThat(results).singleElement().satisfies(r -> {
                assertThat(r.repo()).isEqualTo("wrecked");
                assertThat(r.status()).isEqualTo("FAILED");
                assertThat(r.error()).isNotBlank();
                // persistRepo never sets parse_status, so the DB has no stored value yet — withCounts
                // must fall back to the in-flight "FAILED" instead of losing it to a NULL re-read.
                assertThat(r.parseStatus()).isEqualTo("FAILED");
            });
            assertThat(repoStatus(db, "wrecked")).isEqualTo("FAILED");
        }
    }

    @Test
    void unscannableRepoWithStoredRowsKeepsThemAsStale() throws Exception {
        Path dir = wreckedRepo("wrecked");
        try (Database db = Database.open(ws)) {
            IndexPersistence.persistRepo(db.jdbi(),
                    new RepoScan("wrecked", dir, "a".repeat(40), "main", ""),
                    oneModule("wrecked"), "OK", null);

            List<IndexService.RepoResult> results = new IndexService().run(config(), db);

            assertThat(results).singleElement().satisfies(r -> {
                assertThat(r.status()).isEqualTo("STALE_OK");
                assertThat(r.modules()).isEqualTo(1);
            });
            assertThat(moduleCount(db, "wrecked")).isEqualTo(1);
        }
    }

    @Test
    void unexpectedExtractorFailureIsContainedInsteadOfSinkingTheRun() throws Exception {
        Path dir = Files.createDirectories(ws.resolve("boom"));
        try (Database db = Database.open(ws)) {
            IndexService.RepoResult r = new IndexService().indexRepo(db.jdbi(),
                    p -> { throw new IllegalStateException("tooling api exploded"); },
                    new RepoScan("boom", dir, "a".repeat(40), "main", ""));

            assertThat(r.status()).isEqualTo("FAILED");
            assertThat(r.error()).contains("tooling api exploded");
            assertThat(repoStatus(db, "boom")).isEqualTo("FAILED");
        }
    }

    @Test
    void failedParseStatusForcesRetryEvenWhenGradleFingerprintUnchanged() throws Exception {
        Path dir = Files.createDirectories(ws.resolve("retry-me"));
        try (Database db = Database.open(ws)) {
            RepoScan scan = new RepoScan("retry-me", dir, "a".repeat(40), "main", "");
            IndexPersistence.persistRepo(db.jdbi(), scan, oneModule("retry-me"), "OK", null);
            // Simulate what a prior run's mid-repo source-extraction failure would have left behind:
            // the gradle picture is OK and the fingerprint is unchanged, but the source parse failed.
            db.jdbi().useHandle(h -> h.execute(
                    "UPDATE repo SET parse_status='FAILED' WHERE name='retry-me'"));

            boolean[] extractorCalled = {false};
            IndexService.RepoResult r = new IndexService().indexRepo(db.jdbi(),
                    p -> { extractorCalled[0] = true; return oneModule("retry-me"); },
                    scan); // identical fingerprint to the persisted row

            assertThat(extractorCalled[0]).isTrue();
            assertThat(r.skipped()).isFalse();
        }
    }

    @Test
    void repoIndexedBeforeSourceExtractionExistedIsStillIndexed() throws Exception {
        Path dir = Files.createDirectories(ws.resolve("legacy"));
        Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(dir.resolve("src/main/java/L.java"), "public class L {}\n");
        try (Database db = Database.open(ws)) {
            RepoScan scan = new RepoScan("legacy", dir, "a".repeat(40), "main", "");
            IndexPersistence.persistRepo(db.jdbi(), scan, oneModuleAt("legacy", dir), "OK", null);
            // a row written by a pre-source-extraction build: gradle status OK, fingerprint
            // current, but parse_status never set — it must not be mistaken for "already parsed"
            db.jdbi().useHandle(h -> h.execute("UPDATE repo SET parse_status=NULL WHERE name='legacy'"));

            IndexService.RepoResult r = new IndexService().indexRepo(db.jdbi(),
                    p -> oneModuleAt("legacy", dir), scan); // identical fingerprint

            assertThat(r.skipped()).isFalse();
            assertThat(typeCount(db, "legacy")).isEqualTo(1);
        }
    }

    @Test
    void successfulIndexRunExtractsSourceAndPersistsApiSurface() throws Exception {
        Path dir = Files.createDirectories(ws.resolve("has-source"));
        Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(dir.resolve("src/main/java/P.java"), "public class P {}\n");
        try (Database db = Database.open(ws)) {
            IndexService.RepoResult r = new IndexService().indexRepo(db.jdbi(),
                    p -> oneModuleAt("has-source", dir),
                    new RepoScan("has-source", dir, "a".repeat(40), "main", ""));

            assertThat(r.status()).isEqualTo("OK");
            assertThat(r.parseStatus()).isEqualTo("OK");
            assertThat(typeCount(db, "has-source")).isEqualTo(1);
        }
    }

    @Test
    void cardsRunAfterIndexingWhenModelProvided() {
        FixtureRepo.in(ws, "has-source").file("src/main/java/P.java", "public class P {}\n").commit("init");
        ScriptedChatModel cardModel = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("{\"card_line\": \"L.\", \"card_md\": \"## Purpose\\nP.\"}"),
                "stop", new Usage(1, 1))));
        try (Database db = Database.open(ws)) {
            IndexService service = new IndexService(
                    repoDir -> oneModuleAt("has-source", repoDir), cardModel, "qwen");

            service.run(config(), db);

            assertThat(service.lastCardResult()).isNotNull();
            assertThat(service.lastCardResult().generated()).isEqualTo(1);
            Integer cardCount = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT count(*) FROM repo_card").mapTo(Integer.class).one());
            assertThat(cardCount).isEqualTo(1);
        }
    }

    @Test
    void cardGenerationRuntimeExceptionIsSwallowedAndRunSucceeds() {
        FixtureRepo.in(ws, "has-source").file("src/main/java/P.java", "public class P {}\n").commit("init");
        sdd.core.llm.ChatModel blowsUp = req -> { throw new IllegalStateException("boom"); };
        try (Database db = Database.open(ws)) {
            IndexService service = new IndexService(
                    repoDir -> oneModuleAt("has-source", repoDir), blowsUp, "qwen");

            List<IndexService.RepoResult> results = service.run(config(), db);

            assertThat(results).singleElement()
                    .satisfies(r -> assertThat(r.status()).isEqualTo("OK"));
            assertThat(service.lastCardResult()).isNull();
            assertThat(service.lastCardError()).contains("boom");
        }
    }

    @Test
    void noCardModelLeavesCardResultNull() {
        FixtureRepo.in(ws, "has-source").file("src/main/java/P.java", "public class P {}\n").commit("init");
        try (Database db = Database.open(ws)) {
            IndexService service = new IndexService(repoDir -> oneModuleAt("has-source", repoDir));

            service.run(config(), db);

            assertThat(service.lastCardResult()).isNull();
            assertThat(service.lastCardError()).isNull();
        }
    }

    @Test
    void emptyStaticFallbackKeepsExistingRowsInsteadOfWipingThem() throws Exception {
        Path dir = Files.createDirectories(ws.resolve("half-broken")); // no build files to parse
        try (Database db = Database.open(ws)) {
            IndexPersistence.persistRepo(db.jdbi(),
                    new RepoScan("half-broken", dir, "a".repeat(40), "main", ""),
                    oneModule("half-broken"), "OK", null);

            IndexService.RepoResult r = new IndexService().indexRepo(db.jdbi(),
                    p -> { throw new ExtractionException("gradle kaput"); },
                    new RepoScan("half-broken", dir, "b".repeat(40), "main", ""));

            assertThat(r.status()).isEqualTo("STALE_OK");
            assertThat(r.error()).contains("gradle kaput");
            assertThat(repoStatus(db, "half-broken")).isEqualTo("STALE_OK");
            assertThat(moduleCount(db, "half-broken")).isEqualTo(1);
        }
    }

    @Test
    void secondRunWithoutForceSkipsUnchangedRepo() throws Exception {
        FixtureRepo.in(ws, "has-source").file("src/main/java/P.java", "public class P {}\n").commit("init");
        try (Database db = Database.open(ws)) {
            IndexService service = new IndexService(repoDir -> oneModuleAt("has-source", repoDir));
            service.run(config(), db);

            List<IndexService.RepoResult> second = service.run(config(), db);

            assertThat(second).filteredOn(r -> r.repo().equals("has-source")).first()
                    .satisfies(r -> {
                        assertThat(r.skipped()).isTrue();
                        assertThat(r.status()).isEqualTo("OK");
                    });
        }
    }

    @Test
    void forceReindexesUnchangedRepoWithoutDuplicatingRows() throws Exception {
        FixtureRepo.in(ws, "has-source").file("src/main/java/P.java", "public class P {}\n").commit("init");
        try (Database db = Database.open(ws)) {
            IndexService service = new IndexService(repoDir -> oneModuleAt("has-source", repoDir));
            service.run(config(), db);

            List<IndexService.RepoResult> second = service.run(config(), db, true);

            assertThat(second).filteredOn(r -> r.repo().equals("has-source")).first()
                    .satisfies(r -> {
                        assertThat(r.skipped()).isFalse();
                        assertThat(r.status()).isEqualTo("OK");
                    });
            // per-repo persistence replaces (deletes then reinserts) module rows keyed by repo_id,
            // so a forced re-index of an unchanged repo must not leave duplicate rows behind.
            assertThat(moduleCount(db, "has-source")).isEqualTo(1);
            assertThat(typeCount(db, "has-source")).isEqualTo(1);
        }
    }

    @Test
    void forceComposesWithNoCardsSoNoCardGenerationIsAttempted() throws Exception {
        FixtureRepo.in(ws, "has-source").file("src/main/java/P.java", "public class P {}\n").commit("init");
        try (Database db = Database.open(ws)) {
            // no cardModel injected == the CLI's --no-cards path
            IndexService service = new IndexService(repoDir -> oneModuleAt("has-source", repoDir));
            service.run(config(), db);

            service.run(config(), db, true);

            assertThat(service.lastCardResult()).isNull();
            assertThat(service.lastCardError()).isNull();
        }
    }

    @Test
    void emptyStaticFallbackWithNoStoredRowsStillPersistsDegraded() throws Exception {
        Path dir = Files.createDirectories(ws.resolve("fresh-broken"));
        try (Database db = Database.open(ws)) {
            IndexService.RepoResult r = new IndexService().indexRepo(db.jdbi(),
                    p -> { throw new ExtractionException("gradle kaput"); },
                    new RepoScan("fresh-broken", dir, "a".repeat(40), "main", ""));

            assertThat(r.status()).isEqualTo("DEGRADED");
            assertThat(repoStatus(db, "fresh-broken")).isEqualTo("DEGRADED");
        }
    }
}
