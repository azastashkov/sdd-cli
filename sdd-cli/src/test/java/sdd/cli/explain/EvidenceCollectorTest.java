package sdd.cli.explain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.kb.EntityKind;
import sdd.core.retrieve.FtsRetriever;
import sdd.core.retrieve.Retriever;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceCollectorTest {
    @TempDir Path ws;
    private Database db;
    private Retriever retriever;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        ExplainFixture.seed(db.jdbi());
        retriever = new FtsRetriever(db.jdbi());
    }

    private Evidence collect(RetrievalRequest request) {
        return EvidenceCollector.collect(db.jdbi(), retriever, request);
    }

    private static Section section(Evidence evidence, String titlePrefix) {
        return evidence.sections().stream()
                .filter(s -> s.title().startsWith(titlePrefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no section titled '" + titlePrefix + "*' among "
                        + evidence.sections().stream().map(Section::title).toList()));
    }

    private static List<String> texts(Section section) {
        return section.facts().stream().map(Fact::text).toList();
    }

    // --- describe -----------------------------------------------------------------------------

    @Test
    void describeYieldsAllSectionsWithTheCardLabelled() {
        RetrievalRequest request = new RetrievalRequest(Intent.DESCRIBE,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, false)),
                List.of(), "What is svc-orders?", List.of(), false);

        Evidence evidence = collect(request);

        assertThat(section(evidence, "Repo: " + ExplainFixture.SVC_ORDERS).facts())
                .anySatisfy(f -> assertThat(f.text()).contains("SERVICE").contains("gradle_status=DEGRADED"));
        assertThat(texts(section(evidence, "Summary: " + ExplainFixture.SVC_ORDERS)))
                .anySatisfy(t -> assertThat(t).contains("model-generated").contains("card_line"))
                .anySatisfy(t -> assertThat(t).contains("model-generated").contains("card_md"));
        assertThat(texts(section(evidence, "Modules: " + ExplainFixture.SVC_ORDERS))).isNotEmpty();
        assertThat(texts(section(evidence, "Endpoints: " + ExplainFixture.SVC_ORDERS)))
                .anySatisfy(t -> assertThat(t).contains("GET").contains("/orders/{}"));
        assertThat(texts(section(evidence, "Kafka roles: " + ExplainFixture.SVC_ORDERS)))
                .anySatisfy(t -> assertThat(t).contains(ExplainFixture.ORDERS_TOPIC).contains("PRODUCER"));
        assertThat(texts(section(evidence, "Depends on: " + ExplainFixture.SVC_ORDERS)))
                .contains(ExplainFixture.LIB_API, ExplainFixture.PLATFORM);
        assertThat(texts(section(evidence, "Top API types: " + ExplainFixture.SVC_ORDERS))).isEmpty();
        assertThat(evidence.provenance().repoCount()).isEqualTo(6);
    }

    @Test
    void describeOnAClassEntityAddsADeduplicatedCitationSection() {
        // Insert a second, identical java_type row so resolveClass's non-DISTINCT query would
        // otherwise surface the same repo/detail/source pair twice.
        db.jdbi().useHandle(h -> h.execute(
                "INSERT INTO java_type(module_id, fqcn, kind) VALUES (2,'" + ExplainFixture.PRICE_API_FQCN + "','CLASS')"));

        RetrievalRequest request = new RetrievalRequest(Intent.DESCRIBE,
                List.of(new EntityRef(EntityKind.CLASS, ExplainFixture.PRICE_API_FQCN, false)),
                List.of(), "What is PriceApi?", List.of(), false);

        Evidence evidence = collect(request);

        Section citation = section(evidence, "Resolved class '" + ExplainFixture.PRICE_API_FQCN + "'");
        assertThat(citation.facts()).hasSize(1);
        assertThat(citation.facts().get(0).text())
                .contains(ExplainFixture.LIB_API).contains(ExplainFixture.PRICE_API_FQCN);
        // and the repo it belongs to (lib-api) is still described despite two matching rows
        assertThat(evidence.sections()).anySatisfy(s -> assertThat(s.title()).isEqualTo("Repo: " + ExplainFixture.LIB_API));
    }

    // --- dependency_path ------------------------------------------------------------------------

    @Test
    void dependencyPathReturnsTheHopSequenceWithDepEdgeDetailAndApiUsage() {
        RetrievalRequest request = new RetrievalRequest(Intent.DEPENDENCY_PATH,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, false),
                        new EntityRef(EntityKind.REPO, ExplainFixture.LIB_CORE, true)),
                List.of(), "Why does svc-orders depend on lib-core?", List.of(), false);

        Evidence evidence = collect(request);

        assertThat(texts(section(evidence, "Dependency path"))).containsExactly(
                ExplainFixture.SVC_ORDERS + " -> " + ExplainFixture.LIB_API + " -> " + ExplainFixture.LIB_CORE);
        assertThat(texts(section(evidence, "Dependency edges")))
                .anySatisfy(t -> assertThat(t)
                        .contains(ExplainFixture.SVC_ORDERS + " -> " + ExplainFixture.LIB_API)
                        .contains("com.acme:lib-api").contains("mode=BOM_MANAGED"))
                .anySatisfy(t -> assertThat(t)
                        .contains(ExplainFixture.LIB_API + " -> " + ExplainFixture.LIB_CORE)
                        .contains("com.acme:lib-core").contains("mode=PINNED").contains("declared_version=1.0"));
        assertThat(texts(section(evidence, "API usage")))
                .containsExactly(ExplainFixture.SVC_ORDERS + " -> " + ExplainFixture.LIB_API + ": "
                        + ExplainFixture.PRICE_API_FQCN + " (IMPORT)");
    }

    @Test
    void dependencyPathWithNoPathEmitsAnExplicitFactAndFallsBackToContractEdges() {
        // svc-billing has no outgoing v_repo_dep_edge to svc-orders (only a REST contract call).
        RetrievalRequest request = new RetrievalRequest(Intent.DEPENDENCY_PATH,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.SVC_BILLING, false),
                        new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, true)),
                List.of(), "Why does svc-billing depend on svc-orders?", List.of(), false);

        Evidence evidence = collect(request);

        assertThat(texts(section(evidence, "Dependency path"))).singleElement().satisfies(t ->
                assertThat(t).isEqualTo("no internal Gradle dependency path from " + ExplainFixture.SVC_BILLING
                        + " to " + ExplainFixture.SVC_ORDERS + " in the knowledge base"));
        assertThat(evidence.sections()).noneMatch(s -> s.title().equals("Dependency edges"));
        assertThat(evidence.sections()).noneMatch(s -> s.title().equals("API usage"));
        assertThat(texts(section(evidence, "REST calls (contract)")))
                .anySatisfy(t -> assertThat(t)
                        .contains(ExplainFixture.SVC_BILLING).contains(ExplainFixture.SVC_ORDERS)
                        .contains("GET").contains("/orders/{}").contains("confidence=HIGH"));
    }

    @Test
    void dependencyPathSameRepoOnBothSidesEmitsTheNoPathFactRatherThanAnEmptySectionOrCrash() {
        RetrievalRequest request = new RetrievalRequest(Intent.DEPENDENCY_PATH,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, false),
                        new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, true)),
                List.of(), "Why does svc-orders depend on svc-orders?", List.of(), false);

        Evidence evidence = collect(request);

        assertThat(texts(section(evidence, "Dependency path"))).singleElement().satisfies(t ->
                assertThat(t).isEqualTo("no internal Gradle dependency path from " + ExplainFixture.SVC_ORDERS
                        + " to " + ExplainFixture.SVC_ORDERS + " in the knowledge base"));
    }

    // --- search ---------------------------------------------------------------------------------

    @Test
    void searchPreservesBestFirstOrderingAndIsStableAcrossTwoIdenticalCalls() {
        RetrievalRequest request = new RetrievalRequest(Intent.SEARCH, List.of(),
                List.of("PriceApi", "OrdersController"), "tell me about these symbols", List.of(), false);

        Evidence first = collect(request);
        Evidence second = collect(request);

        assertThat(first).isEqualTo(second);
        List<String> hits = texts(section(first, "Search hits"));
        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(t -> assertThat(t).contains("PriceApi").contains(ExplainFixture.LIB_API));
        assertThat(hits).anySatisfy(t -> assertThat(t).contains("OrdersController").contains(ExplainFixture.SVC_ORDERS));
        assertThat(section(first, "Search hits").source()).isEqualTo("fts_symbol (bm25)");
    }

    // --- truncation -------------------------------------------------------------------------------

    @Test
    void aSectionOverItsLimitRendersTheExplicitMoreMarker() {
        db.jdbi().useHandle(h -> {
            for (int i = 0; i < 30; i++) {
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES "
                        + "((SELECT id FROM repo WHERE name = '" + ExplainFixture.SVC_ORDERS + "'),"
                        + "':extra" + i + "','UNKNOWN')");
            }
        });
        RetrievalRequest request = new RetrievalRequest(Intent.DESCRIBE,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, false)),
                List.of(), "What is svc-orders?", List.of(), false);

        Evidence evidence = collect(request);

        Section modules = section(evidence, "Modules: " + ExplainFixture.SVC_ORDERS);
        assertThat(modules.totalCount()).isEqualTo(31); // the fixture's one module + 30 extra
        assertThat(modules.facts()).hasSize(Section.DEFAULT_LIMIT + 1); // 25 shown + the marker
        assertThat(modules.facts().get(Section.DEFAULT_LIMIT).text()).isEqualTo("+6 more (showing 25 of 31)");
    }

    // --- determinism ------------------------------------------------------------------------------

    @Test
    void sameKbAndSameRequestProduceIdenticalEvidence() {
        RetrievalRequest request = new RetrievalRequest(Intent.DEPENDENCY_PATH,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, false),
                        new EntityRef(EntityKind.REPO, ExplainFixture.LIB_CORE, true)),
                List.of(), "Why does svc-orders depend on lib-core?", List.of(), false);

        assertThat(collect(request)).isEqualTo(collect(request));
    }
}
