package sdd.plan.gen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionOrderTest {
    @TempDir Path ws;
    private Database db;

    // lib-core <- lib-api <- svc-orders (gradle chain); svc-billing REST-calls svc-orders;
    // platform is an isolated bom-site node
    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-api','/w/2','LIBRARY')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-orders','/w/3','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-billing','/w/4','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('platform','/w/5','LIBRARY')");
            for (int i = 1; i <= 5; i++) {
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (" + i + ",':','UNKNOWN')");
            }
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (3,'com.acme','lib-api','compileClasspath','1.0','DIRECT','PINNED',1,2)");
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (3,'OrdersController','get','GET','/orders/{id}','/orders/{}')");
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (4,'FEIGN','OrdersClient','site','GET','/orders/{id}','/orders/{}','orders','LITERAL','raw')");
            h.execute("INSERT INTO rest_call_edge(client_id, endpoint_id, confidence, matched_by) VALUES (1,1,'HIGH','FEIGN_NAME_PATH')");
        });
    }

    private static AffectedRepo repo(String name, String role, String annotation) {
        return new AffectedRepo(name, role, annotation, List.of(), List.of());
    }

    private static ImpactResult result(List<AffectedRepo> affected, List<String> cycles) {
        return new ImpactResult(List.of(), affected, List.of(), cycles, List.of(), List.of(), List.of());
    }

    @Test
    void providersComeFirstContractConsumersAfterTheirProvidersIsolatedNodesAlphabetical() {
        ImpactResult result = result(List.of(
                repo("lib-core", "seed", "SEED"),
                repo("lib-api", "dependent", "BUMP_REBUILD_ONLY"),
                repo("svc-orders", "dependent", "CODE_CHANGE_LIKELY"),
                repo("svc-billing", "contract", "PENDING_CONTRACT"),
                repo("platform", "bom-site", "BOM_DECLARATION_SITE")), List.of());

        List<ExecutionOrder.Unit> order = ExecutionOrder.order(db.jdbi(), result);

        assertThat(order).extracting(u -> u.repos().get(0)).containsExactly(
                "lib-core", "lib-api", "platform", "svc-orders", "svc-billing");
        // lib-core and platform are both ready at the start: alphabetical => lib-core first;
        // emitting lib-core makes lib-api ready, and 'lib-api' < 'platform', so lib-api is
        // emitted before platform (newly-ready units interleave into the alphabetical pool);
        // svc-billing is held back by the REST pseudo-edge until svc-orders is emitted
    }

    @Test
    void kafkaProducerPrecedesConsumerViaPseudoEdge() {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('orders.events','LITERAL')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (3,1,'PRODUCER')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (5,1,'CONSUMER')");
        });
        ImpactResult result = result(List.of(
                repo("platform", "contract", "PENDING_CONTRACT"),
                repo("svc-orders", "seed", "SEED")), List.of());

        List<ExecutionOrder.Unit> order = ExecutionOrder.order(db.jdbi(), result);

        // without the kafka pseudo-edge, 'platform' would win alphabetically — genuine pin
        assertThat(order).extracting(u -> u.repos().get(0)).containsExactly("svc-orders", "platform");
    }

    @Test
    void cycleMembersFormOneCoScheduledUnit() {
        db.jdbi().useHandle(h -> h.execute(
                "INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (1,'com.acme','lib-api','compileClasspath','1.0','DIRECT','PINNED',1,2)"));
        ImpactResult result = result(List.of(
                repo("lib-core", "seed", "SEED"),
                repo("lib-api", "dependent", "CODE_CHANGE_LIKELY"),
                repo("svc-orders", "dependent", "CODE_CHANGE_LIKELY")),
                List.of("lib-api <-> lib-core"));

        List<ExecutionOrder.Unit> order = ExecutionOrder.order(db.jdbi(), result);

        assertThat(order).hasSize(2);
        assertThat(order.get(0).repos()).containsExactly("lib-api", "lib-core");
        assertThat(order.get(1).repos()).containsExactly("svc-orders");
    }

    @Test
    void edgesDedupsMultipleContractDetailsBetweenTheSameRepoPair() {
        // ContractEdges.rest/kafka project detail columns (verb/path/confidence/matched_by,
        // topic) that are DISTINCT over, so two different endpoints (or topics) between the same
        // repo pair now yield two rows, not one -- edges() must collapse them back to one
        // [provider, consumer] pair itself; PlanValidator relies on that to avoid printing the
        // same "execution order violates dependency" line once per contract detail.
        db.jdbi().useHandle(h -> {
            // second REST endpoint svc-orders exposes, also called by svc-billing
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (3,'InvoicesController','get','GET','/invoices/{id}','/invoices/{}')");
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (4,'FEIGN','InvoicesClient','site','GET','/invoices/{id}','/invoices/{}','invoices','LITERAL','raw2')");
            h.execute("INSERT INTO rest_call_edge(client_id, endpoint_id, confidence, matched_by) VALUES (2,2,'HIGH','FEIGN_NAME_PATH')");
            // two kafka topics, same producer/consumer repo pair
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('billing.events.a','LITERAL')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (3,1,'PRODUCER')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (4,1,'CONSUMER')");
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('billing.events.b','LITERAL')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (3,2,'PRODUCER')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (4,2,'CONSUMER')");
        });
        Set<String> affected = Set.of("svc-orders", "svc-billing");

        List<String[]> edges = ExecutionOrder.edges(db.jdbi(), affected);

        long restPairs = edges.stream()
                .filter(e -> e[0].equals("svc-orders") && e[1].equals("svc-billing")).count();
        assertThat(restPairs).isEqualTo(1);
    }

    @Test
    void pseudoEdgeDeadlockAppendsRemainderAlphabetically() {
        // two services REST-calling each other, no gradle edges: pseudo-edges form a 2-cycle
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (4,'BillingController','get','GET','/bill/{id}','/bill/{}')");
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (3,'FEIGN','BillingClient','site','GET','/bill/{id}','/bill/{}','billing','LITERAL','raw')");
            h.execute("INSERT INTO rest_call_edge(client_id, endpoint_id, confidence, matched_by) VALUES (2,2,'HIGH','FEIGN_NAME_PATH')");
        });
        ImpactResult result = result(List.of(
                repo("svc-orders", "seed", "SEED"),
                repo("svc-billing", "contract", "PENDING_CONTRACT")), List.of());

        List<ExecutionOrder.Unit> order = ExecutionOrder.order(db.jdbi(), result);

        assertThat(order).extracting(u -> u.repos().get(0))
                .containsExactly("svc-billing", "svc-orders");   // deadlock => alphabetical remainder
    }
}
