package sdd.cli.explain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.kb.EntityKind;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatRequest;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.retrieve.FtsRetriever;
import sdd.core.retrieve.FtsSymbolWriter;
import sdd.core.retrieve.Hit;
import sdd.core.retrieve.Retriever;
import sdd.core.testing.ScriptedChatModel;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceCollectorTest {
    /**
     * A word planted in a type's javadoc that occurs in no identifier, package, path or repo name
     * anywhere in the fixture — so any section text containing it can only have come from the
     * javadoc. See {@link #noEvidenceSectionOutsideSearchRendersTextTakenFromTypeJavadoc}.
     */
    private static final String JAVADOC_SENTINEL = "quorumsentinel";

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

    /** Every fact text in the whole answer, across all its sections. */
    private static List<String> allTexts(Evidence evidence) {
        return evidence.sections().stream().flatMap(s -> s.facts().stream()).map(Fact::text).toList();
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
    void describeSummaryDoesNotRenderTheLiteralStringNullWhenCardMdIsMissing() {
        // card_line is guaranteed non-null (RepoFacts.card returns early otherwise), but card_md
        // itself is nullable -- String.valueOf(row.get("card_md")) on a null Object renders the
        // 4-character string "null", which reads as if the KB literally stored that text rather
        // than recording that no card_md exists.
        db.jdbi().useHandle(h -> h.createUpdate(
                        "UPDATE repo_card SET card_md = NULL WHERE repo_id = "
                                + "(SELECT id FROM repo WHERE name = :r)")
                .bind("r", ExplainFixture.SVC_ORDERS).execute());
        RetrievalRequest request = new RetrievalRequest(Intent.DESCRIBE,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, false)),
                List.of(), "What is svc-orders?", List.of(), false);

        Evidence evidence = collect(request);

        assertThat(texts(section(evidence, "Summary: " + ExplainFixture.SVC_ORDERS)))
                .noneMatch(t -> t.endsWith(": null"));
    }

    @Test
    void describeEndpointsRenderTheAnyMarkerRatherThanTheLiteralStringNullForAMissingHttpMethod() {
        // rest_endpoint.http_method is nullable; KbEntities.resolveEndpoint already maps a NULL
        // http_method to "ANY" for endpoint resolution -- RepoFacts.endpoints must render the same
        // marker so the two agree, rather than a bare path with a leading space (if omitted) or
        // the literal "null" (if concatenated raw).
        db.jdbi().useHandle(h -> h.createUpdate("UPDATE rest_endpoint SET http_method = NULL WHERE id = 1")
                .execute());
        RetrievalRequest request = new RetrievalRequest(Intent.DESCRIBE,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, false)),
                List.of(), "What is svc-orders?", List.of(), false);

        Evidence evidence = collect(request);

        List<String> texts = texts(section(evidence, "Endpoints: " + ExplainFixture.SVC_ORDERS));
        assertThat(texts).noneMatch(t -> t.contains("null"));
        assertThat(texts).containsExactly("ANY /orders/{}");
    }

    @Test
    void describeTopJavaTypesRenderTheUnknownMarkerRatherThanTheLiteralStringNullForAMissingKind() {
        // java_type.kind is nullable; repo.kind/module.kind already use "UNKNOWN" as this schema's
        // convention for an unclassified kind (V1__init.sql), so java_type.kind follows the same
        // marker rather than the literal "null".
        db.jdbi().useHandle(h -> h.createUpdate("UPDATE java_type SET kind = NULL WHERE fqcn = :fqcn")
                .bind("fqcn", ExplainFixture.PRICE_API_FQCN).execute());
        RetrievalRequest request = new RetrievalRequest(Intent.DESCRIBE,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.LIB_API, false)),
                List.of(), "What is lib-api?", List.of(), false);

        Evidence evidence = collect(request);

        List<String> texts = texts(section(evidence, "Top API types: " + ExplainFixture.LIB_API));
        assertThat(texts).noneMatch(t -> t.contains("null"));
        assertThat(texts).singleElement().satisfies(t -> assertThat(t)
                .startsWith(ExplainFixture.PRICE_API_FQCN + " (UNKNOWN, is_api="));
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

    @Test
    void describeOnATopicEntityDeduplicatesCitationAcrossDuplicateRoleRows() {
        // resolveTopic (KbEntities) has no DISTINCT either — seed a second, identical CONSUMER
        // row for svc-notify so the same duplication risk named for CLASS also gets exercised
        // for TOPIC, the other kind the brief names.
        db.jdbi().useHandle(h -> h.execute(
                "INSERT INTO kafka_role(module_id, topic_id, role) VALUES (5, 1, 'CONSUMER')"));

        RetrievalRequest request = new RetrievalRequest(Intent.DESCRIBE,
                List.of(new EntityRef(EntityKind.TOPIC, ExplainFixture.ORDERS_TOPIC, false)),
                List.of(), "What is orders.events?", List.of(), false);

        Evidence evidence = collect(request);

        Section citation = section(evidence, "Resolved topic '" + ExplainFixture.ORDERS_TOPIC + "'");
        assertThat(citation.facts()).extracting(Fact::text).containsExactlyInAnyOrder(
                ExplainFixture.SVC_ORDERS + ": PRODUCER",
                ExplainFixture.SVC_NOTIFY + ": CONSUMER");
    }

    @Test
    void describeProducesIdenticalEvidenceAcrossTwoCalls() {
        RetrievalRequest request = new RetrievalRequest(Intent.DESCRIBE,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, false),
                        new EntityRef(EntityKind.CLASS, ExplainFixture.PRICE_API_FQCN, false)),
                List.of(), "What is svc-orders, and what is PriceApi?", List.of(), false);

        assertThat(collect(request)).isEqualTo(collect(request));
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
    void dependencyPathApiUsageDoesNotRenderTheLiteralStringNullWhenRefKindIsMissing() {
        // api_usage.ref_kind is nullable -- raw concatenation would render the literal "null",
        // indistinguishable from a ref_kind actually named "null".
        db.jdbi().useHandle(h -> h.createUpdate(
                        "UPDATE api_usage SET ref_kind = NULL WHERE target_fqcn = :fqcn")
                .bind("fqcn", ExplainFixture.PRICE_API_FQCN).execute());
        RetrievalRequest request = new RetrievalRequest(Intent.DEPENDENCY_PATH,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, false),
                        new EntityRef(EntityKind.REPO, ExplainFixture.LIB_CORE, true)),
                List.of(), "Why does svc-orders depend on lib-core?", List.of(), false);

        Evidence evidence = collect(request);

        List<String> texts = texts(section(evidence, "API usage"));
        assertThat(texts).noneMatch(t -> t.contains("null"));
        assertThat(texts).containsExactly(ExplainFixture.SVC_ORDERS + " -> " + ExplainFixture.LIB_API + ": "
                + ExplainFixture.PRICE_API_FQCN);
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
    void dependencyPathRestEdgesDoNotRenderTheLiteralStringNullForNullVerbConfidenceOrMatchedBy() {
        // rest_endpoint.http_method, rest_call_edge.confidence and .matched_by are all nullable.
        // http_method is structural to the sentence ("calls VERB PATH"), so a NULL one becomes the
        // "ANY" marker KbEntities.resolveEndpoint already uses for the same column; confidence and
        // matched_by are parenthesised attributes, so a NULL one is simply omitted.
        db.jdbi().useHandle(h -> {
            h.createUpdate("UPDATE rest_endpoint SET http_method = NULL WHERE id = 1").execute();
            h.createUpdate("UPDATE rest_call_edge SET confidence = NULL, matched_by = NULL "
                    + "WHERE client_id = 1 AND endpoint_id = 1").execute();
        });
        RetrievalRequest request = new RetrievalRequest(Intent.DEPENDENCY_PATH,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.SVC_BILLING, false),
                        new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, true)),
                List.of(), "Why does svc-billing depend on svc-orders?", List.of(), false);

        Evidence evidence = collect(request);

        List<String> texts = texts(section(evidence, "REST calls (contract)"));
        assertThat(texts).noneMatch(t -> t.contains("null"));
        assertThat(texts).containsExactly(ExplainFixture.SVC_BILLING + " calls ANY /orders/{} on "
                + ExplainFixture.SVC_ORDERS + " ()");
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

    @Test
    void dependencyPathMultiSourceOverlapWithTargetStillFindsTheGenuineEdgeFromTheOtherSource() {
        // The topic orders.events resolves to BOTH svc-orders (producer) and svc-notify
        // (consumer), so the subject's fromRepos = {svc-notify, svc-orders} while the object
        // (repo svc-orders) is ALSO one of those sources. svc-notify has a genuine Gradle
        // dependency on svc-orders; a BFS that pre-seeds every source into `visited` (including
        // ones that are also targets) skips that real edge entirely and wrongly reports no path.
        db.jdbi().useHandle(h -> h.execute(
                "INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (5,'com.acme','svc-orders','compileClasspath','1.0','DIRECT','PINNED',1,3)"));
        RetrievalRequest request = new RetrievalRequest(Intent.DEPENDENCY_PATH,
                List.of(new EntityRef(EntityKind.TOPIC, ExplainFixture.ORDERS_TOPIC, false),
                        new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, true)),
                List.of(), "Why does orders.events relate to svc-orders?", List.of(), false);

        Evidence evidence = collect(request);

        assertThat(texts(section(evidence, "Dependency path"))).containsExactly(
                ExplainFixture.SVC_NOTIFY + " -> " + ExplainFixture.SVC_ORDERS);
    }

    @Test
    void dependencyEdgesFromTwoModulesInOneRepoAreDistinguishableRatherThanIdenticalDuplicates() {
        // Two modules of svc-orders each declare the same dependency on lib-api. These are two
        // genuine dep_edge rows, not a join artifact — but a projection that drops the consuming
        // module renders them as the same sentence twice, which reads as a duplication bug and
        // hides the one fact that distinguishes them. Seen live on the trading estate, where
        // trading-ops' :e2e and :load modules both depend on com.trading:common-model.
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (3,':load','UNKNOWN')"); // module 7
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (7,'com.acme','lib-api','compileClasspath',NULL,'BOM','BOM_MANAGED',1,2)");
        });
        RetrievalRequest request = new RetrievalRequest(Intent.DEPENDENCY_PATH,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, false),
                        new EntityRef(EntityKind.REPO, ExplainFixture.LIB_API, true)),
                List.of(), "Why does svc-orders depend on lib-api?", List.of(), false);

        Evidence evidence = collect(request);

        List<String> hops = texts(section(evidence, "Dependency edges")).stream()
                .filter(t -> t.contains(ExplainFixture.SVC_ORDERS + " -> " + ExplainFixture.LIB_API)).toList();
        assertThat(hops).hasSize(2).doesNotHaveDuplicates();
        assertThat(hops).anySatisfy(t -> assertThat(t).contains("from_module=:"))
                .anySatisfy(t -> assertThat(t).contains("from_module=:load"));
    }

    // --- search ---------------------------------------------------------------------------------

    @Test
    void searchPreservesBestFirstOrderingAndIsStableAcrossTwoIdenticalCalls() {
        RetrievalRequest request = new RetrievalRequest(Intent.SEARCH, List.of(),
                List.of("PriceApi", "OrdersController"), "tell me about these symbols", List.of(), false);

        // Ground truth: the retriever's own best-first (ascending score) order for the exact same
        // query string SearchFacts joins internally, gathered independently of SearchFacts so this
        // test can actually falsify a reordering bug in SearchFacts rather than just re-deriving
        // whatever order it happened to produce.
        List<Hit> rawHits = retriever.search("PriceApi OrdersController", 50);
        assertThat(rawHits).hasSize(2); // sanity: both fixture symbols matched, nothing degenerate

        Evidence first = collect(request);
        Evidence second = collect(request);

        assertThat(first).isEqualTo(second);
        List<String> hits = texts(section(first, "Search hits"));
        assertThat(hits).hasSize(2);
        // best-first: SearchFacts' fact order must mirror the retriever's ascending-score order,
        // not just contain the same items — this fails if SearchFacts reverses or shuffles hits.
        assertThat(hits.get(0)).startsWith(rawHits.get(0).identifier() + " (");
        assertThat(hits.get(1)).startsWith(rawHits.get(1).identifier() + " (");
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

    // --- consumers: repo ------------------------------------------------------------------------

    @Test
    void consumersOnRepoHitsInboundGradleAndApiUsageEdges() {
        RetrievalRequest request = new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.LIB_API, false)),
                List.of(), "What consumes lib-api?", List.of(), false);

        Evidence evidence = collect(request);

        assertThat(texts(section(evidence, "Consumers via Gradle: " + ExplainFixture.LIB_API)))
                .containsExactly(ExplainFixture.SVC_ORDERS);
        assertThat(texts(section(evidence, "Consumers via API usage: " + ExplainFixture.LIB_API)))
                .containsExactly(ExplainFixture.SVC_ORDERS + " uses " + ExplainFixture.PRICE_API_FQCN + " (IMPORT)");
    }

    @Test
    void consumersViaApiUsageDoesNotRenderTheLiteralStringNullWhenRefKindIsMissing() {
        db.jdbi().useHandle(h -> h.createUpdate(
                        "UPDATE api_usage SET ref_kind = NULL WHERE target_fqcn = :fqcn")
                .bind("fqcn", ExplainFixture.PRICE_API_FQCN).execute());
        RetrievalRequest request = new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.LIB_API, false)),
                List.of(), "What consumes lib-api?", List.of(), false);

        Evidence evidence = collect(request);

        List<String> texts = texts(section(evidence, "Consumers via API usage: " + ExplainFixture.LIB_API));
        assertThat(texts).noneMatch(t -> t.contains("null"));
        assertThat(texts).containsExactly(ExplainFixture.SVC_ORDERS + " uses " + ExplainFixture.PRICE_API_FQCN);
    }

    @Test
    void consumersOnRepoHitsRestAndKafkaContractEdgesViaContractEdges() {
        RetrievalRequest request = new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, false)),
                List.of(), "What consumes svc-orders?", List.of(), false);

        Evidence evidence = collect(request);

        assertThat(texts(section(evidence, "Consumers via Gradle: " + ExplainFixture.SVC_ORDERS))).isEmpty();
        assertThat(texts(section(evidence, "Consumers via API usage: " + ExplainFixture.SVC_ORDERS))).isEmpty();
        assertThat(texts(section(evidence, "Consumers via REST (contract): " + ExplainFixture.SVC_ORDERS)))
                .containsExactly(ExplainFixture.SVC_BILLING + " calls GET /orders/{} on " + ExplainFixture.SVC_ORDERS
                        + " (confidence=HIGH, matched_by=FEIGN_NAME_PATH)");
        assertThat(texts(section(evidence, "Consumers via Kafka (contract): " + ExplainFixture.SVC_ORDERS)))
                .containsExactly(ExplainFixture.SVC_ORDERS + " produces " + ExplainFixture.ORDERS_TOPIC
                        + " consumed by " + ExplainFixture.SVC_NOTIFY);
    }

    @Test
    void consumersViaRestContractDoNotRenderTheLiteralStringNullForNullVerbConfidenceOrMatchedBy() {
        db.jdbi().useHandle(h -> {
            h.createUpdate("UPDATE rest_endpoint SET http_method = NULL WHERE id = 1").execute();
            h.createUpdate("UPDATE rest_call_edge SET confidence = NULL, matched_by = NULL "
                    + "WHERE client_id = 1 AND endpoint_id = 1").execute();
        });
        RetrievalRequest request = new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, false)),
                List.of(), "What consumes svc-orders?", List.of(), false);

        Evidence evidence = collect(request);

        List<String> texts = texts(section(evidence, "Consumers via REST (contract): " + ExplainFixture.SVC_ORDERS));
        assertThat(texts).noneMatch(t -> t.contains("null"));
        assertThat(texts).containsExactly(ExplainFixture.SVC_BILLING + " calls ANY /orders/{} on "
                + ExplainFixture.SVC_ORDERS + " ()");
    }

    @Test
    void consumersOnARepoWithZeroConsumersIsDistinguishableFromTheRepoNotBeingInTheKb() {
        // lib-legacy is genuinely indexed but has no gradle/api-usage/rest/kafka consumers at
        // all, so every ConsumerFacts section is empty -- without a citation fact for the repo
        // itself, Evidence.isEmpty() reads exactly like "lib-legacy is not in the KB", which is
        // the confusion sdd explain exists to prevent (non-repo kinds never have this problem:
        // their citation section always carries at least one fact).
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-legacy','/w/7','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (7,':','UNKNOWN')");
        });
        RetrievalRequest request = new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.REPO, "lib-legacy", false)),
                List.of(), "what consumes lib-legacy?", List.of(), false);

        Evidence evidence = collect(request);

        assertThat(evidence.isEmpty()).isFalse();
        assertThat(texts(section(evidence, "Resolved repo 'lib-legacy'"))).isNotEmpty();
    }

    // --- consumers: endpoint ---------------------------------------------------------------------

    @Test
    void consumersOnEndpointYieldsRestCallEdgeRowsWithVerbPathConfidenceAndMatchedBy() {
        RetrievalRequest request = new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.ENDPOINT, ExplainFixture.ORDERS_ENDPOINT, false)),
                List.of(), "What calls GET /orders/{id}?", List.of(), false);

        Evidence evidence = collect(request);

        assertThat(texts(section(evidence, "Consumers of endpoint: " + ExplainFixture.ORDERS_ENDPOINT)))
                .containsExactly(ExplainFixture.SVC_BILLING + " calls GET /orders/{} on " + ExplainFixture.SVC_ORDERS
                        + " (confidence=HIGH, matched_by=FEIGN_NAME_PATH)");
        assertThat(evidence.sections()).noneMatch(s -> s.title().startsWith("Endpoint match is ambiguous"));
    }

    @Test
    void consumersOnEndpointDoesNotRenderTheLiteralStringNullForNullConfidenceOrMatchedBy() {
        db.jdbi().useHandle(h -> h.createUpdate(
                        "UPDATE rest_call_edge SET confidence = NULL, matched_by = NULL "
                                + "WHERE client_id = 1 AND endpoint_id = 1")
                .execute());
        RetrievalRequest request = new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.ENDPOINT, ExplainFixture.ORDERS_ENDPOINT, false)),
                List.of(), "What calls GET /orders/{id}?", List.of(), false);

        Evidence evidence = collect(request);

        List<String> texts = texts(section(evidence, "Consumers of endpoint: " + ExplainFixture.ORDERS_ENDPOINT));
        assertThat(texts).noneMatch(t -> t.contains("null"));
        assertThat(texts).containsExactly(ExplainFixture.SVC_BILLING + " calls GET /orders/{} on "
                + ExplainFixture.SVC_ORDERS + " ()");
    }

    @Test
    void consumersOnAmbiguousEndpointYieldsEveryMatchPlusAnExplicitAmbiguityFact() {
        // a second repo (lib-core) happens to expose the exact same verb+path — Routes.templatesMatch
        // can legitimately do this — so the resolved endpoint value is genuinely ambiguous.
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (1,'OrdersControllerV2','get','GET','/orders/{id}','/orders/{}')");
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (5,'FEIGN','LibCoreOrdersClient','site','GET','/orders/{id}','/orders/{}','orders','LITERAL','raw2')");
            h.execute("INSERT INTO rest_call_edge(client_id, endpoint_id, confidence, matched_by) VALUES (2,2,'MEDIUM','PATH_ONLY')");
        });
        RetrievalRequest request = new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.ENDPOINT, ExplainFixture.ORDERS_ENDPOINT, false)),
                List.of(), "What calls GET /orders/{id}?", List.of(), false);

        Evidence evidence = collect(request);

        Section ambiguity = section(evidence, "Endpoint match is ambiguous: '" + ExplainFixture.ORDERS_ENDPOINT + "'");
        assertThat(ambiguity.facts()).singleElement().satisfies(f -> assertThat(f.text())
                .contains("matched 2 distinct endpoints")
                .contains(ExplainFixture.LIB_CORE + " (GET /orders/{})")
                .contains(ExplainFixture.SVC_ORDERS + " (GET /orders/{})"));
        // every match's consumers are rendered — never a silently chosen one
        assertThat(texts(section(evidence, "Consumers of endpoint: " + ExplainFixture.ORDERS_ENDPOINT)))
                .containsExactlyInAnyOrder(
                        ExplainFixture.SVC_NOTIFY + " calls GET /orders/{} on " + ExplainFixture.LIB_CORE
                                + " (confidence=MEDIUM, matched_by=PATH_ONLY)",
                        ExplainFixture.SVC_BILLING + " calls GET /orders/{} on " + ExplainFixture.SVC_ORDERS
                                + " (confidence=HIGH, matched_by=FEIGN_NAME_PATH)");
    }

    @Test
    void consumersOnAnEndpointDeclaredTwiceInOneRepoEmitsEachCallerFactExactlyOnce() {
        // the same verb+path on two controller methods of one repo: two rest_endpoint rows, two
        // EntityMatch values, but one (repo, verb, norm) triple — and callersOf is keyed on exactly
        // that triple, so querying per match would re-fetch the identical rows and say it twice.
        db.jdbi().useHandle(h -> h.execute(
                "INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                        + "VALUES (3,'OrdersAdminController','getForAdmin','GET','/orders/{id}','/orders/{}')"));
        RetrievalRequest request = new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.ENDPOINT, ExplainFixture.ORDERS_ENDPOINT, false)),
                List.of(), "What calls GET /orders/{id}?", List.of(), false);

        Evidence evidence = collect(request);

        assertThat(texts(section(evidence, "Consumers of endpoint: " + ExplainFixture.ORDERS_ENDPOINT)))
                .containsExactly(ExplainFixture.SVC_BILLING + " calls GET /orders/{} on " + ExplainFixture.SVC_ORDERS
                        + " (confidence=HIGH, matched_by=FEIGN_NAME_PATH)");
        // one endpoint declared twice is not two endpoints — nothing ambiguous to report
        assertThat(evidence.sections()).noneMatch(s -> s.title().startsWith("Endpoint match is ambiguous"));
    }

    @Test
    void consumersOnTwoDistinctEndpointsWithinOneRepoStillYieldsAnAmbiguityFact() {
        // a literal path no row matches exactly, so resolution falls back to templatesMatch, where
        // /orders/summary matches both /orders/{} and /{}/summary — two genuinely different
        // endpoints, both in svc-orders. Counting repos would see one repo and stay silent.
        db.jdbi().useHandle(h -> h.execute(
                "INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                        + "VALUES (3,'TenantSummaryController','summary','GET','/{tenant}/summary','/{}/summary')"));
        RetrievalRequest request = new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.ENDPOINT, "GET /orders/summary", false)),
                List.of(), "What calls GET /orders/summary?", List.of(), false);

        Evidence evidence = collect(request);

        Section ambiguity = section(evidence, "Endpoint match is ambiguous: 'GET /orders/summary'");
        assertThat(ambiguity.facts()).singleElement().satisfies(f -> assertThat(f.text())
                .contains("matched 2 distinct endpoints")
                .contains(ExplainFixture.SVC_ORDERS + " (GET /orders/{})")
                .contains(ExplainFixture.SVC_ORDERS + " (GET /{}/summary)"));
        // and both matches' consumers are still rendered, exactly as in the multi-repo case
        assertThat(texts(section(evidence, "Consumers of endpoint: GET /orders/summary")))
                .containsExactly(ExplainFixture.SVC_BILLING + " calls GET /orders/{} on " + ExplainFixture.SVC_ORDERS
                        + " (confidence=HIGH, matched_by=FEIGN_NAME_PATH)");
    }

    // --- consumers: class, topic, artifact --------------------------------------------------------

    @Test
    void consumersOnClassYieldsApiUsageGroupedByConsumerWithRefKind() {
        RetrievalRequest request = new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.CLASS, ExplainFixture.PRICE_API_FQCN, false)),
                List.of(), "What uses PriceApi?", List.of(), false);

        Evidence evidence = collect(request);

        assertThat(texts(section(evidence, "Consumers of class: " + ExplainFixture.PRICE_API_FQCN)))
                .containsExactly(ExplainFixture.SVC_ORDERS + " uses " + ExplainFixture.PRICE_API_FQCN + " (IMPORT)");
    }

    @Test
    void consumersOnClassDoesNotRenderTheLiteralStringNullWhenRefKindIsMissing() {
        db.jdbi().useHandle(h -> h.createUpdate(
                        "UPDATE api_usage SET ref_kind = NULL WHERE target_fqcn = :fqcn")
                .bind("fqcn", ExplainFixture.PRICE_API_FQCN).execute());
        RetrievalRequest request = new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.CLASS, ExplainFixture.PRICE_API_FQCN, false)),
                List.of(), "What uses PriceApi?", List.of(), false);

        Evidence evidence = collect(request);

        List<String> texts = texts(section(evidence, "Consumers of class: " + ExplainFixture.PRICE_API_FQCN));
        assertThat(texts).noneMatch(t -> t.contains("null"));
        assertThat(texts).containsExactly(ExplainFixture.SVC_ORDERS + " uses " + ExplainFixture.PRICE_API_FQCN);
    }

    @Test
    void consumersOnTopicYieldsBothRolesWithGroupIdAndPayloadTypeWhenPresent() {
        db.jdbi().useHandle(h -> h.execute(
                "UPDATE kafka_role SET group_id='notify-group', payload_type='OrderEvent' WHERE module_id = "
                        + "(SELECT id FROM module WHERE repo_id = (SELECT id FROM repo WHERE name = '"
                        + ExplainFixture.SVC_NOTIFY + "'))"));
        RetrievalRequest request = new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.TOPIC, ExplainFixture.ORDERS_TOPIC, false)),
                List.of(), "Who produces and consumes orders.events?", List.of(), false);

        Evidence evidence = collect(request);

        // both roles, ordered by repo name: svc-notify sorts before svc-orders
        assertThat(texts(section(evidence, "Topic roles: " + ExplainFixture.ORDERS_TOPIC))).containsExactly(
                ExplainFixture.SVC_NOTIFY + " (CONSUMER), group_id=notify-group, payload_type=OrderEvent",
                ExplainFixture.SVC_ORDERS + " (PRODUCER)");
    }

    @Test
    void consumersOnArtifactYieldsDepEdgeRowsByGroupAndName() {
        db.jdbi().useHandle(h -> h.execute(
                "INSERT INTO artifact(grp, name, module_id) VALUES ('com.acme','lib-api',2)"));
        RetrievalRequest request = new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.ARTIFACT, "com.acme:lib-api", false)),
                List.of(), "What depends on com.acme:lib-api?", List.of(), false);

        Evidence evidence = collect(request);

        // declared_version is NULL on this dep_edge row (a BOM-managed dependency, per the
        // fixture) and is omitted rather than rendered as the literal "null" -- see
        // Attributes.attributes / DependencyFacts.hopDetail's identical dep_edge NULL handling.
        assertThat(texts(section(evidence, "Consumers of artifact: com.acme:lib-api"))).containsExactly(
                ExplainFixture.SVC_ORDERS + " depends on com.acme:lib-api "
                        + "(configuration=compileClasspath, mode=BOM_MANAGED)");
    }

    @Test
    void consumersOnArtifactDoesNotRenderTheLiteralStringNullWhenConfigurationOrModeIsMissing() {
        // lib-api -> lib-core's dep_edge row (module 2) has configuration/mode set; null them out
        // to exercise the other two nullable dep_edge columns artifactConsumers renders (the
        // fixture's only NULL dep_edge column, declared_version, is already covered above).
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO artifact(grp, name, module_id) VALUES ('com.acme','lib-core',1)");
            h.createUpdate("UPDATE dep_edge SET configuration = NULL, mode = NULL "
                    + "WHERE from_module_id = 2 AND to_grp = 'com.acme' AND to_name = 'lib-core'").execute();
        });
        RetrievalRequest request = new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.ARTIFACT, "com.acme:lib-core", false)),
                List.of(), "What depends on com.acme:lib-core?", List.of(), false);

        Evidence evidence = collect(request);

        List<String> texts = texts(section(evidence, "Consumers of artifact: com.acme:lib-core"));
        assertThat(texts).noneMatch(t -> t.contains("null"));
        assertThat(texts).containsExactly(
                ExplainFixture.LIB_API + " depends on com.acme:lib-core (declared_version=1.0)");
    }

    // --- impact -------------------------------------------------------------------------------------

    @Test
    void impactRendersRootsExplicitlyThenAddedThenCyclesThenWarnings() {
        RetrievalRequest request = new RetrievalRequest(Intent.IMPACT,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.LIB_CORE, false)),
                List.of(), "What breaks if lib-core changes?", List.of(), false);

        Evidence evidence = collect(request);

        // the regression this guards against: Closure.expand's `added` never includes the roots
        // it was seeded with, so an impact answer that skips rendering roots explicitly would
        // never name the very repo the question was about.
        assertThat(texts(section(evidence, "Impact: root repo(s)"))).containsExactly(ExplainFixture.LIB_CORE);
        List<String> added = texts(section(evidence, "Impact: affected repos"));
        assertThat(added).noneMatch(t -> t.startsWith(ExplainFixture.LIB_CORE + " —"));
        assertThat(added)
                .anySatisfy(t -> assertThat(t).contains(ExplainFixture.LIB_API).contains("dependent/BUMP_REBUILD_ONLY"))
                .anySatisfy(t -> assertThat(t).contains(ExplainFixture.SVC_ORDERS).contains("dependent/CODE_CHANGE_LIKELY"))
                .anySatisfy(t -> assertThat(t).contains(ExplainFixture.PLATFORM).contains("bom-site/BOM_DECLARATION_SITE"))
                .anySatisfy(t -> assertThat(t).contains(ExplainFixture.SVC_BILLING).contains("contract/PENDING_CONTRACT")
                        .contains("GET /orders/{}"))
                .anySatisfy(t -> assertThat(t).contains(ExplainFixture.SVC_NOTIFY).contains("contract/PENDING_CONTRACT")
                        .contains(ExplainFixture.ORDERS_TOPIC));
        assertThat(texts(section(evidence, "Impact: dependency cycles"))).isEmpty();
        assertThat(texts(section(evidence, "Impact: warnings")))
                .anySatisfy(t -> assertThat(t).contains(ExplainFixture.SVC_ORDERS).contains("DEGRADED"));

        List<String> titles = evidence.sections().stream().map(Section::title).toList();
        int rootsIdx = titles.indexOf("Impact: root repo(s)");
        int addedIdx = titles.indexOf("Impact: affected repos");
        int cyclesIdx = titles.indexOf("Impact: dependency cycles");
        int warningsIdx = titles.indexOf("Impact: warnings");
        assertThat(rootsIdx).isGreaterThanOrEqualTo(0).isLessThan(addedIdx);
        assertThat(addedIdx).isLessThan(cyclesIdx);
        assertThat(cyclesIdx).isLessThan(warningsIdx);
    }

    // --- absence guard --------------------------------------------------------------------------

    @Test
    void absenceCaveatIsAlwaysPresentEvenWithZeroCounts() {
        RetrievalRequest request = new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.LIB_CORE, false)),
                List.of(), "What consumes lib-core?", List.of(), false);

        Evidence evidence = collect(request);

        assertThat(evidence.caveats()).singleElement().satisfies(c -> assertThat(c)
                .contains("0 rest_client row(s)").contains("0 kafka_topic row(s)")
                .contains(ExplainFixture.LIB_CORE));
    }

    @Test
    void absenceCaveatForConsumersFiresWithRealCountsScopedToTheAskedAboutRepo() {
        db.jdbi().useHandle(h -> {
            // in scope: svc-orders is the repo actually being asked about
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (3,'FEIGN','MysteryClient','site','GET',NULL,NULL,NULL,'DYNAMIC','svcOrders.mystery()')");
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('mystery.orders','DYNAMIC')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (3,2,'PRODUCER')");
            // out of scope: svc-billing was never asked about here — a whole-estate scope would
            // wrongly count this too
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (4,'FEIGN','OutOfScopeClient','site','GET',NULL,NULL,NULL,'DYNAMIC','svcBilling.mystery()')");
        });
        RetrievalRequest request = new RetrievalRequest(Intent.CONSUMERS,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, false)),
                List.of(), "What consumes svc-orders?", List.of(), false);

        Evidence evidence = collect(request);

        assertThat(evidence.caveats()).singleElement().satisfies(c -> assertThat(c)
                .contains("1 rest_client row(s)")
                .contains("1 kafka_topic row(s)")
                .contains(ExplainFixture.SVC_ORDERS)
                .doesNotContain(ExplainFixture.SVC_BILLING));
    }

    @Test
    void absenceCaveatForImpactIsScopedToRootsUnionAddedNotJustRootsNorTheWholeEstate() {
        // roots = {lib-api}: Closure.expand pulls in svc-orders (dependent), platform (bom-site),
        // svc-billing and svc-notify (contracts on svc-orders) — everything except lib-core, which
        // is a pure leaf nothing here depends *from*, so it never enters the affected set. That
        // makes lib-core the deliberate "out of scope" repo below, distinguishing "affected set"
        // scoping (this) from "the whole estate" (would count it too).
        db.jdbi().useHandle(h -> {
            // in scope: svc-orders is in the affected set (added as a dependent) even though it
            // is not itself a root — this is what distinguishes "affected set" from "roots only".
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (3,'FEIGN','MysteryClient','site','GET',NULL,NULL,NULL,'DYNAMIC','svcOrders.mystery()')");
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('mystery.orders','DYNAMIC')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (3,2,'PRODUCER')");
            // out of scope: lib-core is never in the affected set for roots {lib-api} — a
            // whole-estate scope would wrongly count this too.
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (1,'FEIGN','OutOfScopeClient','site','GET',NULL,NULL,NULL,'DYNAMIC','libCore.mystery()')");
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('mystery.core','DYNAMIC')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (1,3,'CONSUMER')");
        });
        RetrievalRequest request = new RetrievalRequest(Intent.IMPACT,
                List.of(new EntityRef(EntityKind.REPO, ExplainFixture.LIB_API, false)),
                List.of(), "What breaks if lib-api changes?", List.of(), false);

        Evidence evidence = collect(request);

        assertThat(evidence.caveats()).singleElement().satisfies(c -> assertThat(c)
                .contains("1 rest_client row(s)")
                .contains("1 kafka_topic row(s)")
                .contains(ExplainFixture.LIB_API).contains(ExplainFixture.SVC_ORDERS)
                .doesNotContain(ExplainFixture.LIB_CORE));
    }

    // --- javadoc firewall -------------------------------------------------------------------------

    @Test
    void noEvidenceSectionOutsideSearchRendersTextTakenFromTypeJavadoc() {
        // Javadoc rots, and nothing in this pipeline checks it against the code. So it is allowed
        // to make a type *findable* — it lives in java_type.javadoc and fts_symbol.doc, and the
        // search intent surfaces it, labelled — but it may never become a *fact*: no describe,
        // consumers, dependency-path or impact section may put unverified prose in front of a
        // reader as if the KB had derived it. That is true today because those collectors are
        // pure SQL over structural tables; this test is what keeps it true.
        String javadoc = "Closes the " + JAVADOC_SENTINEL + " between an admin PUT and the watcher.";
        db.jdbi().useHandle(h -> {
            h.createUpdate("UPDATE java_type SET javadoc = :doc WHERE fqcn = :fqcn")
                    .bind("doc", javadoc).bind("fqcn", ExplainFixture.PRICE_API_FQCN).execute();
            // ...and the same text in the search corpus, as the indexer would have written it.
            // Raw SQL rather than FtsSymbolWriter, which is otherwise the sole write path for this
            // table: it deletes by module and by repo, never by identifier, and widening its API to
            // suit one fixture would be the wrong direction — the point of the rule is that
            // production has exactly two ways to remove rows. The insert below still goes through it.
            h.execute("DELETE FROM fts_symbol WHERE identifier = 'PriceApi'");
            FtsSymbolWriter.insert(h, 2, "PriceApi", ExplainFixture.PRICE_API_FQCN, javadoc);
        });

        // Positive control: the sentinel really is in this KB and really is reachable, so the
        // assertions below fail because the firewall holds, not because nothing was seeded.
        assertThat(allTexts(collect(new RetrievalRequest(Intent.SEARCH, List.of(),
                List.of(JAVADOC_SENTINEL), "find it", List.of(), false))))
                .anySatisfy(t -> assertThat(t).contains("PriceApi").contains("matched on javadoc"));

        List<RetrievalRequest> nonSearch = List.of(
                new RetrievalRequest(Intent.DESCRIBE,
                        List.of(new EntityRef(EntityKind.REPO, ExplainFixture.LIB_API, false),
                                new EntityRef(EntityKind.CLASS, ExplainFixture.PRICE_API_FQCN, false)),
                        List.of(), "What is lib-api, and what is PriceApi?", List.of(), false),
                new RetrievalRequest(Intent.CONSUMERS,
                        List.of(new EntityRef(EntityKind.CLASS, ExplainFixture.PRICE_API_FQCN, false)),
                        List.of(), "What uses PriceApi?", List.of(), false),
                new RetrievalRequest(Intent.DEPENDENCY_PATH,
                        List.of(new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, false),
                                new EntityRef(EntityKind.REPO, ExplainFixture.LIB_API, true)),
                        List.of(), "Why does svc-orders depend on lib-api?", List.of(), false),
                new RetrievalRequest(Intent.IMPACT,
                        List.of(new EntityRef(EntityKind.REPO, ExplainFixture.LIB_API, false)),
                        List.of(), "What breaks if lib-api changes?", List.of(), false));

        for (RetrievalRequest request : nonSearch) {
            Evidence evidence = collect(request);
            List<String> texts = allTexts(evidence);
            // the type carrying the javadoc is genuinely in this answer — otherwise "no section
            // mentions the sentinel" would be trivially true
            assertThat(texts).as("%s facts", request.intent())
                    .anyMatch(t -> t.contains(ExplainFixture.PRICE_API_FQCN)
                            || t.contains(ExplainFixture.LIB_API));
            assertThat(texts).as("%s facts", request.intent())
                    .noneMatch(t -> t.contains(JAVADOC_SENTINEL));
            assertThat(evidence.caveats()).as("%s caveats", request.intent())
                    .noneMatch(c -> c.contains(JAVADOC_SENTINEL));
        }
    }

    // --- no third model call ---------------------------------------------------------------------

    @Test
    void impactCollectionAddsNoModelCallBetweenInterpretationAndNarration() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("""
                        {"intent":"impact","restatement":"What breaks if lib-core changes?",
                         "entities":[{"kind":"repo","value":"lib-core"}],"search_terms":[]}"""),
                        "stop", new Usage(1, 1)),
                new ChatResponse(ChatMessage.assistant("a narrated answer"), "stop", new Usage(1, 1))));

        // call 1: interpretation
        RetrievalRequest request = QuestionInterpreter.interpret(db.jdbi(), retriever, "what breaks if lib-core changes",
                model, "m", 512);
        assertThat(request.intent()).isEqualTo(Intent.IMPACT);
        assertThat(model.requests()).hasSize(1);

        // the deterministic fetch: Closure.expand and everything else here is plain SQL
        Evidence evidence = collect(request);
        assertThat(model.requests()).hasSize(1); // unchanged — collect() never touched the model
        assertThat(evidence.sections()).isNotEmpty();

        // stand-in for call 2 (AnswerNarrator, a later task) — proves the total never exceeds two:
        // no third call snuck in between interpretation and narration.
        model.complete(new ChatRequest("m", List.of(ChatMessage.user("narrate this evidence")), List.of(), 512, 0.2));
        assertThat(model.requests()).hasSize(2);
    }
}
