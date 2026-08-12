package sdd.plan.impact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.retrieve.FtsRetriever;
import sdd.core.retrieve.FtsSymbolWriter;
import sdd.core.testing.ScriptedChatModel;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.Touchpoint;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ImpactAnalysisTest {
    @TempDir Path ws;
    private Database db;

    // lib-core <- svc-pricing (PINNED, api_usage evidence); svc-legacy exists with an FTS-matching
    // type but no dep edge — the model will not select it -> excluded.
    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-pricing','/w/2','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-legacy','/w/3','SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2,':','SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (3,':','SERVICE')");
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (1,'com.acme.LoyaltyTier','CLASS')");
            h.execute("INSERT INTO api_usage(from_module_id, target_fqcn, target_module_id, ref_kind) "
                    + "VALUES (2,'com.acme.LoyaltyTier',1,'IMPORT')");
            FtsSymbolWriter.insert(h, 3L, "LegacyLoyaltyAdapter", "com.acme.legacy.LegacyLoyaltyAdapter");
        });
    }

    private static NormalizedSpec spec() {
        return new NormalizedSpec("S-1", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "loyalty tier pricing"), new SpecItem("R2", "unrelated")),
                List.of(new SpecItem("A1", "acc")), List.of(),
                List.of(new Touchpoint(Touchpoint.Kind.CLASS, "LoyaltyTier")),
                List.of(), List.of(), List.of());
    }

    @Test
    void assemblesSeedsClosureDiscrepanciesExclusionsAndCoverage() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("""
                        {"repos": [{"repo": "lib-core", "role": "primary", "covers": ["R1"],
                                    "reason": "owns LoyaltyTier"}]}"""),
                "stop", new Usage(1, 1))));

        ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()),
                spec(), planner, "deepseek-v4-flash", 4096);

        assertThat(result.seeds()).extracting(Seed::repo, Seed::source).contains(
                tuple("lib-core", "touchpoint"),
                tuple("lib-core", "model"));
        assertThat(result.affected()).extracting(AffectedRepo::repo, AffectedRepo::annotation)
                .containsExactly(
                        tuple("lib-core", "SEED"),
                        tuple("svc-pricing", "CODE_CHANGE_LIKELY"));
        assertThat(result.affected().get(0).covers()).containsExactly("R1");
        assertThat(result.excluded()).singleElement().satisfies(e -> {
            assertThat(e.repo()).isEqualTo("svc-legacy");
            assertThat(e.detail()).contains("not selected by model, not required by graph");
        });
        assertThat(result.discrepancies()).isEmpty();
        assertThat(result.problems()).containsExactly("no repo covers R2");
        assertThat(result.cycles()).isEmpty();
    }

    @Test
    void modelOnlyReposAreIncludedButFlaggedAndOmissionsSurfaced() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("""
                        {"repos": [{"repo": "svc-legacy", "role": "contributor", "covers": ["R1", "R2"],
                                    "reason": "legacy adapter"}]}"""),
                "stop", new Usage(1, 1))));

        ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()),
                spec(), planner, "deepseek-v4-flash", 4096);

        // svc-legacy has an FTS candidate, so it is NOT model-only; lib-core was seeded but unnamed
        assertThat(result.discrepancies()).containsExactly("model omitted seeded repo: lib-core");
        assertThat(result.affected()).extracting(AffectedRepo::repo)
                .contains("lib-core", "svc-legacy", "svc-pricing");
        assertThat(result.excluded()).isEmpty();
    }

    @Test
    void modelFailureDegradesToDeterministicOnlyWithCoverageUnknown() {
        sdd.core.llm.ChatModel down = req -> {
            throw new sdd.core.llm.ModelException("connection refused", 0);
        };

        ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()),
                spec(), down, "m", 16);

        assertThat(result.affected()).extracting(AffectedRepo::repo)
                .containsExactly("lib-core", "svc-pricing");
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("model seeding unavailable"))
                .anySatisfy(w -> assertThat(w).contains("coverage unknown"));
        assertThat(result.problems()).noneSatisfy(p -> assertThat(p).contains("no repo covers"));
    }

    @Test
    void emptyModelSelectionStillSurfacesOmissionDiscrepancies() {
        // model AVAILABLE but selecting nothing is a genuine model/graph disagreement
        ScriptedChatModel planner = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("{\"repos\": []}"), "stop", new Usage(1, 1))));

        ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()),
                spec(), planner, "m", 16);

        assertThat(result.discrepancies()).contains("model omitted seeded repo: lib-core");
        assertThat(result.problems()).contains("no repo covers R1", "no repo covers R2");
    }

    @Test
    void zeroSeedsIsAProblemNotACrash() {
        NormalizedSpec bare = new NormalizedSpec("S-2", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "zzz qqq xxx")), List.of(new SpecItem("A1", "a")),
                List.of(), List.of(), List.of(), List.of(), List.of());
        ScriptedChatModel planner = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("{\"repos\": []}"), "stop", new Usage(1, 1))));

        ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()),
                bare, planner, "m", 16);

        assertThat(result.affected()).isEmpty();
        assertThat(result.problems()).anySatisfy(p -> assertThat(p).contains("no seeds"));
    }

    @Test
    void duplicateModelEntriesAreMergedNotDuplicated() {
        // Model emits the same entry twice — covers and reasons should NOT duplicate
        ScriptedChatModel planner = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("""
                        {"repos": [{"repo": "lib-core", "role": "primary", "covers": ["R1"],
                                    "reason": "owns LoyaltyTier"},
                                   {"repo": "lib-core", "role": "primary", "covers": ["R1"],
                                    "reason": "owns LoyaltyTier"}]}"""),
                "stop", new Usage(1, 1))));

        ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()),
                spec(), planner, "deepseek-v4-flash", 4096);

        // Covers should not duplicate R1
        assertThat(result.affected().get(0).covers()).containsExactly("R1");
        // Model reason line should appear exactly once in reasons
        assertThat(result.affected().get(0).reasons()).filteredOn(r -> r.contains("model"))
                .hasSize(1);
    }

    @Test
    void modelConfirmedCandidateCarriesFtsProvenance() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("""
                        {"repos": [{"repo": "svc-legacy", "role": "contributor", "covers": ["R1"],
                                    "reason": "legacy adapter"}]}"""),
                "stop", new Usage(1, 1))));

        ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()),
                spec(), planner, "m", 16);

        assertThat(result.seeds()).extracting(Seed::repo, Seed::source).contains(
                tuple("svc-legacy", "fts"), tuple("svc-legacy", "model"));
        AffectedRepo legacy = result.affected().stream()
                .filter(a -> a.repo().equals("svc-legacy")).findFirst().orElseThrow();
        assertThat(legacy.reasons()).anySatisfy(r ->
                assertThat(r).isEqualTo("fts R1 hit: LegacyLoyaltyAdapter"));
        // regression pin, not a RED driver: a model-selected candidate is affected today too,
        // so excluded is already empty pre-change — this pins that the rework keeps it so
        assertThat(result.excluded()).isEmpty();
    }

    @Test
    void seedsListHoldsNoIdenticalTriples() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("""
                        {"repos": [{"repo": "lib-core", "role": "primary", "covers": ["R1"], "reason": "owns it"},
                                   {"repo": "lib-core", "role": "primary", "covers": ["R1"], "reason": "owns it"}]}"""),
                "stop", new Usage(1, 1))));

        ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()),
                spec(), planner, "m", 16);

        long modelSeedRows = result.seeds().stream()
                .filter(s -> s.repo().equals("lib-core") && s.source().equals("model")).count();
        assertThat(modelSeedRows).isEqualTo(1);
    }
}
