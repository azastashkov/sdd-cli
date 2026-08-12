package sdd.index.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.ManualEdge;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RestMatcherTest {
    @TempDir Path ws;
    private Database db;
    private long ordersModule;
    private long billingModule;
    private long inventoryModule;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-orders','/w/o','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('billing-service','/w/b','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('inventory','/w/i','SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind, spring_app_name) VALUES (1,':','SERVICE',NULL)");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind, spring_app_name) VALUES (2,':','SERVICE','billing')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind, spring_app_name) VALUES (3,':','SERVICE','inventory')");
            ordersModule = 1; billingModule = 2; inventoryModule = 3;
        });
    }

    private long client(long moduleId, String kind, String verb, String norm, String hint, String uri) {
        return db.jdbi().withHandle(h -> {
            h.createUpdate("""
                    INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method,
                                            uri_template, norm_path, target_hint, resolution, raw_expr)
                    VALUES (:m,:k,'C','site',:v,:u,:n,:t,'LITERAL','raw')""")
                    .bind("m", moduleId).bind("k", kind).bind("v", verb)
                    .bind("u", uri).bind("n", norm).bind("t", hint).execute();
            return h.createQuery("SELECT last_insert_rowid()").mapTo(Long.class).one();
        });
    }

    private long endpoint(long moduleId, String verb, String norm) {
        return db.jdbi().withHandle(h -> {
            h.createUpdate("""
                    INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method,
                                              path_template, norm_path)
                    VALUES (:m,'E','handler',:v,:n,:n)""")
                    .bind("m", moduleId).bind("v", verb).bind("n", norm).execute();
            return h.createQuery("SELECT last_insert_rowid()").mapTo(Long.class).one();
        });
    }

    private List<Map<String, Object>> edges() {
        return db.jdbi().withHandle(h -> h.createQuery(
                "SELECT client_id, endpoint_id, confidence, matched_by FROM rest_call_edge "
                        + "ORDER BY client_id, endpoint_id").mapToMap().list());
    }

    @Test
    void feignNameMatchIsHighUniqueIsMediumAmbiguousIsLowPerCandidate() {
        long feign = client(ordersModule, "FEIGN", "POST", "/pay/charge", "billing", "/pay/charge");
        long epBilling = endpoint(billingModule, "POST", "/pay/charge");
        endpoint(inventoryModule, "POST", "/pay/charge");   // same path elsewhere — name match must win

        long unique = client(ordersModule, "RESTTEMPLATE", "GET", "/stock/{}", null, "/stock/{id}");
        long epStock = endpoint(inventoryModule, "GET", "/stock/{}");

        client(ordersModule, "RESTTEMPLATE", "GET", "/health", null, "/health");
        endpoint(billingModule, "GET", "/health");
        endpoint(inventoryModule, "GET", "/health");        // ambiguous: LOW × 2

        RestMatcher.Report report = RestMatcher.match(db.jdbi(), List.of());

        assertThat(report.high()).isEqualTo(1);
        assertThat(report.medium()).isEqualTo(1);
        assertThat(report.low()).isEqualTo(2);
        // jdbi's mapToMap() surfaces SQLite INTEGER columns as Integer, not Long, so the ids
        // captured above (Long, from last_insert_rowid()) are narrowed here to match — same
        // convention UsageLinkerTest uses for target_module_id.
        assertThat(edges()).anySatisfy(e -> {
            assertThat(e).containsEntry("client_id", (int) feign).containsEntry("endpoint_id", (int) epBilling)
                    .containsEntry("confidence", "HIGH").containsEntry("matched_by", "FEIGN_NAME_PATH");
        });
        assertThat(edges()).anySatisfy(e -> {
            assertThat(e).containsEntry("client_id", (int) unique).containsEntry("endpoint_id", (int) epStock)
                    .containsEntry("confidence", "MEDIUM").containsEntry("matched_by", "UNIQUE_PATH");
        });
    }

    @Test
    void absoluteUrlAndNullNormClientsAreSkippedAndRematchIsWholesale() {
        client(ordersModule, "RESTTEMPLATE", "GET", "/http:/ext/x", null, "http://ext/x"); // absolute
        client(ordersModule, "RESTTEMPLATE", "GET", null, null, null);                     // DYNAMIC
        endpoint(billingModule, "GET", "/http:/ext/x");

        assertThat(RestMatcher.match(db.jdbi(), List.of()).high()
                + RestMatcher.match(db.jdbi(), List.of()).medium()
                + RestMatcher.match(db.jdbi(), List.of()).low()).isZero();
        assertThat(edges()).isEmpty();   // wholesale rebuild left no residue from the 3 runs
    }

    @Test
    void manualEdgePinsAndUnmatchedManualEdgeWarns() {
        long cl = client(ordersModule, "RESTTEMPLATE", "POST", "/pay/charge", null, "/pay/charge");
        long epB = endpoint(billingModule, "POST", "/pay/charge");
        endpoint(inventoryModule, "POST", "/pay/charge");   // would be LOW without the pin

        RestMatcher.Report report = RestMatcher.match(db.jdbi(), List.of(
                new ManualEdge("svc-orders", "POST", "/pay/charge", "billing-service"),
                new ManualEdge("ghost-repo", "GET", "/nope", "billing-service")));

        assertThat(report.manual()).isEqualTo(1);
        assertThat(report.warnings()).anySatisfy(w -> assertThat(w).contains("ghost-repo"));
        assertThat(edges()).anySatisfy(e -> {
            assertThat(e).containsEntry("client_id", (int) cl).containsEntry("endpoint_id", (int) epB)
                    .containsEntry("confidence", "HIGH").containsEntry("matched_by", "MANUAL");
        });
    }

    @Test
    void templateAndVerbHelpers() {
        assertThat(RestMatcher.templatesMatch("/a/{}/c", "/a/{}/c")).isTrue();
        assertThat(RestMatcher.templatesMatch("/a/42/c", "/a/{}/c")).isTrue();
        assertThat(RestMatcher.templatesMatch("/a/{}/c", "/a/b")).isFalse();
        assertThat(RestMatcher.verbsCompatible("ANY", "GET")).isTrue();
        assertThat(RestMatcher.verbsCompatible("GET", "ANY")).isTrue();
        assertThat(RestMatcher.verbsCompatible("GET", "POST")).isFalse();
    }

    @Test
    void manualPinDecrementsCountersForReplacedEdges() {
        client(ordersModule, "RESTTEMPLATE", "POST", "/pay/charge", null, "/pay/charge");
        endpoint(billingModule, "POST", "/pay/charge");
        endpoint(inventoryModule, "POST", "/pay/charge");   // ambiguous: LOW × 2 before the pin

        RestMatcher.Report report = RestMatcher.match(db.jdbi(), List.of(
                new ManualEdge("svc-orders", "POST", "/pay/charge", "billing-service")));

        // the pin deleted the svc-orders→billing LOW row and replaced it with HIGH/MANUAL;
        // the svc-orders→inventory LOW row survives — counters must match surviving rows
        assertThat(report.low()).isEqualTo(1);
        assertThat(report.manual()).isEqualTo(1);
        Integer lowRows = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT count(*) FROM rest_call_edge WHERE confidence='LOW'").mapTo(Integer.class).one());
        assertThat(lowRows).isEqualTo(report.low());
    }

    @Test
    void duplicateManualPinsDoNotDistortCounters() {
        long cl = client(ordersModule, "RESTTEMPLATE", "POST", "/pay/charge", null, "/pay/charge");
        long ep = endpoint(billingModule, "POST", "/pay/charge");

        // two overlapping manual edges resolve to the same (client, endpoint) pair
        RestMatcher.Report report = RestMatcher.match(db.jdbi(), List.of(
                new ManualEdge("svc-orders", "POST", "/pay/charge", "billing-service"),
                new ManualEdge("svc-orders", "POST", "/pay/charge", "billing-service")));

        // second pin replaces the first pin's MANUAL row: manual must be net 1, high must be
        // untouched (the pair was MEDIUM before the first pin: unique candidate)
        assertThat(report.manual()).isEqualTo(1);
        assertThat(report.high()).isZero();
        assertThat(report.medium()).isZero();
        assertThat(edges()).singleElement().satisfies(e -> {
            assertThat(e).containsEntry("client_id", (int) cl).containsEntry("endpoint_id", (int) ep)
                    .containsEntry("confidence", "HIGH").containsEntry("matched_by", "MANUAL");
        });
    }
}
