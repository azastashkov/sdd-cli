package sdd.core.kb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Same estate/edges as sdd-plan's ClosureTest and ExecutionOrderTest — this is the extracted
 * query those two (and OpenQuestions, which keeps its own unfiltered copy) rely on, so it must
 * produce the same edges, in the same order, plus the detail columns those callers previously
 * projected away.
 */
class ContractEdgesTest {
    @TempDir Path ws;
    private Database db;

    // svc-billing AND svc-alpha both call svc-orders' endpoint (REST); svc-notify AND
    // svc-analytics both consume a topic svc-orders produces (Kafka); svc-orders also calls its
    // own endpoint (same-repo, must be excluded). Each pair is inserted in reverse-alphabetical
    // order so a passing "ordered by consumer name" / "ordered by consumer name" assertion can
    // only be satisfied by a real ORDER BY, not by reproducing insertion (rowid) order.
    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-orders','/w/1','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-billing','/w/2','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-notify','/w/3','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-alpha','/w/4','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-analytics','/w/5','SERVICE')");
            for (int i = 1; i <= 5; i++) {
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (" + i + ",':','UNKNOWN')");
            }
            // REST: svc-orders exposes one endpoint that two different repos call.
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (1,'OrdersController','get','GET','/orders/{id}','/orders/{}')");
            // inserted first: svc-billing ('b')
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (2,'FEIGN','OrdersClient','site','GET','/orders/{id}','/orders/{}','orders','LITERAL','raw')");
            h.execute("INSERT INTO rest_call_edge(client_id, endpoint_id, confidence, matched_by) VALUES (1,1,'HIGH','FEIGN_NAME_PATH')");
            // same-repo REST call: svc-orders calls its own endpoint — must be excluded (rc.name <> rp.name)
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (1,'FEIGN','SelfClient','site','GET','/orders/{id}','/orders/{}','orders','LITERAL','raw')");
            h.execute("INSERT INTO rest_call_edge(client_id, endpoint_id, confidence, matched_by) VALUES (2,1,'LOW','SELF')");
            // inserted second: svc-alpha ('a') — sorts BEFORE svc-billing; reverse-alphabetical insertion
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (4,'FEIGN','AlphaClient','site','GET','/orders/{id}','/orders/{}','orders','LITERAL','raw')");
            h.execute("INSERT INTO rest_call_edge(client_id, endpoint_id, confidence, matched_by) VALUES (3,1,'MEDIUM','FEIGN_NAME_ONLY')");
            // Kafka: svc-orders produces orders.events, two different repos consume it.
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('orders.events','LITERAL')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (1,1,'PRODUCER')");
            // inserted first: svc-notify ('n')
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (3,1,'CONSUMER')");
            // inserted second: svc-analytics ('a') — sorts BEFORE svc-notify; reverse-alphabetical insertion
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (5,1,'CONSUMER')");
        });
    }

    @Test
    void restEdgesExcludeSameRepoAndOrderByConsumerThenProvider() {
        var edges = ContractEdges.rest(db.jdbi());

        assertThat(edges).extracting(ContractEdges.RestEdge::consumerRepo, ContractEdges.RestEdge::providerRepo,
                        ContractEdges.RestEdge::verb, ContractEdges.RestEdge::normPath,
                        ContractEdges.RestEdge::confidence, ContractEdges.RestEdge::matchedBy)
                .containsExactly(
                        // alphabetically first by consumer, despite being inserted second
                        tuple("svc-alpha", "svc-orders", "GET", "/orders/{}", "MEDIUM", "FEIGN_NAME_ONLY"),
                        tuple("svc-billing", "svc-orders", "GET", "/orders/{}", "HIGH", "FEIGN_NAME_PATH"));
    }

    @Test
    void kafkaEdgesExcludeSameRepoAndOrderByProducerThenConsumer() {
        var edges = ContractEdges.kafka(db.jdbi());

        assertThat(edges).extracting(ContractEdges.KafkaEdge::producerRepo, ContractEdges.KafkaEdge::consumerRepo,
                        ContractEdges.KafkaEdge::topic)
                .containsExactly(
                        // alphabetically first by consumer, despite being inserted second
                        tuple("svc-orders", "svc-analytics", "orders.events"),
                        tuple("svc-orders", "svc-notify", "orders.events"));
    }
}
