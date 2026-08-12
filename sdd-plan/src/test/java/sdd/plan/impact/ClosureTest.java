package sdd.plan.impact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ClosureTest {
    @TempDir Path ws;
    private Database db;

    // Estate: lib-core <- lib-api <- svc-orders (chain), svc-billing calls svc-orders' endpoint,
    // svc-notify consumes topic produced by svc-orders, platform-bom manages svc-orders' lib-api pin.
    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");      // repo 1, module 1
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-api','/w/2','LIBRARY')");        // repo 2, module 2
            h.execute("INSERT INTO repo(name, path, kind, gradle_status) VALUES ('svc-orders','/w/3','SERVICE','DEGRADED')"); // repo 3, module 3
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-billing','/w/4','SERVICE')");    // repo 4, module 4
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-notify','/w/5','SERVICE')");     // repo 5, module 5
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('platform','/w/6','LIBRARY')");       // repo 6, module 6
            for (int i = 1; i <= 6; i++) {
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (" + i + ",':','UNKNOWN')");
            }
            // internal dep edges: consumer module -> provider module
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");        // lib-api -> lib-core
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (3,'com.acme','lib-api','compileClasspath',NULL,'BOM','BOM_MANAGED',1,2)");        // svc-orders -> lib-api (BOM managed)
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (3,'com.acme','acme-platform-bom','compileClasspath','2.0','DIRECT','PINNED',1,6)"); // svc-orders -> platform (the BOM)
            // api_usage: svc-orders code references lib-api types (code change likely); lib-api does NOT reference lib-core types (bump only)
            h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (2,'com.acme.api.PriceApi','CLASS')");
            h.execute("INSERT INTO api_usage(from_module_id, target_fqcn, target_module_id, ref_kind) VALUES (3,'com.acme.api.PriceApi',2,'IMPORT')");
            // REST: svc-billing's client calls svc-orders' endpoint
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (3,'OrdersController','get','GET','/orders/{id}','/orders/{}')");
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (4,'FEIGN','OrdersClient','site','GET','/orders/{id}','/orders/{}','orders','LITERAL','raw')");
            h.execute("INSERT INTO rest_call_edge(client_id, endpoint_id, confidence, matched_by) VALUES (1,1,'HIGH','FEIGN_NAME_PATH')");
            // Kafka: svc-orders produces orders.events, svc-notify consumes it
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('orders.events','LITERAL')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (3,1,'PRODUCER')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (5,1,'CONSUMER')");
        });
    }

    @Test
    void expandsTransitivelyWithAnnotationsContractsAndBomSites() {
        Closure.Expansion expansion = Closure.expand(db.jdbi(), Set.of("lib-core"));

        assertThat(expansion.added()).extracting(AffectedRepo::repo, AffectedRepo::role, AffectedRepo::annotation)
                .containsExactly(
                        tuple("lib-api", "dependent", "BUMP_REBUILD_ONLY"),
                        tuple("svc-orders", "dependent", "CODE_CHANGE_LIKELY"),
                        tuple("platform", "bom-site", "BOM_DECLARATION_SITE"),
                        tuple("svc-billing", "contract", "PENDING_CONTRACT"),
                        tuple("svc-notify", "contract", "PENDING_CONTRACT"));
        AffectedRepo billing = expansion.added().stream()
                .filter(a -> a.repo().equals("svc-billing")).findFirst().orElseThrow();
        assertThat(billing.reasons()).singleElement().asString()
                .contains("calls GET /orders/{}").contains("svc-orders").contains("HIGH");
        assertThat(expansion.warnings()).anySatisfy(w ->
                assertThat(w).contains("svc-orders").contains("DEGRADED"));
        assertThat(expansion.cycles()).isEmpty();
    }

    @Test
    void cyclesAmongAffectedReposAreReportedAsOneUnit() {
        db.jdbi().useHandle(h -> {
            // make lib-core depend back on lib-api: a 2-repo cycle
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (1,'com.acme','lib-api','compileClasspath','1.0','DIRECT','PINNED',1,2)");
        });

        Closure.Expansion expansion = Closure.expand(db.jdbi(), Set.of("lib-core"));

        assertThat(expansion.cycles()).singleElement().asString()
                .contains("lib-api").contains("lib-core");
        assertThat(expansion.warnings()).anySatisfy(w -> assertThat(w).contains("co-scheduled"));
    }

    @Test
    void multipleProvidersMergeReasonsOnOneDependent() {
        // svc-orders consumes BOTH roots: lib-api (BOM_MANAGED) and platform (PINNED) — one
        // AffectedRepo, two reasons, no duplicate row
        Closure.Expansion expansion = Closure.expand(db.jdbi(), Set.of("lib-api", "platform"));

        assertThat(expansion.added()).extracting(AffectedRepo::repo).containsOnlyOnce("svc-orders");
        AffectedRepo orders = expansion.added().stream()
                .filter(a -> a.repo().equals("svc-orders")).findFirst().orElseThrow();
        assertThat(orders.reasons()).containsExactlyInAnyOrder(
                "depends on lib-api (BOM_MANAGED)", "depends on platform (PINNED)");
    }

    @Test
    void contractReasonAppendsToAnAlreadyAffectedDependent() {
        // give svc-billing a direct Gradle dependency on lib-core: it becomes a dependent AND
        // a REST contract consumer of svc-orders — one row, role stays dependent, both reasons
        db.jdbi().useHandle(h -> h.execute(
                "INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (4,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)"));

        Closure.Expansion expansion = Closure.expand(db.jdbi(), Set.of("lib-core"));

        assertThat(expansion.added()).extracting(AffectedRepo::repo).containsOnlyOnce("svc-billing");
        AffectedRepo billing = expansion.added().stream()
                .filter(a -> a.repo().equals("svc-billing")).findFirst().orElseThrow();
        assertThat(billing.role()).isEqualTo("dependent");
        assertThat(billing.reasons()).hasSize(2)
                .anySatisfy(r -> assertThat(r).contains("depends on lib-core"))
                .anySatisfy(r -> assertThat(r).contains("calls GET /orders/{}"));
    }

    @Test
    void missingBomSiteProducesAWarningNotSilence() {
        db.jdbi().useHandle(h -> h.execute("DELETE FROM dep_edge WHERE to_name = 'acme-platform-bom'"));

        Closure.Expansion expansion = Closure.expand(db.jdbi(), Set.of("lib-core"));

        assertThat(expansion.added()).extracting(AffectedRepo::repo).doesNotContain("platform");
        assertThat(expansion.warnings()).anySatisfy(w ->
                assertThat(w).contains("BOM_MANAGED").contains("svc-orders").contains("declaration site not identifiable"));
    }
}
