package sdd.core.kb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Same fixture/scenarios as sdd-plan's SeedFinderTest — this is the extracted logic those
 * touchpoint resolutions delegate to, so it must resolve every kind identically, plus expose
 * the specific matched row (explain's citation).
 */
class KbEntitiesTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-pricing','/w/p','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-orders','/w/o','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/l','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind, spring_app_name) VALUES (1,':','SERVICE','pricing')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind, spring_app_name) VALUES (2,':','SERVICE','orders')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (3,':','LIBRARY')");
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (1,'PriceController','get','GET','/price/{sku}','/price/{}')");
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('orders.events','LITERAL')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (2,1,'PRODUCER')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (1,1,'CONSUMER')");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (3,'com.acme.pricing.LoyaltyTier','CLASS')");
            h.execute("INSERT INTO artifact(grp, name, module_id) VALUES ('com.acme','lib-core',3)");
        });
    }

    @Test
    void repoResolvesByExactName() {
        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.REPO, "svc-pricing");

        assertThat(r.isEmpty()).isFalse();
        assertThat(r.repos()).containsExactly("svc-pricing");
        assertThat(r.matches()).extracting(EntityMatch::repo, EntityMatch::detail, EntityMatch::source)
                .containsExactly(tuple("svc-pricing", "svc-pricing", "repo"));
    }

    @Test
    void repoMissReturnsEmptyResolutionAndMissReason() {
        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.REPO, "ghost");

        assertThat(r.isEmpty()).isTrue();
        assertThat(r.repos()).isEmpty();
        assertThat(r.matches()).isEmpty();
        assertThat(KbEntities.missReason(EntityKind.REPO)).isEqualTo("no such repo in the knowledge base");
    }

    @Test
    void endpointWithLeadingVerbMatchesTemplate() {
        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.ENDPOINT, "GET /price/1");

        assertThat(r.repos()).containsExactly("svc-pricing");
        assertThat(r.matches()).extracting(EntityMatch::repo, EntityMatch::detail, EntityMatch::source)
                .containsExactly(tuple("svc-pricing", "GET /price/{}", "rest_endpoint"));
    }

    @Test
    void endpointWithoutVerbMatchesAnyVerb() {
        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.ENDPOINT, "/price/1");

        assertThat(r.repos()).containsExactly("svc-pricing");
    }

    @Test
    void endpointMissReturnsMissReason() {
        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.ENDPOINT, "DELETE /nope");

        assertThat(r.isEmpty()).isTrue();
        assertThat(KbEntities.missReason(EntityKind.ENDPOINT)).isEqualTo("no endpoint matches");
    }

    @Test
    void topicResolvesViaKafkaRoleForBothProducerAndConsumer() {
        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.TOPIC, "orders.events");

        assertThat(r.repos()).containsExactly("svc-orders", "svc-pricing");
        assertThat(r.matches()).extracting(EntityMatch::repo, EntityMatch::source)
                .containsExactlyInAnyOrder(tuple("svc-orders", "kafka_role"), tuple("svc-pricing", "kafka_role"));
        assertThat(r.matches()).extracting(EntityMatch::detail)
                .containsExactlyInAnyOrder("PRODUCER", "CONSUMER");
    }

    @Test
    void topicMissReturnsMissReason() {
        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.TOPIC, "no.topic");

        assertThat(r.isEmpty()).isTrue();
        assertThat(KbEntities.missReason(EntityKind.TOPIC)).isEqualTo("no known topic with roles");
    }

    @Test
    void classResolvesBySimpleNameDotlessBranch() {
        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.CLASS, "LoyaltyTier");

        assertThat(r.repos()).containsExactly("lib-core");
        assertThat(r.matches()).extracting(EntityMatch::repo, EntityMatch::detail, EntityMatch::source)
                .containsExactly(tuple("lib-core", "com.acme.pricing.LoyaltyTier", "java_type"));
    }

    @Test
    void classResolvesByFullyQualifiedName() {
        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.CLASS, "com.acme.pricing.LoyaltyTier");

        assertThat(r.repos()).containsExactly("lib-core");
    }

    @Test
    void classMissReturnsMissReason() {
        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.CLASS, "Ghost");

        assertThat(r.isEmpty()).isTrue();
        assertThat(KbEntities.missReason(EntityKind.CLASS)).isEqualTo("no such type in the knowledge base");
    }

    @Test
    void artifactResolvesByGroupColonName() {
        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.ARTIFACT, "com.acme:lib-core");

        assertThat(r.repos()).containsExactly("lib-core");
        assertThat(r.matches()).extracting(EntityMatch::repo, EntityMatch::detail, EntityMatch::source)
                .containsExactly(tuple("lib-core", "com.acme:lib-core", "artifact"));
    }

    @Test
    void artifactValueWithNoColonResolvesEmpty() {
        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.ARTIFACT, "com.acme");

        assertThat(r.isEmpty()).isTrue();
        assertThat(r.repos()).isEmpty();
    }

    @Test
    void artifactMissReturnsMissReason() {
        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.ARTIFACT, "com.acme:ghost");

        assertThat(r.isEmpty()).isTrue();
        assertThat(KbEntities.missReason(EntityKind.ARTIFACT)).isEqualTo("not linked to any indexed module");
    }

    // NOTE: artifact has UNIQUE(grp, name) (V1__init.sql), so a single grp:name coordinate can
    // resolve to at most one module/repo under the current schema — the added ORDER BY r.name
    // (called out in the report) is a defensive determinism fix, not something observably
    // reproducible with a two-row fixture without violating that constraint.

    @Test
    void repoOfModuleReturnsOwningRepoName() {
        assertThat(KbEntities.repoOfModule(db.jdbi(), 3L)).isEqualTo("lib-core");
    }

    @Test
    void repoOfModuleReturnsNullForUnknownModule() {
        assertThat(KbEntities.repoOfModule(db.jdbi(), 999L)).isNull();
    }

    @Test
    void repoNamesListsAllReposSorted() {
        assertThat(KbEntities.repoNames(db.jdbi())).containsExactly("lib-core", "svc-orders", "svc-pricing");
    }

    @Test
    void topicNamesListsAllTopics() {
        assertThat(KbEntities.topicNames(db.jdbi())).containsExactly("orders.events");
    }
}
