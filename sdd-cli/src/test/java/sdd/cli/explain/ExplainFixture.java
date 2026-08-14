package sdd.cli.explain;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import sdd.core.retrieve.FtsSymbolWriter;

/**
 * The six-repo estate shared by every {@code sdd explain} task (3-8): sdd-plan's
 * {@code ClosureTest} fixture (lib-core &lt;- lib-api &lt;- svc-orders Gradle chain, a BOM
 * declaration site, svc-billing's REST call into svc-orders, svc-notify's Kafka consumption of a
 * topic svc-orders produces, and svc-orders' {@code api_usage} reference into lib-api), extended
 * with {@code repo_card} rows and {@code fts_symbol} entries via {@link FtsSymbolWriter}'s sole
 * write path. Kept as raw {@code h.execute("INSERT ...")} — the idiom {@code ContractEdgesTest}
 * uses — rather than going through the indexer, so every task builds on the exact same rows.
 *
 * <p>Repo/module ids, in insertion order: 1 lib-core, 2 lib-api, 3 svc-orders, 4 svc-billing,
 * 5 svc-notify, 6 platform. Add to this fixture rather than copying it — Tasks 4-8 all depend on
 * it staying one definition.
 */
public final class ExplainFixture {
    public static final String LIB_CORE = "lib-core";
    public static final String LIB_API = "lib-api";
    public static final String SVC_ORDERS = "svc-orders";
    public static final String SVC_BILLING = "svc-billing";
    public static final String SVC_NOTIFY = "svc-notify";
    public static final String PLATFORM = "platform";

    /** The FQCN lib-api exposes and svc-orders references via {@code api_usage}. */
    public static final String PRICE_API_FQCN = "com.acme.api.PriceApi";

    /** The endpoint svc-orders exposes and svc-billing calls. */
    public static final String ORDERS_ENDPOINT = "GET /orders/{id}";

    /** The topic svc-orders produces and svc-notify consumes. */
    public static final String ORDERS_TOPIC = "orders.events";

    private ExplainFixture() {
    }

    public static void seed(Jdbi jdbi) {
        jdbi.useHandle(ExplainFixture::seed);
    }

    public static void seed(Handle h) {
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
        // api_usage: svc-orders code references lib-api types (code change likely)
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

        // repo_card: one generated-summary row per repo, so every repo is describable.
        for (String repo : new String[] {LIB_CORE, LIB_API, SVC_ORDERS, SVC_BILLING, SVC_NOTIFY, PLATFORM}) {
            h.createUpdate("INSERT INTO repo_card(repo_id, card_md, card_line, model, input_hash, created_at) "
                            + "SELECT id, '# ' || :repo || char(10) || char(10) || 'Generated summary.', "
                            + ":repo || ' summary line.', 'test-model', 'h', '2026-08-14T00:00:00Z' "
                            + "FROM repo WHERE name = :repo")
                    .bind("repo", repo).execute();
        }

        // fts_symbol: the sole write path, one symbol per side of the FQCN cross-repo reference.
        FtsSymbolWriter.insert(h, 2, "PriceApi", PRICE_API_FQCN);
        FtsSymbolWriter.insert(h, 3, "OrdersController", "OrdersController");
    }
}
