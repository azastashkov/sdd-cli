package sdd.index.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.index.spring.SpringModel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringPersistenceTest {
    @TempDir Path ws;
    private Database db;
    private long moduleId;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc', '/w/svc', 'SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1, ':', 'SERVICE')");
            moduleId = h.createQuery("SELECT id FROM module").mapTo(Long.class).one();
        });
    }

    private static SpringModel.SpringExtract extract(boolean stream) {
        return new SpringModel.SpringExtract(
                List.of(new SpringModel.EndpointInfo("com.acme.C", "get", "GET",
                        "/api/orders/{id}", null, "String")),
                List.of(new SpringModel.ClientInfo("FEIGN", "com.acme.B", "charge", "POST",
                        "/pay/charge", "billing", "LITERAL", "@PostMapping(\"/charge\")")),
                List.of(new SpringModel.KafkaUse("orders.v1", "PRODUCER", "com.acme.K",
                        null, "java.lang.String", "CONSTANT", "OUT_TOPIC")),
                stream);
    }

    @Test
    void persistsEndpointsClientsKafkaWithContextPathPrepend() {
        db.jdbi().useHandle(h ->
                SpringPersistence.persistModuleSpring(h, moduleId, "/orders", extract(false)));

        Map<String, Object> ep = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT http_method, path_template, norm_path FROM rest_endpoint").mapToMap().one());
        assertThat(ep).containsEntry("path_template", "/api/orders/{id}")
                .containsEntry("norm_path", "/orders/api/orders/{}");
        Map<String, Object> cl = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT kind, target_hint, norm_path, resolution FROM rest_client").mapToMap().one());
        assertThat(cl).containsEntry("kind", "FEIGN").containsEntry("target_hint", "billing")
                .containsEntry("norm_path", "/pay/charge").containsEntry("resolution", "LITERAL");
        Map<String, Object> role = db.jdbi().withHandle(h -> h.createQuery("""
                SELECT t.name, r.role FROM kafka_role r JOIN kafka_topic t ON t.id = r.topic_id""")
                .mapToMap().one());
        assertThat(role).containsEntry("name", "orders.v1").containsEntry("role", "PRODUCER");
        String kafkaStatus = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT kafka_status FROM module").mapTo(String.class).one());
        assertThat(kafkaStatus).isNull();
    }

    @Test
    void repersistReplacesRowsAndTopicUpsertDoesNotDuplicate() {
        db.jdbi().useHandle(h -> {
            SpringPersistence.persistModuleSpring(h, moduleId, null, extract(false));
            SpringPersistence.persistModuleSpring(h, moduleId, null, extract(true));
        });
        Integer epCount = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT count(*) FROM rest_endpoint").mapTo(Integer.class).one());
        assertThat(epCount).isEqualTo(1);
        Integer topicCount = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT count(*) FROM kafka_topic").mapTo(Integer.class).one());
        assertThat(topicCount).isEqualTo(1);
        Integer roleCount = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT count(*) FROM kafka_role").mapTo(Integer.class).one());
        assertThat(roleCount).isEqualTo(1);
        String kafkaStatus = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT kafka_status FROM module").mapTo(String.class).one());
        assertThat(kafkaStatus).isEqualTo("UNPARSED_STREAM");
    }
}
