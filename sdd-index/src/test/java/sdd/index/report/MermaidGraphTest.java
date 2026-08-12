package sdd.index.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MermaidGraphTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-orders','/w/2','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('tools.misc','/w/3','UNKNOWN')");
            for (int i = 1; i <= 3; i++) {
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (" + i + ",':','UNKNOWN')");
            }
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (2,'OrdersController','get','GET','/orders/{id}','/orders/{}')");
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (3,'FEIGN','OrdersClient','site','GET','/orders/{id}','/orders/{}','orders','LITERAL','raw')");
            h.execute("INSERT INTO rest_call_edge(client_id, endpoint_id, confidence, matched_by) VALUES (1,1,'HIGH','FEIGN_NAME_PATH')");
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('orders.events','LITERAL')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (2,1,'PRODUCER')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (1,1,'CONSUMER')");
        });
    }

    @Test
    void rendersNodesByKindAndAllThreeEdgeTypesDeterministically() {
        String first = MermaidGraph.render(db.jdbi());
        String second = MermaidGraph.render(db.jdbi());

        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("graph LR\n");
        assertThat(first)
                .contains("  classDef service ")
                .contains("  lib_core[\"lib-core\"]:::library\n")
                .contains("  svc_orders[\"svc-orders\"]:::service\n")
                .contains("  tools_misc[\"tools.misc\"]:::unknown\n")
                .contains("  svc_orders -->|PINNED| lib_core\n")
                .contains("  tools_misc -.->|REST HIGH| svc_orders\n")
                .contains("  svc_orders ==>|orders.events| lib_core\n");
        // ordering: all nodes before all edges; gradle before rest before kafka
        assertThat(first.indexOf("tools_misc[\"")).isLessThan(first.indexOf(" -->|"));
        assertThat(first.indexOf(" -->|")).isLessThan(first.indexOf(" -.->|"));
        assertThat(first.indexOf(" -.->|")).isLessThan(first.indexOf(" ==>|"));
    }

    @Test
    void sanitizedIdCollisionsGetNumericSuffixes() {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('tools-misc','/w/4','LIBRARY')");
        });

        String md = MermaidGraph.render(db.jdbi());

        // 'tools-misc' < 'tools.misc' alphabetically: tools-misc keeps tools_misc,
        // tools.misc gets tools_misc_2
        assertThat(md).contains("  tools_misc[\"tools-misc\"]:::library\n")
                .contains("  tools_misc_2[\"tools.misc\"]:::unknown\n")
                .contains("  tools_misc_2 -.->|REST HIGH| svc_orders\n");
    }

    @Test
    void pipeInEdgeLabelsIsSanitized() {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('weird|topic','LITERAL')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (2,2,'PRODUCER')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (1,2,'CONSUMER')");
        });

        String md = MermaidGraph.render(db.jdbi());

        assertThat(md).contains("  svc_orders ==>|weird/topic| lib_core\n")
                .doesNotContain("weird|topic");
    }

    @Test
    void emptyKbRendersHeaderAndClassDefsOnly() {
        try (Database empty = Database.open(ws.resolve("empty-ws"))) {
            String md = MermaidGraph.render(empty.jdbi());

            assertThat(md).startsWith("graph LR\n");
            assertThat(md.lines().filter(l -> l.contains("[\""))).isEmpty();
            assertThat(md).doesNotContain("-->").doesNotContain("-.->").doesNotContain("==>");
        }
    }
}
