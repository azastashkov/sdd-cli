package sdd.plan.gen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class OpenQuestionsTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-pricing','/w/2','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-lonely','/w/3','SERVICE')");
            for (int i = 1; i <= 3; i++) {
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (" + i + ",':','UNKNOWN')");
            }
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (2,'PriceController','get','GET','/price/{id}','/price/{}')");
            // one DYNAMIC client somewhere in the estate
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (3,'RESTTEMPLATE','Dyn','site','GET',NULL,NULL,NULL,'DYNAMIC','raw')");
        });
    }

    @Test
    void mapsProblemsDiscrepanciesStatusesDisconnectionAndDynamicCallers() {
        ImpactResult result = new ImpactResult(List.of(),
                List.of(new AffectedRepo("lib-core", "seed", "SEED", List.of(), List.of()),
                        new AffectedRepo("svc-pricing", "dependent", "CODE_CHANGE_LIKELY", List.of(), List.of()),
                        new AffectedRepo("svc-lonely", "seed", "SEED", List.of(), List.of())),
                List.of(), List.of(),
                List.of("model-only: svc-lonely (hunch)"),
                List.of("no repo covers R2"),
                List.of("affected repo svc-pricing indexed with status DEGRADED — downgrade confidence in its facts",
                        "model seeding unavailable: connection refused"));

        List<Question> questions = OpenQuestions.detect(db.jdbi(), result);

        assertThat(questions).extracting(Question::text, Question::blocking).containsExactly(
                tuple("no repo covers R2", true),
                tuple("model/graph discrepancy: model-only: svc-lonely (hunch)", false),
                tuple("affected repo svc-pricing indexed with status DEGRADED — downgrade confidence in its facts", false),
                tuple("seed svc-lonely is disconnected from the rest of the affected set — verify the spec's scope", false),
                tuple("1 unresolved (DYNAMIC) REST clients exist in the estate — callers of affected endpoints may be missing (see curation report)", false));
        // note: 'model seeding unavailable' warning is NOT a question (it lacks 'indexed with status');
        // lib-core is connected via the gradle edge to svc-pricing, so no disconnection question for it
    }

    @Test
    void dynamicKafkaTopicsRaiseAQuestionWhenAffectedReposUseKafka() {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('orders.events','LITERAL')");
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('#{dyn}','DYNAMIC')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (2,1,'PRODUCER')");
        });
        ImpactResult result = new ImpactResult(List.of(),
                List.of(new AffectedRepo("svc-pricing", "seed", "SEED", List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of());

        List<Question> questions = OpenQuestions.detect(db.jdbi(), result);

        assertThat(questions).anySatisfy(q -> assertThat(q.text()).isEqualTo(
                "1 unresolved (DYNAMIC) Kafka topics exist in the estate"
                        + " — messaging links touching affected repos may be missing (see curation report)"));
    }

    @Test
    void singleRepoAffectedSetRaisesNoDisconnectionAndNoDynamicQuestionWithoutEndpoints() {
        ImpactResult result = new ImpactResult(List.of(),
                List.of(new AffectedRepo("svc-lonely", "seed", "SEED", List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of());

        List<Question> questions = OpenQuestions.detect(db.jdbi(), result);

        assertThat(questions).isEmpty();   // one repo => no disconnection; svc-lonely owns no endpoints => no DYNAMIC question
    }
}
