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
 * the specific matched row (citation detail, previously consumed by the now-deleted explain
 * command).
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
            // A TypeScript export whose SIMPLE name is the same as the Java type's — the exact
            // collision the two kinds exist to keep apart.
            h.execute("INSERT INTO java_type(module_id, fqcn, kind, language) "
                    + "VALUES (3,'@acme/web-sdk.LoyaltyTier','INTERFACE','TYPESCRIPT')");
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

    // --- exact-match-first endpoint resolution (over-matching fix) ---

    private void seedCandlesCollisionPair() {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (1,'CandlesController','symbols','GET','/candles/{id}/symbols','/candles/{}/symbols')");
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (1,'CandlesController','get','GET','/candles/{id}/{other}','/candles/{}/{}')");
        });
    }

    private void seedHealthNullAndGetRows() {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (1,'HealthController','any',NULL,'/health','/health')");
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (1,'HealthController','get','GET','/health','/health')");
        });
    }

    /**
     * The live-estate regression: trading-candles exposes both GET /api/candles/{}/symbols and
     * GET /api/candles/{}/{}. templatesMatch treats {} as a wildcard on both sides, so naming the
     * first also matched the second under the old fuzzy-only resolution. Naming an endpoint by
     * the exact spelling the KB itself offers must resolve back to that one row.
     */
    @Test
    void namingOneOfTwoCollidingTemplatedEndpointsResolvesExactlyOne() {
        seedCandlesCollisionPair();

        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.ENDPOINT, "GET /candles/{}/symbols");

        assertThat(r.matches()).extracting(EntityMatch::detail)
                .containsExactly("GET /candles/{}/symbols");
    }

    @Test
    void explicitAnyVerbResolvesToNullMethodRowOnly() {
        seedHealthNullAndGetRows();

        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.ENDPOINT, "ANY /health");

        assertThat(r.matches()).extracting(EntityMatch::detail)
                .containsExactly("ANY /health");
    }

    /**
     * Pins the behavior the fix must not break: an omitted verb is not the same thing as an
     * explicit ANY, and must still resolve every http_method at the exact path, including the
     * NULL-method row.
     */
    @Test
    void omittedVerbStillResolvesEveryVerbAtExactPath() {
        seedHealthNullAndGetRows();

        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.ENDPOINT, "/health");

        assertThat(r.matches()).extracting(EntityMatch::detail)
                .containsExactlyInAnyOrder("ANY /health", "GET /health");
    }

    /**
     * A literal path with no exact-match row must still fall back to the existing fuzzy
     * templatesMatch/verbsCompatible scan, unchanged.
     */
    @Test
    void literalPathWithNoExactMatchFallsBackToFuzzyTemplateMatch() {
        db.jdbi().useHandle(h -> h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                + "VALUES (1,'CandlesController','symbols','GET','/candles/{id}/symbols','/candles/{}/symbols')"));

        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.ENDPOINT, "GET /candles/7/symbols");

        assertThat(r.matches()).extracting(EntityMatch::detail)
                .containsExactly("GET /candles/{}/symbols");
    }

    /**
     * Round-trip invariant, driven from endpointLabels' own output so the two cannot drift
     * apart: every label the KB offers as a name must resolve back to exactly the row that
     * produced it. Fixture covers a {}-templated collision pair (also the over-matching repro
     * in the other direction), a NULL-method row, and its GET sibling at the same path.
     */
    @Test
    void everyEndpointLabelRoundTripsToExactlyItsOwnRow() {
        seedCandlesCollisionPair();
        seedHealthNullAndGetRows();

        for (String label : KbEntities.endpointLabels(db.jdbi())) {
            Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.ENDPOINT, label);

            assertThat(r.matches())
                    .as("round-trip for label '%s'", label)
                    .extracting(EntityMatch::detail)
                    .containsExactly(label);
        }
    }

    @Test
    void endpointValueMatchingNothingStillReturnsEmptyResolutionWithUnchangedMissReason() {
        seedCandlesCollisionPair();
        seedHealthNullAndGetRows();

        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.ENDPOINT, "DELETE /nope");

        assertThat(r.isEmpty()).isTrue();
        assertThat(r.matches()).isEmpty();
        assertThat(KbEntities.missReason(EntityKind.ENDPOINT)).isEqualTo("no endpoint matches");
    }

    @Test
    void aSymbolResolvesByTheSpecifierAConsumerImports() {
        Resolution r = KbEntities.resolve(db.jdbi(), EntityKind.SYMBOL, "@acme/web-sdk.LoyaltyTier");

        assertThat(r.matches()).extracting(EntityMatch::repo, EntityMatch::detail)
                .containsExactly(tuple("lib-core", "@acme/web-sdk.LoyaltyTier"));
    }

    @Test
    void aBareNameNeverResolvesToASymbol() {
        // CLASS resolves a dotless name by suffix because a Java package is a convention every
        // Java name follows. A module specifier is not a package path — it is the string the
        // consumer literally typed — so a suffix match would pair a name with whatever package
        // happened to end the same way.
        assertThat(KbEntities.resolve(db.jdbi(), EntityKind.SYMBOL, "LoyaltyTier").matches())
                .isEmpty();
    }

    @Test
    void theTwoLanguagesNeverAnswerForEachOther() {
        // The whole reason SYMBOL is a separate kind: one bare name resolving to both a Java type
        // and a TypeScript export would be a silent cross-language conflation inside a citation,
        // which is the one place a reader trusts absolutely.
        assertThat(KbEntities.resolve(db.jdbi(), EntityKind.CLASS, "LoyaltyTier").matches())
                .extracting(EntityMatch::detail)
                .containsExactly("com.acme.pricing.LoyaltyTier");
        assertThat(KbEntities.resolve(db.jdbi(), EntityKind.CLASS, "@acme/web-sdk.LoyaltyTier")
                .matches()).isEmpty();
    }
}
