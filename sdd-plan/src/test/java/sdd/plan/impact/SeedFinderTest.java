package sdd.plan.impact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.retrieve.FtsRetriever;
import sdd.core.retrieve.FtsSymbolWriter;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.Touchpoint;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class SeedFinderTest {
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
            h.execute("INSERT INTO config_property(module_id, key, value, profile, source_file) "
                    + "VALUES (1,'pricing.tier.refresh-interval','30s',NULL,'src/main/resources/application.yml')");
            FtsSymbolWriter.insert(h, 3L, "LoyaltyTier", "com.acme.pricing.LoyaltyTier", "");
        });
    }

    private static NormalizedSpec spec(List<Touchpoint> touchpoints, List<SpecItem> requirements) {
        return new NormalizedSpec("S-1", "T", "o", "draft", "G.", "",
                requirements, List.of(new SpecItem("A1", "acc")),
                List.of(), touchpoints, List.of(), List.of(), List.of());
    }

    @Test
    void resolvesEveryTouchpointKindWithProvenance() {
        NormalizedSpec s = spec(List.of(
                        new Touchpoint(Touchpoint.Kind.REPO, "svc-pricing"),
                        new Touchpoint(Touchpoint.Kind.ENDPOINT, "GET /price/1"),
                        new Touchpoint(Touchpoint.Kind.TOPIC, "orders.events"),
                        new Touchpoint(Touchpoint.Kind.CLASS, "LoyaltyTier"),
                        new Touchpoint(Touchpoint.Kind.ARTIFACT, "com.acme:lib-core")),
                List.of(new SpecItem("R1", "tier pricing")));

        SeedFinder.SeedScan scan = SeedFinder.find(db.jdbi(), new FtsRetriever(db.jdbi()), s);

        assertThat(scan.problems()).isEmpty();
        assertThat(scan.seeds()).extracting(Seed::repo, Seed::source, Seed::detail).contains(
                tuple("svc-pricing", "touchpoint", "repo:svc-pricing"),
                tuple("svc-pricing", "touchpoint", "endpoint:GET /price/1"),
                tuple("svc-orders", "touchpoint", "topic:orders.events"),
                tuple("svc-pricing", "touchpoint", "topic:orders.events"),
                tuple("lib-core", "touchpoint", "class:LoyaltyTier"),
                tuple("lib-core", "touchpoint", "artifact:com.acme:lib-core"));
        // lib-core is already a touchpoint seed, so the R1 FTS hit must NOT re-appear as a candidate
        assertThat(scan.candidates()).isEmpty();
    }

    @Test
    void aClassTouchpointRecordsTheResolvedTypeAsAnAnchor() {
        // The anchor set is what lets the closure ask "does this consumer use the thing that
        // CHANGED" instead of "does it use anything at all". It must be the resolved fqcn, not the
        // spelling the spec used, because the spec may write a bare simple name.
        NormalizedSpec s = spec(List.of(new Touchpoint(Touchpoint.Kind.CLASS, "LoyaltyTier")), List.of());

        SeedFinder.SeedScan scan = SeedFinder.find(db.jdbi(), new FtsRetriever(db.jdbi()), s);

        assertThat(scan.anchorTypes()).containsExactly("com.acme.pricing.LoyaltyTier");
    }

    @Test
    void nonTypeTouchpointsContributeNoAnchors() {
        // An endpoint or a repo is not a type; anchoring on one would silently narrow the
        // annotation against a set that cannot match any api_usage row.
        NormalizedSpec s = spec(List.of(new Touchpoint(Touchpoint.Kind.REPO, "svc-pricing")), List.of());

        SeedFinder.SeedScan scan = SeedFinder.find(db.jdbi(), new FtsRetriever(db.jdbi()), s);

        assertThat(scan.anchorTypes()).isEmpty();
    }

    @Test
    void aConfigTouchpointSeedsTheRepoDeclaringTheProperty() {
        // config_property has been written by sdd index since V1 and read by nothing. A human task
        // naming a property key could not reach the affected set at all before this kind existed.
        NormalizedSpec s = spec(List.of(
                new Touchpoint(Touchpoint.Kind.CONFIG, "pricing.tier.refresh-interval")), List.of());

        SeedFinder.SeedScan scan = SeedFinder.find(db.jdbi(), new FtsRetriever(db.jdbi()), s);

        assertThat(scan.seeds()).extracting(Seed::repo, Seed::source)
                .containsExactly(tuple("svc-pricing", "touchpoint"));
        assertThat(scan.problems()).isEmpty();
        // A config key is not a type, so it contributes no anchor — anchors come off java_type rows.
        assertThat(scan.anchorTypes()).isEmpty();
    }

    @Test
    void anUnknownConfigKeyBecomesABlockingProblemRatherThanAGuess() {
        NormalizedSpec s = spec(List.of(
                new Touchpoint(Touchpoint.Kind.CONFIG, "no.such.key")), List.of());

        SeedFinder.SeedScan scan = SeedFinder.find(db.jdbi(), new FtsRetriever(db.jdbi()), s);

        assertThat(scan.seeds()).isEmpty();
        assertThat(scan.problems()).singleElement().asString()
                .contains("config:no.such.key").contains("no indexed config property");
    }

    @Test
    void verblessEndpointTouchpointMatchesAnyVerb() {
        NormalizedSpec s = spec(List.of(new Touchpoint(Touchpoint.Kind.ENDPOINT, "/price/1")),
                List.of());

        SeedFinder.SeedScan scan = SeedFinder.find(db.jdbi(), new FtsRetriever(db.jdbi()), s);

        assertThat(scan.problems()).isEmpty();
        assertThat(scan.seeds()).singleElement().satisfies(x -> {
            assertThat(x.repo()).isEqualTo("svc-pricing");
            assertThat(x.detail()).isEqualTo("endpoint:/price/1");
        });
    }

    @Test
    void ftsCandidatesCarryRequirementProvenanceAndDedupe() {
        NormalizedSpec s = spec(List.of(),
                List.of(new SpecItem("R1", "loyalty tier pricing"), new SpecItem("R2", "loyalty tier")));

        SeedFinder.SeedScan scan = SeedFinder.find(db.jdbi(), new FtsRetriever(db.jdbi()), s);

        assertThat(scan.seeds()).isEmpty();
        assertThat(scan.candidates()).singleElement().satisfies(c -> {
            assertThat(c.repo()).isEqualTo("lib-core");
            assertThat(c.source()).isEqualTo("fts");
            assertThat(c.detail()).isEqualTo("R1 hit: LoyaltyTier");
        });
    }

    @Test
    void unresolvableTouchpointsBecomeProblems() {
        NormalizedSpec s = spec(List.of(
                        new Touchpoint(Touchpoint.Kind.REPO, "ghost"),
                        new Touchpoint(Touchpoint.Kind.ENDPOINT, "DELETE /nope"),
                        new Touchpoint(Touchpoint.Kind.TOPIC, "no.topic"),
                        new Touchpoint(Touchpoint.Kind.CLASS, "Ghost"),
                        new Touchpoint(Touchpoint.Kind.ARTIFACT, "com.acme:ghost")),
                List.of(new SpecItem("R1", "req")));

        SeedFinder.SeedScan scan = SeedFinder.find(db.jdbi(), new FtsRetriever(db.jdbi()), s);

        assertThat(scan.seeds()).isEmpty();
        assertThat(scan.problems()).hasSize(5).allSatisfy(p -> assertThat(p).contains("touchpoint"));
        assertThat(scan.problems().get(0)).isEqualTo("touchpoint repo:ghost: no such repo in the knowledge base");
    }
}
