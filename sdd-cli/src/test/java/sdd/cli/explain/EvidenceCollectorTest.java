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
import sdd.core.retrieve.Hit;
import sdd.core.retrieve.Retriever;
import sdd.core.testing.ScriptedChatModel;

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
                .contains("matched 2 distinct repos")
                .contains(ExplainFixture.LIB_CORE).contains(ExplainFixture.SVC_ORDERS));
        // every match's consumers are rendered — never a silently chosen one
        assertThat(texts(section(evidence, "Consumers of endpoint: " + ExplainFixture.ORDERS_ENDPOINT)))
                .containsExactlyInAnyOrder(
                        ExplainFixture.SVC_NOTIFY + " calls GET /orders/{} on " + ExplainFixture.LIB_CORE
                                + " (confidence=MEDIUM, matched_by=PATH_ONLY)",
                        ExplainFixture.SVC_BILLING + " calls GET /orders/{} on " + ExplainFixture.SVC_ORDERS
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

        assertThat(texts(section(evidence, "Consumers of artifact: com.acme:lib-api"))).containsExactly(
                ExplainFixture.SVC_ORDERS + " depends on com.acme:lib-api "
                        + "(configuration=compileClasspath, declared_version=null, mode=BOM_MANAGED)");
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
        RetrievalRequest request = QuestionInterpreter.interpret(db.jdbi(), "what breaks if lib-core changes",
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
