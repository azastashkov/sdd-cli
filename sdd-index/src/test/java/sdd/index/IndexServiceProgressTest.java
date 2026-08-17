package sdd.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.RunSettings;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.progress.Progress;
import sdd.core.testing.FixtureRepo;
import sdd.core.testing.ScriptedChatModel;
import sdd.index.extract.BuildModel;
import sdd.index.extract.GradleBuildExtractor;
import sdd.index.gradle.GradleModel;
import sdd.index.testing.RecordingProgress;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 2's own coverage: {@link IndexService} threading {@link Progress} through its per-repo
 * loop, the estate-wide passes and card generation, without disturbing the pre-existing,
 * unmodified {@code IndexServiceTest}/{@code IndexServiceIT} coverage of behaviour.
 */
class IndexServiceProgressTest {
    @TempDir Path ws;

    private SddConfig config() {
        return config(ws);
    }

    private SddConfig config(Path workspace) {
        return new SddConfig(workspace, Map.of(), Map.of(), null, List.of(), Map.of(), List.of(), List.of(),
                RunSettings.defaults(), Map.of());
    }

    private static BuildModel.Extract oneModuleAt(String name, Path projectDir) {
        return GradleBuildExtractor.adapt(new GradleModel.Extract(List.of(new GradleModel.Project(
                ":", name, "com.acme", "1.0.0", projectDir,
                List.of("java"), false, List.of(),
                Map.of("compileClasspath", new GradleModel.DepConfig(
                        List.of(new GradleModel.DeclaredDep("com.acme", "lib-core", "2.3.0")),
                        List.of(), List.of())))),
                List.of()));
    }

    @Test
    void indexEmitsExpectedEventSequenceIncludingCardGeneration() throws Exception {
        FixtureRepo.in(ws, "has-source").file("src/main/java/P.java", "public class P {}\n").commit("init");
        ScriptedChatModel cardModel = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("{\"card_line\": \"L.\", \"card_md\": \"## Purpose\\nP.\"}"),
                "stop", new Usage(1, 1))));
        try (Database db = Database.open(ws)) {
            IndexService service = new IndexService(
                    repoDir -> oneModuleAt("has-source", repoDir), cardModel, "qwen");
            RecordingProgress progress = new RecordingProgress();

            service.run(config(), db, false, progress);

            assertThat(progress.events()).containsExactly(
                    "phase:index:1",
                    "start:has-source",
                    "detail:gradle extract",
                    "detail:source extraction",
                    "finish:has-source",
                    "phase:link:0",
                    "phase:usage:0",
                    "phase:match:0",
                    "phase:runtime edges:0",
                    "phase:cleanup:0",
                    "phase:cards:1",
                    "start:has-source",
                    "finish:has-source",
                    "phase:report:0");
        }
    }

    @Test
    void cachedCardOnASecondRunDoesNotLookLikeWork() throws Exception {
        FixtureRepo.in(ws, "has-source").file("src/main/java/P.java", "public class P {}\n").commit("init");
        ScriptedChatModel cardModel = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("{\"card_line\": \"L.\", \"card_md\": \"## Purpose\\nP.\"}"),
                "stop", new Usage(1, 1))));
        try (Database db = Database.open(ws)) {
            IndexService service = new IndexService(
                    repoDir -> oneModuleAt("has-source", repoDir), cardModel, "qwen");
            service.run(config(), db, false, new RecordingProgress());

            // Second run: unchanged fixture -> gradle fingerprint match skips indexRepo's own
            // work (no "detail:" events), and the card is now cached by content hash. The repo
            // loop's start/finish still fires unconditionally (design doc: "around the loop"),
            // so what this test actually proves is narrower and load-bearing: no start/finish
            // for "has-source" appears a SECOND time, inside the cards phase.
            RecordingProgress second = new RecordingProgress();
            List<IndexService.RepoResult> results = service.run(config(), db, false, second);

            assertThat(results).filteredOn(r -> r.repo().equals("has-source")).first()
                    .satisfies(r -> assertThat(r.skipped()).isTrue());

            List<String> events = second.events();
            int cardsPhase = events.indexOf("phase:cards:1");
            int reportPhase = events.indexOf("phase:report:0");
            assertThat(cardsPhase).isGreaterThanOrEqualTo(0);
            assertThat(reportPhase).isGreaterThan(cardsPhase);
            assertThat(events.subList(cardsPhase + 1, reportPhase))
                    .as("no start/finish pair inside the cards phase: the card was a cache hit")
                    .doesNotContain("start:has-source", "finish:has-source");
            // and the repo-index phase itself never emitted a "detail" (fingerprint short-circuit,
            // before the gradle-extract/source-extraction try block is ever entered)
            assertThat(events.subList(0, cardsPhase)).doesNotContain("detail:gradle extract",
                    "detail:source extraction");
        }
    }

    @Test
    void defaultThreeArgRunOverloadBehavesIdenticallyToPassingNoOpProgressExplicitly(
            @TempDir Path wsA, @TempDir Path wsB) throws Exception {
        FixtureRepo.in(wsA, "has-source").file("src/main/java/P.java", "public class P {}\n").commit("init");
        FixtureRepo.in(wsB, "has-source").file("src/main/java/P.java", "public class P {}\n").commit("init");
        try (Database dbA = Database.open(wsA); Database dbB = Database.open(wsB)) {
            List<IndexService.RepoResult> viaDefaultOverload =
                    new IndexService(repoDir -> oneModuleAt("has-source", repoDir)).run(config(wsA), dbA, false);
            List<IndexService.RepoResult> viaExplicitNoOp =
                    new IndexService(repoDir -> oneModuleAt("has-source", repoDir))
                            .run(config(wsB), dbB, false, Progress.noOp());

            assertThat(viaDefaultOverload).isEqualTo(viaExplicitNoOp);
        }
    }

    @Test
    void twoArgRunOverloadStillDefaultsForceToFalse() throws Exception {
        FixtureRepo.in(ws, "has-source").file("src/main/java/P.java", "public class P {}\n").commit("init");
        try (Database db = Database.open(ws)) {
            IndexService service = new IndexService(repoDir -> oneModuleAt("has-source", repoDir));
            service.run(config(), db);

            List<IndexService.RepoResult> second = service.run(config(), db); // no --force

            assertThat(second).filteredOn(r -> r.repo().equals("has-source")).first()
                    .satisfies(r -> assertThat(r.skipped()).isTrue());
        }
    }
}
