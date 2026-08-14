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

    // svc-billing calls svc-orders' endpoint (REST); svc-notify consumes a topic svc-orders
    // produces (Kafka); svc-orders also calls its own endpoint (same-repo, must be excluded).
    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-orders','/w/1','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-billing','/w/2','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-notify','/w/3','SERVICE')");
            for (int i = 1; i <= 3; i++) {
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (" + i + ",':','UNKNOWN')");
            }
            // REST: svc-billing's client calls svc-orders' endpoint
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (1,'OrdersController','get','GET','/orders/{id}','/orders/{}')");
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (2,'FEIGN','OrdersClient','site','GET','/orders/{id}','/orders/{}','orders','LITERAL','raw')");
            h.execute("INSERT INTO rest_call_edge(client_id, endpoint_id, confidence, matched_by) VALUES (1,1,'HIGH','FEIGN_NAME_PATH')");
            // same-repo REST call: svc-orders calls its own endpoint — must be excluded (rc.name <> rp.name)
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (1,'FEIGN','SelfClient','site','GET','/orders/{id}','/orders/{}','orders','LITERAL','raw')");
            h.execute("INSERT INTO rest_call_edge(client_id, endpoint_id, confidence, matched_by) VALUES (2,1,'LOW','SELF')");
            // Kafka: svc-orders produces orders.events, svc-notify consumes it
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('orders.events','LITERAL')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (1,1,'PRODUCER')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (3,1,'CONSUMER')");
        });
    }

    @Test
    void restEdgesExcludeSameRepoAndOrderByConsumerThenProvider() {
        var edges = ContractEdges.rest(db.jdbi());

        assertThat(edges).extracting(ContractEdges.RestEdge::consumerRepo, ContractEdges.RestEdge::providerRepo,
                        ContractEdges.RestEdge::verb, ContractEdges.RestEdge::normPath,
                        ContractEdges.RestEdge::confidence, ContractEdges.RestEdge::matchedBy)
                .containsExactly(tuple("svc-billing", "svc-orders", "GET", "/orders/{}", "HIGH", "FEIGN_NAME_PATH"));
    }

    @Test
    void kafkaEdgesExcludeSameRepoAndOrderByProducerThenConsumer() {
        var edges = ContractEdges.kafka(db.jdbi());

        assertThat(edges).extracting(ContractEdges.KafkaEdge::producerRepo, ContractEdges.KafkaEdge::consumerRepo,
                        ContractEdges.KafkaEdge::topic)
                .containsExactly(tuple("svc-orders", "svc-notify", "orders.events"));
    }
}
